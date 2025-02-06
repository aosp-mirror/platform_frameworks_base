/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.communal.ui.viewmodel

import android.content.Context
import com.android.keyguard.KeyguardViewController
import com.android.settingslib.Utils
import com.android.systemui.accessibility.domain.interactor.AccessibilityInteractor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntrySourceInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.keyguard.ui.viewmodel.toAccessibilityHintType
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.util.kotlin.emitOnStart
import dagger.Lazy
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Simpler implementation of [DeviceEntryIconViewModel] for use in glanceable hub, where fingerprint
 * is not supported.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalLockIconViewModel
@Inject
constructor(
    @ShadeDisplayAware val context: Context,
    @ShadeDisplayAware configurationInteractor: ConfigurationInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    keyguardInteractor: KeyguardInteractor,
    private val keyguardViewController: Lazy<KeyguardViewController>,
    private val deviceEntrySourceInteractor: DeviceEntrySourceInteractor,
    accessibilityInteractor: AccessibilityInteractor,
) {

    private val isUnlocked: Flow<Boolean> =
        if (SceneContainerFlag.isEnabled) {
                deviceEntryInteractor.isUnlocked
            } else {
                keyguardInteractor.isKeyguardDismissible
            }
            .flatMapLatest { isUnlocked ->
                if (!isUnlocked) {
                    flowOf(false)
                } else {
                    flow {
                        // delay in case device ends up transitioning away from the lock screen;
                        // we don't want to animate to the unlocked icon and just let the
                        // icon fade with the transition to GONE
                        delay(DeviceEntryIconViewModel.UNLOCKED_DELAY_MS)
                        emit(true)
                    }
                }
            }

    private val iconType: Flow<DeviceEntryIconView.IconType> =
        isUnlocked.map { unlocked ->
            if (unlocked) {
                DeviceEntryIconView.IconType.UNLOCK
            } else {
                DeviceEntryIconView.IconType.LOCK
            }
        }

    val isLongPressEnabled: Flow<Boolean> =
        iconType.map { deviceEntryStatus ->
            when (deviceEntryStatus) {
                DeviceEntryIconView.IconType.UNLOCK -> true
                DeviceEntryIconView.IconType.LOCK,
                DeviceEntryIconView.IconType.FINGERPRINT,
                DeviceEntryIconView.IconType.NONE -> false
            }
        }

    val accessibilityDelegateHint: Flow<DeviceEntryIconView.AccessibilityHintType> =
        accessibilityInteractor.isEnabled.flatMapLatest { touchExplorationEnabled ->
            if (touchExplorationEnabled) {
                iconType.map { it.toAccessibilityHintType() }
            } else {
                flowOf(DeviceEntryIconView.AccessibilityHintType.NONE)
            }
        }

    private val padding: Flow<Int> =
        configurationInteractor.scaleForResolution.map { scale ->
            (context.resources.getDimensionPixelSize(R.dimen.lock_icon_padding) * scale)
                .roundToInt()
        }

    private fun getColor() =
        Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColorAccent)

    private val color: Flow<Int> =
        configurationInteractor.onAnyConfigurationChange
            .emitOnStart()
            .map { getColor() }
            .distinctUntilChanged()

    suspend fun onUserInteraction() {
        if (SceneContainerFlag.isEnabled) {
            deviceEntryInteractor.attemptDeviceEntry()
        } else {
            keyguardViewController.get().showPrimaryBouncer(/* scrim */ true)
        }
        deviceEntrySourceInteractor.attemptEnterDeviceFromDeviceEntryIcon()
    }

    val viewAttributes: Flow<CommunalLockIconAttributes> =
        combine(iconType, color, padding) { iconType, color, padding ->
            CommunalLockIconAttributes(type = iconType, tint = color, padding = padding)
        }
}

data class CommunalLockIconAttributes(
    val type: DeviceEntryIconView.IconType,
    val tint: Int,
    val padding: Int,
)
