package com.overlord08x.sebbypass

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var app: SebApp
    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession

    private lateinit var tvStatusPill: TextView
    private lateinit var tvFilterInfo: TextView
    private lateinit var btnFilterDomain: Button
    private lateinit var btnLoadSeb: Button
    private lateinit var btnDisable: Button
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button
    private lateinit var etUrl: EditText
    private lateinit var btnReload: Button
    private lateinit var btnGo: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var welcomeOverlay: View
    private lateinit var btnWelcomeLoad: Button

    private var canGoBackState = false
    private var shouldAutoNavigateStartUrl = false
    private var hasLoadedAnyPage = false
    private var pendingPageFileCallback: ((List<Uri>) -> Unit)? = null

    private val openSebFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            readAndEnableSebFile(uri)
        }
    }

    private val openSinglePageFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val callback = pendingPageFileCallback
        pendingPageFileCallback = null
        callback?.invoke(if (uri != null) listOf(uri) else emptyList())
    }

    private val openMultiplePageFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val callback = pendingPageFileCallback
        pendingPageFileCallback = null
        callback?.invoke(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        app = application as SebApp
        app.ensureRuntimeInitialized()
        bindViews()
        applyWindowInsets()
        setupSession()
        setupControls()
        setupBackNavigation()

        app.onStatusChanged = { status ->
            applyStatus(status)
        }
        applyStatus(app.currentStatus)
    }

    override fun onResume() {
        super.onResume()
        showPreviousCrashIfAny()
    }

    private fun bindViews() {
        geckoView = findViewById(R.id.geckoView)
        tvStatusPill = findViewById(R.id.tvStatusPill)
        tvFilterInfo = findViewById(R.id.tvFilterInfo)
        btnFilterDomain = findViewById(R.id.btnFilterDomain)
        btnLoadSeb = findViewById(R.id.btnLoadSeb)
        btnDisable = findViewById(R.id.btnDisable)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        etUrl = findViewById(R.id.etUrl)
        btnReload = findViewById(R.id.btnReload)
        btnGo = findViewById(R.id.btnGo)
        progressBar = findViewById(R.id.progressBar)
        welcomeOverlay = findViewById(R.id.welcomeOverlay)
        btnWelcomeLoad = findViewById(R.id.btnWelcomeLoad)
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.rootContainer)
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun setupSession() {
        geckoSession = GeckoSession()
        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                canGoBackState = canGoBack
                btnBack.isEnabled = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                btnForward.isEnabled = canGoForward
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    session.loadUri(request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                Toast.makeText(
                    this@MainActivity,
                    "Failed to load ${uri ?: "page"} (error ${error.code})",
                    Toast.LENGTH_LONG
                ).show()
                return null
            }
        }

        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) {
                Toast.makeText(
                    this@MainActivity,
                    "Browser tab crashed, reopening session...",
                    Toast.LENGTH_LONG
                ).show()
                reopenSession()
            }

            override fun onKill(session: GeckoSession) {
                reopenSession()
            }
        }

        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (url != "about:blank") {
                    hasLoadedAnyPage = true
                    welcomeOverlay.visibility = View.GONE
                    if (!etUrl.hasFocus()) {
                        etUrl.setText(url)
                    }
                }
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 10
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                progressBar.progress = progress
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                progressBar.progress = 100
                progressBar.visibility = View.GONE
            }
        }

        geckoSession.promptDelegate = SebPromptDelegate(this, ::launchPageFilePicker)
        reopenSession()
    }

    private fun reopenSession() {
        val runtime = app.ensureRuntimeInitialized()
        if (!geckoSession.isOpen) {
            geckoSession.open(runtime)
        }
        geckoView.setSession(geckoSession)
        val status = app.currentStatus
        if (status.enabled) {
            geckoSession.settings.userAgentOverride = status.userAgent.ifEmpty { DEFAULT_SEB_USER_AGENT }
        }
    }

    private fun launchPageFilePicker(
        mimeTypes: Array<String>,
        multiple: Boolean,
        callback: (List<Uri>) -> Unit
    ): Boolean {
        if (pendingPageFileCallback != null) {
            return false
        }
        pendingPageFileCallback = callback
        return try {
            if (multiple) {
                openMultiplePageFileLauncher.launch(mimeTypes)
            } else {
                openSinglePageFileLauncher.launch(mimeTypes)
            }
            true
        } catch (_: Exception) {
            pendingPageFileCallback = null
            false
        }
    }

    private fun setupControls() {
        val openPicker = {
            openSebFileLauncher.launch(arrayOf("*/*"))
        }
        btnLoadSeb.setOnClickListener { openPicker() }
        btnWelcomeLoad.setOnClickListener { openPicker() }

        btnDisable.setOnClickListener {
            shouldAutoNavigateStartUrl = false
            app.disableSeb()
        }

        btnFilterDomain.setOnClickListener {
            val status = app.currentStatus
            val isAllUrls = status.rqUrlsFilter == listOf("<all_urls>")
            if (isAllUrls && status.sebStartUrl.isNotEmpty()) {
                val pattern = buildOriginPattern(status.sebStartUrl)
                if (pattern != null) {
                    app.updateFilter(listOf(pattern))
                } else {
                    Toast.makeText(this, "Invalid start URL for domain filter", Toast.LENGTH_SHORT).show()
                }
            } else {
                app.updateFilter(listOf("<all_urls>"))
            }
        }

        btnBack.setOnClickListener { geckoSession.goBack() }
        btnForward.setOnClickListener { geckoSession.goForward() }
        btnReload.setOnClickListener { geckoSession.reload() }
        btnGo.setOnClickListener { navigateFromInput() }

        etUrl.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                navigateFromInput()
                true
            } else {
                false
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canGoBackState) {
                    geckoSession.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun readAndEnableSebFile(uri: Uri) {
        try {
            val rawBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (rawBytes == null || rawBytes.isEmpty()) {
                Toast.makeText(this, "Selected file is empty", Toast.LENGTH_SHORT).show()
                return
            }
            if (rawBytes.size > MAX_SEB_BYTES) {
                Toast.makeText(this, "File terlalu besar (> 5 MB)", Toast.LENGTH_LONG).show()
                return
            }
            val xmlText = decodeSebXml(rawBytes)
            shouldAutoNavigateStartUrl = true
            app.enableSeb(xmlText)
        } catch (err: Throwable) {
            AlertDialog.Builder(this)
                .setTitle("Gagal Memuat .seb")
                .setMessage(err.message ?: err.javaClass.simpleName)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun decodeSebXml(rawBytes: ByteArray): String {
        val header = if (rawBytes.size >= 4) {
            String(rawBytes, 0, 4, StandardCharsets.US_ASCII)
        } else {
            ""
        }

        if (header in ENCRYPTED_SEB_HEADERS || String(rawBytes, 0, minOf(6, rawBytes.size), StandardCharsets.US_ASCII) == "bplist") {
            throw IllegalArgumentException(
                "File .seb ini terenkripsi ($header) atau format biner bplist. Gunakan file .seb XML tanpa password."
            )
        }

        val payloadBytes = when {
            header == "plnd" && hasGzipMagic(rawBytes, 4) -> gunzipLimited(rawBytes, 4)
            hasGzipMagic(rawBytes, 0) -> gunzipLimited(rawBytes, 0)
            else -> rawBytes
        }

        val charset = when {
            payloadBytes.size >= 2 && (payloadBytes[0].toInt() and 0xFF) == 0xFF && (payloadBytes[1].toInt() and 0xFF) == 0xFE ->
                StandardCharsets.UTF_16LE
            payloadBytes.size >= 2 && (payloadBytes[0].toInt() and 0xFF) == 0xFE && (payloadBytes[1].toInt() and 0xFF) == 0xFF ->
                StandardCharsets.UTF_16BE
            else -> StandardCharsets.UTF_8
        }

        val decoded = String(payloadBytes, charset)
            .replace("\uFEFF", "")
            .replace("\u0000", "")
            .trim()

        if (decoded.isEmpty() || !decoded.contains("<dict", ignoreCase = true)) {
            throw IllegalArgumentException("File bukan XML konfigurasi SEB yang valid (<dict> tidak ditemukan).")
        }
        return decoded
    }

    private fun hasGzipMagic(bytes: ByteArray, offset: Int): Boolean {
        return bytes.size >= offset + 2 &&
            (bytes[offset].toInt() and 0xFF) == 0x1F &&
            (bytes[offset + 1].toInt() and 0xFF) == 0x8B
    }

    private fun gunzipLimited(bytes: ByteArray, offset: Int): ByteArray {
        GZIPInputStream(ByteArrayInputStream(bytes, offset, bytes.size - offset)).use { gz ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = gz.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_SEB_BYTES) {
                    throw IllegalArgumentException("Isi file .seb setelah dekompresi terlalu besar.")
                }
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun showPreviousCrashIfAny() {
        val crashLog = SebCrashReporter.consumeCrashLog(this) ?: return
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val textView = TextView(this).apply {
            text = crashLog
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply {
            addView(textView)
        }
        AlertDialog.Builder(this)
            .setTitle("Error Log Terakhir")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("SEB Bypass Crash Log", crashLog))
                Toast.makeText(this, "Error log disalin", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun applyStatus(status: SebStatus) {
        if (status.errorMsg.isNotEmpty()) {
            Toast.makeText(this, status.errorMsg, Toast.LENGTH_LONG).show()
        }

        if (status.enabled) {
            tvStatusPill.text = getString(R.string.status_enabled)
            tvStatusPill.setBackgroundResource(R.drawable.bg_pill_enabled)
            tvStatusPill.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            btnDisable.visibility = View.VISIBLE

            val filterLabel = status.rqUrlsFilter.joinToString(", ")
            tvFilterInfo.text = "Filter: $filterLabel"

            if (status.sebStartUrl.isNotEmpty() && buildOriginPattern(status.sebStartUrl) != null) {
                btnFilterDomain.visibility = View.VISIBLE
                val isAllUrls = status.rqUrlsFilter == listOf("<all_urls>")
                btnFilterDomain.text = if (isAllUrls) {
                    getString(R.string.btn_filter_domain)
                } else {
                    getString(R.string.btn_filter_all)
                }
            } else {
                btnFilterDomain.visibility = View.GONE
            }

            geckoSession.settings.userAgentOverride = status.userAgent.ifEmpty { DEFAULT_SEB_USER_AGENT }

            val autoNavigate = shouldAutoNavigateStartUrl
            shouldAutoNavigateStartUrl = false
            if (autoNavigate && status.sebStartUrl.isNotEmpty()) {
                loadTargetUrl(status.sebStartUrl)
            }
        } else {
            tvStatusPill.text = getString(R.string.status_disabled)
            tvStatusPill.setBackgroundResource(R.drawable.bg_pill_disabled)
            tvStatusPill.setTextColor(ContextCompat.getColor(this, R.color.accent_danger))
            btnDisable.visibility = View.GONE
            btnFilterDomain.visibility = View.GONE
            tvFilterInfo.text = if (hasLoadedAnyPage) "Bypass disabled" else "No .seb loaded"
            geckoSession.settings.userAgentOverride = null
        }
    }

    private fun navigateFromInput() {
        val raw = etUrl.text.toString().trim()
        if (raw.isEmpty()) return
        val normalized = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            raw
        } else {
            "https://$raw"
        }
        etUrl.clearFocus()
        hideKeyboard()
        loadTargetUrl(normalized)
    }

    private fun loadTargetUrl(url: String) {
        val resolved = normalizeTargetUrl(url)
        hasLoadedAnyPage = true
        welcomeOverlay.visibility = View.GONE
        etUrl.setText(resolved)
        geckoSession.loadUri(resolved)
    }

    private fun normalizeTargetUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("sebs://", ignoreCase = true) -> "https://" + trimmed.substring(7)
            trimmed.startsWith("seb://", ignoreCase = true) -> "http://" + trimmed.substring(6)
            else -> trimmed
        }
    }

    private fun buildOriginPattern(rawUrl: String): String? {
        return try {
            val parsed = URI(normalizeTargetUrl(rawUrl))
            val scheme = parsed.scheme?.lowercase() ?: return null
            val host = parsed.host ?: return null
            if (scheme != "http" && scheme != "https") return null
            val portPart = if (parsed.port != -1) ":${parsed.port}" else ""
            "$scheme://$host$portPart/*"
        } catch (_: Exception) {
            null
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etUrl.windowToken, 0)
    }

    override fun onDestroy() {
        if (app.onStatusChanged != null) {
            app.onStatusChanged = null
        }
        geckoSession.close()
        super.onDestroy()
    }

    companion object {
        private const val MAX_SEB_BYTES = 5 * 1024 * 1024
        private val ENCRYPTED_SEB_HEADERS = setOf("pswd", "pwcc", "pkhs", "phsk")
        private const val DEFAULT_SEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 SEB/3.7.0 (x64)"
    }
}
