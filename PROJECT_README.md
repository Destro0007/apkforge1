# APK Forge

Android app: paste Android code (Kotlin/Java/XML) -> it builds a real, installable APK in a free
GitHub cloud build -> installs it. If the build fails, a free OpenRouter AI model rewrites the code.

## 1. Get APK Forge onto your phone (one time)

1. Make a free account at github.com and create a new repository (any name, e.g. `apkforge`).
2. On a computer: open the repository > "Add file" > "Upload files". Drag in EVERYTHING from this
   folder, including the hidden `.github` folder. Commit.
   (If `.github/workflows/build.yml` is missing, use "Add file" > "Create new file", type that path
   and paste the content from this folder.)
3. Open the "Actions" tab. The build "Build APK Forge" starts by itself (5-8 min).
4. Open the finished run, download the artifact `APKForge-apk` (a zip), unzip it, send
   `app-debug.apk` to your phone and install it (allow "Install unknown apps").

## 2. Set up APK Forge (one time)

Open the app > Settings:

- GitHub token: github.com/settings/tokens > "Generate new token (classic)" > tick `repo` and
  `workflow`. Paste it in the app. The app creates a private repo (`apkforge-builds`) by itself.
- OpenRouter key (free): openrouter.ai/keys. Then open openrouter.ai/settings/privacy and allow
  free models, otherwise some free models answer "no endpoints".
- AI model: leave on Auto. The app only lists models whose price is 0.

## 3. Use it

1. Tap "Copy ChatGPT prompt", paste it into ChatGPT and describe your app.
2. Copy ChatGPT's answer, tap Paste. Answer in separate boxes? Copy one box, tap "Add file", type
   the file name (e.g. `MainActivity.kt`, `activity_main.xml`), repeat.
3. Tap Build APK. Wait 4-8 minutes. The installer opens by itself.
4. Build failed? With the checkbox on, the AI fixes the code and rebuilds (max 3 tries). You can
   also tap "Fix with AI" any time, and "Undo AI change" to go back.

## Code format

Several files: put a line `### FILE: path` before each one. Paths are relative to `app/src/main/`,
e.g. `java/com/example/app/MainActivity.kt`, `res/layout/activity_main.xml`. A bare file name like
`MainActivity.kt` or `activity_main.xml` is placed correctly automatically. One pasted file with no
marker works too. Extra libraries: a file named `dependencies.gradle` with lines such as
`implementation("group:artifact:version")`.

Ready in every built app: AndroidX, Material3, ConstraintLayout, RecyclerView, Jetpack Compose,
OkHttp, Gson, coroutines, view binding. minSdk 24, compileSdk 34.

## Good to know

- Apps are signed with one key kept in your private build repo, so a new build updates the
  installed app instead of failing with "App not installed".
- The token and key are stored privately on your phone only.
- Free OpenRouter models are rate limited and may log prompts. Your code and the build errors are
  sent to the model, so do not paste passwords or secret keys in your code.
- Updating APK Forge itself later: uninstall the old one first (it uses a different key).
