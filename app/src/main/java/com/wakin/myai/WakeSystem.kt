package com.wakin.myai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.RecognitionListener as VoskListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

// ---------------------------------------------------------------
// Tomar nijer voice-er fingerprint save, ON/OFF ar match kore
// ---------------------------------------------------------------
object SpeakerStore {
    private const val PREFS = "myai_spk"
    private const val KEY = "vec"
    private const val ON = "on"

    fun has(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)

    fun isEnabled(ctx: Context): Boolean =
        has(ctx) && ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ON, enabled).apply()
    }

    fun save(ctx: Context, vec: FloatArray) {
        val arr = JSONArray()
        for (v in vec) arr.put(v.toDouble())
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .putBoolean(ON, true)
            .apply()
    }

    fun load(ctx: Context): FloatArray? {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return null
        return try {
            val arr = JSONArray(s)
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (e: Exception) {
            null
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).putBoolean(ON, false).apply()
    }

    fun extractSpk(jsonStr: String): FloatArray? {
        return try {
            val arr = JSONObject(jsonStr).optJSONArray("spk") ?: return null
            if (arr.length() == 0) return null
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (e: Exception) {
            null
        }
    }

    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }
}

// ---------------------------------------------------------------
// Model download ar unzip korar helper
// ---------------------------------------------------------------
object VoskFiles {
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    const val MODEL_DIR = "vosk-model-small-en-us-0.15"

    const val SPK_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
    const val SPK_DIR = "vosk-model-spk-0.4"

    fun ensureMainModel(ctx: Context, onStatus: (String) -> Unit): File {
        val root = File(ctx.filesDir, "vosk")
        val marker = File(root, "ready")
        if (!marker.exists()) {
            root.mkdirs()
            onStatus("Prothom bar: main model namachhi (~40MB)...")
            val zip = File(ctx.cacheDir, "model.zip")
            download(MODEL_URL, zip)
            onStatus("Main model khulchi...")
            unzip(zip, root)
            zip.delete()
            marker.writeText("ok")
        }
        return File(root, MODEL_DIR)
    }

    fun ensureSpkModel(ctx: Context, onStatus: (String) -> Unit): File {
        val root = File(ctx.filesDir, "vosk-spk")
        val marker = File(root, "ready")
        if (!marker.exists()) {
            root.mkdirs()
            onStatus("Voice model namachhi (~13MB)...")
            val zip = File(ctx.cacheDir, "spk.zip")
            download(SPK_URL, zip)
            onStatus("Voice model khulchi...")
            unzip(zip, root)
            zip.delete()
            marker.writeText("ok")
        }
        return File(root, SPK_DIR)
    }

    private fun download(urlStr: String, out: File) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.inputStream.use { inp ->
            out.outputStream().use { o -> inp.copyTo(o) }
        }
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val f = File(destDir, entry.name)
                if (entry.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { o -> zis.copyTo(o) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}

// ---------------------------------------------------------------
// Tomar voice 4 second record kore save kore
// ---------------------------------------------------------------
object VoiceEnroller {
    fun enroll(
        ctx: Context,
        onStatus: (String) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        thread {
            var model: Model? = null
            var spkModel: SpeakerModel? = null
            var rec: Recognizer? = null
            var audio: AudioRecord? = null
            try {
                val mainDir = VoskFiles.ensureMainModel(ctx, onStatus)
                val spkDir = VoskFiles.ensureSpkModel(ctx, onStatus)

                onStatus("Model load hocche...")
                model = Model(mainDir.absolutePath)
                spkModel = SpeakerModel(spkDir.absolutePath)
                rec = Recognizer(model, 16000.0f)
                rec.setSpeakerModel(spkModel)

                val minBuf = AudioRecord.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                audio = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, 4096)
                )
                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    onDone(false, "Mic chalu kora jayni.")
                    return@thread
                }

                onStatus("Ekhon bolo: 'Roza, amar kotha shuno' (4 second)...")
                audio.startRecording()
                val buf = ShortArray(2048)
                var total = 0
                val targetSamples = 16000 * 4
                while (total < targetSamples) {
                    val n = audio.read(buf, 0, buf.size)
                    if (n > 0) {
                        rec.acceptWaveForm(buf, n)
                        total += n
                    }
                }
                audio.stop()

                val res = rec.finalResult
                val vec = SpeakerStore.extractSpk(res)
                if (vec == null) {
                    onDone(false, "Voice bujha jayni, ektu jore abar try koro.")
                } else {
                    SpeakerStore.save(ctx, vec)
                    onDone(true, "Tomar voice save hoye geche! Ekhon shudhu tomar voice-ei Roza jagbe.")
                }
            } catch (e: Throwable) {
                onDone(false, "Shomossha hoyeche: " + (e.message ?: e.toString()).take(80))
            } finally {
                try { audio?.release() } catch (e: Exception) {}
                try { rec?.close() } catch (e: Exception) {}
                try { spkModel?.close() } catch (e: Exception) {}
                try { model?.close() } catch (e: Exception) {}
            }
        }
    }
}

// ---------------------------------------------------------------
// "Roza" shobdo-er jonno shob shomoy shune (offline).
// MyVoice ON thakle shudhu tomar voice-ei MainActivity khulbe.
// ---------------------------------------------------------------
class WakeService : Service() {

