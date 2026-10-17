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
        render()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        render()
    }

    private fun render() {
        root.removeAllViews()
        val n = Prefs.n(this)
        text(if (n > 0) "SOS · Помощ $n" else "SOS — помощ наоколо", 26f, bold = true, color = RED)
        text("Задръж червения бутон 2 сек → тревогата тръгва веднага до хората наоколо (7 км) и започва запис на глас до 20 сек.", 15f)
        space()

        // ---------- настройки на тревогата ----------
        text("При тревога", 18f, bold = true)
        check("Давай точна локация", Prefs.exact(this),
            "Изключено: виждат само района (~300 м). Можеш да я включиш и по време на тревогата.") {
            Prefs.setExact(this, it)
        }
        check("След гласовото съобщение отвори 112", Prefs.call112(this), null) { Prefs.setCall112(this, it) }
        check("Получавай тревоги от други", Prefs.receive(this), null) {
            Prefs.setReceive(this, it)
            if (SosService.running) startForegroundService(Intent(this, SosService::class.java))
        }
        space()

        // ---------- разрешения ----------
        text("Разрешения", 18f, bold = true)
        val loc = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val mic = has(Manifest.permission.RECORD_AUDIO)
        val notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
        val overlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)

        perm("Локация", loc) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        perm("Микрофон (за гласовото съобщение)", mic) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
        }
        perm("Известия", notif) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        perm("Бутон над приложенията", overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        perm("Без спиране от батерията", battery) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        space()

        if (SosService.running) {
            text("● На линия", 16f, bold = true, color = GREEN)
            button("Спри") {
                stopService(Intent(this, SosService::class.java))
                root.postDelayed({ render() }, 300)
            }
        } else {
            button("Включи") {
                if (!loc) { toast("Първо разреши локация"); return@button }
                startForegroundService(Intent(this, SosService::class.java))
                root.postDelayed({ render() }, 800)
            }
        }
        if (SosService.running && !mic) {
            text("Микрофонът е разрешен след включването — спри и включи отново, за да работи записът.", 13f, color = Color.GRAY)
        }
        space()
        button("📞 112", secondary = true) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
        }
        space()
        text("Без регистрация. Не се пазят име, телефон или история на движението. Гласът и позицията се трият с тревогата (до 30 мин). При непосредствена опасност звънни на 112 — приложението не е спешна служба и не гарантира, че някой ще дойде.", 13f, color = Color.GRAY)
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
        else button("Разреши: $label") { fix() }
    }
}
