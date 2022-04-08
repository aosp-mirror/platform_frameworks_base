/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.row

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.style.BulletSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowInsets.Type.statusBars
import android.view.WindowManager
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.Interpolators.LINEAR_OUT_SLOW_IN
import com.android.systemui.Prefs
import com.android.systemui.R
import com.android.systemui.statusbar.notification.row.NotificationConversationInfo.OnConversationSettingsClickListener
import javax.inject.Inject


/**
 * Controller to handle presenting the priority conversations onboarding dialog
 */
class PriorityOnboardingDialogController @Inject constructor(
        val view: View,
        val context: Context,
        private val ignoresDnd: Boolean,
        private val showsAsBubble: Boolean,
        val icon : Drawable,
        private val onConversationSettingsClickListener : OnConversationSettingsClickListener,
        val badge : Drawable
) {

    private lateinit var dialog: Dialog
    private val OVERSHOOT: Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1.4f)
    private val IMPORTANCE_ANIM_DELAY = 150L
    private val IMPORTANCE_ANIM_GROW_DURATION = 250L
    private val IMPORTANCE_ANIM_SHRINK_DURATION = 200L
    private val IMPORTANCE_ANIM_SHRINK_DELAY = 25L

    fun init() {
        initDialog()
    }

    fun show() {
        dialog.show()
    }

    private fun done() {
        // Log that the user has seen the onboarding
        Prefs.putBoolean(context, Prefs.Key.HAS_SEEN_PRIORITY_ONBOARDING, true)
        dialog.dismiss()
    }

    private fun settings() {
        // Log that the user has seen the onboarding
        Prefs.putBoolean(context, Prefs.Key.HAS_SEEN_PRIORITY_ONBOARDING, true)
        dialog.dismiss()
        onConversationSettingsClickListener?.onClick()
    }

    class Builder @Inject constructor() {
        private lateinit var view: View
        private lateinit var context: Context
        private var ignoresDnd = false
        private var showAsBubble = false
        private lateinit var icon: Drawable
        private lateinit var onConversationSettingsClickListener
                : OnConversationSettingsClickListener
        private lateinit var badge : Drawable

        fun setView(v: View): Builder {
            view = v
            return this
        }

        fun setContext(c: Context): Builder {
            context = c
            return this
        }

        fun setIgnoresDnd(ignore: Boolean): Builder {
            ignoresDnd = ignore
            return this
        }

        fun setShowsAsBubble(bubble: Boolean): Builder {
            showAsBubble = bubble
            return this
        }

        fun setIcon(draw : Drawable) : Builder {
            icon = draw
            return this
        }
        fun setBadge(badge : Drawable) : Builder {
            this.badge = badge
            return this
        }

        fun setOnSettingsClick(onClick : OnConversationSettingsClickListener) : Builder {
            onConversationSettingsClickListener = onClick
            return this
        }

        fun build(): PriorityOnboardingDialogController {
            val controller = PriorityOnboardingDialogController(
                    view, context, ignoresDnd, showAsBubble, icon,
                    onConversationSettingsClickListener, badge)
            return controller
        }
    }

    private fun initDialog() {
        dialog = Dialog(context)

        if (dialog.window == null) {
            throw IllegalStateException("Need a window for the onboarding dialog to show")
        }

        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        // Prevent a11y readers from reading the first element in the dialog twice
        dialog.setTitle("\u00A0")
        dialog.apply {
            setContentView(view)
            setCanceledOnTouchOutside(true)

            findViewById<TextView>(R.id.done_button)?.setOnClickListener {
                done()
            }

            findViewById<TextView>(R.id.settings_button)?.setOnClickListener {
                settings()
            }

            findViewById<ImageView>(R.id.conversation_icon)?.setImageDrawable(icon)
            findViewById<ImageView>(R.id.icon)?.setImageDrawable(badge)
            val mImportanceRingView = findViewById<ImageView>(R.id.conversation_icon_badge_ring)
            val conversationIconBadgeBg = findViewById<ImageView>(R.id.conversation_icon_badge_bg)

            val ring: GradientDrawable = mImportanceRingView.drawable as GradientDrawable
            ring.mutate()
            val bg = conversationIconBadgeBg.drawable as GradientDrawable
            bg.mutate()
            val ringColor = context.getResources()
                    .getColor(com.android.internal.R.color.conversation_important_highlight)
            val standardThickness = context.resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.importance_ring_stroke_width)
            val largeThickness = context.resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.importance_ring_anim_max_stroke_width)
            val standardSize = context.resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.importance_ring_size)
            val baseSize = standardSize - standardThickness * 2
            val largeSize = baseSize + largeThickness * 2
            val bgSize = context.resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.conversation_icon_size_badged)

            val animatorUpdateListener: ValueAnimator.AnimatorUpdateListener
                    = ValueAnimator.AnimatorUpdateListener { animation ->
                val strokeWidth = animation.animatedValue as Int
                ring.setStroke(strokeWidth, ringColor)
                val newSize = baseSize + strokeWidth * 2
                ring.setSize(newSize, newSize)
                mImportanceRingView.invalidate()
            }

            val growAnimation: ValueAnimator = ValueAnimator.ofInt(0, largeThickness)
            growAnimation.interpolator = LINEAR_OUT_SLOW_IN
            growAnimation.duration = IMPORTANCE_ANIM_GROW_DURATION
            growAnimation.addUpdateListener(animatorUpdateListener)

            val shrinkAnimation: ValueAnimator
                    = ValueAnimator.ofInt(largeThickness, standardThickness)
            shrinkAnimation.duration = IMPORTANCE_ANIM_SHRINK_DURATION
            shrinkAnimation.startDelay = IMPORTANCE_ANIM_SHRINK_DELAY
            shrinkAnimation.interpolator = OVERSHOOT
            shrinkAnimation.addUpdateListener(animatorUpdateListener)
            shrinkAnimation.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator?) {
                    // Shrink the badge bg so that it doesn't peek behind the animation
                    bg.setSize(baseSize, baseSize);
                    conversationIconBadgeBg.invalidate();
                }

                override fun onAnimationEnd(animation: Animator?) {
                    // Reset bg back to normal size
                    bg.setSize(bgSize, bgSize);
                    conversationIconBadgeBg.invalidate();

                }
            })

            val anims = AnimatorSet()
            anims.startDelay = IMPORTANCE_ANIM_DELAY
            anims.playSequentially(growAnimation, shrinkAnimation)

            val gapWidth = dialog.context.getResources().getDimensionPixelSize(
                    R.dimen.conversation_onboarding_bullet_gap_width)
            val description = SpannableStringBuilder()
            description.append(context.getText(R.string.priority_onboarding_show_at_top_text),
                    BulletSpan(gapWidth),  /* flags */0)
            description.append(System.lineSeparator())
            description.append(context.getText(R.string.priority_onboarding_show_avatar_text),
                    BulletSpan(gapWidth),  /* flags */0)
            if (showsAsBubble) {
                description.append(System.lineSeparator())
                description.append(context.getText(
                        R.string.priority_onboarding_appear_as_bubble_text),
                        BulletSpan(gapWidth),  /* flags */0)
            }
            if (ignoresDnd) {
                description.append(System.lineSeparator())
                description.append(context.getText(R.string.priority_onboarding_ignores_dnd_text),
                        BulletSpan(gapWidth),  /* flags */0)
            }
            findViewById<TextView>(R.id.behaviors).setText(description)

            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(wmFlags)
                setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
                setWindowAnimations(com.android.internal.R.style.Animation_InputMethod)

                attributes = attributes.apply {
                    format = PixelFormat.TRANSLUCENT
                    title = PriorityOnboardingDialogController::class.java.simpleName
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    fitInsetsTypes = attributes.fitInsetsTypes and statusBars().inv()
                    width = MATCH_PARENT
                    height = WRAP_CONTENT
                }
            }
            anims.start()
        }
    }

    private val wmFlags = (WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
}
