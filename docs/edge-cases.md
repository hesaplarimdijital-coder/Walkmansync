# Uç durumlar: çift çıkarılabilir depolama senkronu (tablet microSD = kaynak, salt-okunur; Walkman NW-A307 microSD /MUSIC = hedef, okuma/yazma)

Biçim: durum -> önerilen davranış.

## Depolama ve SAF
- Tree URI docId `"<VOLUME_UUID>:<yol>"` taşır -> UUID'yi kaynak/hedef kimliği olarak sakla, ama tek başına güvenme; kullanıcı eşleşmesini (rol: kaynak/hedef) kendi ayarında tut.
- İki kartın FAT/exFAT volume UUID'si aynı (klonlanmış/aynı biçimlendirme) -> UUID'ye dayalı rol ayrımı bozulur; ikinci doğrulama olarak kök dizin işaret dosyası (`.walkmansync-id`, hedefe yazılır) veya toplam boyut/etiket kullan; çakışmada kullanıcıya açıkça seçtir, otomatik eşleme yapma.
- Kart çıkarılıp başka kart takıldı (aynı yuva, farklı UUID) -> kalıcı URI artık o kart değil: eski URI'yi geçersiz say, çalışan işi durdur, "beklenen kart değil" göster, yeni karta yazmadan önce kullanıcıdan onay al.
- Persisted URI iznini kullanıcı iptal etti / sistem düşürdü / izin sayısı sınırı (Android 11-: 128, 12+: 512) -> `takePersistableUriPermission` sonucunu ve `contentResolver.persistedUriPermissions` listesini her açılışta doğrula; yoksa tree seçiciyi yeniden aç; eski, kullanılmayan izinleri `releasePersistableUriPermission` ile temizle.
- USB-C okuyucudaki kart SAF seçicide görünmeyebilir (Android 11+ bazı OEM/okuyucularda yalnızca seçici "Dosyalar" kökünde, bazen hiç; kök seçim kısıtları) -> "Kart görünmüyor" yardım ekranı (yeniden tak, farklı okuyucu/kablo, Samsung "Dosyalarım"da görünüyor mu); `StorageManager.getStorageVolumes()` ile bağlı birimi listele; hedefi bulamazsan çalışmayı başlatma.
- Seçici SD kartın kökünü ("Bu klasörü kullan") Android 11+ ile kök/Download/Android klasörleri için reddedebilir -> hedef için `/MUSIC` alt klasörünü seçtir veya oluştur; kaynakta müzik klasörünü seçtir, kök seçmeyi zorlama.
- Kaynak salt-okunur -> kaynağa yazma/silme/yeniden adlandırma/`.nomedia` yazma çağrısı yapma; kaynakta durum dosyası tutma (veritabanı uygulama deposunda). Yazma izni yokluğunu hata değil beklenen durum say.
- SAF yavaştır (`DocumentFile` her çağrıda sorgu) -> `DocumentsContract.buildChildDocumentsUriUsingTree` + tek sorguda `COLUMN_DISPLAY_NAME/SIZE/LAST_MODIFIED/MIME` ile tara; `DocumentFile.listFiles()` döngüsünden kaçın.
- Kopya sırasında Walkman USB'den çıkarıldı/kart çıkarıldı (ACTION_MEDIA_*, `IOException` ENODEV/EIO) -> işi durdur, kısmi dosyayı `.part` olarak bırak, durumu "kesildi" yaz, kart dönünce sürdür (baştan başla; yarım dosyayı yeniden kullanma).
- Kaynak kart kopya sırasında çıkarıldı -> aynı: okuma hatasında dosyayı atla, tüm işi "kaynak yok" ile askıya al, çıkış kodu/sebebi kaydet.
- Yarım `.part` dosyalar (çöküş, kapatma) -> hedefe önce `adı.part` yaz, boyut+(tercihen) sağlama doğrulanınca yeniden adlandır; açılışta artık `.part` dosyaları listele ve sil/devam et. Walkman `.part` dosyalarını müzik olarak göstermez ama alan kaplar.
- Hedefte beklenmeyen ek dosya (kullanıcı Sony Music Center ile ekledi) -> varsayılan silme yapma; "hedefte fazla" listesini göster, silmeyi ayrı onaya bağla; silmeleri önce önizle (kuru çalışma).

