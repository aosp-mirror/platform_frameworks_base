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

package com.android.systemui.communal.domain.interactor

import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject

/**
 * {@link CommunalBackActionInteractor} is responsible for handling back gestures on the glanceable
 * hub. When invoked SystemUI should navigate back to the lockscreen.
 */
@SysUISingleton
class CommunalBackActionInteractor
@Inject
constructor(
    private val communalInteractor: CommunalInteractor,
    private val communalSceneInteractor: CommunalSceneInteractor,
    private val sceneInteractor: SceneInteractor,
) {
    fun canBeDismissed(): Boolean {
        return communalInteractor.isCommunalShowing.value
    }

    fun onBackPressed() {
        if (SceneContainerFlag.isEnabled) {
            // TODO(b/384610333): Properly determine whether to go to dream or lockscreen on back.
            sceneInteractor.changeScene(
                toScene = Scenes.Lockscreen,
                loggingReason = "CommunalBackActionInteractor",
            )
        } else {
            communalSceneInteractor.changeScene(
                newScene = CommunalScenes.Blank,
                loggingReason = "CommunalBackActionInteractor",
            )
        }
    }
}
