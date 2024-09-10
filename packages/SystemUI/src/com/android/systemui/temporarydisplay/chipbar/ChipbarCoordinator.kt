/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.temporarydisplay.chipbar

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.os.Process
import android.os.VibrationAttributes
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
import android.view.View.ACCESSIBILITY_LIVE_REGION_NONE
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.annotation.IdRes
import androidx.annotation.VisibleForTesting
import com.android.app.animation.Interpolators
import com.android.internal.widget.CachingIconView
import com.android.systemui.Gefingerpoken
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Text.Companion.loadText
import com.android.systemui.common.ui.binder.TextViewBinder
import com.android.systemui.common.ui.binder.TintedIconViewBinder
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.temporarydisplay.TemporaryViewDisplayController
import com.android.systemui.temporarydisplay.TemporaryViewUiEventLogger
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.view.ViewUtil
import com.android.systemui.util.wakelock.WakeLock
import java.time.Duration
import javax.inject.Inject

/**
 * A coordinator for showing/hiding the chipbar.
 *
 * The chipbar is a UI element that displays on top of all content. It appears at the top of the
 * screen and consists of an icon, one line of text, and an optional end icon or action. It will
 * auto-dismiss after some amount of seconds. The user is *not* able to manually dismiss the
 * chipbar.
 *
 * It should be only be used for critical and temporary information that the user *must* be aware
 * of. In general, prefer using heads-up notifications, since they are dismissable and will remain
 * in the list of notifications until the user dismisses them.
 *
 * Only one chipbar may be shown at a time.
 */