    companion object {
        @Volatile
        var instance: WakeService? = null

        const val CHANNEL = "myai_wake"
        const val NOTIF_ID = 7

        val WORDS = listOf("rosa", "roza", "rosie")
        // 10% match holei jagbe
        const val VOICE_THRESHOLD = 0.10f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var spkModel: SpeakerModel? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var listenLock: PowerManager.WakeLock? = null

    @Volatile
    private var alive = true

    @Volatile
    private var ready = false
    private var started = false
    private var paused = false
    private var lastTrigger = 0L

    private val resumeTask = Runnable {
        paused = false
        startListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            getSharedPreferences("myai", MODE_PRIVATE).edit().putBoolean("wake_on", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground("Roza shuru hocche...")
        if (!started) {
            started = true
            thread { prepareModel() }
        }
        return START_STICKY
    }

    private fun acquireListenLock() {
        if (listenLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            listenLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "myai:listen").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
        }
    }

    private fun releaseListenLock() {
        try {
            if (listenLock?.isHeld == true) listenLock?.release()
        } catch (e: Exception) {
        }
        listenLock = null
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flags)
        val stopIntent = Intent(this, WakeService::class.java)
        stopIntent.action = "STOP"
        val stop = PendingIntent.getService(this, 4, stopIntent, flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Roza")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Bondho",
                    stop
                ).build()
            )
            .build()
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Roza", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        if (!alive) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun prepareModel() {
        try {
            val mainDir = VoskFiles.ensureMainModel(this) { updateNotification(it) }
            val spkDir = VoskFiles.ensureSpkModel(this) { updateNotification(it) }
            if (!alive) return

            updateNotification("Model load hocche...")
            val m = Model(mainDir.absolutePath)
            val sm = SpeakerModel(spkDir.absolutePath)
            val grammar = JSONArray(WORDS + listOf("[unk]")).toString()
            val r = Recognizer(m, 16000.0f, grammar)
            r.setSpeakerModel(sm)

            model = m
            spkModel = sm
            recognizer = r
            ready = true
            handler.post { if (alive && !paused) startListening() }
        } catch (e: Throwable) {
            updateNotification("Roza shuru hoyni: " + (e.message ?: e.toString()).take(80))
        }
    }

    private fun restartListening() {
        if (!alive || !ready || paused) return
        try { speech?.stop() } catch (e: Exception) {}
        try { speech?.shutdown() } catch (e: Exception) {}
        speech = null
        startListening()
    }

    private fun startListening() {
        if (!alive || !ready || paused) return
        if (speech != null) return
        val r = recognizer ?: return
        try {
            acquireListenLock()
            val s = SpeechService(r, 16000.0f)
            speech = s
            val status = if (SpeakerStore.isEnabled(this)) {
                "Shudhu tomar voice-er jonno shunchi..."
            } else {
                "'Roza' bolle jagbo..."
            }
            updateNotification(status)
            s.startListening(object : VoskListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (SpeakerStore.isEnabled(this@WakeService)) return
                    if (hasWakeWord(hypothesis, "partial")) triggerWake()
                }

                override fun onResult(hypothesis: String?) {
                    if (!hasWakeWord(hypothesis, "text")) return
                    if (!SpeakerStore.isEnabled(this@WakeService)) {
                        triggerWake()
                        return
                    }
                    val saved = SpeakerStore.load(this@WakeService)
                    if (saved == null) {
                        triggerWake()
                        return
                    }
                    val spk = SpeakerStore.extractSpk(hypothesis ?: "")
                    if (spk == null) {
                        updateNotification("Shobdo khub chhoto, ektu lombo kore 'Roza' bolo")
                        return
                    }
                    val score = SpeakerStore.similarity(saved, spk)
                    val pct = (score * 100).toInt()
                    if (score >= VOICE_THRESHOLD) {
                        updateNotification("Tomar voice ($pct%) - jagchi")
                        triggerWake()
                    } else {
                        updateNotification("Onno voice ($pct%) - jagini")
                    }
                }

                override fun onFinalResult(hypothesis: String?) {}

                override fun onError(exception: Exception?) {
                    handler.postDelayed({ restartListening() }, 2000)
                }

                override fun onTimeout() {
                    handler.postDelayed({ restartListening() }, 1000)
                }
            })
        } catch (e: Exception) {
            updateNotification("Mic shomossha: " + (e.message ?: "").take(60))
            handler.postDelayed({ restartListening() }, 3000)
        }
    }

    private fun hasWakeWord(jsonStr: String?, key: String): Boolean {
        if (jsonStr.isNullOrEmpty()) return false
        val t = try {
            JSONObject(jsonStr).optString(key, "").lowercase(Locale.ROOT)
        } catch (e: Exception) {
            return false
        }
        return WORDS.any { t.contains(it) }
    }

    private fun triggerWake() {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 4000L) return
        lastTrigger = now
        handler.post {
            pauseListening()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "myai:wake"
            )
            wl.acquire(10_000L)

            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("wake", true)
            startActivity(i)
        }
    }

    fun pauseListening() {
        handler.removeCallbacks(resumeTask)
        paused = true
        try { speech?.stop() } catch (e: Exception) {}
        try { speech?.shutdown() } catch (e: Exception) {}
        speech = null
        releaseListenLock()
    }

    fun resumeListening() {
        if (!alive) return
        handler.removeCallbacks(resumeTask)
        handler.postDelayed(resumeTask, 1200)
    }

    override fun onDestroy() {
        alive = false
        instance = null
        handler.removeCallbacksAndMessages(null)
        pauseListening()
        try { recognizer?.close() } catch (e: Exception) {}
        try { spkModel?.close() } catch (e: Exception) {}
        try { model?.close() } catch (e: Exception) {}
        super.onDestroy()
    }
}

// Phone restart hole "Roza" abar chalu kora
class WakeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val on = context.getSharedPreferences("myai", Context.MODE_PRIVATE)
            .getBoolean("wake_on", false)
        if (!on) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        context.startForegroundService(Intent(context, WakeService::class.java))
    }
}