package com.soum91.reynardtv

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import android.widget.Button
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
    private lateinit var urlField: EditText
    private lateinit var bookmarkButton: Button
    private lateinit var desktopButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var session: GeckoSession
    private lateinit var bookmarks: Bookmarks

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

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(27, 27, 29))
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        fun toolButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = if (isTv) 18f else 16f
            minWidth = dp(48)
            setOnClickListener { action() }
        }

        toolbar.addView(toolButton("◀") { if (canGoBack) session.goBack() })
        toolbar.addView(toolButton("▶") { if (canGoForward) session.goForward() })
        toolbar.addView(toolButton("⟳") { session.reload() })
        toolbar.addView(toolButton("⌂") { load(HOME_URL) })

        urlField = EditText(this).apply {
            setSingleLine()
            hint = "Search or enter address"
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
        toolbar.addView(urlField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        bookmarkButton = toolButton("☆") {
            bookmarks.toggle(currentTitle, currentUrl)
            updateBookmarkButton()
        }
        toolbar.addView(bookmarkButton)
        toolbar.addView(toolButton("☰") { showBookmarks() })
        desktopButton = toolButton("") { toggleDesktopMode() }
        toolbar.addView(desktopButton)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        val content = FrameLayout(this)
        geckoView = GeckoView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        cursor = CursorView(this).apply { visibility = if (cursorMode) View.VISIBLE else View.GONE }
        content.addView(geckoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(cursor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // A touch means a person with a touchscreen: drop the remote pointer.
        geckoView.setOnTouchListener { _, event ->
            if (event.source and InputDevice.SOURCE_TOUCHSCREEN != 0 && cursorMode && !isTv) {
                setCursorMode(false)
            }
            false
        }
    }

    // MARK: - Session

    private val desktopMode: Boolean
        get() = prefs.getBoolean("desktopMode", isTv)

    private fun createSession() {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .useTrackingProtection(true)
            .userAgentMode(if (desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (desktopMode) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()
        session = GeckoSession(settings)
        session.navigationDelegate = navigationDelegate
        session.progressDelegate = progressDelegate
        session.contentDelegate = contentDelegate
        session.permissionDelegate = permissionDelegate
        session.promptDelegate = Prompts(this)
        session.open((application as ReynardApp).runtime)
        geckoView.setSession(session)
        updateDesktopButton()
    }

    private fun toggleDesktopMode() {
        val enabled = !desktopMode
        prefs.edit().putBoolean("desktopMode", enabled).apply()
        session.settings.userAgentMode = if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode = if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        updateDesktopButton()
        session.reload()
    }

    private fun updateDesktopButton() {
        desktopButton.text = if (desktopMode) "🖥" else "📱"
    }

    private fun updateBookmarkButton() {
        bookmarkButton.text = if (bookmarks.contains(currentUrl)) "★" else "☆"
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
            currentUrl = url ?: ""
            if (!urlField.hasFocus()) urlField.setText(currentUrl)
            updateBookmarkButton()
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@MainActivity.canGoBack = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            this@MainActivity.canGoForward = canGoForward
        }

        // One tab for now: links that want a new window open here.
        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            session.loadUri(uri)
            return null
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
            progress.progress = progressValue
            progress.visibility = if (progressValue in 1..99) View.VISIBLE else View.GONE
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            currentTitle = title ?: ""
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
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
            toolbar.getChildAt(0).requestFocus()
        }
    }

    private fun hideToolbar() {
        if (isTv) toolbar.visibility = View.GONE
        if (cursorMode) cursor.visibility = View.VISIBLE
        geckoView.requestFocus()
    }

    private val toolbarHasFocus: Boolean
        get() = toolbar.visibility == View.VISIBLE && toolbar.findFocus() != null

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
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && isTv) {
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
