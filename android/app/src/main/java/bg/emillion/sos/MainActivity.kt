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
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
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
        if (Prefs.token(this) == null) loginUi() else mainUi()
    }

    private fun loginUi() {
        text("SOS — колега в беда", 22f, bold = true, color = 0xFFD32F2F.toInt())
        text("Влез с регистрационния номер на таксито и паролата, която ти е дадена.", 15f)
        val plate = EditText(this).apply {
            hint = "Рег. номер, напр. CA1234XX"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(Prefs.plate(this@MainActivity))
        }
        val pass = EditText(this).apply {
            hint = "Парола"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(plate)
        root.addView(pass)
        button("Вход") { btn ->
            btn.isEnabled = false
            val p = plate.text.toString().trim()
            val pw = pass.text.toString().trim()
            Thread {
                try {
                    val j = Net.login(p, pw)
                    Prefs.save(this, j.getString("token"), j.getInt("n"), j.getString("plate"))
                    runOnUiThread { render() }
                } catch (e: Exception) {
                    runOnUiThread {
                        btn.isEnabled = true
                        toast(e.message ?: "Няма връзка")
                    }
                }
            }.start()
        }
    }

    private fun mainUi() {
        text("Такси ${Prefs.n(this)}", 26f, bold = true, color = 0xFFD32F2F.toInt())
        text(Prefs.plate(this), 15f)
        space()

        val loc = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
        val overlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)

        check("Локация", loc) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        check("Известия", notif) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        check("Бутон над приложенията", overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        check("Без спиране от батерията", battery) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        space()

        if (SosService.running) {
            text("● На линия. Задръж червения бутон 2 сек за тревога.", 15f, color = 0xFF2E7D32.toInt())
            button("Спри") {
                stopService(Intent(this, SosService::class.java))
                root.postDelayed({ render() }, 300)
            }
        } else {
            button("Включи") {
                if (!loc) { toast("Първо разреши локация"); return@button }
                startForegroundService(Intent(this, SosService::class.java))
                root.postDelayed({ render() }, 500)
            }
        }
        space()
        text("Тревогата стига до колегите в радиус 7 км. Не замества 112.", 13f, color = Color.GRAY)
        button("Изход") {
            stopService(Intent(this, SosService::class.java))
            Prefs.clear(this)
            render()
        }
    }

    // ---------- малки помощници за UI ----------
    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun space() = root.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK) {
        root.addView(TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun button(label: String, onClick: (Button) -> Unit) {
        root.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick(this) }
        })
    }

    private fun check(label: String, ok: Boolean, fix: () -> Unit) {
        if (ok) text("✓ $label", 15f, color = 0xFF2E7D32.toInt())
        else button("Разреши: $label") { fix() }
    }
}
