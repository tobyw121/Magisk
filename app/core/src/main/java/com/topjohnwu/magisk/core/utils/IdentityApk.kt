package com.topjohnwu.magisk.core.utils

import com.topjohnwu.magisk.core.signing.JarMap
import com.topjohnwu.magisk.core.signing.SignApk
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Date

/** Repackage and sign the APK without persisting the generated private key. */
object IdentityApk {
    fun create(input: File, output: File, original: String, pkg: String, label: String, version: Int): ByteArray {
        val random = SecureRandom()
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Application")
        val now = System.currentTimeMillis()
        val cert = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                name, BigInteger(160, random), Date(now - 86400000),
                Date(now + 30L * 365 * 86400000), name, keys.public
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        )
        JarMap.open(input, true).use { jar ->
            val entry = jar.getJarEntry("AndroidManifest.xml")
            val manifest = ManifestIdentity.rewrite(jar.getRawData(entry), original, pkg, label)
            jar.getOutputStream(entry).use { it.write(manifest) }
            output.outputStream().use {
                SignApk.sign(cert, keys.private, jar, it, "versionCode=$version\n")
            }
        }
        return cert.encoded
    }
}
