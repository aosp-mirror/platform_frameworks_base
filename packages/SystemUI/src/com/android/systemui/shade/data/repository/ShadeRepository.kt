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

import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionListener
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.shade.domain.model.ShadeModel
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

interface ShadeRepository {
    /** ShadeModel information regarding shade expansion events */
    val shadeModel: Flow<ShadeModel>

    /**
     * Amount qs has expanded, [0-1]. 0 means fully collapsed, 1 means fully expanded. Quick
     * Settings can be expanded without the full shade expansion.
     */
    val qsExpansion: StateFlow<Float>

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
    fun setLegacyQsTracking(legacyQsTracking: Boolean)

    /** Sets whether the user is moving the shade with a pointer */
    fun setLegacyShadeTracking(tracking: Boolean)

    /** Sets whether the user is moving the shade with a pointer, on lockscreen only */
    fun setLegacyLockscreenShadeTracking(tracking: Boolean)

    /** Amount shade has expanded with regard to the UDFPS location */
    val udfpsTransitionToFullShadeProgress: StateFlow<Float>

    /** The amount QS has expanded without notifications */
    fun setQsExpansion(qsExpansion: Float)

    fun setUdfpsTransitionToFullShadeProgress(progress: Float)

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
class ShadeRepositoryImpl
@Inject
constructor(shadeExpansionStateManager: ShadeExpansionStateManager) : ShadeRepository {
    override val shadeModel: Flow<ShadeModel> =
        conflatedCallbackFlow {
                val callback =
                    object : ShadeExpansionListener {
                        override fun onPanelExpansionChanged(event: ShadeExpansionChangeEvent) {
                            // Don't propagate ShadeExpansionChangeEvent.dragDownPxAmount field.
                            // It is too noisy and produces extra events that consumers won't care
                            // about
                            val info =
                                ShadeModel(
                                    expansionAmount = event.fraction,
                                    isExpanded = event.expanded,
                                    isUserDragging = event.tracking
                                )
                            trySendWithFailureLogging(info, TAG, "updated shade expansion info")
                        }
                    }

                val currentState = shadeExpansionStateManager.addExpansionListener(callback)
                callback.onPanelExpansionChanged(currentState)

                awaitClose { shadeExpansionStateManager.removeExpansionListener(callback) }
            }
            .distinctUntilChanged()

    private val _qsExpansion = MutableStateFlow(0f)
    override val qsExpansion: StateFlow<Float> = _qsExpansion.asStateFlow()

    private val _lockscreenShadeExpansion = MutableStateFlow(0f)
    override val lockscreenShadeExpansion: StateFlow<Float> =
        _lockscreenShadeExpansion.asStateFlow()

    private var _udfpsTransitionToFullShadeProgress = MutableStateFlow(0f)
    override val udfpsTransitionToFullShadeProgress: StateFlow<Float> =
        _udfpsTransitionToFullShadeProgress.asStateFlow()

    private val _legacyShadeExpansion = MutableStateFlow(0f)
    @Deprecated("Use ShadeInteractor.shadeExpansion instead")
    override val legacyShadeExpansion: StateFlow<Float> = _legacyShadeExpansion.asStateFlow()

    private val _legacyShadeTracking = MutableStateFlow(false)
    @Deprecated("Use ShadeInteractor instead")
    override val legacyShadeTracking: StateFlow<Boolean> = _legacyShadeTracking.asStateFlow()

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
    override val legacyExpandImmediate: StateFlow<Boolean> = _legacyExpandImmediate.asStateFlow()

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

    @Deprecated("Should only be called by NPVC and tests")
    override fun setLegacyLockscreenShadeTracking(tracking: Boolean) {
        legacyLockscreenShadeTracking.value = tracking
    }

    override fun setQsExpansion(qsExpansion: Float) {
        _qsExpansion.value = qsExpansion
    }

    @Deprecated("Should only be called by NPVC and tests")
    override fun setLegacyShadeExpansion(expandedFraction: Float) {
        _legacyShadeExpansion.value = expandedFraction
    }

    override fun setLockscreenShadeExpansion(lockscreenShadeExpansion: Float) {
        _lockscreenShadeExpansion.value = lockscreenShadeExpansion
    }

    override fun setUdfpsTransitionToFullShadeProgress(progress: Float) {
        _udfpsTransitionToFullShadeProgress.value = progress
    }

    companion object {
        private const val TAG = "ShadeRepository"
    }
}
