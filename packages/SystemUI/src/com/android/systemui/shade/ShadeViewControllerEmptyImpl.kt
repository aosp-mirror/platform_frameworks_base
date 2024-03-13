/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.shade

import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.shade.domain.interactor.ShadeBackActionInteractor
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.flow.flowOf

/** Empty implementation of ShadeViewController for variants with no shade. */
class ShadeViewControllerEmptyImpl @Inject constructor() :
    ShadeViewController,
    ShadeBackActionInteractor,
    ShadeLockscreenInteractor,
    PanelExpansionInteractor {
    @Deprecated("Use ShadeInteractor instead") override fun expandToNotifications() {}
    @Deprecated("Use ShadeInteractor instead") override val isExpanded: Boolean = false
    override val isPanelExpanded: Boolean = false
    override fun animateCollapseQs(fullyCollapse: Boolean) {}
    override fun canBeCollapsed(): Boolean = false
    @Deprecated("Use ShadeAnimationInteractor instead") override val isCollapsing: Boolean = false
    @Deprecated("Use !ShadeInteractor.isAnyExpanded instead")
    override val isFullyCollapsed: Boolean = false
    override val isTracking: Boolean = false
    override val isViewEnabled: Boolean = false
    override fun shouldHideStatusBarIconsWhenExpanded() = false
    @Deprecated("Not supported by scenes") override fun blockExpansionForCurrentTouch() {}
    override fun disableHeader(state1: Int, state2: Int, animated: Boolean) {}
    override fun startExpandLatencyTracking() {}
    override fun startBouncerPreHideAnimation() {}
    override fun dozeTimeTick() {}
    override fun resetViews(animate: Boolean) {}
    override val barState: Int = 0
    @Deprecated("Only supported by very old devices that will not adopt scenes.")
    override fun closeUserSwitcherIfOpen(): Boolean {
        return false
    }
    override fun onBackPressed() {}
    @Deprecated("According to b/318376223, shade predictive back is not be supported.")
    override fun onBackProgressed(progressFraction: Float) {}
    override fun setAlpha(alpha: Int, animate: Boolean) {}
    override fun setAlphaChangeAnimationEndAction(r: Runnable) {}
    @Deprecated("Not supported by scenes") override fun setPulsing(pulsing: Boolean) {}
    override fun setQsScrimEnabled(qsScrimEnabled: Boolean) {}
    override fun setAmbientIndicationTop(ambientIndicationTop: Int, ambientTextVisible: Boolean) {}
    override fun updateSystemUiStateFlags() {}
    override fun updateTouchableRegion() {}
    override fun addOnGlobalLayoutListener(listener: ViewTreeObserver.OnGlobalLayoutListener) {}
    override fun removeOnGlobalLayoutListener(listener: ViewTreeObserver.OnGlobalLayoutListener) {}
    override fun transitionToExpandedShade(delay: Long) {}

    @Deprecated("Not supported by scenes") override fun resetViewGroupFade() {}
    @Deprecated("Not supported by scenes")
    override fun setKeyguardTransitionProgress(keyguardAlpha: Float, keyguardTranslationY: Int) {}
    @Deprecated("Not supported by scenes") override fun setOverStretchAmount(amount: Float) {}
    @Deprecated("TODO(b/325072511) delete this")
    override fun setKeyguardStatusBarAlpha(alpha: Float) {}
    override fun showAodUi() {}
    @Deprecated(
        "depends on the state you check, use {@link #isShadeFullyExpanded()},\n" +
            "{@link #isOnAod()}, {@link #isOnKeyguard()} instead."
    )
    override val isFullyExpanded = false
    override fun handleExternalTouch(event: MotionEvent): Boolean {
        return false
    }
    override fun startInputFocusTransfer() {}
    override fun cancelInputFocusTransfer() {}
    override fun finishInputFocusTransfer(velocity: Float) {}
    override fun performHapticFeedback(constant: Int) {}

    override val shadeHeadsUpTracker = ShadeHeadsUpTrackerEmptyImpl()
    override val shadeFoldAnimator = ShadeFoldAnimatorEmptyImpl()
    @Deprecated("Use SceneInteractor.currentScene instead.")
    override val legacyPanelExpansion = flowOf(0f)
}

class ShadeHeadsUpTrackerEmptyImpl : ShadeHeadsUpTracker {
    override fun addTrackingHeadsUpListener(listener: Consumer<ExpandableNotificationRow>) {}
    override fun removeTrackingHeadsUpListener(listener: Consumer<ExpandableNotificationRow>) {}
    override fun setHeadsUpAppearanceController(
        headsUpAppearanceController: HeadsUpAppearanceController?
    ) {}
    override val trackedHeadsUpNotification: ExpandableNotificationRow? = null
}

class ShadeFoldAnimatorEmptyImpl : ShadeFoldAnimator {
    override fun prepareFoldToAodAnimation() {}
    override fun startFoldToAodAnimation(
        startAction: Runnable,
        endAction: Runnable,
        cancelAction: Runnable,
    ) {}
    override fun cancelFoldToAodAnimation() {}
    override val view: ViewGroup? = null
}
