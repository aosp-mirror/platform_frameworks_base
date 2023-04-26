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

package com.android.systemui.keyguard.data.repository

import android.app.trust.TrustManager
import com.android.keyguard.logging.TrustRepositoryLogger
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.shared.model.TrustManagedModel
import com.android.systemui.keyguard.shared.model.TrustModel
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/** Encapsulates any state relevant to trust agents and trust grants. */
interface TrustRepository {
    /** Flow representing whether the current user is trusted. */
    val isCurrentUserTrusted: Flow<Boolean>

    /** Flow representing whether active unlock is available for the current user. */
    val isCurrentUserActiveUnlockAvailable: StateFlow<Boolean>

    /** Reports that whether trust is managed has changed for the current user. */
    val isCurrentUserTrustManaged: StateFlow<Boolean>
}

@SysUISingleton
class TrustRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val userRepository: UserRepository,
    private val trustManager: TrustManager,
    private val logger: TrustRepositoryLogger,
) : TrustRepository {
    private val latestTrustModelForUser = mutableMapOf<Int, TrustModel>()
    private val trustManagedForUser = mutableMapOf<Int, TrustManagedModel>()

    private val trust =
        conflatedCallbackFlow {
                val callback =
                    object : TrustManager.TrustListener {
                        override fun onTrustChanged(
                            enabled: Boolean,
                            newlyUnlocked: Boolean,
                            userId: Int,
                            flags: Int,
                            grantMsgs: List<String>?
                        ) {
                            logger.onTrustChanged(enabled, newlyUnlocked, userId, flags, grantMsgs)
                            trySendWithFailureLogging(
                                TrustModel(enabled, userId),
                                TrustRepositoryLogger.TAG,
                                "onTrustChanged"
                            )
                        }

                        override fun onTrustError(message: CharSequence?) = Unit

                        override fun onEnabledTrustAgentsChanged(userId: Int) = Unit

                        override fun onTrustManagedChanged(isTrustManaged: Boolean, userId: Int) {
                            logger.onTrustManagedChanged(isTrustManaged, userId)
                            trySendWithFailureLogging(
                                TrustManagedModel(userId, isTrustManaged),
                                TrustRepositoryLogger.TAG,
                                "onTrustManagedChanged"
                            )
                        }
                    }
                trustManager.registerTrustListener(callback)
                logger.trustListenerRegistered()
                awaitClose {
                    logger.trustListenerUnregistered()
                    trustManager.unregisterTrustListener(callback)
                }
            }
            .onEach {
                when (it) {
                    is TrustModel -> {
                        latestTrustModelForUser[it.userId] = it
                        logger.trustModelEmitted(it)
                    }
                    is TrustManagedModel -> {
                        trustManagedForUser[it.userId] = it
                        logger.trustManagedModelEmitted(it)
                    }
                }
            }
            .shareIn(applicationScope, started = SharingStarted.Eagerly, replay = 1)

    // TODO: Implement based on TrustManager callback b/267322286
    override val isCurrentUserActiveUnlockAvailable: StateFlow<Boolean> = MutableStateFlow(true)

    override val isCurrentUserTrustManaged: StateFlow<Boolean>
        get() =
            combine(trust, userRepository.selectedUserInfo, ::Pair)
                .map { isUserTrustManaged(it.second.id) }
                .distinctUntilChanged()
                .onEach { logger.isCurrentUserTrustManaged(it) }
                .onStart { emit(false) }
                .stateIn(
                    scope = applicationScope,
                    started = SharingStarted.WhileSubscribed(),
                    initialValue = false
                )

    private fun isUserTrustManaged(userId: Int) =
        trustManagedForUser[userId]?.isTrustManaged ?: false

    override val isCurrentUserTrusted: Flow<Boolean>
        get() =
            combine(trust, userRepository.selectedUserInfo, ::Pair)
                .map { latestTrustModelForUser[it.second.id]?.isTrusted ?: false }
                .distinctUntilChanged()
                .onEach { logger.isCurrentUserTrusted(it) }
                .onStart { emit(false) }
}
