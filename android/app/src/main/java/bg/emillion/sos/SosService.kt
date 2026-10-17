package bg.emillion.sos

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.widget.Toast
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SosService : Service(), LocationListener {
    companion object {
        @Volatile var running = false
        private const val CH_RUN = "run"
        private const val CH_ALERT = "alert"
        private const val NOTIF_RUN = 1
        private const val REC_MAX_MS = 20_000
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
    private var registering = false

    private var lastLoc: Location? = null
    private var lastSent: Location? = null
    private var lastSentAt = 0L

    // ---------- чужди тревоги около мен ----------
    private val alerts = LinkedHashMap<String, JSONObject>()
    private val dismissed = HashSet<String>()
    private val addr = HashMap<String, Triple<String, Double, Double>>() // id -> (адрес, lat, lon)
    private var playWhenArrives: String? = null
    private var player: MediaPlayer? = null

    // ---------- моята тревога ----------
    private var mine = false          // сървърът я потвърди
    private var minePending = false   // пратена, чака потвърждение
    private var mineReached = 0
    private var mineExact = false
    private var mineHelpers = 0
    private var mineLastHelper = ""
    private var mineVoice = ""        // ред за гласа в картата
    private var did112 = false
    private var recorder: MediaRecorder? = null
    private var recStart = 0L
    private var pendingAudio: ByteArray? = null

    // ---------- живот ----------
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "На линия", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Някой наоколо има нужда от помощ", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // звукът е наш и е тих
            enableVibration(false)
        })
        try {
            goForeground("Свързване…")
        } catch (e: Exception) {
            running = false; stopSelf(); return
        }
        overlay = Overlay(this, onHold = ::onHold)
        overlay.showButton()
        startLocation()
        connect()
        main.postDelayed(tick, 60_000)
        main.postDelayed(remind, 20_000)
    }

    /** Микрофонът влиза в типа само ако е разрешен — иначе Android 14 хвърля SecurityException. */
    private fun goForeground(text: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (Build.VERSION.SDK_INT >= 30 && hasMic()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_RUN, runNotif(text), type)
        } else startForeground(NOTIF_RUN, runNotif(text))
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        try { goForeground(if (online) "На линия · Помощ ${Prefs.n(this)}" else "Свързване…") } catch (_: Exception) {}
        if (::overlay.isInitialized) overlay.showButton() // ако разрешението е дадено след старта
        sendPrefs()
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        running = false
        main.removeCallbacksAndMessages(null)
        stopRecording(send = false)
        player?.release(); player = null
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        ws?.close(1000, null)
        if (::overlay.isInitialized) overlay.removeAll()
        alerts.keys.forEach { nm.cancel(it.hashCode()) }
        super.onDestroy()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

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
        val cur = lastLoc
        if (cur != null && l.provider == LocationManager.NETWORK_PROVIDER &&
            cur.provider == LocationManager.GPS_PROVIDER && l.time - cur.time < 60_000) return
        lastLoc = l
        sendLoc(false)
        refresh()
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

    // паркиран телефон не мърда — пак трябва да се обади, иначе изпада от радиуса
    private val tick = object : Runnable {
        override fun run() { sendLoc(false); main.postDelayed(this, 60_000) }
    }

    // ---------- връзка ----------
    private fun connect() {
        val token = Prefs.token(this)
        if (token == null) { register(); return }
        val req = Request.Builder().url(Net.wsUrl + token).build()
        ws = Net.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) = main.post {
                online = true; backoff = 2000L
                status("На линия · Помощ ${Prefs.n(this@SosService)}")
                sendPrefs()
                sendLoc(true)
            }.let { }

            override fun onMessage(w: WebSocket, text: String) = main.post {
                try { handle(JSONObject(text)) } catch (_: Exception) {}
            }.let { }

            override fun onClosed(w: WebSocket, code: Int, reason: String) = main.post { lost() }.let { }

            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) = main.post {
                if (r?.code == 401) { Prefs.clearSession(this@SosService); online = false; main.postDelayed({ register() }, backoff) }
                else lost()
            }.let { }
        })
    }

    /** Без регистрация: сървърът дава номер по ид на устройството. */
    private fun register() {
        if (registering || stopped || kicked) return
        registering = true
        status("Свързване…")
        Thread {
            try {
                val j = Net.register(Prefs.device(this))
                Prefs.saveSession(this, j.getString("token"), j.getInt("n"))
                main.post { registering = false; connect() }
            } catch (e: HttpError) {
                main.post {
                    registering = false
                    if (e.code == 403) out(e.message ?: "Устройството е спряно") else lost()
                }
            } catch (_: Exception) {
                main.post { registering = false; lost() }
            }
        }.start()
    }

    private fun lost() {
        online = false
        if (stopped || kicked) return
        status("Няма връзка — опитвам отново…")
        main.postDelayed({ if (!stopped && !kicked && !online) connect() }, backoff)
        backoff = (backoff * 2).coerceAtMost(60_000L)
    }

    private fun send(o: JSONObject): Boolean = online && (ws?.send(o.toString()) == true)
    private fun sendPrefs() { send(JSONObject().put("t", "prefs").put("receive", Prefs.receive(this))) }

    // ---------- съобщения от сървъра ----------
    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "alert" -> {
                val id = m.getString("id")
                val isNew = !alerts.containsKey(id)
                val old = alerts[id]
                alerts[id] = m
                lookupAddress(id, m.optDouble("lat"), m.optDouble("lon"))
                if (id in dismissed) return
                if (isNew) { beep(); buzz() }
                else if (old != null && !old.optBoolean("audio") && m.optBoolean("audio")) beep()
                notifyAlert(m)
                refresh()
            }
            "cancel" -> {
                val id = m.getString("id")
                alerts.remove(id); dismissed.remove(id); addr.remove(id)
                nm.cancel(id.hashCode())
                File(cacheDir, "in_$id.m4a").delete()
                refresh()
            }
            "audio" -> {
                val id = m.getString("id")
                val f = File(cacheDir, "in_$id.m4a")
                f.writeBytes(Base64.decode(m.getString("b64"), Base64.DEFAULT))
                if (playWhenArrives == id) { playWhenArrives = null; play(f) }
            }
            "sent" -> {
                mine = true; minePending = false
                mineReached = m.optInt("reached")
                mineExact = m.optBoolean("exact")
                overlay.setAlarm(true)
                pendingAudio?.let { pendingAudio = null; sendAudio(it) }
                if (recorder == null && pendingAudio == null && mineVoice != "🎙 Изпраща гласа…") after112() // без глас — 112 веднага
                refresh()
            }
            "exactSet" -> { mineExact = m.optBoolean("on"); refresh() }
            "audioSaved" -> { mineVoice = "🎙 Гласът е изпратен"; after112(); refresh() }
            "ack" -> {
                mineHelpers = m.optInt("count")
                val km = if (m.isNull("km")) "" else " · ${m.optDouble("km")} км"
                mineLastHelper = "Помощ ${m.optInt("n")}$km"
                overlay.setAlarm(true, mineHelpers)
                buzz()
                refresh()
            }
            "cancelled" -> clearMine()
            "muted" -> {
                clearMine()
                val t = SimpleDateFormat("HH:mm", Locale("bg")).format(Date(m.optLong("until")))
                toast("Тревогите от този телефон са спрени до $t — няколко души ги отбелязаха като фалшиви.")
            }
            "falseNoted" -> toast("Отбелязано като фалшива тревога")
            "error" -> {
                if (minePending) { minePending = false; stopRecording(send = false); refresh() }
                toast(m.optString("msg"))
            }
            "kicked" -> out(m.optString("why", "Излязъл си"))
        }
    }

    private fun out(why: String) {
        kicked = true
        Prefs.clearSession(this)
        nm.notify(2, Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS")
            .setContentText(why)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build())
        stopSelf()
    }

    // ---------- моята тревога ----------
    private fun onHold() {
        if (mine || minePending) { send(JSONObject().put("t", "cancel")); clearMine(); return }
        val l = lastLoc
        if (!online || l == null) {
            toast(if (!online) "Няма връзка! Звънни на 112." else "Още няма GPS позиция. Звънни на 112.")
            dial112()
            return
        }
        did112 = false; mineVoice = ""; mineHelpers = 0; mineLastHelper = ""; mineReached = 0
        mineExact = Prefs.exact(this)
        minePending = true
        send(JSONObject().put("t", "sos").put("lat", l.latitude).put("lon", l.longitude).put("exact", mineExact))
        vibrateOnce()
        if (hasMic()) startRecording() else mineVoice = "🎙 Няма разрешение за микрофон"
        refresh()
    }

    private fun clearMine() {
        stopRecording(send = false)
        mine = false; minePending = false; pendingAudio = null
        overlay.setAlarm(false)
        refresh()
    }

    private fun after112() {
        if (did112 || !Prefs.call112(this)) return
        did112 = true
        dial112()
    }

    private fun dial112() {
        try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    // ---------- запис на глас ----------
    private fun startRecording() {
        stopRecording(send = false)
        val f = File(cacheDir, "out.m4a")
        try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22050)
            r.setAudioEncodingBitRate(32000)
            r.setMaxDuration(REC_MAX_MS)
            r.setOutputFile(f.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post { stopRecording(send = true) }
            }
            r.prepare()
            r.start()
            recorder = r
            recStart = System.currentTimeMillis()
            main.post(recTick)
        } catch (e: Exception) {
            recorder = null
            mineVoice = "🎙 Записът не тръгна"
        }
    }

    private val recTick = object : Runnable {
        override fun run() {
            if (recorder == null) return
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    private fun stopRecording(send: Boolean) {
        val r = recorder ?: return
        recorder = null
        main.removeCallbacks(recTick)
        val ok = try { r.stop(); true } catch (_: Exception) { false } // stop() хвърля, ако записът е твърде къс
        try { r.release() } catch (_: Exception) {}
        if (!send) return
        val f = File(cacheDir, "out.m4a")
        if (!ok || !f.exists() || f.length() == 0L) { mineVoice = "🎙 Записът е празен"; after112(); refresh(); return }
        val bytes = f.readBytes()
        f.delete()
        mineVoice = "🎙 Изпраща гласа…"
        if (mine) sendAudio(bytes) else pendingAudio = bytes
        refresh()
    }

    private fun sendAudio(bytes: ByteArray) {
        val ok = send(JSONObject().put("t", "audio").put("mime", "audio/mp4").put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        if (!ok) { mineVoice = "🎙 Гласът не тръгна — няма връзка"; after112() }
    }

    // ---------- чужда тревога: действия ----------
    private fun onAlertAction(id: String, what: String) {
        val a = alerts[id] ?: return
        val lat = a.optDouble("lat"); val lon = a.optDouble("lon")
        when (what) {
            "listen" -> {
                val f = File(cacheDir, "in_$id.m4a")
                if (f.exists()) play(f) else { playWhenArrives = id; send(JSONObject().put("t", "getaudio").put("id", id)); toast("Зарежда гласа…") }
            }
            "map" -> open(mapUri(lat, lon))
            "waze", "gmaps" -> {
                send(JSONObject().put("t", "ack").put("id", id)) // подателят вижда, че идваш
                dismiss(id)
                if (what == "waze") open(wazeUri(lat, lon))
                else if (!open(Uri.parse("google.navigation:q=$lat,$lon"))) open(gmapsDirUri(lat, lon))
            }
            "false" -> { send(JSONObject().put("t", "false").put("id", id)); dismiss(id) }
            "dismiss" -> dismiss(id)
        }
    }

    private fun dismiss(id: String) {
        dismissed.add(id)
        nm.cancel(id.hashCode())
        refresh()
    }

    private fun play(f: File) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare()
                start()
            }
        } catch (_: Exception) { toast("Гласът не може да се пусне") }
    }

    private fun mapUri(lat: Double, lon: Double) = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")
    private fun wazeUri(lat: Double, lon: Double) = Uri.parse("https://waze.com/ul?ll=$lat,$lon&navigate=yes")
    private fun gmapsDirUri(lat: Double, lon: Double) =
        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=driving")

    private fun open(u: Uri): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    // ---------- показване ----------
    private fun active() = alerts.values.filter { it.getString("id") !in dismissed }

    private fun refresh() {
        if (!::overlay.isInitialized) return
        if (mine || minePending) { showMine(); return } // в беда съм — моята карта е по-важна
        val list = active()
        if (list.isEmpty()) { overlay.hideCard(); overlay.hideBorder(); return }
        val a = list.minByOrNull { distanceKm(it) } ?: return
        val id = a.getString("id")
        overlay.showBorder()
        val more = if (list.size > 1) "  (+${list.size - 1})" else ""
        val rows = mutableListOf<List<Btn>>()
        val first = mutableListOf<Btn>()
        if (a.optBoolean("audio")) first.add(Btn("▶ Чуй", Overlay.BLUE) { onAlertAction(id, "listen") })
        first.add(Btn("📍 Карта", Overlay.GLASS) { onAlertAction(id, "map") })
        rows.add(first)
        rows.add(listOf(
            Btn("Waze", Overlay.WAZE) { onAlertAction(id, "waze") },
            Btn("Google Maps", Overlay.GREEN) { onAlertAction(id, "gmaps") },
        ))
        rows.add(listOf(
            Btn("⚠ Фалшива", Overlay.DARK) { onAlertAction(id, "false") },
            Btn("✕ Скрий", Overlay.GLASS) { onAlertAction(id, "dismiss") },
        ))
        overlay.showCard("SOS · Помощ ${a.optInt("n")}$more", info(a), rows)
    }

    private fun showMine() {
        val head = if (mine) "🔴 Тревога · стигна до $mineReached" else "🔴 Изпраща тревогата…"
        val voice = recorder?.let {
            val s = ((System.currentTimeMillis() - recStart) / 1000).coerceAtMost(20)
            "🎙 Записва… $s / 20 сек — говори"
        } ?: mineVoice
        val where = if (mineExact) "📍 Виждат: точното място" else "📍 Виждат: само района (~300 м)"
        val help = if (mineHelpers > 0) "🙋 Идват: $mineHelpers · последно $mineLastHelper" else "🙋 Още никой не е тръгнал"
        val info = listOf(voice, where, help).filter { it.isNotBlank() }.joinToString("\n")
        val rows = mutableListOf<List<Btn>>()
        val r1 = mutableListOf<Btn>()
        if (recorder != null) r1.add(Btn("⏹ Прати гласа", Overlay.BLUE) { stopRecording(send = true) })
        else if (hasMic()) r1.add(Btn("🎙 Нов запис", Overlay.GLASS) { if (mine || minePending) startRecording(); refresh() })
        r1.add(Btn(if (mineExact) "📍 Само района" else "📍 Покажи точно", Overlay.GLASS) {
            send(JSONObject().put("t", "exact").put("on", !mineExact))
        })
        rows.add(r1)
        rows.add(listOf(
            Btn("📞 112", Overlay.GREEN) { dial112() },
            Btn("✕ Спри тревогата", Overlay.DARK) { send(JSONObject().put("t", "cancel")); clearMine() },
        ))
        overlay.hideBorder() // рамката не се показва на този в беда — да не го издава
        overlay.showCard(head, info, rows, bg = 0xF0212121.toInt())
    }

    private fun distanceKm(a: JSONObject): Double {
        val l = lastLoc ?: return a.optDouble("km", 99.0)
        val r = FloatArray(1)
        Location.distanceBetween(l.latitude, l.longitude, a.optDouble("lat"), a.optDouble("lon"), r)
        return r[0] / 1000.0
    }

    private fun info(a: JSONObject): String {
        val id = a.getString("id")
        val exact = a.optBoolean("exact")
        val km = String.format(Locale.US, "%.1f", distanceKm(a))
        val mins = ((System.currentTimeMillis() - a.optLong("ts")) / 60_000).coerceAtLeast(0)
        val street = addr[id]?.first?.takeIf { it.isNotBlank() }
        val where = if (exact) (street ?: "точно място") else "район ~300 м" + (street?.let { " около $it" } ?: "")
        val helpers = a.optInt("helpers")
        val h = if (helpers > 0) " · идват $helpers" else ""
        val voice = if (a.optBoolean("audio")) "🎙 има гласово съобщение\n" else ""
        return "$voice$where\n$km км · преди $mins мин$h"
    }

    /** Улица по координатите. Пак се търси, ако точката се е преместила над 150 м. */
    private fun lookupAddress(id: String, lat: Double, lon: Double) {
        val prev = addr[id]
        if (prev != null) {
            val r = FloatArray(1)
            Location.distanceBetween(prev.second, prev.third, lat, lon, r)
            if (r[0] < 150) return
        }
        addr[id] = Triple(prev?.first ?: "", lat, lon)
        if (!Geocoder.isPresent()) return
        Thread {
            val text = try {
                @Suppress("DEPRECATION")
                val a = Geocoder(this, Locale("bg")).getFromLocation(lat, lon, 1)?.firstOrNull()
                if (a == null) "" else listOfNotNull(
                    a.thoroughfare?.let { s -> if (a.subThoroughfare != null) "$s ${a.subThoroughfare}" else s },
                    a.subLocality ?: a.locality,
                ).joinToString(", ").ifBlank { a.getAddressLine(0) ?: "" }
            } catch (_: Exception) { "" }
            main.post {
                if (alerts.containsKey(id) && text.isNotBlank()) {
                    addr[id] = Triple(text, lat, lon)
                    alerts[id]?.let { if (it.getString("id") !in dismissed) notifyAlert(it) }
                    refresh()
                }
            }
        }.start()
    }

    private fun notifyAlert(a: JSONObject) {
        val id = a.getString("id")
        val lat = a.optDouble("lat"); val lon = a.optDouble("lon")
        fun pi(u: Uri, code: Int) = PendingIntent.getActivity(
            this, id.hashCode() * 4 + code, Intent(Intent.ACTION_VIEW, u),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = info(a)
        nm.notify(id.hashCode(), Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS · Помощ ${a.optInt("n")} има нужда от помощ")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentText(text.replace('\n', ' '))
            .setContentIntent(pi(mapUri(lat, lon), 0))
            .addAction(Notification.Action.Builder(null, "Waze", pi(wazeUri(lat, lon), 1)).build())
            .addAction(Notification.Action.Builder(null, "Google Maps", pi(gmapsDirUri(lat, lon), 2)).build())
            .setCategory(Notification.CATEGORY_ALARM)
            .setColor(0xFFD32F2F.toInt())
            .setOnlyAlertOnce(true)
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

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    // ---------- звук и вибрация: тихо, за да не плаши околните ----------
    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45)
            for (i in 0 until 3) main.postDelayed({ tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 250) }, i * 450L)
            main.postDelayed({ tg.release() }, 1600)
        } catch (_: Exception) {}
    }

    private fun vibrator() = getSystemService(VIBRATOR_SERVICE) as Vibrator

    private fun buzz() {
        try { vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 300), -1)) } catch (_: Exception) {}
    }

    /** Едно кратко потрепване за този в беда — потвърждение, без да го издава. */
    private fun vibrateOnce() {
        try { vibrator().vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
    }

    // напомня с кратко писукане, докато не реагираш на чуждата тревога
    private val remind = object : Runnable {
        override fun run() {
            if (!mine && !minePending && active().isNotEmpty()) { beep(); refresh() }
            main.postDelayed(this, 20_000)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
