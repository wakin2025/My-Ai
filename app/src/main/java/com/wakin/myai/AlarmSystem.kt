package com.wakin.myai

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
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
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------
// Alarm-er talika rakha, set kora, mushe deya
// ---------------------------------------------------------------
object Alarms {
    private const val PREFS = "myai_alarms"
    private const val KEY = "list"

    private fun load(ctx: Context): JSONArray {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            JSONArray(s)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun save(ctx: Context, arr: JSONArray) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun pending(ctx: Context, id: Int, label: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        i.putExtra("id", id)
        i.putExtra("label", label)
        return PendingIntent.getBroadcast(
            ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun schedule(ctx: Context, id: Int, time: Long, label: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(time, show), pending(ctx, id, label))
    }

    private fun add(ctx: Context, time: Long, label: String): Long {
        val arr = load(ctx)
        val id = (System.currentTimeMillis() % 1_000_000_000L).toInt()
        arr.put(JSONObject().put("id", id).put("time", time).put("label", label))
        save(ctx, arr)
        schedule(ctx, id, time, label)
        return time
    }

    fun setAt(ctx: Context, hour: Int, minute: Int, label: String): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return add(ctx, cal.timeInMillis, label)
    }

    fun setIn(ctx: Context, minutes: Int, label: String): Long =
        add(ctx, System.currentTimeMillis() + minutes * 60_000L, label)

    fun remove(ctx: Context, id: Int) {
        val arr = load(ctx)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getInt("id") != id) out.put(o)
        }
        save(ctx, out)
    }

    fun cancelAll(ctx: Context): Int {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val arr = load(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            am.cancel(pending(ctx, o.getInt("id"), o.optString("label")))
        }
        save(ctx, JSONArray())
        return arr.length()
    }

    // Shobcheye kachher (porer) alarm-ta mushe dey, shomoy ferot dey
    fun cancelNext(ctx: Context): Long? {
        val arr = load(ctx)
        if (arr.length() == 0) return null
        var best = 0
        var bestTime = Long.MAX_VALUE
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i).getLong("time")
            if (t < bestTime) {
                bestTime = t
                best = i
            }
        }
        val o = arr.getJSONObject(best)
        val id = o.getInt("id")
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, id, o.optString("label")))
        remove(ctx, id)
        return bestTime
    }

    fun rescheduleAll(ctx: Context) {
        val arr = load(ctx)
        val out = JSONArray()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getLong("time") > now) {
                schedule(ctx, o.getInt("id"), o.getLong("time"), o.optString("label"))
                out.put(o)
            }
        }
        save(ctx, out)
    }

    fun format(time: Long): String =
        SimpleDateFormat("EEE d MMM, hh:mm a", Locale.ENGLISH).format(Date(time))

    fun describe(ctx: Context): String {
        val arr = load(ctx)
        if (arr.length() == 0) return "Kono alarm set nai"
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (i > 0) sb.append("\n")
            sb.append("${i + 1}) ${format(o.getLong("time"))} - ${o.optString("label")}")
        }
        return sb.toString()
    }

    fun stopRinging(ctx: Context) {
        ctx.stopService(Intent(ctx, RingService::class.java))
    }
}

// ---------------------------------------------------------------
// Alarm-er shomoy hole Android eta ke daak dey
// ---------------------------------------------------------------
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarms.remove(context, intent.getIntExtra("id", -1))
        val svc = Intent(context, RingService::class.java)
        svc.putExtra("label", intent.getStringExtra("label") ?: "Alarm")
        context.startForegroundService(svc)
    }
}

// Phone restart hole alarm gulo abar boshiye deya
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Alarms.rescheduleAll(context)
        }
    }
}

// ---------------------------------------------------------------
// Alarm-er awaj bajay (6 second bajbe, 3 second chup thakbe,
// jate chup thakar shomoy tumi "bondho" bolle mic shunte pare)
// ---------------------------------------------------------------
class RingService : Service() {

    companion object {
        @Volatile
        var running = false
    }

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var soundOn = true

    private val pulse = object : Runnable {
        override fun run() {
            val p = player ?: return
            try {
                if (soundOn) {
                    p.pause()
                    soundOn = false
                    handler.postDelayed(this, 3000)
                } else {
                    p.start()
                    soundOn = true
                    handler.postDelayed(this, 6000)
                }
            } catch (e: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        val label = intent?.getStringExtra("label") ?: "Alarm"
        goForeground(label)
        startSound()

        val act = Intent(this, AlarmActivity::class.java)
        act.putExtra("label", label)
        act.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(act)
        } catch (e: Exception) {
        }
        return START_NOT_STICKY
    }

    private fun goForeground(label: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel("myai_alarm", "Alarm", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        nm.createNotificationChannel(ch)

        val ai = Intent(this, AlarmActivity::class.java)
        ai.putExtra("label", label)
        val pi = PendingIntent.getActivity(
            this, 1, ai, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "myai_alarm")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("MyAI Alarm")
            .setContentText(label)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
    }

    private fun startSound() {
        stopSound()
        var uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
            soundOn = true
            handler.postDelayed(pulse, 6000)
        } catch (e: Exception) {
        }
    }

    private fun stopSound() {
        handler.removeCallbacks(pulse)
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        player?.release()
        player = null
    }

    override fun onDestroy() {
        running = false
        stopSound()
        super.onDestroy()
    }
}

// ---------------------------------------------------------------
// Alarm-er screen: STOP button + "bondho / stop / থামো" bolle bondho
// ---------------------------------------------------------------
class AlarmActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var alive = true
    private var cycle = 0

    private val stopWords = listOf(
        "bondho", "bandho", "stop", "off", "thamo", "tham", "dismiss",
        "বন্ধ", "থামো", "থাম", "থামাও", "স্টপ", "অফ"
    )

    private val watcher = object : Runnable {
        override fun run() {
            if (!alive) return
            if (!RingService.running) {
                finishAlarm()
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val label = intent.getStringExtra("label") ?: "Alarm"
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "ALARM"
            textSize = 34f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(match, wrap))
        root.addView(TextView(this).apply {
            text = label
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }, LinearLayout.LayoutParams(match, wrap))
        root.addView(Button(this).apply {
            text = "STOP"
            textSize = 30f
            setOnClickListener { stopAlarm() }
        }, LinearLayout.LayoutParams(match, dp(120)))
        root.addView(TextView(this).apply {
            text = "Ba bolo: bondho / stop / থামো"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }, LinearLayout.LayoutParams(match, wrap))
        setContentView(root)

        handler.postDelayed(watcher, 1000)
        startVoice()
    }

    private fun startVoice() {
        if (!alive) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                check(partialResults)
            }

            override fun onResults(results: Bundle?) {
                check(results)
                again()
            }

            override fun onError(error: Int) {
                again()
            }
        })

        // Ek bar Bangla, ek bar English, ei vabe bodle bodle shune
        cycle++
        val lang = if (cycle % 2 == 0) "bn-BD" else "en-US"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        r.startListening(i)
    }

    private fun again() {
        if (alive) handler.postDelayed({ startVoice() }, 400)
    }

    private fun check(b: Bundle?) {
        val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (s in list) {
            val t = s.lowercase(Locale.ROOT)
            if (stopWords.any { t.contains(it) }) {
                stopAlarm()
                return
            }
        }
    }

    private fun stopAlarm() {
        if (!alive) return
        Alarms.stopRinging(this)
        finishAlarm()
    }

    private fun finishAlarm() {
        alive = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        finish()
    }

    override fun onDestroy() {
        alive = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        super.onDestroy()
    }
}