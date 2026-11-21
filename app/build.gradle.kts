plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miniide.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miniide.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.0.0"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Android 10+ W^X kısıtını aşmak için libaapt2.so dosyasının cihazda
    // nativeLibraryDir içine çıkarılmasını zorunlu kılıyoruz:
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libaapt2.so"
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/services/javax.annotation.processing.Processor",
                "plugin.properties",
                "about.html"
            )
        }
    }
}

val ensureAndroidJar by tasks.registering {
    doLast {
        val assetJar = file("src/main/assets/android.jar")
        if (!assetJar.exists()) {
            val sdkJar = file("${android.sdkDirectory}/platforms/android-${android.compileSdk}/android.jar")
            if (!sdkJar.exists()) {
                // Sessizce geçmek yerine derlemeyi durdur: aksi halde APK derlenir ama uygulama ilk
                // derlemede android.jar bulamayıp çöker.
                throw GradleException(
                    "android.jar bulunamadı. Şunlardan birini yapın:\n" +
                    "  1) Android SDK'dan 'platforms;android-${android.compileSdk}' paketini kurun (beklenen: ${sdkJar.absolutePath})\n" +
                    "  2) android.jar dosyasını elle app/src/main/assets/android.jar konumuna kopyalayın."
                )
            }
            assetJar.parentFile.mkdirs()
            sdkJar.copyTo(assetJar, overwrite = true)
            println("Copied android.jar from SDK: ${sdkJar.absolutePath}")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(ensureAndroidJar)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // 1. ECJ - 4.6.1 (Java 7/8 bytecode, javax.tools gerektirmez)
    implementation("org.eclipse.jdt.core.compiler:ecj:4.6.1")

    // 2. D8 (Dexer) - R8 paketi içinde gelir
    implementation("com.android.tools:r8:3.3.75")

    // 3. APK imzalayıcı (V1 + V2 + V3) ve BouncyCastle (cihazda anahtar/sertifika üretimi)
    implementation("com.android.tools.build:apksig:8.2.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    // 4. JGit (Android ART uyumlu 5.13 LTS serisi - temel Git desteği)
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")

    testImplementation("junit:junit:4.13.2")
}
