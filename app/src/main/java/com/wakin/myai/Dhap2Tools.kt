package com.wakin.myai

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------
// Shesh 5 ta notification (WhatsApp, SMS ityadi) save kore rakhe
// ---------------------------------------------------------------
object NotifStore {
    private const val PREFS = "myai_notifs"
    private const val KEY = "list"

    fun add(ctx: Context, app: String, title: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val arr = load(ctx)
        val out = JSONArray()
        out.put(JSONObject().put("app", app).put("title", title).put("text", t))
        for (i in 0 until minOf(arr.length(), 4)) {
            out.put(arr.getJSONObject(i))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, out.toString()).apply()
    }

    private fun load(ctx: Context): JSONArray {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    }

    fun readSummary(ctx: Context): String {
        val arr = load(ctx)
        if (arr.length() == 0) return ""
        val sb = StringBuilder()
        val count = minOf(arr.length(), 3)
        for (i in 0 until count) {
            val o = arr.getJSONObject(i)
            if (i > 0) sb.append("। ")
            sb.append("${o.optString("title")} লিখেছে: ${o.optString("text")}")
        }
        return sb.toString()
    }
}

class NotifReaderService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        if (title.isEmpty() || text.isEmpty()) return
        NotifStore.add(this, pkg, title, text)
    }
}

// ---------------------------------------------------------------
// Uninstall Password Lock (password chara app delete bondho kore)
// ---------------------------------------------------------------
object UninstallLock {
    private const val PREFS = "myai_ulock"
    private const val PASS = "pass"
    private const val ON = "on"

    fun hasPass(ctx: Context): Boolean =
        !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PASS, "").isNullOrEmpty()

    fun isLocked(ctx: Context): Boolean =
        hasPass(ctx) && ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON, false)

    fun setPass(ctx: Context, pass: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PASS, pass).putBoolean(ON, true).apply()
    }

    fun checkPass(ctx: Context, pass: String): Boolean {
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PASS, "") ?: ""
        return saved.isNotEmpty() && saved == pass
    }

    fun setLocked(ctx: Context, locked: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ON, locked).apply()
    }
}