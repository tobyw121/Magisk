package com.topjohnwu.magisk.core.utils

import androidx.annotation.WorkerThread
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.ktx.getProperty
import com.topjohnwu.magisk.core.model.RuntimeDiagnostics
import com.topjohnwu.superuser.Shell
import timber.log.Timber

object RuntimeDiagnosticsCollector {
    @WorkerThread
    fun collect(shell: Shell): RuntimeDiagnostics = try {
        val probe = mutableListOf<String>()
        shell.newJob().add("app_diagnostics").to(probe).exec()
        val values = probe.mapNotNull {
            val index = it.indexOf('=')
            if (index < 0) null else it.substring(0, index) to it.substring(index + 1)
        }.toMap()
        val configured = if (shell.isRoot) Config.zygisk else null
        val log = mutableListOf<String>()
        if (shell.isRoot && !Info.isZygiskEnabled) {
            shell.newJob().add("tail -n 400 ${Const.MAGISK_LOG} 2>/dev/null").to(log).exec()
        }
        RuntimeDiagnostics.evaluate(
            rootGranted = shell.isRoot,
            binaryFound = values["BINARY_FOUND"] == "true",
            mountsFound = values["MOUNTS_FOUND"] == "true",
            daemonExitCode = values["DAEMON_CODE"]?.toIntOrNull() ?: -1,
            daemonVersion = values["DAEMON_VERSION"].orEmpty(),
            injected = Info.isZygiskEnabled,
            configured = configured,
            nativeBridge = getProperty("ro.dalvik.vm.native.bridge", ""),
            daemonError = values["DAEMON_ERROR"].orEmpty(),
            log = log,
        )
    } catch (e: Exception) {
        Timber.w(e, "Cannot collect runtime diagnostics")
        RuntimeDiagnostics()
    }
}
