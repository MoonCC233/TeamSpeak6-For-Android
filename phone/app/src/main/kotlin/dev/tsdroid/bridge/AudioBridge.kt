package dev.tsdroid.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dev.tslib.AudioConfig
import dev.tslib.OpusCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

class AudioBridge(
    private val context: Context,
    private val tsClient: TsClient,
) {
    companion object {
        private const val TAG = "AudioBridge"
        const val SAMPLE_RATE = 48000
        const val CODEC_OPUS_VOICE = 4
        const val CODEC_OPUS_MUSIC = 5

        /** Output volume multiplier while another app holds transient focus (can-duck). */
        private const val DUCK_FACTOR = 0.3f

        /**
         * Output gain stage with a soft knee: linear below half scale
         * (unity listening is untouched), smoothly compressed above — an
         * boosted mix tops out near full scale instead of hard-clipping.
         * Slope-continuous at the knee (k=2 keeps the derivative at 1).
         */
        internal fun softClip(x: Float): Short {
            val t = x / 32767f
            val limited = when {
                t > 0.5f -> 0.5f + 0.5f * (1f - 1f / (1f + (t - 0.5f) * 2f))
                t < -0.5f -> -(0.5f + 0.5f * (1f - 1f / (1f + (-t - 0.5f) * 2f)))
                else -> t
            }
            return (limited * 32767f).toInt().toShort()
        }
        private const val FRAME_SIZE_MS = 20
        private const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000 // 960
        private const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2 // 16-bit PCM = 2 bytes/sample
        private const val MAX_QUEUE_FRAMES = 10 // Max buffered frames per user
    private const val TRACK_BUFFER_FRAMES = 8 // 160ms of output capacity

        /**
         * Exact digital silence for this long means the system muted/silenced our
         * capture (another app took the mic, privacy toggle, OEM background policy).
         * A real microphone never returns mathematically zero samples.
         */
        private const val SILENT_FRAMES_BEFORE_RECOVERY = 100 // 100 * 20 ms = 2 s

        /** Empty reads (read() == 0) for this long mean the record stopped delivering. */
        private const val EMPTY_READS_BEFORE_RECOVERY = 25 // 25 * 20 ms = 0.5 s

        /** Ignore recording-configuration callbacks right after a session opens. */
        private const val FRAMES_BEFORE_CONFIG_RECOVERY = 50 // 1 s

        /** Reopen quickly at first, then settle into a slow poll so the mic can come back later. */
        private const val MAX_FAST_RECOVERY_ATTEMPTS = 5
        private const val SLOW_RECOVERY_INTERVAL_MS = 10_000L

        /**
         * The input meter bottoms out here. Mapped from dBFS by [publishInputLevel], a normal
         * speaking voice then lands around the middle of the floating window's ring instead of
         * pinned at either end.
         */
        private const val LEVEL_FLOOR_DB = -55.0

        /** Fast attack, slow release: a meter that drops to zero between syllables reads as broken. */
        private const val LEVEL_ATTACK = 0.6f
        private const val LEVEL_RELEASE = 0.2f

        /** Captured frames per published meter value — 50 Hz of updates is wasted on a ring. */
        private const val LEVEL_FRAMES_PER_UPDATE = 3
    }

    /** Health of the capture path — logged (and available for UI surfacing). */
    enum class MicStatus { IDLE, STARTING, RUNNING, RECOVERING, UNAVAILABLE }

    private val audioConfig = AudioConfig()

    // Encoder for capture (our mic)
    private var encoder: OpusCodec? = null

    // Per-user decoders and raw opus queues for playback mixing
    private val userDecoders = ConcurrentHashMap<Int, OpusCodec>()
    private val userQueues = ConcurrentHashMap<Int, ArrayDeque<ByteArray>>()

    /**
     * Wake signal for the playback loop. Capacity 1: the loop only needs to know
     * "there is something to do", and a full slot means it has not woken up yet,
     * so dropping extra offers is correct. This is what lets the idle branch wait
     * for up to 250 ms instead of polling 50 times a second.
     */
    private val playbackWake = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)

    private var audioTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null

    private var audioFocusRequest: AudioFocusRequest? = null

    // Retry job for focus acquisition after a permanent loss (the system never
    // dispatches GAIN for those)
    private var focusRetryJob: Job? = null

    // Output pause while another app holds audio focus (navigation, calls…)
    @Volatile
    private var outputSuspendedByFocus = false

    // Output volume reduction while another app plays a short prompt (can-duck)
    @Volatile
    private var duckedByFocus = false

    private var playbackJob: Job? = null

    /**
     * Capture owns its own scope. Handing an Activity/ViewModel scope to the capture
     * loop (as the UI layer used to do) cancels the microphone the moment that scope
     * dies — which silently killed the mic while the app sat in the background, with
     * nothing left to restart it.
     */
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureLock = Any()

    @Volatile
    private var mic: MicHandle? = null
    private var captureJob: Job? = null

    /** Bumped on every start/stop so a stale capture loop can never touch newer state. */
    @Volatile
    private var captureGeneration = 0

    private var noiseSuppressionEnabled = true

    /** Set by the audio-recording callback when the system silences/reroutes us. */
    @Volatile
    private var recordingConfigDirty = false
    private var recordingCallbackRegistered = false

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val sessionId = mic?.record?.audioSessionId ?: return
            val own = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
            if (own.isClientSilenced) {
                Log.w(TAG, "System silenced our microphone (session=$sessionId); scheduling recovery")
                recordingConfigDirty = true
            } else {
                Log.i(TAG, "Audio recording config changed (session=$sessionId, silenced=false)")
            }
        }
    }

    // Dedicated single-thread scope for audio playback
    @OptIn(ExperimentalCoroutinesApi::class)
    private val playbackScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )

    @Volatile
    var gainFactor: Float = 1.0f

    /**
     * Manual mic gain multiplier applied to captured samples before VAD and
     * encoding. 1.0 = unity; 0 = mic off (the capture loop skips VAD and
     * transmission entirely); values above 1 are soft-clipped against
     * full scale so a loud room cannot hard-clip the boosted signal.
     */
    @Volatile
    var micGainFactor: Float = 1.0f

    @Volatile
    var micMode: MicMode = MicMode.PTT
        private set

    @Volatile
    var vadThresholdDb: Float = VadGate.DEFAULT_THRESHOLD_DB
        private set

    private val vadGate = VadGate()

    fun setMicMode(mode: MicMode) {
        micMode = mode
        vadGate.reset()
    }

    fun setVadThresholdDb(db: Float) {
        vadThresholdDb = db
    }

    @Volatile
    private var mutedUserIds: Set<Int> = emptySet()

    // Per-user playback gain in dB (keyed by client id, session-scoped)
    private val userGainsDb = ConcurrentHashMap<Int, Float>()

    fun setUserGainDb(userId: Int, db: Float) {
        if (db == 0f) userGainsDb.remove(userId) else userGainsDb[userId] = db
    }

    /**
     * Drop queues, decoders and gains for clients that are no longer on the
     * server, so recycled client ids never inherit old state.
     */
    fun pruneUsers(activeIds: Set<Int>) {
        for (id in userDecoders.keys) {
            if (id !in activeIds) {
                userDecoders.remove(id)?.let { decoder ->
                    try { decoder.close() } catch (_: Exception) {}
                }
            }
        }
        userQueues.keys.retainAll(activeIds)
        userGainsDb.keys.retainAll(activeIds)
    }

    private val _isMuted = MutableStateFlow(true) // Start muted (PTT default)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isOutputMuted = MutableStateFlow(false)
    val isOutputMuted: StateFlow<Boolean> = _isOutputMuted.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isLocalVoiceActive = MutableStateFlow(false)
    val isLocalVoiceActive: StateFlow<Boolean> = _isLocalVoiceActive.asStateFlow()

    /**
     * How loud the microphone is hearing us, 0..1, while we are transmitting.
     *
     * A boolean "is speaking" leaves the user guessing whether their voice is actually getting
     * through, so the floating window draws this instead of an on/off dot. Written from the
     * capture loop, read by the overlay.
     */
    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    /** Smoothing state for [inputLevel]; only ever touched by the capture loop. */
    @Volatile
    private var smoothedLevel = 0f

    @Volatile
    private var levelFrameCount = 0

    private val _micStatus = MutableStateFlow(MicStatus.IDLE)
    val micStatus: StateFlow<MicStatus> = _micStatus.asStateFlow()

    fun initialize() {
        try {
            // Explicitly release any old audio stream resources if lingering
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

            encoder = OpusCodec(audioConfig)
            initAudioTrack()
            startPlaybackLoop()
            // Field diagnostics: a silent session with the volume keys still
            // showing media almost always means this line says track=null or
            // callVol=0 — the init error (if any) is logged just above.
            audioManager?.let { manager ->
                Log.i(
                    TAG,
                    "Voice path init: mode=${manager.mode} track=${audioTrack != null} " +
                        "callVol=${manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/" +
                        manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio init failed — playback stays down until re-join", e)
            // We don't throw here to prevent JE_AppCustomException
        }
    }

    /**
     * Start microphone capture.
     *
     * The capture loop owns its own scope and rebuilds the [AudioRecord] by itself
     * whenever the system takes the microphone away, so callers never pass a scope
     * and the mic can no longer be killed by a dead Activity/ViewModel scope.
     */
    @SuppressLint("MissingPermission")
    fun startCapture(noiseSuppressionEnabled: Boolean = true) {
        synchronized(captureLock) {
            if (_isCapturing.value) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Cannot start capture: RECORD_AUDIO permission is missing")
                return
            }

            this.noiseSuppressionEnabled = noiseSuppressionEnabled
            recordingConfigDirty = false
            _isCapturing.value = true
            _micStatus.value = MicStatus.STARTING
            val generation = ++captureGeneration
            registerRecordingCallback()
            requestAudioFocus()
            captureJob = captureScope.launch { runCaptureLoop(generation) }
            Log.i(TAG, "Capture requested (generation=$generation, noiseSuppression=$noiseSuppressionEnabled)")
        }
    }

    fun stopCapture() {
        val job: Job?
        val handle: MicHandle?
        synchronized(captureLock) {
            if (!_isCapturing.value && mic == null && captureJob == null) {
                _micStatus.value = MicStatus.IDLE
                return
            }
            _isCapturing.value = false
            captureGeneration++ // Invalidate the running loop
            job = captureJob
            captureJob = null
            handle = mic
            mic = null
            unregisterRecordingCallback()
        }
        // Stopping the record also unblocks a read() parked in the capture loop, which
        // then releases the record itself. A delayed idempotent close covers the case
        // where that loop is wedged inside read().
        handle?.requestStop()
        job?.cancel()
        if (handle != null) {
            captureScope.launch {
                delay(500)
                handle.close()
            }
        }
        _isLocalVoiceActive.value = false
        resetInputLevel()
        _micStatus.value = MicStatus.IDLE
        // Focus and echo cancellation belong to the session, so they are released
        // here rather than in the capture loop.
        abandonAudioFocus()
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
        vadGate.reset()
        Log.i(TAG, "Capture stopped")
    }

    /**
     * Supervises one microphone session at a time. Whenever the capture path stops
     * delivering real audio the record is closed and reopened, with backoff, so a
     * silenced or revoked microphone recovers on its own instead of staying dead
     * until the user reconnects.
     */
    private suspend fun runCaptureLoop(generation: Int) {
        var failures = 0
        Log.i(TAG, "Capture loop started (generation=$generation)")
        try {
            while (isLoopActive() && isCurrentGeneration(generation) && _isCapturing.value) {
                val handle = try {
                    openMicrophone()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    failures++
                    Log.e(TAG, "Microphone open failed (attempt $failures)", t)
                    _micStatus.value =
                        if (failures > MAX_FAST_RECOVERY_ATTEMPTS) MicStatus.UNAVAILABLE
                        else MicStatus.RECOVERING
                    if (recoveryDelay(failures, generation)) break
                    continue
                }

                if (!isCurrentGeneration(generation) || !_isCapturing.value) {
                    handle.close()
                    break
                }

                mic = handle
                _micStatus.value = MicStatus.RUNNING
                failures = 0
                Log.i(
                    TAG,
                    "Microphone open (session=${handle.record.audioSessionId}, " +
                        "source=${handle.record.audioSource}, state=${handle.record.state})",
                )

                val outcome = try {
                    pumpFrames(handle, generation)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "Capture loop error", t)
                    CaptureOutcome.RECOVER
                } finally {
                    if (mic === handle) mic = null
                    handle.close()
                }

                if (outcome == CaptureOutcome.STOP) break
                if (!isCurrentGeneration(generation) || !_isCapturing.value) break

                failures++
                _micStatus.value =
                    if (failures > MAX_FAST_RECOVERY_ATTEMPTS) MicStatus.UNAVAILABLE
                    else MicStatus.RECOVERING
                Log.w(TAG, "Rebuilding microphone (attempt $failures)")
                if (recoveryDelay(failures, generation)) break
            }
        } finally {
            if (isCurrentGeneration(generation)) {
                _isCapturing.value = false
                _isLocalVoiceActive.value = false
                resetInputLevel()
                _micStatus.value = MicStatus.IDLE
            }
            Log.i(TAG, "Capture loop finished (generation=$generation)")
        }
    }

    /** Reads frames and sends them; returns RECOVER when the capture path went bad. */
    private suspend fun pumpFrames(handle: MicHandle, generation: Int): CaptureOutcome {
        val record = handle.record
        val codec = encoder
        if (codec == null) {
            Log.e(TAG, "Cannot capture: Opus encoder is not initialized")
            return CaptureOutcome.STOP
        }

        val buffer = ShortArray(FRAME_SIZE_SAMPLES)
        var silentFrames = 0
        var emptyReads = 0
        var framesInSession = 0

        while (isLoopActive() && isCurrentGeneration(generation) && _isCapturing.value) {
            val read = try {
                record.read(buffer, 0, FRAME_SIZE_SAMPLES)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "Microphone read failed", t)
                return CaptureOutcome.RECOVER
            }

            if (read < 0) {
                Log.e(TAG, "Microphone read returned error $read")
                return CaptureOutcome.RECOVER
            }

            if (read == 0) {
                // An empty read means the record is stopped or no longer fed.
                emptyReads++
                if (emptyReads >= EMPTY_READS_BEFORE_RECOVERY) {
                    Log.w(TAG, "Microphone stopped delivering samples ($emptyReads empty reads)")
                    return CaptureOutcome.RECOVER
                }
                delay(FRAME_SIZE_MS.toLong()) // Avoid a hot spin
                continue
            }

            emptyReads = 0
            framesInSession++
            if (read < FRAME_SIZE_SAMPLES) {
                Arrays.fill(buffer, read, FRAME_SIZE_SAMPLES, 0.toShort())
            }

            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "Microphone is no longer recording (state=${record.recordingState})")
                return CaptureOutcome.RECOVER
            }

            if (recordingConfigDirty && framesInSession > FRAMES_BEFORE_CONFIG_RECOVERY) {
                recordingConfigDirty = false
                Log.w(TAG, "Audio recording configuration changed; rebuilding microphone")
                return CaptureOutcome.RECOVER
            }

            if (!_isMuted.value && micGainFactor != 0f) {
                // Microphone gain is applied before the VAD gate and the encoder
                // so "turn it up and be heard" behaves the same in every mic mode.
                // (micGainFactor == 0 means the user pulled the slider to 0%.)
                val micGain = micGainFactor
                if (micGain != 1f) {
                    for (i in 0 until read) {
                        // softClip keeps a boosted signal from wrapping around
                        buffer[i] = softClip(buffer[i] * micGain)
                    }
                }

                var energy = 0L
                for (i in 0 until read) {
                    energy += buffer[i].toLong() * buffer[i].toLong()
                }
                val rms = Math.sqrt(energy.toDouble() / read)
                publishInputLevel(rms)

                if (isDigitalSilence(buffer, read)) {
                    silentFrames++
                    if (silentFrames >= SILENT_FRAMES_BEFORE_RECOVERY) {
                        Log.w(
                            TAG,
                            "Microphone is delivering digital silence " +
                                "(${silentFrames * FRAME_SIZE_MS} ms); rebuilding",
                        )
                        return CaptureOutcome.RECOVER
                    }
                } else {
                    silentFrames = 0
                }

                if (micMode == MicMode.VAD) {
                    // Voice activation: the gate — not this loop — decides which
                    // frames leave the device, and it replays the pre-roll so a
                    // word onset that started below the threshold is not clipped.
                    vadGate.openThresholdDb = vadThresholdDb
                    val toSend = vadGate.process(buffer, System.currentTimeMillis())
                    if (toSend != null) {
                        for (frame in toSend) {
                            try {
                                tsClient.sendAudio(codec.encode(shortsToBytes(frame)), CODEC_OPUS_VOICE)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    _isLocalVoiceActive.value = vadGate.isActive
                } else {
                    _isLocalVoiceActive.value = rms > 150.0 // Adjusted threshold for voice activity

                    val pcmBytes = shortsToBytes(buffer)
                    try {
                        val encoded = codec.encode(pcmBytes)
                        tsClient.sendAudio(encoded, CODEC_OPUS_VOICE)
                    } catch (_: Exception) {
                    }
                }
            } else {
                silentFrames = 0
                vadGate.reset()
                _isLocalVoiceActive.value = false
                resetInputLevel()
            }
        }
        return CaptureOutcome.STOP
    }

    /**
     * Publishes the microphone's loudness for the floating window's meter.
     *
     * dBFS mapped from [LEVEL_FLOOR_DB] and then square-rooted, so a quiet voice still moves the
     * ring visibly. Smoothed with a fast attack and a slow release, and published once every
     * [LEVEL_FRAMES_PER_UPDATE] frames: the capture loop runs at 50 Hz and every published value
     * redraws the bubble.
     */
    private fun publishInputLevel(rms: Double) {
        val db = 20.0 * log10((rms / 32768.0).coerceAtLeast(1e-7))
        val target = sqrt(((db - LEVEL_FLOOR_DB) / -LEVEL_FLOOR_DB).coerceIn(0.0, 1.0)).toFloat()
        val rate = if (target > smoothedLevel) LEVEL_ATTACK else LEVEL_RELEASE
        smoothedLevel += (target - smoothedLevel) * rate
        if (++levelFrameCount >= LEVEL_FRAMES_PER_UPDATE) {
            levelFrameCount = 0
            _inputLevel.value = smoothedLevel
        }
    }

    /** Nothing is being transmitted, so the meter reads zero — immediately. */
    private fun resetInputLevel() {
        smoothedLevel = 0f
        levelFrameCount = 0
        if (_inputLevel.value != 0f) _inputLevel.value = 0f
    }

    @SuppressLint("MissingPermission")
    private fun openMicrophone(): MicHandle {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_SIZE_BYTES * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            val state = record.state
            runCatching { record.release() }
            throw IllegalStateException("AudioRecord is not initialized (state=$state)")
        }
        try {
            record.startRecording()
        } catch (t: Throwable) {
            runCatching { record.release() }
            throw t
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            val state = record.recordingState
            runCatching { record.release() }
            throw IllegalStateException("AudioRecord did not start recording (state=$state)")
        }
        attachNoiseSuppressor(record)
        attachEchoCanceler(record)
        return MicHandle(record)
    }

    private fun attachNoiseSuppressor(record: AudioRecord) {
        noiseSuppressor?.release()
        noiseSuppressor = null
        if (noiseSuppressionEnabled && NoiseSuppressor.isAvailable()) {
            try {
                NoiseSuppressor.create(record.audioSessionId)?.also {
                    noiseSuppressor = it
                    Log.i(TAG, "NoiseSuppressor enabled (session=${record.audioSessionId})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create NoiseSuppressor", e)
            }
        } else {
            Log.i(TAG, "NoiseSuppressor skipped: enabled=$noiseSuppressionEnabled, available=${NoiseSuppressor.isAvailable()}")
        }
    }

    /** @return true when the loop must stop instead of retrying. */
    private suspend fun recoveryDelay(failures: Int, generation: Int): Boolean {
        if (!isCurrentGeneration(generation) || !_isCapturing.value) return true
        val waitMs = if (failures <= MAX_FAST_RECOVERY_ATTEMPTS) {
            failures * 500L
        } else {
            SLOW_RECOVERY_INTERVAL_MS
        }
        logMicrophoneEnvironment(failures)
        delay(waitMs)
        return !isCurrentGeneration(generation) || !_isCapturing.value
    }

    private fun logMicrophoneEnvironment(failures: Int) {
        try {
            val muteState = audioManager?.let { manager ->
                runCatching { manager.isMicrophoneMute }.getOrNull()
            }
            Log.w(
                TAG,
                "Microphone unavailable (attempt $failures): systemMicMute=$muteState, " +
                    "permission=${ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)}",
            )
        } catch (_: Throwable) {
        }
    }

    private fun isCurrentGeneration(generation: Int) = captureGeneration == generation

    /** Cancellation state of the capture coroutine (these helpers have no scope receiver). */
    private suspend fun isLoopActive() = currentCoroutineContext().isActive

    /** True only for mathematically silent frames (a silenced client), not a quiet room. */
    private fun isDigitalSilence(buffer: ShortArray, count: Int): Boolean {
        for (i in 0 until count) {
            if (buffer[i].toInt() != 0) return false
        }
        return true
    }

    private fun registerRecordingCallback() {
        val manager = audioManager ?: return
        if (recordingCallbackRegistered) return
        try {
            manager.registerAudioRecordingCallback(recordingCallback, Handler(Looper.getMainLooper()))
            recordingCallbackRegistered = true
            Log.d(TAG, "Audio recording callback registered")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register audio recording callback", e)
        }
    }

    private fun unregisterRecordingCallback() {
        val manager = audioManager ?: return
        if (!recordingCallbackRegistered) return
        recordingCallbackRegistered = false
        try {
            manager.unregisterAudioRecordingCallback(recordingCallback)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to unregister audio recording callback", e)
        }
    }

    private enum class CaptureOutcome { STOP, RECOVER }

    /**
     * Attach echo cancellation for the session, independently of the
     * noise-suppression toggle: without it, speaker playback bleeds into the
     * microphone and remote users hear themselves. USAGE_VOICE_COMMUNICATION
     * implies AEC on most devices, but that is not guaranteed everywhere, so
     * attach the effect whenever the platform offers one.
     */
    private fun attachEchoCanceler(record: AudioRecord) {
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                AcousticEchoCanceler.create(record.audioSessionId)?.also {
                    acousticEchoCanceler = it
                    Log.i(TAG, "AcousticEchoCanceler enabled (session=${record.audioSessionId})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create AcousticEchoCanceler", e)
            }
        } else {
            Log.i(TAG, "AcousticEchoCanceler not available on this device")
        }
    }

    /**
     * Hold audio focus for the duration of a voice session: without it, incoming
     * calls, navigation prompts and other media apps play over (or under) the
     * voice stream with no coordination.
     */
    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return
        val manager = audioManager
        if (manager == null) {
            Log.w(TAG, "No AudioManager; skipping audio focus request")
            return
        }
        val focusAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                // Permanent loss never gets a GAIN callback afterwards — duck the
                // output and keep re-requesting in the background until focus comes
                // back, so a finished call or closed media app restores full volume
                // without needing a reconnect.
                AudioManager.AUDIOFOCUS_LOSS -> {
                    duckedByFocus = true
                    suspendOutput(false)
                    startFocusRetry()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> suspendOutput(true)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duckedByFocus = true
                AudioManager.AUDIOFOCUS_GAIN -> {
                    duckedByFocus = false
                    suspendOutput(false)
                    focusRetryJob?.cancel()
                    focusRetryJob = null
                }
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(focusAttributes)
            .setOnAudioFocusChangeListener(listener)
            .build()
        // Keep the request object even when denied: the retry loop re-uses it
        audioFocusRequest = request
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus not granted; ducking output until focus is available")
            duckedByFocus = true
            startFocusRetry()
        }
    }

    /** Re-request focus with exponential backoff until it is granted again. */
    private fun startFocusRetry() {
        if (focusRetryJob?.isActive == true) return
        focusRetryJob = captureScope.launch {
            var backoffMs = 2_000L
            while (isActive && _isCapturing.value) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
                val manager = audioManager ?: break
                val request = audioFocusRequest ?: break
                if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.i(TAG, "Audio focus regained after retry")
                    duckedByFocus = false
                    suspendOutput(false)
                    break
                }
            }
        }
    }

    private fun abandonAudioFocus() {
        focusRetryJob?.cancel()
        focusRetryJob = null
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        suspendOutput(false)
        duckedByFocus = false
    }

    private fun suspendOutput(suspend: Boolean) {
        if (outputSuspendedByFocus == suspend) return
        outputSuspendedByFocus = suspend
        if (suspend) {
            // Discard anything queued — a resume after a focus gap must not replay
            // audio the user has already missed.
            for ((_, queue) in userQueues) {
                synchronized(queue) { queue.clear() }
            }
        }
    }

    /** Owns one [AudioRecord]; [close] is idempotent so either owner may call it. */
    private class MicHandle(val record: AudioRecord) {
        private val closed = AtomicBoolean(false)
        private val stopped = AtomicBoolean(false)

        /** Unblocks a read() parked on this record without releasing it. */
        fun requestStop() {
            if (!stopped.compareAndSet(false, true)) return
            runCatching { record.stop() }.onFailure { Log.w(TAG, "AudioRecord.stop failed", it) }
        }

        fun close() {
            requestStop()
            if (!closed.compareAndSet(false, true)) return
            runCatching { record.release() }.onFailure { Log.w(TAG, "AudioRecord.release failed", it) }
        }
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        audioTrack = buildAudioTrack(minBuf, lowLatency = true)
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            // Some devices reject PERFORMANCE_MODE_LOW_LATENCY for voice-usage
            // tracks — fall back to a plain track rather than going silent
            Log.w(TAG, "Low-latency voice track not initialized; retrying without performance mode")
            audioTrack?.release()
            audioTrack = buildAudioTrack(minBuf, lowLatency = false)
        }
        audioTrack?.play()
    }

    private fun buildAudioTrack(minBuf: Int, lowLatency: Boolean): AudioTrack {
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAME_SIZE_BYTES * TRACK_BUFFER_FRAMES))
        if (lowLatency) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    /**
     * Playback loop: decodes one opus frame per user, mixes PCM, writes
     * one combined frame to AudioTrack. All decoder access is on this
     * single thread so no synchronization is needed on decoders.
     */
    private fun startPlaybackLoop() {
        playbackJob = playbackScope.launch {
            val mixBuffer = ShortArray(FRAME_SIZE_SAMPLES)
            val decodeBuffer = ShortArray(FRAME_SIZE_SAMPLES)

            while (isActive) {
                var hasData = false
                mixBuffer.fill(0)

                for ((userId, queue) in userQueues) {
                    val opusData = synchronized(queue) { queue.pollFirst() } ?: continue
                    val decoder = userDecoders.getOrPut(userId) { OpusCodec(audioConfig) }
                    try {
                        val pcmBytes = decoder.decode(opusData)
                        bytesToShorts(pcmBytes, decodeBuffer)
                        hasData = true
                        // Per-user gain (dB) applied before summing
                        val gainDb = userGainsDb[userId]
                        if (gainDb != null && gainDb != 0f) {
                            val g = VadGate.dbToGain(gainDb)
                            for (i in mixBuffer.indices) {
                                val sum = mixBuffer[i] + (decodeBuffer[i] * g).toInt()
                                mixBuffer[i] = sum.coerceIn(
                                    Short.MIN_VALUE.toInt(),
                                    Short.MAX_VALUE.toInt(),
                                ).toShort()
                            }
                        } else {
                            // Mix: sum with clipping
                            for (i in mixBuffer.indices) {
                                val sum = mixBuffer[i].toInt() + decodeBuffer[i].toInt()
                                mixBuffer[i] = sum.coerceIn(
                                    Short.MIN_VALUE.toInt(),
                                    Short.MAX_VALUE.toInt(),
                                ).toShort()
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (hasData && !outputSuspendedByFocus) {
                    // Focus ducking multiplies the user gain — audio stays
                    // faintly audible under another app's short prompt
                    val gain = gainFactor * (if (duckedByFocus) DUCK_FACTOR else 1f)
                    if (gain != 1.0f) {
                        for (i in mixBuffer.indices) {
                            mixBuffer[i] = softClip(mixBuffer[i] * gain)
                        }
                    }
                    val bytes = shortsToBytes(mixBuffer)
                    audioTrack?.write(bytes, 0, bytes.size)
                } else {
                    // No audio data — block until a frame arrives (playAudio
                    // offers the wake signal) or the idle safety-net timeout
                    // elapses. Speech onset stays instant because the offer
                    // wakes this loop immediately; the long timeout only keeps
                    // pure silence at ~4 wakeups/s instead of 50.
                    playbackWake.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    /**
     * Queue an opus packet for a specific user. Called from any thread;
     * decoding happens on the playback thread.
     */
    fun setMutedUserIds(userIds: Set<Int>) {
        mutedUserIds = userIds
    }

    fun playAudio(userId: Int, opusData: ByteArray) {
        if (userId in mutedUserIds) return
        if (_isOutputMuted.value) return // Global output mute — discard incoming audio
        if (gainFactor == 0f) return // Speaker slider at 0% — same as muted, skip the work
        if (outputSuspendedByFocus) return // Another app holds focus — discard instead of queueing
        val queue = userQueues.getOrPut(userId) { ArrayDeque() }
        synchronized(queue) {
            // Keep the queue bounded and always favour the freshest audio: once it is full,
            // drop the oldest frames so the listener stays near "now". The previous code
            // silently discarded the *new* frame instead, which made a backlogged user go
            // permanently silent until the queue drained.
            while (queue.size >= MAX_QUEUE_FRAMES) {
                queue.removeFirst()
            }
            queue.addLast(opusData)
        }
        // Wake the playback loop out of its idle wait so onset is immediate.
        // Offer (never put) so a full slot can never block the audio callback.
        playbackWake.offer(true)
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        tsClient.setInputMuted(muted)
    }

    fun toggleMute() {
        val newState = !_isMuted.value
        _isMuted.value = newState
        tsClient.setInputMuted(newState)
    }

    fun setOutputMuted(muted: Boolean) {
        _isOutputMuted.value = muted
        // When output is muted, clear all queued audio so nothing plays
        if (muted) {
            for ((_, queue) in userQueues) {
                synchronized(queue) { queue.clear() }
            }
        }
    }

    fun toggleOutputMute() {
        setOutputMuted(!_isOutputMuted.value)
    }

    fun release() {
        stopCapture()
        playbackJob?.cancel()
        playbackJob = null
        playbackScope.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
        encoder?.close()
        encoder = null
        // Close per-user decoders
        for (decoder in userDecoders.values) {
            try { decoder.close() } catch (_: Exception) {}
        }
        userDecoders.clear()
        userQueues.clear()
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    private fun bytesToShorts(bytes: ByteArray, out: ShortArray) {
        val count = minOf(bytes.size / 2, out.size)
        for (i in 0 until count) {
            out[i] = ((bytes[i * 2].toInt() and 0xFF) or
                    (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        // `out` is reused across frames and across users. The mixer sums every sample of
        // the buffer, so a short decode would otherwise fold the previous frame's (or the
        // previous speaker's) tail into this one. Zero whatever the decoder did not fill.
        Arrays.fill(out, count, out.size, 0.toShort())
    }
}
