# Teknik Doküman: Tab S11 Ultra SD Kart -> Sony NW-A307 Walkman SD Kart Müzik Senkronizasyon Uygulaması

## 1. Proje Özeti
Samsung Galaxy Tab S11 Ultra'nın kendi microSD kartında kayıtlı müzik arşivinden, USB-C dongle (kart okuyucu) ile tablete bağlanan Sony NW-A307 Walkman microSD kartına **yalnızca yeni indirilen müzikleri** tespit edip aktaran Android (Kotlin & Jetpack Compose) uygulaması.

## 2. UI/UX ve Tasarım Sistemi (`desktop-kit`)
- **Temel UI Kütüphanesi**: Uygulamanın pencere kabuğu, tablo yapısı, renk tokenları, tipografisi ve klavye/fare/Samsung DeX etkileşimleri doğrudan `https://github.com/hesaplarimdijital-coder/desktop-kit` reposundaki bileşenlerden (`DesktopShell`, `DeskSplitPane`, `DenseTable`, `DeskMenuBar`, `InputModeTracker`, `ShortcutRegistry`) alınacaktır.
- **Yeni Ekran ve İkon Tasarımı**:
  - Repoda bulunmayan ancak bu uygulama için gereken ekranlar (Kaynak/Hedef SD kart eşleştirme ekranı, "Aktarılacak Yeni Müzikler" fark önizleme ekranı, aktarım ilerleme/özet paneli) `desktop-kit` tasarım diline (`DeskColors`, `DeskDimens`, `DeskType`) sadık kalınarak tasarlanacaktır.
  - Eksik olan vektör ikonlar (microSD kart, USB-C dongle, Walkman cihaz ikonu, senkronizasyon/fark durumu, ses formatı rozetleri) `DeskIcons.kt` standartlarına uygun şekilde sıfırdan çizilip eklenecektir.

## 3. Depolama ve Çift SD Kart (Dual-SD) Mimarisi
- **Kaynak Depolama**: Samsung Tab S11 Ultra'nın dahili yuvasındaki microSD kart.
- **Hedef Depolama**: USB-C dongle ile bağlanan Sony NW-A307 Walkman microSD kartı (hedef kök dizin: `/MUSIC`).
- **İzin ve Kart Ayrımı**:
  - Android `Storage Access Framework (SAF)` (`OpenDocumentTree`) ile her iki SD kart için kalıcı URI izni (`takePersistableUriPermission`) alınır.
  - İki çıkarılabilir SD kartın birbirine karışmaması için `StorageManager` üzerinden her iki kartın `StorageVolume` UUID'si kaydedilir; kaynak SD kart salt-okunur (Read-Only), Walkman SD kartı ise okuma/yazma (Read/Write) olarak kilitlenir.
  - USB dongle takıldığında (`ACTION_MEDIA_MOUNTED` / `ACTION_USB_DEVICE_ATTACHED`) hedef Walkman SD kartı otomatik algılanır.

## 4. Artımlı Senkronizasyon (Incremental Sync) Mantığı
- **Tek Yönlü Ekleme (Append-Only)**: Yalnızca tabletin SD kartına yeni eklenen müzikler Walkman SD kartına kopyalanır. Tabletin SD kartından silinen dosyalar Walkman SD kartından **silinmez**.
- **Fark Analizi ve Tekilleştirme**:
  - Her bağlantıda tüm kartı baştan taramamak için Walkman SD kartında hafif bir indeks dosyası (`.walkman_sync_index.json` veya yerel Room DB) tutulur.
  - `DocumentsContract` cursor sorgusuyla önce **Dosya Yolu/Adı + Dosya Boyutu** karşılaştırılır.
  - Yeni indirilen müzikler tabletin SD kartında düz bir klasöre (`Download/` vb.) inmişse, ses etiketleri (`Artist`, `Album`, `Title`, `TrackNumber`, `Duration`) okunarak Walkman SD kartında zaten var olup olmadığı kontrol edilir.
- **Klasörleme ve Formatlar**:
  - Desteklenen ses ve yardımcı dosyalar: `.flac`, `.dsf`, `.dff`, `.wav`, `.m4a`, `.alac`, `.mp3`, `.ogg`, `.opus`, `.aac`, `.lrc`, `.m3u8`, `cover.jpg`.
  - Düz klasörden gelen yeni şarkılar Walkman SD kartına aktarılırken etiket bilgilerine göre otomatik olarak `/MUSIC/{Sanatçı}/{Albüm}/{ParçaNo} - {Başlık}.{uzantı}` klasör yapısına yerleştirilir (exFAT/FAT32 için geçersiz dosya adı karakterleri temizlenir).

