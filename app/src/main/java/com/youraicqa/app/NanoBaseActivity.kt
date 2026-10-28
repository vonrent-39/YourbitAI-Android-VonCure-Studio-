package com.youraicqa.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

abstract class NanoBaseActivity : Activity() {
    protected val baseScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var openedWithTransition: String = TRANSITION_SLIDE

    companion object {
        const val EXTRA_TRANSITION_STYLE = "nano_transition_style"
        const val TRANSITION_SLIDE = "slide"
        const val TRANSITION_TAB = "tab"
        private val EMPTY_THEME_COLORS = org.json.JSONObject()
        private const val OPEN_DURATION_MS = 380L
        private const val CLOSE_DURATION_MS = OPEN_DURATION_MS
        private const val MAX_CONTENT_DP = 720
        private const val MAX_DIALOG_DP = 560
        private const val GLASS_EAGER_MS = 1500L
        private const val GLASS_DIRTY_GAP_MS = 24L
        private const val GLASS_IDLE_GAP_MS = 100L
    }

    private var appliedFontScale: Float = 1f

    private var appliedThemeOverride: Boolean? = null
    private var appliedThemeOverrideCaptured = false

    private var appliedLang: String = Loc.EN
    private val locHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var locNextDelayMs = 80L
    private val locRunnable = Runnable {
        val t0 = android.os.SystemClock.uptimeMillis()
        Loc.translateTree(window?.decorView)
        hideScrollbars(window?.decorView)
        if (glassWindow) Glass.restyle(window?.decorView)
        val took = android.os.SystemClock.uptimeMillis() - t0
        locNextDelayMs = (took * 6).coerceIn(80L, 600L)
    }

    protected fun hideScrollbars(root: View?, depth: Int = 0) {
        if (root == null || depth > 60) return
        if (root.isVerticalScrollBarEnabled) root.isVerticalScrollBarEnabled = false
        if (root.isHorizontalScrollBarEnabled) root.isHorizontalScrollBarEnabled = false
        if (root is ViewGroup) for (i in 0 until root.childCount) hideScrollbars(root.getChildAt(i), depth + 1)
    }
    private var locScheduled = false

