package com.apkforge.app

import android.content.Context

/** Small wrapper around SharedPreferences (private to this app). */
class Store(context: Context) {
    private val sp = context.getSharedPreferences("apkforge", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = ""): String = sp.getString(key, def) ?: def
    private fun put(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }

    var githubToken: String
        get() = str("gh_token")
        set(v) = put("gh_token", v.trim())

    var githubUser: String
        get() = str("gh_user")
        set(v) = put("gh_user", v.trim())

    var repoName: String
        get() = str("repo", "apkforge-builds")
        set(v) = put("repo", v.trim().replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "apkforge-builds" })

    var openRouterKey: String
        get() = str("or_key")
        set(v) = put("or_key", v.trim())

    /** Empty = automatic (best free models). */
    var model: String
        get() = str("model")
        set(v) = put("model", v.trim())

    var appName: String
        get() = str("app_name", "My App")
        set(v) = put("app_name", v.trim())

    /** Empty = detected from the pasted code. */
    var packageName: String
        get() = str("package")
        set(v) = put("package", v.trim())

    var code: String
        get() = str("code")
        set(v) = put("code", v)

    var autoFix: Boolean
        get() = sp.getBoolean("auto_fix", true)
        set(v) {
            sp.edit().putBoolean("auto_fix", v).apply()
        }

    fun nextVersion(): Int {
        val n = sp.getInt("version", 0) + 1
        sp.edit().putInt("version", n).apply()
        return n
    }
}
