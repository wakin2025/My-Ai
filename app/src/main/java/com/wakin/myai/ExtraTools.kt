package com.wakin.myai

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MemoryStore {
    private const val PREFS = "myai_mem"
    private const val KEY = "items"

    private fun load(ctx: Context): JSONArray {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    }

    fun add(ctx: Context, fact: String) {
        val arr = load(ctx)
        arr.put(fact.trim())
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun allText(ctx: Context): String {
        val arr = load(ctx)
        if (arr.length() == 0) return ""
        val list = ArrayList<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list.joinToString("; ")
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

object PhoneControl {
    fun setTorch(ctx: Context, on: Boolean): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(camId, on)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun changeVolume(ctx: Context, action: String) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (action) {
            "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "max" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI)
            "mute" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
        }
    }

    fun callNumber(ctx: Context, number: String, direct: Boolean) {
        val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val i = Intent(action, Uri.parse("tel:+$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }
}

object WeatherHelper {
    fun get(city: String): String {
        val c = city.trim().ifEmpty { "Bhairab" }
        return try {
            val url = URL("https://wttr.in/" + URLEncoder.encode(c, "UTF-8") + "?format=%C,+%t,+Humidity+%h")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "curl/7.68.0")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }.trim()
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}