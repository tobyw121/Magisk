package com.topjohnwu.magisk.core.utils

object InstallChecks {
    fun matchesAbi(header: ByteArray, abi: String): Boolean {
        if (header.size < 20 || header[0] != 0x7f.toByte() ||
            header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte() || header[5] != 1.toByte()) return false
        val expected = when (abi) {
            "armeabi-v7a" -> 1 to 40
            "arm64-v8a" -> 2 to 183
            "x86" -> 1 to 3
            "x86_64" -> 2 to 62
            "riscv64" -> 2 to 243
            else -> return false
        }
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        return header[4].toInt() == expected.first && machine == expected.second
    }
}
