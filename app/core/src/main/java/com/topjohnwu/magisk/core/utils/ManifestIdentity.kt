package com.topjohnwu.magisk.core.utils

object ManifestIdentity {
    fun rewrite(bytes: ByteArray, original: String, pkg: String, label: String): ByteArray {
        require(pkg.matches(Regex("[a-z]+\\.[a-z]+")))
        require(label.isNotBlank() && label.length <= 32)
        val xml = AXML(bytes)
        check(xml.patchStrings {
            when (it) {
                original -> pkg
                "$original.provider" -> "$pkg.provider"
                "Magisk" -> label
                else -> it
            }
        })
        return xml.bytes
    }
}
