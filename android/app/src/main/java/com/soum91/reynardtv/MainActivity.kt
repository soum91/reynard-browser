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

    private data class BrowserTab(
        val session: GeckoSession,
        var title: String = "New tab",
        var url: String = "",
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false,
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

    // MARK: - Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        isTv = uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
        cursorMode = isTv
        bookmarks = Bookmarks(this)

        buildLayout()
        createSession()

        val launchUrl = intentUrl(intent)
        when {
            launchUrl != null -> load(launchUrl)
            prefs.getString("lastUrl", null) != null -> session.loadUri(prefs.getString("lastUrl", null)!!)
            else -> load(HOME_URL)
        }
        if (isTv) {
            showToolbar(focusUrl = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentUrl(intent)?.let { load(it) }
    }

    override fun onPause() {
        super.onPause()
        if (currentUrl.startsWith("http")) {
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
        actionsRow.addView(chromeButton("⌂", "Home") { load(HOME_URL) })

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
                    if (isTv) hideToolbar()
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
                if (isTv) hideToolbar()
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
            openTab(HOME_URL)
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

    private fun newGeckoSession(): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .useTrackingProtection(true)
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

    private fun openTab(url: String? = null): GeckoSession {
        val newSession = newGeckoSession()
        tabs.add(BrowserTab(newSession))
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
            load(HOME_URL)
            if (isTv) hideToolbar()
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

    private fun searchUrl(query: String) = "https://www.google.com/search?q=" + Uri.encode(query)

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            val tab = tabs.firstOrNull { it.session === session } ?: return
            tab.url = url ?: ""
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
            return GeckoResult.fromValue(openTab())
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
                if (!isTv) toolbar.visibility = View.VISIBLE
            }
        }
    }

    private val permissionDelegate = object : PermissionDelegate {
        override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int> {
            // Video sites need autoplay and DRM (Widevine); everything else stays denied.
            val allowed = when (perm.permission) {
                PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> true
                else -> false
            }
            return GeckoResult.fromValue(if (allowed) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY)
        }
    }

    // MARK: - Toolbar

    private fun showToolbar(focusUrl: Boolean) {
        toolbar.visibility = View.VISIBLE
        cursor.visibility = View.GONE
        if (focusUrl) {
            urlField.requestFocus()
        } else {
            menuButton.requestFocus()
        }
    }

    private fun hideToolbar() {
        if (isTv) toolbar.visibility = View.GONE
        if (cursorMode) cursor.visibility = View.VISIBLE
        geckoView.requestFocus()
    }

    private val toolbarHasFocus: Boolean
        get() = toolbar.visibility == View.VISIBLE && toolbar.findFocus() != null

    private fun showBrowserMenu() {
        val choices = arrayOf(
            "＋  New tab",
            "▣  Tabs (" + tabs.size + ")",
            "☆  Bookmarks",
            "⌂  Home",
            "⟳  Reload",
            if (desktopMode) "▤  Request mobile site" else "▣  Request desktop site",
            "×  Close current tab"
        )
        AlertDialog.Builder(this)
            .setTitle("Reynard")
            .setItems(choices) { _, choice ->
                when (choice) {
                    0 -> { openTab(HOME_URL); if (isTv) showToolbar(focusUrl = true) }
                    1 -> showTabPicker()
                    2 -> showBookmarks()
                    3 -> { load(HOME_URL); if (isTv) hideToolbar() }
                    4 -> { session.reload(); if (isTv) hideToolbar() }
                    5 -> { toggleDesktopMode(); if (isTv) hideToolbar() }
                    6 -> closeTab(activeTabIndex)
                }
            }
            .show()
    }

    private fun showTabPicker() {
        val labels = tabs.mapIndexed { i, tab ->
            (if (i == activeTabIndex) "●  " else "○  ") + tab.title
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Open tabs")
            .setItems(labels) { _, which ->
                selectTab(which)
                if (isTv) hideToolbar()
            }
            .setPositiveButton("New tab") { _, _ -> openTab(HOME_URL) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBookmarks() {
        val items = bookmarks.all()
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(items.map { it.title }.toTypedArray()) { _, which ->
                load(items[which].url)
                if (isTv) hideToolbar()
            }
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
                if (toolbar.visibility == View.VISIBLE && toolbarHasFocus && isTv) hideToolbar() else showToolbar(focusUrl = false)
            }
            return true
        }

        // While the toolbar (or a dialog) has focus, the D-pad moves between buttons as usual.
        if (toolbarHasFocus) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN &&
                isTv && actionsRow.findFocus() != null
            ) {
                hideToolbar()
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
    private fun wantsBack() = isFullScreen || canGoBack || (isTv && !toolbarHasFocus)

    private fun handleBack() {
        when {
            isFullScreen -> session.exitFullScreen()
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
