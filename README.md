# Walkman Sync

Galaxy Tab S11 Ultra'nın kendi microSD kartındaki müzik arşivinden, USB-C kart okuyucuya takılı Sony NW-A307
Walkman microSD kartına **yalnızca yeni müzikleri** aktaran Android uygulaması (Kotlin + Jetpack Compose,
arayüz [desktop-kit](https://github.com/hesaplarimdijital-coder/desktop-kit) 0.1.3; DeX ve klavye/fare uyumlu).

- **Yalnızca ekleme (append-only):** Walkman kartında hiçbir dosyanın üzerine yazılmaz, hiçbir dosya silinmez ya da
  yeniden adlandırılmaz. Uygulama yalnızca kendi `*.wsync.part` geçici dosyalarına ve indeks dosyasına dokunur.
- **Fark analizi:** kartın kökündeki `.walkman_sync_index.json` + yol/boyut + ses etiketleri (sanatçı, albüm, parça,
  başlık, süre). Düz klasörlere (`Download/`, `Telegram/`…) inen şarkılar `MUSIC/{Sanatçı}/{Albüm}/{NN} - {Başlık}`
  düzenine yerleştirilir; FAT/exFAT için geçersiz karakterler temizlenir.
- **Güvenli kopya:** `ad.wsync.part` yaz → `fsync` → boyut doğrula → gerçek ada çevir. Kart çekilirse yarım dosya
  bir sonraki taramada temizlenir.
- **Kart kilidi:** iki kart `StorageVolume` UUID'siyle eşlenir ve her açılışta yeniden doğrulanır. Kaynak yalnızca
  okuma izniyle ve `"r"` kipinde açılır.
- **Arka plan:** tarama/aktarım `dataSync` ön plan servisiyle sürer; bildirimden duraklat/sürdür/iptal.

## Kurulum
1. Projeyi **Android Studio** (Ladybug ya da daha yeni) ile açın; Gradle JDK olarak **JDK 17** seçin.
2. Bağımlılıklar Google Maven, Maven Central ve **JitPack**'ten (`desktop-kit`) iner; `settings.gradle.kts`
   bunları zaten tanımlar, ilk eşitleme için internet gerekir.
3. `app` yapılandırmasını tablete (Android 11 / API 30 ve üstü) kurun. Komut satırı: `./gradlew :app:installDebug`.

## İlk kullanım
1. **Kartlar** ekranında "Kaynak kart" için *Klasör seç*: tabletin SD kartındaki müzik klasörünü (ya da kart
   kökünü) seçin. İzin yalnızca okuma içindir.
2. Walkman kartını USB-C okuyucuyla takın, "Hedef kart" için *Klasör seç*: kartın **kökünü** ya da **MUSIC**
   klasörünü seçin (başka klasör reddedilir). Bildirim izni sorulursa verin (vermezseniz ilerleme yalnızca uygulamada görünür).
3. **Tara** (Ctrl+R): uygulama indeksi okur, Walkman'i ve kaynağı tarar, etiketleri okur.
4. **Aktarılacak Yeni Müzikler** ekranında listeyi önizleyin; satırları dahil edin/çıkarın. "Atlananlar" sekmesindeki
   zorlanabilir öğeleri (ör. aynı içerik başka yolda, dosya sistemi algılanamayınca 4 GB üstü DSD) isterseniz seçin.
5. **Aktar** (Ctrl+Enter): ilerleme, hız ve kalan süre görünür; Space duraklatır, Esc iptal eder. Bitince özet ve
   başarısız öğeler listelenir.

## Mimari özeti
- `:core` — saf Kotlin/JVM (Android bağımlılığı yok): veri modelleri ve sözleşmeler, etiket ayrıştırıcıları
  (MP3/FLAC/M4A/OGG/Opus/WAV/DSF/DFF/AAC), fark motoru, yol planlayıcı, JSON indeks, atomik aktarım motoru,
  tek-iş durum makinesi (`SyncCoordinator`). Append-only kuralı `GuardedTargetTree`'nin `final` metotlarıyla zorlanır.
- `:app` — SAF/StorageManager uygulamaları (`SafVolumeRegistry`, `SafSourceTree`, `SafTargetTree`), ön plan servisi,
  `MediaMetadataRetriever` etiket yedeği, Compose ekranları (`SyncViewModel` → `SyncController` tek köprü).
- Ayrıntılı sözleşmeler: `docs/ARCHITECTURE.md`; ürün: `docs/SPEC.md`; uç durumlar: `docs/edge-cases.md`;
  denetim kayıtları: `docs/QA-LOG.md`.

## Test
```
bash tools/verify-core.sh
```
`:core`'u Gradle'ın gömülü Kotlin derleyicisiyle çevrimdışı derler ve JUnit4 testlerini (181 test) çalıştırır.
Android Studio'da `./gradlew :core:test` aynı testleri koşar.

## Bilinen sınırlamalar
- **Bu teslimat Android derleyicisinden geçmedi.** Geliştirme ortamında Android SDK/Maven erişimi yoktu; `:app`
  yalnızca gerçek API imzalarından yazılmış taklitlere karşı tür denetiminden geçirildi. İlk Gradle derlemesinde küçük
  düzeltmeler (import, parametre adı, sürüm uyumu) gerekebilir.
- Gerçek cihazda doğrulanmadı: USB-C okuyucudaki kartın SAF seçicide görünmesi (bazı okuyucu/OEM'lerde görünmeyebilir),
  `MEDIA_*`/USB olaylarının zamanlaması, Samsung pil optimizasyonu, DeX pencere geçişleri, Android 15 `dataSync`
  6 saat sınırı (`onTimeout` aktarımı duraklatır; kullanıcı sürdürür).
- Android 11+'da dosya sistemi türü çoğu kartta okunamaz (`UNKNOWN`): 4 GB üstü dosyalar varsayılan olarak atlanır,
  elle zorlanabilir. Kart aslında FAT32 ise kopya `EFBIG` ile güvenle başarısız olur (yarım dosya silinir).
- Etiketsiz `.lrc`/`cover.jpg` (düz klasörde, eşleşen ses yoksa) aktarılmaz ("yetim yardımcı dosya").
- Çalma listesi (`.m3u8`) içindeki yollar yeniden yazılmaz; olduğu gibi kopyalanır.
- Aynı UUID'li iki kart (klonlanmış) güvenlik gereği kullanılmaz; kartlardan birini yeniden biçimlendirin.
- Tabletten silinen dosyalar Walkman'den silinmez (tasarım gereği); Walkman'de silinen parça yeniden kopyalanmaz
  (zorlanabilir).
