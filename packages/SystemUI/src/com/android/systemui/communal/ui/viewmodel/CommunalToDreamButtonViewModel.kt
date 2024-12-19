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

package com.android.systemui.communal.ui.viewmodel

import android.annotation.SuppressLint
import android.app.DreamManager
import android.content.Intent
import android.provider.Settings
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.util.kotlin.isDevicePluggedIn
import com.android.systemui.util.kotlin.sample
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommunalToDreamButtonViewModel
@AssistedInject
constructor(
    @Background private val backgroundContext: CoroutineContext,
    batteryController: BatteryController,
    private val settingsInteractor: CommunalSettingsInteractor,
    private val activityStarter: ActivityStarter,
    private val dreamManager: DreamManager,
) : ExclusiveActivatable() {

    private val _requests = Channel<Unit>(Channel.BUFFERED)

    /** Whether we should show a button on hub to switch to dream. */
    @SuppressLint("MissingPermission")
    val shouldShowDreamButtonOnHub =
        batteryController.isDevicePluggedIn().distinctUntilChanged().flowOn(backgroundContext)

    /** Handle a tap on the "show dream" button. */
    fun onShowDreamButtonTap() {
        _requests.trySend(Unit)
    }

    @SuppressLint("MissingPermission")
    override suspend fun onActivated(): Nothing = coroutineScope {
        launch {
            _requests
                .receiveAsFlow()
                .sample(settingsInteractor.isScreensaverEnabled)
                .collectLatest { enabled ->
                    withContext(backgroundContext) {
                        if (enabled) {
                            dreamManager.startDream()
                        } else {
                            activityStarter.postStartActivityDismissingKeyguard(
                                Intent(Settings.ACTION_DREAM_SETTINGS),
                                0,
                            )
                        }
                    }
                }
        }

        awaitCancellation()
    }

    @AssistedFactory
    interface Factory {
        fun create(): CommunalToDreamButtonViewModel
    }
}
