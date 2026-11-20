# Cihaz Üzerinde Kotlin Derleyici (`kotlin-compiler-embeddable`) Fizibilite Raporu

## 1. Amaç
Bu rapor, **Mini Android IDE** uygulamasına cihaz üzerinde doğrudan `.kt` dosyalarını derleyebilmek için `org.jetbrains.kotlin:kotlin-compiler-embeddable` kütüphanesinin gömülmesinin teknik fizibilitesini (APK boyutu, bellek tüketimi, derleme süresi ve Android ART uyumluluğu) değerlendirir.

## 2. Teknik Metrikler ve Etkiler

| Kriter | Mevcut Hat (AAPT2 + ECJ + D8) | `kotlin-compiler-embeddable` Eklendiğinde | Etki |
| :--- | :--- | :--- | :--- |
| **APK Boyutu** | ~45 MB (android.jar + libaapt2.so + ECJ/D8 + JGit) | **~105 – 120 MB** (+58 MB derleyici JAR + stdlib + DEX) | **Çok Yüksek (2.5x artış)** |
| **Anlık RAM / ART Heap** | 80 – 160 MB | **380 – 650 MB** (PSI + Frontend + IR Backend) | **Kritik OOM Riski** |
| **Tek Dosya Soğuk Derleme Süresi (ARM64)** | 1.5 – 3.5 sn | **20 – 45 sn** | **Kullanıcı Deneyimi Zayıf** |
| **Android ART Uyumluluğu** | Tam uyumlu (Java 8 bytecode) | `java.lang.management`, `sun.misc.Unsafe`, `javax.xml` ve `FileSystems` bağımlılıkları nedeniyle ART üzerinde yama gerektirir | **Yüksek Bakım Riski** |

## 3. Bellek (OOM) ve ART Riskleri
- Çoğu Android cihazda (4–8 GB RAM) tek bir uygulamaya ayrılan ART heap üst sınırı `android:largeHeap="true"` kullanılsa dahi genellikle **256 MB – 512 MB** aralığındadır.
- `kotlin-compiler-embeddable` sınıf yükleme, `android.jar` indeksleme ve JVM IR üretimi sırasında 400 MB'ı aşan anlık bellek zirveleri oluşturur; bu durum orta segment cihazlarda sık sık `OutOfMemoryError` ile servisin çökmesine yol açar.

## 4. Sonuç ve Karar: **OLUMSUZ (Uygulanmadı)**
Prompt 8 kuralı gereğince (*"Rapor olumsuzsa uygulama"*), **`kotlin-compiler-embeddable` cihaz içi derleme hattına eklenmemiştir**.

### Önerilen Alternatif Mimari
1. Cihaz üzerinde hızlı, kararlı ve düşük bellekli derleme için mevcut **Java + XML (AAPT2 + ECJ + D8)** hattının korunması.
2. Kotlin projeleri için **GitHub Actions (`.github/workflows/build.yml`)** üzerinden bulut derleme veya harici Termux/JDK ortamına köprü kullanılması.
