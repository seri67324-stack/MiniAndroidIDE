package com.miniide.app.builder

import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

object DebugKeystoreHelper {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    data class SigningCredentials(val privateKey: PrivateKey, val certificate: X509Certificate)

    fun getOrCreateDebugCredentials(storageDir: File): SigningCredentials {
        val keyFile = File(storageDir, "debug_key.pk8")
        val certFile = File(storageDir, "debug_cert.pem")

        if (keyFile.exists() && certFile.exists()) {
            try {
                val keySpec = PKCS8EncodedKeySpec(keyFile.readBytes())
                val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
                val cert = certFile.inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                }
                return SigningCredentials(privateKey, cert)
            } catch (_: Exception) {
                // Bozulmuş anahtar dosyaları varsa yeniden üret
                keyFile.delete()
                certFile.delete()
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        val issuer = X500Name("CN=Android Debug, O=Android, C=US")
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86400000L)
        val notAfter = Date(now + 30L * 365 * 86400000L)

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now),
            notBefore,
            notAfter,
            issuer,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        val certificate = JcaX509CertificateConverter().getCertificate(certHolder)

        storageDir.mkdirs()
        keyFile.writeBytes(keyPair.private.encoded)
        certFile.writeBytes(certificate.encoded)

        return SigningCredentials(keyPair.private, certificate)
    }

    fun signApk(unsignedApk: File, signedApk: File, storageDir: File, minSdkVersion: Int = 21) {
        if (signedApk.exists()) signedApk.delete()
        val creds = getOrCreateDebugCredentials(storageDir)

        val signerConfig = ApkSigner.SignerConfig.Builder(
            "ANDROID_DEBUG",
            creds.privateKey,
            listOf(creds.certificate)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setMinSdkVersion(minSdkVersion.coerceAtLeast(21))
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}
