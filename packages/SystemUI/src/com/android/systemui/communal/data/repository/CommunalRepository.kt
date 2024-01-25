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

package com.android.systemui.communal.data.repository

import com.android.systemui.Flags.communalHub
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.scene.data.repository.SceneContainerRepository
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Encapsulates the state of communal mode. */
interface CommunalRepository {
    /** Whether communal features are enabled. */
    val isCommunalEnabled: Boolean

    /**
     * A {@link StateFlow} that tracks whether communal hub is enabled (it can be disabled in
     * settings).
     */
    val communalEnabledState: StateFlow<Boolean>

    /** Whether the communal hub is showing. */
    val isCommunalHubShowing: Flow<Boolean>

    /**
     * Target scene as requested by the underlying [SceneTransitionLayout] or through
     * [setDesiredScene].
     */
    val desiredScene: StateFlow<CommunalSceneKey>

    /** Exposes the transition state of the communal [SceneTransitionLayout]. */
    val transitionState: StateFlow<ObservableCommunalTransitionState>

    /** Updates the requested scene. */
    fun setDesiredScene(desiredScene: CommunalSceneKey)

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableCommunalTransitionState>?)
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background backgroundScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val featureFlagsClassic: FeatureFlagsClassic,
    sceneContainerFlags: SceneContainerFlags,
    sceneContainerRepository: SceneContainerRepository,
    userRepository: UserRepository,
    private val secureSettings: SecureSettings
) : CommunalRepository {

    private val communalEnabledSettingState: Flow<Boolean> =
        userRepository.selectedUserInfo
            .flatMapLatest { userInfo -> observeSettings(userInfo.id) }
            .shareIn(scope = applicationScope, started = SharingStarted.WhileSubscribed())

    override val communalEnabledState: StateFlow<Boolean> =
        if (featureFlagsClassic.isEnabled(Flags.COMMUNAL_SERVICE_ENABLED) && communalHub()) {
            communalEnabledSettingState
                .filterNotNull()
                .stateIn(
                    scope = applicationScope,
                    started = SharingStarted.Eagerly,
                    initialValue = true
                )
        } else {
            MutableStateFlow(false)
        }

    override val isCommunalEnabled: Boolean
        get() = communalEnabledState.value

    private val _desiredScene: MutableStateFlow<CommunalSceneKey> =
        MutableStateFlow(CommunalSceneKey.DEFAULT)
    override val desiredScene: StateFlow<CommunalSceneKey> = _desiredScene.asStateFlow()

    private val defaultTransitionState =
        ObservableCommunalTransitionState.Idle(CommunalSceneKey.DEFAULT)
    private val _transitionState = MutableStateFlow<Flow<ObservableCommunalTransitionState>?>(null)
    override val transitionState: StateFlow<ObservableCommunalTransitionState> =
        _transitionState
            .flatMapLatest { innerFlowOrNull -> innerFlowOrNull ?: flowOf(defaultTransitionState) }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.Lazily,
                initialValue = defaultTransitionState,
            )

    override fun setDesiredScene(desiredScene: CommunalSceneKey) {
        _desiredScene.value = desiredScene
    }

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    override fun setTransitionState(transitionState: Flow<ObservableCommunalTransitionState>?) {
        _transitionState.value = transitionState
    }

    override val isCommunalHubShowing: Flow<Boolean> =
        if (sceneContainerFlags.isEnabled()) {
            sceneContainerRepository.desiredScene.map { scene -> scene.key == SceneKey.Communal }
        } else {
            desiredScene.map { sceneKey -> sceneKey == CommunalSceneKey.Communal }
        }

    private fun observeSettings(userId: Int): Flow<Boolean> =
        secureSettings
            .observerFlow(
                userId = userId,
                names =
                    arrayOf(
                        GLANCEABLE_HUB_ENABLED,
                    )
            )
            // Force an update
            .onStart { emit(Unit) }
            .map { readFromSettings(userId) }

    private suspend fun readFromSettings(userId: Int): Boolean =
        withContext(backgroundDispatcher) {
            secureSettings.getIntForUser(GLANCEABLE_HUB_ENABLED, 1, userId) == 1
        }

    companion object {
        private const val GLANCEABLE_HUB_ENABLED = "glanceable_hub_enabled"
    }
}
