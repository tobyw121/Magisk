package com.topjohnwu.magisk.core.model

import org.junit.Assert.*
import org.junit.Test

class RuntimeDiagnosticsTest {
    private fun probe(
        root: Boolean = false, binary: Boolean = true, mounts: Boolean = true,
        code: Int = 0, version: String = "31.0:MAGISK:R", injected: Boolean = false,
        configured: Boolean? = null, log: List<String> = emptyList(),
    ) = RuntimeDiagnostics.evaluate(root, binary, mounts, code, version, injected, configured, log = log)

    @Test fun reachableDaemonWithoutRootIsNotMissingInstallation() {
        val result = probe()
        assertEquals(RuntimeDiagnostics.Root.ACCESS_UNAVAILABLE, result.root)
        assertEquals(RuntimeDiagnostics.Zygisk.UNKNOWN, result.zygisk)
    }

    @Test fun survivingMountsWithMissingBinaryAreReported() {
        assertEquals(RuntimeDiagnostics.Root.DAEMON_UNREACHABLE,
            probe(binary = false, code = 127, version = "").root)
    }

    @Test fun noEvidenceDoesNotProveAbsence() {
        assertEquals(RuntimeDiagnostics.Root.NOT_DETECTED,
            probe(binary = false, mounts = false, code = 127, version = "").root)
        assertEquals(RuntimeDiagnostics.Root.UNKNOWN,
            probe(binary = false, mounts = false, code = -1, version = "").root)
    }

    @Test fun timeoutAndMalformedVersionDoNotReportActiveRoot() {
        assertEquals(RuntimeDiagnostics.Root.DAEMON_UNREACHABLE, probe(root = true, code = 124).root)
        assertEquals(RuntimeDiagnostics.Root.DAEMON_UNREACHABLE,
            probe(root = true, version = "permission denied").root)
    }

    @Test fun missingInjectionIsNotDisabledWhenConfigured() {
        val result = probe(root = true, configured = true)
        assertEquals(RuntimeDiagnostics.Root.ACTIVE, result.root)
        assertEquals(RuntimeDiagnostics.Zygisk.NOT_LOADED, result.zygisk)
    }

    @Test fun knownInjectionTakesPrecedenceOverMissingRootAndConfiguration() {
        assertEquals(RuntimeDiagnostics.Zygisk.LOADED, probe(injected = true).zygisk)
        assertEquals(RuntimeDiagnostics.Zygisk.DISABLED, probe(root = true, configured = false).zygisk)
    }

    @Test fun lastMatchingIssueWinsAndStaleErrorsDoNotOverrideLoadedState() {
        val log = listOf("zygisk64: Cannot hook method: nativeForkSystemServer signature",
            "zygote crashed too many times, rolling-back")
        assertEquals(RuntimeDiagnostics.ZygiskIssue.CRASH_ROLLBACK,
            probe(root = true, configured = true, log = log).zygiskIssue)
        assertNull(probe(root = true, configured = true, injected = true, log = log).zygiskIssue)
        assertNull(probe(root = true, configured = false, log = log).zygiskIssue)
    }
    @Test fun safeModeIsReportedOnlyWhenZygiskIsDisabled() {
        val log = listOf("* Safe mode triggered")
        assertEquals(RuntimeDiagnostics.ZygiskIssue.SAFE_MODE,
            probe(root = true, configured = false, log = log).zygiskIssue)
        assertNull(probe(root = true, configured = true, injected = true, log = log).zygiskIssue)
    }

}
