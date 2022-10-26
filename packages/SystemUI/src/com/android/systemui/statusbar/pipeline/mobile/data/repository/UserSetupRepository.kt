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
 */

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Repository to observe the state of [DeviceProvisionedController.isUserSetup]. This information
 * can change some policy related to display
 */
interface UserSetupRepository {
    /** Observable tracking [DeviceProvisionedController.isUserSetup] */
    val isUserSetupFlow: StateFlow<Boolean>
}

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class UserSetupRepositoryImpl
@Inject
constructor(
    private val deviceProvisionedController: DeviceProvisionedController,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application scope: CoroutineScope,
) : UserSetupRepository {
    /** State flow that tracks [DeviceProvisionedController.isUserSetup] */
    override val isUserSetupFlow: StateFlow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : DeviceProvisionedController.DeviceProvisionedListener {
                        override fun onUserSetupChanged() {
                            trySend(Unit)
                        }
                    }

                deviceProvisionedController.addCallback(callback)

                awaitClose { deviceProvisionedController.removeCallback(callback) }
            }
            .onStart { emit(Unit) }
            .mapLatest { fetchUserSetupState() }
            .stateIn(scope, started = SharingStarted.WhileSubscribed(), initialValue = false)

    private suspend fun fetchUserSetupState(): Boolean =
        withContext(bgDispatcher) { deviceProvisionedController.isCurrentUserSetup }
}
