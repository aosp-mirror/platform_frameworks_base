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

package com.android.systemui.keyguard

import android.annotation.WorkerThread
import android.content.ComponentCallbacks2
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.utils.GlobalWindowManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Releases cached resources on allocated by keyguard.
 *
 * We release most resources when device goes idle since that's the least likely time it'll cause
 * jank during use. Idle in this case means after lockscreen -> AoD transition completes or when the
 * device screen is turned off, depending on settings.
 */
@SysUISingleton
class ResourceTrimmer
@Inject
constructor(
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val globalWindowManager: GlobalWindowManager,
    @Application private val applicationScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
) : CoreStartable {

    override fun start() {
        Log.d(LOG_TAG, "Resource trimmer registered.")
        applicationScope.launch(bgDispatcher) {
            keyguardTransitionInteractor
                .isFinishedIn(scene = Scenes.Gone, stateWithoutSceneContainer = GONE)
                .filter { isOnGone -> isOnGone }
                .collect { onKeyguardGone() }
        }
    }

    @WorkerThread
    private fun onKeyguardGone() {
        // We want to clear temporary caches we've created while rendering and animating
        // lockscreen elements, especially clocks.
        Log.d(LOG_TAG, "Sending TRIM_MEMORY_UI_HIDDEN.")
        globalWindowManager.trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    }

    companion object {
        private const val LOG_TAG = "ResourceTrimmer"
    }
}
