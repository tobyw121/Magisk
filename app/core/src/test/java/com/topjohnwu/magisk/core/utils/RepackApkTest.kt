package com.topjohnwu.magisk.core.utils

import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class RepackApkTest {
    @Test fun repacksActualApk() {
        val path = System.getenv("MAGISK_REPACK_INPUT")
        assumeNotNull(path)
        val input = File(checkNotNull(path))
        val output = File(System.getenv("MAGISK_REPACK_OUTPUT") ?: File.createTempFile("identity-", ".apk").path)
        val cert = IdentityApk.create(input, output, "com.topjohnwu.magisk", "abcdefgh.ijklmnop", "Neutralxyz", 1000000)
        assertTrue(cert.isNotEmpty())
        ZipFile(input).use { original ->
            ZipFile(output).use { patched ->
                assertEquals("versionCode=1000000\n", patched.comment)
                assertArrayEquals(
                    original.getInputStream(original.getEntry("classes.dex")).readBytes(),
                    patched.getInputStream(patched.getEntry("classes.dex")).readBytes()
                )
                val strings = mutableListOf<String>()
                AXML(patched.getInputStream(patched.getEntry("AndroidManifest.xml")).readBytes()).patchStrings {
                    strings.add(it); it
                }
                assertTrue(strings.contains("abcdefgh.ijklmnop"))
                assertTrue(strings.contains("abcdefgh.ijklmnop.provider"))
                assertTrue(strings.contains("Neutralxyz"))
                assertFalse(strings.contains("com.topjohnwu.magisk"))
            }
        }
    }
}
