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

package com.android.keyguard.mediator

import android.annotation.BinderThread
import android.os.Handler
import android.os.Trace
import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.unfold.SysUIUnfoldComponent
import com.android.systemui.util.concurrency.PendingTasksContainer
import com.android.systemui.util.kotlin.getOrNull
import java.util.Optional
import javax.inject.Inject

/**
 * Coordinates screen on/turning on animations for the KeyguardViewMediator. Specifically for screen
 * on events, this will invoke the onDrawn Runnable after all tasks have completed. This should
 * route back to the [com.android.systemui.keyguard.KeyguardService], which informs the
 * system_server that keyguard has drawn.
 */
@SysUISingleton
class ScreenOnCoordinator
@Inject
constructor(
    unfoldComponent: Optional<SysUIUnfoldComponent>,
    @Main private val mainHandler: Handler,
) {

    private val foldAodAnimationController =
        unfoldComponent.map(SysUIUnfoldComponent::getFoldAodAnimationController).getOrNull()
    private val fullScreenLightRevealAnimations =
        unfoldComponent.map(SysUIUnfoldComponent::getFullScreenLightRevealAnimations).getOrNull()
    private val pendingTasks = PendingTasksContainer()

    /**
     * When turning on, registers tasks that may need to run before invoking [onDrawn]. This is
     * called on a binder thread from [com.android.systemui.keyguard.KeyguardService].
     */
    @BinderThread
    fun onScreenTurningOn(onDrawn: Runnable) {
        Trace.beginSection("ScreenOnCoordinator#onScreenTurningOn")

        pendingTasks.reset()

        foldAodAnimationController?.onScreenTurningOn(pendingTasks.registerTask("fold-to-aod"))
        fullScreenLightRevealAnimations?.forEach {
            it.onScreenTurningOn(pendingTasks.registerTask(it::class.java.simpleName))
        }

        pendingTasks.onTasksComplete {
            if (Flags.enableBackgroundKeyguardOndrawnCallback()) {
                // called by whatever thread completes the last task registered.
                onDrawn.run()
            } else {
                mainHandler.post { onDrawn.run() }
            }
        }
        Trace.endSection()
    }

    /**
     * Called when screen is fully turned on and screen on blocker is removed. This is called on a
     * binder thread from [com.android.systemui.keyguard.KeyguardService].
     */
    @BinderThread
    fun onScreenTurnedOn() {
        foldAodAnimationController?.onScreenTurnedOn()
    }

    @BinderThread
    fun onScreenTurnedOff() {
        pendingTasks.reset()
    }
}
