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

package com.android.systemui.security.data.repository

import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.security.data.model.SecurityModel
import com.android.systemui.statusbar.policy.SecurityController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

interface SecurityRepository {
    /** The current [SecurityModel]. */
    val security: Flow<SecurityModel>
}

@SysUISingleton
class SecurityRepositoryImpl
@Inject
constructor(
    private val securityController: SecurityController,
    @Background private val bgDispatcher: CoroutineDispatcher,
) : SecurityRepository {
    override val security: Flow<SecurityModel> = conflatedCallbackFlow {
        suspend fun updateState() {
            trySendWithFailureLogging(SecurityModel.create(securityController, bgDispatcher), TAG)
        }

        val callback = SecurityController.SecurityControllerCallback { launch { updateState() } }

        securityController.addCallback(callback)
        updateState()
        awaitClose { securityController.removeCallback(callback) }
    }

    companion object {
        private const val TAG = "SecurityRepositoryImpl"
    }
}
