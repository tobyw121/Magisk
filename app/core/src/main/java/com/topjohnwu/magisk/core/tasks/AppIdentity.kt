package com.topjohnwu.magisk.core.tasks

import android.content.Context
import android.content.Intent
import com.topjohnwu.magisk.core.BuildConfig.APP_PACKAGE_NAME
import com.topjohnwu.magisk.core.BuildConfig.APP_VERSION_CODE
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.utils.IdentityApk
import com.topjohnwu.superuser.Shell
import java.io.File
import java.security.SecureRandom

/** Full APK repackaging. Private signing keys never leave this process. */
object AppIdentity {
    private const val IDENTITY = "/data/adb/magisk_manager_identity"
    private const val ORIGINAL = "/data/adb/magisk_manager_original.apk"
    private val random = SecureRandom()

    fun isHidden(context: Context) = context.packageName != APP_PACKAGE_NAME

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun run(command: String) {
        val result = Shell.cmd(command).exec()
        check(result.isSuccess) {
            "App identity operation failed (${result.code}): " +
                (result.err + result.out).takeLast(5).joinToString("\n").take(2048)
        }
    }

    private fun letters(length: Int) = buildString {
        repeat(length) { append(('a'.code + random.nextInt(26)).toChar()) }
    }

    private fun launch(context: Context, pkg: String) {
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(pkg))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun hide(context: Context) {
        check(Const.USER_ID == 0 && !isHidden(context))
        // Probe the running daemon, not a potentially newer binary on disk.
        run("app_supports_identity")
        val pkg = "${letters(8)}.${letters(12)}"
        val label = letters(10).replaceFirstChar { it.uppercaseChar() }
        val apk = File.createTempFile("identity-", ".apk", context.cacheDir)
        val enrollment = File.createTempFile("identity-", ".txt", context.cacheDir)
        var installed = false
        var enrolled = false
        try {
            val cert = IdentityApk.create(
                File(context.applicationInfo.sourceDir), apk, APP_PACKAGE_NAME, pkg, label,
                APP_VERSION_CODE
            )
            val hex = cert.joinToString("") { "%02x".format(it.toInt() and 255) }
            enrollment.writeText("$pkg\n$hex\n")
            run("umask 077; cp ${quote(context.applicationInfo.sourceDir)} $ORIGINAL.new && " +
                "chown 0:0 $ORIGINAL.new && chmod 600 $ORIGINAL.new && mv -f $ORIGINAL.new $ORIGINAL")
            run("adb_pm_install ${quote(apk.path)} $pkg")
            installed = true
            run("umask 077; cp ${quote(enrollment.path)} $IDENTITY.new && " +
                "chown 0:0 $IDENTITY.new && chmod 600 $IDENTITY.new && mv -f $IDENTITY.new $IDENTITY")
            enrolled = true
            launch(context, pkg)
            // Original is removed only when the new app has acquired a root shell.
        } catch (e: Exception) {
            if (enrolled) Shell.cmd("rm -f $IDENTITY").exec()
            if (installed) Shell.cmd("pm uninstall --user 0 $pkg").exec()
            throw e
        } finally {
            apk.delete()
            enrollment.delete()
            Shell.cmd("rm -f $IDENTITY.new $ORIGINAL.new").exec()
        }
    }

    fun finishMigration(context: Context) {
        if (!isHidden(context) || Const.USER_ID != 0 || !Shell.getShell().isRoot) return
        val result = Shell.cmd("head -n 1 $IDENTITY").exec()
        if (result.isSuccess && result.out.firstOrNull() == context.packageName) {
            run("if pm path --user 0 $APP_PACKAGE_NAME | grep -q '^package:'; then pm uninstall --user 0 $APP_PACKAGE_NAME; fi")
        }
    }

    fun restore(context: Context) {
        check(Const.USER_ID == 0 && isHidden(context))
        run("adb_pm_install $ORIGINAL $APP_PACKAGE_NAME")
        run("rm -f $IDENTITY")
        launch(context, APP_PACKAGE_NAME)
        run("pm uninstall --user 0 ${quote(context.packageName)}")
    }
}
