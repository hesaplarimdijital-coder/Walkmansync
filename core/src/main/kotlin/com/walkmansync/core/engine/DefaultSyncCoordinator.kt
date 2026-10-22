package com.walkmansync.core.engine

import com.walkmansync.core.model.ScanProgress
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.SyncPlan
import com.walkmansync.core.model.SyncState
import com.walkmansync.core.model.TransferOutcome
import com.walkmansync.core.model.TransferProgress
import com.walkmansync.core.model.TransferSummary
import com.walkmansync.core.model.VolumePair
import com.walkmansync.core.model.VolumeRole
import com.walkmansync.core.model.VolumeStatus
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.transfer.TransferControl
import com.walkmansync.core.transfer.TransferEvent
import com.walkmansync.core.transfer.TransferRequest
import com.walkmansync.core.transfer.TransferRunner
import com.walkmansync.core.workflow.ScanWorkflow
import com.walkmansync.core.workflow.SyncCoordinator
import com.walkmansync.core.workflow.TreeOpener
import com.walkmansync.core.workflow.VolumeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Durum makinesi. Tek iş kuralı: [busyToken] (Mutex) tarama/aktarım süresince tutulur; durum geçişleri
 * [lock] altında yapılır, böylece komutlar (herhangi bir thread) ile iş coroutine'i yarışmaz.
 *
 * İptal ilkeleri:
 *  - Tarama iptali (kullanıcı ya da kart kaybı): durumu iptal eden anında yayımlar ([JobEnv.detached]),
 *    iş coroutine'i sonradan durumu EZMEZ; Mutex iş gerçekten çözülünce bırakılır (kart üzerinde ikinci iş yok).
 *  - Aktarım iptali: [TransferControl.cancel] ile runner'ın düzgün temizlik yapıp özet üretmesi beklenir;
 *    [hardCancelMs] içinde bitmezse (takılı G/Ç) iş zorla iptal edilir ve sentetik özet yayımlanır.
 */
