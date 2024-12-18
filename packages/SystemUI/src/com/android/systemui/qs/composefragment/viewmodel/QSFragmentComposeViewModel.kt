/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.composefragment.viewmodel

import android.content.res.Resources
import android.graphics.Rect
import androidx.annotation.FloatRange
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LifecycleCoroutineScope
import com.android.systemui.Dumpable
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.disableflags.data.repository.DisableFlagsRepository
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.util.LargeScreenUtils
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printSection
import com.android.systemui.util.println
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.io.PrintWriter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QSFragmentComposeViewModel
@AssistedInject
constructor(
    val containerViewModel: QuickSettingsContainerViewModel,
    @Main private val resources: Resources,
    private val footerActionsViewModelFactory: FooterActionsViewModel.Factory,
    private val footerActionsController: FooterActionsController,
    private val sysuiStatusBarStateController: SysuiStatusBarStateController,
    private val keyguardBypassController: KeyguardBypassController,
    private val disableFlagsRepository: DisableFlagsRepository,
    private val largeScreenShadeInterpolator: LargeScreenShadeInterpolator,
    private val configurationInteractor: ConfigurationInteractor,
    private val largeScreenHeaderHelper: LargeScreenHeaderHelper,
    @Assisted private val lifecycleScope: LifecycleCoroutineScope,
) : Dumpable {
    val footerActionsViewModel =
        footerActionsViewModelFactory.create(lifecycleScope).also {
            lifecycleScope.launch { footerActionsController.init() }
        }

    private val _qsBounds = MutableStateFlow(Rect())

    private val _qsExpanded = MutableStateFlow(false)
    var isQSExpanded: Boolean
        get() = _qsExpanded.value
        set(value) {
            _qsExpanded.value = value
        }

    private val _qsVisible = MutableStateFlow(false)
    val qsVisible = _qsVisible.asStateFlow()
    var isQSVisible: Boolean
        get() = qsVisible.value
        set(value) {
            _qsVisible.value = value
        }

    // This can only be negative if undefined (in which case it will be -1f), else it will be
    // in [0, 1]. In some cases, it could be set back to -1f internally to indicate that it's
    // different to every value in [0, 1].
    @FloatRange(from = -1.0, to = 1.0) private val _qsExpansion = MutableStateFlow(-1f)
    var qsExpansionValue: Float
        get() = _qsExpansion.value
        set(value) {
            if (value < 0f) {
                _qsExpansion.value = -1f
            }
            _qsExpansion.value = value.coerceIn(0f, 1f)
        }

    private val _panelFraction = MutableStateFlow(0f)
    var panelExpansionFractionValue: Float
        get() = _panelFraction.value
        set(value) {
            _panelFraction.value = value
        }

    private val _squishinessFraction = MutableStateFlow(0f)
    var squishinessFractionValue: Float
        get() = _squishinessFraction.value
        set(value) {
            _squishinessFraction.value = value
        }

    val qqsHeaderHeight =
        configurationInteractor.onAnyConfigurationChange
            .map {
                if (LargeScreenUtils.shouldUseLargeScreenShadeHeader(resources)) {
                    0
                } else {
                    largeScreenHeaderHelper.getLargeScreenHeaderHeight()
                }
            }
            .stateIn(lifecycleScope, SharingStarted.WhileSubscribed(), 0)

    private val _headerAnimating = MutableStateFlow(false)

    private val _stackScrollerOverscrolling = MutableStateFlow(false)
    var stackScrollerOverscrollingValue: Boolean
        get() = _stackScrollerOverscrolling.value
        set(value) {
            _stackScrollerOverscrolling.value = value
        }

    /**
     * Whether QS is enabled by policy. This is normally true, except when it's disabled by some
     * policy. See [DisableFlagsRepository].
     */
    val qsEnabled =
        disableFlagsRepository.disableFlags
            .map { it.isQuickSettingsEnabled() }
            .stateIn(
                lifecycleScope,
                SharingStarted.WhileSubscribed(),
                disableFlagsRepository.disableFlags.value.isQuickSettingsEnabled(),
            )

    private val _showCollapsedOnKeyguard = MutableStateFlow(false)

    private val _keyguardAndExpanded = MutableStateFlow(false)

    /**
     * Tracks the current [StatusBarState]. It will switch early if the upcoming state is
     * [StatusBarState.KEYGUARD]
     */
    @get:VisibleForTesting
    val statusBarState =
        conflatedCallbackFlow {
                val callback =
                    object : StatusBarStateController.StateListener {
                        override fun onStateChanged(newState: Int) {
                            trySend(newState)
                        }

                        override fun onUpcomingStateChanged(upcomingState: Int) {
                            if (upcomingState == StatusBarState.KEYGUARD) {
                                trySend(upcomingState)
                            }
                        }
                    }
                sysuiStatusBarStateController.addCallback(callback)

                awaitClose { sysuiStatusBarStateController.removeCallback(callback) }
            }
            .stateIn(
                lifecycleScope,
                SharingStarted.WhileSubscribed(),
                sysuiStatusBarStateController.state,
            )

    private val _viewHeight = MutableStateFlow(0)

    private val _headerTranslation = MutableStateFlow(0f)

    private val _inSplitShade = MutableStateFlow(false)

    private val _transitioningToFullShade = MutableStateFlow(false)

    private val _lockscreenToShadeProgress = MutableStateFlow(false)

    private val _overscrolling = MutableStateFlow(false)

    private val _isSmallScreen = MutableStateFlow(false)
    var isSmallScreenValue: Boolean
        get() = _isSmallScreen.value
        set(value) {
            _isSmallScreen.value = value
        }

    private val _shouldUpdateMediaSquishiness = MutableStateFlow(false)

    private val _heightOverride = MutableStateFlow(-1)
    val heightOverride = _heightOverride.asStateFlow()
    var heightOverrideValue: Int
        get() = heightOverride.value
        set(value) {
            _heightOverride.value = value
        }

    val expansionState: StateFlow<QSExpansionState> =
        combine(_stackScrollerOverscrolling, _qsExpanded, _qsExpansion) { args: Array<Any> ->
                val expansion = args[2] as Float
                QSExpansionState(expansion.coerceIn(0f, 1f))
            }
            .stateIn(lifecycleScope, SharingStarted.WhileSubscribed(), QSExpansionState(0f))

    /**
     * Accessibility action for collapsing/expanding QS. The provided runnable is responsible for
     * determining the correct action based on the expansion state.
     */
    var collapseExpandAccessibilityAction: Runnable? = null

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.asIndenting().run {
            printSection("Quick Settings state") {
                println("isQSExpanded", isQSExpanded)
                println("isQSVisible", isQSVisible)
                println("isQSEnabled", qsEnabled.value)
                println("isCustomizing", containerViewModel.editModeViewModel.isEditing.value)
            }
            printSection("Expansion state") {
                println("qsExpansion", qsExpansionValue)
                println("panelExpansionFraction", panelExpansionFractionValue)
                println("squishinessFraction", squishinessFractionValue)
                println("expansionState", expansionState.value)
            }
            printSection("Shade state") {
                println("stackOverscrolling", stackScrollerOverscrollingValue)
                println("statusBarState", StatusBarState.toString(statusBarState.value))
                println("isSmallScreen", isSmallScreenValue)
                println("heightOverride", "${heightOverrideValue}px")
                println("qqsHeaderHeight", "${qqsHeaderHeight.value}px")
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(lifecycleScope: LifecycleCoroutineScope): QSFragmentComposeViewModel
    }

    // In the future, this will have other relevant elements like squishiness.
    data class QSExpansionState(@FloatRange(0.0, 1.0) val progress: Float)
}
