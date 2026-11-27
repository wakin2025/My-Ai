package com.wakin.myai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val MODELS = listOf(
        "gemini-3.8-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite"
    )

    private val SYSTEM = "Tumi Roza, Wakin-er personal AI assistant. Tomar naam Roza. " +
        "Uttor chhoto ar shohoj rakho. Uttor jore pora hobe, tai star (*), markdown, emoji bebohar korbe na. " +
        "User Bangla lipi-te (বাংলা) likhle ba bolle tumi-o Bangla lipi-te uttor dao. " +
        "Banglish-e likhle Banglish-e dao. English-e likhle English-e dao."

    private class Reply(val text: String, val note: String)

    private fun systemText(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        return SYSTEM +
            " Ekhon shomoy: $now (24-hour). " +
            "User alarm set korte, dekhte, bondho korte ba mushe dite chaile function call koro, " +
            "nijer theke bole dio na je kaj hoye geche. Shomoy shob shomoy 24-hour e dao " +
            "(jemon ratri 9 tay = 21, bikel 4 tay = 16, shokal 7 tay = 7). " +
            "Alarm ba WhatsApp-er kotha user nijer na tulle tumi nijer theke tulbe na, kono offer-o dibe na. " +
            "Kauke WhatsApp-e shomoy moto message pathate bolle schedule_whatsapp function bebohar koro: " +
            "message-er lekha tumi nijei likho (user-er bhashay, chhoto, shundor, emoji dewa jay). " +
            "Contact-er naam Bangla-e bolle name_alt-e English banan, English-e bolle Bangla banan dao. " +
            "User 'X minute por' ba 'ekhon theke' bolle schedule_whatsapp-e shudhu in_minutes dao " +
            "(hour/minute dio na). 'ratri 9 tay'-er moto spasto shomoy bolle hour ar minute dao."
    }

    private lateinit var chatView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var micBtn: Button
    private lateinit var langBtn: Button
    private val history = JSONArray()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var speakReply = false
    private var langTag = "bn-BD"

    private val TTS_MODEL = "gemini-3.1-flash-tts-preview"
    private val VOICES = listOf("Kore", "Aoede", "Leda", "Zephyr", "Sulafat")
    private var voiceName = "Kore"
    private var track: AudioTrack? = null
    private var speakId = 0
    @Volatile private var busy = false
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var wakeMode = false
    private lateinit var wakeBtn: Button
    private lateinit var pinBtn: Button
    private lateinit var myVoiceBtn: Button
    private lateinit var voiceBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceName = getSharedPreferences("myai", MODE_PRIVATE).getString("voice", "Kore") ?: "Kore"
        buildUi()
        tts = TextToSpeech(this) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { if (wakeMode) handler.postDelayed({ finishWake() }, 600) }
                    }

                    override fun onError(utteranceId: String?) {
                        runOnUiThread { if (wakeMode) handler.postDelayed({ finishWake() }, 600) }
                    }
                })
            }
        }
        if (getKey().isEmpty()) askKey()
        handleWakeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        wakeBtn.text = wakeLabel()
        pinBtn.text = pinLabel()
        myVoiceBtn.text = myVoiceLabel()
        WakeService.instance?.pauseListening()
    }

    override fun onStop() {
        super.onStop()
        WakeService.instance?.resumeListening()
    }

    private fun pinLabel() = if (PinStore.has(this)) "PIN: SET" else "PIN: NO"

    private fun myVoiceLabel() = if (SpeakerStore.has(this)) "MyVoice: ON" else "MyVoice: OFF"

    private fun onMyVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 4)
            return
        }
        if (SpeakerStore.has(this)) {
            AlertDialog.Builder(this)
                .setTitle("My Voice")
                .setMessage("Tomar voice save kora ache. Ekhon shudhu tomar voice-ei Roza jagbe.")
                .setPositiveButton("Notun Record") { _, _ -> startVoiceEnroll() }
                .setNeutralButton("Mushe dao") { _, _ ->
                    SpeakerStore.clear(this)
                    myVoiceBtn.text = myVoiceLabel()
                    append("(Voice lock mushe dewa hoyeche)\n\n")
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("My Voice")
                .setMessage(
                    "Record chaple 4 second shomoy pabe. Tokhon normal golar awaje bolbe: " +
                        "'Roza, amar kotha shuno'."
                )
                .setPositiveButton("Record") { _, _ -> startVoiceEnroll() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startVoiceEnroll() {
        stopSpeaking()
        myVoiceBtn.isEnabled = false
        append("(Voice record shuru hocche...)\n")
        VoiceEnroller.enroll(
            this,
            onStatus = { msg -> runOnUiThread { append("($msg)\n") } },
            onDone = { ok, msg ->
                runOnUiThread {
                    myVoiceBtn.isEnabled = true
                    myVoiceBtn.text = myVoiceLabel()
                    append("($msg)\n\n")
                }
            }
        )
    }

    private fun askPin() {
        val box = EditText(this).apply {
            hint = "Screen lock-er PIN (shudhu number)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Lock PIN")
            .setMessage(
                "PIN phone-e encrypted thake. Ei app shudhu schedule kora WhatsApp wish " +
                    "pathanor shomoy PIN diye lock khulbe, onno kono shomoy na."
            )
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val p = box.text.toString().trim()
                if (p.length in 4..16 && p.all { it.isDigit() }) {
                    try {
                        PinStore.save(this, p)
                    } catch (e: Exception) {
                        append("(PIN save hoyni: ${e.message})\n\n")
                    }
                    pinBtn.text = pinLabel()
                } else {
                    append("(PIN shudhu 4 theke 16 ta number hote hobe)\n\n")
                }
            }
            .setNeutralButton("Mushe dao") { _, _ ->
                PinStore.clear(this)
                pinBtn.text = pinLabel()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wakeLabel() = if (WakeService.instance != null) "Roza: ON" else "Roza: OFF"

    private fun toggleWake() {
        val prefs = getSharedPreferences("myai", MODE_PRIVATE)
        if (WakeService.instance != null) {
            stopService(Intent(this, WakeService::class.java))
            prefs.edit().putBoolean("wake_on", false).apply()
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
                return
            }
            startForegroundService(Intent(this, WakeService::class.java))
            prefs.edit().putBoolean("wake_on", true).apply()
        }
        handler.postDelayed({ wakeBtn.text = wakeLabel() }, 700)
    }

    private fun handleWakeIntent(i: Intent?) {
        if (i == null || !i.getBooleanExtra("wake", false)) return
        i.removeExtra("wake")
        wakeMode = true
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ finishWake() }, 90_000)
        handler.postDelayed({
            try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_ACK, 150)
            } catch (e: Exception) {
            }
        }, 200)
        handler.postDelayed({
            speakReply = true
            stopSpeaking()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                finishWake()
            }
        }, 700)
    }

    private fun finishWake() {
        if (!wakeMode) return
        wakeMode = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
        listening = false
        micBtn.text = "Mic"
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        stopSpeaking()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun langName() = if (langTag == "bn-BD") "Bangla" else "English"

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(24), dp(12), dp(12))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "MyAI"
            textSize = 22f
        }
        top.addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
        langBtn = Button(this).apply {
            text = langName()
            setOnClickListener {
                langTag = if (langTag == "bn-BD") "en-US" else "bn-BD"
                text = langName()
            }
        }
        top.addView(langBtn)
        voiceBtn = Button(this).apply {
            text = voiceName
            setOnClickListener { nextVoice() }
        }
        top.addView(voiceBtn)
        val keyBtn = Button(this).apply {
            text = "Key"
            setOnClickListener { askKey() }
        }
        top.addView(keyBtn)
        root.addView(top)

        wakeBtn = Button(this).apply {
            text = wakeLabel()
            setOnClickListener { toggleWake() }
        }
        pinBtn = Button(this).apply {
            text = pinLabel()
            setOnClickListener { askPin() }
        }
        myVoiceBtn = Button(this).apply {
            text = myVoiceLabel()
            setOnClickListener { onMyVoice() }
        }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(wakeBtn)
        row2.addView(pinBtn)
        row2.addView(myVoiceBtn)
        root.addView(row2)

        scroll = ScrollView(this)
        chatView = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        scroll.addView(chatView)
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Likho..."
            maxLines = 4
        }
        bottom.addView(input, LinearLayout.LayoutParams(0, wrap, 1f))
        micBtn = Button(this).apply {
            text = "Mic"
            setOnClickListener { onMic() }
        }
        bottom.addView(micBtn)
        val sendBtn = Button(this).apply {
            text = "Pathao"
            setOnClickListener {
                speakReply = false
                sendMessage()
            }
        }
        bottom.addView(sendBtn)
        root.addView(bottom)

        setContentView(root)
    }

    private fun getKey(): String =
        getSharedPreferences("myai", MODE_PRIVATE).getString("key", "") ?: ""

    private fun askKey() {
        val box = EditText(this).apply {
            hint = "Gemini API key paste koro"
            setText(getKey())
        }
        AlertDialog.Builder(this)
            .setTitle("API Key")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                getSharedPreferences("myai", MODE_PRIVATE).edit()
                    .putString("key", box.text.toString().trim()).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun append(s: String) {
        chatView.append(s)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------- Voice: shona ----------

    private fun onMic() {
        stopSpeaking()
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onMyVoice()
            } else {
                append("(Mic permission na dile voice save kora jabe na)\n\n")
            }
            return
        }
        if (requestCode == 3) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleWake()
            } else {
                append("(Mic permission na dile Roza shunte parbe na)\n\n")
            }
            return
        }
        if (requestCode == 2) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                append("(Contacts permission peyechi, ekhon abar bolo)\n\n")
            } else {
                append("(Contacts permission na dile naam khuje pabo na)\n\n")
            }
            return
        }
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            append("(Mic permission na dile shunte parbo na)\n\n")
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            append("(Ei phone-e voice recognition nai. Google app update kore dekho)\n\n")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                micBtn.text = "Shunchi..."
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                micBtn.text = "Mic"
            }

            override fun onError(error: Int) {
                listening = false
                micBtn.text = "Mic"
                append("(Shunte parini, abar bolo. error $error)\n\n")
                if (wakeMode) handler.postDelayed({ finishWake() }, 1500)
            }

            override fun onResults(results: Bundle?) {
                listening = false
                micBtn.text = "Mic"
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    speakReply = true
                    input.setText(text)
                    sendMessage()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        listening = true
        r.startListening(intent)
    }

    // ---------- Voice: bola ----------

    private fun nextVoice() {
        val i = VOICES.indexOf(voiceName)
        voiceName = VOICES[(i + 1) % VOICES.size]
        voiceBtn.text = voiceName
        getSharedPreferences("myai", MODE_PRIVATE).edit().putString("voice", voiceName).apply()
        val sample = if (langTag == "bn-BD") {
            "আমি তোমার ব্যক্তিগত এআই সহকারী। কেমন আছো?"
        } else {
            "Hi, I am your personal assistant. How are you today?"
        }
        speak(sample)
    }

    private fun stopSpeaking() {
        speakId++
        tts?.stop()
        track?.let {
            try {
                it.stop()
            } catch (e: Exception) {
            }
            it.release()
        }
        track = null
    }

    private fun speak(text: String) {
        stopSpeaking()
        val myId = speakId
        val key = getKey()
        if (key.isEmpty()) {
            speakSystem(text)
            return
        }
        thread {
            val pcm = try {
                geminiSpeech(key, text)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (myId != speakId) return@runOnUiThread
                if (pcm == null) speakSystem(text) else playPcm(pcm)
            }
        }
    }

    private fun geminiSpeech(key: String, text: String): ByteArray? {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$TTS_MODEL:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", key)
        conn.connectTimeout = 15000
        conn.readTimeout = 40000
        conn.doOutput = true

        val prompt = "Say in a warm, natural, friendly voice: $text"
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", voiceName)
                            )
                        )
                    )
            )
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        if (code !in 200..299) return null
        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        val data = JSONObject(resp)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)
            ?.optJSONObject("inlineData")
            ?.optString("data")
        if (data.isNullOrEmpty()) return null
        return Base64.decode(data, Base64.DEFAULT)
    }

    private fun playPcm(pcm: ByteArray) {
        val rate = 24000
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = t
        t.write(pcm, 0, pcm.size)
        t.play()
        if (wakeMode) {
            handler.postDelayed({ finishWake() }, pcm.size / 48L + 1200L)
        }
    }

    private fun speakSystem(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        val res = t.setLanguage(Locale.forLanguageTag(langTag))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            append(
                "(Ei phone-e ${langName()} voice nai. Settings > text-to-speech e " +
                    "Google engine-e ${langName()} voice namao)\n\n"
            )
            return
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "myai")
    }

    // ---------- AI ----------

    private fun message(role: String, text: String): JSONObject =
        JSONObject().put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun removeFromHistory(msg: JSONObject) {
        for (i in history.length() - 1 downTo 0) {
            if (history.get(i) === msg) {
                history.remove(i)
                return
            }
        }
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val key = getKey()
        if (key.isEmpty()) {
            askKey()
            return
        }
        if (busy) {
            append("(Ager uttor ashche, ektu opekkha koro)\n\n")
            return
        }
        busy = true
        val shouldSpeak = speakReply
        input.setText("")
        append("Tumi: $text\n\n")
        val userMsg = message("user", text)
        history.put(userMsg)

        thread {
            try {
                val reply = callGemini(key)
                history.put(message("model", reply.text))
                runOnUiThread {
                    append("AI: ${reply.text}\n")
                    if (reply.note.isNotEmpty()) append("(${reply.note})\n")
                    append("\n")
                    if (shouldSpeak) speak(reply.text)
                }
            } catch (e: Exception) {
                removeFromHistory(userMsg)
                runOnUiThread {
                    append("Error: ${e.message}\n\n")
                    if (wakeMode) handler.postDelayed({ finishWake() }, 4000)
                }
            } finally {
                busy = false
            }
        }
    }

    private fun callGemini(key: String): Reply {
        var lastError = "Kono model-e uttor pawa jayni"
        for (model in MODELS) {
            for (attempt in 1..2) {
                val result = request(key, model)
                val code = result.first
                val resp = result.second
                if (code in 200..299) return parseReply(resp)

                lastError = "HTTP $code ($model): " + resp.take(150)
                if (code == -1) {
                    if (attempt == 1) {
                        Thread.sleep(1500)
                        continue
                    }
                    throw Exception("Internet-er shomossha, abar try koro (" + resp.take(80) + ")")
                }
                if (code == 404) break
                if (code !in listOf(429, 500, 503, 504)) throw Exception(lastError)
                if (attempt == 1) Thread.sleep(2000)
            }
        }
        throw Exception(lastError)
    }

    private fun request(key: String, model: String): Pair<Int, String> =
        try {
            requestRaw(key, model)
        } catch (e: java.io.IOException) {
            Pair(-1, e.message ?: "internet shomossha")
        }

    private fun requestRaw(key: String, model: String): Pair<Int, String> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", key)
        conn.connectTimeout = 20000
        conn.readTimeout = 45000
        conn.doOutput = true

        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText())))
            )
            .put("tools", tools())
            .put("contents", history)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return Pair(code, resp)
    }

    private fun parseReply(resp: String): Reply {
        val empty = Reply("(AI kono uttor dey nai, abar try koro)", "")
        val candidates = JSONObject(resp).optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) return empty
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
        if (parts == null || parts.length() == 0) return empty

        val texts = ArrayList<String>()
        val notes = ArrayList<String>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val fc = p.optJSONObject("functionCall")
            if (fc != null) {
                val r = runAction(fc.optString("name"), fc.optJSONObject("args"))
                if (r.first.isNotEmpty()) texts.add(r.first)
                if (r.second.isNotEmpty()) notes.add(r.second)
            } else {
                val t = p.optString("text")
                if (t.isNotEmpty()) texts.add(t)
            }
        }
        val text = texts.joinToString(" ").trim()
        if (text.isEmpty()) return empty
        return Reply(text, notes.joinToString("\n"))
    }

    private fun runAction(name: String, args: JSONObject?): Pair<String, String> {
        val a = args ?: JSONObject()
        val reply = a.optString("reply", "Thik ache.")
        val label = a.optString("label", "Alarm").ifBlank { "Alarm" }
        return when (name) {
            "set_alarm" -> {
                val h = a.optInt("hour", -1)
                val m = a.optInt("minute", 0)
                if (h !in 0..23 || m !in 0..59) {
                    Pair("Shomoy bujhte parini, abar bolo.", "")
                } else {
                    val t = Alarms.setAt(this, h, m, label)
                    Pair(reply, "Alarm: " + Alarms.format(t))
                }
            }
            "set_alarm_in" -> {
                val min = a.optInt("minutes", -1)
                if (min < 1) {
                    Pair("Koto minute por, bujhte parini.", "")
                } else {
                    val t = Alarms.setIn(this, min, label)
                    Pair(reply, "Alarm: " + Alarms.format(t))
                }
            }
            "list_alarms" -> Pair(reply, Alarms.describe(this))
            "stop_alarm" -> {
                if (RingService.running) {
                    Alarms.stopRinging(this)
                    Pair(reply, "")
                } else {
                    val t = Alarms.cancelNext(this)
                    if (t != null) {
                        Pair(reply, "Porer alarm mushe geche: " + Alarms.format(t))
                    } else {
                        Pair("Kono alarm bajchhe na, ar set kora alarm-o nai.", "")
                    }
                }
            }
            "cancel_all_alarms" -> {
                val n = Alarms.cancelAll(this)
                Pair(reply, "$n ta alarm mushe geche")
            }
            "schedule_whatsapp" -> scheduleWish(a, reply)
            "list_wishes" -> Pair(reply, Wishes.describe(this))
            "cancel_wishes" -> {
                val n = Wishes.cancelAll(this)
                Pair(reply, "$n ta wish mushe geche")
            }
            else -> Pair(reply, "")
        }
    }

    private fun isAccessibilityOn(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains("$packageName/") && s.contains("WaSendService")
    }

    private fun scheduleWish(a: JSONObject, reply: String): Pair<String, String> {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (wakeMode && km.isKeyguardLocked) {
            return Pair("Phone lock thakle WhatsApp schedule kora jabe na. Age app khulo.", "")
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2) }
            return Pair("Contacts-er permission dao, tarpor abar bolo.", "")
        }
        var h = a.optInt("hour", -1)
        var m = a.optInt("minute", 0)
        val inMin = a.optInt("in_minutes", 0)
        if (inMin > 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.MINUTE, inMin)
            h = cal.get(Calendar.HOUR_OF_DAY)
            m = cal.get(Calendar.MINUTE)
        }
        val msg = a.optString("message", "").trim()
        val name = a.optString("name", "").trim()
        if (h !in 0..23 || m !in 0..59 || msg.isEmpty() || name.isEmpty()) {
            return Pair("Naam, shomoy ba message bujhte parini, abar bolo.", "")
        }
        val found = WaContacts.find(this, listOf(name, a.optString("name_alt", "")))
        val match = found.match
        if (match == null) {
            return if (found.ambiguous.isNotEmpty()) {
                Pair("Ekadhik contact mile geche: " + found.ambiguous.joinToString(", ") + ". Puro naam bolo.", "")
            } else {
                Pair("Contact-e $name khuje pai ni. Contact-e naam-ta kemon lekha ache?", "")
            }
        }

        val t = Wishes.add(this, h, m, match.name, match.number, msg)
        val notes = ArrayList<String>()
        notes.add("WhatsApp: ${match.name} (+${match.number}), " + Alarms.format(t))
        notes.add("Message: $msg")
        if (!isAccessibilityOn()) {
            runOnUiThread { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            notes.add("Accessibility-te MyAI chalu koro, na hole Send chapbe na")
            return Pair("$reply Tobe age accessibility chalu korte hobe.", notes.joinToString("\n"))
        }
        return Pair(reply, notes.joinToString("\n"))
    }

    private fun fn(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", desc)
            .put(
                "parameters",
                JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", props)
                    .put("required", JSONArray(required))
            )

    private fun tools(): JSONArray {
        val reply = JSONObject().put("type", "STRING").put(
            "description",
            "User-er bhashay (Bangla lipi / Banglish / English) ek-duo line-er confirmation. " +
                "Jore pora hobe, tai emoji ba star dio na."
        )
        val label = JSONObject().put("type", "STRING")
            .put("description", "Alarm-er naam. Na thakle Alarm")
        val hour = JSONObject().put("type", "INTEGER")
            .put("description", "24-hour format-e ghonta, 0 theke 23")
        val minute = JSONObject().put("type", "INTEGER")
            .put("description", "Minute, 0 theke 59")
        val minutes = JSONObject().put("type", "INTEGER")
            .put("description", "Ekhon theke koto minute por")

        val decls = JSONArray()
            .put(
                fn(
                    "set_alarm", "Nirdishto shomoye alarm set kore",
                    JSONObject().put("hour", hour).put("minute", minute)
                        .put("label", label).put("reply", reply),
                    listOf("hour", "minute", "reply")
                )
            )
            .put(
                fn(
                    "set_alarm_in", "Ekhon theke kichu minute por alarm ba timer set kore",
                    JSONObject().put("minutes", minutes).put("label", label).put("reply", reply),
                    listOf("minutes", "reply")
                )
            )
            .put(
                fn(
                    "list_alarms", "Set kora alarm-er talika dekhay",
                    JSONObject().put("reply", reply), listOf("reply")
                )
            )
            .put(
                fn(
                    "stop_alarm", "Ekhon bajte thaka alarm bondho kore. Alarm na bajle porer (shobcheye kachher) alarm-ta mushe dey",
                    JSONObject().put("reply", reply), listOf("reply")
                )
            )
            .put(
                fn(
                    "cancel_all_alarms", "Shob set kora alarm mushe dey",
                    JSONObject().put("reply", reply), listOf("reply")
                )
            )
        val nameP = JSONObject().put("type", "STRING")
            .put("description", "Contact-er naam, user jevabe bolechhe")
        val nameAlt = JSONObject().put("type", "STRING")
            .put("description", "Oi naam-er onno lipi-te banan (Bangla hole English, English hole Bangla)")
        val msgP = JSONObject().put("type", "STRING")
            .put("description", "WhatsApp-e pathanor puro message, tumi likhbe")
        decls.put(
            fn(
                "schedule_whatsapp",
                "Kono contact-ke WhatsApp-e nirdishto shomoye message pathanor schedule kore",
                JSONObject().put("name", nameP).put("name_alt", nameAlt)
                    .put("hour", hour).put("minute", minute)
                    .put("in_minutes", minutes)
                    .put("message", msgP).put("reply", reply),
                listOf("name", "message", "reply")
            )
        )
        decls.put(
            fn(
                "list_wishes", "Schedule kora WhatsApp message-er talika dekhay",
                JSONObject().put("reply", reply), listOf("reply")
            )
        )
        decls.put(
            fn(
                "cancel_wishes", "Schedule kora shob WhatsApp message mushe dey",
                JSONObject().put("reply", reply), listOf("reply")
            )
        )
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }
}