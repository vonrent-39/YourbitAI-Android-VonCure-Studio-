package com.youraicqa.app

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

object AchievementPopup {
    private const val SHOW_MS = 4500L
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Achievement>()
    private var current: View? = null

    fun show(app: android.content.Context, a: Achievement) {
        handler.post {
            queue.addLast(a)
            pump(app)
        }
    }

    private fun pump(app: android.content.Context) {
        if (current != null) return
        val a = queue.removeFirstOrNull() ?: return
        val act = NanoApp.currentActivity()
        if (act == null || act.isFinishing || act.isDestroyed) {
            Toast.makeText(app, Loc.t("Ачивка получена:") + " " + Loc.t(a.title), Toast.LENGTH_LONG).show()
            pump(app)
            return
        }
        display(app, act, a)
    }

    private fun dp(act: Activity, v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics
    ).toInt()

    private fun display(app: android.content.Context, act: Activity, a: Achievement) {
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: run { pump(app); return }
        val font = AchievementsStore.font(act)
        val d = { v: Int -> dp(act, v) }

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(10), d(9), d(14), d(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = d(14).toFloat()
                setColor(0xF2201C2E.toInt())
                setStroke(d(1), 0xFF7C5CE0.toInt())
            }
            elevation = d(10).toFloat()
        }
        card.addView(TextView(act).apply {
            text = a.id.toString()
            typeface = font
            textSize = if (a.id >= 10) 14f else 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = d(9).toFloat()
                setColor(0xFF7C5CE0.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(d(36), d(36)).apply { marginEnd = d(10) }
        })
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(act).apply {
            text = Loc.t("Ачивка получена:")
            Ico.start(this, R.drawable.ic_trophy, 12f, 0xFFB9A8FF.toInt(), 5f)
            typeface = font
            textSize = 10.5f
            setTextColor(0xFFB9A8FF.toInt())
        })
        col.addView(TextView(act).apply {
            text = Loc.t(a.title)
            typeface = font
            textSize = 13.5f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(col)

        val top = ViewCompat.getRootWindowInsets(content)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: d(24)
        val lp = FrameLayout.LayoutParams(
            minOf(d(330), act.resources.displayMetrics.widthPixels - d(24)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = top + d(8) }
        card.layoutParams = lp
        card.alpha = 0f
        card.translationY = -d(70).toFloat()
        current = card
        content.addView(card)

        val autoHide = Runnable { dismiss(app, card, 0f, -d(70).toFloat()) }
        card.animate().alpha(1f).translationY(0f).setDuration(220).start()
        handler.postDelayed(autoHide, SHOW_MS)

        var downX = 0f
        var downY = 0f
        card.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    handler.removeCallbacks(autoHide)
                    v.animate().cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = minOf(0f, ev.rawY - downY)
                    v.translationX = dx
                    v.translationY = dy
                    v.alpha = (1f - maxOf(abs(dx) / v.width, abs(dy) / (d(70).toFloat())) * 0.7f).coerceIn(0.2f, 1f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = v.translationX
                    val dy = v.translationY
                    if (abs(dx) > v.width * 0.3f || dy < -d(36).toFloat()) {
                        val tx = if (abs(dx) > v.width * 0.3f) (if (dx > 0) v.width.toFloat() * 1.2f else -v.width.toFloat() * 1.2f) else dx
                        val ty = if (abs(dx) > v.width * 0.3f) dy else -d(90).toFloat()
                        dismiss(app, v, tx, ty)
                    } else {
                        v.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(160).start()
                        handler.postDelayed(autoHide, 2500L)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dismiss(app: android.content.Context, card: View, tx: Float, ty: Float) {
        if (current !== card) return
        current = null
        card.animate().translationX(tx).translationY(ty).alpha(0f).setDuration(180).withEndAction {
            (card.parent as? ViewGroup)?.removeView(card)
            handler.postDelayed({ pump(app) }, 150)
        }.start()
    }
}
