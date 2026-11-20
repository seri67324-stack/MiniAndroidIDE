package com.miniide.app

import com.android.apksig.ApkVerifier
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.miniide.app.builder.DebugKeystoreHelper
import com.miniide.app.builder.ErrorParser
import com.miniide.app.builder.ZipAlignPackager
import com.miniide.app.project.ProjectManager
import org.eclipse.jdt.internal.compiler.batch.Main as EcjMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files

class BuilderPipelineTest {

    @Test
    fun testErrorParserTranslations() {
        val projectDir = File("/tmp/proj")
        val ecjOut = """
----------
1. ERROR in /tmp/proj/src/com/example/MainActivity.java (at line 14)
	UnknownType x = null;
	^^^^^^^^^^^
UnknownType cannot be resolved to a type
----------
        """.trimIndent()

        val errors = ErrorParser.parseEcj(ecjOut, projectDir)
        assertEquals(1, errors.size)
        assertTrue(errors[0].isError)
        assertEquals("src/com/example/MainActivity.java", errors[0].normalizedFile())
        assertEquals(14, errors[0].line)
        assertTrue(errors[0].messageTr.contains("türü bulunamadı"))
    }

    @Test
    fun testSampleProjectFullCompileAndSign() {
        val sampleZip = File("src/main/assets/GocbilHatirlatici.zip").takeIf { it.exists() }
            ?: File("app/src/main/assets/GocbilHatirlatici.zip")
        val androidJar = File("src/main/assets/android.jar").takeIf { it.exists() }
            ?: File("app/src/main/assets/android.jar")
        if (!sampleZip.exists() || !androidJar.exists()) return

        val tempRoot = Files.createTempDirectory("miniide_test").toFile()
        try {
            sampleZip.inputStream().use { ProjectManager.unzip(it, tempRoot) }
            val projectDir = File(tempRoot, "GocbilHatirlatici")
            assertTrue("AndroidManifest.xml must exist", File(projectDir, "AndroidManifest.xml").exists())

            val genDir = File(projectDir, "gen").apply { mkdirs() }
            val binDir = File(projectDir, "bin").apply { mkdirs() }
            val classesDir = File(binDir, "classes").apply { mkdirs() }
            val dexDir = File(binDir, "dex").apply { mkdirs() }
            val resCompiledZip = File(binDir, "res_compiled.zip")
            val resourcesAp = File(binDir, "resources.ap_")

            val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            val hostAapt2 = sdkRoot?.let { File(it, "build-tools") }
                ?.listFiles()?.sortedByDescending { it.name }
                ?.firstNotNullOfOrNull { dir ->
                    File(dir, "aapt2.exe").takeIf { it.exists() } ?: File(dir, "aapt2").takeIf { it.exists() }
                }

            if (hostAapt2 != null) {
                val compileProc = ProcessBuilder(
                    hostAapt2.absolutePath, "compile",
                    "--dir", File(projectDir, "res").absolutePath,
                    "-o", resCompiledZip.absolutePath
                ).redirectErrorStream(true).start()
                val compileOut = compileProc.inputStream.bufferedReader().readText()
                assertEquals("AAPT2 compile failed: $compileOut", 0, compileProc.waitFor())

                val linkProc = ProcessBuilder(
                    hostAapt2.absolutePath, "link",
                    "-I", androidJar.absolutePath,
                    "--manifest", File(projectDir, "AndroidManifest.xml").absolutePath,
                    "--java", genDir.absolutePath,
                    "-o", resourcesAp.absolutePath,
                    "--auto-add-overlay",
                    resCompiledZip.absolutePath
                ).redirectErrorStream(true).start()
                val linkOut = linkProc.inputStream.bufferedReader().readText()
                assertEquals("AAPT2 link failed: $linkOut", 0, linkProc.waitFor())

                val outSw = StringWriter()
                val errSw = StringWriter()
                val ecj = EcjMain(PrintWriter(outSw, true), PrintWriter(errSw, true), false, null, null)
                val ok = ecj.compile(
                    arrayOf(
                        "-source", "1.8",
                        "-target", "1.8",
                        "-proc:none",
                        "-nowarn",
                        "-encoding", "UTF-8",
                        "-bootclasspath", androidJar.absolutePath,
                        "-d", classesDir.absolutePath,
                        File(projectDir, "src").absolutePath,
                        genDir.absolutePath
                    )
                )
                assertTrue("ECJ compile failed: $errSw\n$outSw", ok)

                val classFiles = classesDir.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .map { it.toPath() }
                    .toList()
                assertTrue("Must produce class files", classFiles.isNotEmpty())

                D8.run(
                    D8Command.builder()
                        .addLibraryFiles(androidJar.toPath())
                        .addProgramFiles(classFiles)
                        .setMinApiLevel(21)
                        .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
                        .build()
                )
                val dexFiles = dexDir.listFiles { f -> f.extension == "dex" }?.toList() ?: emptyList()
                assertTrue("Must produce classes.dex", dexFiles.isNotEmpty())

                val unsignedApk = File(binDir, "unsigned.apk")
                ZipAlignPackager.packageAndAlign(resourcesAp, dexFiles, unsignedApk)
                assertTrue("Unsigned APK must exist", unsignedApk.exists() && unsignedApk.length() > 0)

                val signedApk = File(binDir, "signed.apk")
                val keystoreDir = File(tempRoot, "keystore")
                DebugKeystoreHelper.signApk(unsignedApk, signedApk, keystoreDir, 21)
                assertTrue("Signed APK must exist", signedApk.exists() && signedApk.length() > 0)

                val verifierResult = ApkVerifier.Builder(signedApk)
                    .setMinCheckedPlatformVersion(21)
                    .build()
                    .verify()
                assertTrue("APK signature verification failed: ${verifierResult.errors}", verifierResult.isVerified)
                assertTrue("V2 signature must be verified", verifierResult.isVerifiedUsingV2Scheme)
                assertTrue("V3 signature must be verified", verifierResult.isVerifiedUsingV3Scheme)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
