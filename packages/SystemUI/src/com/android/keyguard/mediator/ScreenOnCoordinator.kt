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

import android.os.Trace

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.util.concurrency.Execution
import com.android.systemui.util.concurrency.PendingTasksContainer
import com.android.systemui.unfold.SysUIUnfoldComponent
import com.android.systemui.util.kotlin.getOrNull

import java.util.Optional

import javax.inject.Inject

/**
 * Coordinates screen on/turning on animations for the KeyguardViewMediator. Specifically for
 * screen on events, this will invoke the onDrawn Runnable after all tasks have completed. This
 * should route back to the KeyguardService, which informs the system_server that keyguard has
 * drawn.
 */
@SysUISingleton
class ScreenOnCoordinator @Inject constructor(
    screenLifecycle: ScreenLifecycle,
    unfoldComponent: Optional<SysUIUnfoldComponent>,
    private val execution: Execution
) : ScreenLifecycle.Observer {

    private val unfoldLightRevealAnimation = unfoldComponent.map(
        SysUIUnfoldComponent::getUnfoldLightRevealOverlayAnimation).getOrNull()
    private val foldAodAnimationController = unfoldComponent.map(
        SysUIUnfoldComponent::getFoldAodAnimationController).getOrNull()
    private val pendingTasks = PendingTasksContainer()

    init {
        screenLifecycle.addObserver(this)
    }

    /**
     * When turning on, registers tasks that may need to run before invoking [onDrawn].
     */
    override fun onScreenTurningOn(onDrawn: Runnable) {
        execution.assertIsMainThread()
        Trace.beginSection("ScreenOnCoordinator#onScreenTurningOn")

        pendingTasks.reset()

        unfoldLightRevealAnimation?.onScreenTurningOn(pendingTasks.registerTask("unfold-reveal"))
        foldAodAnimationController?.onScreenTurningOn(pendingTasks.registerTask("fold-to-aod"))

        pendingTasks.onTasksComplete { onDrawn.run() }
        Trace.endSection()
    }

    override fun onScreenTurnedOn() {
        execution.assertIsMainThread()

        foldAodAnimationController?.onScreenTurnedOn()

        pendingTasks.reset()
    }
}
