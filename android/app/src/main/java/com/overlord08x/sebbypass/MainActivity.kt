package com.overlord08x.sebbypass

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.net.URI

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

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                session.loadUri(uri)
                return null
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
        geckoSession.open(app.runtime)
        geckoView.setSession(geckoSession)
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
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "Selected file is empty", Toast.LENGTH_SHORT).show()
                return
            }
            shouldAutoNavigateStartUrl = true
            app.enableSeb(text)
        } catch (err: Exception) {
            Toast.makeText(this, "Failed to read file: ${err.message}", Toast.LENGTH_LONG).show()
        }
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
        hasLoadedAnyPage = true
        welcomeOverlay.visibility = View.GONE
        etUrl.setText(url)
        geckoSession.loadUri(url)
    }

    private fun buildOriginPattern(rawUrl: String): String? {
        return try {
            val parsed = URI(rawUrl)
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
        private const val DEFAULT_SEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 SEB/3.7.0 (x64)"
    }
}
