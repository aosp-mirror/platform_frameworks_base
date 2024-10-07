/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.shade.data.repository

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Data for the shade, mostly related to expansion of the shade and quick settings. */
interface ShadeRepository {
    /**
     * Amount qs has expanded, [0-1]. 0 means fully collapsed, 1 means fully expanded. Quick
     * Settings can be expanded without the full shade expansion.
     */
    @Deprecated("Use ShadeInteractor.qsExpansion instead") val qsExpansion: StateFlow<Float>

    /** Amount shade has expanded with regard to the UDFPS location */
    val udfpsTransitionToFullShadeProgress: StateFlow<Float>

    /**
     * Information about the currently running fling animation, or null if no fling animation is
     * running.
     */
    val currentFling: StateFlow<FlingInfo?>

    /**
     * The amount the lockscreen shade has dragged down by the user, [0-1]. 0 means fully collapsed,
     * 1 means fully expanded. Value resets to 0 when the user finishes dragging.
     */
    val lockscreenShadeExpansion: StateFlow<Float>

    /**
     * NotificationPanelViewController.mExpandedFraction as a StateFlow. This nominally represents
     * the amount the shade has expanded 0-1 like many other flows in this repo, but there are cases
     * where its value will be 1 and no shade will be rendered, e.g. whenever the keyguard is
     * visible and when quick settings is expanded. The confusing nature and impending deletion of
     * this makes it unsuitable for future development, so usage is discouraged.
     */
    @Deprecated("Use ShadeInteractor.shadeExpansion instead")
    val legacyShadeExpansion: StateFlow<Float>

    /**
     * NotificationPanelViewController.mTracking as a flow. "Tracking" means that the user is moving
     * the shade up or down with a pointer. Going forward, this concept will be replaced by checks
     * for whether a transition was driven by user input instead of whether a pointer is currently
     * touching the screen, i.e. after the user has lifted their finger to fling the shade, these
     * values would be different.
     */
    @Deprecated("Use ShadeInteractor instead") val legacyShadeTracking: StateFlow<Boolean>

    /** Specifically tracks the user expanding the shade on the lockscreen only */
    @Deprecated("Use ShadeInteractor.isUserInteractingWithShade instead")
    val legacyLockscreenShadeTracking: MutableStateFlow<Boolean>

    /**
     * QuickSettingsController.mTracking as a flow. "Tracking" means that the user is moving quick
     * settings up or down with a pointer. Going forward, this concept will be replaced by checks
     * for whether a transition was driven by user input instead of whether a pointer is currently
     * touching the screen, i.e. after the user has lifted their finger to fling the QS, these
     * values would be different.
     */
    @Deprecated("Use ShadeInteractor instead") val legacyQsTracking: StateFlow<Boolean>

    /**
     * NotificationPanelViewController.mPanelExpanded as a flow. This value is true whenever the
     * expansion fraction is greater than zero or NPVC is about to accept an input transfer from the
     * status bar, home screen, or trackpad.
     */
    @Deprecated("Use ShadeInteractor instead")
    val legacyExpandedOrAwaitingInputTransfer: StateFlow<Boolean>

    /**
     * QuickSettingsController.mExpanded as a flow. Indicates that QS is in expanded state:
     * - single pane shade: expanding shade and then expanding QS
     * - split shade: just expanding shade (QS are expanded automatically)
     */
    @Deprecated("Use ShadeInteractor instead") val legacyIsQsExpanded: StateFlow<Boolean>

    /**
     * QuickSettingsController.mExpandImmediate as a flow. Indicates that Quick Settings is being
     * expanded without first expanding the Shade or Quick Settings is being collapsed without first
     * collapsing to shade, i.e. expanding with 2-finger swipe or collapsing by flinging from the
     * bottom of the screen. Replaced by ShadeInteractor.isQsBypassingShade.
     */
    @Deprecated("Use ShadeInteractor.isQsBypassingShade instead")
    val legacyExpandImmediate: StateFlow<Boolean>

    /**
     * Whether the shade layout should be wide (true) or narrow (false).
     *
     * In a wide layout, notifications and quick settings each take up only half the screen width
     * (whether they are shown at the same time or not). In a narrow layout, they can each be as
     * wide as the entire screen.
     */
    val isShadeLayoutWide: StateFlow<Boolean>

    /** True when QS is taking up the entire screen, i.e. fully expanded on a non-unfolded phone. */
    @Deprecated("Use ShadeInteractor instead") val legacyQsFullscreen: StateFlow<Boolean>

