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
import android.graphics.HardwareRenderer
import android.os.Trace
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.utils.GlobalWindowManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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
    private val keyguardInteractor: KeyguardInteractor,
    private val powerInteractor: PowerInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val globalWindowManager: GlobalWindowManager,
    @Application private val applicationScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val featureFlags: FeatureFlags,
    private val sceneInteractor: SceneInteractor,
) : CoreStartable, WakefulnessLifecycle.Observer {

    override fun start() {
        Log.d(LOG_TAG, "Resource trimmer registered.")
        if (com.android.systemui.Flags.trimResourcesWithBackgroundTrimAtLock()) {
            applicationScope.launch(bgDispatcher) {
                // We need to wait for the AoD transition (and animation) to complete.
                // This means we're waiting for isDreaming (== implies isDoze) and dozeAmount == 1f
                // signal. This is to make sure we don't clear font caches during animation which
                // would jank and leave stale data in memory.
                val isDozingFully =
                    keyguardInteractor.dozeAmount.map { it == 1f }.distinctUntilChanged()
                combine(
                        powerInteractor.isAsleep,
                        keyguardInteractor.isDreaming,
                        isDozingFully,
                        ::Triple
                    )
                    .distinctUntilChanged()
                    .collect { onWakefulnessUpdated(it.first, it.second, it.third) }
            }
        }

        applicationScope.launch(bgDispatcher) {
            // We drop 1 to avoid triggering on initial collect().
            if (SceneContainerFlag.isEnabled) {
                sceneInteractor.transitionState
                    .filter { it.isIdle(Scenes.Gone) }
                    .collect { onKeyguardGone() }
            } else {
                keyguardTransitionInteractor.transition(Edge.create(to = GONE)).collect {
                    if (it.transitionState == TransitionState.FINISHED) {
                        onKeyguardGone()
                    }
                }
            }
        }
    }

    @WorkerThread
    private fun onKeyguardGone() {
        // We want to clear temporary caches we've created while rendering and animating
        // lockscreen elements, especially clocks.
        Log.d(LOG_TAG, "Sending TRIM_MEMORY_UI_HIDDEN.")
        globalWindowManager.trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        if (featureFlags.isEnabled(Flags.TRIM_FONT_CACHES_AT_UNLOCK)) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Trimming font caches since keyguard went away.")
            }
            globalWindowManager.trimCaches(HardwareRenderer.CACHE_TRIM_FONT)
        }
    }

    @WorkerThread
    private fun onWakefulnessUpdated(
        isAsleep: Boolean,
        isDreaming: Boolean,
        isDozingFully: Boolean
    ) {
        if (!com.android.systemui.Flags.trimResourcesWithBackgroundTrimAtLock()) {
            return
        }

        if (DEBUG) {
            Log.d(LOG_TAG, "isAsleep: $isAsleep Dreaming: $isDreaming DozeAmount: $isDozingFully")
        }
        // There are three scenarios:
        // * No dozing and no AoD at all - where we go directly to ASLEEP with isDreaming = false
        //      and dozeAmount == 0f
        // * Dozing without Aod - where we go to ASLEEP with isDreaming = true and dozeAmount jumps
        //      to 1f
        // * AoD - where we go to ASLEEP with iDreaming = true and dozeAmount slowly increases
        //      to 1f
        val dozeDisabledAndScreenOff = isAsleep && !isDreaming
        val dozeEnabledAndDozeAnimationCompleted = isAsleep && isDreaming && isDozingFully
        if (dozeDisabledAndScreenOff || dozeEnabledAndDozeAnimationCompleted) {
            Trace.beginSection("ResourceTrimmer#trimMemory")
            Log.d(LOG_TAG, "SysUI asleep, trimming memory.")
            globalWindowManager.trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            globalWindowManager.trimCaches(HardwareRenderer.CACHE_TRIM_ALL)
            Trace.endSection()
        }
    }

    companion object {
        private const val LOG_TAG = "ResourceTrimmer"
        private val DEBUG = Log.isLoggable(LOG_TAG, Log.DEBUG)
    }
}
