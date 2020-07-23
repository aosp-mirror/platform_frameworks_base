package com.android.systemui.bubbles

import android.content.Context
import android.graphics.drawable.TransitionDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_LOW_BOUNCY
import androidx.dynamicanimation.animation.SpringForce.STIFFNESS_LOW
import com.android.systemui.R
import com.android.systemui.util.DismissCircleView
import com.android.systemui.util.animation.PhysicsAnimator

/*
 * View that handles interactions between DismissCircleView and BubbleStackView.
 */
class DismissView(context: Context) : FrameLayout(context) {

    var circle = DismissCircleView(context).apply {
        val targetSize: Int = context.resources.getDimensionPixelSize(R.dimen.dismiss_circle_size)
        val newParams = LayoutParams(targetSize, targetSize)
        newParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        setLayoutParams(newParams)
        setTranslationY(
            resources.getDimensionPixelSize(R.dimen.floating_dismiss_gradient_height).toFloat())
    }

    var isShowing = false
    private val animator = PhysicsAnimator.getInstance(circle)
    private val spring = PhysicsAnimator.SpringConfig(STIFFNESS_LOW, DAMPING_RATIO_LOW_BOUNCY);
    private val DISMISS_SCRIM_FADE_MS = 200
    init {
        setLayoutParams(LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.floating_dismiss_gradient_height),
            Gravity.BOTTOM))
        setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.floating_dismiss_bottom_margin))
        setClipToPadding(false)
        setClipChildren(false)
        setVisibility(View.INVISIBLE)
        setBackgroundResource(
            R.drawable.floating_dismiss_gradient_transition)
        addView(circle)
    }

    /**
     * Animates this view in.
     */
    fun show() {
        if (isShowing) return
        isShowing = true
        bringToFront()
        setZ(Short.MAX_VALUE - 1f)
        setVisibility(View.VISIBLE)
        (getBackground() as TransitionDrawable).startTransition(DISMISS_SCRIM_FADE_MS)
        animator.cancel()
        animator
            .spring(DynamicAnimation.TRANSLATION_Y, 0f, spring)
            .start()
    }

    /**
     * Animates this view out, as well as the circle that encircles the bubbles, if they
     * were dragged into the target and encircled.
     */
    fun hide() {
        if (!isShowing) return
        isShowing = false
        (getBackground() as TransitionDrawable).reverseTransition(DISMISS_SCRIM_FADE_MS)
        animator
            .spring(DynamicAnimation.TRANSLATION_Y, height.toFloat(),
                spring)
            .withEndActions({ setVisibility(View.INVISIBLE) })
            .start()
    }

    fun updateResources() {
        val targetSize: Int = context.resources.getDimensionPixelSize(R.dimen.dismiss_circle_size)
        circle.layoutParams.width = targetSize
        circle.layoutParams.height = targetSize
        circle.requestLayout()
    }
}