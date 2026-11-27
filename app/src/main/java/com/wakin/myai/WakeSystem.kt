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
        for