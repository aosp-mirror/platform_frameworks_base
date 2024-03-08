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

package com.android.systemui.communal

import android.provider.Settings
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.CoreStartable
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dock.DockManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.util.kotlin.emitOnStart
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.settings.SystemSettings
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * A [CoreStartable] responsible for automatically navigating between communal scenes when certain
 * conditions are met.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalSceneStartable
@Inject
constructor(
    private val dockManager: DockManager,
    private val communalInteractor: CommunalInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val systemSettings: SystemSettings,
    @Application private val applicationScope: CoroutineScope,
    @Background private val bgScope: CoroutineScope,
) : CoreStartable {
    private var screenTimeout: Int = DEFAULT_SCREEN_TIMEOUT

    override fun start() {
        // Handle automatically switching based on keyguard state.
        keyguardTransitionInteractor.startedKeyguardTransitionStep
            .mapLatest(::determineSceneAfterTransition)
            .filterNotNull()
            // TODO(b/322787129): Also set a custom transition animation here to avoid the regular
            // slide-in animation when setting the scene programmatically
            .onEach { nextScene -> communalInteractor.onSceneChanged(nextScene) }
            .launchIn(applicationScope)

        // TODO(b/322787129): re-enable once custom animations are in place
        // Handle automatically switching to communal when docked.
        //        dockManager
        //            .retrieveIsDocked()
        //            // Allow some time after docking to ensure the dream doesn't start. If the
        // dream
        //            // starts, then we don't want to automatically transition to glanceable hub.
        //            .debounce(DOCK_DEBOUNCE_DELAY)
        //            .sample(keyguardTransitionInteractor.startedKeyguardState, ::Pair)
        //            .onEach { (docked, lastStartedState) ->
        //                if (docked && lastStartedState == KeyguardState.LOCKSCREEN) {
        //                    communalInteractor.onSceneChanged(CommunalScenes.Communal)
        //                }
        //            }
        //            .launchIn(bgScope)

        systemSettings
            .observerFlow(Settings.System.SCREEN_OFF_TIMEOUT)
            // Read the setting value on start.
            .emitOnStart()
            .onEach {
                screenTimeout =
                    systemSettings.getInt(
                        Settings.System.SCREEN_OFF_TIMEOUT,
                        DEFAULT_SCREEN_TIMEOUT
                    )
            }
            .launchIn(bgScope)

        // Handle timing out back to the dream.
        bgScope.launch {
            combine(
                    communalInteractor.desiredScene,
                    keyguardInteractor.isDreaming,
                    // Emit a value on start so the combine starts.
                    communalInteractor.userActivity.emitOnStart()
                ) { scene, isDreaming, _ ->
                    // Time out should run whenever we're dreaming and the hub is open, even if not
                    // docked.
                    scene == CommunalScenes.Communal && isDreaming
                }
                // collectLatest cancels the previous action block when new values arrive, so any
                // already running timeout gets cancelled when conditions change or user interaction
                // is detected.
                .collectLatest { shouldTimeout ->
                    if (!shouldTimeout) {
                        return@collectLatest
                    }
                    delay(screenTimeout.milliseconds)
                    communalInteractor.onSceneChanged(CommunalScenes.Blank)
                }
        }
    }

    private suspend fun determineSceneAfterTransition(
        lastStartedTransition: TransitionStep,
    ): SceneKey? {
        val to = lastStartedTransition.to
        val from = lastStartedTransition.from
        val docked = dockManager.isDocked

        return when {
            docked && to == KeyguardState.LOCKSCREEN && from == KeyguardState.DREAMING -> {
                CommunalScenes.Communal
            }
            to == KeyguardState.GONE -> CommunalScenes.Blank
            !docked && !KeyguardState.deviceIsAwakeInState(to) -> {
                // If the user taps the screen and wakes the device within this timeout, we don't
                // want to dismiss the hub
                delay(AWAKE_DEBOUNCE_DELAY)
                CommunalScenes.Blank
            }
            else -> null
        }
    }

    companion object {
        val AWAKE_DEBOUNCE_DELAY = 5.seconds
        val DOCK_DEBOUNCE_DELAY = 1.seconds
        val DEFAULT_SCREEN_TIMEOUT = 15000
    }
}
