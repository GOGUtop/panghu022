package com.xixijiuguan.mysillytavernxixi

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextPaint
import android.util.Base64
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.tencent.smtt.export.external.interfaces.JsResult
import com.tencent.smtt.export.external.interfaces.WebResourceError
import com.tencent.smtt.export.external.interfaces.WebResourceRequest
import com.tencent.smtt.export.external.interfaces.WebResourceResponse
import com.tencent.smtt.sdk.ValueCallback
import com.tencent.smtt.sdk.WebChromeClient
import com.tencent.smtt.sdk.WebSettings
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var rootContainer: FrameLayout
    private var splashOverlay: View? = null

    data class Portal(val emoji: String, val name: String, val url: String, val desc: String, val gradient: String)
    private val portals = listOf(
        Portal("✈️", "小狗阿里云1号狗洞", "http://aaa.xixisillytavern.top:8000/", "小狗阿里云 8000 入口", "#0B2545,#D8C08D"),
        Portal("☁️", "小狗阿里云2号狗洞", "http://aaa.xixisillytavern.top:8443/", "小狗阿里云 8443 入口", "#1E3A5F,#F2EFE8"),
        Portal("🏰", "小狗西西画家狗洞", "http://aaa.xn--pss82d789a4qsa.top:8888/", "西西大画家 8888 入口", "#6F7F8D,#D8C08D"),
        Portal("🚇", "小狗隧道4号狗洞", "http://8.129.24.112:5705/", "小狗隧道入口", "#07111F,#7E8EA1")
    )

    companion object {
        var isAppInForeground = false
        var isAppInPip = false
        // 默认入口。用户选择狗洞后，这里会自动更新成当前正在使用的地址。
        var CURRENT_URL = "http://aaa.xixisillytavern.top:8000/"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastErrorTime = 0L
    private var lastFailureTime = 0L
    private lateinit var sharedPrefs: SharedPreferences
    private var isSoundEnabled = true

    private var isRetryingFlag = false
    private var healthCheckRunnable: Runnable? = null
    private var serverAwakeNotified = false

    private var isCapturing = false
    private val capturedBitmaps = mutableListOf<Bitmap>()
    private var shotStartY: Int = -1

    private var translator: Translator? = null
    private var translatorReady = false

    private val webCacheDir: File by lazy {
        val ext = externalCacheDir
        val dir = if (ext != null) File(ext, "web_cache_v1") else File(cacheDir, "web_cache_v1")
        if (!dir.exists()) dir.mkdirs()
        dir
    }
    private val webCacheMaxBytes = 200L * 1024 * 1024
    @Volatile private var cacheHitCount = 0
    @Volatile private var cacheMissCount = 0
    @Volatile private var firstLoadNotified = false

    // 键盘适配
    private var keyboardListenerAttached = false
    private var lastKeyboardHeight = 0
    private var keyboardWasOpen = false
    private var keyboardCloseFixRunnable: Runnable? = null
    private var keyboardCloseAnimator: ValueAnimator? = null
    private var lastImeVisibleState = false
    private var lastAppliedBottomPadding = 0
    private var lastSafeTopPadding = 0
    private var lastSafeBottomPadding = 0

    private fun getCachePathForDisplay(): String =
        try { webCacheDir.absolutePath } catch (_: Exception) { "未知路径" }

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(this, FloatingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            } catch (_: Exception) {}
        } else Toast.makeText(this, "呜呜，需要悬浮窗权限才能显示悬浮球汪！", Toast.LENGTH_SHORT).show()
    }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = filePathCallback
        filePathCallback = null

        if (callback == null) return@registerForActivityResult

        if (result.resultCode == RESULT_OK) {
            callback.onReceiveValue(parseFileChooserResult(result.resultCode, result.data))
        } else {
            callback.onReceiveValue(null)
        }
    }

    private val novelFileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) readNovelFileAndShow(uri)
            else Toast.makeText(this, "没有选择聊天记录文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseFileChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null

        val picked = mutableListOf<Uri>()

        try {
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i)?.uri?.let { picked.add(it) }
                }
            }
        } catch (_: Exception) {}

        try {
            data.data?.let { picked.add(it) }
        } catch (_: Exception) {}

        val unique = picked.distinct()
        return if (unique.isNotEmpty()) unique.toTypedArray()
        else WebChromeClient.FileChooserParams.parseResult(resultCode, data)
    }

    private fun buildBetterFileChooserIntent(fileChooserParams: WebChromeClient.FileChooserParams?): Intent {
        val allowMultiple = fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val openDocumentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "*/*",
                    "application/json",
                    "application/jsonl",
                    "application/x-jsonlines",
                    "application/octet-stream",
                    "text/plain",
                    "text/json",
                    "text/x-json",
                    "text/x-jsonl",
                    "text/markdown",
                    "image/png",
                    "image/jpeg",
                    "image/webp"
                )
            )
        }

        val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
        }

        return Intent.createChooser(openDocumentIntent, "🐶 选择酒馆文件 / JSONL 聊天记录").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(getContentIntent))
        }
    }

    private data class NovelMsg(val name: String, val text: String, val isUser: Boolean?)

    private fun buildNovelFileChooserIntent(): Intent {
        // 小说化导入专用文件选择器：不要只限制 text/plain，否则很多手机会隐藏 .json / .jsonl。
        val mimeTypes = arrayOf(
            "*/*",
            "application/json",
            "application/jsonl",
            "application/x-jsonlines",
            "application/octet-stream",
            "text/plain",
            "text/json",
            "text/x-json",
            "text/x-jsonl"
        )

        val openDocumentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }

        val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }

        return Intent.createChooser(openDocumentIntent, "📖 选择 .jsonl / .json / .txt 聊天记录").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(getContentIntent))
        }
    }


    private var embeddedNovelReaderOverlay: FrameLayout? = null

    private fun openNovelFileImporter() {
        showEmbeddedNovelReader()
    }

    inner class AndroidNovelBridge {
        @JavascriptInterface
        fun closeReader() {
            mainHandler.post { closeEmbeddedNovelReader() }
        }

        @JavascriptInterface
        fun toast(msg: String) {
            mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun closeEmbeddedNovelReader() {
        try {
            embeddedNovelReaderOverlay?.let { rootContainer.removeView(it) }
        } catch (_: Exception) {}
        embeddedNovelReaderOverlay = null
    }

    private fun showEmbeddedNovelReader() {
        try { closeEmbeddedNovelReader() } catch (_: Exception) {}

        val ctx = this
        val overlay = FrameLayout(ctx).apply {
            tag = "embedded_novel_reader"
            setBackgroundColor(Color.parseColor("#FF111827"))
            isClickable = true
            isFocusable = true
        }
        embeddedNovelReaderOverlay = overlay

        val reader = WebView(ctx)
        try {
            reader.settings.javaScriptEnabled = true
            reader.settings.domStorageEnabled = true
            reader.settings.allowFileAccess = true
            reader.settings.allowContentAccess = true
            reader.settings.cacheMode = WebSettings.LOAD_DEFAULT
            reader.settings.useWideViewPort = true
            reader.settings.loadWithOverviewMode = true
            reader.settings.textZoom = 100
        } catch (_: Exception) {}

        reader.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView?, filePath: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = filePath
                return try {
                    fileChooserLauncher.launch(buildNovelFileChooserIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    Toast.makeText(applicationContext, "❌ 打开文件选择器失败：${e.message}", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }

        try { reader.addJavascriptInterface(AndroidNovelBridge(), "AndroidNovelBridge") } catch (_: Exception) {}

        overlay.addView(reader, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        rootContainer.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val html = readEmbeddedNovelReaderHtml()
        reader.loadDataWithBaseURL("https://st-reader.local/", html, "text/html", "UTF-8", null)
        Toast.makeText(this, "📖 已打开小狗小说化阅读器", Toast.LENGTH_SHORT).show()
    }

    private fun readEmbeddedNovelReaderHtml(): String {
        return try {
            assets.open("st_novel_reader.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            """
            <!doctype html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
            body{font-family:sans-serif;background:#111827;color:#fff;padding:24px;line-height:1.7}
            button{font-size:16px;padding:10px 14px;border:0;border-radius:10px;background:#667eea;color:#fff}
            </style></head>
            <body>
            <h2>📖 小狗小说化阅读器资源缺失</h2>
            <p>没有找到 <b>app/src/main/assets/st_novel_reader.html</b>。</p>
            <p>请把压缩包里的 <b>st_novel_reader.html</b> 放到项目的 <b>app/src/main/assets/</b> 目录。</p>
            <button onclick="AndroidNovelBridge.closeReader()">关闭</button>
            </body></html>
            """.trimIndent()
        }
    }


    private fun readNovelFileAndShow(uri: Uri) {
        Toast.makeText(this, "📖 正在读取聊天记录...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val raw = readTextFromUriSmart(uri)
                val fileName = getDisplayNameFromUri(uri).ifBlank { "聊天记录" }
                val messages = parseNovelMessages(raw)
                val novel = buildContinuousNovel(messages)
                runOnUiThread {
                    if (novel.isBlank()) {
                        Toast.makeText(
                            this,
                            "没有解析到可小说化的内容。请确认文件是 SillyTavern 导出的 .jsonl / .json / .txt 聊天记录。",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        showNativeNovelReader(fileName, novel)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun readTextFromUriSmart(uri: Uri): String {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        if (bytes.isEmpty()) return ""

        val utf8 = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { "" }
        // 如果 UTF-8 解出来大量乱码，再兜底用 GBK。大部分 SillyTavern 导出都是 UTF-8。
        val badCount = utf8.count { it == '\uFFFD' }
        return if (badCount > 3) {
            try { String(bytes, java.nio.charset.Charset.forName("GBK")) } catch (_: Exception) { utf8 }
        } else utf8
    }

    private fun getDisplayNameFromUri(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            } ?: ""
        } catch (_: Exception) { "" }
    }

    private fun parseNovelMessages(raw: String): List<NovelMsg> {
        val text = raw.trim().removePrefix("\uFEFF").trim()
        if (text.isBlank()) return emptyList()

        val out = mutableListOf<NovelMsg>()
        val contentKeys = listOf(
            "mes", "message", "text", "content", "value", "reply", "response", "prompt",
            "description", "scenario", "first_mes", "alternate_greeting"
        )
        val arrayKeys = listOf(
            "chat", "messages", "data", "history", "items", "entries", "log", "swipes",
            "alternate_greetings", "greetings"
        )

        fun jsonValueToText(value: Any?): String {
            return when (value) {
                null, JSONObject.NULL -> ""
                is String -> value
                is Number, is Boolean -> value.toString()
                is JSONArray -> {
                    val parts = mutableListOf<String>()
                    for (i in 0 until value.length()) {
                        val t = jsonValueToText(value.opt(i)).trim()
                        if (t.isNotBlank()) parts.add(t)
                    }
                    parts.joinToString("\n")
                }
                is JSONObject -> {
                    for (k in contentKeys) {
                        if (value.has(k) && !value.isNull(k)) {
                            val t = jsonValueToText(value.opt(k)).trim()
                            if (t.isNotBlank()) return t
                        }
                    }
                    ""
                }
                else -> value.toString()
            }
        }

        fun guessName(obj: JSONObject): String {
            val keys = listOf("name", "role", "speaker", "user_name", "character_name", "sender", "author")
            for (k in keys) {
                if (obj.has(k) && !obj.isNull(k)) {
                    val v = obj.optString(k, "").trim()
                    if (v.isNotBlank()) return v
                }
            }
            return ""
        }

        fun guessIsUser(obj: JSONObject): Boolean? {
            return when {
                obj.has("is_user") -> obj.optBoolean("is_user")
                obj.has("isUser") -> obj.optBoolean("isUser")
                obj.optString("role", "").equals("user", true) -> true
                obj.optString("role", "").equals("assistant", true) -> false
                obj.optString("role", "").equals("system", true) -> null
                obj.optString("name", "").equals("user", true) -> true
                else -> null
            }
        }

        fun addMessage(content: String, name: String = "", isUser: Boolean? = null) {
            val cleaned = cleanNovelMessage(content)
            if (cleaned.isNotBlank()) out.add(NovelMsg(name, cleaned, isUser))
        }

        fun addFromObject(obj: JSONObject): Boolean {
            for (k in contentKeys) {
                if (obj.has(k) && !obj.isNull(k)) {
                    val content = jsonValueToText(obj.opt(k))
                    if (content.isNotBlank()) {
                        addMessage(content, guessName(obj), guessIsUser(obj))
                        return true
                    }
                }
            }
            return false
        }

        fun walkValue(value: Any?) {
            when (value) {
                null, JSONObject.NULL -> Unit
                is JSONArray -> {
                    for (i in 0 until value.length()) walkValue(value.opt(i))
                }
                is JSONObject -> {
                    val added = addFromObject(value)

                    // 即使当前对象已经像一条消息，也继续扫已知数组字段；有些导出会在对象里嵌套 chat/messages。
                    for (k in arrayKeys) {
                        if (value.has(k) && !value.isNull(k)) walkValue(value.opt(k))
                    }

                    // 如果当前对象不是消息，再递归扫所有子对象，兼容各种 .json 包装格式。
                    if (!added) {
                        val keys = value.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (k !in arrayKeys && k !in contentKeys) walkValue(value.opt(k))
                        }
                    }
                }
                is String -> addMessage(value)
                else -> addMessage(value.toString())
            }
        }

        fun parseJsonLine(line: String): Boolean {
            var l = line.trim().trimEnd(',')
            if (l.startsWith("data:", true)) l = l.substringAfter(":").trim()
            if (l.isBlank()) return false
            return try {
                when {
                    l.startsWith("{") -> { walkValue(JSONObject(l)); true }
                    l.startsWith("[") -> { walkValue(JSONArray(l)); true }
                    else -> false
                }
            } catch (_: Exception) { false }
        }

        // 1) 优先按完整 JSON 解析：支持 .json 的 {chat:[]}/{messages:[]} 和数组格式。
        var parsedWhole = false
        try {
            when {
                text.startsWith("{") -> { walkValue(JSONObject(text)); parsedWhole = true }
                text.startsWith("[") -> { walkValue(JSONArray(text)); parsedWhole = true }
            }
        } catch (_: Exception) {
            parsedWhole = false
        }

        // 2) 如果完整 JSON 没解析成功，或者解析出来为空，再按 JSONL 一行一条解析。
        if (!parsedWhole || out.isEmpty()) {
            val before = out.size
            text.lineSequence().forEach { line ->
                val l = line.trim()
                if (l.isBlank()) return@forEach
                if (!parseJsonLine(l)) addMessage(l)
            }
            // 如果完整 JSON 解析出来了内容，就不要重复追加普通文本。
            if (parsedWhole && before > 0 && out.size > before) {
                return out.take(before)
            }
        }

        return out
    }

    private fun cleanNovelMessage(input: String): String {
        var s = stripHtml(input)
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\t", " ")
            .replace(Regex("(?is)<script.*?</script>"), "")
            .replace(Regex("(?is)<style.*?</style>"), "")
            .replace(Regex("(?is)```.*?```"), "")
            .replace(Regex("(?is)<!--.*?-->"), "")
            .replace(Regex("(?i)End of The ECoT.*"), "")
            .replace(Regex("""(?i)navigator\.clipboard|document\.createElement|function\(|</body>|</script>"""), "")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        // 清掉明显的网页脚本、复制提示、控制符；保留正文里的 <日期>/<地点> 这类设定标签。
        s = s.lines().filter { line ->
            val t = line.trim()
            t.isNotBlank() &&
                    !t.startsWith("{{") &&
                    !t.startsWith("/sys") &&
                    !t.contains("<script", true) &&
                    !t.contains("已复制", true) &&
                    !t.contains("clipboard", true) &&
                    !t.contains("createElement", true)
        }.joinToString("\n").trim()
        return s
    }

    private fun buildContinuousNovel(messages: List<NovelMsg>): String {
        if (messages.isEmpty()) return ""
        val pieces = mutableListOf<String>()
        var last = ""
        for (m in messages) {
            val text = m.text.trim()
            if (text.isBlank()) continue
            if (text == last) continue
            last = text

            val normalized = text
                .replace(Regex("\n{2,}"), "\n\n")
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .trim()
            if (normalized.isBlank()) continue
            pieces.add(normalized)
        }
        return pieces.joinToString("\n\n")
    }

    private fun showNativeNovelReader(sourceName: String, novelText: String) {
        try { rootContainer.findViewWithTag<View>("native_novel_reader")?.let { rootContainer.removeView(it) } } catch (_: Exception) {}
        val ctx = this
        val overlay = FrameLayout(ctx).apply {
            tag = "native_novel_reader"
            setBackgroundColor(Color.parseColor("#EEF3F4F6"))
            isClickable = true
            isFocusable = true
        }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        }

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(4f), dp(6f), dp(10f))
        }
        val title = TextView(ctx).apply {
            text = "📖 小说化预览"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#2D3748"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        top.addView(title)

        fun smallBtn(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#667EEA"))
            }
            setOnClickListener { onClick() }
        }
        top.addView(smallBtn("导入", { openNovelFileImporter() }).apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6f) })
        top.addView(smallBtn("复制", { copyTextToClipboard("小说化文本", novelText) }).apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6f) })
        top.addView(smallBtn("导出", { saveNovelTextToDownloads(novelText) }).apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6f) })
        top.addView(smallBtn("关闭", { try { rootContainer.removeView(overlay) } catch (_: Exception) {} }).apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6f) })
        panel.addView(top)

        panel.addView(TextView(ctx).apply {
            text = "来源：$sourceName"
            textSize = 12f
            setTextColor(Color.parseColor("#718096"))
            setPadding(dp(6f), 0, dp(6f), dp(8f))
        })

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        val content = TextView(ctx).apply {
            text = novelText
            textSize = 16f
            setTextColor(Color.parseColor("#2D3748"))
            setLineSpacing(dp(8f).toFloat(), 1.15f)
            setPadding(dp(18f), dp(18f), dp(18f), dp(26f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#FFFFFFFF"))
                setStroke(dp(1f), Color.parseColor("#D8E0E6"))
            }
            setTextIsSelectable(true)
        }
        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        overlay.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootContainer.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun copyTextToClipboard(label: String, text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            Toast.makeText(this, "已复制小说文本", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "复制失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveNovelTextToDownloads(text: String) {
        Thread {
            try {
                val fileName = "酒馆小说化_${System.currentTimeMillis()}.txt"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("无法创建下载文件")
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    File(dir, fileName).writeText(text, Charsets.UTF_8)
                }
                runOnUiThread { Toast.makeText(this, "已导出到下载目录", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPrefs = getSharedPreferences("SillyTavernPrefs", Context.MODE_PRIVATE)
        isSoundEnabled = sharedPrefs.getBoolean("sound_enabled", true)
        setContentView(R.layout.activity_main)
        rootContainer = findViewById(R.id.root_container)

        applyDisplayMode("normal")

        webView = findViewById(R.id.x5_webview)
        initWebViewSettings()
        setupWebChromeClient()
        setupDownloadListener()
        webView.addJavascriptInterface(AndroidDownloader(), "AndroidDownloader")
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        showStartupCacheRefreshThenPortal()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (splashOverlay != null) { moveTaskToBack(true); return }
                if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
            }
        })
        checkOverlayPermissionAndStartService()
        checkPiPIntent(intent)
        initTranslator()

        mainHandler.postDelayed({ cleanupCacheLRUAsync() }, 8000)
        showStartupCacheHint()
        requestNotificationPermission()
        requestBatteryWhitelist()
    }

    // =====================================================================
    // 🖥️ 固定普通模式：删除全屏 / 小米模式，只让 APP 正常模式运行
    // ✅ 这版专门修复：部分真机内容顶到状态栏、输入法弹出后输入框不被顶起
    // =====================================================================
    private fun applyDisplayMode(mode: String = "normal") {
        sharedPrefs.edit().putString("display_mode", "normal").apply()
        val win = window

        @Suppress("DEPRECATION")
        win.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )

        // 关键：关闭系统自动适配，改成我们自己按状态栏 / 导航栏 / 输入法高度精确留白。
        // 这样小米 / OPPO / 华为上不会出现“顶到状态栏”或“键盘不顶起输入框”。
        WindowCompat.setDecorFitsSystemWindows(win, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            win.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            win.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }

        try {
            win.statusBarColor = Color.parseColor("#121212")
            win.navigationBarColor = Color.parseColor("#121212")
        } catch (_: Exception) {}

        applySafeAreaInsets()
        setupKeyboardListener()
    }

    // ✅ 统一安全区适配：顶部永远避开状态栏；底部键盘收起时做“德芙丝滑”动画，不再突然下坠。
    private fun applySafeAreaInsets() {
        try {
            ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
                val systemBars = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
                )
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

                val topPadding = systemBars.top
                val normalBottomPadding = systemBars.bottom
                val targetBottomPadding = if (imeVisible) ime.bottom else normalBottomPadding

                lastSafeTopPadding = topPadding
                lastSafeBottomPadding = normalBottomPadding

                // 键盘收起瞬间，系统会立刻把 bottom 从键盘高度砍到导航栏高度，视觉上就会“咚”一下。
                // 这里拦住这个瞬间，自己做 70ms 奶油快滑缓动，让输入框快速顺滑归位。
                val closingIme = lastImeVisibleState && !imeVisible && lastAppliedBottomPadding > targetBottomPadding + dp(8f)

                keyboardCloseAnimator?.cancel()

                if (closingIme) {
                    collapseInjectedToolFolder()
                    val start = lastAppliedBottomPadding
                    val end = targetBottomPadding
                    keyboardCloseAnimator = ValueAnimator.ofInt(start, end).apply {
                        duration = 70L
                        interpolator = android.view.animation.DecelerateInterpolator(1.65f)
                        addUpdateListener { animator ->
                            val bottom = animator.animatedValue as Int
                            try {
                                view.setPadding(0, topPadding, 0, bottom)
                                lastAppliedBottomPadding = bottom
                            } catch (_: Exception) {}
                        }
                        doOnEndCompat {
                            try {
                                view.setPadding(0, topPadding, 0, end)
                                lastAppliedBottomPadding = end
                            } catch (_: Exception) {}
                            smoothFixAfterKeyboardClosed()
                        }
                        start()
                    }
                } else {
                    try {
                        view.setPadding(0, topPadding, 0, targetBottomPadding)
                        lastAppliedBottomPadding = targetBottomPadding
                    } catch (_: Exception) {}

                    if (imeVisible) {
                        collapseInjectedToolFolder()
                        keyboardCloseFixRunnable?.let { mainHandler.removeCallbacks(it) }
                        mainHandler.postDelayed({ scrollFocusedInputIntoView() }, 24)
                        mainHandler.postDelayed({ scrollFocusedInputIntoView() }, 72)
                    }
                }

                lastImeVisibleState = imeVisible
                insets
            }
            ViewCompat.requestApplyInsets(rootContainer)
        } catch (_: Exception) {
            try { rootContainer.setPadding(0, getStatusBarHeight(), 0, getNavBarHeight()) } catch (_: Exception) {}
        }
    }

    private fun ValueAnimator.doOnEndCompat(action: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) { action() }
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
    }

    // ✅ 键盘监听：只做防抖和网页 resize，不再硬砍 padding。
    private fun setupKeyboardListener() {
        if (keyboardListenerAttached) return
        keyboardListenerAttached = true

        val rootView = rootContainer
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            try {
                val rect = Rect()
                rootView.getWindowVisibleDisplayFrame(rect)
                val screenHeight = rootView.rootView.height
                val keyboardHeight = screenHeight - rect.bottom
                val nowOpen = keyboardHeight > screenHeight * 0.15

                if (Math.abs(keyboardHeight - lastKeyboardHeight) < 10 && nowOpen == keyboardWasOpen) return@addOnGlobalLayoutListener
                lastKeyboardHeight = keyboardHeight

                if (nowOpen) {
                    keyboardWasOpen = true
                    collapseInjectedToolFolder()
                    keyboardCloseFixRunnable?.let { mainHandler.removeCallbacks(it) }
                    mainHandler.postDelayed({ scrollFocusedInputIntoView() }, 24)
                    mainHandler.postDelayed({ scrollFocusedInputIntoView() }, 72)
                } else if (keyboardWasOpen) {
                    keyboardWasOpen = false
                    collapseInjectedToolFolder()
                    smoothFixAfterKeyboardClosed()
                }
            } catch (_: Exception) {}
        }
    }

    private fun scrollFocusedInputIntoView() {
        try {
            if (!::webView.isInitialized) return
            webView.evaluateJavascript(
                """
                (function(){
                    var el = document.activeElement;
                    if(!el) return;
                    var editable = (el.tagName==='TEXTAREA' || el.tagName==='INPUT' || el.isContentEditable);
                    if(!editable) return;
                    try{
                        el.scrollIntoView({block:'center', inline:'nearest', behavior:'auto'});
                    }catch(e){
                        try{ el.scrollIntoView(false); }catch(_e){}
                    }
                    try{
                        window.dispatchEvent(new Event('resize'));
                    }catch(e){}
                })();
                """.trimIndent(),
                null
            )
        } catch (_: Exception) {}
    }

    private fun smoothFixAfterKeyboardClosed() {
        keyboardCloseFixRunnable?.let { mainHandler.removeCallbacks(it) }

        keyboardCloseFixRunnable = Runnable {
            try {
                if (!::webView.isInitialized) return@Runnable
                webView.evaluateJavascript(
                    """
                    (function(){
                        try {
                            window.dispatchEvent(new Event('resize'));

                            // 奶油快滑：比插件多一点点动画，但不拖泥带水。
                            var runFix = function(){
                                try {
                                    window.dispatchEvent(new Event('resize'));

                                    var active = document.activeElement;
                                    if (active && (active.tagName === 'TEXTAREA' || active.tagName === 'INPUT' || active.isContentEditable)) {
                                        try { active.blur(); } catch(e) {}
                                    }

                                    var inputBar =
                                        document.querySelector('#send_form') ||
                                        document.querySelector('.send_form') ||
                                        document.querySelector('#form_sheld') ||
                                        document.querySelector('.form_sheld') ||
                                        document.querySelector('#send_textarea') ||
                                        document.querySelector('textarea');

                                    if (inputBar && inputBar.scrollIntoView) {
                                        try { inputBar.scrollIntoView({block:'nearest', inline:'nearest', behavior:'auto'}); } catch(e) {}
                                    }

                                    var scroller =
                                        document.querySelector('#chat') ||
                                        document.querySelector('#chat_container') ||
                                        document.querySelector('.chat-container') ||
                                        document.scrollingElement ||
                                        document.documentElement;

                                    if (scroller) {
                                        try { scroller.scrollTop = scroller.scrollHeight; } catch(e) {}
                                    }
                                } catch(e) {}
                            };

                            runFix();
                            try { requestAnimationFrame(function(){ runFix(); }); } catch(e) {}
                            setTimeout(runFix, 28);
                            setTimeout(runFix, 64);
                        } catch(e) {}
                    })();
                    """.trimIndent(),
                    null
                )
            } catch (_: Exception) {}
        }

        mainHandler.postDelayed(keyboardCloseFixRunnable!!, 0)
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(24f)
    }
    private fun getNavBarHeight(): Int {
        return try {
            val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        } catch (_: Exception) { 0 }
    }

    // =====================================================================
    // 🧹 启动缓存刷新：每次打开 APP 都先清理旧缓存，并提示即将写入新缓存
    // =====================================================================
    private fun showStartupCacheRefreshThenPortal() {
        val ctx = this

        val dialogView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(24f), dp(24f), dp(20f))
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                colors = intArrayOf(
                    Color.parseColor("#1a1a2e"),
                    Color.parseColor("#16213e"),
                    Color.parseColor("#0f3460")
                )
                orientation = GradientDrawable.Orientation.TL_BR
            }
        }

        dialogView.addView(TextView(ctx).apply {
            text = "🧹🚀"
            textSize = 40f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6f))
        })

        dialogView.addView(TextView(ctx).apply {
            text = "正在刷新酒馆缓存"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        dialogView.addView(TextView(ctx).apply {
            text = "小狗会先清理旧缓存，再写入新的缓存文件，让这次启动更干净汪～"
            textSize = 13f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(8f), 0, dp(18f))
        })

        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 10
        }
        dialogView.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(8f)
        ))

        val statusText = TextView(ctx).apply {
            text = "正在清除旧网页缓存..."
            textSize = 12f
            setTextColor(Color.parseColor("#99FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, 0)
        }
        dialogView.addView(statusText)

        val dialog = AlertDialog.Builder(ctx).setView(dialogView).create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            try {
                dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
                    cornerRadius = dp(24f).toFloat()
                    setColor(Color.TRANSPARENT)
                })
            } catch (_: Exception) {}
        }
        dialog.show()

        ValueAnimator.ofInt(10, 85).apply {
            duration = 900
            addUpdateListener { progressBar.progress = it.animatedValue as Int }
            start()
        }

        // WebView 相关清理放在主线程，避免部分机型报错。
        try { webView.stopLoading() } catch (_: Exception) {}
        try { webView.clearCache(true) } catch (_: Exception) {}
        try { webView.clearHistory() } catch (_: Exception) {}
        try { webView.clearFormData() } catch (_: Exception) {}

        Thread {
            var deletedCount = 0

            try { deletedCount += clearAllWebCache() } catch (_: Exception) {}

            // 只清缓存，不清 Cookie / WebStorage，避免每次启动都把登录状态或酒馆数据删掉。
            try { cacheHitCount = 0 } catch (_: Exception) {}
            try { cacheMissCount = 0 } catch (_: Exception) {}
            try { firstLoadNotified = false } catch (_: Exception) {}
            try {
                sharedPrefs.edit()
                    .putLong("startup_cache_refresh_time_v1", System.currentTimeMillis())
                    .putBoolean("first_load_done_v1", false)
                    .apply()
            } catch (_: Exception) {}

            runOnUiThread {
                try {
                    statusText.text = "旧缓存已清理 $deletedCount 个文件，接下来会重新写入新缓存"
                    ValueAnimator.ofInt(progressBar.progress, 100).apply {
                        duration = 260
                        addUpdateListener { progressBar.progress = it.animatedValue as Int }
                        start()
                    }
                } catch (_: Exception) {}

                mainHandler.postDelayed({
                    try { dialog.dismiss() } catch (_: Exception) {}
                    showStartupAnnouncementThenPortal()
                }, 420)
            }
        }.apply { isDaemon = true }.start()
    }

    // =====================================================================
    // 📢 启动公告：打开 APP 后先显示功能说明，再进入入口选择
    // =====================================================================
    private fun showStartupAnnouncementThenPortal() {
        val skipKey = "startup_announcement_skip_v1"

        // 用户点过“以后不再显示”后，直接进入狗洞选择页
        if (sharedPrefs.getBoolean(skipKey, false)) {
            showPortalSelector()
            return
        }

        buildPrettyDialog(
            "📢",
            "小狗酒馆公告",
            "欢迎回来汪～进入酒馆前先看看功能说明",
            listOf(
                Triple(
                    "🚪 狗洞入口",
                    "7BB7FF",
                    "启动后可以选择不同酒馆入口。选错了也没事，进入后点右侧 🐾 控制面板里的【换狗洞】即可重新选择。"
                ),
                Triple(
                    "🐾 悬浮球控制",
                    "38EF7D",
                    "开启悬浮窗权限后，可以用小狗悬浮球快速打开酒馆、回到桌面、进入画中画小窗。通知栏也有备用控制按钮。"
                ),
                Triple(
                    "🚀 小狗加速器",
                    "FFCB7B",
                    "每次启动都会先清理旧缓存，再在进入酒馆时写入新的静态资源缓存。你也可以在控制面板里查看缓存状态或手动清空缓存。"
                ),
                Triple(
                    "📸 长截图 / 角色卡",
                    "C77BFF",
                    "右侧 🐾 控制面板支持长截图、生成卡片、保存图片等辅助功能，方便整理聊天内容。"
                ),
                Triple(
                    "🔔 回复提醒",
                    "FFB86B",
                    "AI 回复完成、空回复、截断或报错时，会通过提示音、Toast 或悬浮提示告诉你。"
                ),
                Triple(
                    "💣 重置酒馆",
                    "FF7B9C",
                    "重置会清空缓存、Cookie、网页存储和部分本地设置，然后自动重启 APP，效果接近重新安装。"
                )
            ),
            "进入酒馆",
            { showPortalSelector() },
            "以后不再显示",
            {
                sharedPrefs.edit().putBoolean(skipKey, true).apply()
                showPortalSelector()
            },
            "关闭",
            { showPortalSelector() }
        ).show()
    }

    // =====================================================================
    // 🚪 入口选择面板
    // =====================================================================
    private fun showPortalSelector() {
        val ctx = this
        val overlay = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                colors = intArrayOf(
                    Color.parseColor("#07111F"),
                    Color.parseColor("#102A43"),
                    Color.parseColor("#D8C08D")
                )
                orientation = GradientDrawable.Orientation.TL_BR
            }
            isClickable = true
            isFocusable = true
        }

        // 🖼️ 狗洞选择页壁纸：放在 app/src/main/assets/portal_bg.png
        val wallpaperView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.96f
            try {
                assets.open("portal_bg.png").use { input ->
                    setImageBitmap(BitmapFactory.decodeStream(input))
                }
            } catch (_: Exception) {
                setBackgroundColor(Color.parseColor("#07111F"))
            }
        }
        overlay.addView(
            wallpaperView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 🌫️ 玻璃暗层，保证按钮和文字在亮壁纸上也清楚
        overlay.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                colors = intArrayOf(
                    Color.parseColor("#AA07111F"),
                    Color.parseColor("#551E3A5F"),
                    Color.parseColor("#BB07111F")
                )
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 🌊 大幅度动态壁纸：轻微放大后做云洞漂移，不影响点击
        wallpaperView.post {
            try {
                wallpaperView.scaleX = 1.22f
                wallpaperView.scaleY = 1.22f
                ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 13000L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    interpolator = LinearInterpolator()
                    addUpdateListener { anim ->
                        if (wallpaperView.parent == null) {
                            try { cancel() } catch (_: Exception) {}
                            return@addUpdateListener
                        }
                        val v = (anim.animatedValue as Float).toDouble()
                        wallpaperView.translationX = (Math.sin(Math.toRadians(v)) * dp(34f)).toFloat()
                        wallpaperView.translationY = (Math.cos(Math.toRadians(v * 0.78)) * dp(48f)).toFloat()
                        wallpaperView.rotation = (Math.sin(Math.toRadians(v * 0.62)) * 1.4f).toFloat()
                    }
                    start()
                }
            } catch (_: Exception) {}
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(46f), dp(24f), dp(36f))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        container.addView(TextView(ctx).apply {
            text = "🦴"
            textSize = 58f
            gravity = Gravity.CENTER
            setShadowLayer(dp(10f).toFloat(), 0f, dp(4f).toFloat(), Color.parseColor("#9907111F"))
            setPadding(0, 0, 0, dp(4f))
        })

        container.addView(TextView(ctx).apply {
            text = "狗骨酒馆"
            textSize = 34f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#FFF4D6"))
            gravity = Gravity.CENTER
            setShadowLayer(dp(8f).toFloat(), 0f, dp(3f).toFloat(), Color.parseColor("#CC000000"))
        })

        container.addView(TextView(ctx).apply {
            text = "选一个云洞钻进去汪～"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E8EDF4"))
            gravity = Gravity.CENTER
            setShadowLayer(dp(5f).toFloat(), 0f, dp(2f).toFloat(), Color.parseColor("#AA000000"))
            setPadding(0, dp(8f), 0, dp(30f))
        })

        fun glassCardBackground(gradient: String, selected: Boolean = false): GradientDrawable {
            val colors = gradient.split(",")
            return GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColors(intArrayOf(Color.parseColor(colors[0]), Color.parseColor(colors[1])))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                alpha = 218
                setStroke(
                    if (selected) dp(2.2f) else dp(1.2f),
                    if (selected) Color.parseColor("#FFF4D6") else Color.parseColor("#88F2EFE8")
                )
            }
        }

        fun addPortalLikeCard(
            emoji: String,
            title: String,
            desc: String,
            gradient: String,
            selected: Boolean = false,
            onClick: () -> Unit
        ) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
                background = glassCardBackground(gradient, selected)
                elevation = dp(12f).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            card.addView(TextView(ctx).apply {
                text = emoji
                textSize = 34f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(58f), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(ctx).apply {
                text = title
                textSize = 17f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setShadowLayer(dp(3f).toFloat(), 0f, dp(1f).toFloat(), Color.parseColor("#AA000000"))
            })
            textCol.addView(TextView(ctx).apply {
                text = desc
                textSize = 11f
                setTextColor(Color.parseColor("#E6FFFFFF"))
                setPadding(0, dp(4f), 0, 0)
            })
            if (selected) {
                textCol.addView(TextView(ctx).apply {
                    text = "✦ 上次使用"
                    textSize = 10f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#FFF4D6"))
                    setPadding(0, dp(4f), 0, 0)
                })
            }
            card.addView(textCol)
            card.addView(TextView(ctx).apply {
                text = "›"
                textSize = 32f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#F7F2E8"))
                setPadding(dp(8f), 0, 0, 0)
            })

            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(14f)
            container.addView(card, lp)
        }

        val lastUsed = sharedPrefs.getInt("last_portal_idx", 0)
        portals.forEachIndexed { idx, portal ->
            addPortalLikeCard(
                portal.emoji,
                portal.name,
                portal.desc,
                portal.gradient,
                idx == lastUsed
            ) {
                sharedPrefs.edit().putInt("last_portal_idx", idx).apply()
                CURRENT_URL = portal.url
                showLoadingAnimation(portal)
            }
        }

        // 📱 假本地酒馆：下载酒馆本体 / 账号数据 / 本地 Node 启动 / 同步中心
        addPortalLikeCard(
            "📱",
            "假本地酒馆",
            "下载服务器酒馆和账号数据到手机，再用 APP 内置 Node 启动",
            "#0E2A47,#8FD3FE",
            false
        ) {
            showLocalTavernCenter()
        }

        // ☁️ 一键设置服务器同步接口
        addPortalLikeCard(
            "🔁",
            "同步中心",
            "配置下载地址、选择账号、上传本地数据回服务器",
            "#152238,#D8C08D",
            false
        ) {
            showLocalTavernCenter()
        }

        // 💣 入口页新增重置酒馆：使用同一套云洞金色风格
        addPortalLikeCard(
            "💣",
            "重置酒馆",
            "清空缓存、Cookie、网页数据并重新打开，效果接近重装",
            "#4A1F24,#D8C08D",
            false
        ) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("💣 重置狗骨酒馆？")
                .setMessage("这会清空缓存、Cookie 和网页数据，然后自动重新打开。\n\n确定要重置吗汪？")
                .setPositiveButton("确定重置") { _, _ -> simulateReinstall() }
                .setNegativeButton("取消", null)
                .show()
        }

        container.addView(TextView(ctx).apply {
            text = "💡 选错了也没事，进去后点 🐾 → 换狗洞 即可切换"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#DDE8EDF4"))
            gravity = Gravity.CENTER
            setShadowLayer(dp(4f).toFloat(), 0f, dp(1f).toFloat(), Color.parseColor("#BB000000"))
            setPadding(0, dp(10f), 0, 0)
        })

        scroll.addView(container)
        overlay.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootContainer.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        splashOverlay = overlay
    }

    // =====================================================================
    // 📱 狗骨酒馆：内置 Node 假本地模式 V2
    // =====================================================================
    @Volatile private var localNodeRunning = false
    @Volatile private var localNodeThread: Thread? = null
    private var realtimeSyncRunnable: Runnable? = null

    private data class LocalProgressDialogRefs(
        val dialog: AlertDialog,
        val progressBar: ProgressBar,
        val percentText: TextView,
        val detailText: TextView
    )

    private fun localTavernRoot(): File = File(filesDir, "local_tavern/SillyTavern")
    private fun localDataRoot(): File = File(localTavernRoot(), "data")
    private fun localDownloadDir(): File = File(filesDir, "local_tavern_downloads").apply { if (!exists()) mkdirs() }

    private fun syncBaseUrl(): String = sharedPrefs.getString("xixi_sync_base_url", "http://aaa.xixisillytavern.top:8000/xixi-sync")
        ?: "http://aaa.xixisillytavern.top:8000/xixi-sync"
    private fun syncToken(): String = sharedPrefs.getString("xixi_sync_token", "xixi") ?: "xixi"
    private fun selectedLocalAccount(): String = sharedPrefs.getString("xixi_selected_account", "") ?: ""

    private fun showLocalTavernCenter() {
        val ctx = this
        val root = localTavernRoot()
        val account = selectedLocalAccount().ifBlank { "未选择" }
        val nodeState = if (NodeMobileNative.isAvailable()) "Node 已接入" else "Node 未加载：${NodeMobileNative.lastError()}"
        val localState = if (File(root, "server.js").exists()) "本地酒馆已下载" else "本地酒馆未下载"

        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(18f), dp(18f), dp(14f))
        }
        fun titleText(t: String, size: Float = 18f): TextView = TextView(ctx).apply {
            text = t
            textSize = size
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#2D3748"))
            setPadding(0, dp(4f), 0, dp(8f))
        }
        fun infoText(t: String): TextView = TextView(ctx).apply {
            text = t
            textSize = 12f
            setTextColor(Color.parseColor("#4A5568"))
            setLineSpacing(0f, 1.25f)
            setPadding(0, 0, 0, dp(12f))
        }
        fun btn(t: String, color: String, action: () -> Unit): TextView = TextView(ctx).apply {
            text = t
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor(color))
            }
            setOnClickListener { action() }
        }
        fun addButton(t: String, color: String, action: () -> Unit) {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10f)
            box.addView(btn(t, color, action), lp)
        }

        box.addView(titleText("📱 狗骨假本地酒馆"))
        box.addView(infoText("状态：$localState\n账号：$account\nNode：$nodeState\n目录：${root.absolutePath}"))

        addButton("1️⃣ 下载 / 更新 SillyTavern 本体", "#0E7490") { downloadLocalTavernBundle() }
        addButton("2️⃣ 选择账号并下载账号数据", "#2563EB") { fetchAccountsAndChoose() }
        addButton("3️⃣ 启动 APP 内置 Node 酒馆", "#16A34A") { startEmbeddedNodeTavern() }
        addButton("🌐 打开本地酒馆 127.0.0.1:8000", "#7C3AED") { openLocalTavernPage() }
        addButton("🔁 开启 15 秒实时同步", "#C2410C") { startRealtimeSync() }
        addButton("⬆️ 手动上传本地账号数据", "#B91C1C") { uploadSelectedAccountNow() }
        addButton("⚙️ 设置服务器接口", "#475569") { showSyncSettingsDialog() }
        addButton("🧹 清理手机本地酒馆", "#111827") { confirmClearLocalTavern() }

        scroll.addView(box)
        AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showSyncSettingsDialog() {
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(6f))
        }
        val urlEdit = android.widget.EditText(ctx).apply {
            hint = "同步接口，例如 http://域名:8000/xixi-sync"
            setText(syncBaseUrl())
            setSingleLine(false)
            minLines = 2
        }
        val tokenEdit = android.widget.EditText(ctx).apply {
            hint = "同步密码 / Token"
            setText(syncToken())
            setSingleLine(true)
        }
        box.addView(TextView(ctx).apply { text = "服务器同步接口"; setTypeface(null, Typeface.BOLD); textSize = 16f })
        box.addView(urlEdit)
        box.addView(TextView(ctx).apply { text = "同步密码"; setTypeface(null, Typeface.BOLD); textSize = 16f; setPadding(0, dp(10f), 0, 0) })
        box.addView(tokenEdit)
        AlertDialog.Builder(this)
            .setTitle("⚙️ 同步设置")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                sharedPrefs.edit()
                    .putString("xixi_sync_base_url", urlEdit.text.toString().trim().trimEnd('/'))
                    .putString("xixi_sync_token", tokenEdit.text.toString().trim())
                    .apply()
                Toast.makeText(this, "已保存同步设置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadLocalTavernBundle() {
        val url = "${syncBaseUrl().trimEnd('/')}/bundle.zip?token=${urlEncode(syncToken())}"
        val zip = File(localDownloadDir(), "sillytavern_mobile_bundle.zip")
        val progress = showLocalProgressDialog("下载 SillyTavern 本体", "准备连接服务器...")

        Thread {
            try {
                downloadToFileWithProgress(url, zip) { downloaded, total, speed ->
                    runOnUiThread {
                        updateLocalDownloadProgress(
                            progress,
                            "正在下载本地酒馆包",
                            downloaded,
                            total,
                            speed
                        )
                    }
                }

                runOnUiThread {
                    progress.progressBar.isIndeterminate = true
                    progress.percentText.text = "下载完成，正在准备解压..."
                    progress.detailText.text = "已下载 ${formatBytes(zip.length())}，正在清理旧本地酒馆"
                }

                if (localTavernRoot().exists()) localTavernRoot().deleteRecursively()

                unzipWithProgress(zip, File(filesDir, "local_tavern")) { done, total, name ->
                    runOnUiThread {
                        progress.progressBar.isIndeterminate = false
                        progress.progressBar.max = 100
                        val pct = if (total > 0) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
                        progress.progressBar.progress = pct
                        progress.percentText.text = "正在解压：$pct%"
                        progress.detailText.text = "文件：$done / $total\n${name.takeLast(72)}"
                    }
                }

                runOnUiThread {
                    progress.progressBar.isIndeterminate = false
                    progress.progressBar.progress = 100
                    progress.percentText.text = "✅ 本地酒馆包已下载并解压"
                    progress.detailText.text = "目录：${localTavernRoot().absolutePath}"
                    Toast.makeText(this, "✅ 本地酒馆包已下载并解压", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.progressBar.isIndeterminate = false
                    progress.percentText.text = "❌ 下载/解压失败"
                    progress.detailText.text = e.message ?: "未知错误"
                    Toast.makeText(this, "❌ 下载/解压失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun fetchAccountsAndChoose() {
        Toast.makeText(this, "正在读取服务器账号列表...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val txt = httpGet("${syncBaseUrl().trimEnd('/')}/accounts?token=${urlEncode(syncToken())}")
                val arr = if (txt.trim().startsWith("[")) JSONArray(txt) else (JSONObject(txt).optJSONArray("accounts") ?: JSONArray())
                val names = ArrayList<String>()
                for (i in 0 until arr.length()) names.add(arr.optString(i))
                runOnUiThread {
                    if (names.isEmpty()) {
                        Toast.makeText(this, "服务器没有返回账号", Toast.LENGTH_LONG).show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("👤 选择要下载的账号")
                            .setItems(names.toTypedArray()) { _, which ->
                                val acc = names[which]
                                sharedPrefs.edit().putString("xixi_selected_account", acc).apply()
                                downloadAccountData(acc)
                            }
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "❌ 获取账号失败：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun downloadAccountData(account: String) {
        val url = "${syncBaseUrl().trimEnd('/')}/export?account=${urlEncode(account)}&token=${urlEncode(syncToken())}"
        val zip = File(localDownloadDir(), "account_${safeName(account)}.zip")
        val progress = showLocalProgressDialog("下载账号数据", "账号：$account")
        Thread {
            try {
                downloadToFileWithProgress(url, zip) { downloaded, total, speed ->
                    runOnUiThread {
                        updateLocalDownloadProgress(
                            progress,
                            "正在下载账号数据：$account",
                            downloaded,
                            total,
                            speed
                        )
                    }
                }
                val dataDir = localDataRoot()
                if (!dataDir.exists()) dataDir.mkdirs()
                runOnUiThread {
                    progress.progressBar.isIndeterminate = true
                    progress.percentText.text = "下载完成，正在解压账号数据..."
                    progress.detailText.text = "已下载 ${formatBytes(zip.length())}"
                }
                unzipWithProgress(zip, dataDir) { done, total, name ->
                    runOnUiThread {
                        progress.progressBar.isIndeterminate = false
                        progress.progressBar.max = 100
                        val pct = if (total > 0) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
                        progress.progressBar.progress = pct
                        progress.percentText.text = "正在解压账号：$pct%"
                        progress.detailText.text = "文件：$done / $total\n${name.takeLast(72)}"
                    }
                }
                runOnUiThread {
                    progress.progressBar.isIndeterminate = false
                    progress.progressBar.progress = 100
                    progress.percentText.text = "✅ 账号数据已下载到手机"
                    progress.detailText.text = "账号：$account"
                    Toast.makeText(this, "✅ 账号数据已下载到手机：$account", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.progressBar.isIndeterminate = false
                    progress.percentText.text = "❌ 下载账号失败"
                    progress.detailText.text = e.message ?: "未知错误"
                    Toast.makeText(this, "❌ 下载账号失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startEmbeddedNodeTavern() {
        if (localNodeRunning) {
            Toast.makeText(this, "本地 Node 酒馆已经在运行", Toast.LENGTH_SHORT).show()
            openLocalTavernPage()
            return
        }
        if (!NodeMobileNative.isAvailable()) {
            AlertDialog.Builder(this)
                .setTitle("Node 没有正确塞进 APK")
                .setMessage("当前 native-lib/libnode 没加载成功：\n${NodeMobileNative.lastError()}\n\n请确认 Android Studio 构建时已经下载并打包 nodejs-mobile。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val root = localTavernRoot()
        if (!File(root, "server.js").exists()) {
            Toast.makeText(this, "还没有下载 SillyTavern 本体，先点第 1 步", Toast.LENGTH_LONG).show()
            return
        }
        val bootstrap = File(filesDir, "xixi_bootstrap.js")
        try {
            assets.open("nodejs-project/xixi_bootstrap.js").use { input ->
                bootstrap.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "复制启动脚本失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        localNodeRunning = true
        localNodeThread = Thread {
            try {
                NodeMobileNative.start(arrayOf("node", bootstrap.absolutePath, root.absolutePath, "8000"))
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "本地 Node 启动失败：${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                localNodeRunning = false
            }
        }.apply { isDaemon = true; start() }
        Toast.makeText(this, "正在启动本地 Node 酒馆...", Toast.LENGTH_SHORT).show()
        mainHandler.postDelayed({ openLocalTavernPage() }, 2200)
    }

    private fun openLocalTavernPage() {
        try {
            splashOverlay?.let { rootContainer.removeView(it); splashOverlay = null }
        } catch (_: Exception) {}
        CURRENT_URL = "http://127.0.0.1:8000/"
        try { webView.loadUrl(CURRENT_URL) } catch (_: Exception) {}
    }

    private fun startRealtimeSync() {
        realtimeSyncRunnable?.let { mainHandler.removeCallbacks(it) }
        realtimeSyncRunnable = object : Runnable {
            override fun run() {
                uploadSelectedAccountSilent()
                mainHandler.postDelayed(this, 15000)
            }
        }
        mainHandler.post(realtimeSyncRunnable!!)
        Toast.makeText(this, "已开启每 15 秒同步一次", Toast.LENGTH_SHORT).show()
    }

    private fun uploadSelectedAccountNow() {
        Toast.makeText(this, "正在上传本地账号数据...", Toast.LENGTH_SHORT).show()
        uploadSelectedAccountSilent(true)
    }

    private fun uploadSelectedAccountSilent(showResult: Boolean = false) {
        val account = selectedLocalAccount()
        if (account.isBlank()) {
            if (showResult) Toast.makeText(this, "还没选择账号", Toast.LENGTH_SHORT).show()
            return
        }
        val dataRoot = localDataRoot()
        if (!dataRoot.exists()) {
            if (showResult) Toast.makeText(this, "手机本地还没有 data 目录", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val zip = File(localDownloadDir(), "upload_${safeName(account)}_${System.currentTimeMillis()}.zip")
                zipSelectedSyncData(dataRoot, zip)
                uploadZip("${syncBaseUrl().trimEnd('/')}/upload?account=${urlEncode(account)}&token=${urlEncode(syncToken())}", zip)
                try { zip.delete() } catch (_: Exception) {}
                if (showResult) runOnUiThread { Toast.makeText(this, "✅ 已上传到服务器", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                if (showResult) runOnUiThread { Toast.makeText(this, "❌ 上传失败：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun confirmClearLocalTavern() {
        AlertDialog.Builder(this)
            .setTitle("🧹 清理本地酒馆？")
            .setMessage("会删除手机里的 local_tavern 目录，不影响服务器。")
            .setPositiveButton("确定清理") { _, _ ->
                try { File(filesDir, "local_tavern").deleteRecursively() } catch (_: Exception) {}
                Toast.makeText(this, "已清理手机本地酒馆", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLocalProgressDialog(title: String, initialDetail: String): LocalProgressDialogRefs {
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(10f))
        }
        val titleView = TextView(ctx).apply {
            text = title
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1F2937"))
            setPadding(0, 0, 0, dp(10f))
        }
        val percentView = TextView(ctx).apply {
            text = "准备中..."
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F766E"))
            setPadding(0, 0, 0, dp(8f))
        }
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
        }
        val detailView = TextView(ctx).apply {
            text = initialDetail
            textSize = 12f
            setTextColor(Color.parseColor("#4B5563"))
            setLineSpacing(0f, 1.22f)
            setPadding(0, dp(10f), 0, 0)
        }
        box.addView(titleView)
        box.addView(percentView)
        box.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10f)))
        box.addView(detailView)
        val dialog = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("后台下载", null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        return LocalProgressDialogRefs(dialog, progressBar, percentView, detailView)
    }

    private fun updateLocalDownloadProgress(
        refs: LocalProgressDialogRefs,
        title: String,
        downloaded: Long,
        total: Long,
        speedBytesPerSecond: Double
    ) {
        if (total > 0L) {
            refs.progressBar.isIndeterminate = false
            refs.progressBar.max = 100
            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
            refs.progressBar.progress = pct
            refs.percentText.text = "$title：$pct%"
            refs.detailText.text = "已下载 ${formatBytes(downloaded)} / ${formatBytes(total)}\n速度 ${formatBytes(speedBytesPerSecond.toLong().coerceAtLeast(0L))}/s"
        } else {
            refs.progressBar.isIndeterminate = true
            refs.percentText.text = title
            refs.detailText.text = "已下载 ${formatBytes(downloaded)}\n速度 ${formatBytes(speedBytesPerSecond.toLong().coerceAtLeast(0L))}/s"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L).toDouble()
        return when {
            b >= 1024.0 * 1024.0 * 1024.0 -> String.format("%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
            b >= 1024.0 * 1024.0 -> String.format("%.1f MB", b / 1024.0 / 1024.0)
            b >= 1024.0 -> String.format("%.1f KB", b / 1024.0)
            else -> "${bytes.coerceAtLeast(0L)} B"
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            requestMethod = "GET"
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun downloadToFile(url: String, dest: File) {
        downloadToFileWithProgress(url, dest, null)
    }

    private fun downloadToFileWithProgress(
        url: String,
        dest: File,
        onProgress: ((downloaded: Long, total: Long, speedBytesPerSecond: Double) -> Unit)?
    ) {
        dest.parentFile?.mkdirs()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 120000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "DogBoneTavern-Android/1.0")
            setRequestProperty("Accept", "application/zip,*/*")
        }
        if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
        val total = try { conn.contentLengthLong } catch (_: Exception) { -1L }
        val buffer = ByteArray(128 * 1024)
        var downloaded = 0L
        val start = System.currentTimeMillis().coerceAtLeast(1L)
        var lastCallback = 0L
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloaded += read.toLong()
                    val now = System.currentTimeMillis()
                    if (onProgress != null && (now - lastCallback > 220L || downloaded == total)) {
                        val seconds = ((now - start).coerceAtLeast(1L)).toDouble() / 1000.0
                        onProgress(downloaded, total, downloaded.toDouble() / seconds)
                        lastCallback = now
                    }
                }
                output.flush()
            }
        }
        if (onProgress != null) {
            val seconds = ((System.currentTimeMillis() - start).coerceAtLeast(1L)).toDouble() / 1000.0
            onProgress(downloaded, total, downloaded.toDouble() / seconds)
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        targetDir.mkdirs()
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                val canonicalTarget = targetDir.canonicalPath + File.separator
                if (!outFile.canonicalPath.startsWith(canonicalTarget)) throw Exception("非法 zip 路径：${entry.name}")
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun unzipWithProgress(
        zipFile: File,
        targetDir: File,
        onProgress: ((done: Int, total: Int, entryName: String) -> Unit)?
    ) {
        targetDir.mkdirs()
        var totalEntries = 0
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    totalEntries++
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
        } catch (_: Exception) {
            totalEntries = 0
        }

        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var done = 0
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                val canonicalTarget = targetDir.canonicalPath + File.separator
                if (!outFile.canonicalPath.startsWith(canonicalTarget)) throw Exception("非法 zip 路径：${entry.name}")
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                done++
                if (done == 1 || done % 10 == 0 || done == totalEntries) {
                    onProgress?.invoke(done, totalEntries, entry.name)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun zipSelectedSyncData(dataRoot: File, zipFile: File) {
        val allow = setOf("chats", "group chats", "characters", "worlds", "User Avatars", "backgrounds", "user", "groups")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            allow.forEach { name ->
                val f = File(dataRoot, name)
                if (f.exists()) addFileToZip(zos, f, f.name)
            }
        }
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, path: String) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { addFileToZip(zos, it, "$path/${it.name}") }
        } else {
            zos.putNextEntry(ZipEntry(path))
            FileInputStream(file).use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    private fun uploadZip(url: String, zip: File) {
        val boundary = "----XiXiDogBone${System.currentTimeMillis()}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 120000
            doOutput = true
            requestMethod = "POST"
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        conn.outputStream.use { out ->
            fun write(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"file\"; filename=\"${zip.name}\"\r\n")
            write("Content-Type: application/zip\r\n\r\n")
            FileInputStream(zip).use { it.copyTo(out) }
            write("\r\n--$boundary--\r\n")
        }
        if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
    }

    private fun urlEncode(s: String): String = try { URLEncoder.encode(s, "UTF-8") } catch (_: Exception) { s }
    private fun safeName(s: String): String = s.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")

    // =====================================================================
    // 🐕 加载动画
    // =====================================================================
    private var loadingDogAnim: ValueAnimator? = null
    private var loadingProgressView: ProgressBar? = null
    private var loadingTextView: TextView? = null
    private var loadingEmojiView: TextView? = null

    private fun showLoadingAnimation(portal: Portal) {
        splashOverlay?.let {
            it.animate().alpha(0f).setDuration(250).withEndAction {
                rootContainer.removeView(it)
                splashOverlay = null
                buildLoadingOverlay(portal)
                webView.loadUrl(portal.url)
            }.start()
        }
    }

    private fun buildLoadingOverlay(portal: Portal) {
        val ctx = this
        val overlay = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                val colors = portal.gradient.split(",")
                setColors(intArrayOf(Color.parseColor("#1a1a2e"), Color.parseColor(colors[0]), Color.parseColor(colors[1])))
                orientation = GradientDrawable.Orientation.TL_BR
            }
            isClickable = true
        }

        val center = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(40f), 0, dp(40f), 0)
        }

        val cacheNotice = TextView(ctx).apply {
            text = "🧹 已清理旧缓存 · 正在写入新的酒馆缓存"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFE066"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14f))
        }
        center.addView(cacheNotice)

        val dogEmoji = TextView(ctx).apply {
            text = "🐕"; textSize = 80f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20f))
        }
        loadingEmojiView = dogEmoji
        center.addView(dogEmoji)

        loadingDogAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                dogEmoji.translationX = (Math.sin(Math.toRadians(v.toDouble())) * dp(20f)).toFloat()
                dogEmoji.rotation = (Math.sin(Math.toRadians((v * 2).toDouble())) * 8).toFloat()
            }
            start()
        }

        val emojis = arrayOf("🐕", "🐕‍🦺", "🐩", "🦴")
        var emojiIdx = 0
        val emojiSwitcher = object : Runnable {
            override fun run() {
                emojiIdx = (emojiIdx + 1) % emojis.size
                loadingEmojiView?.text = emojis[emojiIdx]
                mainHandler.postDelayed(this, 600)
            }
        }
        mainHandler.postDelayed(emojiSwitcher, 600)

        center.addView(TextView(ctx).apply {
            text = "正在钻进${portal.name.substring(2)}..."
            textSize = 16f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })

        val tips = arrayOf(
            "🧹 正在清理旧缓存痕迹...", "🚀 正在写入新的网页缓存...",
            "🦴 小狗在嗅气味...", "🌟 闻到酒馆的味道啦",
            "🐾 小狗跑得飞快", "✨ 马上就到啦",
            "🌈 钻洞ing...", "🎀 别走开汪～"
        )
        val tipView = TextView(ctx).apply {
            text = tips[0]; textSize = 12f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            gravity = Gravity.CENTER; setPadding(0, dp(6f), 0, dp(28f))
        }
        loadingTextView = tipView
        center.addView(tipView)

        var tipIdx = 0
        val tipSwitcher = object : Runnable {
            override fun run() {
                tipIdx = (tipIdx + 1) % tips.size
                loadingTextView?.text = tips[tipIdx]
                mainHandler.postDelayed(this, 1500)
            }
        }
        mainHandler.postDelayed(tipSwitcher, 1500)

        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 5
        }
        loadingProgressView = progressBar
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8f))
        center.addView(progressBar, lp)

        center.addView(TextView(ctx).apply {
            text = "🐶 小狗加速器已启用 · 本次启动会重新写入缓存"; textSize = 10f
            setTextColor(Color.parseColor("#88FFFFFF"))
            gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, 0)
        })

        overlay.addView(center, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        rootContainer.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        splashOverlay = overlay

        ValueAnimator.ofInt(5, 90).apply {
            duration = 10000
            addUpdateListener { progressBar.progress = it.animatedValue as Int }
            start()
        }
    }

    private fun hideLoadingAnimation() {
        loadingDogAnim?.cancel()
        loadingDogAnim = null

        // 不要调用 mainHandler.removeCallbacksAndMessages(null)
        // 否则会误删公告、重启、健康检查等其它延迟任务。
        splashOverlay?.let { ov ->
            loadingProgressView?.let { progress ->
                ValueAnimator.ofInt(progress.progress, 100).apply {
                    duration = 300
                    addUpdateListener { a -> progress.progress = a.animatedValue as Int }
                    start()
                }
            }
            mainHandler.postDelayed({
                ov.animate().alpha(0f).setDuration(400).withEndAction {
                    try { rootContainer.removeView(ov) } catch (_: Exception) {}
                    splashOverlay = null
                    loadingProgressView = null
                    loadingTextView = null
                    loadingEmojiView = null
                }.start()
            }, 350)
        }
    }

    // =====================================================================
    // 💣 一键伪重装（超级稳定软重启方案）
    // =====================================================================
    @Volatile private var isResetting = false

    private fun simulateReinstall() {
        if (isResetting) return
        isResetting = true

        Toast.makeText(this, "🐕💣 小狗正在重置酒馆汪...", Toast.LENGTH_SHORT).show()

        // ✅ 第一阶段：立刻停止网页，清理 WebView / Cookie / WebStorage
        try { webView.stopLoading() } catch (_: Exception) {}
        try { stopServerHealthCheck() } catch (_: Exception) {}

        try { webView.loadUrl("about:blank") } catch (_: Exception) {}
        try { webView.clearCache(true) } catch (_: Exception) {}
        try { webView.clearHistory() } catch (_: Exception) {}
        try { webView.clearFormData() } catch (_: Exception) {}
        try { webView.clearSslPreferences() } catch (_: Exception) {}
        try { webView.clearMatches() } catch (_: Exception) {}

        try { com.tencent.smtt.sdk.CookieManager.getInstance().removeAllCookie() } catch (_: Exception) {}
        try { com.tencent.smtt.sdk.CookieManager.getInstance().removeSessionCookie() } catch (_: Exception) {}
        try { com.tencent.smtt.sdk.CookieManager.getInstance().flush() } catch (_: Exception) {}

        try { android.webkit.CookieManager.getInstance().removeAllCookies(null) } catch (_: Exception) {}
        try { android.webkit.CookieManager.getInstance().removeSessionCookies(null) } catch (_: Exception) {}
        try { android.webkit.CookieManager.getInstance().flush() } catch (_: Exception) {}

        try { android.webkit.WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}

        // ✅ 第二阶段：子线程清理文件。清完之后再回主线程重开页面。
        Thread {
            try { sharedPrefs.edit().clear().commit() } catch (_: Exception) {}

            try { cacheDir?.listFiles()?.forEach { it.deleteRecursively() } } catch (_: Exception) {}
            try { externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() } } catch (_: Exception) {}
            try { codeCacheDir?.listFiles()?.forEach { it.deleteRecursively() } } catch (_: Exception) {}

            try {
                val webViewDir = File(applicationInfo.dataDir, "app_webview")
                if (webViewDir.exists()) webViewDir.listFiles()?.forEach { it.deleteRecursively() }
            } catch (_: Exception) {}

            try {
                val x5Dir = File(applicationInfo.dataDir, "app_tbs")
                if (x5Dir.exists()) x5Dir.listFiles()?.forEach { it.deleteRecursively() }
            } catch (_: Exception) {}

            try { clearAllWebCache() } catch (_: Exception) {}

            runOnUiThread {
                Toast.makeText(this, "✅ 重置完成，正在重新打开酒馆汪～", Toast.LENGTH_SHORT).show()

                mainHandler.postDelayed({
                    try { stopService(Intent(this, FloatingService::class.java)) } catch (_: Exception) {}
                    softRestartAppAfterReset()
                }, 500)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun softRestartAppAfterReset() {
        try {
            isResetting = false

            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra("FROM_RESET_RESTART", true)
            }

            startActivity(intent)
            try { overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out) } catch (_: Exception) {}
            finish()
        } catch (e: Exception) {
            isResetting = false
            Toast.makeText(this, "❌ 重启失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun switchPortal() {
        AlertDialog.Builder(this)
            .setTitle("🚪 换个狗洞钻？")
            .setMessage("将返回入口选择页\n（不会清空缓存，速度更快）")
            .setPositiveButton("好的") { _, _ ->
                try { webView.stopLoading() } catch (_: Exception) {}
                try { webView.loadUrl("about:blank") } catch (_: Exception) {}
                showPortalSelector()
            }
            .setNegativeButton("取消", null).show()
    }

    // =====================================================================
    // 通知 / 电池白名单
    // =====================================================================
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Toast.makeText(this, "🔔 通知权限已开启汪～", Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, "❌ 没有通知权限，控制面板按钮可能不可见", Toast.LENGTH_LONG).show()
    }
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try { notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) } catch (_: Exception) {}
            }
        }
    }
    private fun requestBatteryWhitelist() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val key = "battery_whitelist_asked_v2"
        if (sharedPrefs.getBoolean(key, false)) return
        val brand = Build.MANUFACTURER.uppercase()
        val brandTip = when {
            brand.contains("HUAWEI") || brand.contains("HONOR") -> "华为/荣耀手机会主动杀掉后台服务\n必须手动设置才能保住悬浮球"
            brand.contains("OPPO") || brand.contains("REALME") || brand.contains("ONEPLUS") -> "OPPO/Realme 系统会偷偷杀后台\n需要手动加白名单"
            brand.contains("XIAOMI") || brand.contains("REDMI") -> "小米/Redmi 系统会限制后台运行"
            brand.contains("VIVO") -> "Vivo 系统会限制后台"
            else -> "部分手机系统会杀掉后台服务\n导致悬浮球消失"
        }
        buildPrettyDialog("🔋", "小狗需要特殊权限", "不设置的话悬浮球会被系统杀掉",
            listOf(
                Triple("🐕 为什么需要？", "FFD369", brandTip),
                Triple("🛡️ 操作步骤", "38EF7D", "1️⃣ 点【去设置】关闭电池优化\n2️⃣ 跳转后台管理→找酒馆→允许后台+自启动\n3️⃣ 最近任务里下拉酒馆🔒锁住"),
                Triple("💡 安全说明", "7BB7FF", "不会明显增加耗电\n只是告诉系统不要杀小狗")
            ),
            "去设置", {
                sharedPrefs.edit().putBoolean(key, true).apply()
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") })
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                }
                mainHandler.postDelayed({ tryOpenAutoStartSetting() }, 1500)
            }, null, null, "下次再说", {}).show()
    }
    private fun tryOpenAutoStartSetting() {
        val intents = listOf(
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") }
        )
        for (i in intents) {
            try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); if (i.resolveActivity(packageManager) != null) { startActivity(i); return } } catch (_: Exception) {}
        }
    }

    // =====================================================================
    // 缓存系统
    // =====================================================================
    private fun showStartupCacheHint() {
        mainHandler.postDelayed({
            showCenterToast("🚀 小狗加速器已启动\n正在重新写入新的缓存汪～", 3500)
        }, 12000)
    }
    private fun openCacheFolderInternal() {
        val path = getCachePathForDisplay()
        try { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("缓存路径", path)) } catch (_: Exception) {}
        AlertDialog.Builder(this).setTitle("📂 缓存目录").setMessage("路径已复制：\n$path").setPositiveButton("好的", null).show()
    }
    private fun buildPrettyDialog(emoji: String, title: String, subtitle: String, cards: List<Triple<String, String, String>>,
                                  primaryText: String, onPrimary: (() -> Unit)?, neutralText: String? = null, onNeutral: (() -> Unit)? = null,
                                  negativeText: String? = "关闭", onNegative: (() -> Unit)? = null): Dialog {
        val ctx = this
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22f), dp(22f), dp(22f), dp(14f))
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat()
                colors = intArrayOf(Color.parseColor("#1a1a2e"), Color.parseColor("#16213e"), Color.parseColor("#0f3460"))
                orientation = GradientDrawable.Orientation.TL_BR }
        }
        ll.addView(TextView(ctx).apply { text = emoji; textSize = 40f; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(4f)) })
        ll.addView(TextView(ctx).apply { text = title; textSize = 19f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        ll.addView(TextView(ctx).apply { text = subtitle; textSize = 12f; setTextColor(Color.parseColor("#99FFFFFF")); gravity = Gravity.CENTER; setPadding(0, dp(4f), 0, dp(16f)) })
        for ((tag, color, content) in cards) {
            val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.parseColor("#33$color")); setStroke(dp(1f), Color.parseColor("#55$color")) } }
            card.addView(TextView(ctx).apply { text = tag; textSize = 10f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#FF$color")); setPadding(0, 0, 0, dp(4f)) })
            card.addView(TextView(ctx).apply { text = content; textSize = 14f; setTextColor(Color.parseColor("#FFEAEAEA")); setLineSpacing(0f, 1.35f); setTextIsSelectable(true) })
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(10f); ll.addView(card, lp)
        }
        scroll.addView(ll)
        val builder = AlertDialog.Builder(ctx).setView(scroll)
        builder.setPositiveButton(primaryText) { _, _ -> onPrimary?.invoke() }
        if (neutralText != null) builder.setNeutralButton(neutralText) { _, _ -> onNeutral?.invoke() }
        if (negativeText != null) builder.setNegativeButton(negativeText) { _, _ -> onNegative?.invoke() }
        val dialog = builder.create()
        dialog.setOnShowListener { try { dialog.window?.setBackgroundDrawable(GradientDrawable().apply { cornerRadius = dp(22f).toFloat(); setColor(Color.parseColor("#1a1a2e")) }) } catch (_: Exception) {} }
        return dialog
    }
    private fun showPrettyCacheStatsDialog() {
        val files = try { webCacheDir.listFiles { f -> f.name.endsWith(".dat") } ?: emptyArray() } catch (_: Exception) { emptyArray() }
        val mb = files.sumOf { it.length() } / 1024.0 / 1024.0
        val hitRate = if (cacheHitCount + cacheMissCount > 0) (cacheHitCount * 100.0 / (cacheHitCount + cacheMissCount)) else 0.0
        buildPrettyDialog("🚀", "小狗加速器", "嗅嗅看现在的状态～",
            listOf(
                Triple("📦 缓存仓库", "7BB7FF", "文件数量: ${files.size} 个\n占用空间: ${"%.2f".format(mb)} MB"),
                Triple("⚡ 加速战绩", "38EF7D", "命中: $cacheHitCount 次\n命中率: ${"%.1f".format(hitRate)}%"),
                Triple("📂 存放位置", "FFCB7B", getCachePathForDisplay())
            ),
            "📂 一键前往", { openCacheFolderInternal() },
            "🧹 清空缓存", { Thread { val n = clearAllWebCache(); runOnUiThread { Toast.makeText(applicationContext, "🧹 已清空 $n 个文件汪～", Toast.LENGTH_LONG).show() } }.start() }, "关闭"
        ).show()
    }
    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun showCenterToast(msg: String, durationMs: Long = 2200L) {
        mainHandler.post {
            try {
                val tv = TextView(this).apply { text = msg; textSize = 16f; setTextColor(Color.WHITE); setPadding(60, 36, 60, 36); gravity = Gravity.CENTER
                    background = GradientDrawable().apply { colors = intArrayOf(Color.parseColor("#E0667eea"), Color.parseColor("#E0764ba2")); orientation = GradientDrawable.Orientation.TL_BR; cornerRadius = 60f }; elevation = 16f }
                val toast = Toast(applicationContext); @Suppress("DEPRECATION") toast.view = tv
                toast.setGravity(Gravity.CENTER, 0, 0); toast.duration = if (durationMs > 2500) Toast.LENGTH_LONG else Toast.LENGTH_SHORT; toast.show()
            } catch (_: Exception) { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
        }
    }
    private fun initTranslator() {
        try {
            val options = TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.CHINESE).build()
            translator = Translation.getClient(options); lifecycle.addObserver(translator!!)
            translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
                ?.addOnSuccessListener { translatorReady = true }
                ?.addOnFailureListener { translatorReady = false }
        } catch (_: Exception) {}
    }
    private fun isCacheable(url: String, method: String, headers: Map<String, String>?): Boolean {
        if (!method.equals("GET", true)) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val lower = url.lowercase()
        val blacklist = listOf("/api/","/sse","/socket.io","/events","/csrf","/login","/logout","/auth","/session","/user","/getstatus","/getconfig","/settings")
        for (b in blacklist) if (lower.contains(b)) return false
        if (headers != null) for ((k, _) in headers) { val lk = k.lowercase(); if (lk == "range" || lk == "upgrade" || lk == "sec-websocket-key" || lk == "authorization" || lk == "cookie") return false }
        val path = try { java.net.URI(url).path ?: "" } catch (_: Exception) { return false }
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("js","mjs","css","png","jpg","jpeg","gif","svg","ico","webp","bmp","woff","woff2","ttf","otf","eot","mp3","ogg","wav","m4a","map")
    }
    private fun urlHash(url: String): String = try { MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } } catch (_: Exception) { url.hashCode().toString() }
    private fun guessMime(url: String): String {
        val ext = url.substringAfterLast('.', "").substringBefore('?').substringBefore('#').lowercase()
        return when (ext) { "js","mjs" -> "application/javascript"; "css" -> "text/css"; "json","map" -> "application/json"; "png" -> "image/png"; "jpg","jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"; "ico" -> "image/x-icon"; "webp" -> "image/webp"; "woff" -> "font/woff"; "woff2" -> "font/woff2"; "ttf" -> "font/ttf"; "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; "m4a" -> "audio/mp4"; else -> "application/octet-stream" }
    }
    private fun cacheReadResponse(url: String): WebResourceResponse? {
        return try {
            val hash = urlHash(url); val datFile = File(webCacheDir, "$hash.dat"); val metaFile = File(webCacheDir, "$hash.meta")
            if (!datFile.exists() || !metaFile.exists() || datFile.length() == 0L) return null
            val meta = metaFile.readLines().associate { val idx = it.indexOf('='); if (idx < 0) "" to "" else it.substring(0, idx) to it.substring(idx + 1) }
            val mime = meta["mime"]?.takeIf { it.isNotBlank() } ?: guessMime(url)
            val encoding = meta["encoding"]?.takeIf { it.isNotBlank() } ?: "UTF-8"
            try { datFile.setLastModified(System.currentTimeMillis()) } catch (_: Exception) {}
            cacheHitCount++
            val response = WebResourceResponse(mime, encoding, FileInputStream(datFile))
            try { response.responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000, immutable", "X-Local-Cache" to "HIT"); response.setStatusCodeAndReasonPhrase(200, "OK") } catch (_: Exception) {}
            response
        } catch (_: Exception) { null }
    }
    private fun cacheFetchAndStore(url: String, headers: Map<String, String>?): WebResourceResponse? {
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 12000; requestMethod = "GET"; instanceFollowRedirects = true
                setRequestProperty("Connection", "keep-alive"); setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("User-Agent", headers?.get("User-Agent") ?: "Mozilla/5.0 (Linux; Android) SillyTavernApp")
                setRequestProperty("Accept", headers?.get("Accept") ?: "*/*")
                headers?.forEach { (k, v) -> val lk = k.lowercase(); if (lk !in setOf("host","connection","content-length","if-none-match","if-modified-since","range","user-agent","accept","accept-encoding")) try { setRequestProperty(k, v) } catch (_: Exception) {} }
            }
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); cacheMissCount++; return null }
            val ctype = conn.contentType ?: ""
            val mime = ctype.substringBefore(";").trim().ifBlank { guessMime(url) }
            val encoding = if (ctype.contains("charset=", true)) ctype.substringAfter("charset=", "").trim().trim(';',' ','"','\'').takeIf { it.isNotEmpty() } ?: "UTF-8" else "UTF-8"
            val hash = urlHash(url); val datFile = File(webCacheDir, "$hash.dat"); val tmpDat = File(webCacheDir, "$hash.dat.tmp"); val metaFile = File(webCacheDir, "$hash.meta")
            try {
                val rawStream = conn.inputStream
                val input = if (conn.contentEncoding?.equals("gzip", true) == true) java.util.zip.GZIPInputStream(rawStream) else rawStream
                input.use { inp -> FileOutputStream(tmpDat).use { output -> inp.copyTo(output, 32 * 1024) } }
            } finally { try { conn.disconnect() } catch (_: Exception) {} }
            if (datFile.exists()) datFile.delete(); tmpDat.renameTo(datFile)
            metaFile.writeText("url=$url\nmime=$mime\nencoding=$encoding\nsize=${datFile.length()}\nts=${System.currentTimeMillis()}")
            cacheMissCount++
            val response = WebResourceResponse(mime, encoding, FileInputStream(datFile))
            try { response.responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000", "X-Local-Cache" to "STORE"); response.setStatusCodeAndReasonPhrase(200, "OK") } catch (_: Exception) {}
            response
        } catch (_: Exception) { cacheMissCount++; null }
    }
    private fun cleanupCacheLRUAsync() {
        Thread {
            try {
                val files = webCacheDir.listFiles { f -> f.name.endsWith(".dat") } ?: return@Thread
                var total = files.sumOf { it.length() }
                if (total <= webCacheMaxBytes) return@Thread
                val sorted = files.sortedBy { it.lastModified() }
                val target = (webCacheMaxBytes * 0.85).toLong()
                for (f in sorted) { if (total <= target) break; val sz = f.length(); val baseName = f.nameWithoutExtension; val meta = File(f.parentFile, "$baseName.meta"); if (f.delete()) { total -= sz; meta.delete() } }
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }
    private fun clearAllWebCache(): Int {
        var n = 0
        try { webCacheDir.listFiles()?.forEach { if (it.delete()) n++ }; sharedPrefs.edit().putBoolean("first_load_done_v1", false).apply(); firstLoadNotified = false } catch (_: Exception) {}
        return n
    }
    private fun matchLocalDict(raw: String): String? {
        val s = raw.lowercase()
        return when {
            s.contains("no available channel") || s.contains("distributor") -> "API代理报错——当前模型没渠道了"
            s.contains("api key") || s.contains(" 401") || s.contains("unauthorized") -> "API密码不对"
            s.contains("quota") || s.contains(" 429") || s.contains("rate limit") -> "余额不足或被限流"
            s.contains("context") && (s.contains("length") || s.contains("token")) -> "爆Token啦"
            s.contains("timeout") -> "请求超时"
            s.contains("connection refused") -> "连接被拒"
            s.contains("server error") || s.contains("500") || s.contains("502") -> "服务器抽风"
            else -> null
        }
    }
    private fun translateAndExplainSplit(rawErr: String, callback: (String?, String?) -> Unit) {
        val dictHit = matchLocalDict(rawErr)
        val toTranslate = if (rawErr.length > 400) rawErr.substring(0, 400) + "…" else rawErr
        if (!translatorReady || translator == null) { callback(null, dictHit); return }
        translator!!.translate(toTranslate).addOnSuccessListener { callback(it.trim(), dictHit) }.addOnFailureListener { callback(null, dictHit) }
    }
    private fun playNotificationSound() {
        if (!isSoundEnabled) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mp = MediaPlayer().apply { setDataSource(applicationContext, uri); setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()) }
            mp.prepare(); mp.setOnCompletionListener { it.release() }; mp.start()
        } catch (_: Exception) {}
    }
    private fun showGlobalToast(msg: String) {
        mainHandler.post {
            try {
                if (!isAppInForeground && Settings.canDrawOverlays(this)) {
                    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                    val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
                    params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; params.y = 250
                    val tv = TextView(applicationContext).apply { text = msg; textSize = 15f; setTextColor(Color.WHITE); setPadding(50, 25, 50, 25); background = GradientDrawable().apply { setColor(Color.parseColor("#D9000000")); cornerRadius = 50f } }
                    wm.addView(tv, params); mainHandler.postDelayed({ try { wm.removeView(tv) } catch (_: Exception) {} }, 3500)
                } else Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            } catch (_: Exception) { try { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {} }
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface fun getSoundState(): Boolean = isSoundEnabled
        @JavascriptInterface fun toggleSound(): Boolean {
            isSoundEnabled = !isSoundEnabled
            sharedPrefs.edit().putBoolean("sound_enabled", isSoundEnabled).apply()
            mainHandler.post { Toast.makeText(applicationContext, if (isSoundEnabled) "🔊 AI 提示音已开启" else "🔇 AI 提示音已关闭", Toast.LENGTH_SHORT).show(); if (isSoundEnabled) playNotificationSound() }
            return isSoundEnabled
        }
        @JavascriptInterface fun notifyAiReplyDone() { showGlobalToast("回复完毕汪！"); playNotificationSound() }
        @JavascriptInterface fun notifyAiReplyTruncated() { showGlobalToast("呜呜呜！为什么截断我汪。"); playNotificationSound() }
        @JavascriptInterface fun notifyAiReplyEmpty() { showGlobalToast("可恶的ai本汪等了那么久竟然空回本汪！！"); playNotificationSound() }
        @JavascriptInterface fun onError(errorMsg: String) {
            mainHandler.post { if (System.currentTimeMillis() - lastErrorTime > 5000) { lastErrorTime = System.currentTimeMillis(); notifySillyError(errorMsg) } }
        }
        @JavascriptInterface fun openInBrowser(url: String) {
            mainHandler.post { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); Toast.makeText(applicationContext, "🌐 已在浏览器打开", Toast.LENGTH_SHORT).show() } catch (_: Exception) {} }
        }
        @JavascriptInterface fun retryConnection() {
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                Toast.makeText(applicationContext, "🐕 小狗正在嗅气味汪...", Toast.LENGTH_SHORT).show()
                Thread {
                    var ok = false
                    try {
                        val host = java.net.URI(CURRENT_URL).host
                        if (host != null) java.net.InetAddress.getAllByName(host)
                        val conn = java.net.URL(CURRENT_URL).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 3500; conn.readTimeout = 3500
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0"); conn.setRequestProperty("Cache-Control", "no-cache")
                        if (conn.responseCode in 200..399) ok = true; conn.disconnect()
                    } catch (_: Exception) {}
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        if (ok) { Toast.makeText(applicationContext, "🎉 欢迎回到酒馆汪！", Toast.LENGTH_LONG).show()
                            isRetryingFlag = false; serverAwakeNotified = false; stopServerHealthCheck()
                            try { webView.clearCache(false) } catch (_: Exception) {}; webView.loadUrl(CURRENT_URL)
                        } else Toast.makeText(applicationContext, "😿 酒馆还在睡觉，再等等汪...", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
        }
        @JavascriptInterface fun markShotStart(scrollTop: Int, clientH: Int) { mainHandler.post { shotStartY = scrollTop; Toast.makeText(applicationContext, "📍 已标记开头！滚到结尾处再点一次📸即可", Toast.LENGTH_LONG).show() } }
        @JavascriptInterface fun markShotEndAndCapture(scrollTop: Int, clientH: Int) {
            mainHandler.post {
                if (shotStartY < 0) { Toast.makeText(applicationContext, "❌ 还没标记开头汪～", Toast.LENGTH_SHORT).show(); return@post }
                val endBottom = scrollTop + clientH; val sY = minOf(shotStartY, endBottom); val eY = maxOf(shotStartY, endBottom)
                if (eY - sY < 50) { Toast.makeText(applicationContext, "❌ 起止位置太近啦", Toast.LENGTH_SHORT).show(); shotStartY = -1; return@post }
                startRangeScreenshot(sY, eY)
            }
        }
        @JavascriptInterface fun cancelShotMark() { mainHandler.post { shotStartY = -1 } }
        @JavascriptInterface fun hasShotStart(): Boolean = shotStartY >= 0
        @JavascriptInterface fun toastNoChat() { mainHandler.post { Toast.makeText(applicationContext, "❌ 找不到聊天区域", Toast.LENGTH_SHORT).show() } }
        @JavascriptInterface fun generatePosterCard(text: String, charName: String, avatarUrl: String, styleIdx: Int) {
            mainHandler.post { Toast.makeText(applicationContext, "🎨 正在生成卡片汪～", Toast.LENGTH_SHORT).show() }
            Thread {
                try {
                    val avatar = loadBitmapFromUrl(avatarUrl)
                    val poster = drawPosterCard(stripHtml(text), charName.ifBlank { "未知角色" }, avatar, styleIdx)
                    saveBitmapToGallery(poster, "Tavern_Card_${System.currentTimeMillis()}.png"); poster.recycle(); avatar?.recycle()
                    runOnUiThread { Toast.makeText(applicationContext, "🎉 卡片已保存", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) { runOnUiThread { Toast.makeText(applicationContext, "❌ 生成失败", Toast.LENGTH_LONG).show() } }
            }.start()
        }
        @JavascriptInterface fun openCacheFolder() { mainHandler.post { openCacheFolderInternal() } }
        @JavascriptInterface fun getCachePath(): String = getCachePathForDisplay()
        @JavascriptInterface fun clearWebCache() { Thread { val n = clearAllWebCache(); runOnUiThread { Toast.makeText(applicationContext, "🧹 已清空 $n 个文件", Toast.LENGTH_LONG).show() } }.start() }
        @JavascriptInterface fun showCacheStats() { mainHandler.post { showPrettyCacheStatsDialog() } }
        @JavascriptInterface fun simulateReinstall() { mainHandler.post { this@MainActivity.simulateReinstall() } }
        @JavascriptInterface fun confirmReset() {
            mainHandler.post {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("💣 重置酒馆？")
                    .setMessage("🐕 这会清空所有缓存并自动重启\n（相当于重装APP）\n\n确定要重置吗汪？")
                    .setPositiveButton("✅ 确定重置") { _, _ -> this@MainActivity.simulateReinstall() }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        @JavascriptInterface fun switchPortal() { mainHandler.post { this@MainActivity.switchPortal() } }
        @JavascriptInterface fun setDisplayMode(mode: String) {
            mainHandler.post {
                applyDisplayMode("normal")
                Toast.makeText(applicationContext, "🌟 已固定为普通模式", Toast.LENGTH_SHORT).show()
            }
        }
        @JavascriptInterface fun getDisplayMode(): String = "normal"
        @JavascriptInterface fun openNovelFileImporter() { mainHandler.post { this@MainActivity.openNovelFileImporter() } }
    }

    private fun stripHtml(s: String): String = s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"").trim()
    private fun loadBitmapFromUrl(url: String): Bitmap? {
        if (url.isBlank()) return null
        return try {
            val absUrl = if (url.startsWith("http")) url else if (url.startsWith("//")) "http:$url" else CURRENT_URL.trimEnd('/') + "/" + url.trimStart('/')
            val conn = (java.net.URL(absUrl).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 5000; setRequestProperty("User-Agent", "Mozilla/5.0") }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }
    private fun drawPosterCard(cleanText: String, charName: String, avatar: Bitmap?, styleIdx: Int): Bitmap {
        val W = 1080; val padding = 80f
        data class Style(val bg1: Int, val bg2: Int, val accent: Int, val textColor: Int, val nameColor: Int, val quoteColor: Int, val sparkleColor: Int, val label: String)
        val styles = listOf(
            Style(Color.parseColor("#2d1b69"), Color.parseColor("#11001c"), Color.parseColor("#b388ff"), Color.parseColor("#e8e0f0"), Color.parseColor("#ce93d8"), Color.parseColor("#b388ff"), Color.parseColor("#e1bee7"), "暮光紫"),
            Style(Color.parseColor("#fff0f3"), Color.parseColor("#fce4ec"), Color.parseColor("#f48fb1"), Color.parseColor("#4a2c3d"), Color.parseColor("#c2185b"), Color.parseColor("#f48fb1"), Color.parseColor("#f8bbd0"), "樱花粉"),
            Style(Color.parseColor("#f5f0e6"), Color.parseColor("#e8dfc8"), Color.parseColor("#8d6e63"), Color.parseColor("#3e2723"), Color.parseColor("#5d4037"), Color.parseColor("#a1887f"), Color.parseColor("#bcaaa4"), "暖茶棕"),
            Style(Color.parseColor("#0a1628"), Color.parseColor("#1a237e"), Color.parseColor("#82b1ff"), Color.parseColor("#e3f2fd"), Color.parseColor("#90caf9"), Color.parseColor("#64b5f6"), Color.parseColor("#bbdefb"), "月光蓝"),
            Style(Color.parseColor("#3e1f00"), Color.parseColor("#1a0a00"), Color.parseColor("#ffab91"), Color.parseColor("#fff3e0"), Color.parseColor("#ffcc80"), Color.parseColor("#ff8a65"), Color.parseColor("#ffe0b2"), "晚霞橙"),
            Style(Color.parseColor("#eceff1"), Color.parseColor("#cfd8dc"), Color.parseColor("#546e7a"), Color.parseColor("#263238"), Color.parseColor("#37474f"), Color.parseColor("#78909c"), Color.parseColor("#90a4ae"), "墨韵青")
        )
        val st = styles[styleIdx.coerceIn(0, styles.size - 1)]
        val maxChars = 600
        val displayText = if (cleanText.length > maxChars) cleanText.substring(0, maxChars) + "…" else cleanText
        val textPaint = TextPaint().apply { isAntiAlias = true; color = st.textColor; textSize = 40f; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL); letterSpacing = 0.02f }
        val contentWidth = (W - padding * 2).toInt()
        val staticLayout = android.text.StaticLayout.Builder.obtain(displayText, 0, displayText.length, textPaint, contentWidth).setLineSpacing(14f, 1.2f).setIncludePad(false).build()
        val avatarSize = 120f; val headerH = 200f; val divider1Y = headerH; val quoteTopY = divider1Y + 30f; val textTopY = quoteTopY + 80f; val textBottomY = textTopY + staticLayout.height + 40f; val footerH = 120f
        val totalH = (textBottomY + footerH).toInt().coerceAtLeast(900)
        val bmp = Bitmap.createBitmap(W, totalH, Bitmap.Config.ARGB_8888); val canvas = Canvas(bmp)
        canvas.drawRect(0f, 0f, W.toFloat(), totalH.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, totalH.toFloat(), st.bg1, st.bg2, Shader.TileMode.CLAMP) })
        val rng = Random(charName.hashCode() + styleIdx)
        val dotPaint = Paint().apply { isAntiAlias = true; color = st.sparkleColor }
        for (i in 0 until 35) { dotPaint.alpha = (rng.nextFloat() * 60 + 20).toInt(); canvas.drawCircle(rng.nextFloat() * W, rng.nextFloat() * totalH, rng.nextFloat() * 2.5f + 0.8f, dotPaint) }
        canvas.drawRect(0f, 0f, W.toFloat(), 5f, Paint().apply { color = st.accent; alpha = 180 })
        val avatarCx = padding + avatarSize / 2; val avatarCy = padding + avatarSize / 2
        if (avatar != null) {
            canvas.drawCircle(avatarCx, avatarCy, avatarSize / 2 + 6f, Paint().apply { color = st.accent; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 4f; alpha = 150 })
            val clipPath = Path().apply { addCircle(avatarCx, avatarCy, avatarSize / 2, Path.Direction.CW) }
            canvas.save(); canvas.clipPath(clipPath)
            canvas.drawBitmap(avatar, Rect(0, 0, avatar.width, avatar.height), RectF(avatarCx - avatarSize/2, avatarCy - avatarSize/2, avatarCx + avatarSize/2, avatarCy + avatarSize/2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            canvas.drawCircle(avatarCx, avatarCy, avatarSize / 2, Paint().apply { color = st.accent; isAntiAlias = true; alpha = 180 })
            canvas.drawText(if (charName.isNotEmpty()) charName.substring(0, 1) else "?", avatarCx, avatarCy + 18f, Paint().apply { color = Color.WHITE; isAntiAlias = true; textSize = 50f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER })
        }
        val nameX = avatarCx + avatarSize / 2 + 28f
        canvas.drawText(charName, nameX, avatarCy - 8f, Paint().apply { color = st.nameColor; isAntiAlias = true; textSize = 48f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) })
        canvas.drawText("— ${st.label} · 高光剪报 —", nameX, avatarCy + 36f, Paint().apply { color = st.textColor; alpha = 140; isAntiAlias = true; textSize = 24f })
        canvas.save(); canvas.translate(padding, textTopY); staticLayout.draw(canvas); canvas.restore()
        canvas.drawText("🐶🦴酒馆 · SillyTavern", W / 2f, textBottomY + 50f, Paint().apply { color = st.textColor; alpha = 160; isAntiAlias = true; textSize = 26f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) })
        return bmp
    }
    private fun showPrettyErrorDialog(translated: String?, dict: String?, rawErr: String) {
        buildPrettyDialog("🐶", "小狗辅助翻译", "嗅嗅看，这个错误说了啥～",
            listOf(
                Triple("翻译", "2663EB", translated ?: "翻译失败"),
                Triple("字典", "F59E0B", dict ?: "字典里没有"),
                Triple("原文", "FFFFFF", rawErr)
            ),
            "知道了", null, "📋 复制", { try { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("error", rawErr)) } catch (_: Exception) {} }
        ).show()
    }
    private fun notifySillyError(rawErr: String) {
        translateAndExplainSplit(rawErr) { translated, dict ->
            mainHandler.post {
                if (isAppInForeground) showPrettyErrorDialog(translated, dict, rawErr)
                else showGlobalToast("🐶 ${translated ?: dict ?: "酒馆出错"}")
            }
        }
    }
    inner class AndroidDownloader {
        @JavascriptInterface fun downloadBase64(base64Data: String, fileName: String) {
            Thread {
                try {
                    val base64 = base64Data.substringAfter("base64,")
                    val mimeType = base64Data.substringBefore(";base64,").substringAfter("data:").ifEmpty { "application/octet-stream" }
                    val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
                    val safeName = fileName.ifBlank { "sillytavern_${System.currentTimeMillis()}.bin" }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, safeName); put(MediaStore.MediaColumns.MIME_TYPE, mimeType); put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS) }
                        contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)?.let { contentResolver.openOutputStream(it)?.use { out -> out.write(decodedBytes) } }
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); if (!dir.exists()) dir.mkdirs()
                        FileOutputStream(File(dir, safeName)).use { it.write(decodedBytes) }
                    }
                    runOnUiThread { Toast.makeText(applicationContext, "🐶 下载了: $safeName", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) { runOnUiThread { Toast.makeText(applicationContext, "❌ 下载出错", Toast.LENGTH_SHORT).show() } }
            }.start()
        }
    }
    private fun startRangeScreenshot(startY: Int, endY: Int) {
        if (isCapturing) { Toast.makeText(this, "📸 正在截图", Toast.LENGTH_SHORT).show(); return }
        isCapturing = true; capturedBitmaps.clear()
        Toast.makeText(this, "📸 开始截取，请勿操作", Toast.LENGTH_SHORT).show()
        val js = """
            (function(){var f=document.querySelector('[data-st-tool-folder]');var cb=document.querySelector('[data-st-card-btn]');if(f)f.style.display='none';if(cb)cb.style.display='none';
            var chat=document.getElementById('chat');if(!chat)return 'NO_CHAT';chat.scrollTop=$startY;
            var r=chat.getBoundingClientRect();var cs=window.getComputedStyle(chat);var borderTop=parseFloat(cs.borderTopWidth)||0;var skipTopCss=Math.max(borderTop,4);
            var form=document.getElementById('form_sheld')||document.getElementById('send_form');
            var formTopCss=form?form.getBoundingClientRect().top:(r.top+r.height);
            var visibleCss=Math.max(50,Math.floor(Math.min(formTopCss,r.top+r.height)-r.top-skipTopCss));
            var dpr=window.devicePixelRatio||1;
            return Math.round((r.top+skipTopCss)*dpr)+','+Math.round(visibleCss*dpr)+','+visibleCss;})();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            try {
                val clean = raw.trim('"')
                if (clean.contains("NO_CHAT")) { isCapturing = false; shotStartY = -1; restoreFloatingBtns(); return@evaluateJavascript }
                val parts = clean.split(","); val chatTopPx = parts[0].toInt(); val visiblePx = parts[1].toInt(); val visibleCss = parts[2].toInt()
                if (visiblePx <= 0 || visibleCss <= 0) throw Exception()
                mainHandler.postDelayed({ captureRangeLoop(startY, endY, visibleCss, chatTopPx, visiblePx, 0) }, 500)
            } catch (e: Exception) { isCapturing = false; shotStartY = -1; restoreFloatingBtns() }
        }
    }
    private fun captureRangeLoop(startY: Int, endY: Int, vHCss: Int, chatTopPx: Int, visiblePx: Int, index: Int) {
        val totalRange = endY - startY; val n = ceil(totalRange.toDouble() / vHCss).toInt().coerceAtLeast(1)
        if (index >= n) { stitchAndSave(); return }
        if (index >= 30) { stitchAndSave(); return }
        val isLast = (index == n - 1)
        val targetScroll = if (isLast) maxOf(startY, endY - vHCss).coerceAtLeast(0) else startY + index * vHCss
        val remainingCss = if (isLast) (totalRange - index * vHCss).coerceAtLeast(1) else vHCss
        val cropFromCss = if (isLast) (vHCss - remainingCss).coerceAtLeast(0) else 0
        val dprF = visiblePx.toFloat() / vHCss.toFloat()
        val cropFromPx = (cropFromCss * dprF).toInt().coerceAtLeast(0)
        val cropHeightPx = (remainingCss * dprF).toInt().coerceAtLeast(1)
        webView.evaluateJavascript("(function(){var c=document.getElementById('chat');if(!c)return -1;c.scrollTop=$targetScroll;return c.scrollTop;})();") {
            mainHandler.postDelayed({
                try {
                    val full = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(full); canvas.drawColor(Color.parseColor("#121212")); webView.draw(canvas)
                    val srcY = (chatTopPx + cropFromPx).coerceAtLeast(0).coerceAtMost(full.height - 1)
                    val srcH = cropHeightPx.coerceAtMost(full.height - srcY).coerceAtLeast(1)
                    val cropped = Bitmap.createBitmap(full, 0, srcY, full.width, srcH); full.recycle()
                    capturedBitmaps.add(cropped); captureRangeLoop(startY, endY, vHCss, chatTopPx, visiblePx, index + 1)
                } catch (e: Throwable) { isCapturing = false; shotStartY = -1; capturedBitmaps.forEach { it.recycle() }; capturedBitmaps.clear(); restoreFloatingBtns() }
            }, 550)
        }
    }
    private fun stitchAndSave() {
        if (capturedBitmaps.isEmpty()) { isCapturing = false; shotStartY = -1; restoreFloatingBtns(); return }
        Thread {
            try {
                val w = capturedBitmaps[0].width; val totalH = capturedBitmaps.sumOf { it.height }
                val final = Bitmap.createBitmap(w, totalH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(final); canvas.drawColor(Color.parseColor("#121212"))
                var y = 0; for (bm in capturedBitmaps) { canvas.drawBitmap(bm, 0f, y.toFloat(), null); y += bm.height; bm.recycle() }
                capturedBitmaps.clear()
                saveBitmapToGallery(final, "Tavern_LongShot_${System.currentTimeMillis()}.png"); final.recycle()
                runOnUiThread { isCapturing = false; shotStartY = -1; restoreFloatingBtns(); Toast.makeText(this, "🎉 长截图已保存", Toast.LENGTH_LONG).show() }
            } catch (e: Throwable) { runOnUiThread { isCapturing = false; shotStartY = -1; restoreFloatingBtns() } }
        }.start()
    }
    private fun restoreFloatingBtns() {
        webView.evaluateJavascript("(function(){var f=document.querySelector('[data-st-tool-folder]');var cb=document.querySelector('[data-st-card-btn]');if(f)f.style.display='';if(cb)cb.style.display='';if(window._stHardCollapseFolder)window._stHardCollapseFolder();else if(window._stCollapseFolder)window._stCollapseFolder(true);})();", null)
    }

    private fun collapseInjectedToolFolder() {
        try {
            if (!::webView.isInitialized) return
            webView.evaluateJavascript(
                """
                (function(){
                    try {
                        if (window._stHardCollapseFolder) window._stHardCollapseFolder();
                        else if (window._stCollapseFolder) window._stCollapseFolder(true);

                        var folder = document.querySelector('[data-st-tool-folder]');
                        if (folder) {
                            folder.style.transform = 'translateZ(0)';
                            folder.style.backfaceVisibility = 'hidden';
                            folder.style.webkitBackfaceVisibility = 'hidden';
                            folder.style.contain = 'layout style paint';
                            folder.style.isolation = 'isolate';
                        }

                        document.querySelectorAll('[data-st-tool-folder] > div:nth-child(2) > div').forEach(function(el){
                            el.style.opacity = '0';
                            el.style.transform = 'translateX(115%) translateZ(0)';
                            el.style.backfaceVisibility = 'hidden';
                            el.style.webkitBackfaceVisibility = 'hidden';
                        });
                    } catch(e) {}
                })();
                """.trimIndent(),
                null
            )
        } catch (_: Exception) {}
    }
    private fun saveBitmapToGallery(bm: Bitmap, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Tavern") }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let { contentResolver.openOutputStream(it)?.use { os -> bm.compress(Bitmap.CompressFormat.PNG, 100, os) } }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Tavern"); if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, name)).use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun setupWebChromeClient() {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView?, filePath: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = filePath

                val intent = buildBetterFileChooserIntent(fileChooserParams)
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    Toast.makeText(applicationContext, "❌ 打开文件管理器失败：${e.message}", Toast.LENGTH_LONG).show()
                    false
                }
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                if (isAppInForeground) AlertDialog.Builder(this@MainActivity).setTitle("提示").setMessage(message).setPositiveButton("确定") { _, _ -> result?.confirm() }.setNegativeButton("取消") { _, _ -> result?.cancel() }.setCancelable(false).show()
                else result?.cancel(); return true
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                if (isAppInForeground) AlertDialog.Builder(this@MainActivity).setMessage(message).setPositiveButton("知道了") { _, _ -> result?.confirm() }.setCancelable(false).show()
                else result?.confirm(); return true
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) loadingProgressView?.let { if (it.progress < newProgress) it.progress = newProgress }
            }
        }
    }
    private fun setupDownloadListener() {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (!url.startsWith("blob:") && !url.startsWith("data:")) {
                try {
                    val request = DownloadManager.Request(Uri.parse(url))
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    request.setMimeType(mimetype).addRequestHeader("User-Agent", userAgent).setTitle(fileName)
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    request.setAllowedOverMetered(true); request.setAllowedOverRoaming(true)
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    Toast.makeText(applicationContext, "🐶 下载了: $fileName", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        }
    }
    private fun checkPiPIntent(intent: Intent?) { if (intent?.getBooleanExtra("ENTER_PIP_MODE", false) == true) { enterPiPMode(); intent.removeExtra("ENTER_PIP_MODE") } }
    private fun enterPiPMode() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build()) }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig); isAppInPip = isInPictureInPictureMode
        mainHandler.postDelayed({
            if (isInPictureInPictureMode) { isAppInForeground = true; webView.evaluateJavascript("javascript:(function(){var meta=document.querySelector('meta[name=\"viewport\"]');if(meta){if(!window._stOrigMeta)window._stOrigMeta=meta.getAttribute('content');meta.setAttribute('content','width=412, user-scalable=no');}})();", null) }
            else webView.evaluateJavascript("javascript:(function(){var meta=document.querySelector('meta[name=\"viewport\"]');if(meta&&window._stOrigMeta){meta.setAttribute('content',window._stOrigMeta);}else if(meta){meta.setAttribute('content','width=device-width, initial-scale=1.0, maximum-scale=1.0');}})();", null)
        }, 150)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); checkPiPIntent(intent) }
    private fun checkOverlayPermissionAndStartService() {
        if (!Settings.canDrawOverlays(this)) overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        else try { val intent = Intent(this, FloatingService::class.java); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent) } catch (_: Exception) {}
    }
    private fun startServerHealthCheck() {
        stopServerHealthCheck(); serverAwakeNotified = false; var attempts = 0
        val runnable = object : Runnable {
            override fun run() {
                val self = this; attempts++
                Thread {
                    var isOk = false
                    try {
                        val host = java.net.URI(CURRENT_URL).host; if (host != null) java.net.InetAddress.getAllByName(host)
                        val conn = java.net.URL(CURRENT_URL).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 3500; conn.readTimeout = 3500; conn.setRequestProperty("User-Agent", "Mozilla/5.0"); conn.setRequestProperty("Cache-Control", "no-cache")
                        if (conn.responseCode in 200..399) isOk = true; conn.disconnect()
                    } catch (_: Exception) {}
                    if (isOk) {
                        mainHandler.post {
                            if (isRetryingFlag && !serverAwakeNotified) {
                                serverAwakeNotified = true
                                Toast.makeText(applicationContext, "🎉 酒馆醒啦！", Toast.LENGTH_LONG).show()
                                showGlobalToast("🎉 酒馆醒啦！点【立即重试】回去汪～")
                                playNotificationSound()
                                webView.evaluateJavascript("if(typeof onServerAwake==='function')onServerAwake();", null)
                            }
                            stopServerHealthCheck()
                        }; return@Thread
                    }
                    mainHandler.postDelayed(self, if (attempts < 120) 3000L else 30000L)
                }.start()
            }
        }
        healthCheckRunnable = runnable; mainHandler.post(runnable)
    }
    private fun stopServerHealthCheck() { healthCheckRunnable?.let { mainHandler.removeCallbacks(it) }; healthCheckRunnable = null }

    private fun initWebViewSettings() {
        webView.setBackgroundColor(Color.parseColor("#121212"))
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true; s.domStorageEnabled = true
        try { s.setAppCacheEnabled(true) } catch (_: Throwable) {}
        try { s.setAppCachePath(cacheDir.absolutePath) } catch (_: Throwable) {}
        try { s.setAppCacheMaxSize(100 * 1024 * 1024) } catch (_: Throwable) {}
        s.databaseEnabled = true; s.allowFileAccess = true; s.useWideViewPort = true
        s.loadWithOverviewMode = false; s.cacheMode = WebSettings.LOAD_DEFAULT
        s.javaScriptCanOpenWindowsAutomatically = true; s.loadsImagesAutomatically = true
        s.blockNetworkImage = false; s.setGeolocationEnabled(false)
        s.setRenderPriority(WebSettings.RenderPriority.HIGH)
        try { s.javaClass.getMethod("setMixedContentMode", Int::class.javaPrimitiveType).invoke(s, 0) } catch (_: Exception) {}

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null) return null; val url = request.url?.toString() ?: return null
                if (!isCacheable(url, request.method ?: "GET", request.requestHeaders)) return null
                return cacheReadResponse(url) ?: cacheFetchAndStore(url, request.requestHeaders)
            }
            @Suppress("DEPRECATION") override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                if (url == null || !isCacheable(url, "GET", null)) return null
                return cacheReadResponse(url) ?: cacheFetchAndStore(url, null)
            }
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean { view.loadUrl(url); return true }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) handleNetworkFailure(view)
            }
            @Suppress("DEPRECATION") override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (failingUrl == CURRENT_URL) handleNetworkFailure(view)
            }
            private fun handleNetworkFailure(view: WebView?) {
                if (isRetryingFlag) return
                val now = System.currentTimeMillis()
                if (now - lastFailureTime < 2000) return
                lastFailureTime = now
                isRetryingFlag = true; serverAwakeNotified = false
                hideLoadingAnimation()
                val html = """
                <html><head><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no"/>
                <style>*{box-sizing:border-box}html,body{margin:0;padding:0;width:100%;height:100%;background:linear-gradient(135deg,#0f0c29,#302b63,#24243e);overflow:hidden;font-family:-apple-system,sans-serif}.container{position:fixed;inset:0;display:flex;flex-direction:column;justify-content:center;align-items:center;padding:40px 30px}.emoji{font-size:70px;margin-bottom:15px;animation:bounce 2s ease-in-out infinite}@keyframes bounce{0%,100%{transform:translateY(0)}50%{transform:translateY(-10px)}}h2{color:#fff;font-size:20px;margin:0 0 8px;text-align:center}.subtitle{color:rgba(255,255,255,.65);font-size:13px;margin-bottom:30px;text-align:center}.spin{width:36px;height:36px;border:3px solid rgba(255,255,255,.15);border-top-color:#7c4dff;border-radius:50%;animation:spin 1s linear infinite;margin-bottom:25px}@keyframes spin{to{transform:rotate(360deg)}}.btn{display:flex;align-items:center;justify-content:center;gap:8px;width:85%;max-width:320px;padding:14px 20px;background:linear-gradient(135deg,#667eea,#764ba2);color:#fff;font-size:15px;border:none;border-radius:14px;box-shadow:0 8px 24px rgba(118,75,162,.35);cursor:pointer;text-decoration:none;margin-bottom:10px}.btn-secondary{background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.15)}.btn-awake{background:linear-gradient(135deg,#11998e,#38ef7d)}.btn-nuke{background:linear-gradient(135deg,#eb3349,#f45c43)}.btn-switch{background:linear-gradient(135deg,#fa709a,#fee140);color:#333}.status{margin-top:15px;color:rgba(255,255,255,.5);font-size:12px}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#ff9800;margin-right:6px;animation:pulse 1.2s ease-in-out infinite}.dot.green{background:#38ef7d}@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}</style></head>
                <body><div class="container"><div class="emoji">🐕</div><h2 id="title">酒馆暂时联系不上...</h2><p class="subtitle">小狗正在努力嗅气味汪～</p><div class="spin" id="spin"></div><a class="btn" href="$CURRENT_URL" onclick="openInBrowser(event)">🌐 用浏览器打开</a><button class="btn btn-secondary" id="retryBtn" onclick="manualRetry()">🔄 立即重试</button><button class="btn btn-switch" onclick="switchPortal()">🚪 换个狗洞试试</button><button class="btn btn-nuke" onclick="nukeReset()">💣 一键重置（等同重装）</button><div class="status"><span class="dot" id="statusDot"></span><span id="statusText">自动监测中...</span></div></div>
                <script>function openInBrowser(e){e.preventDefault();if(window.AndroidBridge)window.AndroidBridge.openInBrowser('$CURRENT_URL')}function manualRetry(){if(window.AndroidBridge)window.AndroidBridge.retryConnection()}function nukeReset(){if(window.AndroidBridge)window.AndroidBridge.confirmReset()}function switchPortal(){if(window.AndroidBridge)window.AndroidBridge.switchPortal()}function onServerAwake(){document.querySelector('.emoji').textContent='🎉';document.getElementById('title').textContent='酒馆醒啦！';document.getElementById('spin').style.display='none';var b=document.getElementById('retryBtn');b.textContent='🎉 立即回到酒馆';b.classList.add('btn-awake');document.getElementById('statusDot').classList.add('green');document.getElementById('statusText').textContent='已恢复'}</script></body></html>
                """.trimIndent()
                view?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                startServerHealthCheck()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isRetryingFlag) return
                if (url.isNullOrEmpty() || url.startsWith("data:") || url == "about:blank") return
                stopServerHealthCheck(); isRetryingFlag = false; serverAwakeNotified = false
                if (splashOverlay != null) hideLoadingAnimation()
                injectToolFolderWithRetries(view)
            }
        }
    }

    private fun injectToolFolderWithRetries(view: WebView?) {
        val target = view ?: webView
        try { target.evaluateJavascript(getInjectedJs(), null) } catch (_: Exception) {}

        // SillyTavern 有时会在页面刚完成后继续重绘 DOM，导致右侧小狗按钮被覆盖或丢失。
        // 所以这里补几次注入：已经存在时 JS 会自动跳过，不会重复生成。
        val delays = longArrayOf(350L, 900L, 1800L, 3200L)
        for (delay in delays) {
            mainHandler.postDelayed({
                try {
                    target.evaluateJavascript(
                        """
                        (function(){
                            try{
                                var old=document.querySelector('[data-st-tool-folder]');
                                if(old){
                                    var ver=old.getAttribute('data-st-tool-folder-version')||'';
                                    if(ver!=='v4-right-scrollbar-middle-52vh'){
                                        try{old.remove();}catch(_e){}
                                        try{var p=document.querySelector('[data-st-tool-panel]');if(p)p.remove();}catch(_e){}
                                        try{window.toolFolderInjected=false;}catch(_e){}
                                        return 'missing';
                                    }
                                    old.style.position='fixed';
                                    old.style.top='52vh';
                                    old.style.right='0px';
                                    old.style.width='72px';
                                    old.style.height='72px';
                                    old.style.zIndex='2147483000';
                                    old.style.display='block';
                                    old.style.visibility='visible';
                                    old.style.opacity='1';
                                    old.style.pointerEvents='none';
                                    old.style.transform='translateY(-50%) translateZ(0)';
                                    old.style.webkitTransform='translateY(-50%) translateZ(0)';
                                    try{var btn=old.firstElementChild;if(btn){btn.style.top='7px';btn.style.right='0px';}}catch(_e){}
                                    return 'exists-fixed';
                                }
                            }catch(e){}
                            return 'missing';
                        })();
                        """.trimIndent()
                    ) { result ->
                        if (result == null || result.contains("missing")) {
                            try { target.evaluateJavascript(getInjectedJs(), null) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }, delay)
        }
    }

    private fun getInjectedJs(): String = """
        window._st_manualStop=false;window.apiFinishReason='unknown';window._st_hasError=false;
        if(!window.blobDownloadHookedV2){window.blobDownloadHookedV2=true;
        function _stDownload(b,f){try{if(typeof b==='string'){if(b.startsWith('data:')){if(window.AndroidDownloader)window.AndroidDownloader.downloadBase64(b,f);return;}if(b.startsWith('blob:')){fetch(b).then(function(r){return r.blob();}).then(function(bl){_stDownload(bl,f);});return;}}else{var r=new FileReader();r.onload=function(){if(window.AndroidDownloader)window.AndroidDownloader.downloadBase64(r.result,f);};r.readAsDataURL(b);}}catch(e){}}
        var oc=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){try{var h=this.href||'';var d=this.getAttribute('download');if(d||h.startsWith('blob:')||h.startsWith('data:')){var fn=d||(h.split('/').pop().split('?')[0])||('download_'+Date.now());if(h.startsWith('blob:')||h.startsWith('data:')){_stDownload(h,fn);return;}}}catch(e){}return oc.apply(this,arguments);};
        document.addEventListener('click',function(e){var t=e.target;while(t&&t.tagName!=='A')t=t.parentElement;if(!t||t.tagName!=='A')return;var h=t.href||'';var d=t.getAttribute('download');if(!d&&!h.startsWith('blob:')&&!h.startsWith('data:'))return;var fn=d||(h.split('/').pop().split('?')[0])||('download_'+Date.now());if(h.startsWith('blob:')||h.startsWith('data:')){e.preventDefault();e.stopPropagation();_stDownload(h,fn);}},true);}
        

        if(!window.stMobilePopupFixSafeV1){window.stMobilePopupFixSafeV1=true;
        try{
        var st=document.createElement('style');st.setAttribute('data-st-mobile-popup-fix','1');
        st.textContent=
        '#dialogue_popup,.dialogue_popup,.popup,.popup-content,.modal,.modal-content,.ui-dialog,.drawer-content{max-width:100vw!important;box-sizing:border-box!important;}'+
        '#dialogue_popup *,.dialogue_popup *,.popup *,.modal *,.drawer-content *{box-sizing:border-box!important;}'+
        '.chat-history-item,.chat-history-entry,.chat_select,.backup_item,.chat_item,.list-group-item{max-width:100%!important;white-space:normal!important;word-break:break-word!important;overflow-wrap:anywhere!important;}'+
        '.chat-history-item *,.chat-history-entry *,.chat_select *,.backup_item *,.chat_item *,.list-group-item *{overflow-wrap:anywhere!important;}'+
        'input[type="checkbox"],button,.menu_button,.menu_button_icon,[role="button"]{touch-action:manipulation!important;}';
        document.documentElement.appendChild(st);
        }catch(e){}
        }

        window._stShowNovel=function(){
            try{
                if(window.AndroidBridge && window.AndroidBridge.openNovelFileImporter){
                    window.AndroidBridge.openNovelFileImporter();
                }else{
                    alert('小说化导入器还没准备好，请稍后再试');
                }
            }catch(e){alert('打开小说化导入器失败：'+e.message);}
        };

        if(!window.toolFolderInjected){window.toolFolderInjected=true;
        var folder=document.createElement('div');folder.setAttribute('data-st-tool-folder','1');folder.setAttribute('data-st-tool-folder-version','v4-right-scrollbar-middle-52vh');
        folder.style.cssText='position:fixed;top:52vh;right:0;width:72px;height:72px;z-index:2147483000;display:block;pointer-events:none;contain:layout style paint;isolation:isolate;transform:translateY(-50%) translateZ(0);-webkit-transform:translateY(-50%) translateZ(0);backface-visibility:hidden;-webkit-backface-visibility:hidden;';
        var trigger=document.createElement('div');trigger.innerHTML='<div style="position:relative;z-index:2;font-size:27px;line-height:1;transform:translateX(-3px);filter:drop-shadow(0 2px 3px rgba(0,0,0,.45));">🐾</div>';
        trigger.style.cssText='position:absolute;right:0;top:7px;pointer-events:auto;width:62px;height:62px;display:flex;align-items:center;justify-content:center;background:linear-gradient(145deg,rgba(28,28,38,0.82),rgba(12,13,18,0.78));backdrop-filter:blur(18px) saturate(1.25);-webkit-backdrop-filter:blur(18px) saturate(1.25);color:white;border:1px solid rgba(255,255,255,0.20);border-right:0;font-size:27px;border-radius:31px 0 0 31px;box-shadow:-8px 10px 26px rgba(0,0,0,0.42),inset 0 1px 0 rgba(255,255,255,0.25),inset 0 -10px 22px rgba(118,91,255,0.10);cursor:pointer;transform:translateX(38%) translateZ(0);transition:transform 0.2s cubic-bezier(0.2,0.85,0.25,1),background 0.18s ease,box-shadow 0.18s ease,border-color 0.18s ease;will-change:transform;overflow:hidden;backface-visibility:hidden;-webkit-backface-visibility:hidden;';
        var shine=document.createElement('div');shine.style.cssText='position:absolute;left:8px;top:7px;width:28px;height:13px;border-radius:999px;background:linear-gradient(90deg,rgba(255,255,255,.32),rgba(255,255,255,0));opacity:.65;pointer-events:none;';
        trigger.appendChild(shine);
        var overlay=document.createElement('div');overlay.setAttribute('data-st-tool-panel','1');
        overlay.style.cssText='position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:2147483400;display:none;opacity:0;pointer-events:none;background:radial-gradient(circle at 86% 50%,rgba(124,91,255,0.26),rgba(0,0,0,0.42) 38%,rgba(0,0,0,0.55) 100%);backdrop-filter:blur(7px);-webkit-backdrop-filter:blur(7px);transition:opacity 0.18s ease;box-sizing:border-box;';
        var panel=document.createElement('div');
        panel.style.cssText='position:absolute;right:18px;top:50%;transform:translateY(-50%) scale(0.94);width:88vw;max-width:392px;max-height:80vh;overflow:hidden;border-radius:30px;background:linear-gradient(160deg,rgba(31,33,45,0.94),rgba(18,18,26,0.96) 52%,rgba(10,11,16,0.98));border:1px solid rgba(255,255,255,0.18);box-shadow:0 26px 70px rgba(0,0,0,0.62),0 0 0 1px rgba(216,192,141,0.10) inset,inset 0 1px 0 rgba(255,255,255,0.22);color:#fff;box-sizing:border-box;transition:transform 0.2s cubic-bezier(0.2,0.85,0.25,1);';
        var glow=document.createElement('div');glow.style.cssText='position:absolute;inset:-80px -80px auto auto;width:190px;height:190px;border-radius:999px;background:radial-gradient(circle,rgba(130,105,255,.46),rgba(130,105,255,0) 68%);pointer-events:none;opacity:.82;';
        var goldLine=document.createElement('div');goldLine.style.cssText='position:absolute;left:22px;right:22px;top:0;height:1px;background:linear-gradient(90deg,rgba(216,192,141,0),rgba(216,192,141,.72),rgba(255,255,255,.35),rgba(216,192,141,0));pointer-events:none;';
        var head=document.createElement('div');
        head.style.cssText='position:relative;display:flex;align-items:center;gap:12px;padding:18px 18px 12px 18px;box-sizing:border-box;';
        var logo=document.createElement('div');logo.innerHTML='🐾';logo.style.cssText='width:48px;height:48px;border-radius:17px;display:flex;align-items:center;justify-content:center;background:linear-gradient(135deg,#7c5cff,#6ed6ff);font-size:24px;box-shadow:0 12px 28px rgba(124,92,255,0.34),inset 0 1px 0 rgba(255,255,255,.42);';
        var titleBox=document.createElement('div');titleBox.style.cssText='flex:1;min-width:0;';
        titleBox.innerHTML='<div style="font-size:12px;letter-spacing:5px;color:rgba(216,192,141,.86);font-weight:800;margin-bottom:4px;white-space:nowrap;">SYSTEM TOOLS</div><div style="font-size:19px;font-weight:900;letter-spacing:.6px;white-space:nowrap;">小狗快捷面板</div><div style="font-size:11px;color:rgba(255,255,255,0.56);margin-top:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">选择功能后自动收起</div>';
        var closeBtn=document.createElement('div');closeBtn.innerHTML='×';closeBtn.style.cssText='width:38px;height:38px;border-radius:14px;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,0.075);border:1px solid rgba(255,255,255,0.13);font-size:30px;line-height:1;cursor:pointer;color:#fff;box-shadow:inset 0 1px 0 rgba(255,255,255,.14);';
        head.appendChild(logo);head.appendChild(titleBox);head.appendChild(closeBtn);
        var chip=document.createElement('div');chip.style.cssText='position:relative;margin:0 18px 12px 18px;padding:9px 12px;border-radius:16px;background:rgba(255,255,255,0.055);border:1px solid rgba(255,255,255,0.10);color:rgba(255,255,255,.68);font-size:11px;line-height:1.35;box-sizing:border-box;';
        chip.innerHTML='轻点空白处 / 右上角 × / 再点悬浮球，都可以关闭面板';
        var grid=document.createElement('div');
        grid.style.cssText='position:relative;display:grid;grid-template-columns:1fr 1fr;gap:11px;padding:0 16px 16px 16px;box-sizing:border-box;max-height:calc(80vh - 132px);overflow-y:auto;-webkit-overflow-scrolling:touch;';
        var foot=document.createElement('div');foot.style.cssText='position:relative;margin:0 16px 16px 16px;padding:10px 12px;border-radius:18px;text-align:center;color:rgba(255,255,255,.50);font-size:10px;letter-spacing:.8px;background:linear-gradient(90deg,rgba(255,255,255,.045),rgba(255,255,255,.02));border:1px solid rgba(255,255,255,.07);';foot.innerHTML='DOG BONE TAVERN · QUICK ACCESS';
        panel.appendChild(glow);panel.appendChild(goldLine);panel.appendChild(head);panel.appendChild(chip);panel.appendChild(grid);panel.appendChild(foot);overlay.appendChild(panel);
        var isOpen=false;var lastActivity=Date.now();
        function makeItem(emoji,label,sub,grad,onTap){
            var parts=grad.split(',');var a=parts[0]||'#7c5cff';var b=parts[1]||'#6ed6ff';
            var item=document.createElement('button');item.type='button';
            item.style.cssText='appearance:none;-webkit-appearance:none;border:1px solid rgba(255,255,255,0.10);outline:none;text-align:left;min-height:96px;border-radius:22px;padding:12px;color:#fff;background:linear-gradient(160deg,rgba(255,255,255,0.095),rgba(255,255,255,0.035));box-shadow:0 12px 28px rgba(0,0,0,0.28),inset 0 1px 0 rgba(255,255,255,0.13);cursor:pointer;box-sizing:border-box;overflow:hidden;position:relative;touch-action:manipulation;';
            item.innerHTML='<div style="position:absolute;right:-20px;top:-24px;width:82px;height:82px;border-radius:999px;background:radial-gradient(circle,'+a+'66,rgba(255,255,255,0) 68%);pointer-events:none;"></div><div style="position:relative;display:flex;align-items:center;gap:9px;margin-bottom:10px;"><div style="width:38px;height:38px;border-radius:15px;display:flex;align-items:center;justify-content:center;background:linear-gradient(135deg,'+a+','+b+');font-size:22px;box-shadow:0 8px 20px '+a+'55,inset 0 1px 0 rgba(255,255,255,.30);">'+emoji+'</div><div style="width:5px;height:24px;border-radius:8px;background:linear-gradient(180deg,'+a+','+b+');opacity:.95;"></div></div><div style="position:relative;font-size:15px;font-weight:900;line-height:1.15;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">'+label+'</div><div style="position:relative;font-size:10px;line-height:1.35;color:rgba(255,255,255,0.66);margin-top:5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">'+sub+'</div>';
            item.addEventListener('touchstart',function(){try{item.style.transform='scale(.985)';}catch(e){}},{passive:true});
            item.addEventListener('touchend',function(){try{item.style.transform='scale(1)';}catch(e){}},{passive:true});
            item.addEventListener('click',function(e){e.stopPropagation();lastActivity=Date.now();try{collapse(true);}catch(_e){}setTimeout(function(){try{onTap();}catch(err){console.error(err);}},80);});
            return item;
        }
        function buildItems(){grid.innerHTML='';var so=false;try{so=window.AndroidBridge&&window.AndroidBridge.getSoundState();}catch(e){}
        grid.appendChild(makeItem(so?'🔊':'🔇',so?'声音开':'声音关','回复提醒音开关','#7c5cff,#6ed6ff',function(){if(window.AndroidBridge)window.AndroidBridge.toggleSound();}));
        var hs=false;try{hs=window.AndroidBridge&&window.AndroidBridge.hasShotStart();}catch(e){}
        grid.appendChild(makeItem(hs?'🏁':'📸',hs?'完成截图':'长截图',hs?'结束并保存长图':'标记起点开始截','#ff64b4,#ff8a6b',function(){var chat=document.getElementById('chat');if(!chat){if(window.AndroidBridge)window.AndroidBridge.toastNoChat();return;}if(window.AndroidBridge.hasShotStart())window.AndroidBridge.markShotEndAndCapture(chat.scrollTop,chat.clientHeight);else window.AndroidBridge.markShotStart(chat.scrollTop,chat.clientHeight);}));
        grid.appendChild(makeItem('🚀','加速器','缓存状态 / 清理','#ffb86c,#ffe066',function(){if(window.AndroidBridge)window.AndroidBridge.showCacheStats();}));
        grid.appendChild(makeItem('📖','小说化','导入聊天记录','#a855f7,#5b7cfa',function(){if(window._stShowNovel)window._stShowNovel();else alert('小说化工具还在加载，稍后再试汪～');}));
        grid.appendChild(makeItem('🚪','换狗洞','切换酒馆入口','#f6d365,#fda085',function(){if(window.AndroidBridge)window.AndroidBridge.switchPortal();}));
        grid.appendChild(makeItem('💣','重置酒馆','清空后重开','#ff3b5f,#ff7a59',function(){if(window.AndroidBridge)window.AndroidBridge.confirmReset();}));}
        function expand(){isOpen=true;lastActivity=Date.now();buildItems();overlay.style.display='block';overlay.style.pointerEvents='auto';trigger.innerHTML='<div style="position:relative;z-index:2;font-size:31px;line-height:1;transform:translateX(-2px);filter:drop-shadow(0 2px 3px rgba(0,0,0,.35));">×</div>';trigger.appendChild(shine);trigger.style.transform='translateX(0) translateZ(0)';trigger.style.background='linear-gradient(135deg,#7c5cff,#6ed6ff)';trigger.style.borderColor='rgba(255,255,255,0.38)';trigger.style.boxShadow='-8px 10px 28px rgba(124,92,255,0.42),inset 0 1px 0 rgba(255,255,255,0.32)';try{requestAnimationFrame(function(){overlay.style.opacity='1';panel.style.transform='translateY(-50%) scale(1)';});}catch(e){overlay.style.opacity='1';panel.style.transform='translateY(-50%) scale(1)';}}
        function collapse(force){isOpen=false;overlay.style.opacity='0';overlay.style.pointerEvents='none';panel.style.transform='translateY(-50%) scale(0.94)';trigger.innerHTML='<div style="position:relative;z-index:2;font-size:27px;line-height:1;transform:translateX(-3px);filter:drop-shadow(0 2px 3px rgba(0,0,0,.45));">🐾</div>';trigger.appendChild(shine);trigger.style.transform='translateX(38%) translateZ(0)';trigger.style.background='linear-gradient(145deg,rgba(28,28,38,0.82),rgba(12,13,18,0.78))';trigger.style.borderColor='rgba(255,255,255,0.20)';trigger.style.boxShadow='-8px 10px 26px rgba(0,0,0,0.42),inset 0 1px 0 rgba(255,255,255,0.25),inset 0 -10px 22px rgba(118,91,255,0.10)';setTimeout(function(){if(!isOpen){overlay.style.display='none';grid.innerHTML='';}},force?45:180);}
        function hardCollapse(){collapse(true);}
        window._stCollapseFolder=collapse;
        window._stHardCollapseFolder=hardCollapse;
        var dM=false,dY=0,dIT=0,lastTouchToggle=0;
        trigger.addEventListener('touchstart',function(e){dM=false;dY=e.touches[0].clientY;dIT=folder.getBoundingClientRect().top;lastActivity=Date.now();},{passive:true});
        trigger.addEventListener('touchmove',function(e){var dy=e.touches[0].clientY-dY;if(Math.abs(dy)>8){dM=true;var nt=(dIT+dy);var minT=76;var maxT=Math.max(90,window.innerHeight-96);folder.style.top=Math.max(minT,Math.min(maxT,nt))+'px';e.preventDefault();}},{passive:false});
        trigger.addEventListener('touchend',function(e){lastTouchToggle=Date.now();if(!dM){if(isOpen)collapse();else expand();}try{e.preventDefault();}catch(_e){}},{passive:false});
        trigger.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();if(Date.now()-lastTouchToggle<350)return;if(isOpen)collapse();else expand();});
        overlay.addEventListener('click',function(e){if(e.target===overlay)collapse(true);});
        panel.addEventListener('click',function(e){e.stopPropagation();});
        closeBtn.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();collapse(true);});
        function collapseBecauseViewportMoves(){if(isOpen)hardCollapse();}
        try{window.addEventListener('resize',collapseBecauseViewportMoves,{passive:true});}catch(e){}
        try{window.addEventListener('orientationchange',hardCollapse,{passive:true});}catch(e){}
        try{if(window.visualViewport){window.visualViewport.addEventListener('resize',collapseBecauseViewportMoves,{passive:true});window.visualViewport.addEventListener('scroll',collapseBecauseViewportMoves,{passive:true});}}catch(e){}
        setInterval(function(){if(isOpen&&Date.now()-lastActivity>14000)collapse();},1000);
        folder.appendChild(trigger);document.body.appendChild(folder);document.body.appendChild(overlay);}

        if(!window.cardSelectionInjected){window.cardSelectionInjected=true;
        var cardBtn=document.createElement('div');cardBtn.setAttribute('data-st-card-btn','1');cardBtn.innerHTML='🔖 生成卡片';
        cardBtn.style.cssText='position:fixed;display:none;z-index:99999;background:linear-gradient(135deg,#667eea,#764ba2);color:#fff;padding:8px 14px;border-radius:20px;font-size:13px;font-weight:600;cursor:pointer;user-select:none;box-shadow:0 4px 16px rgba(118,75,162,0.5);white-space:nowrap;';
        document.body.appendChild(cardBtn);
        function findAi(n){var e=(n&&n.nodeType===1)?n:(n?n.parentElement:null);while(e&&e!==document.body){if(e.classList&&e.classList.contains('mes')){if(e.getAttribute('is_user')==='true')return null;return e;}e=e.parentElement;}return null;}
        function upd(){var s=window.getSelection();var t=s?s.toString().trim():'';if(!t||t.length<2){cardBtn.style.display='none';return;}var m=findAi(s.anchorNode);if(!m){cardBtn.style.display='none';return;}try{var r=s.getRangeAt(0).getBoundingClientRect();var top=r.bottom+8,left=r.left+r.width/2-60;if(top+50>window.innerHeight)top=r.top-44;if(left<8)left=8;if(left+130>window.innerWidth)left=window.innerWidth-138;cardBtn.style.left=left+'px';cardBtn.style.top=top+'px';cardBtn.style.display='block';cardBtn._m=m;cardBtn._t=t;}catch(e){cardBtn.style.display='none';}}
        document.addEventListener('selectionchange',function(){setTimeout(upd,50);});
        window.addEventListener('scroll',function(){cardBtn.style.display='none';},true);
        function showStyle(text,mes){try{window.getSelection().removeAllRanges();}catch(e){}cardBtn.style.display='none';
        var ow=document.getElementById('st-pw');if(ow)ow.remove();
        var w=document.createElement('div');w.id='st-pw';w.style.cssText='position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:2147483647;background:rgba(0,0,0,0.78);';
        var p=document.createElement('div');p.style.cssText='position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);background:linear-gradient(135deg,#1a1a2e,#16213e,#0f3460);color:#fff;padding:24px 22px;border-radius:20px;width:88vw;max-width:360px;max-height:88vh;overflow-y:auto;box-sizing:border-box;';
        var h='<div style="font-size:18px;font-weight:700;margin-bottom:12px;text-align:center;">✨ 选择卡片风格</div>';
        var ss=[{i:0,e:'🌙',n:'暮光紫'},{i:1,e:'🌸',n:'樱花粉'},{i:2,e:'☕',n:'暖茶棕'},{i:3,e:'🌊',n:'月光蓝'},{i:4,e:'🌅',n:'晚霞橙'},{i:5,e:'🏔',n:'墨韵青'}];
        ss.forEach(function(s){h+='<button data-i="'+s.i+'" class="sb" style="display:flex;align-items:center;gap:12px;width:100%;padding:14px 16px;margin:0 0 8px 0;border:1px solid rgba(255,255,255,0.08);border-radius:14px;background:rgba(255,255,255,0.05);color:#fff;font-size:14px;cursor:pointer;box-sizing:border-box;">'+'<span style="font-size:24px;">'+s.e+'</span><span style="flex:1;text-align:left;">'+s.n+'</span></button>';});
        h+='<button id="sc" style="display:block;width:100%;padding:11px;margin-top:6px;border:1px solid rgba(255,255,255,0.15);border-radius:14px;background:rgba(255,255,255,0.04);color:#fff;font-size:14px;cursor:pointer;box-sizing:border-box;">取消</button>';
        p.innerHTML=h;w.appendChild(p);document.documentElement.appendChild(w);
        function close(){try{w.remove();}catch(e){}}
        w.addEventListener('click',function(e){if(e.target===w)close();});
        p.querySelector('#sc').onclick=close;
        p.querySelectorAll('.sb').forEach(function(b){b.onclick=function(){var i=parseInt(b.getAttribute('data-i'));var nn=mes.querySelector('.ch_name .name_text')||mes.querySelector('.name_text')||mes.querySelector('.ch_name');var cn=nn?(nn.innerText||'').trim():'';var ai=mes.querySelector('.avatar img')||mes.querySelector('img');var au=ai?(ai.src||''):'';if(window.AndroidBridge)window.AndroidBridge.generatePosterCard(text,cn,au,i);close();};});}
        cardBtn.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();if(cardBtn._t&&cardBtn._m)showStyle(cardBtn._t,cardBtn._m);});
        cardBtn.addEventListener('touchend',function(e){e.preventDefault();e.stopPropagation();if(cardBtn._t&&cardBtn._m)showStyle(cardBtn._t,cardBtn._m);},{passive:false});}

        if(!window.networkHooked){window.networkHooked=true;var of=window.fetch;
        window.fetch=async function(){var u=(typeof arguments[0]==='string')?arguments[0]:(arguments[0]&&arguments[0].url?arguments[0].url:'');
        if(u.indexOf('generate')>=0||u.indexOf('completions')>=0||u.indexOf('chat')>=0)window.apiFinishReason='unknown';
        var res=await of.apply(this,arguments);
        if(u.indexOf('generate')>=0||u.indexOf('completions')>=0||u.indexOf('chat')>=0){var c=res.clone();
        (async function(){try{var r=c.body.getReader();var d=new TextDecoder('utf-8');var done=false;
        while(!done){var x=await r.read();done=x.done;if(x.value){var ck=d.decode(x.value,{stream:true});
        if(ck.indexOf('"finish_reason":"length"')>=0)window.apiFinishReason='length';
        else if(ck.indexOf('"finish_reason":"stop"')>=0||ck.indexOf('"finish_reason":"eos_token"')>=0)window.apiFinishReason='stop';}}}catch(e){}})();}
        return res;};}

        if(!window._st_eo){window._st_eo=true;
        new MutationObserver(function(ms){ms.forEach(function(m){m.addedNodes.forEach(function(n){
        if(n.nodeType===1){var et=n.classList&&n.classList.contains('toast-error')?n:(n.querySelector?n.querySelector('.toast-error'):null);
        if(et&&!et._h){et._h=true;var mn=et.querySelector('.toast-message');if(mn&&window.AndroidBridge){window._st_hasError=true;window.AndroidBridge.onError(mn.innerText);}}}});});}).observe(document.body,{childList:true,subtree:true});}

        var ob=new MutationObserver(function(){if(window.SillyTavern&&typeof window.SillyTavern.getContext==='function'){
        var ctx=window.SillyTavern.getContext();if(ctx&&ctx.eventSource&&!window._st_h){window._st_h=true;var es=ctx.eventSource;
        es.on('generation_started',function(){window._st_manualStop=false;window.apiFinishReason='unknown';window._st_hasError=false;});
        es.on('generation_stopped',function(){window._st_manualStop=true;});
        es.on('generation_ended',function(){Promise.resolve().then(function(){if(window._st_hasError){window._st_hasError=false;return;}
        var c=window.SillyTavern.getContext();var ca=(c&&c.chat)?c.chat:window.chat;var t='';
        if(ca&&ca.length>0){var am=ca.filter(function(m){return m.is_user!==true});if(am.length>0)t=am[am.length-1].mes||'';}
        t=t.replace(/<[^>]+>/g,'').replace(/[\s\r\n\u200B-\u200D\uFEFF]+$/,'');
        var ms=window._st_manualStop===true;var r=window.apiFinishReason||'unknown';var ie=t==='';
        if(ie){if(window.AndroidBridge)window.AndroidBridge.notifyAiReplyEmpty();return;}
        if(ms||r==='length'){if(window.AndroidBridge)window.AndroidBridge.notifyAiReplyTruncated();return;}
        if(r==='stop'){if(window.AndroidBridge)window.AndroidBridge.notifyAiReplyDone();return;}
        var lc=t.slice(-1);var ve=['.','!','?','。','！','？','"','\u201d','\u2019','~','*',']',')','}','-','\u2026'];
        if(ve.indexOf(lc)>=0){if(window.AndroidBridge)window.AndroidBridge.notifyAiReplyDone();}
        else{if(window.AndroidBridge)window.AndroidBridge.notifyAiReplyTruncated();}});});ob.disconnect();}}});
        ob.observe(document,{childList:true,subtree:true});
    """.trimIndent()

    override fun onResume() {
        super.onResume(); isAppInForeground = true
        try { webView.onResume(); webView.resumeTimers() } catch (_: Exception) {}
        applyDisplayMode("normal")
        if (Settings.canDrawOverlays(this)) {
            try { val intent = Intent(this, FloatingService::class.java); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent) } catch (_: Exception) {}
        }
    }
    override fun onStop() { super.onStop(); isAppInForeground = false }
    override fun onDestroy() {
        super.onDestroy(); stopServerHealthCheck()
        loadingDogAnim?.cancel()
        try { translator?.close() } catch (_: Exception) {}
        try { webView.destroy() } catch (_: Exception) {}
    }
}