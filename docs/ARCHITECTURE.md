# Walkman Sync — Mimari ve Sözleşmeler

Sahipler: **M** = Mimar (sözleşme, "MİMAR — değiştirilmez"), **B1** = Backend #1 (depolama/SAF, atomik aktarım,
indeks deposu, tarama/coordinator, servis, DI), **B2** = Backend #2 (fark motoru, etiketler, klasörleyici, JSON
codec, ViewModel, ekranlar, ikonlar). Bir ajan YALNIZCA kendi dosyalarını oluşturur/değiştirir; M dosyasında
değişiklik gerekirse kendi dosyasında uyum katmanı yazmaz, Team Lead'e bildirir. Kökler: `core/src/main/kotlin/com/walkmansync/core` = **C**,
`app/src/main/kotlin/com/walkmansync` = **A**, `app/src/main/res` = **R**, testler `core/src/test/kotlin/com/walkmansync/core` = **T**.

## 1. Modüller
- `:core` — saf Kotlin/JVM 17. `android.*`/`androidx.*` importu YASAK. Yalnızca stdlib + kotlinx-coroutines-core;
  `tools/verify-core.sh` 1.6.4 ile derler → 1.6.4'te olmayan ya da `@ExperimentalCoroutinesApi`/`@FlowPreview` API kullanılmaz.
- `:app` — Android (minSdk 30, target/compile 35), Compose + desktop-kit 0.1.3. Mantık burada değil, core'dadır.
- Doğrulama: `bash tools/verify-core.sh` (core derle + JUnit4). Android derlemesi bu ortamda yapılamaz.

