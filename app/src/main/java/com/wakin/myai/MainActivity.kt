package com.wakin.myai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        tts = TextToSpeech(this) { status -> ttsReady = (status == TextToSpeech.SUCCESS) }
        if (getKey().isEmpty()) askKey()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
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
        tts?.stop()
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

    private fun speak(text: String) {
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
                history.put(message("model", reply))
                runOnUiThread {
                    append("AI: $reply\n\n")
                    if (shouldSpeak) speak(reply)
                }
            } catch (e: Exception) {
                history.remove(history.length() - 1)
                runOnUiThread { append("Error: ${e.message}\n\n") }
            }
        }
    }

    // Model bhul/busy hole nijei abar try kore, tarpor porer model-e jay
    private fun callGemini(key: String): String {
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
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM)))
            )
            .put("contents", history)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return Pair(code, resp)
    }

    private fun parseReply(resp: String): String {
        val empty = "(AI kono uttor dey nai, abar try koro)"
        val candidates = JSONObject(resp).optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) return empty
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
        if (parts == null || parts.length() == 0) return empty
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).optString("text"))
        }
        val out = sb.toString().trim()
        return if (out.isEmpty()) empty else out
    }
}