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

package com.android.systemui.accessibility.data.repository

import android.annotation.SuppressLint
import android.view.accessibility.CaptioningManager
import com.android.systemui.accessibility.data.model.CaptioningModel
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.user.utils.UserScopedService
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

interface CaptioningRepository {

    /** Current state of Live Captions. */
    val captioningModel: StateFlow<CaptioningModel?>

    /** Sets [CaptioningModel.isSystemAudioCaptioningEnabled]. */
    suspend fun setIsSystemAudioCaptioningEnabled(isEnabled: Boolean)
}

@OptIn(ExperimentalCoroutinesApi::class)
class CaptioningRepositoryImpl
@Inject
constructor(
    private val userScopedCaptioningManagerProvider: UserScopedService<CaptioningManager>,
    userRepository: UserRepository,
    @Background private val backgroundCoroutineContext: CoroutineContext,
    @Application coroutineScope: CoroutineScope,
) : CaptioningRepository {

    @SuppressLint("NonInjectedService") // this uses user-aware context
    private val captioningManager: StateFlow<CaptioningManager?> =
        userRepository.selectedUser
            .map { userScopedCaptioningManagerProvider.forUser(it.userInfo.userHandle) }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), null)

    override val captioningModel: StateFlow<CaptioningModel?> =
        captioningManager
            .filterNotNull()
            .flatMapLatest { it.captioningModel() }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), null)

    override suspend fun setIsSystemAudioCaptioningEnabled(isEnabled: Boolean) {
        withContext(backgroundCoroutineContext) {
            captioningManager.value?.isSystemAudioCaptioningEnabled = isEnabled
        }
    }

    private fun CaptioningManager.captioningModel(): Flow<CaptioningModel> {
        return conflatedCallbackFlow {
                val listener =
                    object : CaptioningManager.CaptioningChangeListener() {

                        override fun onSystemAudioCaptioningChanged(enabled: Boolean) {
                            trySend(Unit)
                        }

                        override fun onSystemAudioCaptioningUiChanged(enabled: Boolean) {
                            trySend(Unit)
                        }
                    }
                addCaptioningChangeListener(listener)
                awaitClose { removeCaptioningChangeListener(listener) }
            }
            .onStart { emit(Unit) }
            .map {
                CaptioningModel(
                    isSystemAudioCaptioningEnabled = isSystemAudioCaptioningEnabled,
                    isSystemAudioCaptioningUiEnabled = isSystemAudioCaptioningUiEnabled,
                )
            }
            .flowOn(backgroundCoroutineContext)
    }
}
