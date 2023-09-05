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
     * 1 means fully expanded.
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
