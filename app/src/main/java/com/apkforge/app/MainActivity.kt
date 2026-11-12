package com.apkforge.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private class Outcome(val apk: File?, val errors: String?)

    private lateinit var store: Store
    private lateinit var appNameInput: EditText
    private lateinit var packageInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var autoFixBox: CheckBox
    private lateinit var buildBtn: MaterialButton
    private lateinit var fixBtn: MaterialButton
    private lateinit var undoBtn: MaterialButton
    private lateinit var downloadBtn: MaterialButton
    private lateinit var installBtn: MaterialButton
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var progress: ProgressBar

    private var job: Job? = null
    private var previousCode: String? = null
    private var lastErrors: String? = null
    private var lastApk: File? = null
    private var cachedModels: List<OpenRouter.Model>? = null

    // ------------------------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        setContentView(buildUi())
        appNameInput.setText(store.appName)
        packageInput.setText(store.packageName)
        codeInput.setText(store.code)
        autoFixBox.isChecked = store.autoFix
        setBusy(false)
        log("Ready. 1) Settings: add your GitHub token. 2) Paste your code. 3) Build APK.")
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    private fun saveFields() {
        store.appName = appNameInput.text.toString()
        store.packageName = packageInput.text.toString()
        store.code = codeInput.text.toString()
    }

    // ------------------------------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun btn(label: String, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), 0, dp(2), 0)
            r.addView(v, lp)
        }
        return r
    }

    private fun space(h: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(h))
        return v
    }

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(24))

        val title = TextView(this)
        title.text = "APK Forge"
        title.textSize = 26f
        title.typeface = Typeface.DEFAULT_BOLD
        root.addView(title)

        val sub = TextView(this)
        sub.text = "Paste Android code, get an installable APK."
        sub.textSize = 13f
        sub.alpha = 0.7f
        root.addView(sub)
        root.addView(space(10))

        root.addView(row(
            btn("Settings") { showSettings() },
            btn("Copy ChatGPT prompt") { copyPrompt() }
        ))
        root.addView(space(6))

        appNameInput = EditText(this)
        appNameInput.hint = "App name"
        appNameInput.setSingleLine(true)
        root.addView(appNameInput)

        packageInput = EditText(this)
        packageInput.hint = "Package name (leave empty = auto-detect)"
        packageInput.setSingleLine(true)
        packageInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        root.addView(packageInput)
        root.addView(space(6))

        codeInput = EditText(this)
        codeInput.hint = "Paste your Android code here (Kotlin / Java / XML).\nSeveral files: put a line  ### FILE: name  before each one."
        codeInput.typeface = Typeface.MONOSPACE
        codeInput.textSize = 12f
        codeInput.gravity = Gravity.TOP or Gravity.START
        codeInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val frame = GradientDrawable()
        frame.setStroke(dp(1), 0x55888888)
        frame.cornerRadius = dp(8).toFloat()
        codeInput.background = frame
        codeInput.setPadding(dp(10), dp(10), dp(10), dp(10))
        codeInput.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                v.parent.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
        root.addView(codeInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))
        root.addView(space(6))

        root.addView(row(
            btn("Paste") { pasteClipboard() },
            btn("Clear") { codeInput.setText("") }
        ))

        autoFixBox = CheckBox(this)
        autoFixBox.text = "Auto-fix failed builds with free AI (max 3 tries)"
        autoFixBox.setOnCheckedChangeListener { _, checked -> store.autoFix = checked }
        root.addView(autoFixBox)

        buildBtn = btn("Build APK") { onBuildClicked() }
        buildBtn.textSize = 18f
        buildBtn.minimumHeight = dp(56)
        root.addView(buildBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fixBtn = btn("Fix with AI") { onFixClicked() }
        undoBtn = btn("Undo AI change") { undoAi() }
        root.addView(row(fixBtn, undoBtn))

        downloadBtn = btn("Download APK") { lastApk?.let { saveApkToDownloads(it) } }
        downloadBtn.visibility = View.GONE
        root.addView(downloadBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        installBtn = btn("Install APK") { lastApk?.let { installApk(it) } }
        installBtn.visibility = View.GONE
        root.addView(installBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(space(8))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.isIndeterminate = true
        progress.visibility = View.GONE
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusView = TextView(this)
        statusView.textSize = 15f
        statusView.typeface = Typeface.DEFAULT_BOLD
        statusView.setTextIsSelectable(true)
        root.addView(statusView)
        root.addView(space(6))

        logView = TextView(this)
        logView.typeface = Typeface.MONOSPACE
        logView.textSize = 11f
        logView.setTextIsSelectable(true)
        root.addView(logView)
        root.addView(btn("Copy log") {
            copyText("log", logView.text.toString())
            toast("Log copied")
        })

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(root)
        return scroll
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        buildBtn.text = if (busy) "Stop" else "Build APK"
        fixBtn.isEnabled = !busy
        undoBtn.isEnabled = !busy && previousCode != null
    }

    private fun setStatus(s: String) {
        statusView.text = s
    }

    private fun log(line: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logView.append("[$t] $line\n")
    }

    // ---------------------------------------------------------------------------- clipboard

    private fun clipboardText(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
    }

    private fun copyText(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun copyPrompt() {
        copyText("prompt", ChatPrompt.TEXT)
        toast("Prompt copied. Paste it into ChatGPT and describe your app.")
    }

    private fun pasteClipboard() {
        val t = clipboardText()
        if (t.isBlank()) {
            toast("Clipboard is empty")
            return
        }
        codeInput.setText(t)
    }

    private fun addFileDialog() {
        val t = clipboardText()
        if (t.isBlank()) {
            toast("Copy a file's code first, then tap Add file")
            return
        }
        val input = EditText(this)
        input.hint = "File name, e.g. MainActivity.kt or activity_main.xml"
        input.setSingleLine(true)
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("Add copied code as a file")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                val current = codeInput.text.toString().trimEnd()
                val head = if (name.isNotEmpty()) "### FILE: $name\n" else ""
                codeInput.setText(if (current.isEmpty()) head + t else current + "\n\n" + head + t)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------------------- settings

    private fun showSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        fun field(hint: String, value: String, secret: Boolean): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(value)
            e.setSingleLine(true)
            e.inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return e
        }

        val help = TextView(this)
        help.text = "GitHub token (classic, scopes: repo + workflow): github.com/settings/tokens\n" +
            "OpenRouter key (free): openrouter.ai/keys. Also allow free models at openrouter.ai/settings/privacy"
        help.textSize = 12f
        help.alpha = 0.75f
        box.addView(help)

        val token = field("GitHub token", store.githubToken, true)
        val repo = field("Build repository name", store.repoName, false)
        val orKey = field("OpenRouter API key (free)", store.openRouterKey, true)

        val modelBtn = btn(if (store.model.isEmpty()) "AI model: Auto" else "AI model: ${store.model}") { chooseModel() }
        box.addView(modelBtn)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val newToken = token.text.toString().trim()
                if (newToken != store.githubToken) store.githubUser = ""
                store.githubToken = newToken
                val newRepo = repo.text.toString().trim()
                if (newRepo != store.repoName) store.githubUser = ""
                store.repoName = newRepo
                store.openRouterKey = orKey.text.toString()
                toast("Saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun loadModels(): List<OpenRouter.Model> {
        cachedModels?.let { return it }
        val list = io { OpenRouter.freeModels() }
        cachedModels = list
        return list
    }

    private fun chooseModel() {
        lifecycleScope.launch {
            val models = try {
                loadModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Could not load free models: ${e.message}")
                return@launch
            }
            val ids = models.take(40).map { it.id }
            val names = (listOf("Auto (best free models, recommended)") + ids).toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Free AI model")
                .setItems(names) { _, which ->
                    store.model = if (which == 0) "" else ids[which - 1]
                    toast(if (which == 0) "Model: Auto" else "Model: ${ids[which - 1]}")
                }
                .show()
        }
    }

    // ----------------------------------------------------------------------- build pipeline

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun onBuildClicked() {
        if (job?.isActive == true) {
            job?.cancel()
            return
        }
        saveFields()
        job = lifecycleScope.launch {
            setBusy(true)
            try {
                runPipeline()
            } catch (e: CancellationException) {
                log("Stopped.")
                setStatus("Stopped.")
            } catch (e: Exception) {
                log("Error: ${e.message}")
                setStatus("Failed: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun runPipeline() {
        if (store.githubToken.isBlank()) {
            toast("Add your GitHub token in Settings first")
            showSettings()
            return
        }
        installBtn.visibility = View.GONE
        var attempt = 0
        while (true) {
            val outcome = buildOnce()
            val apk = outcome.apk
            if (apk != null) {
                lastApk = apk
                lastErrors = null
                downloadBtn.visibility = View.VISIBLE
                installBtn.visibility = View.VISIBLE
                val mb = apk.length() / 1024.0 / 1024.0
                setStatus("Build complete. APK ready (%.1f MB). Choose Download APK or Install APK.".format(Locale.US, mb))
                log("APK downloaded to APK Forge storage. Installation is now a separate action.")
                return
            }
            lastErrors = outcome.errors
            log("Build failed. Main errors:\n" + (outcome.errors ?: "").lines().take(25).joinToString("\n"))
            val canFix = autoFixBox.isChecked && store.openRouterKey.isNotBlank() && attempt < 3
            if (!canFix) {
                setStatus("Build failed. Tap 'Fix with AI' or edit the code, then build again.")
                return
            }
            attempt++
            log("Asking a free AI to fix the code (try $attempt of 3)...")
            if (!aiFix(outcome.errors)) {
                setStatus("The AI could not fix it. Edit the code and try again.")
                return
            }
        }
    }

    private suspend fun buildOnce(): Outcome {
        saveFields()
        val project = ProjectBuilder.parse(codeInput.text.toString())
        if (project.files.isEmpty()) throw IllegalStateException("Paste some code first.")

        val pkg = store.packageName.ifBlank { project.pkg }
        val pkgOk = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$""").matches(pkg)
        if (!pkgOk) throw IllegalStateException("Package name '$pkg' is not valid (example: com.example.myapp).")

        val gh = GitHub(store.githubToken)
        setStatus("Connecting to GitHub...")
        var ownerTmp = store.githubUser
        if (ownerTmp.isBlank()) {
            ownerTmp = io { gh.whoAmI() }
            store.githubUser = ownerTmp
        }
        val owner = ownerTmp
        val repo = store.repoName

        setStatus("Preparing repository $owner/$repo...")
        val branch = io { gh.ensureRepo(owner, repo) }

        val versionCode = store.nextVersion()
        val appName = store.appName.ifBlank { "My App" }
        val files = io { ProjectBuilder.assemble(assets, project, appName, pkg, versionCode) }

        setStatus("Uploading ${files.size} files to GitHub...")
        val sha = io { gh.commitFiles(owner, repo, branch, files, "APK Forge build $versionCode", "app/src/main/") }
        log("Code uploaded (commit ${sha.take(7)}).")

        val runId = waitForRun(gh, owner, repo, sha, branch)
        log("Cloud build started: github.com/$owner/$repo/actions/runs/$runId")

        val started = System.currentTimeMillis()
        var conclusion = ""
        while (true) {
            val st = io { gh.runStatus(owner, repo, runId) }
            if (st.first == "completed") {
                conclusion = st.second
                break
            }
            val sec = (System.currentTimeMillis() - started) / 1000
            setStatus("Building in the cloud... ${sec / 60}m ${sec % 60}s (usually 4-8 min)")
            if (sec > 25 * 60) throw IllegalStateException("The build is taking too long. Check GitHub > Actions.")
            delay(6000)
        }

        if (conclusion == "success") {
            setStatus("Downloading the APK...")
            val dir = File(filesDir, "apks")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "app-$versionCode.apk")
            io { gh.downloadApk(owner, repo, runId, out) }
            return Outcome(out, null)
        }

        setStatus("Build failed. Reading the log...")
        val raw = try {
            io { gh.failureLog(owner, repo, runId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Build $conclusion. Could not read the log: ${e.message}"
        }
        return Outcome(null, LogTools.extractErrors(raw))
    }

    private suspend fun waitForRun(gh: GitHub, owner: String, repo: String, sha: String, branch: String): Long {
        val start = System.currentTimeMillis()
        var dispatched = false
        while (true) {
            val id = io { gh.findRun(owner, repo, sha) }
            if (id != null) return id
            val waited = (System.currentTimeMillis() - start) / 1000
            setStatus("Waiting for GitHub to start the build... ${waited}s")
            if (waited > 40 && !dispatched) {
                dispatched = true
                log("No automatic start, starting the workflow manually.")
                io { gh.dispatch(owner, repo, branch) }
            }
            if (waited > 180) {
                throw IllegalStateException("GitHub Actions did not start. Open the repo > Actions tab and enable workflows.")
            }
            delay(4000)
        }
    }

    // ------------------------------------------------------------------------------------ AI

    private suspend fun candidateModels(): List<String> {
        var ranked: List<String> = emptyList()
        try {
            ranked = loadModels().map { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Could not load the free model list, using built-in defaults.")
        }
        if (ranked.isEmpty()) ranked = OpenRouter.FALLBACK
        val chosen = store.model
        val list = if (chosen.isNotBlank()) listOf(chosen) + ranked.filter { it != chosen } else ranked
        return list.take(4)
    }

    private suspend fun aiFix(errors: String?): Boolean {
        val key = store.openRouterKey
        if (key.isBlank()) {
            toast("Add your OpenRouter key in Settings first")
            log("No OpenRouter key. Open Settings and add a free key.")
            return false
        }
        val before = codeInput.text.toString()
        val project = ProjectBuilder.parse(before)
        if (project.files.isEmpty()) {
            log("Nothing to fix. Paste some code first.")
            return false
        }
        val bundle = project.toBundle()
        val router = OpenRouter(key)

        for (model in candidateModels()) {
            setStatus("AI is rewriting the code... ($model)")
            try {
                val reply = io { router.chat(model, AiPrompts.SYSTEM, AiPrompts.user(bundle, errors)) }
                val fixed = ProjectBuilder.parse(reply, ignorePreamble = true)
                if (fixed.files.isEmpty()) {
                    log("$model: the reply had no code, trying the next model.")
                    continue
                }
                previousCode = before
                codeInput.setText(ProjectBuilder.merge(project, fixed))
                saveFields()
                log("AI ($model) changed ${fixed.files.size} file(s). Tap 'Undo AI change' to go back.")
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("$model failed: ${e.message}")
            }
        }
        return false
    }

    private fun onFixClicked() {
        if (job?.isActive == true) return
        saveFields()
        job = lifecycleScope.launch {
            setBusy(true)
            try {
                if (aiFix(lastErrors)) {
                    setStatus("Code updated by the AI. Check it, then tap Build APK.")
                } else {
                    setStatus("The AI could not change the code.")
                }
            } catch (e: CancellationException) {
                setStatus("Stopped.")
            } catch (e: Exception) {
                log("Error: ${e.message}")
                setStatus("Failed: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun undoAi() {
        val prev = previousCode ?: return
        codeInput.setText(prev)
        previousCode = null
        undoBtn.isEnabled = false
        saveFields()
        toast("Restored the code from before the AI change")
    }

    // --------------------------------------------------------------------------------- install

    private fun saveApkToDownloads(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Android could not create the Downloads file.")
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Could not open the Downloads file.")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
                toast("APK saved to Downloads/" + file.name)
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw IllegalStateException("Downloads folder is unavailable.")
                dir.mkdirs()
                val target = File(dir, file.name)
                file.copyTo(target, overwrite = true)
                toast("APK saved to " + target.absolutePath)
            }
        } catch (e: Exception) {
            log("Download error: " + e.message)
            toast("Could not save APK: " + e.message)
        }
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow 'Install unknown apps' for APK Forge, come back and tap Install APK")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(intent)
    }
}
