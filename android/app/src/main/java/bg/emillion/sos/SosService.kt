package bg.emillion.sos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class SosService : Service(), LocationListener {
    companion object {
        @Volatile var running = false
        private const val CH_RUN = "run"
        private const val CH_ALERT = "alert"
        private const val NOTIF_RUN = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var overlay: Overlay
    private lateinit var nm: NotificationManager
    private var lm: LocationManager? = null

    private var ws: WebSocket? = null
    private var online = false
    private var stopped = false
    private var kicked = false
    private var backoff = 2000L

    private var lastLoc: Location? = null
    private var lastSent: Location? = null
    private var lastSentAt = 0L

    private val alerts = LinkedHashMap<String, JSONObject>()   // чужди тревоги около мен
    private val dismissed = HashSet<String>()
    private var myAlarm = false

    // ---------- живот ----------
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "На линия", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Колега в беда", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // звукът е наш и е тих
            enableVibration(false)
        })
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIF_RUN, runNotif("Свързване…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIF_RUN, runNotif("Свързване…"))
        } catch (e: Exception) {
            running = false; stopSelf(); return
        }
        overlay = Overlay(this, onHold = ::onHold, onGo = ::go, onDismiss = ::dismiss)
        overlay.showButton()
        startLocation()
        connect()
        main.postDelayed(tick, 60_000)
        main.postDelayed(remind, 20_000)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (::overlay.isInitialized) overlay.showButton() // ако разрешението е дадено след старта
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        running = false
        main.removeCallbacksAndMessages(null)
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        ws?.close(1000, null)
        if (::overlay.isInitialized) overlay.removeAll()
        alerts.keys.forEach { nm.cancel(it.hashCode()) }
        super.onDestroy()
    }

    // ---------- локация ----------
    private fun startLocation() {
        val m = getSystemService(LOCATION_SERVICE) as LocationManager
        lm = m
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (m.isProviderEnabled(p)) {
                    m.requestLocationUpdates(p, 15_000L, 30f, this, Looper.getMainLooper())
                    m.getLastKnownLocation(p)?.let { if (lastLoc == null) lastLoc = it }
                }
            } catch (_: SecurityException) {}
        }
    }

    override fun onLocationChanged(l: Location) {
        // по-грубата мрежова позиция не бие свежа GPS
        val cur = lastLoc
        if (cur != null && l.provider == LocationManager.NETWORK_PROVIDER &&
            cur.provider == LocationManager.GPS_PROVIDER && l.time - cur.time < 60_000) return
        lastLoc = l
        sendLoc(false)
        refreshBanner()
    }

    @Deprecated("нужен за Android 8–10")
    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    private fun sendLoc(force: Boolean) {
        val l = lastLoc ?: return
        if (!online) return
        val prev = lastSent
        val now = System.currentTimeMillis()
        if (!force && prev != null && l.distanceTo(prev) < 100 && now - lastSentAt < 60_000) return
        if (send(JSONObject().put("t", "loc").put("lat", l.latitude).put("lon", l.longitude))) {
            lastSent = l; lastSentAt = now
        }
    }

    // паркиран шофьор не мърда — пак трябва да се обади, иначе изпада от радиуса
    private val tick = object : Runnable {
        override fun run() { sendLoc(false); main.postDelayed(this, 60_000) }
    }

    // ---------- връзка ----------
    private fun connect() {
        val token = Prefs.token(this) ?: run { stopSelf(); return }
        val req = Request.Builder().url(Net.wsUrl + token).build()
        ws = Net.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) = main.post {
                online = true; backoff = 2000L
                status("На линия · Такси ${Prefs.n(this@SosService)}")
                sendLoc(true)
            }.let { }

            override fun onMessage(w: WebSocket, text: String) = main.post {
                try { handle(JSONObject(text)) } catch (_: Exception) {}
            }.let { }

            override fun onClosed(w: WebSocket, code: Int, reason: String) = main.post { lost() }.let { }

            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) = main.post {
                if (r?.code == 401) out("Сесията е невалидна — влез отново") else lost()
            }.let { }
        })
    }

    private fun lost() {
        online = false
        if (stopped || kicked) return
        status("Няма връзка — опитвам отново…")
        main.postDelayed({ if (!stopped && !kicked) connect() }, backoff)
        backoff = (backoff * 2).coerceAtMost(60_000L)
    }

    private fun send(o: JSONObject): Boolean = online && (ws?.send(o.toString()) == true)

    // ---------- съобщения от сървъра ----------
    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "alert" -> {
                val id = m.getString("id")
                val isNew = !alerts.containsKey(id)
                alerts[id] = m
                if (id in dismissed) return
                if (isNew) { beep(); buzz() }
                notifyAlert(m)
                refreshBanner()
            }
            "cancel" -> {
                val id = m.getString("id")
                alerts.remove(id); dismissed.remove(id)
                nm.cancel(id.hashCode())
                refreshBanner()
            }
            "sent" -> {
                myAlarm = true
                overlay.setAlarm(true)
                buzz()
                val n = m.optInt("reached")
                toast(if (n > 0) "Тревогата стигна до $n колеги" else "Тревогата е пусната — в момента няма колеги до 7 км. Звънни 112.")
            }
            "cancelled" -> { myAlarm = false; overlay.setAlarm(false); toast("Тревогата е спряна") }
            "ack" -> {
                val c = m.optInt("count")
                overlay.setAlarm(true, c)
                val km = if (m.isNull("km")) "" else " · ${m.optDouble("km")} км"
                toast("Такси ${m.optInt("n")} идва$km")
                buzz()
            }
            "error" -> toast(m.optString("msg"))
            "kicked" -> out(m.optString("why", "Излязъл си"))
        }
    }

    private fun out(why: String) {
        kicked = true
        Prefs.clear(this)
        nm.notify(2, Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS — изход")
            .setContentText(why)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build())
        stopSelf()
    }

    // ---------- действия от бутоните ----------
    private fun onHold() {
        if (myAlarm) { send(JSONObject().put("t", "cancel")); return }
        val l = lastLoc
        when {
            !online -> toast("Няма връзка! Звънни 112.")
            l == null -> toast("Още няма GPS позиция — опитай пак след секунда")
            else -> send(JSONObject().put("t", "sos").put("lat", l.latitude).put("lon", l.longitude))
        }
        overlay.setAlarm(myAlarm)
    }

    private fun go(id: String) {
        val a = alerts[id] ?: return
        send(JSONObject().put("t", "ack").put("id", id))
        dismissed.add(id)
        refreshBanner()
        val lat = a.optDouble("lat"); val lon = a.optDouble("lon")
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(nav) } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }

    private fun dismiss(id: String) {
        dismissed.add(id)
        nm.cancel(id.hashCode())
        refreshBanner()
    }

    // ---------- показване ----------
    private fun active() = alerts.values.filter { it.getString("id") !in dismissed }

    private fun refreshBanner() {
        val list = active()
        if (list.isEmpty()) { overlay.hideBanner(); overlay.hideBorder(); return }
        val a = list.minByOrNull { distanceKm(it) } ?: return
        overlay.showBorder()
        val more = if (list.size > 1) "  (+${list.size - 1})" else ""
        overlay.showBanner(a.getString("id"), "SOS · Такси ${a.optInt("n")}$more", info(a))
    }

    private fun distanceKm(a: JSONObject): Double {
        val l = lastLoc ?: return a.optDouble("km", 99.0)
        val r = FloatArray(1)
        Location.distanceBetween(l.latitude, l.longitude, a.optDouble("lat"), a.optDouble("lon"), r)
        return r[0] / 1000.0
    }

    private fun info(a: JSONObject): String {
        val km = String.format("%.1f", distanceKm(a))
        val mins = ((System.currentTimeMillis() - a.optLong("ts")) / 60_000).coerceAtLeast(0)
        val car = listOf(a.optString("plate"), a.optString("model")).filter { it.isNotBlank() }.joinToString(" · ")
        val firm = a.optString("op")
        val helpers = a.optInt("helpers")
        val h = if (helpers > 0) " · $helpers идват" else ""
        return "$car\n$firm\n$km км · преди $mins мин$h"
    }

    private fun notifyAlert(a: JSONObject) {
        val lat = a.optDouble("lat"); val lon = a.optDouble("lon")
        val nav = PendingIntent.getActivity(
            this, a.getString("id").hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(a.getString("id").hashCode(), Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS · Такси ${a.optInt("n")} има нужда от помощ")
            .setStyle(Notification.BigTextStyle().bigText(info(a)))
            .setContentText(info(a).replace('\n', ' '))
            .setContentIntent(nav)
            .setCategory(Notification.CATEGORY_ALARM)
            .setColor(0xFFD32F2F.toInt())
            .build())
    }

    private fun status(s: String) = nm.notify(NOTIF_RUN, runNotif(s))

    private fun runNotif(s: String): Notification = Notification.Builder(this, CH_RUN)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("SOS")
        .setContentText(s)
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    // ---------- звук и вибрация: тихо, за да не плаши клиент ----------
    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45)
            for (i in 0 until 3) main.postDelayed({ tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 250) }, i * 450L)
            main.postDelayed({ tg.release() }, 1600)
        } catch (_: Exception) {}
    }

    private fun buzz() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 300), -1))
        } catch (_: Exception) {}
    }

    // напомня с кратко писукане, докато не натиснеш „Тръгвам" или „✕"
    private val remind = object : Runnable {
        override fun run() {
            if (active().isNotEmpty()) { beep(); refreshBanner() }
            main.postDelayed(this, 20_000)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
