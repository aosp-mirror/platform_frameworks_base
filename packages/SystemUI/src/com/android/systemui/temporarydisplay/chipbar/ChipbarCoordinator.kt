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

import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
import android.view.View.ACCESSIBILITY_LIVE_REGION_NONE
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.annotation.IdRes
import com.android.internal.widget.CachingIconView
import com.android.systemui.Gefingerpoken
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.animation.ViewHierarchyAnimator
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Text.Companion.loadText
import com.android.systemui.common.ui.binder.TextViewBinder
import com.android.systemui.common.ui.binder.TintedIconViewBinder
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.temporarydisplay.TemporaryViewDisplayController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.view.ViewUtil
import com.android.systemui.util.wakelock.WakeLock
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
    private val falsingManager: FalsingManager,
    private val falsingCollector: FalsingCollector,
    private val viewUtil: ViewUtil,
    private val vibratorHelper: VibratorHelper,
    wakeLockBuilder: WakeLock.Builder,
    systemClock: SystemClock,
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
    ) {

    private lateinit var parent: ChipbarRootView

    override val windowLayoutParams =
        commonWindowLayoutParams.apply { gravity = Gravity.TOP.or(Gravity.CENTER_HORIZONTAL) }

    override fun updateView(newInfo: ChipbarInfo, currentView: ViewGroup) {
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
        currentView.requireViewById<View>(R.id.loading).visibility =
            (newInfo.endItem == ChipbarEndItem.Loading).visibleIfTrue()

        // Error
        currentView.requireViewById<View>(R.id.error).visibility =
            (newInfo.endItem == ChipbarEndItem.Error).visibleIfTrue()

        // Button
        val buttonView = currentView.requireViewById<TextView>(R.id.end_button)
        if (newInfo.endItem is ChipbarEndItem.Button) {
            TextViewBinder.bind(buttonView, newInfo.endItem.text)

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
        maybeGetAccessibilityFocus(newInfo, currentView)

        // ---- Haptics ----
        newInfo.vibrationEffect?.let { vibratorHelper.vibrate(it) }
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
        ViewHierarchyAnimator.animateAddition(
            view.getInnerView(),
            ViewHierarchyAnimator.Hotspot.TOP,
            Interpolators.EMPHASIZED_DECELERATE,
            duration = ANIMATION_IN_DURATION,
            includeMargins = true,
            includeFadeIn = true,
            // We can only request focus once the animation finishes.
            onAnimationEnd = {
                maybeGetAccessibilityFocus(view.getTag(INFO_TAG) as ChipbarInfo?, view)
            },
        )
    }

    override fun animateViewOut(view: ViewGroup, removalReason: String?, onAnimationEnd: Runnable) {
        val innerView = view.getInnerView()
        innerView.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
        ViewHierarchyAnimator.animateRemoval(
            innerView,
            ViewHierarchyAnimator.Hotspot.TOP,
            Interpolators.EMPHASIZED_ACCELERATE,
            ANIMATION_OUT_DURATION,
            includeMargins = true,
            onAnimationEnd,
        )
    }

    private fun ViewGroup.getInnerView(): ViewGroup {
        return requireViewById(R.id.chipbar_inner)
    }

    override fun getTouchableRegion(view: View, outRect: Rect) {
        viewUtil.setRectToViewWindowLocation(view, outRect)
    }

    private fun Boolean.visibleIfTrue(): Int {
        return if (this) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
}

private const val ANIMATION_IN_DURATION = 500L
private const val ANIMATION_OUT_DURATION = 250L
@IdRes private val INFO_TAG = R.id.tag_chipbar_info