## Dosya sistemi
- FAT32 tek dosya 4 GiB - 1 B sınırı (DSD/DSF/DFF, uzun DXD, bazı WAV) -> kopyadan önce boyutu kontrol et; hedef FAT32 ise atla ve listele ("sığmıyor"); hedef exFAT ise sorun yok. Hedef dosya sistemini `StorageVolume`/yazma denemesiyle tahmin et, bilmiyorsan 4 GiB üstünü uyar.
- FAT/exFAT yasak karakterler (`\ / : * ? " < > |`), sonda nokta/boşluk, denetim karakterleri -> hedef adında güvenli karaktere çevir (örn. `_`), eşlemeyi veritabanında tut; kaynak adı değişmez. SAF zaten bazı karakterleri sessizce değiştirir: yazdıktan sonra gerçek `displayName`'i oku.
- Yol uzunluğu: exFAT'te 255 karakter/öge, tam yol Walkman/Android'de pratikte ~255-260; çok derin klasör -> klasör düzenini düzleştir (`Sanatçı/Albüm/NN Parça`), uzun adları kısalt ama benzersizliği koru (kısa hash eki).
- Büyük/küçük harf duyarsızlığı: FAT/exFAT `Song.flac` ile `song.FLAC` aynıdır; kaynak (ext4/sdFAT) ayrı olabilir -> hedef karşılaştırmasını `lowercase`/Unicode-fold ile anahtarla; çakışmada ikinci dosyaya `(2)` ekle ve uyar, üzerine yazma.
- FAT dosya adı kodlaması: SAF/Android dosya adlarını UTF-8 görür ama Walkman dizinleme ve eski okuyucular uzun/Unicode adlarda takılabilir -> dosya adında emoji/nadir semboller için ASCII'ye düş seçeneği sun.
- Unicode NFC/NFD: macOS/iOS kökenli adlar NFD (`ö` = o + ¨) gelir; aynı ad iki biçimde -> karşılaştırmada NFC'ye normalleştir, hedefe NFC yaz; ikisi aynı dosyaysa çift kopya üretme.
- Boş yer: hedef doluyken yazma -> işe başlamadan toplam gereken boyutu hesapla (+ %2 / en az 100 MB pay: FAT küme israfı, `.part`), yetmezse planı göster ve dur; yazma sırasında `ENOSPC` -> işi güvenle durdur, kısmi dosyayı sil, yeniden başlat deme.
- FAT zaman damgası: mtime 2 sn hassasiyet, saat dilimi yok/yerel saat; ayrıca cihaz saati yanlış olabilir -> değişiklik tespitini mtime'a değil `boyut + (varsa) sağlama/ilk-N-KB karması`na dayandır; mtime'ı yalnızca ipucu say, ±2 sn ve ±1 saat (DST) toleransı tanı.
- `SAF setLastModified` çoğu zaman desteklenmez -> hedefte kaynak mtime'ını korumaya çalışma; karşılaştırma verisini kendi veritabanında tut.

