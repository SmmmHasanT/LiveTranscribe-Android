package com.charles.livecaptionn.translation

import android.util.Log
import com.charles.livecaptionn.data.SettingsRepository
import com.charles.livecaptionn.settings.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Translates through the DeepL REST API using the user's own API key.
 *
 * - Free keys end in ":fx" and go to api-free.deepl.com; others to api.deepl.com.
 * - The last few source lines are sent as `context` (DeepL does not bill
 *   context characters) so short game lines translate with better meaning.
 */
class DeepLTranslationRepository(
    private val settingsRepository: SettingsRepository
) : TranslationRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .writeTimeout(9, TimeUnit.SECONDS)
        .build()

    private val recentLines = ArrayDeque<String>()

    override suspend fun translate(
        text: String,
        sourceCode: String,
        targetCode: String,
        autoDetect: Boolean
    ): String {
        val clean = text.trim()
        if (clean.isEmpty() || targetCode.isBlank()) return clean
        val key = settingsRepository.settingsFlow.first().deeplApiKey.trim()
        if (key.isEmpty()) {
            Log.w(TAG, "DeepL selected but no API key set.")
            return ""
        }
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("text", JSONArray().put(clean))
                    put("target_lang", targetLang(targetCode))
                    if (!autoDetect && sourceCode.isNotBlank() && sourceCode != Language.AUTO_CODE) {
                        put("source_lang", sourceLang(sourceCode))
                    }
                    synchronized(recentLines) {
                        if (recentLines.isNotEmpty()) put("context", recentLines.joinToString("\n"))
                    }
                }
                val request = Request.Builder()
                    .url(endpoint(key))
                    .header("Authorization", "DeepL-Auth-Key $key")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val payload = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "DeepL HTTP ${resp.code}: ${payload.take(200)}")
                        return@withContext ""
                    }
                    val out = JSONObject(payload)
                        .getJSONArray("translations")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()
                    remember(clean)
                    out
                }
            } catch (t: Throwable) {
                Log.w(TAG, "DeepL translation failed.", t)
                ""
            }
        }
    }

    private fun remember(line: String) = synchronized(recentLines) {
        recentLines.addLast(line)
        while (recentLines.size > CONTEXT_LINES) recentLines.removeFirst()
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val TAG = "DeepLRepo"
        private const val CONTEXT_LINES = 3
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun isFreeKey(key: String) = key.trim().endsWith(":fx")

        fun endpoint(key: String): String =
            if (isFreeKey(key)) "https://api-free.deepl.com/v2/translate"
            else "https://api.deepl.com/v2/translate"

        /** DeepL source codes are plain language codes, upper-cased. */
        fun sourceLang(code: String): String = code.substringBefore('-').uppercase()

        /** A few DeepL targets need a regional variant. */
        fun targetLang(code: String): String = when (val c = code.lowercase()) {
            "en" -> "EN-US"
            "pt" -> "PT-BR"
            "zh", "zh-cn", "zh-hans" -> "ZH-HANS"
            "zh-tw", "zh-hant" -> "ZH-HANT"
            else -> c.uppercase()
        }

        /** Languages the DeepL API supports (source and target). */
        val LANGUAGES: List<Language> = listOf(
            Language("tr", "Turkish"),
            Language("en", "English"),
            Language("ja", "Japanese"),
            Language("ko", "Korean"),
            Language("zh", "Chinese"),
            Language("de", "German"),
            Language("fr", "French"),
            Language("es", "Spanish"),
            Language("it", "Italian"),
            Language("pt", "Portuguese"),
            Language("ru", "Russian"),
            Language("uk", "Ukrainian"),
            Language("pl", "Polish"),
            Language("nl", "Dutch"),
            Language("cs", "Czech"),
            Language("sk", "Slovak"),
            Language("sl", "Slovenian"),
            Language("hu", "Hungarian"),
            Language("ro", "Romanian"),
            Language("bg", "Bulgarian"),
            Language("el", "Greek"),
            Language("da", "Danish"),
            Language("sv", "Swedish"),
            Language("fi", "Finnish"),
            Language("nb", "Norwegian"),
            Language("et", "Estonian"),
            Language("lv", "Latvian"),
            Language("lt", "Lithuanian"),
            Language("id", "Indonesian"),
            Language("ar", "Arabic"),
        )
    }
}
