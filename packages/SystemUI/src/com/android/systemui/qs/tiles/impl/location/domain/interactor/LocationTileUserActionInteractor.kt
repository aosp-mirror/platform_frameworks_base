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

package com.android.systemui.qs.tiles.impl.location.domain.interactor

import android.content.Intent
import android.provider.Settings
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.location.domain.model.LocationTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.LocationController
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Handles location tile clicks. */
class LocationTileUserActionInteractor
@Inject
constructor(
    @Background private val coroutineContext: CoroutineContext,
    @Application private val applicationScope: CoroutineScope,
    private val locationController: LocationController,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val activityStarter: ActivityStarter,
    private val keyguardController: KeyguardStateController,
) : QSTileUserActionInteractor<LocationTileModel> {
    override suspend fun handleInput(input: QSTileInput<LocationTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    val wasEnabled: Boolean = input.data.isEnabled
                    if (keyguardController.isMethodSecure() && keyguardController.isShowing()) {
                        activityStarter.postQSRunnableDismissingKeyguard {
                            CoroutineScope(applicationScope.coroutineContext).launch {
                                locationController.setLocationEnabled(!wasEnabled)
                            }
                        }
                    } else {
                        withContext(coroutineContext) {
                            locationController.setLocationEnabled(!wasEnabled)
                        }
                    }
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(
                        action.expandable,
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    )
                }
            }
        }
}