    override fun attachBaseContext(newBase: Context) {
        appliedThemeOverride = UiPrefs.themeOverride(newBase)
        appliedThemeOverrideCaptured = true
        Loc.init(newBase)
        appliedLang = Loc.lang(newBase)
        val themedBase = UiPrefs.applyThemeContext(Loc.wrap(newBase))
        Th.light = !Th.isNight(themedBase)
        val scale = UiPrefs.fontScale(themedBase)
        appliedFontScale = scale
        val config = android.content.res.Configuration(themedBase.resources.configuration)
        config.fontScale = scale
        val dm = themedBase.resources.displayMetrics
        dm.scaledDensity = dm.density * scale
        super.attachBaseContext(themedBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyGlobalFontIfNeeded(this)
        openedWithTransition = intent?.getStringExtra(EXTRA_TRANSITION_STYLE) ?: TRANSITION_SLIDE
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
            glassDirty = true
            if (!locScheduled) {
                locScheduled = true
                locHandler.postDelayed({ locScheduled = false; locRunnable.run() }, locNextDelayMs)
            }
        }
        glassEagerUntil = android.os.SystemClock.uptimeMillis() + GLASS_EAGER_MS
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!glassWindow) return true
                val now = android.os.SystemClock.uptimeMillis()
                val since = now - lastGlassAt
                val due = now < glassEagerUntil || (glassDirty && since >= GLASS_DIRTY_GAP_MS) || since >= GLASS_IDLE_GAP_MS
                if (due) {
                    glassDirty = false
                    lastGlassAt = now
                    Glass.restyle(window?.decorView)
                } else if (!glassRetryPosted) {
                    glassRetryPosted = true
                    window?.decorView?.postDelayed({
                        glassRetryPosted = false
                        window?.decorView?.invalidate()
                    }, GLASS_IDLE_GAP_MS)
                }
                return true
            }
        })
    }

    private var glassDirty = true
    private var lastGlassAt = 0L
    private var glassRetryPosted = false
    private var glassEagerUntil = 0L

    override fun onContentChanged() {
        super.onContentChanged()
        Loc.translateTree(window?.decorView)
        hideScrollbars(window?.decorView)
        if (glassWindow) Glass.restyle(window?.decorView)
    }

    protected open val handlesEdgeToEdgeInsetsItself: Boolean get() = false

    protected open val glassWindow: Boolean get() = true
    protected open val staggerEntrance: Boolean get() = false
    private var staggerDone = false

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        if (glassWindow) Glass.clearRootBackground(findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0))
        if (!handlesEdgeToEdgeInsetsItself) applyDefaultEdgeToEdgeInsets()
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        if (glassWindow) Glass.clearRootBackground(findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0))
        if (!handlesEdgeToEdgeInsetsItself) applyDefaultEdgeToEdgeInsets()
    }

    override fun setContentView(view: View, params: ViewGroup.LayoutParams) {
        super.setContentView(view, params)
        if (glassWindow) Glass.clearRootBackground(findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0))
        if (!handlesEdgeToEdgeInsetsItself) applyDefaultEdgeToEdgeInsets()
    }

    private var sidePad = 0

    protected fun contentSidePad(width: Int): Int = maxOf(0, (width - dp(MAX_CONTENT_DP)) / 2)

    protected fun popupWidth(): Int = minOf((resources.displayMetrics.widthPixels * 0.94f).toInt(), dp(MAX_DIALOG_DP))

    private fun applyDefaultEdgeToEdgeInsets() {
        val contentRoot = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) ?: return
        val baseLeft = contentRoot.paddingLeft
        val baseTop = contentRoot.paddingTop
        val baseRight = contentRoot.paddingRight
        val baseBottom = contentRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { v, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                baseLeft + maxOf(systemBars.left, sidePad),
                baseTop + systemBars.top,
                baseRight + maxOf(systemBars.right, sidePad),
                baseBottom + maxOf(systemBars.bottom, ime.bottom)
            )
            insets
        }
        contentRoot.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val sp = contentSidePad(right - left)
            if (sp != sidePad) {
                sidePad = sp
                ViewCompat.requestApplyInsets(v)
            }
        }
        ViewCompat.requestApplyInsets(contentRoot)
    }

    override fun onResume() {
        super.onResume()
        glassEagerUntil = android.os.SystemClock.uptimeMillis() + GLASS_EAGER_MS
        if (staggerEntrance && !staggerDone) {
            staggerDone = true
            if (UiPrefs.animationsEnabled(this)) window?.decorView?.post { staggerScreenContent() }
        }
        val themeChanged = appliedThemeOverrideCaptured && UiPrefs.themeOverride(this) != appliedThemeOverride
        if (UiPrefs.fontScale(this) != appliedFontScale || themeChanged || Loc.lang(this) != appliedLang) {
            recreate()
            return
        }
        applyPluginWindowTheme()
        if (Cloud.blocked(this) != null) Cloud.showBlockedDialog(this)
    }

    override fun onDestroy() {
        locHandler.removeCallbacksAndMessages(null)
        baseScope.cancel()
        super.onDestroy()
    }

    private fun applyPluginWindowTheme() {
        baseScope.launch {
            try {
                val colors = PluginManager.activeThemeOverride(this@NanoBaseActivity)
                    ?.optJSONObject("colors")
                if (colors == null) {
                    resetPluginWindowTheme()
                    onPluginThemeColors(EMPTY_THEME_COLORS)
                    return@launch
                }
                fun parse(key: String): Int? = colors.optString(key).takeIf { it.startsWith("#") }
                    ?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
                val bg = parse("background")?.let { Th.bg(it) } ?: themeColor(R.color.app_background)
                val gradientTo = colors.optJSONObject("backgroundGradient")
                    ?.optString("to")?.takeIf { it.startsWith("#") }
                    ?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
                    ?.let { Th.bg(it) }
                val windowDrawable: android.graphics.drawable.Drawable = if (gradientTo != null) {
                    val angle = colors.optJSONObject("backgroundGradient")?.optDouble("angle", 135.0) ?: 135.0
                    GradientDrawable(orientationForAngle(angle), intArrayOf(bg, gradientTo))
                } else {
                    android.graphics.drawable.ColorDrawable(bg)
                }
                window.setBackgroundDrawable(windowDrawable)
                window.statusBarColor = bg
                window.navigationBarColor = bg
                setSystemBarIconsForBackground(bg)
                onPluginThemeColors(colors)
            } catch (e: Exception) {
                NanoLog.log(this@NanoBaseActivity, "PLUGIN_THEME apply_error=${e.message}")
            }
        }
    }

    protected fun orientationForAngle(angleDeg: Double): GradientDrawable.Orientation {
        val a = ((angleDeg % 360) + 360) % 360
        return when {
            a < 22.5 || a >= 337.5 -> GradientDrawable.Orientation.LEFT_RIGHT
            a < 67.5 -> GradientDrawable.Orientation.BL_TR
            a < 112.5 -> GradientDrawable.Orientation.BOTTOM_TOP
            a < 157.5 -> GradientDrawable.Orientation.BR_TL
            a < 202.5 -> GradientDrawable.Orientation.RIGHT_LEFT
            a < 247.5 -> GradientDrawable.Orientation.TR_BL
            a < 292.5 -> GradientDrawable.Orientation.TOP_BOTTOM
            else -> GradientDrawable.Orientation.TL_BR
        }
    }

    protected fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

