package com.youraicqa.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class QuizCardView(private val ctx: Context, private val onAnswer: (Int, Int) -> Unit) {

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun build(quiz: JSONObject): View {
        val isPoll = quiz.optString("kind", "quiz").equals("poll", ignoreCase = true)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF17171C.toInt()))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Th.line(0xFF2A2A32.toInt()))
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            }
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(ctx).apply {
            text = if (isPoll) "Опрос" else "Квиз"
            Ico.start(this, if (isPoll) R.drawable.ic_poll else R.drawable.ic_brain, 15f, Th.fg(0xFFB39DFF.toInt()), 6f)
            setTextColor(Th.fg(0xFFB39DFF.toInt()))
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(headerRow)

        val title = quiz.optString("title").trim()
        if (title.isNotEmpty()) {
            card.addView(TextView(ctx).apply {
                text = title
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                textSize = 16.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, 0)
            })
        }

        val questions = quiz.optJSONArray("questions") ?: JSONArray()
        var answeredCount = 0
        var correctCount = 0
        val letters = listOf("А", "Б", "В", "Г", "Д", "Е")

        for (qi in 0 until questions.length()) {
            val q = questions.getJSONObject(qi)
            val options = q.optJSONArray("options") ?: JSONArray()
            val correctIdx = if (isPoll) -1 else q.optInt("correct", -1)
            val answeredIdx = if (q.isNull("answered")) -1 else q.optInt("answered", -1)
            if (answeredIdx >= 0) {
                answeredCount++
                if (!isPoll && answeredIdx == correctIdx) correctCount++
            }

            card.addView(TextView(ctx).apply {
                text = if (questions.length() > 1) "${qi + 1}. ${q.optString("q")}" else q.optString("q")
                setTextColor(Th.fg(0xFFE8E8E8.toInt()))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(if (qi == 0) 12 else 18), 0, dp(8))
            })

            for (oi in 0 until options.length()) {
                val optionText = options.optString(oi)
                val isSelected = oi == answeredIdx
                val isCorrectOpt = !isPoll && oi == correctIdx
                val locked = answeredIdx >= 0

                val (bgColor, borderColor, textColor) = when {
                    !locked -> Triple(0xFF1E1E25.toInt(), 0xFF33333C.toInt(), 0xFFE0E0E0.toInt())
                    isPoll && isSelected -> Triple(0xFF2A2450.toInt(), 0xFF7C6CFF.toInt(), 0xFFFFFFFF.toInt())
                    isPoll -> Triple(0xFF1E1E25.toInt(), 0xFF2C2C34.toInt(), 0xFF9A9AA5.toInt())
                    isCorrectOpt -> Triple(0xFF163C22.toInt(), 0xFF35A85A.toInt(), 0xFFFFFFFF.toInt())
                    isSelected -> Triple(0xFF3C1A1A.toInt(), 0xFFB0453F.toInt(), 0xFFFFFFFF.toInt())
                    else -> Triple(0xFF1A1A1F.toInt(), 0xFF2A2A32.toInt(), 0xFF7A7A82.toInt())
                }

                val optionRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Th.bg(bgColor))
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), Th.line(borderColor))
                    }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(6)
                    layoutParams = lp
                    isClickable = !locked
                    isFocusable = !locked
                    if (!locked) {
                        applyPressAnimation(PressAnimStyle.SOFT)
                        setOnClickListener { onAnswer(qi, oi) }
                    }
                }
                optionRow.addView(TextView(ctx).apply {
                    text = letters.getOrElse(oi) { (oi + 1).toString() }
                    setTextColor(Th.fg(textColor))
                    textSize = 13.5f
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(10)
                    }
                })
                optionRow.addView(TextView(ctx).apply {
                    text = optionText
                    setTextColor(Th.fg(textColor))
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (locked && !isPoll && (isCorrectOpt || isSelected)) {
                    optionRow.addView(TextView(ctx).apply {
                        text = if (isCorrectOpt) "✓" else "✕"
                        setTextColor(Th.fg(textColor))
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                    })
                } else if (locked && isPoll && isSelected) {
                    optionRow.addView(TextView(ctx).apply {
                        text = "✓"
                        setTextColor(Th.fg(textColor))
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                    })
                }
                card.addView(optionRow)
            }
        }

        if (!isPoll && questions.length() > 0 && answeredCount == questions.length()) {
            card.addView(TextView(ctx).apply {
                text = "Результат: $correctCount из ${questions.length()}"
                setTextColor(Th.fg(0xFFB39DFF.toInt()))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(14), 0, 0)
            })
        } else if (isPoll && questions.length() > 0 && answeredCount == questions.length()) {
            card.addView(TextView(ctx).apply {
                text = "Спасибо за ответ!"
                setTextColor(Th.fg(0xFF9A9AA5.toInt()))
                textSize = 12.5f
                setPadding(0, dp(12), 0, 0)
            })
        }

        return card
    }

}
