# 🛡️ DoomBlocker — Akıllı Sosyal Medya & Doomscroll Engelleyici

**DoomBlocker**, sosyal medya uygulamalarında farkında olmadan saatlerce ekran kaydırma (*doomscrolling*) alışkanlığını kırmak için geliştirilmiş, **Kotlin** ve **Jetpack Compose** tabanlı modern bir Android uygulamasıdır.

Standart uygulama kilitleyicilerden farklı olarak günlük sabit bir kota koymak yerine **60 dakikalık kayan zaman penceresi (Rolling Window)** ve **Hibrit Süre Havuzu** modeliyle çalışır; ayrıca YouTube'un tamamını kapatmak yerine yalnızca **YouTube Shorts** ekranını cerrahi olarak engeller.

---

## ✨ Temel Özellikler

### 1. ⏱️ Hibrit Kayan 60 Dakika (Rolling Window) Modeli
Sabit saat başı sıfırlama yerine, **şu anki zamandan geriye doğru son 60 dakika** içindeki aktif kullanım saniye bazında hesaplanır ve iki kural aynı anda uygulanır:
- **Uygulama Başına Limit (Max 5 Dakika):** Takip edilen bir uygulamayı son 60 dakikada en fazla **5 dakika** kullanabilirsin. Örneğin Instagram'da 5 dakikan dolduğunda Instagram kilitlenir, ancak genel havuzda kalan 2 dakikalık hakkını diğer uygulamalarda kullanabilirsin.
- **Genel Toplam Havuz (Max 7 Dakika):** Takip edilen 4 uygulamanın (**Instagram**, **X / Twitter**, **Reddit**, **YouTube Shorts**) son 60 dakikadaki toplam kullanımı **7 dakikaya** ulaştığında, hiçbirinin tekil 5 dakikası dolmamış olsa bile (ör. `2 dk Instagram + 2 dk X + 1 dk Reddit + 2 dk Shorts = 7 dk`) **tümü kilitlenir**.
- **🔔 1 Dakika Kala Erken Uyarı:** Hem uygulama bazlı hem de genel havuz süresinin bitmesine **son 1 dakika** kaldığında ekranda bildirim ve uyarı mesajı gösterilir.

### 2. 🎯 YouTube Shorts Cerrahi Yakalayıcı (`AccessibilityService`)
- YouTube'da uzun video izlerken, eğitim/müzik dinlerken veya arama yaparken **süre sayılmaz ve uygulama engellenmez**.
- Yalnızca **YouTube Shorts** oynatıcısı açıkken süre hibrit havuzdan düşer.
- Süre dolduğunda YouTube kapatılmaz; uygulama otomatik olarak **Geri** komutu göndererek seni Shorts ekranından çıkarır ve normal YouTube'a döndürür.

### 3. 🧘 3 Saniyelik "Bilinçli Duraksama" (Mindful Pause)
- Süren henüz dolmamış olsa bile Instagram, X, Reddit veya YouTube Shorts'u açtığında karşına **3 saniyelik** bir farkındalık ekranı çıkar:
  > *"Şu an gerçekten belirli bir şeye bakmak için mi giriyorsun, yoksa sadece el alışkanlığı mı?"*
- **"✋ Vazgeç & Ana Ekrana Dön"** seçeneğiyle kas hafızasıyla yapılan gereksiz girişleri daha başlamadan sonlandırabilirsin.

### 4. 🌙 Gece / Derin Odak Saatleri (Zamanlayıcı)
- Uygulama içinden başlangıç ve bitiş saatlerini (örneğin `23:30 - 08:00`) dilediğin gibi ayarlayabileceğin veya kapatabileceğin **Derin Odak Modu** bulunur.
- Belirlenen saat aralığında tüm takip edilen uygulamalar **0 dakika** limitle tam kilit altına alınır.

### 5. 🔒 Strict Mode (Kendi Kendini Kandırmayı Önleme)
Anlık dürtüyle korumayı kapatmayı veya ayarları esnetmeyi önlemek için çok katmanlı koruma içerir:
- **30 Saniye Bekleme Duvarı:** Korumayı kapatmak, süreleri sıfırlamak veya aktif bir kısıtlama varken odak saatlerini değiştirmek istediğinde 30 saniyelik geri sayım başlar (ekrandan çıkılırsa sıfırlanır).
- **Kopyalanamaz Caydırıcı Metin Doğrulaması:** 30 saniye sonunda rastgele üretilen uzun bir cümleyi ve doğrulama kodunu kopyala-yapıştır yapmadan harfi harfine yazman gerekir.
- **Ayarlar Koruması (Anti-Tamper):** Aktif bir engel varken Android **Ayarlar > Erişilebilirlik** menüsünden servisi kapatmaya çalışırsan uygulama bunu algılar ve engel ekranına yönlendirir.

### 6. 📊 Günlük İstatistik & Kurtarılan Zaman Özeti
- Ana ekranda **Bugün Toplam Kullanım** (ve uygulama bazlı dağılım), **Engellenen Giriş Sayısı**, **Bilinçli Vazgeçiş Sayısı** ve **Tahmini Kurtarılan Zaman (`~dk`)** canlı olarak takip edilir.

### 7. ⚡ Hızlandırılmış Test Modu
- 1 saat beklemeden tüm senaryoları hızlıca test edebilmek için tek dokunuşla **60 saniyelik pencerede 20 sn tekil / 30 sn toplam limit (10 sn kala uyarı)** moduna geçiş yapılabilir.

---

## 🛠️ Teknoloji Yığını ve Mimari

- **Dil:** Kotlin
- **Arayüz (UI):** Jetpack Compose + Material 3 (Koyu Tema & Özel *Mindful Pause* Adaptive Icon)
- **Arka Plan Algılama:** Android `AccessibilityService` (`DoomBlockerAccessibilityService` & `YouTubeShortsDetector`)
- **Test:** JUnit 4 Birim Testleri (`UsageEngineTest`)
- **Minimum SDK:** Android 8.0 (API 26) / **Hedef SDK:** Android 16 (API 36)

```
app/src/main/kotlin/com/doomblocker/app/
├── MainActivity.kt
├── data/
│   └── UsageRepository.kt              # Kalıcı durum yönetimi, günlük istatistikler & Strict Mode
├── domain/
│   └── UsageEngine.kt                  # Kayan 60 dk hibrit pencere & odak saatleri motoru
├── service/
│   ├── DoomBlockerAccessibilityService.kt # Arka plan paket takibi, uyarı ve engelleme servisi
│   └── YouTubeShortsDetector.kt        # YouTube Shorts görünüm ağacı (View Hierarchy) dedektörü
└── ui/
    ├── block/BlockActivity.kt          # Canlı geri sayımlı engelleme ve Anti-Tamper ekranı
    ├── main/MainScreen.kt              # Ana kontrol paneli (Dashboard), istatistikler ve ayarlar
    └── pause/MindfulPauseActivity.kt   # 3 saniyelik bilinçli duraksama ekranı
```

---

## 🚀 Kurulum ve Çalıştırma

1. Projeyi klonlayın:
   ```bash
   git clone https://github.com/canerma/doomblocker.git
   cd doomblocker
   ```
2. Debug APK dosyasını derleyin:
   ```bash
   ./gradlew assembleDebug
   ```
3. Telefonunuzda **USB Hata Ayıklama** açıkken doğrudan yükleyin:
   ```bash
   adb install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```
4. Uygulamayı açıp **Erişilebilirlik İzni**, **Bildirim İzni** ve **Pil Optimizasyonu Muafiyeti** adımlarını tamamlayın.