// Only the top strip starts the drag, so inner scrolling is not hijacked.
    fun enableSheetSwipe(dialog: android.app.Dialog, root: View, addHandle: Boolean = false) {
        if (addHandle && root is LinearLayout) {
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) }
                background = GradientDrawable().apply { setColor(Th.bg(0xFF3A3A45.toInt())); cornerRadius = dp(2).toFloat() }
            }, 0)
        }
        var downY = 0f
        var dragging = false
        var vt: android.view.VelocityTracker? = null
        root.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (ev.y <= dp(52)) {
                        v.animate().cancel()
                        downY = ev.rawY - v.translationY; dragging = true
                        vt?.recycle(); vt = android.view.VelocityTracker.obtain().also { it.addMovement(ev) }
                        true
                    } else false
                }
                android.view.MotionEvent.ACTION_MOVE -> if (dragging) {
                    vt?.addMovement(ev)
                    v.translationY = (ev.rawY - downY).coerceAtLeast(0f)
                    true
                } else false
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    vt?.recycle(); vt = null
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && (v.translationY > dp(90) || vy > 1400f)) {
                        v.animate().translationY(v.height.toFloat()).setDuration(170).withEndAction { dialog.dismiss() }.start()
                    } else {
                        v.animate().translationY(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    }
                    true
                } else false
                else -> false
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun applyThemeToDialog(dialog: android.app.Dialog) {
        Loc.translateTree(dialog.window?.decorView)
        hideScrollbars(dialog.window?.decorView)
    }

    private fun resetPluginWindowTheme() {
        val bg = androidx.core.content.ContextCompat.getColor(this, R.color.app_background)
        val gradient = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_app_gradient)
        window.setBackgroundDrawable(if (glassWindow && gradient != null) gradient else android.graphics.drawable.ColorDrawable(bg))
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setSystemBarIconsForBackground(bg)
    }

    private fun setSystemBarIconsForBackground(bg: Int) {
        val lightBg = androidx.core.graphics.ColorUtils.calculateLuminance(bg) > 0.5
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            var flags = window.decorView.systemUiVisibility
            flags = if (lightBg) flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (android.os.Build.VERSION.SDK_INT >= 26) flags = if (lightBg) flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    protected open fun onPluginThemeColors(colors: org.json.JSONObject) {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Dialog card: soft top-to-bottom gradient plus a faint highlight stroke. */
    protected fun dialogCardBackground(radiusDp: Int = 20): GradientDrawable {
        val base = if (Th.light) themeColor(R.color.app_surface) else 0xFF16161C.toInt()
        val top = if (Th.light) base else androidx.core.graphics.ColorUtils.blendARGB(base, android.graphics.Color.WHITE, 0.07f)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, base)).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), if (Th.light) themeColor(R.color.app_border) else 0x26FFFFFF)
        }
    }

    /** Children fade/slide in one after another when a dialog opens. */
    protected fun staggerDialogContent(root: ViewGroup) {
        if (!UiPrefs.animationsEnabled(this)) return
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            c.alpha = 0f
            c.translationY = dp(8).toFloat()
            c.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 45L).setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { c.alpha = 1f; c.translationY = 0f }.start()
        }
    }

    private fun applyDialogAnimation(dialog: android.app.Dialog) {
        if (!UiPrefs.animationsEnabled(this) || !EconomyPrefs.transitionsEnabled(this)) return
        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
    }