## Müzik içeriği
- Aynı parça farklı formatta (FLAC/MP3/ALAC/WAV) -> tekilleştirmeyi (etiket: sanatçı+albüm+parça no+süre) kullanıcı kuralına bırak: "en yüksek kalite", "hedef format tercihi" veya "ikisini de al"; sessizce birini silme.
- Etiket eksik/bozuk (ID3 yok, tek tırnaklı kodlama, UTF-16/Latin-1 karışımı) -> etiket yoksa dosya yolundan türet, "bilinmeyen sanatçı" kovası kullan; Türkçe karakterlerde kod sayfası hatasına karşı UTF-8/Latin-5 (Windows-1254) dene; karar verilemeyen dosyayı "incele" listesine al.
- Albüm kapağı/.lrc/.cue yan dosyaları -> parçayla birlikte taşı (aynı klasör), kaynak klasör yapısını bozma; `.nomedia` varsa kopyalama ve uyar (Walkman'de görünmez olabilir).
- Çalma listeleri (.m3u/.m3u8) -> yolları hedef kök-göreli yeniden yaz; yol ayracı `/`, kodlama UTF-8.

## Android uygulama yaşam döngüsü
- Uzun kopya ön planda çalışmalı -> `ForegroundService` (`foregroundServiceType="dataSync"`, `FOREGROUND_SERVICE_DATA_SYNC` izni). Android 15+ `dataSync` için 24 saatte ~6 saat toplam sınır; süre dolunca `Service.onTimeout(startId, fgsType)` çağrılır -> orada 5 sn içinde `stopSelf()`, ilerlemeyi kaydet, "devam et" bildirimi göster; sürekli yeniden başlatma döngüsü kurma. Android 14'te tür bildirmek ve izin zorunlu.
- `POST_NOTIFICATIONS` (API 33+) reddedildi -> servis yine çalışır ama bildirim görünmez (FGS görev yöneticisinde kalır); ilk senkronda gerekçeyi göster, reddedilirse uygulama içi ilerleme çubuğuna düş.
- Doze / uygulama bekleme (App Standby) / Samsung pil optimizasyonu ("uyuyan uygulamalar") -> uzun işi `WorkManager` + `setForeground` ile yürüt; kullanıcıdan bir kez pil kısıtlamasını kaldırmasını iste (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` yalnızca gerekçeli); kopya CPU wakelock'a ihtiyaç duyuyorsa kısa `PARTIAL_WAKE_LOCK` kullan, süreli bırak.
- Ekran kapanınca / DeX'ten çıkılınca USB ve SD erişimi askıya alınabilir -> okuma/yazma hatasında otomatik yeniden dene (artan bekleme, en çok N), sonra "duraklatıldı".
- DeX'te pencere yeniden boyutlandırma / mod değişimi (DeX <-> tablet) Activity'yi yeniden oluşturur (configChanges eksikse) -> işi Activity'ye bağlama; durum ViewModel + servis/Worker'da, `rememberSaveable` ile seçimleri koru; manifestte `configChanges=...|screenSize|smallestScreenSize|screenLayout|density|uiMode`, `resizeableActivity="true"` (kit önerisi).
- Uygulama arka planda öldürüldü (düşük bellek, kullanıcı kaydırdı) -> senkron durumu (kuyruk, hangi dosya bitti) her dosyadan sonra kalıcı veritabanına yaz; yeniden açılışta `.part` taraması + kaldığı yerden devam.
- Aynı anda iki senkron (kullanıcı iki kez başlattı, WorkManager + elle) -> tek kilit/"tek aktif iş" kuralı; ikinci istek kuyruğa ya da reddedilir.
- Kart takılıyken `ACTION_MEDIA_EJECT/UNMOUNTED` ve USB `ACTION_USB_DEVICE_DETACHED` yayınları gelir (kısıtlı): olay güvenilmez, asıl doğrulama her dosya işleminde `IOException` ve her açılışta `StorageVolume.getState()` kontrolüdür.
- Hedef Walkman tarafında kitaplık yenileme -> kopyadan sonra Walkman'in kendisi kütüphaneyi tarar (Android'de MediaScanner tabletin kendi kütüphanesini ilgilendirir); tablette `MediaScannerConnection.scanFile` çağırma, hedef kart tablonun müzik kütüphanesini kirletmesin; hedefte `.nomedia` yazmayı kullanıcı seçimine bırak.
