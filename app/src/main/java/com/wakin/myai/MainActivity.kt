package com.wakin.myai

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
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
import kotlin.concurrent.thread

class MainActivity : Activity() {

    // Jodi "model not found" error ashe, ei naam-ta bodle dao
    private val MODEL = "gemini-2.5-flash"

    private val SYSTEM = "Tumi Wakin-er personal AI assistant. " +
        "Se Banglish ba Bangla-e likhle tumi-o shei vashay chhoto, shohoj uttor dao."

    private lateinit var chatView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val history = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (getKey().isEmpty()) askKey()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        val sendBtn = Button(this).apply {
            text = "Pathao"
            setOnClickListener { sendMessage() }
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
        input.setText("")
        append("Tumi: $text\n\n")
        history.put(message("user", text))

        thread {
            try {
                val reply = callGemini(key)
                history.put(message("model", reply))
                runOnUiThread { append("AI: $reply\n\n") }
            } catch (e: Exception) {
                history.remove(history.length() - 1)
                runOnUiThread { append("Error: ${e.message}\n\n") }
            }
        }
    }

    private fun callGemini(key: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
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
        val resp = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw Exception("HTTP $code: " + resp.take(300))

        return JSONObject(resp)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0)
            .getString("text").trim()
    }
}