package bg.emillion.sos

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("sos", Context.MODE_PRIVATE)
    fun token(c: Context): String? = sp(c).getString("token", null)
    fun n(c: Context): Int = sp(c).getInt("n", 0)
    fun plate(c: Context): String = sp(c).getString("plate", "") ?: ""
    fun save(c: Context, token: String, n: Int, plate: String) =
        sp(c).edit().putString("token", token).putInt("n", n).putString("plate", plate).apply()
    fun clear(c: Context) = sp(c).edit().remove("token").apply()
}

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    val wsUrl: String get() = BuildConfig.SERVER.replaceFirst("https", "wss") + "/ws?token="

    /** Блокира — вика се от нишка. Връща JSON отговора или хвърля грешка с текст за човека. */
    fun login(plate: String, password: String): JSONObject {
        val body = JSONObject().put("plate", plate).put("password", password).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(BuildConfig.SERVER + "/login").post(body).build()
        http.newCall(req).execute().use { r ->
            val j = JSONObject(r.body?.string() ?: "{}")
            if (!r.isSuccessful) throw Exception(j.optString("error", "Грешка " + r.code))
            return j
        }
    }
}
