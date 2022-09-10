/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.quickaffordance

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.containeddrawable.ContainedDrawable
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
    @Application context: Context,
    private val component: ControlsComponent,
) : KeyguardQuickAffordanceConfig {

    private val appContext = context.applicationContext

    override val state: Flow<KeyguardQuickAffordanceConfig.State> =
        component.canShowWhileLockedSetting.flatMapLatest { canShowWhileLocked ->
            if (canShowWhileLocked) {
                stateInternal(component.getControlsListingController().getOrNull())
            } else {
                flowOf(KeyguardQuickAffordanceConfig.State.Hidden)
            }
        }

    override fun onQuickAffordanceClicked(
        animationController: ActivityLaunchAnimator.Controller?,
    ): KeyguardQuickAffordanceConfig.OnClickedResult {
        return KeyguardQuickAffordanceConfig.OnClickedResult.StartActivity(
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
    ): Flow<KeyguardQuickAffordanceConfig.State> {
        if (listingController == null) {
            return flowOf(KeyguardQuickAffordanceConfig.State.Hidden)
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
        hasServiceInfos: Boolean,
        visibility: ControlsComponent.Visibility,
        @DrawableRes iconResourceId: Int?,
    ): KeyguardQuickAffordanceConfig.State {
        return if (
            isFeatureEnabled &&
                hasFavorites &&
                hasServiceInfos &&
                iconResourceId != null &&
                visibility == ControlsComponent.Visibility.AVAILABLE
        ) {
            KeyguardQuickAffordanceConfig.State.Visible(
                icon = ContainedDrawable.WithResource(iconResourceId),
                contentDescriptionResourceId = component.getTileTitleId(),
            )
        } else {
            KeyguardQuickAffordanceConfig.State.Hidden
        }
    }

    companion object {
        private const val TAG = "HomeControlsKeyguardQuickAffordanceConfig"
    }
}
