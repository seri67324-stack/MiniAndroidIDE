package com.miniide.app.builder

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.android.apksig.ApkSigner
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Prompt 8: Mevcut LegacyProjectBuilder / ZipAlignPackager hattına zarar vermeden,
 * özellik bayraklarıyla (BuildFeatureFlags) kontrol edilen gelişmiş derleme uzantıları:
 * 1. Release Keystore içe aktarma, güvenli saklama ve V1+V2+V3 imzalama
 * 2. SHA-256 hash tabanlı artımlı değişiklik tespiti (IncrementalBuildCache)
 * 3. dependencies.txt ayrıştırıcı, Maven Central / Google Maven URL çözümleyici, POM okuyucu ve AAR açıcı
 * 4. Hata tanılama ve yaygın hatalar için "Düzelt" (Quick-Fix) eylemleri (eksik import / eksik izin)
 */
object BuildExtensions {

    data class BuildFeatureFlags(
        val useReleaseSigning: Boolean = false,
        val enableIncrementalCache: Boolean = true,
        val enableMavenDependencies: Boolean = false
    )

    // ---------- 1. Release Keystore Güvenli Saklama ve İmzalama ----------

    data class ReleaseSigningConfig(
        val keystoreFile: File,
        val alias: String,
        val storePassword: String,
        val keyPassword: String
    )

    object ReleaseKeystoreManager {
        private const val PREFS = "ide_release_keystore"
        private const val KEY_ALIAS = "miniide_release_ks_aes"

        fun signReleaseApk(unsignedApk: File, signedApk: File, config: ReleaseSigningConfig, minSdk: Int = 21) {
            require(config.keystoreFile.isFile) { "Keystore dosyası bulunamadı: ${config.keystoreFile.absolutePath}" }
            if (signedApk.exists()) signedApk.delete()

            val ksType = if (config.keystoreFile.name.endsWith(".p12", ignoreCase = true) ||
                config.keystoreFile.name.endsWith(".pfx", ignoreCase = true)
            ) "PKCS12" else KeyStore.getDefaultType()

            val ks = KeyStore.getInstance(ksType)
            FileInputStream(config.keystoreFile).use { fis ->
                ks.load(fis, config.storePassword.toCharArray())
            }
            val key = ks.getKey(config.alias, config.keyPassword.toCharArray()) as? PrivateKey
                ?: throw IllegalArgumentException("Alias '${config.alias}' için özel anahtar bulunamadı")
            val chain = ks.getCertificateChain(config.alias)?.map { it as X509Certificate }
                ?: listOf(ks.getCertificate(config.alias) as X509Certificate)

            val signerConfig = ApkSigner.SignerConfig.Builder(config.alias, key, chain).build()
            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setMinSdkVersion(minSdk.coerceAtLeast(21))
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign()
        }

        private fun getOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return kg.generateKey()
        }

        fun saveSecret(context: Context, keyName: String, plainValue: String) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val enc = cipher.doFinal(plainValue.toByteArray(Charsets.UTF_8))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("${keyName}_enc", Base64.encodeToString(enc, Base64.NO_WRAP))
                .putString("${keyName}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
        }

        fun loadSecret(context: Context, keyName: String): String? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val enc = prefs.getString("${keyName}_enc", null) ?: return null
            val iv = prefs.getString("${keyName}_iv", null) ?: return null
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

    // ---------- 2. Hash Tabanlı Artımlı Derleme Değişiklik Tespiti ----------

    data class IncrementalChangeReport(
        val changedJavaFiles: List<String>,
        val deletedJavaFiles: List<String>,
        val resourcesChanged: Boolean,
        val manifestChanged: Boolean,
        val currentHashes: Map<String, String>
    ) {
        val hasAnyChange: Boolean
            get() = changedJavaFiles.isNotEmpty() || deletedJavaFiles.isNotEmpty() || resourcesChanged || manifestChanged
    }

    object IncrementalBuildCache {
        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        }

