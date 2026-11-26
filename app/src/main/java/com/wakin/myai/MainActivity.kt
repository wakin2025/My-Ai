package com.wakin.myai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
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
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    // Prothom model-ta kaj na korle app nijei porer model try korbe
    private val MODELS = listOf(
        "gemini-3.8-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite"
    )

    private val SYSTEM = "Tumi Wakin-er personal AI assistant. " +
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
            "(jemon ratri 9 tay = 21, bikel 4 tay = 16, shokal 7 tay = 7)."
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

    // Notun, sundor voice (Gemini TTS). Kaj na korle phone-er nijer voice-e fallback korbe
    private val TTS_MODEL = "gemini-3.1-flash-tts-preview"
    private val VOICES = listOf("Kore", "Aoede", "Leda", "Zephyr", "Sulafat")
    private var voiceName = "Kore"
    private var track: AudioTrack? = null
    private var speakId = 0
    private lateinit var voiceBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceName = getSharedPreferences("myai", MODE_PRIVATE).getString("voice", "Kore") ?: "Kore"
        buildUi()
        tts = TextToSpeech(this) { status -> ttsReady = (status == TextToSpeech.SUCCESS) }
        if (getKey().isEmpty()) askKey()
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

    // Age Gemini-r sundor voice try kore, na hole phone-er voice
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

    // Gemini voice: 24000 Hz, mono, 16-bit PCM
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

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val key = getKey()
        if (key.isEmpty()) {
            askKey()
            return
        }
        val shouldSpeak = speakReply
        input.setText("")
        append("Tumi: $text\n\n")
        history.put(message("user", text))

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
                history.remove(history.length() - 1)
                runOnUiThread { append("Error: ${e.message}\n\n") }
            }
        }
    }

    // Model bhul/busy hole nijei abar try kore, tarpor porer model-e jay
    private fun callGemini(key: String): Reply {
        var lastError = "Kono model-e uttor pawa jayni"
        for (model in MODELS) {
            for (attempt in 1..2) {
                val result = request(key, model)
                val code = result.first
                val resp = result.second
                if (code in 200..299) return parseReply(resp)

                lastError = "HTTP $code ($model): " + resp.take(150)
                if (code == 404) break
                if (code !in listOf(429, 500, 503, 504)) throw Exception(lastError)
                if (attempt == 1) Thread.sleep(2000)
            }
        }
        throw Exception(lastError)
    }

    private fun request(key: String, model: String): Pair<Int, String> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", key)
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
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

    // AI jei kaj korte bole, shetar asol kaj ekhane hoy
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
            else -> Pair(reply, "")
        }
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
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }
}