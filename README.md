# Mini Android IDE (v1.0.0)

**Mini Android IDE**, Android telefon veya tablet üzerinde bilgisayar gerektirmeden doğrudan **Java & XML** projeleri oluşturup düzenleyebilen ve cihaz üzerinde **imzalı APK** üretebilen modern bir mobil geliştirme ortamıdır.

---

## ✨ Öne Çıkan Özellikler

### 🛠️ Cihaz Üzerinde 6 Aşamalı Tam Derleme Hattı
1. **Hazırlık:** Proje yapısını (`AndroidManifest.xml`, `src/`, `res/`, `assets/`, `libs/`) doğrular, `minSdkVersion` bilgisini okur ve çalışma dizinlerini hazırlar.
2. **AAPT2 (`libaapt2.so` - ARM64):** XML kaynaklarını (`res/`) ve `assets/` dosyalarını derler, `R.java` ve `resources.ap_` paketini üretir.
3. **ECJ (Eclipse Compiler for Java):** Java kaynak kodlarını ve `libs/*.jar` kütüphanelerini `android.jar` (API 34) karşısında `.class` bayt koduna derler.
4. **D8 Dexer:** Derlenen `.class` dosyalarını ve harici JAR kütüphanelerini optimize edilmiş `classes.dex` formatına dönüştürür.
5. **4-Byte ZipAlign Paketleme:** Sıkıştırılmamış kaynakları (`resources.arsc`, görseller) ve `classes.dex` dosyalarını geçerli `0xD935` ZIP extra hizalama başlığıyla 4-byte sınırına hizalayarak paketler.
6. **APK İmzalama (`apksig` + `BouncyCastle`):** Cihaz üzerinde otomatik RSA-2048 anahtar/sertifika üretir ve APK'yı **V1 + V2 + V3** şemalarıyla imzalar.

### 💻 Modern Kod Editörü
- **Sözdizimi Renklendirme:** Java, Kotlin, XML, JSON, Gradle ve Properties dosyaları için açık/koyu tema uyumlu renklendirme.
- **Satır Vurgulama:** Aktif imleç satırı ve derleme hatası satırı vurgusu, sabit satır numarası şeridi (`gutter`).
- **Üretkenlik Araçları:** Geri Al / Yinele (60 adım), Ara ve Değiştir, Satıra Git, Yazı Boyutu Yakınlaştırma (`A-` / `A+`), Otomatik Girinti ve Eşleşen Parantez Tamamlama.
- **Hazır Kod Şablonları (`⚡ Şablon`):** Tek dokunuşla Java (`Toast`, `OnClickListener`, `AlertDialog`, `Intent`, `SharedPreferences`) ve XML (`TextView`, `Button`, `LinearLayout`, `EditText`) şablonları ekleme.
- **Hızlı Sembol Çubuğu & Tek Tuşla Derle:** Mobil klavyede zor ulaşılan semboller (`Tab`, `{`, `}`, `(`, `)`, `;`, `"`, vb.) ve editörden çıkmadan **▶ Derle** düğmesi.

### 📁 Proje ve Dosya Yöneticisi
- **+ Yeni Proje Sihirbazı:** Uygulama adı ve paket adıyla (`com.ornek.uygulama`) saniyeler içinde derlenmeye hazır modern Android projesi oluşturma.
- **ZIP İçe / Dışa Aktarma:** Mevcut projeleri `.zip` olarak içe aktarma veya düzenlenen projeyi tek tuşla `.zip` olarak dışa aktarıp paylaşma.
- **Gelişmiş Dosya Gezgini:** Tür rozetleri (`JAVA`, `XML`, `KLSR`, `IMG`, `JAR`, `APK`), anlık dosya arama filtresi, dosya kopyalama, yeniden adlandırma ve silme.
- **Türkçe Hata Tanılama:** ECJ ve AAPT2 derleme hatalarını anlaşılır Türkçe açıklamalarla listeler; **"Editörde Aç ›"** düğmesiyle doğrudan hatalı dosyanın ilgili satırına odaklanır.

---

## 🔐 İzinler ve Gizlilik Notu
- **`INTERNET` İzni:** Yalnızca kullanıcı açıkça tetiklediğinde **Git (`clone`, `pull`, `push`)** ve isteğe bağlı **Maven bağımlılık indirme** işlemleri için kullanılır. Arka planda telemetri veya otomatik ağ gönderimi yapılmaz; Git kişisel erişim anahtarları (PAT) **Android Keystore (AES-GCM)** ile şifrelenerek cihazda saklanır.

---

## 🚀 GitHub Actions ile Otomatik APK Derleme

Bu depo `.github/workflows/build.yml` iş akışı ile yapılandırılmıştır:
- Her `push` işleminde veya **Actions > Build APK > Run workflow** tetiklendiğinde otomatik olarak:
  1. Android SDK API 34 `android.jar` dosyasını `app/src/main/assets/android.jar` konumuna kopyalar.
  2. `app/src/main/jniLibs/arm64-v8a/libaapt2.so` ikilisini doğrular.
  3. Birim testlerini (`testDebugUnitTest`) ve APK derlemesini (`assembleDebug`) çalıştırır.
  4. Üretilen `app-debug.apk` dosyasını **MiniAndroidIDE-debug-apk** artifact'i olarak indirilebilir hale getirir.
