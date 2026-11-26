package com.wakin.myai

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------
// Ekhon kon wish pathano cholche, sheta service-ke janay
// ---------------------------------------------------------------
object WishState {
    @Volatile
    var pendingUntil = 0L

    @Volatile
    var sent = false

    @Volatile
    var name = ""

    fun arm(n: String) {
        name = n
        sent = false
        pendingUntil = System.currentTimeMillis() + 70_000L
    }

    fun isPending() = !sent && System.currentTimeMillis() < pendingUntil

    fun clear() {
        pendingUntil = 0L
    }
}

// ---------------------------------------------------------------
// Schedule kora wish-er talika
// ---------------------------------------------------------------
object Wishes {
    private const val PREFS = "myai_wishes"
    private const val KEY = "list"
    private const val LOG = "log"

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

    fun now(): String = SimpleDateFormat("d MMM hh:mm a", Locale.ENGLISH).format(Date())

    fun setLog(ctx: Context, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LOG, text).apply()
    }

    private fun pending(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, WishReceiver::class.java)
        i.putExtra("id", id)
        return PendingIntent.getBroadcast(
            ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun schedule(ctx: Context, id: Int, time: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(time, show), pending(ctx, id))
    }

    fun add(ctx: Context, hour: Int, minute: Int, name: String, number: String, message: String): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val time = cal.timeInMillis
        val id = (System.currentTimeMillis() % 1_000_000_000L).toInt()
        val arr = load(ctx)
        arr.put(
            JSONObject().put("id", id).put("time", time).put("name", name)
                .put("number", number).put("message", message)
        )
        save(ctx, arr)
        schedule(ctx, id, time)
        return time
    }

    // Shomoy hole wish-ta nie talika theke shoriye fele
    fun take(ctx: Context, id: Int): JSONObject? {
        val arr = load(ctx)
        val out = JSONArray()
        var found: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getInt("id") == id) found = o else out.put(o)
        }
        save(ctx, out)
        return found
    }

    fun cancelAll(ctx: Context): Int {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val arr = load(ctx)
        for (i in 0 until arr.length()) {
            am.cancel(pending(ctx, arr.getJSONObject(i).getInt("id")))
        }
        save(ctx, JSONArray())
        return arr.length()
    }

    fun rescheduleAll(ctx: Context) {
        val arr = load(ctx)
        val out = JSONArray()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getLong("time") > now) {
                schedule(ctx, o.getInt("id"), o.getLong("time"))
                out.put(o)
            }
        }
        save(ctx, out)
    }

    fun describe(ctx: Context): String {
        val arr = load(ctx)
        val sb = StringBuilder()
        if (arr.length() == 0) {
            sb.append("Kono wish schedule kora nai")
        } else {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (i > 0) sb.append("\n")
                sb.append(
                    "${i + 1}) ${Alarms.format(o.getLong("time"))} - " +
                        "${o.optString("name")}: ${o.optString("message").take(40)}"
                )
            }
        }
        val log = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LOG, "") ?: ""
        if (log.isNotEmpty()) sb.append("\nShesh wish: $log")
        return sb.toString()
    }
}

// ---------------------------------------------------------------
// Contact-er naam theke WhatsApp number khuje ber kora
// ---------------------------------------------------------------
object WaContacts {
    class Match(val name: String, val number: String)

    // match: ekjon paoa gele; ambiguous: ekadhik jon mile gele tader naam
    class Found(val match: Match?, val ambiguous: List<String>)

    private const val WA_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.profile"

    fun find(ctx: Context, names: List<String>): Found {
        val qs = names.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (qs.isEmpty()) return Found(null, emptyList())

        val wa = ArrayList<Match>()
        val ph = ArrayList<Match>()
        load(ctx, wa, ph)

        // Age WhatsApp contact-er modhye khuje, na pele phone-er contact-e
        for (list in listOf(wa, ph)) {
            val r = pick(list, qs)
            if (r.match != null || r.ambiguous.isNotEmpty()) return r
        }
        return Found(null, emptyList())
    }

    // 1) puro naam mile 2) naam-er vitore thake 3) kachakachi (1-2 okkhor bhul, jemon Ashraf/Ashruf)
    private fun pick(list: List<Match>, qs: List<String>): Found {
        val steps = listOf<(String, String) -> Boolean>(
            { n, q -> n == q },
            { n, q -> n.contains(q) },
            { n, q -> q.length >= 5 && close(n, q) }
        )
        for (test in steps) {
            val hits = list.filter { m ->
                val n = m.name.lowercase(Locale.ROOT)
                qs.any { q -> test(n, q) }
            }
            val distinct = hits.distinctBy { it.number }
            if (distinct.size == 1) return Found(distinct[0], emptyList())
            if (distinct.size > 1) {
                return Found(null, distinct.map { it.name }.distinct().take(4))
            }
        }
        return Found(null, emptyList())
    }