    /** NPVC.mClosing as a flow. */
    @Deprecated("Use ShadeAnimationInteractor instead") val legacyIsClosing: StateFlow<Boolean>

    /** Sets whether the shade layout should be wide (true) or narrow (false). */
    fun setShadeLayoutWide(isShadeLayoutWide: Boolean)

    /** Sets whether a closing animation is happening. */
    @Deprecated("Use ShadeAnimationInteractor instead") fun setLegacyIsClosing(isClosing: Boolean)

    /**  */
    @Deprecated("Use ShadeInteractor instead")
    fun setLegacyQsFullscreen(legacyQsFullscreen: Boolean)

    /**
     * Sets whether Quick Settings is being expanded without first expanding the Shade or Quick
     * Settings is being collapsed without first collapsing to shade.
     */
    @Deprecated("Use ShadeInteractor instead")
    fun setLegacyExpandImmediate(legacyExpandImmediate: Boolean)

    /** Sets whether QS is expanded. */
    @Deprecated("Use ShadeInteractor instead")
    fun setLegacyIsQsExpanded(legacyIsQsExpanded: Boolean)

    /**
     * Sets whether the expansion fraction is greater than zero or NPVC is about to accept an input
     * transfer from the status bar, home screen, or trackpad.
     */
    @Deprecated("Use ShadeInteractor instead")
    fun setLegacyExpandedOrAwaitingInputTransfer(legacyExpandedOrAwaitingInputTransfer: Boolean)

    /** Sets whether the user is moving Quick Settings with a pointer */
    @Deprecated("Use ShadeInteractor instead") fun setLegacyQsTracking(legacyQsTracking: Boolean)

    /** Sets whether the user is moving the shade with a pointer */
    @Deprecated("Use ShadeInteractor instead") fun setLegacyShadeTracking(tracking: Boolean)

    /** Sets whether the user is moving the shade with a pointer, on lockscreen only */
    @Deprecated("Use ShadeInteractor instead")
    fun setLegacyLockscreenShadeTracking(tracking: Boolean)

    /** The amount QS has expanded without notifications */
    fun setQsExpansion(qsExpansion: Float)

    fun setUdfpsTransitionToFullShadeProgress(progress: Float)

    /**
     * Sets the [FlingInfo] of the currently animating fling. If [info] is null, no fling is
     * animating.
     */
    fun setCurrentFling(info: FlingInfo?)

    /**
     * Set the amount the shade has dragged down by the user, [0-1]. 0 means fully collapsed, 1
     * means fully expanded.
     */
    fun setLockscreenShadeExpansion(lockscreenShadeExpansion: Float)

    /**
     * Set the legacy expansion value. This should only be called whenever the value of
     * NotificationPanelViewController.mExpandedFraction changes or in tests.
     */
    @Deprecated("Should only be called by NPVC and tests")
    fun setLegacyShadeExpansion(expandedFraction: Float)
}

