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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.TelephonyManager
import android.util.Base64
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

    @Volatile
    var lockAfter = false

    fun arm(n: String, shouldLock: Boolean) {
        name = n
        sent = false
        lockAfter = shouldLock
        pendingUntil = System.currentTimeMillis() + 70_000L
    }

    fun isPending() = !sent && System.currentTimeMillis() < pendingUntil

    fun clear() {
        pendingUntil = 0L
        lockAfter = false
    }
}

// ---------------------------------------------------------------
// Lock-er PIN phone-er Keystore diye encrypt kore rakhe
// ---------------------------------------------------------------
object PinStore {
    private const val PREFS = "myai_pin"
    private const val KEY = "blob"
    private const val ALIAS = "myai_pin_key"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    fun has(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)

    fun save(ctx: Context, pin: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = c.doFinal(pin.toByteArray())
        val blob = Base64.encodeToString(c.iv + enc, Base64.NO_WRAP)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, blob).apply()
    }

    fun get(ctx: Context): String? {
        val blob = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return null
        return try {
            val all = Base64.decode(blob, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val data = all.copyOfRange(12, all.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(c.doFinal(data))
        } catch (e: Exception) {
            null
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

// Shudhu wish pathanor shomoy, kichukkhon-er jonno PIN chapar onumoti dey
object PinTyper {
    @Volatile
    var armedUntil = 0L

    @Volatile
    var typing = false

    @Volatile
    var seen = ""

    fun arm() {
        armedUntil = System.currentTimeMillis() + 40_000L
        typing = false
        seen = ""
    }

    fun isArmed() = System.currentTimeMillis() < armedUntil

    fun disarm() {
        armedUntil = 0L
        typing = false
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
    private var unlocked = false
    private var wasLocked = false

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

        if (savedInstanceState == null) {
            handler.postDelayed({ unlockThenSend(name, number, message) }, 1200)
        }
    }

    // Lock thakle PIN diye khole, tarpor WhatsApp-e pathay
    private fun unlockThenSend(name: String, number: String, message: String) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) {
            wasLocked = false
            unlocked = true
            launchWhatsApp(name, number, message)
            return
        }
        wasLocked = true
        if (km.isKeyguardSecure && PinStore.has(this)) PinTyper.arm()
        try {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    unlocked = true
                    PinTyper.disarm()
                    handler.postDelayed({ launchWhatsApp(name, number, message) }, 800)
                }

                override fun onDismissError() {
                    failWish(name, "lock khola jayni (error)")
                }

                override fun onDismissCancelled() {
                    failWish(name, "lock khola cancel hoyeche")
                }
            })
        } catch (e: Exception) {
            failWish(name, "lock khola jayni")
            return
        }
        handler.postDelayed({
            if (!unlocked) {
                failWish(name, "PIN dewa jayni. Dekhechi: " + PinTyper.seen.take(150))
            }
        }, 40_000)
    }

    private fun failWish(name: String, why: String) {
        if (unlocked) return
        PinTyper.disarm()
        Wishes.setLog(this, "${Wishes.now()} $name: $why")
        finish()
    }

    private fun launchWhatsApp(name: String, number: String, message: String) {
        WishState.arm(name, wasLocked)
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
        if (PinTyper.isArmed() && !PinTyper.typing) {
            tryTypePin()
        }
        if (!WishState.isPending()) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.startsWith("com.whatsapp")) return

        val btn = findSend(root) ?: return
        if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val shouldLock = WishState.lockAfter
            WishState.sent = true
            WishState.lockAfter = false
            Wishes.setLog(this, "${Wishes.now()} ${WishState.name}: Send chapa holo")
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
                if (shouldLock && Build.VERSION.SDK_INT >= 28) {
                    handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }, 800)
                }
            }, 2500)
        }
    }

    // ---------- lock screen-er PIN pad-e number chapa ----------

    private fun tryTypePin() {
        val pin = PinStore.get(this) ?: return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        if (pkg.startsWith("com.whatsapp") || pkg == packageName) return

        for (d in pin.toSet()) {
            if (findKey(root, d, 0) == null) {
                PinTyper.seen = describe(root)
                return
            }
        }
        PinTyper.typing = true
        for ((i, d) in pin.withIndex()) {
            handler.postDelayed({
                val r = rootInActiveWindow
                val k = if (r != null) findKey(r, d, 0) else null
                k?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }, 400L * (i + 1))
        }
        handler.postDelayed({
            val r = rootInActiveWindow
            val ok = if (r != null) findEnter(r, 0) else null
            ok?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.postDelayed({ PinTyper.disarm() }, 3000)
        }, 400L * (pin.length + 1) + 200)
    }

    private fun findKey(n: AccessibilityNodeInfo?, d: Char, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
        if (label.isNotEmpty() && label.length <= 6) {
            val first = label.split(" ", "\n").firstOrNull() ?: ""
            if (first == d.toString()) {
                val c = clickable(n)
                if (c != null) return c
            }
        }
        for (i in 0 until n.childCount) {
            val r = findKey(n.getChild(i), d, depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun findEnter(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        val label = (n.contentDescription?.toString() ?: n.text?.toString() ?: "")
            .trim().lowercase(Locale.ROOT)
        if ((label == "enter" || label == "ok" || label == "done") && n.isClickable) return n
        for (i in 0 until n.childCount) {
            val r = findEnter(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    // PIN pad na pele ki ki dekhechi, sheta likhe rakhi (shomossha dhorar jonno)
    private fun describe(root: AccessibilityNodeInfo): String {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 12 || out.size >= 14) return
            val l = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
            if (l.isNotEmpty()) out.add(l.take(12))
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return (root.packageName?.toString() ?: "?") + ": " + out.joinToString("|")
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