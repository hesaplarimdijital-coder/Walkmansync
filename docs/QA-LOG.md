# QA günlüğü (Team Lead denetimi)

Her satır: dosya — sorun — düzeltme. Ortam yeniden başlarsa sonraki deneme buradan devam eder.

- (başlangıç) verify-core: 178 test geçti; :app imports/desktop-kit imzaları/R.string anahtarları tarandı — eksik import/anahtar yok.
- storage/SafTreeSupport.kt, SafTargetTree.kt — resolve/doRename docId'yi büyük harfli UUID + istenen harfle kuruyordu; sağlayıcının enforceTree'si harf duyarlı → "MUSIC" kökü "Music" iken SecurityException/yanlış PermissionLost — `docIdFor()` kök önekini ağaç docId'sinden aynen alır.
- core SyncState/SyncError + registry/UI/bildirim — salt-okunur hedef NotMounted olarak "takılı değil" diye gösteriliyordu — `VolumeStatus.ReadOnly` + `SyncError.VolumeReadOnly` eklendi, tüm when dalları ve metinler (en/tr) güncellendi.
- core AtomicTransferRunner/EngineErrors — UNKNOWN fs'te zorlanan >4 GiB dosya FAT32 kartta EFBIG ile düşünce genel IoFailure oluyordu — EFBIG → dosyaya özgü FileTooLargeForFat32 (+ .part temizliği), test eklendi (179 test).
- path/PathPlanner.kt (+DefaultPathPlanner, test) — TAG_BASED .lrc/cover için planStandalone tanımsızdı — sözleşmeye yazıldı: motor çağırmaz (ORPHAN_AUXILIARY), çağrılırsa geçerli `MUSIC/Unknown Artist/Unknown Album/<ad>` döner; test eklendi.
- storage/VolumeRegistry + SyncController + MainActivity + VolumeEventReceiver — USB yeniden denemeli yenileme yalnızca `as? SafVolumeRegistry` downcast'iyle alıcıdaydı; MainActivity USB intent'inde tek seferlik yeniliyordu — `refresh(withRetries)` / `refreshVolumes(afterUsbAttach)` sözleşmeye alındı, etkinlik zinciri de gecikmeli yeniler.
- storage/SafTreeSupport.kt, SafTargetTree.kt — withContext iptal edilmiş coroutine'e dönerken açılan fd'yi atıyordu (sızıntı) — `openOnIo()` iptalde kaynağı kapatır (openInput/openRandomAccess/openOutput).
- storage/SafTargetTree.kt doRename — kart sökülünce gelen IllegalStateException TargetExists sayılıyordu — birim yoksa VolumeMissing.
- service/SyncForegroundService.kt — PAUSE/RESUME/CANCEL startId'leri izlenmediğinden finish() sonrası servis asılı kalabiliyordu — latestStartId her komutta güncellenir.
- app/build.gradle.kts, libs.versions.toml — navigation-compose ve lifecycle-viewmodel-savedstate doğrudan import edildiği hâlde yalnızca geçişliydi; kullanılmayan lifecycle-service — açık bağımlılık eklendi/kaldırıldı. SafVolumeRegistry/SyncNotifications getSystemService nullable → requireNotNull.
- docs/ARCHITECTURE.md — FAT32/UNKNOWN zorlama kararı, ReadOnly durumu, docIdFor, openOnIo, USB olay zinciri, planStandalone kararı belgelendi.
- storage/SafTreeSupport.kt resolve, SafTargetTree.kt doDelete — ExternalStorageProvider ağaç URI'sinde OLMAYAN belge sorgusunda null değil IllegalArgumentException ("Missing file") fırlatır → guarded IoFailure; ilk taramada indeks/MUSIC/.part varlık sorguları taramayı düşürürdü — `queryExisting()`: birim takılıysa null, değilse VolumeMissing.
- MainActivity.dispatchKeyEvent — kısayol KeyDown'da tüketiliyor, KeyUp odaktaki Compose düğmesine gidip (Space/Enter) eylemi ikinci kez tetikleyebiliyordu — tüketilen tuşun KeyUp'ı da yutulur.
- README.md yazıldı (64 satır). Son verify: 181 test geçti; :app UI ve servis/depolama katmanları gerçek imzalardan yazılmış taklitlere karşı tür denetiminden temiz geçti (scratchpad stubcheck/svcstub).
