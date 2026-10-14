package com.ranzxm.browserak

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private data class Media(val url: String, val mime: String, val referer: String)
    private data class Tab(@Volatile var url: String = HOME, var title: String = "Tab baru", var state: Bundle? = null,
                           val media: LinkedHashMap<String, Media> = linkedMapOf(), var blocked: Int = 0)
    private val tabs = mutableListOf(Tab())
    private var selected = 0
    private var web: WebView? = null
    private lateinit var root: LinearLayout
    private lateinit var container: FrameLayout
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabButton: Button
    private lateinit var grabButton: Button
    private lateinit var protection: Protection
    private lateinit var scripts: Userscripts
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("browser", MODE_PRIVATE) }
    private var privateMode = false
    private var uploadCallback: ValueCallback<Array<Uri>>? = null
    private var exportFile: File? = null
    private var homeMessage: TextView? = null
    private val tab get() = tabs[selected]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protection = Protection(this)
        scripts = Userscripts(this)
        buildUi()
        val launchUrl = intent?.dataString?.takeIf(::isWebUrl)
        if (launchUrl != null) tab.url = launchUrl
        else if (savedInstanceState?.getBoolean("private") != true && !prefs.getBoolean("private_active", false)) {
            val saved = prefs.getString("tabs", null)
            if (saved != null) runCatching {
                val array = JSONArray(saved)
                val restored = (0 until minOf(array.length(), 12)).mapNotNull { i ->
                    array.optString(i).takeIf { it == HOME || isWebUrl(it) }?.let { Tab(it) }
                }
                if (restored.isNotEmpty()) { tabs.clear(); tabs.addAll(restored) }
            }
        }
        if (savedInstanceState?.getBoolean("private") == true || prefs.getBoolean("private_active", false)) {
            clearSession { privateMode = true; prefs.edit().putBoolean("private_active", true).apply(); showTab() }
        }
        else showTab()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
    }

    private fun buildUi() {
        val dark = prefs.getBoolean("dark", false)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (dark) 0xff161d19.toInt() else 0xfff3f6f4.toInt())
            if (Build.VERSION.SDK_INT >= 35) setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val top = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        address = EditText(this).apply {
            setSingleLine(); textSize = 15f
            setTextColor(if (dark) Color.WHITE else Color.BLACK)
            hint = "Cari atau masukkan URL"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) { navigate(text.toString()); true } else false
            }
        }
        top.addView(address, LinearLayout.LayoutParams(0, dp(52), 1f))
        top.addView(button("Go", "Buka alamat") { navigate(address.text.toString()) })
        root.addView(top)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(this)
        bottom.addView(button("‹", "Kembali") { back() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        bottom.addView(button("⌂", "Beranda") { web?.loadUrl(HOME) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        grabButton = button("Grab", "Media terdeteksi") { scanMedia() }
        bottom.addView(grabButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        tabButton = button("1", "Daftar tab") { showTabs() }
        bottom.addView(tabButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        bottom.addView(button("⋮", "Menu browser") { showMenu() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(bottom)
        setContentView(root)
    }

    private fun button(label: String, description: String = label, action: () -> Unit) = Button(this).apply {
        text = label; contentDescription = description; minWidth = 0; minimumWidth = 0
        setPadding(dp(4), 0, dp(4), 0); setOnClickListener { action() }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun showTab() {
        container.removeAllViews()
        val current = tab
        val view = WebView(this)
        homeMessage = null
        web = view
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
            if (Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = prefs.getBoolean("dark", false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) {
                progress.progress = value
                progress.visibility = if (value == 100) View.INVISIBLE else View.VISIBLE
            }
            override fun onReceivedTitle(view: WebView, title: String?) { current.title = title ?: "Tab" }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                uploadCallback?.onReceiveValue(null)
                uploadCallback = callback
                try { startActivityForResult(params.createIntent(), 20) }
                catch (_: android.content.ActivityNotFoundException) { uploadCallback = null; callback.onReceiveValue(null); toast("Pemilih file tidak tersedia") }
                return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (isWebUrl(url)) return false
                toast("Skema URL tidak didukung")
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                homeMessage?.let { container.removeView(it) }; homeMessage = null
                current.url = url; current.media.clear(); current.blocked = 0
                address.setText(if (url == HOME) "" else url)
                updateCounts()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) toast("Halaman gagal dimuat: ${error.description}")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) toast("HTTP ${response.statusCode}")
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (view.url != url) return
                if (url == HOME) renderHome()
                else {
                    if (!privateMode) {
                        remember("history", current.title, url, 150)
                        // ponytail: WebView has no native document-start hook; run both phases at document-end until a supported hook is added.
                        try {
                            val js = scripts.javascript(url, "document-start") + ";\n" + scripts.javascript(url, "document-end")
                            if (js.isNotEmpty()) view.evaluateJavascript(js, null)
                        } catch (e: Exception) { toast("Userscript gagal: ${e.message}") }
                    }
                    val css = protection.css(url)
                    if (css.isNotEmpty()) view.evaluateJavascript("(()=>{let s=document.getElementById('browserak-filter');if(!s){s=document.createElement('style');s.id='browserak-filter';document.documentElement.appendChild(s)}s.textContent=${JSONObject.quote(css)}})()", null)
                }
                saveTabs()
                updateCounts()
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!request.isForMainFrame && protection.blocked(url, current.url)) {
                    handler.post { current.blocked++; if (tab === current) updateCounts() }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (request.method == "GET" && isMedia(url)) recordMedia(current, Media(url, "", current.url))
                return null
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                container.removeView(view); view.destroy(); web = null
                toast("WebView berhenti; muat ulang tab")
                container.addView(button("Muat ulang") { showTab() })
                return true
            }
        }
        view.setDownloadListener { url, _, disposition, mime, _ ->
            if (!isWebUrl(url)) toast("Unduhan blob/data belum didukung")
            else {
                recordMedia(current, Media(url, mime ?: "", current.url))
                confirmDownload(Media(url, mime ?: "", current.url), URLUtil.guessFileName(url, disposition, mime))
            }
        }
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        if (current.state == null || view.restoreState(current.state!!) == null) view.loadUrl(current.url)
        updateCounts()
    }

    private fun renderHome() {
        if (homeMessage != null) return
        val dark = prefs.getBoolean("dark", false)
        homeMessage = TextView(this).apply {
            text = "Browserak\n\n${if (privateMode) "Sesi privat · riwayat tidak disimpan" else "Ringan. Fokus. Kendali di tanganmu."}\n\nGunakan kolom alamat untuk mencari.\nGrab untuk media · Menu untuk proteksi dan script."
            textSize = 20f; gravity = android.view.Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0)
            setTextColor(if (dark) Color.WHITE else 0xff183d2d.toInt())
            setBackgroundColor(if (dark) 0xff161d19.toInt() else 0xfff3f6f4.toInt())
            setOnClickListener { address.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT) }
        }
        container.addView(homeMessage, FrameLayout.LayoutParams(-1, -1))
    }

    private fun navigate(input: String) {
        val value = input.trim()
        if (value.isEmpty()) return
        val url = when {
            isWebUrl(value) -> value
            !value.contains(' ') && value.contains('.') && !value.contains("://") -> "https://$value"
            else -> "https://duckduckgo.com/?q=${Uri.encode(value)}"
        }
        if (!isWebUrl(url)) { toast("URL tidak valid"); return }
        address.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        web?.loadUrl(url)
    }

    private fun releaseTab(save: Boolean = true) {
        web?.let { view ->
            if (save) { tab.state = Bundle(); view.saveState(tab.state!!) }
            container.removeView(view); view.stopLoading(); view.destroy()
        }
        web = null
    }

    private fun showTabs() {
        val labels = tabs.mapIndexed { i, t -> "${if (i == selected) "• " else ""}${t.title}" } + listOf("+ Tab baru", "Tutup tab aktif")
        AlertDialog.Builder(this).setTitle("Tab · hanya satu WebView aktif")
            .setItems(labels.toTypedArray()) { _, index ->
                when {
                    index < tabs.size -> { releaseTab(); selected = index; showTab() }
                    index == tabs.size -> {
                        if (tabs.size >= 12) toast("Maksimal 12 tab untuk menjaga RAM")
                        else { releaseTab(); tabs.add(Tab()); selected = tabs.lastIndex; showTab() }
                    }
                    else -> {
                        releaseTab(false); tabs.removeAt(selected)
                        if (tabs.isEmpty()) tabs.add(Tab())
                        selected = selected.coerceAtMost(tabs.lastIndex); showTab()
                    }
                }
            }.show()
    }

    private fun updateCounts() {
        tabButton.text = "${tabs.size}"
        grabButton.text = "Grab ${tab.media.size}"
        address.hint = if (privateMode) "Privat · cari atau URL" else "Cari atau masukkan URL"
    }

    private fun showMenu() {
        val host = Uri.parse(tab.url).host ?: ""
        val choices = arrayOf("Muat ulang", "Maju", "Bookmark halaman", "Bookmark", "Riwayat",
            "Unduhan", "Adblock: ${if (protection.enabled(host)) "aktif" else "nonaktif"} · ${tab.blocked} diblokir",
            "Perbarui filter iklan", "Userscript", if (privateMode) "Keluar sesi privat" else "Mulai sesi privat",
            "Tema ${if (prefs.getBoolean("dark", false)) "terang" else "gelap"}")
        AlertDialog.Builder(this).setItems(choices) { _, i -> when (i) {
            0 -> web?.reload()
            1 -> if (web?.canGoForward() == true) web?.goForward()
            2 -> if (isWebUrl(tab.url) && !privateMode) { remember("bookmarks", tab.title, tab.url, 300); toast("Bookmark disimpan") }
            3 -> showSaved("bookmarks", "Bookmark")
            4 -> if (privateMode) toast("Riwayat dinonaktifkan pada sesi privat") else showSaved("history", "Riwayat")
            5 -> showDownloads()
            6 -> { if (host.isNotEmpty()) { protection.toggle(host); web?.reload() } }
            7 -> { toast("Memperbarui filter…"); protection.update { message -> runOnUiThread { if (!isDestroyed) toast(message) } } }
            8 -> showScripts()
            9 -> switchPrivate()
            10 -> {
                prefs.edit().putBoolean("dark", !prefs.getBoolean("dark", false)).apply()
                releaseTab(); buildUi(); showTab()
            }
        } }.show()
    }

    private fun switchPrivate() {
        AlertDialog.Builder(this).setTitle(if (privateMode) "Keluar sesi privat?" else "Mulai sesi privat?")
            .setMessage("Semua tab ditutup. Cookie, login, cache, penyimpanan situs dihapus saat masuk/keluar. WebView tidak menyediakan isolasi cookie per tab. Userscript dan unduhan dinonaktifkan pada sesi privat. Situs/jaringan tetap dapat melihat aktivitas.")
            .setPositiveButton("Lanjut") { _, _ ->
                saveTabs(); releaseTab(false)
                // Persist before clearing: interrupted cleanup must be repeated after process death.
                prefs.edit().putBoolean("private_active", true).commit()
                clearSession {
                    privateMode = !privateMode
                    prefs.edit().putBoolean("private_active", privateMode).apply()
                    tabs.clear(); tabs.add(Tab()); selected = 0; showTab()
                }
            }.setNegativeButton("Batal", null).show()
    }

    private fun clearSession(done: () -> Unit) {
        web?.clearCache(true)
        WebView(this).apply { clearCache(true); clearHistory(); clearFormData(); destroy() }
        WebStorage.getInstance().deleteAllData()
        android.webkit.WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); done() }
    }

    private fun remember(key: String, title: String, url: String, limit: Int) {
        if (!isWebUrl(url)) return
        val old = JSONArray(prefs.getString(key, "[]"))
        val next = JSONArray().put(JSONObject().put("title", title).put("url", url))
        for (i in 0 until old.length()) {
            val row = old.optJSONObject(i) ?: continue
            if (row.optString("url") != url && next.length() < limit) next.put(row)
        }
        prefs.edit().putString(key, next.toString()).apply()
    }

    private fun showSaved(key: String, title: String) {
        val values = JSONArray(prefs.getString(key, "[]"))
        AlertDialog.Builder(this).setTitle(title)
            .setItems(Array(values.length()) { values.getJSONObject(it).optString("title") }) { _, i -> navigate(values.getJSONObject(i).getString("url")) }
            .setNeutralButton("Hapus semua") { _, _ -> prefs.edit().remove(key).apply() }.setNegativeButton("Tutup", null).show()
    }

    private fun recordMedia(target: Tab, media: Media) {
        handler.post {
            if (target.media.size < 100 && tabs.contains(target) && isWebUrl(media.url)) {
                target.media[media.url] = media
                if (target === tab) updateCounts()
            }
        }
    }

    private fun showGrabber() {
        val items = tab.media.values.toList()
        AlertDialog.Builder(this).setTitle("Grabber · ${items.size} URL")
            .setItems(items.map { "${if (it.url.substringBefore('?').endsWith(".m3u8", true)) "HLS · " else ""}${Uri.decode(Uri.parse(it.url).lastPathSegment ?: "Media")}\n${Uri.parse(it.url).host}" }.toTypedArray()) { _, i ->
                confirmDownload(items[i], URLUtil.guessFileName(items[i].url, null, items[i].mime))
            }.setNeutralButton("URL manual") { _, _ ->
                val input = EditText(this).apply { hint = "https://…"; setSingleLine() }
                AlertDialog.Builder(this).setTitle("Unduh URL").setView(input).setPositiveButton("Lanjut") { _, _ ->
                    val url = input.text.toString().trim()
                    if (isWebUrl(url)) confirmDownload(Media(url, "", tab.url), URLUtil.guessFileName(url, null, null))
                    else toast("URL tidak valid")
                }.setNegativeButton("Batal", null).show()
            }.setNegativeButton("Tutup", null).show()
    }

    private fun scanMedia() {
        val target = tab
        val referer = target.url
        val view = web ?: return
        view.evaluateJavascript("""(()=>JSON.stringify(Array.from(document.querySelectorAll('video,audio,source')).slice(0,100).map(e=>e.currentSrc||e.src).filter(u=>/^https?:\/\//i.test(u))))()""") { result ->
            try {
                val decoded = org.json.JSONTokener(result).nextValue() as? String ?: "[]"
                val array = JSONArray(decoded)
                for (i in 0 until minOf(array.length(), 100)) {
                    val url = array.optString(i)
                    if (isWebUrl(url) && target.media.size < 100) target.media[url] = Media(url, "", referer)
                }
            } catch (_: org.json.JSONException) { toast("Media halaman tidak dapat dibaca") }
            if (!isDestroyed && target === tab) { updateCounts(); showGrabber() }
        }
    }

    private fun confirmDownload(media: Media, name: String) {
        if (privateMode) { toast("Unduhan dinonaktifkan pada sesi privat"); return }
        val hls = media.url.substringBefore('?').endsWith(".m3u8", true)
        val input = EditText(this).apply { setSingleLine(); setText(if (hls) name.substringBeforeLast('.', name) + ".ts" else name) }
        AlertDialog.Builder(this).setTitle("Unduh file?")
            .setMessage("${Uri.parse(media.url).host}\nHanya media yang Anda berhak unduh. HLS non-DRM saja. Ukuran diketahui setelah koneksi.")
            .setView(input).setPositiveButton("Unduh") { _, _ ->
                try {
                    DownloadService.enqueue(this, media.url, input.text.toString(), media.referer.takeIf(::isWebUrl),
                        CookieManager.getInstance().getCookie(media.url), web?.settings?.userAgentString ?: "Browserak")
                    toast("Masuk antrean")
                } catch (e: Exception) { toast(e.message ?: "Gagal memulai unduhan") }
            }.setNegativeButton("Batal", null).show()
    }

    private fun showDownloads() {
        val jobs = try { DownloadService.list(this) } catch (e: Exception) { toast("Antrean tidak dapat dibaca: ${e.message}"); return }
        AlertDialog.Builder(this).setTitle("Unduhan · ketuk untuk tindakan")
            .setItems(jobs.map { "${it.name}\n${it.state} · ${size(it.done)} / ${if (it.total > 0) size(it.total) else "?"}${if (it.error.isNotEmpty()) "\n${it.error}" else ""}" }.toTypedArray()) { _, index ->
                val job = jobs[index]
                AlertDialog.Builder(this).setTitle(job.name).setItems(arrayOf("Pause", "Resume / coba lagi", "Batal", "Simpan salinan…")) { _, action ->
                    try { when (action) {
                        0 -> DownloadService.pause(this, job.id)
                        1 -> DownloadService.resume(this, job.id)
                        2 -> DownloadService.cancel(this, job.id)
                        3 -> {
                            val file = File(job.path)
                            if (job.state != "completed" || !file.isFile) toast("File belum selesai")
                            else {
                                exportFile = file
                                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
                                    putExtra(Intent.EXTRA_TITLE, file.name)
                                }, 22)
                            }
                        }
                    } } catch (e: Exception) { toast(e.message ?: "Tindakan gagal") }
                }.show()
            }.setNeutralButton("Segarkan") { _, _ -> showDownloads() }.setNegativeButton("Tutup", null).show()
    }

    private fun showScripts() {
        if (privateMode) { toast("Userscript dinonaktifkan pada sesi privat"); return }
        val values = try { scripts.list() } catch (e: Exception) { toast(e.message ?: "Script tidak dapat dibaca"); return }
        AlertDialog.Builder(this).setTitle("Userscript")
            .setItems(values.map { "${if (it.enabled) "Aktif" else "Nonaktif"} · ${it.name}" }.toTypedArray()) { _, i ->
                val script = values[i]
                AlertDialog.Builder(this).setTitle(script.name).setItems(arrayOf("Aktif / nonaktif", "Edit", "Hapus")) { _, action ->
                    try { when (action) {
                        0 -> { scripts.toggle(script.id); showScripts() }
                        1 -> editScript(script.source, script.id)
                        2 -> AlertDialog.Builder(this).setMessage("Hapus ${script.name}?").setPositiveButton("Hapus") { _, _ ->
                            try { scripts.delete(script.id) } catch (e: Exception) { toast(e.message ?: "Penghapusan gagal") }
                        }.setNegativeButton("Batal", null).show()
                    } } catch (e: Exception) { toast(e.message ?: "Perubahan script gagal") }
                }.show()
            }.setPositiveButton("Tambah") { _, _ -> editScript("// ==UserScript==\n// @name Script saya\n// @match https://example.com/*\n// @run-at document-end\n// @grant none\n// ==/UserScript==\n") }
            .setNeutralButton("Impor .user.js") { _, _ ->
                try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 21) }
                catch (_: android.content.ActivityNotFoundException) { toast("Pemilih file tidak tersedia") }
            }.setNegativeButton("Tutup", null).show()
    }

    private fun editScript(source: String, id: String? = null) {
        val editor = EditText(this).apply { setText(source); textSize = 12f; gravity = android.view.Gravity.TOP; minLines = 12; maxLines = 20; contentDescription = "Kode userscript" }
        val scroll = ScrollView(this).apply { addView(editor) }
        val dialog = AlertDialog.Builder(this).setTitle("Edit userscript").setView(scroll)
            .setMessage("Script dapat membaca/mengubah data situs dalam @match. Simpan hanya kode tepercaya. Penyimpanan GM memakai localStorage per situs; bukan brankas rahasia. Kompatibilitas Tampermonkey terbatas.")
            .setPositiveButton("Simpan", null).setNegativeButton("Batal", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = editor.text.toString()
                AlertDialog.Builder(this).setTitle("Izinkan script pada situs berikut?")
                    .setMessage(code.lineSequence().filter { it.contains("@match") || it.contains("@exclude") || it.contains("@grant") }.joinToString("\n").take(4000))
                    .setPositiveButton("Izinkan & simpan") { _, _ ->
                        try { scripts.save(code, id); dialog.dismiss(); toast("Script disimpan; muat ulang situs") }
                        catch (e: Exception) { toast(e.message ?: "Script tidak dapat disimpan") }
                    }.setNegativeButton("Batal", null).show()
            }
        }
        dialog.show()
    }

    @Deprecated("Platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20) {
            val result = if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                ?.takeIf { values -> values.isNotEmpty() && values.all(::safeUpload) } else null
            uploadCallback?.onReceiveValue(result); uploadCallback = null
        }
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 21) io.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 256 * 1024) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 256 * 1024 + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("File tidak dapat dibuka")
                require(bytes.size <= 256 * 1024) { "Script maksimal 256 KiB" }
                val source = bytes.toString(Charsets.UTF_8)
                runOnUiThread { if (!isDestroyed) editScript(source) }
            } catch (e: Exception) { runOnUiThread { toast(e.message ?: "Impor gagal") } }
        }
        if (requestCode == 22) {
            val file = exportFile ?: return
            exportFile = null
            io.execute {
                try {
                    contentResolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("Tujuan tidak dapat dibuka")
                    runOnUiThread { toast("Salinan disimpan") }
                } catch (e: Exception) { runOnUiThread { toast("Penyimpanan gagal; file asli tetap tersedia: ${e.message}") } }
            }
        }
    }

    private fun saveTabs() {
        if (!privateMode) prefs.edit().putString("tabs", JSONArray(tabs.map { it.url }).toString()).apply()
    }

    private fun safeUpload(uri: Uri): Boolean {
        // Picker output is untrusted. Never grant a website access to app-owned files/providers.
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return false
        val provider = packageManager.resolveContentProvider(uri.authority!!, 0) ?: return false
        if (provider.applicationInfo.uid == applicationInfo.uid) return false
        return try { contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }
        catch (_: Exception) { false }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("private", privateMode)
        saveTabs(); super.onSaveInstanceState(outState)
    }

    override fun onPause() { web?.onPause(); saveTabs(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() {
        uploadCallback?.onReceiveValue(null); uploadCallback = null
        releaseTab(false)
        if (privateMode) clearSession { }
        io.shutdown(); super.onDestroy()
    }
    private fun back() { if (web?.canGoBack() == true) web?.goBack() else finish() }
    @Deprecated("Platform callback")
    override fun onBackPressed() { back() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun size(value: Long) = when { value >= 1048576 -> "${value / 1048576} MiB"; value >= 1024 -> "${value / 1024} KiB"; else -> "$value B" }

    companion object {
        private const val HOME = "about:blank"
        internal fun isWebUrl(url: String): Boolean = try {
            val uri = java.net.URI(url)
            uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
        } catch (_: Exception) { false }
        private fun isMedia(url: String) = url.substringBefore('?').substringBefore('#').lowercase().let { path ->
            listOf(".mp4", ".webm", ".m3u8", ".mp3", ".m4a", ".ogg", ".wav", ".flac", ".pdf", ".zip", ".apk").any(path::endsWith)
        }
    }
}