        fun scanHashes(projectDir: File): Map<String, String> {
            val rootPath = projectDir.canonicalPath
            val hashes = LinkedHashMap<String, String>()
            val manifest = File(projectDir, "AndroidManifest.xml")
            if (manifest.isFile) {
                hashes["AndroidManifest.xml"] = sha256(manifest.readBytes())
            }
            for (sub in listOf("src", "res")) {
                val dir = File(projectDir, sub)
                if (!dir.isDirectory) continue
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.canonicalPath.removePrefix(rootPath).trimStart('\\', '/').replace('\\', '/')
                    hashes[rel] = sha256(f.readBytes())
                }
            }
            return hashes
        }

        fun detectChanges(
            previousHashes: Map<String, String>,
            currentHashes: Map<String, String>
        ): IncrementalChangeReport {
            val changedJava = ArrayList<String>()
            val deletedJava = ArrayList<String>()
            var resChanged = false
            val manifestChanged = previousHashes["AndroidManifest.xml"] != currentHashes["AndroidManifest.xml"]

            for ((path, hash) in currentHashes) {
                if (previousHashes[path] != hash) {
                    when {
                        path.startsWith("src/") && path.endsWith(".java", ignoreCase = true) -> changedJava.add(path)
                        path.startsWith("res/") -> resChanged = true
                    }
                }
            }
            for (oldPath in previousHashes.keys) {
                if (!currentHashes.containsKey(oldPath)) {
                    when {
                        oldPath.startsWith("src/") && oldPath.endsWith(".java", ignoreCase = true) -> deletedJava.add(oldPath)
                        oldPath.startsWith("res/") -> resChanged = true
                    }
                }
            }

            return IncrementalChangeReport(
                changedJavaFiles = changedJava.sorted(),
                deletedJavaFiles = deletedJava.sorted(),
                resourcesChanged = resChanged,
                manifestChanged = manifestChanged,
                currentHashes = currentHashes
            )
        }
    }

    // ---------- 3. dependencies.txt Ayrıştırıcı & Maven Koordinat -> URL Çözümleyici ----------

    data class MavenCoord(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val packaging: String = "jar" // "jar" veya "aar"
    ) {
        fun asNotation(): String = "$groupId:$artifactId:$version"
    }

    data class ArtifactUrls(
        val mavenCentralPom: String,
        val mavenCentralArtifact: String,
        val googleMavenPom: String,
        val googleMavenArtifact: String
    )

    object MavenDependencyResolver {
        private const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
        private const val GOOGLE_MAVEN = "https://dl.google.com/dl/android/maven2"

        /**
         * dependencies.txt içeriğini ("grup:ad:sürüm" veya "grup:ad:sürüm@aar") ayrıştırır.
         */
        fun parseDependenciesTxt(content: String): List<MavenCoord> {
            val list = ArrayList<MavenCoord>()
            for (rawLine in content.lines()) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val atParts = line.split('@')
                val coordPart = atParts[0].trim()
                val explicitPkg = atParts.getOrNull(1)?.trim()?.lowercase()
                val parts = coordPart.split(':')
                if (parts.size != 3) continue
                val g = parts[0].trim()
                val a = parts[1].trim()
                val v = parts[2].trim()
                if (g.isEmpty() || a.isEmpty() || v.isEmpty()) continue
                val pkg = explicitPkg ?: if (g.startsWith("androidx.") || g.startsWith("com.google.android.material")) "aar" else "jar"
                list.add(MavenCoord(g, a, v, pkg))
            }
            return list
        }

        /** Maven koordinatından Maven Central ve Google Maven POM/JAR/AAR adreslerini üretir. */
        fun resolveArtifactUrls(coord: MavenCoord): ArtifactUrls {
            val groupPath = coord.groupId.replace('.', '/')
            val baseRel = "$groupPath/${coord.artifactId}/${coord.version}/${coord.artifactId}-${coord.version}"
            return ArtifactUrls(
                mavenCentralPom = "$MAVEN_CENTRAL/$baseRel.pom",
                mavenCentralArtifact = "$MAVEN_CENTRAL/$baseRel.${coord.packaging}",
                googleMavenPom = "$GOOGLE_MAVEN/$baseRel.pom",
                googleMavenArtifact = "$GOOGLE_MAVEN/$baseRel.${coord.packaging}"
            )
        }

        /** Basit 1. seviye geçişli bağımlılıklar için POM dosyasından compile bağımlılıklarını okur. */
        fun parseDirectDependenciesFromPom(pomXml: String): List<MavenCoord> {
            val deps = ArrayList<MavenCoord>()
            val depBlockRegex = Regex("""<dependency>([\s\S]*?)</dependency>""")
            val gRegex = Regex("""<groupId>\s*([^<\s]+)\s*</groupId>""")
            val aRegex = Regex("""<artifactId>\s*([^<\s]+)\s*</artifactId>""")
            val vRegex = Regex("""<version>\s*([^<\s]+)\s*</version>""")
            val sRegex = Regex("""<scope>\s*([^<\s]+)\s*</scope>""")
            val tRegex = Regex("""<type>\s*([^<\s]+)\s*</type>""")

            for (m in depBlockRegex.findAll(pomXml)) {
                val block = m.groupValues[1]
                val scope = sRegex.find(block)?.groupValues?.get(1)?.lowercase() ?: "compile"
                if (scope == "test" || scope == "provided") continue
                val g = gRegex.find(block)?.groupValues?.get(1) ?: continue
                val a = aRegex.find(block)?.groupValues?.get(1) ?: continue
                val v = vRegex.find(block)?.groupValues?.get(1) ?: continue
                if (v.startsWith("\${")) continue
                val type = tRegex.find(block)?.groupValues?.get(1)?.lowercase() ?: "jar"
                deps.add(MavenCoord(g, a, v, type))
            }
            return deps
        }

        /** AAR içinden classes.jar, res/ ve paket adını çıkarır. */
        fun extractAar(aarFile: File, libsDir: File): Pair<File?, String?> {
            var extractedJar: File? = null
            var aarPackage: String? = null
            val resOutDir = File(libsDir, "aar_res/${aarFile.nameWithoutExtension}")

            ZipFile(aarFile).use { zip ->
                zip.getEntry("classes.jar")?.let { entry ->
                    val jarOut = File(libsDir, "${aarFile.nameWithoutExtension}.jar")
                    zip.getInputStream(entry).use { i -> jarOut.outputStream().use { o -> i.copyTo(o) } }
                    extractedJar = jarOut
                }
                zip.getEntry("AndroidManifest.xml")?.let { entry ->
                    val manifestText = zip.getInputStream(entry).bufferedReader().readText()
                    aarPackage = Regex("""package\s*=\s*"([^"]+)"""").find(manifestText)?.groupValues?.get(1)
                }
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.name.startsWith("res/") && !e.isDirectory) {
                        val out = File(resOutDir, e.name.removePrefix("res/"))
                        out.parentFile?.mkdirs()
                        zip.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    }
                }
            }
            return extractedJar to aarPackage
        }

        fun downloadUrlToFile(urlStr: String, target: File): Boolean {
            return try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) return false
                target.parentFile?.mkdirs()
                conn.inputStream.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    // ---------- 5. Hata Tanılama İçin "Düzelt" (Quick-Fix) Önerileri ----------

    sealed class QuickFix(val titleTr: String) {
        class AddJavaImport(val importFqn: String) : QuickFix("Eksik import ekle: $importFqn")
        class AddManifestPermission(val permission: String) : QuickFix("Manifest izni ekle: $permission")
    }

    object QuickFixEngine {
        private val knownImports = mapOf(
            "Activity" to "android.app.Activity",
            "Bundle" to "android.os.Bundle",
            "Intent" to "android.content.Intent",
            "Context" to "android.content.Context",
            "Uri" to "android.net.Uri",
            "View" to "android.view.View",
            "TextView" to "android.widget.TextView",
            "Button" to "android.widget.Button",
            "EditText" to "android.widget.EditText",
            "Toast" to "android.widget.Toast",
            "LinearLayout" to "android.widget.LinearLayout",
            "Log" to "android.util.Log",
            "Handler" to "android.os.Handler",
            "Looper" to "android.os.Looper",
            "File" to "java.io.File",
            "List" to "java.util.List",
            "ArrayList" to "java.util.ArrayList",
            "Map" to "java.util.Map",
            "HashMap" to "java.util.HashMap"
        )

        fun suggestFix(error: BuildError): QuickFix? {
            val unresolvedType = Regex("""^([A-Z][A-Za-z0-9_]*)\s+cannot be resolved to a type$""")
                .matchEntire(error.message.trim())?.groupValues?.get(1)
            if (unresolvedType != null) {
                val fqn = knownImports[unresolvedType]
                if (fqn != null) return QuickFix.AddJavaImport(fqn)
            }

            val permMatch = Regex("""android\.permission\.([A-Z0-9_]+)""").find(error.message)
            if (permMatch != null) {
                return QuickFix.AddManifestPermission("android.permission.${permMatch.groupValues[1]}")
            }
            return null
        }

        fun applyJavaImport(javaCode: String, importFqn: String): String {
            val importLine = "import $importFqn;"
            if (javaCode.contains(importLine)) return javaCode
            val pkgMatch = Regex("""^package\s+[^;]+;\s*$""", RegexOption.MULTILINE).find(javaCode)
            return if (pkgMatch != null) {
                val insertPos = pkgMatch.range.last + 1
                javaCode.substring(0, insertPos) + "\n\n$importLine" + javaCode.substring(insertPos)
            } else {
                "$importLine\n$javaCode"
            }
        }

        fun applyManifestPermission(manifestXml: String, permission: String): String {
            if (manifestXml.contains(permission)) return manifestXml
            val tag = "    <uses-permission android:name=\"$permission\" />\n"
            val appIdx = manifestXml.indexOf("<application")
            return if (appIdx > 0) {
                manifestXml.substring(0, appIdx) + tag + "\n" + manifestXml.substring(appIdx)
            } else {
                manifestXml
            }
        }
    }
}