class DefaultSyncCoordinator(
    private val volumes: StateFlow<VolumeSnapshot>,
    private val opener: TreeOpener,
    private val scanWorkflow: ScanWorkflow,
    private val transferRunner: TransferRunner,
    private val clock: Clock,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val hardCancelMs: Long = HARD_CANCEL_MS,
) : SyncCoordinator {

    private class JobEnv(val pair: VolumePair) {
        @Volatile var job: Job? = null
        @Volatile var watchdog: Job? = null
        @Volatile var control: TransferControl? = null
        @Volatile var detached = false
        var released = false
        @Volatile var lostError: SyncError? = null
        @Volatile var lastProgress: TransferProgress? = null
        @Volatile var plan: SyncPlan? = null
        var lastPublishNanos: Long? = null
    }

    private val lock = Any()
    private val busyToken = Mutex()
    private var active: JobEnv? = null

    /** Son sonuç durumunun (Completed/Failed) ait olduğu çift. */
    private var resultPair: VolumePair? = null

    private val _state = MutableStateFlow<SyncState>(idleFor(volumes.value))
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    init {
        scope.launch { volumes.collect { onVolumes(it) } }
    }

    // ---- komutlar ------------------------------------------------------------------------------

    override fun scan(): Boolean = synchronized(lock) {
        val current = _state.value
        if (current !is SyncState.Idle && current !is SyncState.PlanReady &&
            current !is SyncState.Completed && current !is SyncState.Failed
        ) return false
        val pair = volumes.value.pair ?: return false
        if (!busyToken.tryLock()) return false
        val env = JobEnv(pair)
        active = env
        _state.value = SyncState.Scanning(pair, ScanProgress(com.walkmansync.core.model.ScanPhase.LOADING_INDEX))
        launchJob(env) { runScan(env) }
        true
    }

    override fun startTransfer(selection: Set<String>): Boolean = synchronized(lock) {
        val current = _state.value as? SyncState.PlanReady ?: return false
        val plan = current.plan
        if (volumes.value.pair != plan.pair) return false
        val items = plan.resolveSelection(selection)
        if (items.isEmpty()) return false
        if (!busyToken.tryLock()) return false
        val env = JobEnv(plan.pair)
        env.plan = plan
        env.control = TransferControl()
        val initial = TransferProgress.initial(items, clock.nowMillis())
        env.lastProgress = initial
        active = env
        _state.value = SyncState.Transferring(plan, initial)
        launchJob(env) { runTransfer(env, plan, items) }
        true
    }

    override fun pause(): Boolean = synchronized(lock) {
        val env = active ?: return false
        val current = _state.value as? SyncState.Transferring ?: return false
        val control = env.control ?: return false
        control.pause()
        _state.value = SyncState.Paused(current.plan, current.progress.asPaused())
        true
    }

    override fun resume(): Boolean = synchronized(lock) {
        val env = active ?: return false
        val current = _state.value as? SyncState.Paused ?: return false
        val control = env.control ?: return false
        control.resume()
        _state.value = SyncState.Transferring(current.plan, current.progress.copy(isPaused = false))
        true
    }

    override fun cancel(): Boolean = synchronized(lock) {
        val env = active ?: return false
        when (_state.value) {
            is SyncState.Scanning -> {
                env.detached = true
                _state.value = idleFor(volumes.value)
                env.job?.cancel()
                true
            }
            is SyncState.Transferring, is SyncState.Paused -> {
                val control = env.control ?: return false
                if (control.cancelled.value) return true
                control.cancel()
                armWatchdog(env)
                true
            }
            else -> false
        }
    }

    override fun dismiss(): Boolean = synchronized(lock) {
        when (_state.value) {
            is SyncState.Completed, is SyncState.Failed, is SyncState.PlanReady -> {
                _state.value = idleFor(volumes.value)
                true
            }
            else -> false
        }
    }

    // ---- tarama --------------------------------------------------------------------------------

    private suspend fun runScan(env: JobEnv) {
        var terminal: SyncState? = null
        try {
            val snapshot = volumes.value
            val srcBinding = (snapshot.source as? VolumeStatus.Ready)?.binding
            val dstBinding = (snapshot.target as? VolumeStatus.Ready)?.binding
            if (snapshot.pair != env.pair || srcBinding == null || dstBinding == null) {
                throw SyncException(lostError(env.pair, snapshot) ?: SyncError.Unexpected("çift değişti"))
            }
            val source = opener.openSource(srcBinding)
            val target = opener.openTarget(dstBinding)
            val plan = scanWorkflow.scan(env.pair, source, target) { progress -> publishScan(env, progress) }
            terminal = SyncState.PlanReady(plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            logger.w(TAG, "Tarama başarısız: ${e.error}", e)
            terminal = failed(e.error, null)
        } catch (e: Exception) {
            logger.e(TAG, "Tarama beklenmeyen hatayla durdu", e)
            terminal = failed(SyncError.Unexpected(e.toString()), null)
        } finally {
            finishJob(env, terminal)
        }
    }

    private fun publishScan(env: JobEnv, progress: ScanProgress) {
        synchronized(lock) {
            if (env.detached || active !== env) return
            val now = clock.monotonicNanos()
            val last = env.lastPublishNanos
            if (last != null && now - last < SCAN_PUBLISH_NANOS) return
            env.lastPublishNanos = now
            _state.value = SyncState.Scanning(env.pair, progress)
        }
    }

    // ---- aktarım -------------------------------------------------------------------------------

    private suspend fun runTransfer(env: JobEnv, plan: SyncPlan, items: List<com.walkmansync.core.model.SyncItem>) {
        var terminal: SyncState? = null
        try {
            val snapshot = volumes.value
            val srcBinding = (snapshot.source as? VolumeStatus.Ready)?.binding
            val dstBinding = (snapshot.target as? VolumeStatus.Ready)?.binding
            if (snapshot.pair != plan.pair || srcBinding == null || dstBinding == null) {
                throw SyncException(lostError(plan.pair, snapshot) ?: SyncError.Unexpected("çift değişti"))
            }
            val control = env.control ?: TransferControl()
            val source = opener.openSource(srcBinding)
            val target = opener.openTarget(dstBinding)
            var summary: TransferSummary? = null
            transferRunner.run(TransferRequest(plan, items, source, target), control).collect { event ->
                when (event) {
                    is TransferEvent.Progress -> publishTransfer(env, plan, control, event.progress)
                    is TransferEvent.Finished -> summary = event.summary
                }
            }
            val done = summary
            terminal = if (done == null) failed(SyncError.Unexpected("aktarım özet üretmeden bitti"), null)
            else terminalFor(env, done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            logger.w(TAG, "Aktarım başlatılamadı: ${e.error}", e)
            terminal = failed(e.error, null)
        } catch (e: Exception) {
            logger.e(TAG, "Aktarım beklenmeyen hatayla durdu", e)
            terminal = failed(SyncError.Unexpected(e.toString()), null)
        } finally {
            finishJob(env, terminal)
        }
    }

    private fun terminalFor(env: JobEnv, summary: TransferSummary): SyncState {
        env.lostError?.let { return failed(it, summary) }
        return when (summary.outcome) {
            TransferOutcome.COMPLETED,
            TransferOutcome.COMPLETED_WITH_ERRORS,
            TransferOutcome.CANCELLED -> SyncState.Completed(summary)
            TransferOutcome.ABORTED -> failed(summary.fatalError ?: SyncError.Unexpected("bilinmeyen"), summary)
        }
    }

    private fun publishTransfer(env: JobEnv, plan: SyncPlan, control: TransferControl, raw: TransferProgress) {
        synchronized(lock) {
            if (env.detached || active !== env) return
            // Kullanıcının pause/resume komutu runner'ın gecikmeli olayından önceliklidir.
            val paused = control.paused.value && !control.cancelled.value
            val progress = if (raw.isPaused == paused) raw else raw.copy(isPaused = paused)
            env.lastProgress = progress
            if (env.lostError != null) return // kart kaybı sonrası son gösterilen durum korunur
            val now = clock.monotonicNanos()
            val last = env.lastPublishNanos
            val current = _state.value
            val modeChanged = (current is SyncState.Paused) != paused
            if (!modeChanged && last != null && now - last < TRANSFER_PUBLISH_NANOS) return
            env.lastPublishNanos = now
            _state.value = if (paused) SyncState.Paused(plan, progress) else SyncState.Transferring(plan, progress)
        }
    }

    // ---- ortak bitiş / izleme -------------------------------------------------------------------

    /**
     * İşi başlatır. Gövde hiç çalışmadan iptal edilirse (`finally` yürümez) kilit yine de bırakılsın diye
     * tamamlanma işleyicisi [finishJob]'u (idempotent) çağırır.
     */
    private fun launchJob(env: JobEnv, block: suspend () -> Unit) {
        val job = scope.launch { block() }
        env.job = job
        job.invokeOnCompletion { finishJob(env, null) }
    }

    /** İş çözüldü: terminal durum (varsa) yayımlanır ve tek-iş kilidi aynı kritik bölümde bırakılır. */
    private fun finishJob(env: JobEnv, terminal: SyncState?) {
        env.watchdog?.cancel()
        synchronized(lock) {
            if (env.released) return
            env.released = true
            if (terminal != null && !env.detached) {
                _state.value = terminal
                resultPair = env.pair
            }
            if (active === env) active = null
            busyToken.unlock()
        }
    }

    private fun failed(error: SyncError, summary: TransferSummary?): SyncState.Failed =
        SyncState.Failed(error, recoverable = error !is SyncError.IndexUnsupported && error !is SyncError.Unexpected, summary)

    /** Takılı G/Ç nedeniyle aktarım düzgün bitmezse [hardCancelMs] sonra işi zorla keser. */
    private fun armWatchdog(env: JobEnv) {
        if (env.watchdog != null) return
        env.watchdog = scope.launch {
            delay(hardCancelMs)
            synchronized(lock) {
                if (active !== env || env.detached) return@launch
                env.detached = true
                resultPair = env.pair
                _state.value = env.lostError?.let { failed(it, syntheticSummary(env, TransferOutcome.ABORTED, it)) }
                    ?: SyncState.Completed(syntheticSummary(env, TransferOutcome.CANCELLED, null))
            }
            logger.e(TAG, "Aktarım zaman aşımında zorla iptal edildi")
            env.job?.cancel()
        }
    }

    private fun syntheticSummary(env: JobEnv, outcome: TransferOutcome, error: SyncError?): TransferSummary {
        val p = env.lastProgress
        val now = clock.nowMillis()
        return TransferSummary(
            outcome = outcome,
            totalItems = p?.totalItems ?: 0,
            copiedItems = p?.completedItems ?: 0,
            copiedBytes = p?.completedBytes ?: 0,
            failures = emptyList(),
            notAttempted = (p?.totalItems ?: 0) - (p?.completedItems ?: 0),
            startedAtMillis = p?.startedAtMillis ?: now,
            finishedAtMillis = now,
            fatalError = error,
            indexSaved = false,
        )
    }

    private fun onVolumes(snapshot: VolumeSnapshot) {
        synchronized(lock) {
            val env = active
            if (env != null) {
                if (env.lostError != null || env.detached) return
                if (snapshot.pair == env.pair) return
                val error = lostError(env.pair, snapshot) ?: return
                logger.w(TAG, "Çift bozuldu, iş iptal ediliyor: $error")
                when (_state.value) {
                    is SyncState.Scanning -> {
                        env.detached = true
                        resultPair = env.pair
                        _state.value = failed(error, null)
                        env.job?.cancel()
                    }
                    is SyncState.Transferring, is SyncState.Paused -> {
                        env.lostError = error
                        env.control?.cancel()
                        armWatchdog(env)
                    }
                    else -> Unit
                }
                return
            }
            when (val current = _state.value) {
                is SyncState.Idle, is SyncState.AwaitingVolumes -> {
                    val next = idleFor(snapshot)
                    if (next != current) _state.value = next
                }
                is SyncState.PlanReady -> if (snapshot.pair != current.plan.pair) _state.value = idleFor(snapshot)
                is SyncState.Completed, is SyncState.Failed -> {
                    // Sonuç, kart sökülünce kaybolmasın; yalnızca BAŞKA bir çift gelirse düşer.
                    val newPair = snapshot.pair
                    if (newPair != null && newPair != resultPair) _state.value = idleFor(snapshot)
                }
                else -> Unit
            }
        }
    }

    /** Çiftin hangi rolünün neden kullanılamadığı; hâlâ geçerliyse null. */
    private fun lostError(expected: VolumePair, snapshot: VolumeSnapshot): SyncError? {
        statusError(VolumeRole.SOURCE, snapshot.source)?.let { return it }
        statusError(VolumeRole.TARGET, snapshot.target)?.let { return it }
        val now = snapshot.pair ?: return SyncError.VolumeMismatch(VolumeRole.TARGET, expected.target, null)
        if (now == expected) return null
        return if (now.source != expected.source) SyncError.VolumeMismatch(VolumeRole.SOURCE, expected.source, now.source.uuid)
        else SyncError.VolumeMismatch(VolumeRole.TARGET, expected.target, now.target.uuid)
    }

    private fun statusError(role: VolumeRole, status: VolumeStatus): SyncError? = when (status) {
        is VolumeStatus.Ready -> null
        VolumeStatus.Unbound -> SyncError.VolumeMissing(role, null)
        is VolumeStatus.NotMounted -> SyncError.VolumeMissing(role, status.binding.volume)
        is VolumeStatus.PermissionMissing -> SyncError.PermissionLost(role)
        is VolumeStatus.ReadOnly -> SyncError.VolumeReadOnly(role, status.binding.volume)
        is VolumeStatus.Conflict -> SyncError.VolumeMismatch(role, status.binding.volume, "DUPLICATE_UUID")
    }

    private fun TransferProgress.asPaused(): TransferProgress =
        copy(isPaused = true, bytesPerSecond = 0, etaMillis = null)

    private companion object {
        const val TAG = "SyncCoordinator"
        const val HARD_CANCEL_MS = 15_000L
        const val SCAN_PUBLISH_NANOS = 100L * 1_000_000L
        const val TRANSFER_PUBLISH_NANOS = 200L * 1_000_000L

        fun idleFor(snapshot: VolumeSnapshot): SyncState =
            snapshot.pair?.let { SyncState.Idle(it) } ?: SyncState.AwaitingVolumes(snapshot.source, snapshot.target)
    }
}
