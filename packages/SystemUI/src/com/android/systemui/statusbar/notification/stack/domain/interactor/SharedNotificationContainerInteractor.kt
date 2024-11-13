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
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.policy.SplitShadeStateController
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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
    private val splitShadeStateController: Lazy<SplitShadeStateController>,
    private val shadeInteractor: Lazy<ShadeInteractor>,
    keyguardInteractor: KeyguardInteractor,
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    largeScreenHeaderHelperLazy: Lazy<LargeScreenHeaderHelper>,
) {

    private val _topPosition = MutableStateFlow(0f)
    val topPosition = _topPosition.asStateFlow()

    private val _notificationStackChanged = MutableStateFlow(0L)
    /** An internal modification was made to notifications */
    val notificationStackChanged = _notificationStackChanged.debounce(20L)

    private val configurationChangeEvents =
        configurationRepository.onAnyConfigurationChange.onStart { emit(Unit) }

    /* Warning: Even though the value it emits only contains the split shade status, this flow must
     * emit a value whenever the configuration *or* the split shade status changes. Adding a
     * distinctUntilChanged() to this would cause configurationBasedDimensions to miss configuration
     * updates that affect other resources, like margins or the large screen header flag.
     */
    private val dimensionsUpdateEventsWithShouldUseSplitShade: Flow<Boolean> =
        if (SceneContainerFlag.isEnabled) {
            combine(configurationChangeEvents, shadeInteractor.get().isShadeLayoutWide) {
                _,
                isShadeLayoutWide ->
                isShadeLayoutWide
            }
        } else {
            configurationChangeEvents.map {
                splitShadeStateController.get().shouldUseSplitNotificationShade(context.resources)
            }
        }

    val configurationBasedDimensions: Flow<ConfigurationBasedDimensions> =
        dimensionsUpdateEventsWithShouldUseSplitShade
            .map { shouldUseSplitShade ->
                with(context.resources) {
                    ConfigurationBasedDimensions(
                        useSplitShade = shouldUseSplitShade,
                        useLargeScreenHeader =
                            getBoolean(R.bool.config_use_large_screen_shade_header),
                        marginHorizontal =
                            getDimensionPixelSize(R.dimen.notification_panel_margin_horizontal),
                        marginBottom =
                            getDimensionPixelSize(R.dimen.notification_panel_margin_bottom),
                        marginTop = getDimensionPixelSize(R.dimen.notification_panel_margin_top),
                        marginTopLargeScreen =
                            largeScreenHeaderHelperLazy.get().getLargeScreenHeaderHeight(),
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
        _notificationStackChanged.value = _notificationStackChanged.value + 1
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
