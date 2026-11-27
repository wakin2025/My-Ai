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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

// ---------------------------------------------------------------
// "Roza" shobdo-er jonno shob shomoy shune (phone-er vitore, internet lage na).
// Shobdo shunle MainActivity khole dey.
// ---------------------------------------------------------------
class WakeService : Service() {

    companion object {
        @Volatile
        var instance: WakeService? = null

        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val MODEL_DIR = "vosk-model-small-en-us-0.15"
        const val CHANNEL = "myai_wake"
        const val NOTIF_ID = 7

        // Ei shobdo gulor kono ekta shunle "Roza" dhora hobe.
        // Ingreji model "Roza" ke prai "rosa" hishabe shone. Dorkar hole ekhane bodlao.
        val WORDS = listOf("rosa", "roza", "rosie")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null

    @Volatile
    private var alive = true

    @Volatile
    private var ready = false
    private var started = false
    private var paused = false
    private var triggered = false
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

    // ---------------- notification ----------------

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

    // ---------------- model ----------------

    private fun prepareModel() {
        try {
            val root = File(filesDir, "vosk")
            val marker = File(root, "ready")
            if (!marker.exists()) {
                root.mkdirs()
                updateNotification("Prothom bar: model namachhi (~40MB)...")
                val zip = File(cacheDir, "model.zip")
                download(MODEL_URL, zip)
                updateNotification("Model khulchi...")
                unzip(zip, root)
                zip.delete()
                marker.writeText("ok")
            }
            if (!alive) return
            updateNotification("Model load hocche...")
            val m = Model(File(root, MODEL_DIR).absolutePath)
            val grammar = JSONArray(WORDS + listOf("[unk]")).toString()
            val r = Recognizer(m, 16000.0f, grammar)
            model = m
            recognizer = r
            ready = true
            handler.post { if (alive && !paused) startListening() }
        } catch (e: Throwable) {
            updateNotification("Roza shuru hoyni: " + (e.message ?: e.toString()).take(80))
        }
    }

    private fun download(urlStr: String, out: File) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code !in 200..299) throw Exception("Download HTTP $code")
        val total = conn.contentLength.toLong()
        conn.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p >= last + 10) {
                            last = p
                            updateNotification("Model namachhi: $p%")
                        }
                    }
                }
            }
        }
    }

    private fun unzip(zip: File, dest: File) {
        val destPath = dest.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(destPath)) throw Exception("zip-e bhul path")
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
    }

    // ---------------- shona ----------------

    private fun startListening() {
        val r = recognizer ?: return
        if (!alive || !ready || paused || speech != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Mic permission nai")
            return
        }
        try {
            r.reset()
            val s = SpeechService(r, 16000.0f)
            speech = s
            triggered = false
            s.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    check(hypothesis)
                }

                override fun onResult(hypothesis: String?) {
                    check(hypothesis)
                }

                override fun onFinalResult(hypothesis: String?) {}

                override fun onError(exception: Exception?) {
                    updateNotification("Mic error: " + (exception?.message ?: "").take(60))
                }

                override fun onTimeout() {}
            })
            updateNotification("Roza shunche. \"Roza\" bole dako")
        } catch (e: Throwable) {
            speech = null
            updateNotification("Mic shuru hoyni: " + (e.message ?: "").take(60))
        }
    }

    private fun stopListening() {
        val s = speech ?: return
        speech = null
        try {
            s.cancel()
        } catch (e: Throwable) {
        }
        try {
            s.shutdown()
        } catch (e: Throwable) {
        }
    }

    private fun check(hyp: String?) {
        if (hyp == null || triggered) return
        val text = try {
            val o = JSONObject(hyp)
            o.optString("partial", o.optString("text", ""))
        } catch (e: Exception) {
            ""
        }
        if (text.isEmpty()) return
        val words = text.lowercase(Locale.ROOT).split(" ")
        if (words.any { it in WORDS }) onWake()
    }

    private fun onWake() {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 4000) return
        triggered = true
        lastTrigger = now

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
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.putExtra("wake", true)
        try {
            startActivity(i)
            // Jodi MainActivity kono karone shesh na kore, 2 minute por nijei abar shuru hobe
            resumeListening(120_000L)
        } catch (e: Exception) {
            resumeListening(1000L)
        }
    }

    // MainActivity khola thakle ba kotha bolar shomoy mic charte hoy
    fun pauseListening() {
        handler.removeCallbacks(resumeTask)
        paused = true
        stopListening()
    }

    fun resumeListening(delayMs: Long = 1200L) {
        handler.removeCallbacks(resumeTask)
        handler.postDelayed(resumeTask, delayMs)
    }

    override fun onDestroy() {
        alive = false
        instance = null
        handler.removeCallbacksAndMessages(null)
        stopListening()
        try {
            recognizer?.close()
        } catch (e: Throwable) {
        }
        try {
            model?.close()
        } catch (e: Throwable) {
        }
        super.onDestroy()
    }
}

// Phone restart hole "Roza" chalu thakle abar shuru kore
class WakeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val on = context.getSharedPreferences("myai", Context.MODE_PRIVATE).getBoolean("wake_on", false)
        if (on) context.startForegroundService(Intent(context, WakeService::class.java))
    }
}