## 5. Güvenli Dosya Aktarımı ve Arka Plan Servisi
- **Atomik Kopyalama**: Aktarım sırasında USB dongle bağlantısının kopması durumunda Walkman'de bozuk dosya oluşmaması için dosyalar önce `.part` uzantısıyla yazılır; kopyalama ve boyut doğrulaması bittiğinde gerçek dosya adına çevrilir.
- **Arka Plan Çalışması**: Çoklu dosya aktarımlarının DeX'te pencere değiştirildiğinde veya ekran kapandığında kesilmemesi için işlem `ForegroundService` (`dataSync`) üzerinde yürütülür ve anlık ilerleme durumu (kalan parça, aktarım hızı, duraklat/iptal) arayüze aktarılır.

## 6. Kalite Odaklı ve Token-Optimizasyonlu Ajan Orkestrasyon Sistemi

Bu projenin geliştirilmesinde **birinci öncelik en yüksek ürün ve kod kalitesidir**. Kaliteden ödün vermeden token kullanımını minimize etmek için `Fable`, `Opus`, `Sonnet` ve `Haiku` modelleri (sürüm numarası belirtilmeden) aşağıdaki görev dağılımıyla çalıştırılır. **Her aşamada harcanacak düşünme ve akıl yürütme eforuna (effort), görevin kritikliğine ve karmaşıklığına göre Claude kendisi otonom olarak karar verir.**

### 6.1 Ajan Rolleri ve Model Eşleşmesi
1. **Business Analyst & Keşif Ajanı (`Haiku` / `Sonnet`)**:
   - **Görev**: `desktop-kit` reposunu tarayarak yalnızca kullanılacak bileşenlerin (`DesktopShell`, `DeskSplitPane`, `DenseTable`, `DeskIcons`, `DeskColors`, `DeskDimens`) public API imzalarını çıkarır ve çift SD kart uç durum (edge-case) listesini oluşturur.
   - **Token Tasarrufu**: Tüm repo kodunu diğer ajanların bağlamına (context) taşımaz; yalnızca 1 sayfalık "API ve Kısıt Özeti" üretir.
2. **Teknik Mimar Ajanı (`Opus` / `Fable`)**:
   - **Görev**: Kod yazımı başlamadan önce modüller arası Kotlin `interface` sözleşmelerini, veri modellerini (`SyncItem`, `VolumePair`, `SyncState`) ve çift SD kart UUID güvenlik kurallarını tanımlar.
   - **Token Tasarrufu**: Backend ajanlarının birbirinin iç kodunu okumasına gerek kalmadan paralel çalışabilmesi için kesin sınırlar (Contract-First) çizer.
3. **Backend Ajanı #1 — Depolama, SAF & Atomik Aktarım (`Sonnet`)**:
   - **Görev**: Yalnızca Teknik Mimar'ın belirlediği depolama arayüzünü baz alarak; çift SD kart `StorageVolume` UUID kilidini, `DocumentsContract` hızlı cursor tarayıcısını, `.part` uzantılı atomik kopyalamayı ve `ForegroundService` katmanını eksiksiz kodlar.
4. **Backend Ajanı #2 — Delta Sync, Ses Etiketleri & UI/İkonlar (`Sonnet`)**:
   - **Görev**: Artımlı fark motorunu (`Yol+Boyut` ve `ID3/FLAC Metadata` karşılaştırması), `/MUSIC/{Sanatçı}/{Albüm}/` otomatik klasörleyicisini, `desktop-kit` tabanlı ekranları ve eksik vektör ikonları (`DeskIcons`) kodlar.
5. **Team Lead & Kalite Denetim Ajanı (`Opus` / `Fable`)**:
   - **Görev**: Üretilen tüm modülleri birleştirir; `FileDescriptor` sızıntılarını, USB kopma güvenliğini, thread/coroutine senkronizasyonunu ve `desktop-kit` uyumluluğunu en üst kalite standartlarında denetler. Eksik veya hatalı kısımları yalnızca ilgili dosyada nokta atışı (diff bazlı) düzelterek nihai ürünü teslim eder.

### 6.2 Kaliteyi Koruyarak Token Minimize Etme Kuralları
- **Bağlam İzolasyonu (Context Isolation)**: Alt ajanlara tüm konuşma geçmişi gönderilmez; her ajana yalnızca kendi göreviyle ilgili `interface` sözleşmesi ve `desktop-kit` imza özeti verilir.
- **Sıfır İskelet / Sıfır Tekrar (Zero-Placeholder Policy)**: Yeniden üretim (re-generation) turlarını ve token israfını önlemek için hiçbir ajan `// TODO` veya yarım fonksiyon bırakmaz; her modül ilk seferde derlenebilir ve üretim kalitesinde yazılır.
- **Dinamik Efor Yönetimi**: Basit yapılandırma ve dosya tarama adımlarında düşük efor, çift SD kart I/O güvenliği, ses metadata eşleştirme ve mimari entegrasyon adımlarında ise yüksek/maksimum efor Claude tarafından dinamik olarak seçilir.