// dismiss() runs the exit animation first and closes the window afterwards.
    private inner class SmoothDialog(context: Context, themeRes: Int = android.R.style.Theme_DeviceDefault_Dialog_NoActionBar) :
        android.app.Dialog(context, themeRes) {
        var onAnimatedDismiss: ((onReallyDone: () -> Unit) -> Unit)? = null
        var configured = false
        private var closing = false

        override fun show() {
            if (!configured) {
                configured = true
                configureAutoDialog(this)
            }
            super.show()
            val w = window ?: return
            val maxW = dp(MAX_DIALOG_DP)
            val lp = w.attributes
            if (resources.displayMetrics.widthPixels > maxW && (lp.width == ViewGroup.LayoutParams.MATCH_PARENT || lp.width > maxW)) {
                w.setLayout(maxW, lp.height)
            }
        }

        override fun dismiss() {
            if (closing) return
            val hook = onAnimatedDismiss
            if (hook == null) {
                super.dismiss()
                return
            }
            closing = true
            hook {
                try { window?.setWindowAnimations(0) } catch (_: Exception) {}
                super.dismiss()
            }
        }
    }

    /** Dialog with blur/dim and enter/exit animation applied automatically on show(). */
    fun smoothDialog(withTheme: Boolean = true): android.app.Dialog =
        if (withTheme) SmoothDialog(this) else SmoothDialog(this, 0)

    private fun configureAutoDialog(dialog: SmoothDialog) {
        val w = dialog.window ?: return
        val bottom = (w.attributes.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
        if (bottom) {
            applyDialogBlurBackground(dialog, sheet = true)
        } else {
            applyDialogAnimation(dialog)
            applyDialogBlurBackground(dialog)
        }
    }

// Blur and dim fade in with the dialog; blur needs Android 12+ and is skipped otherwise.
    private fun applyDialogBlurBackground(dialog: android.app.Dialog, sheet: Boolean = false) {
        val window = dialog.window ?: return
        (dialog as? SmoothDialog)?.configured = true
        val animate = UiPrefs.animationsEnabled(this)
        if (sheet) {
            window.setWindowAnimations(0)
            if (animate) {
                window.findViewById<View>(android.R.id.content)?.translationY = resources.displayMetrics.heightPixels.toFloat()
            }
        }
        val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && EconomyPrefs.blurEnabled(this)
        val targetBlurPx = if (supportsBlur) dp(14) else 0
        val targetDim = if (supportsBlur) 0.35f else 0.6f

        if (supportsBlur) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 1 }
        }
        window.setDimAmount(0f)

        val showAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_DURATION_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val win = dialog.window ?: return@addUpdateListener
                if (supportsBlur) {
                    win.attributes = win.attributes.apply {
                        blurBehindRadius = (targetBlurPx * t).toInt().coerceAtLeast(1)
                    }
                }
                win.setDimAmount(targetDim * t)
            }
        }
        dialog.setOnShowListener {
            showAnimator.start()
            val content = dialog.window?.findViewById<View>(android.R.id.content)
            if (sheet && animate && content != null) {
                if (content.translationY == 0f) content.translationY = resources.displayMetrics.heightPixels.toFloat()
                content.animate().cancel()
                content.animate().translationY(0f).setDuration(OPEN_DURATION_MS - 80)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            }
        }

        (dialog as? SmoothDialog)?.onAnimatedDismiss = { onReallyDone ->
            showAnimator.cancel()
            val decor = dialog.window?.decorView
            val startBlur = if (supportsBlur)
                runCatching { dialog.window?.attributes?.blurBehindRadius ?: 0 }.getOrDefault(0)
            else 0
            val startDim = runCatching { dialog.window?.attributes?.dimAmount ?: targetDim }.getOrDefault(targetDim)
            val sheetContent = if (sheet) dialog.window?.findViewById<View>(android.R.id.content) else null
            if (sheetContent != null && animate) {
                sheetContent.animate().cancel()
                sheetContent.animate()
                    .translationY(maxOf(sheetContent.height, 1).toFloat() + (sheetContent.translationY.coerceAtLeast(0f)))
                    .setDuration(CLOSE_DURATION_MS - 100)
                    .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
                    .withEndAction { onReallyDone() }
                    .start()
                android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = CLOSE_DURATION_MS - 100
                    addUpdateListener { anim ->
                        val t = anim.animatedValue as Float
                        try {
                            val win = dialog.window ?: return@addUpdateListener
                            if (supportsBlur) win.attributes = win.attributes.apply { blurBehindRadius = (startBlur * t).toInt().coerceAtLeast(1) }
                            win.setDimAmount(startDim * t)
                        } catch (_: Exception) { cancel() }
                    }
                }.start()
            } else if (decor == null || sheet) {
                onReallyDone()
            } else {
                decor.animate().cancel()
                decor.pivotX = decor.width / 2f
                decor.pivotY = decor.height / 2f
                decor.animate()
                    .alpha(0f)
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(CLOSE_DURATION_MS)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { onReallyDone() }
                    .start()
                android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = CLOSE_DURATION_MS
                    interpolator = android.view.animation.AccelerateInterpolator()
                    addUpdateListener { anim ->
                        val t = anim.animatedValue as Float
                        try {
                            val win = dialog.window ?: return@addUpdateListener
                            if (supportsBlur) {
                                win.attributes = win.attributes.apply {
                                    blurBehindRadius = (startBlur * t).toInt().coerceAtLeast(1)
                                }
                            }
                            win.setDimAmount(startDim * t)
                        } catch (_: Exception) {
                            cancel()
                        }
                    }
                }.start()
            }
        }
    }

    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
        if (UiPrefs.animationsEnabled(this) && EconomyPrefs.transitionsEnabled(this)) {
            if (intent.getStringExtra(EXTRA_TRANSITION_STYLE) == TRANSITION_TAB) {
                overridePendingTransition(R.anim.screen_zoom_in, R.anim.screen_fade_out)
            } else {
                overridePendingTransition(R.anim.screen_slide_in_right, R.anim.screen_fade_out)
            }
        }
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        super.startActivityForResult(intent, requestCode)
        if (UiPrefs.animationsEnabled(this) && EconomyPrefs.transitionsEnabled(this)) {
            if (intent.getStringExtra(EXTRA_TRANSITION_STYLE) == TRANSITION_TAB) {
                overridePendingTransition(R.anim.screen_zoom_in, R.anim.screen_fade_out)
            } else {
                overridePendingTransition(R.anim.screen_slide_in_right, R.anim.screen_fade_out)
            }
        }
    }

    override fun finish() {
        super.finish()
        if (UiPrefs.animationsEnabled(this) && EconomyPrefs.transitionsEnabled(this)) {
            if (openedWithTransition == TRANSITION_TAB) {
                overridePendingTransition(R.anim.screen_fade_in, R.anim.screen_zoom_out)
            } else {
                overridePendingTransition(R.anim.screen_fade_in, R.anim.screen_slide_out_right)
            }
        }
    }

    private fun themeColor(res: Int): Int = androidx.core.content.ContextCompat.getColor(this, res)

    private fun pillBackground(filled: Boolean, destructive: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        val light = !isNightMode()
        when {
            destructive -> {
                setColor(if (light) themeColor(R.color.app_danger_soft) else 0xFF4A211E.toInt())
                setStroke(dp(1), themeColor(R.color.app_danger))
            }
            filled -> setColor(if (light) themeColor(R.color.app_accent) else 0xFF34343E.toInt())
            else -> {
                setColor(if (light) themeColor(R.color.app_surface_alt) else 0xFF1C1C22.toInt())
                setStroke(dp(1), if (light) themeColor(R.color.app_border) else 0xFF34343E.toInt())
            }
        }
    }

    private fun dialogButtonTextColor(destructive: Boolean, defaultColor: Int): Int {
        val light = !isNightMode()
        return when {
            destructive -> if (light) themeColor(R.color.app_danger) else 0xFFFF6B5B.toInt()
            light && defaultColor != 0xFFFFFFFF.toInt() -> themeColor(R.color.app_text_primary)
            else -> defaultColor
        }
    }

    fun styledConfirmDialog(
        title: String,
        message: String,
        positiveText: String,
        negativeText: String = "Отмена",
        destructive: Boolean = false,
        mandatory: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val dialog = SmoothDialog(this)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        if (mandatory) dialog.setCancelable(false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = dialogCardBackground(20)
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        root.addView(TextView(this).apply {
            text = title
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (message.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = message
                setTextColor(Th.fg(0xFFAAAAAA.toInt()))
                textSize = 14f
                setPadding(0, dp(8), 0, 0)
            })
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)
        }
        if (!mandatory) btnRow.addView(TextView(this).apply {
            text = negativeText
            setTextColor(Th.fg(0xFFCCCCCC.toInt()))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(4)
            layoutParams = lp
            isClickable = true
            background = pillBackground(filled = false, destructive = false)
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = positiveText
            setTextColor(dialogButtonTextColor(destructive, defaultColor = 0xFFFFFFFF.toInt()))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = if (mandatory) 0 else dp(4)
            layoutParams = lp
            isClickable = true
            background = pillBackground(filled = true, destructive = destructive)
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        })
        root.addView(btnRow)

        val panel = FrameLayout(this).apply {
            setPadding(dp(24), 0, dp(24), 0)
            addView(root)
        }
        staggerDialogContent(root)
        dialog.setContentView(panel)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        applyDialogAnimation(dialog)
        applyDialogBlurBackground(dialog)
        dialog.show()
        applyThemeToDialog(dialog)
    }

    fun styledListDialog(title: String, items: List<String>, onSelect: (Int) -> Unit) {
        val dialog = SmoothDialog(this)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = dialogCardBackground(20)
            setPadding(dp(20), dp(18), dp(20), dp(10))
        }
        root.addView(TextView(this).apply {
            text = title
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        val itemsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, label ->
            itemsBox.addView(TextView(this).apply {
                text = label
                setTextColor(Th.fg(0xFFE8E8E8.toInt()))
                textSize = 14.5f
                setPadding(dp(10), dp(13), dp(10), dp(13))
                isClickable = true
                background = GradientDrawable().apply {
                    setColor(Th.bg(0x00000000))
                    cornerRadius = dp(12).toFloat()
                }
                applyPressAnimation(PressAnimStyle.LIFT)
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(i)
                }
            })
        }
        root.addView(MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.6f).toInt()).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(itemsBox)
        })
        val panel = FrameLayout(this).apply {
            setPadding(dp(24), 0, dp(24), 0)
            addView(root)
        }
        staggerDialogContent(root)
        dialog.setContentView(panel)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        applyDialogAnimation(dialog)
        applyDialogBlurBackground(dialog)
        dialog.show()
        applyThemeToDialog(dialog)
    }

    fun showModelListPanel(
        title: String,
        subtitle: String,
        models: List<MainActivity.Model>,
        noKey: (MainActivity.Model) -> Boolean = { false },
        onPick: (MainActivity.Model) -> Unit
    ) {
        val dialog = smoothDialog()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = dialogCardBackground(24)
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) }
            background = GradientDrawable().apply { setColor(Th.bg(0xFF3A3A45.toInt())); cornerRadius = dp(2).toFloat() }
        })
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = title; setTextColor(Th.fg(0xFFFFFFFF.toInt())); textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        })
        if (subtitle.isNotBlank()) titleCol.addView(TextView(this).apply {
            text = subtitle; setTextColor(Th.fg(0xFF9A9AA5.toInt())); textSize = 13f; setPadding(0, dp(2), 0, 0)
        })
        header.addView(titleCol)
        header.addView(TextView(this).apply {
            text = Ico.glyph(context, R.drawable.ic_close, 15f, Th.fg(0xFFCCCCCC.toInt())); setTextColor(Th.fg(0xFFCCCCCC.toInt())); textSize = 16f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Th.bg(0xFF2A2A32.toInt())); cornerRadius = dp(18).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Th.bg(0xFF1E1E26.toInt())); cornerRadius = dp(14).toFloat() }
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14); bottomMargin = dp(10) }
        }
        searchRow.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_search); setColorFilter(Th.fg(0xFF888888.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(5) }
        })
        val search = android.widget.EditText(this).apply {
            hint = "Поиск моделей…"
            setHintTextColor(Th.fg(0xFF777777.toInt())); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            background = null; setPadding(0, dp(10), 0, dp(10)); textSize = 14.5f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchRow.addView(search)
        root.addView(searchRow)

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
            clipChildren = false; clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        scroll.addView(list)
        root.addView(scroll)

        fun providerOf(m: MainActivity.Model): String = when {
            m.isLocal -> "На устройстве (GGUF)"
            m.serverId.isNotBlank() -> "Сервер: " + (ServerStore.find(this, m.serverId)?.name ?: "свой сервер")
            m.pluginId.isNotBlank() -> "Плагин"
            m.customProviderUrl.isNotBlank() ->
                try { android.net.Uri.parse(m.customProviderUrl).host ?: m.customProviderUrl } catch (_: Exception) { m.customProviderUrl }
            else -> ApiKeyGate.providerLabel(m.provider)
        }

        fun sectionHead(text: String, color: Int) {
            list.addView(TextView(this).apply {
                this.text = text; setTextColor(Th.fg(color)); textSize = 12f; setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(8))
            })
        }
        fun addRow(m: MainActivity.Model) {
            val missing = noKey(m)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(8) }
                background = GradientDrawable().apply {
                    setColor(Th.bg(0xFF141418.toInt())); cornerRadius = dp(14).toFloat()
                    if (m.recommended) setStroke(dp(2), Th.line(0xFFFFD54F.toInt())) else setStroke(dp(1), Th.line(0xFF2A2A32.toInt()))
                }
            }
            val color = Th.fg(if (m.recommended) 0xFFFFD54F.toInt() else m.accent)
            card.addView(TextView(this).apply {
                text = m.badge.ifBlank { m.name.take(2) }
                setTextColor(color); textSize = 12f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setStroke(dp(2), color); cornerRadius = dp(12).toFloat(); setColor((color and 0x00FFFFFF) or 0x28000000)
                }
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(8) }
            })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = m.name; setTextColor(Th.fg(0xFFFFFFFF.toInt())); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            })
            col.addView(TextView(this).apply {
                text = providerOf(m) + if (missing) " · ⚠ нет ключа" else ""
                setTextColor(Th.fg(if (missing) 0xFFFFB74D.toInt() else 0xFF9A9AA5.toInt())); textSize = 12.5f
                setPadding(0, dp(2), 0, 0)
            })
            card.addView(col)
            card.addView(TextView(this).apply { text = "›"; setTextColor(Th.fg(0xFF666666.toInt())); textSize = 18f })
            card.applyPressAnimation(PressAnimStyle.LIFT)
            card.setOnClickListener { dialog.dismiss(); onPick(m) }
            list.addView(card)
        }
        fun rebuild(filter: String) {
            list.removeAllViews()
            val q = filter.trim().lowercase()
            val shown = models.filter { m ->
                q.isEmpty() || m.name.lowercase().contains(q) || m.id.lowercase().contains(q) || m.desc.lowercase().contains(q)
            }
            val mine = shown.filter { it.customProviderUrl.isNotBlank() || it.isLocal || it.serverId.isNotBlank() || it.pluginId.isNotBlank() }
            val builtin = shown.filter { it.customProviderUrl.isBlank() && !it.isLocal && it.serverId.isBlank() && it.pluginId.isBlank() }
            val rec = builtin.filter { it.recommended }
            val rest = builtin.filter { !it.recommended }
            if (rec.isNotEmpty()) { sectionHead("РЕКОМЕНДОВАННЫЕ", 0xFFFFD54F.toInt()); rec.forEach { addRow(it) } }
            if (mine.isNotEmpty()) { sectionHead("МОИ МОДЕЛИ", 0xFF8AB4F8.toInt()); mine.forEach { addRow(it) } }
            if (rest.isNotEmpty()) { sectionHead("ВСЕ МОДЕЛИ", 0xFF888888.toInt()); rest.forEach { addRow(it) } }
            if (shown.isEmpty()) list.addView(TextView(this).apply {
                text = "Ничего не найдено"; setTextColor(Th.fg(0xFF777777.toInt())); gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
            Loc.translateTree(list)
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { rebuild(s?.toString() ?: "") }
        })
        rebuild("")

        dialog.setContentView(root)
        dialog.window?.setLayout(popupWidth(), (resources.displayMetrics.heightPixels * 0.82f).toInt())
        enableSheetSwipe(dialog, root)
        dialog.show()
        Loc.translateTree(dialog.window?.decorView)
        applyThemeToDialog(dialog)
    }

    data class StyledDialogButton(val text: String, val keepOpen: Boolean = false, val destructive: Boolean = false, val onClick: () -> Unit)

    fun styledPanelDialog(title: String, contentView: android.view.View?, buttons: List<StyledDialogButton>): android.app.Dialog {
        val dialog = SmoothDialog(this)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = dialogCardBackground(20)
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }
        root.addView(TextView(this).apply {
            text = title
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        if (contentView != null) {
            if (contentView is android.widget.ScrollView) root.addView(contentView)
            else root.addView(MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.68f).toInt()).apply {
                isVerticalScrollBarEnabled = false
                addView(contentView)
            })
        }
        if (buttons.isNotEmpty()) {
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(16), 0, 0)
            }
            buttons.forEachIndexed { i, spec ->
                val isPrimary = i == buttons.lastIndex
                btnRow.addView(TextView(this).apply {
                    text = spec.text
                    setTextColor(
                        when {
                            spec.destructive -> dialogButtonTextColor(true, defaultColor = 0xFFFFFFFF.toInt())
                            isPrimary -> dialogButtonTextColor(false, defaultColor = 0xFFFFFFFF.toInt())
                            else -> Th.fg(0xFFCCCCCC.toInt())
                        }
                    )
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    lp.marginStart = dp(4)
                    layoutParams = lp
                    isClickable = true
                    background = pillBackground(filled = isPrimary, destructive = spec.destructive)
                    applyPressAnimation(PressAnimStyle.SOFT)
                    setOnClickListener {
                        if (!spec.keepOpen) dialog.dismiss()
                        spec.onClick()
                    }
                })
            }
            root.addView(btnRow)
        }
        val panel = FrameLayout(this).apply {
            setPadding(dp(24), 0, dp(24), 0)
            addView(root)
        }
        staggerDialogContent(root)
        dialog.setContentView(panel)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        applyDialogAnimation(dialog)
        applyDialogBlurBackground(dialog)
        dialog.show()
        applyThemeToDialog(dialog)
        return dialog
    }
}

class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : android.widget.ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}