    private fun close(name: String, q: String): Boolean {
        val words = name.split(" ", "-", ".", "_").filter { it.isNotEmpty() }
        val limit = maxOf(1, q.length / 4)
        return (words + name).any { lev(it, q) <= limit }
    }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun load(ctx: Context, wa: MutableList<Match>, ph: MutableList<Match>) {
        // WhatsApp nijer contact-row-te puro international number rakhe
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.DATA1),
            ContactsContract.Data.MIMETYPE + "=?",
            arrayOf(WA_MIME),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val jid = c.getString(1) ?: continue
                val digits = jid.substringBefore("@").filter { ch -> ch.isDigit() }
                if (digits.length >= 8) wa.add(Match(name, digits))
            }
        }

        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val raw = c.getString(1) ?: continue
                val num = normalize(ctx, raw) ?: continue
                ph.add(Match(name, num))
            }
        }
    }

    private fun normalize(ctx: Context, raw: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 7) return null
        if (trimmed.startsWith("+")) return digits
        if (digits.startsWith("00")) return digits.substring(2)

        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val iso = (tm.simCountryIso ?: "").lowercase(Locale.ROOT)
        val cc = mapOf(
            "bd" to "880", "in" to "91", "pk" to "92", "np" to "977", "lk" to "94",
            "ae" to "971", "sa" to "966", "my" to "60", "sg" to "65", "gb" to "44",
            "us" to "1", "ca" to "1", "au" to "61", "qa" to "974", "kw" to "965", "om" to "968"
        )[iso] ?: return null

        if (!digits.startsWith("0") && digits.length >= 11 && digits.startsWith(cc)) return digits
        return cc + digits.trimStart('0')
    }
}

// ---------------------------------------------------------------
// Wish-er shomoy hole Android eta ke daak dey
// ---------------------------------------------------------------
class WishReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val w = Wishes.take(context, intent.getIntExtra("id", -1)) ?: return

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "myai:wish"
        )
        wl.acquire(90_000L)

        val i = Intent(context, WishActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.putExtra("name", w.optString("name"))
        i.putExtra("number", w.optString("number"))
        i.putExtra("message", w.optString("message"))
        context.startActivity(i)
    }
}

// Phone restart hole wish gulo abar boshiye deya
class WishBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Wishes.rescheduleAll(context)
        }
    }
}

// ---------------------------------------------------------------
// Screen jagay, WhatsApp-e chat khole message boshay
// ---------------------------------------------------------------
class WishActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        val name = intent.getStringExtra("name") ?: ""
        val number = intent.getStringExtra("number") ?: ""
        val message = intent.getStringExtra("message") ?: ""

        setContentView(TextView(this).apply {
            text = "WhatsApp-e wish pathachhi: $name"
            textSize = 20f
            gravity = Gravity.CENTER
        })

        try {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .requestDismissKeyguard(this, null)
        } catch (e: Exception) {
        }

        if (savedInstanceState == null) {
            handler.postDelayed({ launchWhatsApp(name, number, message) }, 1500)
        }
    }

    private fun launchWhatsApp(name: String, number: String, message: String) {
        WishState.arm(name)
        val uri = Uri.parse("https://wa.me/$number?text=" + Uri.encode(message))
        var ok = false
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                val i = Intent(Intent.ACTION_VIEW, uri)
                i.setPackage(pkg)
                startActivity(i)
                ok = true
                break
            } catch (e: Exception) {
            }
        }
        if (!ok) {
            Wishes.setLog(this, "${Wishes.now()} $name: WhatsApp khola jayni")
            finish()
            return
        }
        handler.postDelayed({
            if (!WishState.sent) {
                Wishes.setLog(this, "${Wishes.now()} $name: Send button pai ni, pathano hoyni")
                WishState.clear()
            }
            finish()
        }, 70_000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

// ---------------------------------------------------------------
// WhatsApp-er Send button-e chap dey (Accessibility service)
// ---------------------------------------------------------------
class WaSendService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!WishState.isPending()) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.startsWith("com.whatsapp")) return

        val btn = findSend(root) ?: return
        if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            WishState.sent = true
            Wishes.setLog(this, "${Wishes.now()} ${WishState.name}: Send chapa holo")
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 2500)
        }
    }

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (!list.isNullOrEmpty()) {
                val c = clickable(list[0])
                if (c != null) return c
            }
        }
        return search(root, 0)
    }

    private fun clickable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var c = n
        var depth = 0
        while (c != null && depth < 4) {
            if (c.isClickable) return c
            c = c.parent
            depth++
        }
        return null
    }

    private fun search(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        val desc = n.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT)
        if (desc == "send" && n.isClickable) return n
        for (i in 0 until n.childCount) {
            val r = search(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }
}