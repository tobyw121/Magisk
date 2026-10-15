package com.topjohnwu.magisk.core.utils

import org.junit.Assert.*
import org.junit.Test

class InstallChecksTest {
    private fun elf(bits: Int, machine: Int) = ByteArray(20).apply {
        this[0] = 0x7f; this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte(); this[4] = bits.toByte(); this[5] = 1
        this[18] = machine.toByte(); this[19] = (machine shr 8).toByte()
    }

    @Test fun bothArmBinariesMustMatchTheirOwnArchitecture() {
        assertTrue(InstallChecks.matchesAbi(elf(2, 183), "arm64-v8a"))
        assertTrue(InstallChecks.matchesAbi(elf(1, 40), "armeabi-v7a"))
        assertFalse(InstallChecks.matchesAbi(elf(2, 183), "armeabi-v7a"))
        assertFalse(InstallChecks.matchesAbi(elf(1, 40), "arm64-v8a"))
    }

    @Test fun otherSupportedArchitecturesAreRecognized() {
        assertTrue(InstallChecks.matchesAbi(elf(1, 3), "x86"))
        assertTrue(InstallChecks.matchesAbi(elf(2, 62), "x86_64"))
        assertTrue(InstallChecks.matchesAbi(elf(2, 243), "riscv64"))
        assertFalse(InstallChecks.matchesAbi(elf(2, 62), "arm64-v8a"))
    }

    @Test fun truncatedWrongMagicAndUnsupportedEncodingAreRejected() {
        assertFalse(InstallChecks.matchesAbi(ByteArray(3), "arm64-v8a"))
        assertFalse(InstallChecks.matchesAbi(ByteArray(20), "arm64-v8a"))
        assertFalse(InstallChecks.matchesAbi(elf(2, 183).apply { this[5] = 2 }, "arm64-v8a"))
        assertFalse(InstallChecks.matchesAbi(elf(2, 183), "unknown"))
    }
}