/** Business logic for shade interactions */
@SysUISingleton
class ShadeRepositoryImpl @Inject constructor(@Application applicationContext: Context) :
    ShadeRepository {
    private val _qsExpansion = MutableStateFlow(0f)
    @Deprecated("Use ShadeInteractor.qsExpansion instead")
    override val qsExpansion: StateFlow<Float> = _qsExpansion.asStateFlow()

    private val _lockscreenShadeExpansion = MutableStateFlow(0f)
    override val lockscreenShadeExpansion: StateFlow<Float> =
        _lockscreenShadeExpansion.asStateFlow()

    private var _udfpsTransitionToFullShadeProgress = MutableStateFlow(0f)
    override val udfpsTransitionToFullShadeProgress: StateFlow<Float> =
        _udfpsTransitionToFullShadeProgress.asStateFlow()

    private val _currentFling: MutableStateFlow<FlingInfo?> = MutableStateFlow(null)
    override val currentFling: StateFlow<FlingInfo?> = _currentFling.asStateFlow()

    private val _legacyShadeExpansion = MutableStateFlow(0f)
    @Deprecated("Use ShadeInteractor.shadeExpansion instead")
    override val legacyShadeExpansion: StateFlow<Float> = _legacyShadeExpansion.asStateFlow()

    private val _legacyShadeTracking = MutableStateFlow(false)
    @Deprecated("Use ShadeInteractor instead")
    override val legacyShadeTracking: StateFlow<Boolean> = _legacyShadeTracking.asStateFlow()

    @Deprecated("Use ShadeInteractor.isUserInteractingWithShade instead")
    override val legacyLockscreenShadeTracking = MutableStateFlow(false)

    private val _legacyQsTracking = MutableStateFlow(false)
    @Deprecated("Use ShadeInteractor instead")
    override val legacyQsTracking: StateFlow<Boolean> = _legacyQsTracking.asStateFlow()

    private val _legacyExpandedOrAwaitingInputTransfer = MutableStateFlow(false)
    @Deprecated("Use ShadeInteractor instead")
    override val legacyExpandedOrAwaitingInputTransfer: StateFlow<Boolean> =
        _legacyExpandedOrAwaitingInputTransfer.asStateFlow()

    private val _legacyIsQsExpanded = MutableStateFlow(false)
    @Deprecated("Use ShadeInteractor instead")
    override val legacyIsQsExpanded: StateFlow<Boolean> = _legacyIsQsExpanded.asStateFlow()

    private val _legacyExpandImmediate = MutableStateFlow(false)
    @Deprecated("Use ShadeInteractor instead")
    override val legacyExpandImmediate: StateFlow<Boolean> = _legacyExpandImmediate.asStateFlow()

    private val _legacyQsFullscreen = MutableStateFlow(false)
    @Deprecated("Use ShadeInteractor instead")
    override val legacyQsFullscreen: StateFlow<Boolean> = _legacyQsFullscreen.asStateFlow()

    private val _isShadeLayoutWide = MutableStateFlow(false)
    override val isShadeLayoutWide: StateFlow<Boolean> = _isShadeLayoutWide.asStateFlow()

    override fun setShadeLayoutWide(isShadeLayoutWide: Boolean) {
        _isShadeLayoutWide.value = isShadeLayoutWide
    }

    @Deprecated("Use ShadeInteractor instead")
    override fun setLegacyQsFullscreen(legacyQsFullscreen: Boolean) {
        _legacyQsFullscreen.value = legacyQsFullscreen
    }

    @Deprecated("Use ShadeInteractor instead")
    override fun setLegacyExpandImmediate(legacyExpandImmediate: Boolean) {
        _legacyExpandImmediate.value = legacyExpandImmediate
    }

    @Deprecated("Use ShadeInteractor instead")
    override fun setLegacyIsQsExpanded(legacyIsQsExpanded: Boolean) {
        _legacyIsQsExpanded.value = legacyIsQsExpanded
    }

    @Deprecated("Use ShadeInteractor instead")
    override fun setLegacyExpandedOrAwaitingInputTransfer(
        legacyExpandedOrAwaitingInputTransfer: Boolean
    ) {
        _legacyExpandedOrAwaitingInputTransfer.value = legacyExpandedOrAwaitingInputTransfer
    }

    @Deprecated("Should only be called by NPVC and tests")
    override fun setLegacyQsTracking(legacyQsTracking: Boolean) {
        _legacyQsTracking.value = legacyQsTracking
    }

    @Deprecated("Should only be called by NPVC and tests")
    override fun setLegacyShadeTracking(tracking: Boolean) {
        _legacyShadeTracking.value = tracking
    }

    private val _legacyIsClosing = MutableStateFlow(false)
    @Deprecated("Use ShadeInteractor instead")
    override val legacyIsClosing: StateFlow<Boolean> = _legacyIsClosing.asStateFlow()

    @Deprecated("Use ShadeInteractor instead")
    override fun setLegacyIsClosing(isClosing: Boolean) {
        _legacyIsClosing.value = isClosing
    }

    @Deprecated("Should only be called by NPVC and tests")
    override fun setLegacyLockscreenShadeTracking(tracking: Boolean) {
        legacyLockscreenShadeTracking.value = tracking
    }

    override fun setQsExpansion(qsExpansion: Float) {
        _qsExpansion.value = qsExpansion
    }

    override fun setLockscreenShadeExpansion(lockscreenShadeExpansion: Float) {
        _lockscreenShadeExpansion.value = lockscreenShadeExpansion
    }

    override fun setUdfpsTransitionToFullShadeProgress(progress: Float) {
        _udfpsTransitionToFullShadeProgress.value = progress
    }

    override fun setCurrentFling(info: FlingInfo?) {
        _currentFling.value = info
    }

    @Deprecated("Should only be called by NPVC and tests")
    override fun setLegacyShadeExpansion(expandedFraction: Float) {
        _legacyShadeExpansion.value = expandedFraction
    }

    companion object {
        private const val TAG = "ShadeRepository"
    }
}
