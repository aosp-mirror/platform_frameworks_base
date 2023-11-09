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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Encapsulates the state of communal mode. */
interface CommunalRepository {
    /** Whether communal features are enabled. */
    val isCommunalEnabled: Boolean

    /**
     * Target scene as requested by the underlying [SceneTransitionLayout] or through
     * [setDesiredScene].
     */
    val desiredScene: StateFlow<CommunalSceneKey>

    /** Updates the requested scene. */
    fun setDesiredScene(desiredScene: CommunalSceneKey)
}

@SysUISingleton
class CommunalRepositoryImpl
@Inject
constructor(
    private val featureFlagsClassic: FeatureFlagsClassic,
) : CommunalRepository {
    override val isCommunalEnabled: Boolean
        get() = featureFlagsClassic.isEnabled(Flags.COMMUNAL_SERVICE_ENABLED) && communalHub()

    private val _desiredScene: MutableStateFlow<CommunalSceneKey> =
        MutableStateFlow(CommunalSceneKey.Blank)
    override val desiredScene: StateFlow<CommunalSceneKey> = _desiredScene.asStateFlow()

    override fun setDesiredScene(desiredScene: CommunalSceneKey) {
        _desiredScene.value = desiredScene
    }
}
