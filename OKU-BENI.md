# Mini Android IDE (v1.0.0)

Detaylı dokümantasyon için [README.md](README.md) dosyasına bakabilirsiniz.

## Yapılan Düzeltmeler ve Modernleştirmeler (v1.0.0)
- **Kotlin Derleme Hataları Giderildi:** `EditorActivity.kt` içindeki `AppCompatEditText.text` (`Editable?`) null-safety derleme hataları tamamen düzeltildi.
- **ARM64 `libaapt2.so` Eklendi:** `app/src/main/jniLibs/arm64-v8a/libaapt2.so` konumuna gerçek AOSP ARM64 AAPT2 ikilisi yerleştirildi ve GitHub Actions iş akışına otomatik yedek indirme eklendi.
- **Otomatik `android.jar` Hazırlığı:** `app/build.gradle.kts` içine `ensureAndroidJar` görevi eklendi; yerel SDK veya GitHub Actions ortamından `android.jar` (API 34) otomatik kopyalanır.
- **Derleyici Hattı İyileştirmeleri (`LegacyProjectBuilder`):**
  - Kullanıcı projelerinde `assets/` klasörü (`-A`) ve `libs/*.jar` harici kütüphaneleri (`-classpath` & D8) desteği eklendi.
  - `res/` klasörü olmayan projelerde boş `gen/` dizininin ECJ'yi çökertmesi hatası düzeltildi.
  - Projenin `AndroidManifest.xml` dosyasındaki `minSdkVersion` değeri D8 ve ApkSigner'a dinamik olarak aktarılır.
  - Android 14 (`targetSdk 34`) `FOREGROUND_SERVICE_TYPE_DATA_SYNC` uyumluluğu ve aktif `aapt2` alt sürecini anında sonlandırma (`destroyForcibly`) eklendi.
- **Modern Material 3 Arayüz ve Yeni Özellikler:**
  - **+ Yeni Proje Sihirbazı:** Uygulama adı ve paket adıyla sıfırdan derlenmeye hazır modern Android projesi oluşturma.
  - **ZIP Dışa Aktarma ve APK Paylaşma:** Projeyi `.zip` olarak dışa aktarma ve üretilen APK'yı doğrudan paylaşma.
  - **Gelişmiş Kod Editörü:** Aktif satır vurgusu, hata satırı vurgusu, satır:sütun durum çubuğu, `A-`/`A+` yazı boyutu ayarı, hazır Java/XML kod şablonları (`⚡ Şablon`), otomatik parantez tamamlama ve editör içinden tek tuşla **▶ Derle**.
  - **Modern Dosya Yöneticisi:** Dosya türü rozetleri (`JAVA`, `XML`, `KLSR`, `IMG`, `JAR`, `APK`), anlık arama filtresi, dosya kopyalama ve tek tuşla derleme.
