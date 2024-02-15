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
 *
 */

package com.android.systemui.statusbar.notification.stack.domain.interactor

import android.content.Context
import com.android.systemui.Flags.centralizedStatusBarDimensRefactor
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.res.R
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.statusbar.policy.SplitShadeStateController
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Encapsulates business-logic specifically related to the shared notification stack container. */
@SysUISingleton
class SharedNotificationContainerInteractor
@Inject
constructor(
    configurationRepository: ConfigurationRepository,
    private val context: Context,
    private val splitShadeStateController: SplitShadeStateController,
    keyguardInteractor: KeyguardInteractor,
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    largeScreenHeaderHelperLazy: Lazy<LargeScreenHeaderHelper>,
) {

    private val _topPosition = MutableStateFlow(0f)
    val topPosition = _topPosition.asStateFlow()

    private val _notificationStackChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** An internal modification was made to notifications */
    val notificationStackChanged = _notificationStackChanged.asSharedFlow()

    val configurationBasedDimensions: Flow<ConfigurationBasedDimensions> =
        configurationRepository.onAnyConfigurationChange
            .onStart { emit(Unit) }
            .map { _ ->
                with(context.resources) {
                    ConfigurationBasedDimensions(
                        useSplitShade =
                            splitShadeStateController.shouldUseSplitNotificationShade(
                                context.resources
                            ),
                        useLargeScreenHeader =
                            getBoolean(R.bool.config_use_large_screen_shade_header),
                        marginHorizontal =
                            getDimensionPixelSize(R.dimen.notification_panel_margin_horizontal),
                        marginBottom =
                            getDimensionPixelSize(R.dimen.notification_panel_margin_bottom),
                        marginTop = getDimensionPixelSize(R.dimen.notification_panel_margin_top),
                        marginTopLargeScreen =
                            if (centralizedStatusBarDimensRefactor()) {
                                largeScreenHeaderHelperLazy.get().getLargeScreenHeaderHeight()
                            } else {
                                getDimensionPixelSize(R.dimen.large_screen_shade_header_height)
                            },
                        keyguardSplitShadeTopMargin =
                            getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin),
                    )
                }
            }
            .distinctUntilChanged()

    /**
     * The notification shelf can extend over the lock icon area if:
     * * UDFPS supported. Ambient indication will always appear below
     * * UDFPS not supported and ambient indication not visible, which will appear above lock icon
     */
    val useExtraShelfSpace: Flow<Boolean> =
        combine(
            keyguardInteractor.ambientIndicationVisible,
            deviceEntryUdfpsInteractor.isUdfpsSupported,
        ) { ambientIndicationVisible, isUdfpsSupported ->
            isUdfpsSupported || !ambientIndicationVisible
        }

    val isSplitShadeEnabled: Flow<Boolean> =
        configurationBasedDimensions
            .map { dimens: ConfigurationBasedDimensions -> dimens.useSplitShade }
            .distinctUntilChanged()

    /** Top position (without translation) of the shared container. */
    fun setTopPosition(top: Float) {
        _topPosition.value = top
    }

    /** An internal modification was made to notifications */
    fun notificationStackChanged() {
        _notificationStackChanged.tryEmit(Unit)
    }

    data class ConfigurationBasedDimensions(
        val useSplitShade: Boolean,
        val useLargeScreenHeader: Boolean,
        val marginHorizontal: Int,
        val marginBottom: Int,
        val marginTop: Int,
        val marginTopLargeScreen: Int,
        val keyguardSplitShadeTopMargin: Int,
    )
}