## 2. Dosya sahipliği (eksiksiz)
| Yol | Sahip | İçerik |
|---|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/**`, `gradlew*`, `.gitignore` | M | Gradle iskeleti |
| `core/build.gradle.kts`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `tools/verify-core.sh` | M | |
| `docs/ARCHITECTURE.md`, `docs/SPEC.md` | M | `docs/desktop-kit-api.md`, `docs/edge-cases.md`: Analist (salt-okunur) |
| C`/model/{VolumeModels,FileModels,AudioTags,TargetLayout,SyncPlan,Progress,SyncError,SyncState}.kt` | M | veri modelleri |
| C`/fs/{Trees,GuardedTargetTree,RandomAccessSource}.kt` | M | dosya sistemi portları |
| C`/runtime/{Runtime,Modules}.kt` | M | Clock, AppDispatchers, Logger, DI fabrika arayüzleri |
| C`/tags/TagReader.kt`, C`/index/{SyncIndex,ContentKey}.kt`, C`/scan/TreeScanner.kt` | M | |
| C`/path/PathPlanner.kt`, C`/delta/DeltaEngine.kt`, C`/transfer/TransferRunner.kt`, C`/workflow/Workflow.kt` | M | |
| T`/testing/InMemoryTrees.kt`, T`/contract/ContractInvariantsTest.kt` | M | ortak sahteler (ikisi de kullanır) |
| A`/{AppGraph,WalkmanSyncApp}.kt`, A`/storage/VolumeRegistry.kt`, A`/sync/SyncController.kt`, A`/service/SyncServiceContract.kt` | M | app sözleşmeleri |
| `app/src/main/AndroidManifest.xml` | M | |
| R`/values/{strings,themes,colors}.xml`, R`/xml/usb_mass_storage_filter.xml`, R`/mipmap-anydpi/ic_launcher.xml`, R`/drawable/{ic_launcher_foreground,ic_launcher_monochrome,ic_stat_sync}.xml` | M | |
| C`/engine/**` (zorunlu: `EngineModules.kt`; öneri: `DefaultTreeScanner`, `TargetIndexStore`, `AtomicTransferRunner`, `SpeedMeter`, `DefaultScanWorkflow`, `DefaultSyncCoordinator`) | B1 | `com.walkmansync.core.engine` |
| T`/engine/**` | B1 | B1 testleri |
| A`/AppContainer.kt`, A`/platform/**` (`AndroidLogger`) | B1 | DI kökü |
| A`/storage/**` (VolumeRegistry.kt HARİÇ; `SafVolumeRegistry`, `VolumeEventReceiver`, `SafSourceTree`, `SafTargetTree`, `SafCursor`, `PfdRandomAccessSource`, `BindingStore`, `FileSystemProbe`) | B1 | |
| A`/sync/**` (SyncController.kt HARİÇ; `DefaultSyncController`) | B1 | |
| A`/service/**` (SyncServiceContract.kt HARİÇ; `SyncForegroundService`, `SyncNotifications`) | B1 | |
| R`/values/strings_service.xml`, R`/values-tr/strings_service.xml` | B1 | bildirim/kanal metinleri |
| C`/domain/**` (zorunlu: `DomainModules.kt`, `DefaultTagService.kt`; öneri: `DefaultDeltaEngine`) | B2 | `com.walkmansync.core.domain` |
| C`/tags/parsers/**` (`CompositeTagReader`, ID3/MPEG, FLAC, MP4, Ogg, RIFF, DSD, AAC, metin çözme) | B2 | |
| C`/path/impl/**` (`FatFileNameSanitizer`, `DefaultPathPlanner`), C`/index/json/**` (`JsonIndexCodec`, küçük JSON okuyucu/yazıcı) | B2 | |
| T`/domain/**` | B2 | B2 testleri |
| A`/MainActivity.kt`, A`/ui/**` (ViewModel, ekranlar, `WalkmanIcons`), A`/media/**` (`MediaMetadataTagFallback`) | B2 | |
| R`/values/strings_ui.xml`, R`/values-tr/strings_ui.xml`, R`/drawable/ic_ui_*.xml` (gerekirse) | B2 | |

`values/` İngilizce, `values-tr/` Türkçe. Testler yalnızca sözleşmelere + kendi sahtelerine dayanır; diğer ajanın
uygulama sınıfını import etmez (B1'in `TargetIndexStore` testi kendi sahte `IndexCodec`'ini yazar). verify sırasında
diğer ajanın dosyasında hata çıkarsa dokunmayın; raporlayın.

## 3. Bağlama (manuel DI, Hilt yok)
`WalkmanSyncApp.graph` → `AppContainer` (B1) kurulum sırası `AppGraph.kt` belgesinde. Ajanlar birbirine yalnızca
`DomainModules.create(...)` (B2) ve `EngineModules.create(...)` (B1) imzalarıyla (bkz. `runtime/Modules.kt`) ve
`com.walkmansync.media.MediaMetadataTagFallback(app: Application, logger: Logger) : TagFallback` (B2) ile bağlanır.
UI → `SyncViewModel` (B2) → `graph.syncController` (`SyncController`, tek köprü). UI başka bir şeyi görmez.
`MainActivity` (B2): `DesktopKitRoot`/`DesktopShell` kurar; API 33+ `POST_NOTIFICATIONS` ister (ret → yalnızca
uygulama içi ilerleme); `onCreate`/`onNewIntent`'te `USB_DEVICE_ATTACHED` gelirse
`syncController.refreshVolumes(afterUsbAttach = true)` (hemen + 1,5/3/5 sn sonra yeniden okuma), `onStart`'ta düz yenileme.
USB olay zinciri: `USB_DEVICE_ATTACHED` manifest ALICISINA gelmez; yalnızca (a) manifestteki etkinlik intent-filter'ı
(singleTask → onCreate/onNewIntent) ve (b) `SafVolumeRegistry`'nin dinamik alıcısı alır; ikisi de
`VolumeRegistry.refresh(withRetries = true)` çağırır. `MEDIA_*` hem manifest hem dinamik alıcıyla `refresh()` tetikler.

## 4. Veri akışı
```
SafVolumeRegistry ──snapshot: StateFlow<VolumeSnapshot>──▶ SyncCoordinator ──state──▶ SyncController ──▶ SyncViewModel ──▶ ekranlar
   ▲ StorageVolumeCallback / VolumeEventReceiver               │ scan()
   │                                                           ▼
   └── TreeOpener.openSource/openTarget ◀──────────── ScanWorkflow: IndexStore.load → TreeScanner(hedef MUSIC)
                                                              → .part temizliği → TreeScanner(kaynak) → DeltaEngine
                                                              (TagService, PathPlanner, FlatFolderRule) → SyncPlan
                                                       startTransfer(sel) → plan.resolveSelection → TransferRunner
                                                              (.part yaz/sync/doğrula/rename, IndexStore.save her N)
SyncForegroundService: state'i izler, bildirim + WakeLock; iş coordinator'ın appScope'unda koşar.
```

## 5. Thread / coroutine kuralları
- `appScope = SupervisorJob + Dispatchers.Main.immediate`. Coordinator durumu yalnızca bu scope'ta değiştirir.
- G/Ç her zaman `dispatchers.io` (SAF cursor, akışlar, fd); saf hesap `dispatchers.cpu`. Port uygulamaları kendi
  içinde `withContext` yapar; çağıran dispatcher seçmez. `Dispatchers.*` core'da yalnızca `AppDispatchers.Default`'ta.
- Eşzamanlılık sınırları: dizin listeleme ≤ 4, etiket okuma ≤ 4 (`Semaphore`); kopyalama tek dosya, sıralı.
- **Tek iş kuralı:** Coordinator'da tek `Job` + `Mutex`; tarama ve aktarım asla aynı anda koşmaz → hedef kart ve
  indeks dosyası için başka kilit gerekmez (IndexStore yine örnek başına `Mutex` tutar).
- **İptal:** `CancellationException` asla yutulmaz/sarılmaz. Suspend çağrıları saran `runCatching` YASAK; `catch (e: Exception)`
  bloklarında önce `if (e is CancellationException) throw e`. Her tampon döngüsünde `ensureActive()`. Temizlik
  (`.part` sil, indeks kaydet) `withContext(NonCancellable)` içinde. Kart çıkınca coordinator işi `cancel` eder.
- Kaynak sahipliği: `openInput`/`openRandomAccess`/`openOutput` döndürdüğünü çağıran `use {}` ile kapatır (SAF
  uygulaması fd'yi `openOnIo` ile açar: `withContext` dönüşünde iptal olursa açılan fd orada kapatılır);
  `TagReader`/`TagFallback` kaynağı kapatmaz; SAF cursor'ları `use {}`; ParcelFileDescriptor akışla birlikte kapanır.
- StateFlow yayınları ≤ 10/sn (tarama), ≥ 200 ms aralık (aktarım); bildirim ≤ 1/sn.
- Zaman yalnızca `Clock` ile (test edilebilirlik). Kayıt zaman damgası `nowMillis`, hız `monotonicNanos`.

## 6. Hata modeli
- Portlar `SyncException(SyncError)` fırlatır; `SyncError.isFatal` aktarımın durup durmayacağını belirler.
- SAF eşlemesi (B1): `FileNotFoundException` → `SourceGone`; `SecurityException` → `PermissionLost(role)`;
  mesajında ENOSPC/"No space" → `InsufficientSpace`; diğer `IOException` → `IoFailure(fatal=false)`, ardından
  runner `isAvailable()` ile kart kontrolü yapar → yoksa `VolumeMissing` (ölümcül).
- Dosyaya özgü hatalar `ItemFailure` olur, aktarım sürer. Ölümcül → `SyncState.Failed(error, recoverable, summary)`;
  `recoverable=false` yalnızca `IndexUnsupported` ve `Unexpected`.
- Etiket okuma hataları asla taramayı durdurmaz (null). `TargetGuardViolation` programlama hatasıdır → `Unexpected`.
- UI metinleri `SyncError` tipine göre `strings_ui.xml`'den üretilir; `message` yalnızca günlük içindir.

## 7. UUID güvenlik kuralları
1. Kimlik = `StorageVolume.uuid` (büyük harf, `VolumeId`). `primary`/dahili depolama asla kaynak/hedef olamaz.
2. Ağaç URI docId öneki ("UUID:") kayıtlı UUID ile eşleşmek zorunda (`DocumentIds`), her `bind` ve her
   `openSource/openTarget` çağrısında yeniden doğrulanır; önbelleğe alınmaz.
3. Kaynak ≠ hedef (`VolumePair.init`, `SAME_AS_OTHER_ROLE`). Takılı birimlerde aynı UUID iki kez → `DUPLICATE_UUID` /
   `VolumeStatus.Conflict`; o UUID ile hiçbir işlem yapılmaz.
4. Kaynak izni yalnızca READ alınır ve dosyalar yalnızca "r" kipinde açılır; hedef READ|WRITE.
5. Hedef ağaç = volume kökü ya da `MUSIC` (`TargetLayout.isValidTargetRoot`). İndeks bu kökte durur ve içindeki
   `targetVolume` farklıysa yabancı sayılır (klonlanmış kart) → yok sayılır, aktarımdan önce üzerine yazılmaz.
6. Kart değişirse (aynı yuva, farklı UUID) bağlama `NotMounted` olur; yeni karta kullanıcı yeniden bağlamadan yazılmaz.
   Hedef birim `MOUNTED_READ_ONLY` ise durum `VolumeStatus.ReadOnly` (çift oluşmaz; açılışta
   `SyncError.VolumeReadOnly`); kaynak için salt-okunur birim `Ready` sayılır.
7. Aktarım/tarama sırasında çiftin herhangi bir rolü `Ready` dışına düşerse iş iptal edilir.
8. SAF belge kimlikleri kurulurken UUID öneki ve kök klasörün harf büyüklüğü ağaç kökü docId'sinden AYNEN alınır
   (`SafReader.docIdFor`): ExternalStorageProvider ağaç denetimi harf duyarlıdır.

## 8. Append-only garantisi (katmanlı)
- **Tip:** `SourceTree`'de yazma metodu yok; `TreeOpener.openTarget` yalnızca `GuardedTargetTree` döndürür.
- **Koruma sınıfı:** `GuardedTargetTree` (`final`) — yalnızca `*.wsync.part` oluşturur/yazar; yalnızca `x.wsync.part→x`
  (MUSIC altında) ve `INDEX→INDEX.bak` adlandırır; yalnızca `.wsync.part` ve `INDEX.bak` siler; dizin silmez.
- **Protokol:** son ad (büyük/küçük harf duyarsız) varsa yazmadan önce ve rename'de `TargetExists` → asla üzerine yazma.
- **Fark motoru:** yalnızca kopyalama planlar; değişmiş/silinmiş kaynaklar hedefte korunur (`CHANGED_SINCE_SYNC`,
  `REMOVED_FROM_TARGET` yalnızca kullanıcı zorlarsa yeni adla kopyalanır).
- **İndeks:** kayıtlar yalnızca eklenir; atomik `.part → (INDEX→.bak) → INDEX`.
- Ortak sahteler (`InMemoryTargetTree`) de `GuardedTargetTree`'yi genişletir; testler kuralları gerçek yolda sınar.

## 9. Kesinleşmiş ürün kuralları
- Düz klasör: `FlatFolderRule` (path/PathPlanner.kt) — kökte/`Music/` doğrudan, `Download`/`Telegram`/… altı ya da tek
  seviyeli klasör → etiketlere göre `MUSIC/{Artist}/{Album}/{NN} - {Title}.{ext}`; aksi hâlde göreli yapı korunur.
- `.part` adı: `<ad>.wsync.part` (başka araçların `.part` dosyalarına dokunmamak için).
- Tekilleştirme: önce yol+boyut (indeks), sonra ad+boyut (hedef), sonra `ContentKey` (±1 süre kovası, 2 sn).
- FAT32/UNKNOWN (Team Lead kararı): > 4 GiB-1 dosya FAT32'de `TOO_LARGE_FOR_FAT32` ile atlanır ve ZORLANAMAZ; UNKNOWN'da
  aynı nedenle atlanır ama ZORLANABİLİR (`forceable`, `CopyReason.FORCED`, plan uyarısı `TARGET_FS_UNKNOWN`; UI'da
  "Atlananlar" sekmesinden seçilir). Runner UNKNOWN+FORCED'ı dener; kart gerçekte FAT32 ise yazma EFBIG ("File too large")
  ile düşer → dosyaya özgü `ItemFailure(FileTooLargeForFat32)`, `.part` silinir, aktarım sürer. exFAT sınırsız.
  Dosya sistemi tespiti (B1 `FileSystemProbe`): `/proc/mounts` kesin türü; Android 11+'da çoğu kartta yalnızca FUSE
  görünür → `UNKNOWN`.
- Yardımcı dosya yerleşimi: TAG_BASED kipte eşi olmayan `.lrc`/`cover.jpg` `ORPHAN_AUXILIARY` ile atlanır (motor
  `planStandalone` çağırmaz); `planStandalone` yine de toplamdır: `MUSIC/Unknown Artist/Unknown Album/<ad>`.
- Boş alan: başta toplam seçim, her dosyada `size + 8 MiB` kontrolü.
- Tablette `MediaScannerConnection` çağrılmaz; hedefe `.nomedia` yazılmaz.
