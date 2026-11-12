package com.apkforge.app

import android.content.res.AssetManager

/** A source file. [path] is relative to app/src/main/ */
data class SourceFile(val path: String, val content: String)

class ParsedProject(
    val files: List<SourceFile>,
    val extraDeps: List<String>,
    val pkg: String,
    val launcher: String
) {
    val hasManifest: Boolean get() = files.any { it.path == "AndroidManifest.xml" }

    /** Text form: "### FILE: path" lines followed by the file content. */
    fun toBundle(): String {
        val sb = StringBuilder()
        for (f in files) {
            sb.append("### FILE: ").append(f.path).append('\n')
            sb.append(f.content.trimEnd('\n')).append("\n\n")
        }
        if (extraDeps.isNotEmpty()) {
            sb.append("### FILE: dependencies.gradle\n")
            for (d in extraDeps) sb.append("implementation(\"").append(d).append("\")\n")
        }
        return sb.toString()
    }
}

object ProjectBuilder {

    private val marker = Regex(
        """^\s*(?:###|##|//|<!--|/\*|===)\s*FILE\s*:\s*(\S+?)\s*(?:-->|\*/|===)?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val pkgRegex = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val depRegex = Regex("""implementation\s*\(?\s*["']([^"']+)["']""")
    private val depOk = Regex("""^[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+(:[A-Za-z0-9_.\-+]+)?$""")
    private val activityRegex = Regex("""class\s+(\w+)\s*(?::|extends)\s*([\w.]*Activity)\b""")

    /**
     * Turns pasted text into files. Without "### FILE:" markers the whole text is one file.
     * With [ignorePreamble] any text before the first marker is dropped (used for AI replies).
     */
    fun parse(raw: String, ignorePreamble: Boolean = false): ParsedProject {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")
            .filter { !it.trimStart().startsWith("```") }

        val blocks = ArrayList<Pair<String?, String>>()
        var name: String? = null
        val buf = StringBuilder()
        var sawMarker = false

        fun flush() {
            val text = buf.toString().trim('\n')
            buf.setLength(0)
            if (text.isNotBlank()) blocks.add(Pair(name, text))
        }

        for (line in lines) {
            val m = marker.matchEntire(line)
            if (m != null) {
                if (ignorePreamble && !sawMarker) buf.setLength(0) else flush()
                name = m.groupValues[1]
                sawMarker = true
            } else {
                buf.append(line).append('\n')
            }
        }
        flush()

        val allText = blocks.joinToString("\n") { it.second }
        val defaultPkg = pkgRegex.find(allText)?.groupValues?.get(1) ?: "com.example.app"

        val files = LinkedHashMap<String, SourceFile>()
        val deps = LinkedHashSet<String>()
        for ((rawName, text) in blocks) {
            val n = rawName?.trim()
            if (n != null && (n.endsWith(".gradle") || n.endsWith(".gradle.kts") || n.endsWith("dependencies.txt"))) {
                for (m in depRegex.findAll(text)) {
                    val dep = m.groupValues[1]
                    if (depOk.matches(dep)) deps.add(dep)
                }
                continue
            }
            val path = pathFor(n, text, defaultPkg)
            files[path] = SourceFile(path, text + "\n")
        }

        var launcher: String? = null
        var first: String? = null
        for (f in files.values) {
            if (!(f.path.endsWith(".kt") || f.path.endsWith(".java"))) continue
            val own = pkgRegex.find(f.content)?.groupValues?.get(1) ?: defaultPkg
            for (m in activityRegex.findAll(f.content)) {
                val fq = own + "." + m.groupValues[1]
                if (first == null) first = fq
                if (launcher == null && m.groupValues[1] == "MainActivity") launcher = fq
            }
        }
        return ParsedProject(
            files.values.toList(),
            deps.toList(),
            defaultPkg,
            launcher ?: first ?: "$defaultPkg.MainActivity"
        )
    }

    /** Overlays the files returned by the AI on top of the current project. */
    fun merge(old: ParsedProject, upd: ParsedProject): String {
        val map = LinkedHashMap<String, SourceFile>()
        old.files.forEach { map[it.path] = it }
        upd.files.forEach { map[it.path] = it }
        val deps = LinkedHashSet<String>()
        deps.addAll(old.extraDeps)
        deps.addAll(upd.extraDeps)
        return ParsedProject(map.values.toList(), deps.toList(), old.pkg, old.launcher).toBundle()
    }

    /** Builds the complete file set that is pushed to the GitHub build repository. */
    fun assemble(
        assets: AssetManager,
        p: ParsedProject,
        appName: String,
        pkg: String,
        versionCode: Int
    ): Map<String, String> {
        fun tpl(name: String): String =
            assets.open("template/$name").bufferedReader().use { it.readText() }

        val depLines = p.extraDeps.joinToString("\n") { "    implementation(\"$it\")" }

        fun fill(s: String): String = s
            .replace("@@PACKAGE@@", pkg)
            .replace("@@VERSIONCODE@@", versionCode.toString())
            .replace("@@VERSIONNAME@@", "1.$versionCode")
            .replace("@@LAUNCHER@@", p.launcher)
            .replace("@@EXTRADEPS@@", depLines)

        val out = LinkedHashMap<String, String>()
        out[".github/workflows/build.yml"] = tpl("workflow.yml.tpl")
        out["settings.gradle.kts"] = tpl("settings.gradle.kts.tpl")
        out["build.gradle.kts"] = tpl("build.gradle.kts.tpl")
        out["gradle.properties"] = tpl("gradle.properties.tpl")
        out["app/build.gradle.kts"] = fill(tpl("app_build.gradle.kts.tpl"))
        out["app/proguard-rules.pro"] = "# none\n"

        val main = "app/src/main/"
        if (!p.hasManifest) out[main + "AndroidManifest.xml"] = fill(tpl("manifest.xml.tpl"))
        out[main + "res/drawable/apkforge_icon.xml"] = tpl("icon.xml.tpl")
        out[main + "res/values/apkforge_theme.xml"] = tpl("theme.xml.tpl")
        out[main + "res/values/apkforge_strings.xml"] =
            "<resources>\n    <string name=\"apkforge_app_name\">${escapeRes(appName)}</string>\n</resources>\n"

        for (f in p.files) out[main + f.path] = f.content
        return out
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun pathFor(name: String?, text: String, defPkg: String): String {
        var n = name?.trim()?.replace('\\', '/')?.trimStart('/')
        if (n != null) {
            n = n.removePrefix("app/src/main/").removePrefix("src/main/")
            if (n.contains('/')) return n
        }
        val own = pkgRegex.find(text)?.groupValues?.get(1) ?: defPkg
        val simple: String = n ?: when {
            text.trimStart().startsWith("<") -> "activity_main.xml"
            isKotlin(text) -> guessClassName(text) + ".kt"
            else -> guessClassName(text) + ".java"
        }
        return when {
            simple.endsWith(".kt") || simple.endsWith(".java") ->
                "java/" + own.replace('.', '/') + "/" + simple
            simple.endsWith(".xml") -> xmlPath(simple, text)
            else -> "assets/$simple"
        }
    }

    private fun xmlPath(n: String, text: String): String {
        if (n == "AndroidManifest.xml") return n
        val head = text.take(700)
        return when {
            Regex("""<\s*resources\b""").containsMatchIn(head) -> "res/values/$n"
            Regex("""<\s*(shape|selector|vector|layer-list|ripple|animated-vector|inset|rotate|bitmap|clip)\b""")
                .containsMatchIn(head) -> "res/drawable/$n"
            Regex("""<\s*menu\b""").containsMatchIn(head) -> "res/menu/$n"
            Regex("""<\s*(PreferenceScreen|network-security-config|paths|full-backup-content|data-extraction-rules|locale-config)\b""")
                .containsMatchIn(head) -> "res/xml/$n"
            else -> "res/layout/$n"
        }
    }

    private fun isKotlin(text: String): Boolean {
        if (Regex("""\bfun\s+\w+""").containsMatchIn(text)) return true
        if (Regex("""^\s*(import|package)\s+[\w.*]+[ \t]*$""", RegexOption.MULTILINE).containsMatchIn(text)) return true
        return !Regex("""\b(public|private|protected)\s+(static\s+)?(final\s+)?(class|void|int|String)\b""")
            .containsMatchIn(text)
    }

    private fun guessClassName(text: String): String {
        Regex("""class\s+(\w+)\s*(?::|extends)\s*[\w.]*Activity\b""").find(text)?.let { return it.groupValues[1] }
        Regex("""(?:class|object|interface)\s+(\w+)""").find(text)?.let { return it.groupValues[1] }
        return "MainActivity"
    }

    private fun escapeRes(s: String): String {
        var t = s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
            .replace("&", "&amp;").replace("<", "&lt;")
        if (t.startsWith("@") || t.startsWith("?")) t = "\\" + t
        return t
    }
}
