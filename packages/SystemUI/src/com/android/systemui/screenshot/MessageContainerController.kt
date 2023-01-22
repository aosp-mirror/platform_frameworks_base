package com.android.systemui.screenshot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.Guideline
import com.android.systemui.R

/**
 * MessageContainerController controls the display of content in the screenshot message container.
 */
class MessageContainerController
constructor(
    parent: ViewGroup,
) {
    private val guideline: Guideline = parent.requireViewById(R.id.guideline)
    private val messageContainer: ViewGroup =
        parent.requireViewById(R.id.screenshot_message_container)

    /**
     * Show a notification under the screenshot view indicating that a work profile screenshot has
     * been taken and which app can be used to view it.
     *
     * @param appName The name of the app to use to view screenshots
     * @param appIcon Optional icon for the relevant files app
     * @param onDismiss Runnable to be run when the user dismisses this message
     */
    fun showWorkProfileMessage(appName: CharSequence, appIcon: Drawable?, onDismiss: Runnable) {
        // Eventually this container will support multiple notification types, but for now just make
        // sure we don't double inflate.
        if (messageContainer.childCount == 0) {
            View.inflate(
                messageContainer.context,
                R.layout.screenshot_work_profile_first_run,
                messageContainer
            )
        }
        if (appIcon != null) {
            // Replace the default icon if one is provided.
            val imageView: ImageView =
                messageContainer.requireViewById<ImageView>(R.id.screenshot_message_icon)
            imageView.setImageDrawable(appIcon)
        }
        val messageContent =
            messageContainer.requireViewById<TextView>(R.id.screenshot_message_content)
        messageContent.text =
            messageContainer.context.getString(
                R.string.screenshot_work_profile_notification,
                appName
            )
        messageContainer.requireViewById<View>(R.id.message_dismiss_button).setOnClickListener {
            animateOutMessageContainer()
            onDismiss.run()
        }

        // Need the container to be fully measured before animating in (to know animation offset
        // destination)
        messageContainer.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    messageContainer.viewTreeObserver.removeOnPreDrawListener(this)
                    animateInMessageContainer()
                    return false
                }
            }
        )
    }

    private fun animateInMessageContainer() {
        if (messageContainer.visibility == View.VISIBLE) return

        messageContainer.visibility = View.VISIBLE
        getAnimator(true).start()
    }

    private fun animateOutMessageContainer() {
        getAnimator(false).apply {
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        super.onAnimationEnd(animation)
                        messageContainer.visibility = View.INVISIBLE
                    }
                }
            )
            start()
        }
    }

    private fun getAnimator(animateIn: Boolean): Animator {
        val params = messageContainer.layoutParams as MarginLayoutParams
        val offset = messageContainer.height + params.topMargin + params.bottomMargin
        val anim = if (animateIn) ValueAnimator.ofFloat(0f, 1f) else ValueAnimator.ofFloat(1f, 0f)
        with(anim) {
            duration = ScreenshotView.SCREENSHOT_ACTIONS_EXPANSION_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { valueAnimator: ValueAnimator ->
                val interpolation = valueAnimator.animatedValue as Float
                guideline.setGuidelineEnd((interpolation * offset).toInt())
                messageContainer.alpha = interpolation
            }
        }
        return anim
    }
}
