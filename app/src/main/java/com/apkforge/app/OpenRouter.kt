package com.apkforge.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** OpenRouter client. Only models whose prompt AND completion price are 0 are ever offered. */
class OpenRouter(private val key: String) {

    data class Model(val id: String, val context: Int)

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        private val jsonType = "application/json; charset=utf-8".toMediaType()

        /** Used only if the live model list cannot be loaded. */
        val FALLBACK = listOf(
            "qwen/qwen3-coder:free",
            "deepseek/deepseek-chat-v3-0324:free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "google/gemini-2.0-flash-exp:free"
        )

        /** Live list of free text models, best coding models first. No API key needed. */
        fun freeModels(): List<Model> {
            val r = Request.Builder().url("https://openrouter.ai/api/v1/models").build()
            client.newCall(r).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Could not load the model list (${resp.code}).")
                val data = JSONObject(resp.body?.string().orEmpty()).getJSONArray("data")
                val out = ArrayList<Model>()
                for (i in 0 until data.length()) {
                    val m = data.getJSONObject(i)
                    val pricing = m.optJSONObject("pricing") ?: continue
                    val p = pricing.optString("prompt", "1").toDoubleOrNull() ?: continue
                    val c = pricing.optString("completion", "1").toDoubleOrNull() ?: continue
                    if (p != 0.0 || c != 0.0) continue
                    val id = m.getString("id")
                    if (id.contains("embed") || id.contains("moderation")) continue
                    val modality = m.optJSONObject("architecture")?.optString("modality").orEmpty()
                    if (modality.isNotEmpty() && !modality.endsWith("->text")) continue
                    out.add(Model(id, m.optInt("context_length", 0)))
                }
                return out.sortedByDescending { score(it) }
            }
        }

        private fun score(m: Model): Int {
            val id = m.id.lowercase()
            var s = minOf(m.context / 4000, 40)
            if ("coder" in id) s += 100
            if (listOf("qwen", "deepseek", "glm", "kimi", "gpt-oss", "llama-3.3", "gemini").any { it in id }) s += 25
            if (Regex("""\b(0\.5|1|1\.5|2|3|4|7|8)b\b""").containsMatchIn(id)) s -= 80
            return s
        }
    }

    /** Sends one chat request and returns the text of the reply. Blocks. */
    fun chat(model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/apkforge")
            .header("X-Title", "APK Forge")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException(errorOf(resp.code, text))
            val json = JSONObject(text)
            json.optJSONObject("error")?.let {
                throw IllegalStateException(it.optString("message", "AI error"))
            }
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
                ?: throw IllegalStateException("Empty AI reply.")
            if (choice.optString("finish_reason") == "length") {
                throw IllegalStateException("The reply was cut off (too long).")
            }
            val msg = choice.optJSONObject("message")
            val content = if (msg == null || msg.isNull("content")) "" else msg.getString("content")
            if (content.isBlank()) throw IllegalStateException("Empty AI reply.")
            return content
        }
    }

    private fun errorOf(code: Int, body: String): String {
        val msg = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) {
            ""
        }
        return when (code) {
            401 -> "OpenRouter rejected the API key."
            402 -> "This model needs credits."
            404 -> "Model not available for your account (check openrouter.ai/settings/privacy). $msg"
            429 -> "Free model is rate limited right now."
            else -> "OpenRouter $code: $msg"
        }.trim()
    }
}

object AiPrompts {
    val SYSTEM = """
You are a senior Android engineer fixing code that must build with Gradle (AGP 8.5, Kotlin 1.9.24, compileSdk 34, minSdk 24, JDK 17).
Available libraries: AndroidX core-ktx, appcompat, material (Material3 theme), constraintlayout, recyclerview, activity-ktx, fragment-ktx, lifecycle, coroutines, OkHttp, Gson, Jetpack Compose (BOM 2024.06.00, Material3, navigation-compose). View binding is enabled.
The project is given as files. Each file starts with a line "### FILE: path" (path relative to app/src/main/).
Fix every compile or resource error so the app builds and runs. Keep the app's behavior, package name and launcher activity.
Reply with the COMPLETE content of every file you changed or added, each preceded by its "### FILE: path" line. Omit files that need no change.
If a new library is required, add a file "### FILE: dependencies.gradle" with lines like implementation("group:artifact:version").
Output only these file blocks: no explanations, no markdown fences, no other text.
""".trimIndent()

    fun user(bundle: String, errors: String?): String {
        val sb = StringBuilder()
        if (errors.isNullOrBlank()) {
            sb.append("No build has failed yet. Review the code for compile errors, missing imports, missing resources or manifest problems, and fix them.\n\n")
        } else {
            sb.append("The build failed with these errors:\n").append(errors).append("\n\n")
        }
        sb.append("Current project files:\n\n").append(bundle)
        return sb.toString()
    }
}

object ChatPrompt {
    val TEXT = """
Create a complete Android app for me: [DESCRIBE YOUR APP HERE].

Rules for your answer:
1. Kotlin, minSdk 24, one module. Use Android Views (XML layouts) or Jetpack Compose with Material3.
2. Give me EVERY file needed. Put one line exactly like this before each file:
### FILE: java/com/example/myapp/MainActivity.kt
(paths are relative to app/src/main/ - layouts go in res/layout/, values in res/values/, drawables in res/drawable/)
3. Include a MainActivity as the launcher activity. Do not include build.gradle, settings.gradle or AndroidManifest.xml unless you need extra permissions or activities.
4. If you need extra libraries, add a file named dependencies.gradle containing only lines like implementation("group:artifact:version").
5. Already available: AndroidX core/appcompat/material/constraintlayout/recyclerview/lifecycle, Compose (BOM 2024.06, Material3, navigation), OkHttp, Gson, coroutines, view binding.
6. No explanations and no markdown code fences - only the ### FILE: lines and the code.
""".trimIndent()
}
