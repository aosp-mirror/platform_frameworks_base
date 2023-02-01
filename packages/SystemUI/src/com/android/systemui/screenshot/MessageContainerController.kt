package com.android.systemui.screenshot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.UserHandle
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.constraintlayout.widget.Guideline
import com.android.systemui.R
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject

/**
 * MessageContainerController controls the display of content in the screenshot message container.
 */
class MessageContainerController
@Inject
constructor(
    private val workProfileMessageController: WorkProfileMessageController,
    private val screenshotDetectionController: ScreenshotDetectionController,
    private val featureFlags: FeatureFlags,
) {
    private lateinit var container: ViewGroup
    private lateinit var guideline: Guideline
    private lateinit var workProfileFirstRunView: ViewGroup
    private lateinit var detectionNoticeView: ViewGroup
    private var animateOut: Animator? = null

    fun setView(screenshotView: ViewGroup) {
        container = screenshotView.requireViewById(R.id.screenshot_message_container)
        guideline = screenshotView.requireViewById(R.id.guideline)

        workProfileFirstRunView = container.requireViewById(R.id.work_profile_first_run)
        detectionNoticeView = container.requireViewById(R.id.screenshot_detection_notice)

        // Restore to starting state.
        container.visibility = View.GONE
        guideline.setGuidelineEnd(0)
        workProfileFirstRunView.visibility = View.GONE
        detectionNoticeView.visibility = View.GONE
    }

    // Minimal implementation for use when Flags.SCREENSHOT_METADATA isn't turned on.
    fun onScreenshotTaken(userHandle: UserHandle) {
        if (featureFlags.isEnabled(Flags.SCREENSHOT_WORK_PROFILE_POLICY)) {
            val workProfileData = workProfileMessageController.onScreenshotTaken(userHandle)
            if (workProfileData != null) {
                workProfileFirstRunView.visibility = View.VISIBLE
                detectionNoticeView.visibility = View.GONE

                workProfileMessageController.populateView(
                    workProfileFirstRunView,
                    workProfileData,
                    this::animateOutMessageContainer
                )
                animateInMessageContainer()
            }
        }
    }

    fun onScreenshotTaken(screenshot: ScreenshotData) {
        if (featureFlags.isEnabled(Flags.SCREENSHOT_WORK_PROFILE_POLICY)) {
            val workProfileData =
                workProfileMessageController.onScreenshotTaken(screenshot.userHandle)
            var notifiedApps: List<CharSequence> = listOf()
            if (featureFlags.isEnabled(Flags.SCREENSHOT_DETECTION)) {
                notifiedApps = screenshotDetectionController.maybeNotifyOfScreenshot(screenshot)
            }

            // If work profile first run needs to show, bias towards that, otherwise show screenshot
            // detection notification if needed.
            if (workProfileData != null) {
                workProfileFirstRunView.visibility = View.VISIBLE
                detectionNoticeView.visibility = View.GONE
                workProfileMessageController.populateView(
                    workProfileFirstRunView,
                    workProfileData,
                    this::animateOutMessageContainer
                )
                animateInMessageContainer()
            } else if (notifiedApps.isNotEmpty()) {
                detectionNoticeView.visibility = View.VISIBLE
                workProfileFirstRunView.visibility = View.GONE
                screenshotDetectionController.populateView(detectionNoticeView, notifiedApps)
                animateInMessageContainer()
            }
        }
    }

    private fun animateInMessageContainer() {
        if (container.visibility == View.VISIBLE) return

        // Need the container to be fully measured before animating in (to know animation offset
        // destination)
        container.visibility = View.VISIBLE
        container.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    container.viewTreeObserver.removeOnPreDrawListener(this)
                    getAnimator(true).start()
                    return false
                }
            }
        )
    }

    private fun animateOutMessageContainer() {
        if (animateOut != null) return

        animateOut =
            getAnimator(false).apply {
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            super.onAnimationEnd(animation)
                            container.visibility = View.GONE
                            animateOut = null
                        }
                    }
                )
                start()
            }
    }

    private fun getAnimator(animateIn: Boolean): Animator {
        val params = container.layoutParams as MarginLayoutParams
        val offset = container.height + params.topMargin + params.bottomMargin
        val anim = if (animateIn) ValueAnimator.ofFloat(0f, 1f) else ValueAnimator.ofFloat(1f, 0f)
        with(anim) {
            duration = ScreenshotView.SCREENSHOT_ACTIONS_EXPANSION_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { valueAnimator: ValueAnimator ->
                val interpolation = valueAnimator.animatedValue as Float
                guideline.setGuidelineEnd((interpolation * offset).toInt())
                container.alpha = interpolation
            }
        }
        return anim
    }
}
