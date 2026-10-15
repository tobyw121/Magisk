package com.topjohnwu.magisk.core.model

data class RuntimeDiagnostics(
    val root: Root = Root.UNKNOWN,
    val zygisk: Zygisk = Zygisk.UNKNOWN,
    val daemonVersion: String = "",
    val daemonExitCode: Int = -1,
    val binaryFound: Boolean = false,
    val mountsFound: Boolean = false,
    val nativeBridge: String = "",
    val daemonError: String = "",
    val zygiskIssue: ZygiskIssue? = null,
) {
    enum class Root { UNKNOWN, ACTIVE, ACCESS_UNAVAILABLE, DAEMON_UNREACHABLE, NOT_DETECTED }
    enum class Zygisk { UNKNOWN, LOADED, DISABLED, NOT_LOADED }
    enum class ZygiskIssue { SAFE_MODE, CRASH_ROLLBACK, JNI_HOOK, PLT_HOOK, RUNTIME_CALLBACKS }

    fun report() = """
        root=$root
        daemonVersion=$daemonVersion
        daemonExitCode=$daemonExitCode
        binaryFound=$binaryFound
        mountsFound=$mountsFound
        zygisk=$zygisk
        nativeBridge=$nativeBridge
        lastZygiskIssue=$zygiskIssue
        daemonError=$daemonError
    """.trimIndent()

    companion object {
        fun evaluate(
            rootGranted: Boolean,
            binaryFound: Boolean,
            mountsFound: Boolean,
            daemonExitCode: Int,
            daemonVersion: String,
            injected: Boolean,
            configured: Boolean?,
            nativeBridge: String = "",
            daemonError: String = "",
            log: List<String> = emptyList(),
        ): RuntimeDiagnostics {
            val reachable = daemonExitCode == 0 && daemonVersion.contains(":MAGISK:")
            val root = when {
                reachable && rootGranted -> Root.ACTIVE
                reachable -> Root.ACCESS_UNAVAILABLE
                daemonExitCode == 124 || daemonExitCode == 137 -> Root.DAEMON_UNREACHABLE
                binaryFound || mountsFound -> Root.DAEMON_UNREACHABLE
                daemonExitCode == 127 -> Root.NOT_DETECTED
                else -> Root.UNKNOWN
            }
            val zygisk = when {
                injected -> Zygisk.LOADED
                !reachable || !rootGranted || configured == null -> Zygisk.UNKNOWN
                !configured -> Zygisk.DISABLED
                else -> Zygisk.NOT_LOADED
            }
            val issue = if (zygisk == Zygisk.DISABLED) {
                if (log.any { "* Safe mode triggered" in it }) ZygiskIssue.SAFE_MODE else null
            } else if (zygisk == Zygisk.NOT_LOADED) {
                log.asReversed().firstNotNullOfOrNull { line ->
                    when {
                        "zygote crashed too many times, rolling-back" in line -> ZygiskIssue.CRASH_ROLLBACK
                        "Cannot hook method:" in line -> ZygiskIssue.JNI_HOOK
                        "plt_hook failed" in line -> ZygiskIssue.PLT_HOOK
                        "JavaVM not found" in line || "JNIEnv not found" in line -> ZygiskIssue.RUNTIME_CALLBACKS
                        else -> null
                    }
                }
            } else null
            return RuntimeDiagnostics(root, zygisk, daemonVersion, daemonExitCode,
                binaryFound, mountsFound, nativeBridge, daemonError, issue)
        }
    }
}
