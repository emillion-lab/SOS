package bg.emillion.sos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var root: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20)
            setPadding(p, p, p, p)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        askAllOnce()
        render()
    }

    /** При първо отваряне иска наведнъж всички права, вкл. микрофона — без него няма гласово съобщение. */
    private fun askAllOnce() {
        val sp = getSharedPreferences("sos", MODE_PRIVATE)
        if (sp.getBoolean("askedAll", false)) return
        sp.edit().putBoolean("askedAll", true).apply()
        askMissing()
    }

    private fun askMissing() {
        val want = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = want.filter { !has(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 3)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        // Микрофонът влиза в услугата само при старт — рестарт, ако тя вече върви
        if (code == 3 && has(Manifest.permission.RECORD_AUDIO) && SosService.running) {
            stopService(Intent(this, SosService::class.java))
            root.postDelayed({ startForegroundService(Intent(this, SosService::class.java)); render() }, 600)
        }
        render()
    }

    private fun render() {
        root.removeAllViews()
        val n = Prefs.n(this)
        text(if (n > 0) tr("SOS · Помощ $n", "SOS · Help $n") else tr("SOS — помощ наоколо", "SOS — help nearby"), 26f, bold = true, color = RED)
        text(tr("Задръж червения бутон 2 сек → тревогата тръгва веднага до хората наоколо (7 км) и започва запис на глас до 20 сек.", "Hold the red button for 2 sec → the alarm goes out at once to people nearby (7 km) and a voice recording of up to 20 sec starts."), 15f)
        space()

        // ---------- настройки на тревогата ----------
        text(tr("При тревога", "During an alarm"), 18f, bold = true)
        check(tr("Давай точна локация", "Share exact location"), Prefs.exact(this),
            tr("Изключено: виждат само района (~300 м). Можеш да я включиш и по време на тревогата.", "Off: others see only the area (~300 m). You can turn it on during the alarm too.")) {
            Prefs.setExact(this, it)
        }
        check(tr("След гласовото съобщение отвори 112", "Open 112 after the voice message"), Prefs.call112(this), null) { Prefs.setCall112(this, it) }
        check(tr("Получавай тревоги от други", "Receive alarms from others"), Prefs.receive(this), null) {
            Prefs.setReceive(this, it)
            if (SosService.running) startForegroundService(Intent(this, SosService::class.java))
        }
        space()

        // ---------- разрешения ----------
        text(tr("Разрешения", "Permissions"), 18f, bold = true)
        val loc = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val mic = has(Manifest.permission.RECORD_AUDIO)
        val notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
        val overlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)

        perm(tr("Локация", "Location"), loc) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        perm(tr("Микрофон (за гласовото съобщение)", "Microphone (for the voice message)"), mic) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
        }
        perm(tr("Известия", "Notifications"), notif) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        perm(tr("Бутон над приложенията", "Button over other apps"), overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        perm(tr("Без спиране от батерията", "Not stopped by battery saver"), battery) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        space()

        if (SosService.running) {
            text(tr("● На линия", "● Online"), 16f, bold = true, color = GREEN)
            button(tr("Спри", "Stop")) {
                stopService(Intent(this, SosService::class.java))
                root.postDelayed({ render() }, 300)
            }
        } else {
            button(tr("Включи", "Turn on")) {
                if (!loc || !mic) { askMissing() }
                if (!loc) { toast(tr("Първо разреши локация", "Allow location first")); return@button }
                startForegroundService(Intent(this, SosService::class.java))
                root.postDelayed({ render() }, 800)
            }
        }
        space()
        button("📞 112", secondary = true) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
        }
        space()
        text(tr("Без регистрация. Не се пазят име, телефон или история на движението. Гласът и позицията се трият с тревогата (до 30 мин). При непосредствена опасност звънни на 112 — приложението не е спешна служба и не гарантира, че някой ще дойде.", "No sign-up. No name, phone number or movement history is stored. Voice and position are deleted with the alarm (within 30 min). In immediate danger call 112 — this app is not an emergency service and does not guarantee that anyone will come."), 13f, color = Color.GRAY)
    }

    // ---------- малки помощници за UI ----------
    private val RED = 0xFFD32F2F.toInt()
    private val GREEN = 0xFF2E7D32.toInt()
    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun space() = root.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK) {
        root.addView(TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun button(label: String, secondary: Boolean = false, onClick: (Button) -> Unit) {
        root.addView(Button(this).apply {
            text = label
            if (secondary) alpha = 0.85f
            setOnClickListener { onClick(this) }
        })
    }

    private fun check(label: String, on: Boolean, hint: String?, onChange: (Boolean) -> Unit) {
        root.addView(CheckBox(this).apply {
            text = label
            textSize = 16f
            isChecked = on
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        if (hint != null) root.addView(TextView(this).apply {
            text = hint
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(dp(32), 0, 0, dp(6))
        })
    }

    private fun perm(label: String, ok: Boolean, fix: () -> Unit) {
        if (ok) text("✓ $label", 15f, color = GREEN)
        else button(tr("Разреши: $label", "Allow: $label")) { fix() }
    }
}