@SysUISingleton
open class ChipbarCoordinator
@Inject
constructor(
    context: Context,
    logger: ChipbarLogger,
    windowManager: WindowManager,
    @Main mainExecutor: DelayableExecutor,
    accessibilityManager: AccessibilityManager,
    configurationController: ConfigurationController,
    dumpManager: DumpManager,
    powerManager: PowerManager,
    private val chipbarAnimator: ChipbarAnimator,
    private val falsingManager: FalsingManager,
    private val falsingCollector: FalsingCollector,
    private val swipeChipbarAwayGestureHandler: SwipeChipbarAwayGestureHandler,
    private val viewUtil: ViewUtil,
    private val vibratorHelper: VibratorHelper,
    wakeLockBuilder: WakeLock.Builder,
    systemClock: SystemClock,
    tempViewUiEventLogger: TemporaryViewUiEventLogger,
) :
    TemporaryViewDisplayController<ChipbarInfo, ChipbarLogger>(
        context,
        logger,
        windowManager,
        mainExecutor,
        accessibilityManager,
        configurationController,
        dumpManager,
        powerManager,
        R.layout.chipbar,
        wakeLockBuilder,
        systemClock,
        tempViewUiEventLogger,
    ) {

    private lateinit var parent: ChipbarRootView

    /** The current loading information, or null we're not currently loading. */
    @VisibleForTesting
    internal var loadingDetails: LoadingDetails? = null
        private set(value) {
            // Always cancel the old one before updating
            field?.animator?.cancel()
            field = value
        }

    override val windowLayoutParams =
        commonWindowLayoutParams.apply { gravity = Gravity.TOP.or(Gravity.CENTER_HORIZONTAL) }

    override fun updateView(newInfo: ChipbarInfo, currentView: ViewGroup) {
        updateGestureListening()

        logger.logViewUpdate(
            newInfo.windowTitle,
            newInfo.text.loadText(context),
            when (newInfo.endItem) {
                null -> "null"
                is ChipbarEndItem.Loading -> "loading"
                is ChipbarEndItem.Error -> "error"
                is ChipbarEndItem.Button -> "button(${newInfo.endItem.text.loadText(context)})"
            }
        )

        currentView.setTag(INFO_TAG, newInfo)

        // Detect falsing touches on the chip.
        parent = currentView.requireViewById(R.id.chipbar_root_view)
        parent.touchHandler =
            object : Gefingerpoken {
                override fun onTouchEvent(ev: MotionEvent?): Boolean {
                    falsingCollector.onTouchEvent(ev)
                    return false
                }
            }

        // ---- Start icon ----
        val iconView = currentView.requireViewById<CachingIconView>(R.id.start_icon)
        TintedIconViewBinder.bind(newInfo.startIcon, iconView)

        // ---- Text ----
        val textView = currentView.requireViewById<TextView>(R.id.text)
        TextViewBinder.bind(textView, newInfo.text)
        // Updates text view bounds to make sure it perfectly fits the new text
        // (If the new text is smaller than the previous text) see b/253228632.
        textView.requestLayout()

        // ---- End item ----
        // Loading
        val isLoading = newInfo.endItem == ChipbarEndItem.Loading
        val loadingView = currentView.requireViewById<ImageView>(R.id.loading)
        loadingView.visibility = isLoading.visibleIfTrue()

        if (isLoading) {
            val currentLoadingDetails = loadingDetails
            // Since there can be multiple chipbars, we need to check if the loading view is the
            // same and possibly re-start the loading animation on the new view.
            if (currentLoadingDetails == null || currentLoadingDetails.loadingView != loadingView) {
                val newDetails = createLoadingDetails(loadingView)
                newDetails.animator.start()
                loadingDetails = newDetails
            }
        } else {
            loadingDetails = null
        }

        // Error
        currentView.requireViewById<View>(R.id.error).visibility =
            (newInfo.endItem == ChipbarEndItem.Error).visibleIfTrue()

        // Button
        val buttonView = currentView.requireViewById<TextView>(R.id.end_button)
        val hasButton = newInfo.endItem is ChipbarEndItem.Button
        if (hasButton) {
            TextViewBinder.bind(buttonView, (newInfo.endItem as ChipbarEndItem.Button).text)

            val onClickListener =
                View.OnClickListener { clickedView ->
                    if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY))
                        return@OnClickListener
                    newInfo.endItem.onClickListener.onClick(clickedView)
                }

            buttonView.setOnClickListener(onClickListener)
            buttonView.visibility = View.VISIBLE
        } else {
            buttonView.visibility = View.GONE
        }

        currentView
            .getInnerView()
            .setEndPadding(
                if (hasButton) R.dimen.chipbar_outer_padding_half else R.dimen.chipbar_outer_padding
            )

        // ---- Overall accessibility ----
        val iconDesc = newInfo.startIcon.icon.contentDescription
        val loadedIconDesc =
            if (iconDesc != null) {
                "${iconDesc.loadContentDescription(context)} "
            } else {
                ""
            }
        val endItemDesc =
            if (newInfo.endItem is ChipbarEndItem.Loading) {
                ". ${context.resources.getString(R.string.media_transfer_loading)}."
            } else {
                ""
            }

        val chipInnerView = currentView.getInnerView()
        chipInnerView.contentDescription =
            "$loadedIconDesc${newInfo.text.loadText(context)}$endItemDesc"
        chipInnerView.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        // Set minimum duration between content changes to 1 second in order to announce quick
        // state changes.
        chipInnerView.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.minDurationBetweenContentChanges = Duration.ofMillis(1000)
                }
            }
        maybeGetAccessibilityFocus(newInfo, currentView)

        // ---- Haptics ----
        newInfo.vibrationEffect?.let {
            vibratorHelper.vibrate(
                Process.myUid(),
                context.getApplicationContext().getPackageName(),
                it,
                newInfo.windowTitle,
                VIBRATION_ATTRIBUTES,
            )
        }
    }

    private fun maybeGetAccessibilityFocus(info: ChipbarInfo?, view: ViewGroup) {
        // Don't steal focus unless the chipbar has something interactable.
        // (The chipbar is marked as a live region, so its content will be announced whenever the
        // content changes.)
        if (info?.endItem is ChipbarEndItem.Button) {
            view.getInnerView().requestAccessibilityFocus()
        } else {
            view.getInnerView().clearAccessibilityFocus()
        }
    }

    override fun animateViewIn(view: ViewGroup) {
        // We can only request focus once the animation finishes.
        val onAnimationEnd = Runnable {
            maybeGetAccessibilityFocus(view.getTag(INFO_TAG) as ChipbarInfo?, view)
        }
        val animatedIn = chipbarAnimator.animateViewIn(view.getInnerView(), onAnimationEnd)

        // If the view doesn't get animated, the [onAnimationEnd] runnable won't get run and the
        // views would remain un-displayed. So, just force-set/run those items immediately.
        if (!animatedIn) {
            logger.logAnimateInFailure()
            chipbarAnimator.forceDisplayView(view.getInnerView())
            onAnimationEnd.run()
        }
    }

    override fun animateViewOut(view: ViewGroup, removalReason: String?, onAnimationEnd: Runnable) {
        val innerView = view.getInnerView()
        innerView.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE

        val fullEndRunnable = Runnable {
            loadingDetails = null
            onAnimationEnd.run()
        }
        val removed = chipbarAnimator.animateViewOut(innerView, fullEndRunnable)
        // If the view doesn't get animated, the [onAnimationEnd] runnable won't get run. So, just
        // run it immediately.
        if (!removed) {
            logger.logAnimateOutFailure()
            fullEndRunnable.run()
        }

        updateGestureListening()
    }

    private fun updateGestureListening() {
        val currentDisplayInfo = getCurrentDisplayInfo()
        if (currentDisplayInfo != null && currentDisplayInfo.info.allowSwipeToDismiss) {
            swipeChipbarAwayGestureHandler.setViewFetcher { currentDisplayInfo.view }
            swipeChipbarAwayGestureHandler.addOnGestureDetectedCallback(TAG) {
                onSwipeUpGestureDetected()
            }
        } else {
            swipeChipbarAwayGestureHandler.resetViewFetcher()
            swipeChipbarAwayGestureHandler.removeOnGestureDetectedCallback(TAG)
        }
    }

    private fun onSwipeUpGestureDetected() {
        val currentDisplayInfo = getCurrentDisplayInfo()
        if (currentDisplayInfo == null) {
            logger.logSwipeGestureError(id = null, errorMsg = "No info is being displayed")
            return
        }
        if (!currentDisplayInfo.info.allowSwipeToDismiss) {
            logger.logSwipeGestureError(
                id = currentDisplayInfo.info.id,
                errorMsg = "This view prohibits swipe-to-dismiss",
            )
            return
        }
        tempViewUiEventLogger.logViewManuallyDismissed(currentDisplayInfo.info.instanceId)
        removeView(currentDisplayInfo.info.id, SWIPE_UP_GESTURE_REASON)
        updateGestureListening()
    }

    private fun ViewGroup.getInnerView(): ViewGroup {
        return this.requireViewById(R.id.chipbar_inner)
    }

    override fun getTouchableRegion(view: View, outRect: Rect) {
        viewUtil.setRectToViewWindowLocation(view, outRect)
    }

    private fun View.setEndPadding(@DimenRes endPaddingDimen: Int) {
        this.setPaddingRelative(
            this.paddingStart,
            this.paddingTop,
            context.resources.getDimensionPixelSize(endPaddingDimen),
            this.paddingBottom,
        )
    }

    private fun Boolean.visibleIfTrue(): Int {
        return if (this) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun createLoadingDetails(loadingView: View): LoadingDetails {
        // Ideally, we would use a <ProgressBar> view, which would automatically handle the loading
        // spinner rotation for us. However, due to b/243983980, the ProgressBar animation
        // unexpectedly pauses when SysUI starts another window. ObjectAnimator is a workaround that
        // won't pause.
        val animator =
            ObjectAnimator.ofFloat(loadingView, View.ROTATION, 0f, 360f).apply {
                duration = LOADING_ANIMATION_DURATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = Interpolators.LINEAR
            }
        return LoadingDetails(loadingView, animator)
    }

    internal data class LoadingDetails(
        val loadingView: View,
        val animator: ObjectAnimator,
    )

    companion object {
        val VIBRATION_ATTRIBUTES: VibrationAttributes =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
    }
}

@IdRes private val INFO_TAG = R.id.tag_chipbar_info
private const val SWIPE_UP_GESTURE_REASON = "SWIPE_UP_GESTURE_DETECTED"
private const val TAG = "ChipbarCoordinator"
private const val LOADING_ANIMATION_DURATION_MS = 1000L
