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
 * limitations under the License.
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import com.android.systemui.R
import com.android.systemui.animation.Expandable
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.ControlsActivity
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.util.kotlin.getOrNull
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Home controls quick affordance data source. */
@SysUISingleton
class HomeControlsKeyguardQuickAffordanceConfig
@Inject
constructor(
    @Application private val context: Context,
    private val component: ControlsComponent,
) : KeyguardQuickAffordanceConfig {

    private val appContext = context.applicationContext

    override val key: String = BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS

    override val pickerName: String by lazy { context.getString(component.getTileTitleId()) }

    override val pickerIconResourceId: Int by lazy { component.getTileImageId() }

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        component.canShowWhileLockedSetting.flatMapLatest { canShowWhileLocked ->
            if (canShowWhileLocked) {
                stateInternal(component.getControlsListingController().getOrNull())
            } else {
                flowOf(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
            }
        }

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState {
        if (!component.isEnabled()) {
            return KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
        }

        val currentServices =
            component.getControlsListingController().getOrNull()?.getCurrentServices()
        val hasFavorites =
            component.getControlsController().getOrNull()?.getFavorites()?.isNotEmpty() == true
        if (currentServices.isNullOrEmpty() || !hasFavorites) {
            return KeyguardQuickAffordanceConfig.PickerScreenState.Disabled(
                instructions =
                    listOf(
                        context.getString(
                            R.string.keyguard_affordance_enablement_dialog_home_instruction_1
                        ),
                        context.getString(
                            R.string.keyguard_affordance_enablement_dialog_home_instruction_2
                        ),
                    ),
            )
        }

        return KeyguardQuickAffordanceConfig.PickerScreenState.Default
    }

    override fun onTriggered(
        expandable: Expandable?,
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.StartActivity(
            intent =
                Intent(appContext, ControlsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(
                        ControlsUiController.EXTRA_ANIMATE,
                        true,
                    ),
            canShowWhileLocked = component.canShowWhileLockedSetting.value,
        )
    }

    private fun stateInternal(
        listingController: ControlsListingController?,
    ): Flow<KeyguardQuickAffordanceConfig.LockScreenState> {
        if (listingController == null) {
            return flowOf(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }

        return conflatedCallbackFlow {
            val callback =
                object : ControlsListingController.ControlsListingCallback {
                    override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {
                        val favorites: List<StructureInfo>? =
                            component.getControlsController().getOrNull()?.getFavorites()

                        trySendWithFailureLogging(
                            state(
                                isFeatureEnabled = component.isEnabled(),
                                hasFavorites = favorites?.isNotEmpty() == true,
                                hasPanels = serviceInfos.any { it.panelActivity != null },
                                hasServiceInfos = serviceInfos.isNotEmpty(),
                                iconResourceId = component.getTileImageId(),
                                visibility = component.getVisibility(),
                            ),
                            TAG,
                        )
                    }
                }

            listingController.addCallback(callback)

            awaitClose { listingController.removeCallback(callback) }
        }
    }

    private fun state(
        isFeatureEnabled: Boolean,
        hasFavorites: Boolean,
        hasPanels: Boolean,
        hasServiceInfos: Boolean,
        visibility: ControlsComponent.Visibility,
        @DrawableRes iconResourceId: Int?,
    ): KeyguardQuickAffordanceConfig.LockScreenState {
        return if (
            isFeatureEnabled &&
                (hasFavorites || hasPanels) &&
                hasServiceInfos &&
                iconResourceId != null &&
                visibility == ControlsComponent.Visibility.AVAILABLE
        ) {
            KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                icon =
                    Icon.Resource(
                        res = iconResourceId,
                        contentDescription =
                            ContentDescription.Resource(
                                res = component.getTileTitleId(),
                            ),
                    ),
            )
        } else {
            KeyguardQuickAffordanceConfig.LockScreenState.Hidden
        }
    }

    companion object {
        private const val TAG = "HomeControlsKeyguardQuickAffordanceConfig"
    }
}
