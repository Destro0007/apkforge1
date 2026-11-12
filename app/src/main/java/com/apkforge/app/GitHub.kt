package com.apkforge.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class ApiException(val code: Int, message: String) : Exception(message)

/** Minimal GitHub REST client. All calls block, so call them from a background thread. */
class GitHub(private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val api = "https://api.github.com"

    private fun req(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun send(builder: Request.Builder): String {
        client.newCall(builder.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, describe(r.code, text))
            return text
        }
    }

    private fun get(path: String): String = send(req(api + path))

    private fun post(path: String, body: JSONObject): String =
        send(req(api + path).post(body.toString().toRequestBody(jsonType)))

    private fun patch(path: String, body: JSONObject): String =
        send(req(api + path).patch(body.toString().toRequestBody(jsonType)))

    private fun describe(code: Int, body: String): String {
        val msg = try {
            JSONObject(body).optString("message")
        } catch (e: Exception) {
            ""
        }
        val hint = when (code) {
            401 -> "The token was rejected. Create a new classic token with scopes: repo + workflow."
            403 -> "Refused by GitHub (token scopes, or rate limit)."
            404 -> "Not found. Check that the token has the repo + workflow scopes."
            422 -> "GitHub could not process this request."
            else -> ""
        }
        return "GitHub $code: $msg $hint".trim()
    }

    fun whoAmI(): String = JSONObject(get("/user")).getString("login")

    /** Makes sure the build repository exists. Returns its default branch. */
    fun ensureRepo(owner: String, repo: String): String {
        try {
            return JSONObject(get("/repos/$owner/$repo")).optString("default_branch", "main")
        } catch (e: ApiException) {
            if (e.code != 404) throw e
        }
        val body = JSONObject()
            .put("name", repo)
            .put("private", true)
            .put("auto_init", true)
            .put("description", "Apps built by APK Forge")
        return JSONObject(post("/user/repos", body)).optString("default_branch", "main")
    }

    /**
     * Commits [files] in a single commit. Files under [cleanPrefix] that are not in [files]
     * are deleted, so old source files from a previous build do not stay around.
     */
    fun commitFiles(
        owner: String,
        repo: String,
        branch: String,
        files: Map<String, String>,
        message: String,
        cleanPrefix: String
    ): String {
        val base = "/repos/$owner/$repo"

        var headSha: String? = null
        for (i in 0 until 8) {
            try {
                headSha = JSONObject(get("$base/git/ref/heads/$branch"))
                    .getJSONObject("object").getString("sha")
                break
            } catch (e: ApiException) {
                if (e.code != 404 && e.code != 409) throw e
                Thread.sleep(1500)
            }
        }
        val head = headSha ?: throw IllegalStateException("Branch '$branch' is not ready yet. Try again.")

        val baseTree = JSONObject(get("$base/git/commits/$head")).getJSONObject("tree").getString("sha")
        val existing = JSONObject(get("$base/git/trees/$baseTree?recursive=1")).optJSONArray("tree") ?: JSONArray()

        val entries = JSONArray()
        for ((path, content) in files) {
            entries.put(
                JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("content", content)
            )
        }
        for (i in 0 until existing.length()) {
            val e = existing.getJSONObject(i)
            val path = e.getString("path")
            if (e.getString("type") == "blob" && path.startsWith(cleanPrefix) && !files.containsKey(path)) {
                entries.put(
                    JSONObject().put("path", path).put("mode", "100644").put("type", "blob")
                        .put("sha", JSONObject.NULL)
                )
            }
        }

        val tree = JSONObject(
            post("$base/git/trees", JSONObject().put("base_tree", baseTree).put("tree", entries))
        ).getString("sha")
        val commit = JSONObject(
            post(
                "$base/git/commits",
                JSONObject().put("message", message).put("tree", tree).put("parents", JSONArray().put(head))
            )
        ).getString("sha")
        patch("$base/git/refs/heads/$branch", JSONObject().put("sha", commit))
        return commit
    }

    fun findRun(owner: String, repo: String, sha: String): Long? {
        val arr = JSONObject(get("/repos/$owner/$repo/actions/runs?head_sha=$sha&per_page=5"))
            .optJSONArray("workflow_runs") ?: return null
        if (arr.length() == 0) return null
        return arr.getJSONObject(0).getLong("id")
    }

    fun dispatch(owner: String, repo: String, branch: String) {
        post("/repos/$owner/$repo/actions/workflows/build.yml/dispatches", JSONObject().put("ref", branch))
    }

    /** Returns (status, conclusion). Conclusion is empty until the run has finished. */
    fun runStatus(owner: String, repo: String, runId: Long): Pair<String, String> {
        val j = JSONObject(get("/repos/$owner/$repo/actions/runs/$runId"))
        val conclusion = if (j.isNull("conclusion")) "" else j.getString("conclusion")
        return Pair(j.optString("status"), conclusion)
    }

    fun failureLog(owner: String, repo: String, runId: Long): String {
        val jobs = JSONObject(get("/repos/$owner/$repo/actions/runs/$runId/jobs")).getJSONArray("jobs")
        var jobId = -1L
        for (i in 0 until jobs.length()) {
            val j = jobs.getJSONObject(i)
            if (j.optString("conclusion") == "failure") {
                jobId = j.getLong("id")
                break
            }
            if (jobId < 0) jobId = j.getLong("id")
        }
        if (jobId < 0) return "No job log found."
        return send(req("$api/repos/$owner/$repo/actions/jobs/$jobId/logs"))
    }

    fun downloadApk(owner: String, repo: String, runId: Long, dest: File) {
        var artId = -1L
        for (attempt in 0 until 4) {
            val arts = JSONObject(get("/repos/$owner/$repo/actions/runs/$runId/artifacts")).getJSONArray("artifacts")
            for (i in 0 until arts.length()) {
                val a = arts.getJSONObject(i)
                if (a.optString("name") == "app-debug-apk") {
                    artId = a.getLong("id")
                    break
                }
            }
            if (artId >= 0) break
            Thread.sleep(3000)
        }
        if (artId < 0) throw IllegalStateException("The build finished but no APK file was found.")

        client.newCall(req("$api/repos/$owner/$repo/actions/artifacts/$artId/zip").build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, describe(r.code, r.body?.string().orEmpty()))
            val stream = r.body?.byteStream() ?: throw IllegalStateException("Empty download.")
            ZipInputStream(stream).use { zip ->
                var found = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                        dest.outputStream().use { out -> zip.copyTo(out) }
                        found = true
                        break
                    }
                }
                if (!found) throw IllegalStateException("No .apk found inside the download.")
            }
        }
    }
}
