package bg.emillion.sos

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Езикът следва телефона: български, ако е на български, иначе английски. */
object Lang {
    val bg: Boolean get() = Locale.getDefault().language == "bg"
    val code: String get() = if (bg) "bg" else "en"
}

/** Текст на езика на телефона. */
fun tr(bg: String, en: String): String = if (Lang.bg) bg else en

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("sos", Context.MODE_PRIVATE)
    fun token(c: Context): String? = sp(c).getString("token", null)
    fun n(c: Context): Int = sp(c).getInt("n", 0)
    fun saveSession(c: Context, token: String, n: Int) = sp(c).edit().putString("token", token).putInt("n", n).apply()
    fun clearSession(c: Context) = sp(c).edit().remove("token").apply()

    /** Показвай точното място при тревога (иначе само района ~300 м). */
    fun exact(c: Context) = sp(c).getBoolean("exact", false)
    fun setExact(c: Context, v: Boolean) = sp(c).edit().putBoolean("exact", v).apply()

    /** След гласовото съобщение отвори 112. */
    fun call112(c: Context) = sp(c).getBoolean("call112", false)
    fun setCall112(c: Context, v: Boolean) = sp(c).edit().putBoolean("call112", v).apply()

    /** Получавай тревоги от други. */
    fun receive(c: Context) = sp(c).getBoolean("receive", true)
    fun setReceive(c: Context, v: Boolean) = sp(c).edit().putBoolean("receive", v).apply()

    /**
     * Ид на устройството. ANDROID_ID оцелява при преинсталиране (сменя се само при фабрично
     * нулиране или друг ключ за подписване). Ако липсва — случаен, пазен локално.
     */
    @SuppressLint("HardwareIds")
    fun device(c: Context): String {
        val aid = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID)
        if (aid != null && aid.matches(Regex("[0-9a-fA-F]{8,32}")) && aid != "9774d56d682e549c") return aid.lowercase()
        sp(c).getString("rnd", null)?.let { return it }
        val b = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val rnd = b.joinToString("") { "%02x".format(it) }
        sp(c).edit().putString("rnd", rnd).apply()
        return rnd
    }
}

class HttpError(val code: Int, msg: String) : Exception(msg)

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    val wsUrl: String get() = BuildConfig.SERVER.replaceFirst("https", "wss") + "/ws?lang=" + Lang.code + "&token="

    /** Блокира — вика се от нишка. Без регистрация: само ид на устройството. */
    fun register(device: String): JSONObject {
        val body = JSONObject().put("device", device).put("lang", Lang.code).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(BuildConfig.SERVER + "/register").post(body).build()
        http.newCall(req).execute().use { r ->
            val j = JSONObject(r.body?.string() ?: "{}")
            if (!r.isSuccessful) throw HttpError(r.code, j.optString("error", tr("Грешка ", "Error ") + r.code))
            return j
        }
    }
}
