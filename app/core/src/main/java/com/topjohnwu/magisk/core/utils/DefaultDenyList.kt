package com.topjohnwu.magisk.core.utils

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Process
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import android.widget.Toast

/** Seed each package once; the root-owned journal survives manager repackaging. */
object DefaultDenyList {
    private const val JOURNAL = "/data/adb/magisk_denylist_defaults"
    private val _revisions = kotlinx.coroutines.flow.MutableStateFlow(0)
    val revisions: kotlinx.coroutines.flow.StateFlow<Int> get() = _revisions
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val ENFORCED = "@enforcement-v1"

    fun eligible(pkg: String, applicationUid: Boolean, ownUid: Boolean): Boolean =
        applicationUid && !ownUid && pkg != BuildConfig.APP_PACKAGE_NAME

    fun shouldSeed(pkg: String, applicationUid: Boolean, ownUid: Boolean, seen: Set<String>): Boolean =
        eligible(pkg, applicationUid, ownUid) && pkg !in seen

    private fun execute(command: String): Shell.Result = Shell.cmd(command).exec()
    private fun checked(command: String): Shell.Result = execute(command).also {
        check(it.isSuccess) { "Default DenyList failed: ${(it.err + it.out).takeLast(5)}" }
    }

    // Called under DenyListStore.operations for explicit user edits as well.
    fun rememberChoice(pkg: String) {
        if (!pkg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) return
        checked("[ ! -L $JOURNAL ] && umask 077 && touch $JOURNAL && chown 0:0 $JOURNAL && chmod 600 $JOURNAL")
        if (pkg !in checked("cat $JOURNAL").out) checked("printf '%s\\n' '$pkg' >> $JOURNAL")
    }

    fun initialize(context: Context) {
        if (!running.compareAndSet(false, true)) return
        try {
            if (!Shell.getShell().isRoot || !Info.env.isActive) return
            runBlocking {
                DenyListStore.operations.withLock {
                    try {
                        checked("[ ! -L $JOURNAL ] && umask 077 && touch $JOURNAL && chown 0:0 $JOURNAL && chmod 600 $JOURNAL")
                        val seen = checked("cat $JOURNAL").out.toMutableSet()
                        val store = DenyListStore { cmd ->
                            val result = execute(cmd)
                            DenyListStore.Reply(result.code, result.out, result.err)
                        }
                        val pm = context.packageManager
                        var added = 0
                        var failures = 0
                        for (app in pm.getInstalledApplications(0)) {
                            val pkg = app.packageName
                            if (!shouldSeed(pkg, Process.isApplicationUid(app.uid), app.uid == Process.myUid(), seen)) continue
                            require(pkg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
                            val flags = PackageManager.MATCH_DISABLED_COMPONENTS or
                                PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
                            val info = try {
                                pm.getPackageInfo(pkg, flags)
                            } catch (e: Exception) {
                                Timber.e(e, "Cannot enumerate default DenyList package %s", pkg)
                                failures++
                                continue
                            }
                            val targets = linkedSetOf<DenyListStore.Target>()
                            fun regular(name: String?) {
                                targets += DenyListStore.Target(pkg, name ?: app.processName ?: pkg)
                            }
                            regular(app.processName)
                            info.activities.orEmpty().forEach { regular(it.processName) }
                            info.receivers.orEmpty().forEach { regular(it.processName) }
                            info.providers.orEmpty().forEach { regular(it.processName) }
                            info.services.orEmpty().forEach {
                                if (it.flags and ServiceInfo.FLAG_ISOLATED_PROCESS == 0) regular(it.processName)
                                else if (it.flags and ServiceInfo.FLAG_USE_APP_ZYGOTE != 0) {
                                    regular("${app.processName ?: pkg}_zygote")
                                } else targets += DenyListStore.Target("isolated", "${app.processName ?: pkg}:")
                            }
                            val result = store.change(targets, true)
                            if (!result.successful) {
                                Timber.e("Default DenyList not confirmed for %s: %s", pkg, result.diagnostics)
                                failures++
                                continue
                            }
                            // Validated package characters cannot inject shell syntax.
                            checked("printf '%s\\n' '$pkg' >> $JOURNAL")
                            check(pkg in checked("cat $JOURNAL").out)
                            seen += pkg
                            added++
                        }
                        // Enable once; subsequent manual disabling is respected.
                        if (ENFORCED !in seen) {
                            checked("magisk --denylist enable")
                            checked("magisk --denylist status")
                            com.topjohnwu.magisk.core.Config.denyList = true
                            checked("printf '%s\\n' '$ENFORCED' >> $JOURNAL")
                        }
                        if (added > 0) _revisions.value++
                        if (failures > 0) context.toast(R.string.denylist_defaults_failed, Toast.LENGTH_LONG)
                        else if (added > 0) {
                            Timber.i("Default DenyList: configured %d new packages", added)
                            context.toast(R.string.denylist_defaults_applied, Toast.LENGTH_LONG)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Default DenyList initialization incomplete; retrying on next app start")
                        context.toast(R.string.denylist_defaults_failed, Toast.LENGTH_LONG)
                    }
                }
            }
        } finally {
            running.set(false)
        }
    }
}
