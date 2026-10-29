package com.soum91.reynardtv

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.ScreenLength
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebResponse
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var geckoView: GeckoView
    private lateinit var cursor: CursorView
    private lateinit var toolbar: LinearLayout
    private lateinit var actionsRow: LinearLayout
    private lateinit var tabStrip: LinearLayout
    private lateinit var tabScroller: HorizontalScrollView
    private lateinit var urlField: EditText
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var bookmarkButton: TextView
    private lateinit var desktopButton: TextView
    private lateinit var menuButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var session: GeckoSession
    private lateinit var bookmarks: Bookmarks
    private lateinit var history: HistoryStore
    private lateinit var downloads: TvDownloads
    private lateinit var addOns: TvAddons

    private data class BrowserTab(
        val session: GeckoSession,
        var title: String = "New tab",
        var url: String = "",
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false,
        val privateMode: Boolean = false,
    )

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabIndex = -1

    private val prefs by lazy { getSharedPreferences("browser", Context.MODE_PRIVATE) }

    private var isTv = false
    private var cursorMode = false
    private var canGoBack = false
    private var canGoForward = false
    private var isFullScreen = false
    private var currentUrl = ""
    private var currentTitle = ""
    private var centerDownAt = 0L
    private var centerLongPressed = false
    private var toolbarHiddenByUser = false

    // MARK: - Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        isTv = uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
        cursorMode = isTv
        bookmarks = Bookmarks(this)
        history = HistoryStore(this)
        downloads = TvDownloads(this)
        addOns = TvAddons(this, (application as ReynardApp).runtime) { url -> openTab(url) }
        applyWebTheme()

        buildLayout()
        createSession()

        val launchUrl = intentUrl(intent)
        when {
            launchUrl != null -> load(launchUrl)
            prefs.getString("lastUrl", null) != null -> session.loadUri(prefs.getString("lastUrl", null)!!)
            else -> load(homeUrl())
        }
        if (isTv) showToolbar(focusUrl = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentUrl(intent)?.let { load(it) }
    }

    override fun onPause() {
        super.onPause()
        if (tabs.getOrNull(activeTabIndex)?.privateMode != true && currentUrl.startsWith("http")) {
            prefs.edit().putString("lastUrl", currentUrl).apply()
        }
    }

    private fun intentUrl(intent: Intent?): String? {
        intent ?: return null
        if (intent.action == Intent.ACTION_WEB_SEARCH) {
            return intent.getStringExtra("query")?.let { searchUrl(it) }
        }
        return intent.dataString?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    // MARK: - Layout

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun roundedBackground(color: Int, radius: Int = 12) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun focusBackground(base: Int = Color.rgb(49, 42, 66)): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), roundedBackground(Color.rgb(102, 69, 169)))
            addState(intArrayOf(android.R.attr.state_pressed), roundedBackground(Color.rgb(102, 69, 169)))
            addState(intArrayOf(), roundedBackground(base))
        }

    private fun chromeButton(label: String, description: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            contentDescription = description
            setTextColor(Color.rgb(247, 246, 255))
            textSize = if (isTv) 23f else 19f
            gravity = Gravity.CENTER
            minWidth = dp(if (isTv) 54 else 44)
            maxLines = 1
            isFocusable = true
            isClickable = true
            background = focusBackground()
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                dp(if (isTv) 54 else 44),
                dp(if (isTv) 52 else 46)
            ).apply { marginEnd = dp(5) }
        }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 16, 27))
        }

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(32, 26, 49))
            setPadding(dp(12), dp(8), dp(12), dp(10))
            elevation = dp(5).toFloat()
        }

        // TV's two-row chrome uses the same real tabs as the phone layout.
        tabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(tabStrip)
        }
        toolbar.addView(
            tabScroller,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isTv) 48 else 42))
        )

        actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        backButton = chromeButton("‹", "Go back") { if (canGoBack) session.goBack() }
        forwardButton = chromeButton("›", "Go forward") { if (canGoForward) session.goForward() }
        actionsRow.addView(backButton)
        actionsRow.addView(forwardButton)
        actionsRow.addView(chromeButton("⟳", "Reload page") { session.reload() })
        actionsRow.addView(chromeButton("⌂", "Home") { load(homeUrl()) })

        urlField = EditText(this).apply {
            setSingleLine()
            hint = "Search or enter address"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(170, 164, 186))
            textSize = if (isTv) 18f else 16f
            background = focusBackground(Color.rgb(47, 40, 64))
            setPadding(dp(18), 0, dp(18), 0)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, event ->
                val submitted = actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (submitted) {
                    load(text.toString())
                    hideKeyboard()
                    focusPage()
                }
                submitted
            }
        }
        actionsRow.addView(urlField, LinearLayout.LayoutParams(0, dp(if (isTv) 52 else 46), 1f).apply {
            marginEnd = dp(7)
        })

        bookmarkButton = chromeButton("☆", "Bookmark page") {
            if (currentUrl.isNotBlank()) {
                bookmarks.toggle(currentTitle, currentUrl)
                updateBookmarkButton()
            }
        }
        actionsRow.addView(bookmarkButton)
        desktopButton = chromeButton("▣", "Desktop or mobile site") { toggleDesktopMode() }
        actionsRow.addView(desktopButton)
        menuButton = chromeButton("☰", "Browser menu") { showBrowserMenu() }
        actionsRow.addView(menuButton)
        toolbar.addView(actionsRow)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Color.rgb(255, 113, 57))
            visibility = View.GONE
        }

        val content = FrameLayout(this)
        geckoView = GeckoView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        cursor = CursorView(this).apply {
            visibility = if (cursorMode) View.VISIBLE else View.GONE
        }
        content.addView(
            geckoView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        content.addView(
            cursor,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(toolbar)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // Keep touch input native on phones; the D-pad pointer is only needed with a remote.
        geckoView.setOnTouchListener { _, event ->
            if (event.source and InputDevice.SOURCE_TOUCHSCREEN != 0 && cursorMode && !isTv) {
                setCursorMode(false)
            }
            false
        }
    }

    private fun renderTabs() {
        if (!::tabStrip.isInitialized) return
        val hadFocus = tabStrip.findFocus() != null
        tabStrip.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val active = index == activeTabIndex
            val title = tab.title.ifBlank { "New tab" }
            val tabButton = chromeButton((if (active) "●  " else "○  ") + title, "Switch to " + title) {
                selectTab(index)
                focusPage()
            }.apply {
                textSize = if (isTv) 15f else 13f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(15), 0, dp(8), 0)
                maxWidth = dp(if (isTv) 250 else 170)
                minWidth = dp(if (isTv) 150 else 105)
                ellipsize = TextUtils.TruncateAt.END
                background = focusBackground(
                    if (active) Color.rgb(76, 57, 116) else Color.rgb(43, 37, 59)
                )
                layoutParams = LinearLayout.LayoutParams(
                    dp(if (isTv) 210 else 142),
                    dp(if (isTv) 43 else 38)
                ).apply { marginEnd = dp(2) }
                setOnLongClickListener {
                    closeTab(index)
                    true
                }
            }
            tabStrip.addView(tabButton)
            tabStrip.addView(chromeButton("×", "Close " + title) {
                closeTab(index)
            }.apply {
                textSize = 21f
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(if (isTv) 43 else 38))
                    .apply { marginEnd = dp(8) }
            })
        }
        tabStrip.addView(chromeButton("+", "New tab") {
            openTab(homeUrl())
            if (isTv) showToolbar(focusUrl = true)
        }.apply { textSize = 24f })

        // Rebuilding the strip must not strand keyboard/remote focus.
        if (hadFocus && activeTabIndex >= 0) {
            tabStrip.getChildAt(activeTabIndex * 2)?.requestFocus()
        }
    }

    private fun updateNavigationButtons() {
        if (!::backButton.isInitialized) return
        backButton.isEnabled = canGoBack
        forwardButton.isEnabled = canGoForward
        backButton.alpha = if (canGoBack) 1f else 0.4f
        forwardButton.alpha = if (canGoForward) 1f else 0.4f
    }

    // MARK: - Session

    private val desktopMode: Boolean
        get() = prefs.getBoolean("desktopMode", isTv)

    private fun newGeckoSession(privateMode: Boolean): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(privateMode)
            .useTrackingProtection(prefs.getBoolean("trackingProtection", true))
            .userAgentMode(
                if (desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            .viewportMode(
                if (desktopMode) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            )
            .build()
        return GeckoSession(settings).apply {
            navigationDelegate = this@MainActivity.navigationDelegate
            progressDelegate = this@MainActivity.progressDelegate
            contentDelegate = this@MainActivity.contentDelegate
            permissionDelegate = this@MainActivity.permissionDelegate
            promptDelegate = Prompts(this@MainActivity)
            open((application as ReynardApp).runtime)
        }
    }

    private fun createSession() {
        openTab()
        updateDesktopButton()
    }

    private fun openTab(url: String? = null, privateMode: Boolean = false): GeckoSession {
        val newSession = newGeckoSession(privateMode)
        tabs.add(BrowserTab(newSession, privateMode = privateMode))
        selectTab(tabs.lastIndex)
        url?.let { newSession.loadUri(it) }
        return newSession
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        if (index == activeTabIndex) return
        if (activeTabIndex >= 0 && ::session.isInitialized) {
            geckoView.releaseSession()
            session.setActive(false)
        }
        activeTabIndex = index
        val tab = tabs[index]
        session = tab.session
        geckoView.setSession(session)
        session.setActive(true)
        currentUrl = tab.url
        currentTitle = tab.title
        canGoBack = tab.canGoBack
        canGoForward = tab.canGoForward
        urlField.setText(currentUrl)
        updateNavigationButtons()
        updateBookmarkButton()
        renderTabs()
        geckoView.requestFocus()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        if (tabs.size == 1) {
            load(homeUrl())
            focusPage()
            return
        }
        val removed = tabs[index]
        val wasActive = index == activeTabIndex
        if (wasActive) {
            geckoView.releaseSession()
            removed.session.setActive(false)
        }
        tabs.removeAt(index)
        removed.session.close()
        if (wasActive) {
            activeTabIndex = -1
            selectTab(index.coerceAtMost(tabs.lastIndex))
        } else {
            if (index < activeTabIndex) activeTabIndex--
            renderTabs()
        }
    }

    private fun toggleDesktopMode() {
        val enabled = !desktopMode
        prefs.edit().putBoolean("desktopMode", enabled).apply()
        tabs.forEach { tab ->
            tab.session.settings.userAgentMode =
                if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            tab.session.settings.viewportMode =
                if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
        updateDesktopButton()
        session.reload()
    }

    private fun updateDesktopButton() {
        desktopButton.text = if (desktopMode) "▣" else "▤"
        desktopButton.contentDescription = if (desktopMode) "Desktop site enabled" else "Mobile site enabled"
    }

    private fun updateBookmarkButton() {
        bookmarkButton.text = if (currentUrl.isNotBlank() && bookmarks.contains(currentUrl)) "★" else "☆"
    }

    private fun load(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") || text.startsWith("about:") -> text
            !text.contains(' ') && text.contains('.') -> "https://$text"
            else -> searchUrl(text)
        }
        session.loadUri(url)
    }

    private fun searchUrl(query: String): String {
        val base = when (prefs.getString("searchEngine", "Google")) {
            "DuckDuckGo" -> "https://duckduckgo.com/?q="
            "Bing" -> "https://www.bing.com/search?q="
            "Brave" -> "https://search.brave.com/search?q="
            else -> "https://www.google.com/search?q="
        }
        return base + Uri.encode(query)
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            val tab = tabs.firstOrNull { it.session === session } ?: return
            tab.url = url ?: ""
            if (!tab.privateMode && !url.isNullOrBlank() && url.startsWith("http")) {
                history.record(url, tab.title)
            }
            if (session === this@MainActivity.session) {
                currentUrl = tab.url
                if (!urlField.hasFocus()) urlField.setText(currentUrl)
                updateBookmarkButton()
            }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tabs.firstOrNull { it.session === session }?.canGoBack = canGoBack
            if (session === this@MainActivity.session) {
                this@MainActivity.canGoBack = canGoBack
                updateNavigationButtons()
            }
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            tabs.firstOrNull { it.session === session }?.canGoForward = canGoForward
            if (session === this@MainActivity.session) {
                this@MainActivity.canGoForward = canGoForward
                updateNavigationButtons()
            }
        }

        // GeckoView requires a fresh session for a new window. It loads the URI itself.
        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            val isPrivate = tabs.firstOrNull { it.session === session }?.privateMode == true
            return GeckoResult.fromValue(openTab(privateMode = isPrivate))
        }

        override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            val uri = request.uri
            if (uri.startsWith("http") || uri.startsWith("about:") || uri.startsWith("data:") || uri.startsWith("blob:") || uri.startsWith("resource:")) {
                return GeckoResult.allow()
            }
            // intent:, market:, etc. go to whatever app handles them.
            try {
                val intent = if (uri.startsWith("intent:")) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
                startActivity(intent)
            } catch (_: Exception) {
            }
            return GeckoResult.deny()
        }
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onProgressChange(session: GeckoSession, progressValue: Int) {
            if (session !== this@MainActivity.session) return
            progress.progress = progressValue
            progress.visibility = if (progressValue in 1..99) View.VISIBLE else View.GONE
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            val tab = tabs.firstOrNull { it.session === session } ?: return
            tab.title = title?.ifBlank { "New tab" } ?: "New tab"
            if (!tab.privateMode && tab.url.startsWith("http")) history.updateTitle(tab.url, tab.title)
            if (session === this@MainActivity.session) currentTitle = tab.title
            renderTabs()
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            if (session !== this@MainActivity.session) return
            isFullScreen = fullScreen
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (fullScreen) {
                toolbar.visibility = View.GONE
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                toolbar.visibility = if (toolbarHiddenByUser && isTv) View.GONE else View.VISIBLE
                cursor.visibility = if (cursorMode && !toolbarHasFocus) View.VISIBLE else View.GONE
            }
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            downloads.receive(response)
        }
    }

    private val permissionDelegate = object : PermissionDelegate {
        override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int> {
            // Video sites need autoplay and DRM (Widevine); everything else stays denied.
            val allowed = when (perm.permission) {
                PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> prefs.getBoolean("autoplay", true)
                PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> prefs.getBoolean("drm", true)
                else -> false
            }
            return GeckoResult.fromValue(if (allowed) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY)
        }
    }

    // MARK: - Toolbar

    private fun showToolbar(focusUrl: Boolean) {
        toolbarHiddenByUser = false
        toolbar.visibility = View.VISIBLE
        cursor.visibility = View.GONE
        if (focusUrl) {
            urlField.requestFocus()
        } else {
            menuButton.requestFocus()
        }
    }

    private fun hideToolbar() {
        if (!isTv) return
        toolbarHiddenByUser = true
        toolbar.visibility = View.GONE
        if (cursorMode) cursor.visibility = View.VISIBLE
        geckoView.requestFocus()
    }

    /** Focus the page while leaving the browser controls visible. */
    private fun focusPage() {
        if (cursorMode) cursor.visibility = View.VISIBLE
        geckoView.requestFocus()
    }

    private val toolbarHasFocus: Boolean
        get() = toolbar.visibility == View.VISIBLE && toolbar.findFocus() != null

    private fun showBrowserMenu() {
        val choices = arrayOf(
            "＋  New tab", "◈  New private tab", "▣  Tabs (" + tabs.size + ")",
            "☆  Bookmarks", "◷  History", "⇩  Downloads", "⬡  Add-ons",
            "⚙  Settings", "⌂  Home", "⟳  Reload",
            if (desktopMode) "▤  Request mobile site" else "▣  Request desktop site",
            "⌕  Find in page", "−  Hide toolbar", "×  Close current tab"
        )
        AlertDialog.Builder(this)
            .setTitle("Reynard")
            .setItems(choices) { _, choice ->
                when (choice) {
                    0 -> { openTab(homeUrl()); showToolbar(focusUrl = true) }
                    1 -> { openTab(homeUrl(), privateMode = true); showToolbar(focusUrl = true) }
                    2 -> showTabPicker()
                    3 -> showBookmarks()
                    4 -> showHistory()
                    5 -> downloads.showList()
                    6 -> addOns.showManager()
                    7 -> showSettings()
                    8 -> { load(homeUrl()); focusPage() }
                    9 -> { session.reload(); focusPage() }
                    10 -> { toggleDesktopMode(); focusPage() }
                    11 -> findInPage()
                    12 -> hideToolbar()
                    13 -> closeTab(activeTabIndex)
                }
            }
            .show()
    }

    private fun homeUrl() = prefs.getString("homeUrl", HOME_URL)?.takeIf { it.startsWith("https://") } ?: HOME_URL

    private fun showSettings() {
        val items = arrayOf("Mozilla Account / Sync", "Appearance", "Browsing and Search", "Privacy and Security", "Add-ons", "Downloads", "About / Updates")
        AlertDialog.Builder(this).setTitle("Reynard Settings").setItems(items) { _, which ->
            when (which) {
                0 -> showSyncInfo()
                1 -> showAppearanceSettings()
                2 -> showBrowsingSettings()
                3 -> showPrivacySettings()
                4 -> addOns.showManager()
                5 -> downloads.showList()
                6 -> showAbout()
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun showSyncInfo() {
        // Do not accept Firefox credentials until a verified, secure Android Sync backend exists.
        AlertDialog.Builder(this).setTitle("Mozilla Account")
            .setMessage("Firefox Sync needs the Android account and encrypted Sync engines integrated before sign-in is available. No account data has been imported yet.")
            .setPositiveButton("OK", null).show()
    }

    private fun showAppearanceSettings() {
        val names = arrayOf("System", "Dark", "Light")
        val current = prefs.getInt("webTheme", 0)
        AlertDialog.Builder(this).setTitle("Website appearance")
            .setSingleChoiceItems(names, current) { dialog, which ->
                prefs.edit().putInt("webTheme", which).apply()
                applyWebTheme()
                dialog.dismiss()
            }
            .setNeutralButton("Show toolbar") { _, _ -> showToolbar(focusUrl = false) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun applyWebTheme() {
        val scheme = when (prefs.getInt("webTheme", 0)) {
            1 -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
            2 -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
            else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        }
        (application as ReynardApp).runtime.settings.setPreferredColorScheme(scheme)
    }

    private fun showBrowsingSettings() {
        val items = arrayOf("Search engine: ${prefs.getString("searchEngine", "Google")}", "Homepage URL", "${if (desktopMode) "Use mobile sites" else "Use desktop sites"}", "Find in page")
        AlertDialog.Builder(this).setTitle("Browsing and Search")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val engines = arrayOf("Google", "DuckDuckGo", "Bing", "Brave")
                        AlertDialog.Builder(this).setTitle("Search engine")
                            .setSingleChoiceItems(engines, engines.indexOf(prefs.getString("searchEngine", "Google"))) { d, index ->
                                prefs.edit().putString("searchEngine", engines[index]).apply()
                                d.dismiss()
                            }.setNegativeButton("Cancel", null).show()
                    }
                    1 -> {
                        val edit = EditText(this).apply { setSingleLine(); setText(homeUrl()); selectAll() }
                        AlertDialog.Builder(this).setTitle("Homepage (https://)").setView(edit)
                            .setPositiveButton("Save") { _, _ ->
                                val url = edit.text.toString().trim()
                                if (url.startsWith("https://")) prefs.edit().putString("homeUrl", url).apply()
                                else Toast.makeText(this, "Enter an HTTPS URL", Toast.LENGTH_LONG).show()
                            }.setNegativeButton("Cancel", null).show()
                    }
                    2 -> toggleDesktopMode()
                    3 -> findInPage()
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun showPrivacySettings() {
        val keys = arrayOf("trackingProtection", "autoplay", "drm")
        val labels = arrayOf("Tracking protection (new tabs)", "Allow video autoplay", "Allow Widevine DRM playback")
        val values = keys.map { prefs.getBoolean(it, true) }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Privacy and Security")
            .setMultiChoiceItems(labels, values) { _, i, checked -> values[i] = checked }
            .setPositiveButton("Apply") { _, _ ->
                prefs.edit().apply { keys.forEachIndexed { i, key -> putBoolean(key, values[i]) } }.apply()
            }
            .setNeutralButton("Clear browsing data") { _, _ ->
                showClearBrowsingData()
            }.setNegativeButton("Cancel", null).show()
    }

    /** The site's Gecko storage is independent from our app-level history index. */
    private fun showClearBrowsingData() {
        val flags = arrayOf(
            StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES,
            StorageController.ClearFlags.ALL_CACHES,
            StorageController.ClearFlags.AUTH_SESSIONS,
            StorageController.ClearFlags.PERMISSIONS,
        )
        val values = booleanArrayOf(true, true, false, false, true)
        val labels = arrayOf("Cookies and website data", "Website caches", "Site login sessions", "Site permissions", "Local browsing history")
        AlertDialog.Builder(this).setTitle("Clear browsing data")
            .setMultiChoiceItems(labels, values) { _, index, checked -> values[index] = checked }
            .setPositiveButton("Clear selected") { _, _ ->
                val selected = flags.indices.filter { values[it] }.fold(0L) { result, index -> result or flags[index] }
                if (values[4]) history.clear()
                if (selected != 0L) {
                    (application as ReynardApp).runtime.storageController.clearData(selected)
                        .accept({ Toast.makeText(this, "Site data cleared", Toast.LENGTH_SHORT).show() },
                            { err -> Toast.makeText(this, "Could not clear site data: ${err?.message}", Toast.LENGTH_LONG).show() })
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showAbout() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        AlertDialog.Builder(this).setTitle("About Reynard TV")
            .setMessage("Version $version\nGeckoView browser for Android TV\n\nUpdates and changelogs are available on GitHub. APK updates install over the existing app only when signed with the same key.")
            .setPositiveButton("Check updates") { _, _ -> checkForUpdates() }
            .setNeutralButton("Open releases") { _, _ -> load("https://github.com/soum91/reynard-browser/releases") }
            .setNegativeButton("Close", null).show()
    }

    /** Compare immutable APK build numbers published in the Android TV prerelease. */
    private fun checkForUpdates() {
        Toast.makeText(this, "Checking GitHub releases...", Toast.LENGTH_SHORT).show()
        Thread {
            val url = "https://api.github.com/repos/soum91/reynard-browser/releases/tags/android-tv"
            val result = runCatching {
                val request = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                try {
                    check(request.responseCode == 200) { "GitHub returned ${request.responseCode}" }
                    val response = JSONObject(request.inputStream.bufferedReader().use { it.readText() })
                    val assets = response.getJSONArray("assets")
                    val items = (0 until assets.length()).map { assets.getJSONObject(it) }
                    val apk = items.filter { it.optString("name").endsWith(".apk") }
                        .maxByOrNull { Regex("build-(\\d+)").find(it.optString("name"))?.groupValues?.get(1)?.toLongOrNull() ?: 0L }
                        ?: error("No Android TV APK published yet")
                    val publishedBuild = Regex("build-(\\d+)").find(apk.optString("name"))?.groupValues?.get(1)?.toLongOrNull()
                        ?: error("APK version missing")
                    Pair(publishedBuild, apk.getString("browser_download_url"))
                } finally { request.disconnect() }
            }
            runOnUiThread {
                result.onSuccess { (build, apkUrl) ->
                    val info = packageManager.getPackageInfo(packageName, 0)
                    @Suppress("DEPRECATION")
                    val installed = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                    val newer = build > installed
                    AlertDialog.Builder(this).setTitle(if (newer) "Reynard TV update available" else "Reynard TV is up to date")
                        .setMessage("Installed build: $installed\nLatest build: $build\n\n${if (newer) "Download the new APK and install it using your TV file manager." else "You have the latest published build."}")
                        .setPositiveButton(if (newer) "Download APK" else "OK") { _, _ -> if (newer) downloads.downloadDirect(apkUrl) }
                        .setNeutralButton("Release notes") { _, _ -> load("https://github.com/soum91/reynard-browser/releases/tag/android-tv") }
                        .setNegativeButton("Close", null).show()
                }.onFailure { err ->
                    AlertDialog.Builder(this).setTitle("Update check failed")
                        .setMessage(err.message ?: "Could not contact GitHub")
                        .setPositiveButton("Open releases") { _, _ -> load("https://github.com/soum91/reynard-browser/releases") }
                        .setNegativeButton("Close", null).show()
                }
            }
        }.start()
    }

    private fun findInPage() {
        val edit = EditText(this).apply { setSingleLine(); hint = "Find text" }
        AlertDialog.Builder(this).setTitle("Find in page").setView(edit)
            .setPositiveButton("Find") { _, _ ->
                val query = edit.text.toString()
                if (query.isNotBlank()) session.finder.find(query, 0)
                focusPage()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showHistory() {
        val items = history.all()
        AlertDialog.Builder(this).setTitle("History")
            .setItems(items.map { it.title.ifBlank { it.url } }.toTypedArray()) { _, which -> load(items[which].url); focusPage() }
            .setNeutralButton("Clear") { _, _ ->
                AlertDialog.Builder(this).setMessage("Clear local browsing history?")
                    .setPositiveButton("Clear") { _, _ -> history.clear() }
                    .setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }

    private fun editBookmarks() {
        val items = bookmarks.all()
        AlertDialog.Builder(this).setTitle("Edit bookmarks")
            .setItems(items.map { it.title }.toTypedArray()) { _, which ->
                val item = items[which]
                AlertDialog.Builder(this).setTitle(item.title).setMessage(item.url)
                    .setPositiveButton("Open") { _, _ -> load(item.url) }
                    .setNeutralButton("Delete") { _, _ -> bookmarks.remove(item.url); updateBookmarkButton() }
                    .setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }

    private fun showTabPicker() {
        val labels = tabs.mapIndexed { i, tab ->
            (if (i == activeTabIndex) "●  " else "○  ") + tab.title
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Open tabs")
            .setItems(labels) { _, which ->
                selectTab(which)
                focusPage()
            }
            .setPositiveButton("New tab") { _, _ -> openTab(homeUrl()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBookmarks() {
        val items = bookmarks.all()
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(items.map { it.title }.toTypedArray()) { _, which ->
                load(items[which].url)
                focusPage()
            }
            .setNeutralButton("Edit / Delete") { _, _ -> editBookmarks() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlField.windowToken, 0)
    }

    // MARK: - Remote control

    private fun setCursorMode(enabled: Boolean) {
        cursorMode = enabled
        cursor.visibility = if (enabled && !toolbarHasFocus) View.VISIBLE else View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        if (code == KeyEvent.KEYCODE_BACK) {
            if (!wantsBack()) {
                return super.dispatchKeyEvent(event)
            }
            if (event.action == KeyEvent.ACTION_UP) {
                handleBack()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_BOOKMARK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (toolbarHiddenByUser) showToolbar(focusUrl = false)
                else if (toolbarHasFocus) focusPage()
                else showToolbar(focusUrl = false)
            }
            return true
        }

        // While the toolbar (or a dialog) has focus, the D-pad moves between buttons as usual.
        if (toolbarHasFocus) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN &&
                isTv && actionsRow.findFocus() != null
            ) {
                focusPage()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        val isDpad = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

        if ((isDpad || isCenter) && !cursorMode) {
            setCursorMode(true)
        }
        if (cursorMode && isDpad) {
            if (event.action == KeyEvent.ACTION_DOWN) moveCursor(code, event.repeatCount)
            return true
        }
        if (cursorMode && isCenter) {
            handleCenter(event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Back exits the app only from the toolbar of a page with no history. */
    private fun wantsBack() = isFullScreen || (isTv && toolbarHiddenByUser) || canGoBack || (isTv && !toolbarHasFocus)

    private fun handleBack() {
        when {
            isFullScreen -> session.exitFullScreen()
            isTv && toolbarHiddenByUser -> showToolbar(focusUrl = false)
            canGoBack -> session.goBack()
            isTv && !toolbarHasFocus -> showToolbar(focusUrl = false)
        }
    }

    private fun moveCursor(code: Int, repeat: Int) {
        // Starts precise, speeds up while the button is held.
        val step = dp(min(8 + repeat * 4, 48)).toFloat()
        var dx = 0f
        var dy = 0f
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> dy = -step
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = step
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = step
        }
        val edge = dp(24).toFloat()
        val x = cursor.cursorX + dx
        val y = cursor.cursorY + dy
        // Pushing against an edge scrolls the page instead.
        val scrollX = when {
            x < edge && dx < 0 -> dx
            x > cursor.width - edge && dx > 0 -> dx
            else -> 0f
        }
        val scrollY = when {
            y < edge && dy < 0 -> dy * 2
            y > cursor.height - edge && dy > 0 -> dy * 2
            else -> 0f
        }
        if (scrollX != 0f || scrollY != 0f) {
            geckoView.panZoomController.scrollBy(
                ScreenLength.fromPixels(scrollX.toDouble()),
                ScreenLength.fromPixels(scrollY.toDouble())
            )
        }
        cursor.moveTo(x, y)
        sendHover()
    }

    private fun handleCenter(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    centerDownAt = SystemClock.uptimeMillis()
                    centerLongPressed = false
                } else if (!centerLongPressed && SystemClock.uptimeMillis() - centerDownAt > LONG_PRESS_MS) {
                    // Hold OK: open the toolbar (address bar, bookmarks, desktop mode).
                    centerLongPressed = true
                    showToolbar(focusUrl = true)
                }
            }
            KeyEvent.ACTION_UP -> if (!centerLongPressed) tapAtCursor()
        }
    }

    private fun sendHover() {
        val now = SystemClock.uptimeMillis()
        val hover = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, cursor.cursorX, cursor.cursorY, 0)
        hover.source = InputDevice.SOURCE_MOUSE
        geckoView.dispatchGenericMotionEvent(hover)
        hover.recycle()
    }

    private fun tapAtCursor() {
        val x = cursor.cursorX
        val y = cursor.cursorY
        val down = SystemClock.uptimeMillis()
        val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        press.source = InputDevice.SOURCE_TOUCHSCREEN
        geckoView.dispatchTouchEvent(press)
        press.recycle()
        geckoView.postDelayed({
            val up = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            geckoView.dispatchTouchEvent(up)
            up.recycle()
        }, 40)
    }

    companion object {
        private const val HOME_URL = "https://www.google.com/"
        private const val LONG_PRESS_MS = 600L
    }
}
