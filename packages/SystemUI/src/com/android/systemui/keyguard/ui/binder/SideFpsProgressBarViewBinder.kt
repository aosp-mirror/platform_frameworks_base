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

package com.android.systemui.keyguard.ui.binder

import android.animation.ValueAnimator
import android.graphics.Point
import com.android.systemui.CoreStartable
import com.android.systemui.Flags
import com.android.systemui.biometrics.SideFpsController
import com.android.systemui.biometrics.shared.SideFpsControllerRefactor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.ui.view.SideFpsProgressBar
import com.android.systemui.keyguard.ui.viewmodel.SideFpsProgressBarViewModel
import com.android.systemui.log.SideFpsLogger
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.kotlin.Quint
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val spfsProgressBarCommand = "sfps-progress-bar"

@SysUISingleton
class SideFpsProgressBarViewBinder
@Inject
constructor(
    private val viewModel: SideFpsProgressBarViewModel,
    private val view: SideFpsProgressBar,
    @Application private val applicationScope: CoroutineScope,
    // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
    private val sfpsController: dagger.Lazy<SideFpsController>,
    private val logger: SideFpsLogger,
    private val commandRegistry: CommandRegistry,
) : CoreStartable {

    override fun start() {
        if (!Flags.restToUnlock()) {
            return
        }
        // When the rest to unlock feature is disabled by the user, stop any coroutines that are
        // not required.
        var layoutJob: Job? = null
        var progressJob: Job? = null
        commandRegistry.registerCommand(spfsProgressBarCommand) { SfpsProgressBarCommand() }
        applicationScope.launch {
            viewModel.isProlongedTouchRequiredForAuthentication.collectLatest { enabled ->
                logger.isProlongedTouchRequiredForAuthenticationChanged(enabled)
                if (enabled) {
                    layoutJob = launch {
                        combine(
                                viewModel.isVisible,
                                viewModel.progressBarLocation,
                                viewModel.rotation,
                                viewModel.isFingerprintAuthRunning,
                                viewModel.progressBarLength,
                                ::Quint
                            )
                            .collectLatest { (visible, location, rotation, fpDetectRunning, length)
                                ->
                                updateView(
                                    visible,
                                    location,
                                    fpDetectRunning,
                                    length,
                                    viewModel.progressBarThickness,
                                    rotation,
                                )
                            }
                    }
                    progressJob = launch {
                        viewModel.progress.collectLatest { view.setProgress(it) }
                    }
                } else {
                    view.hide()
                    layoutJob?.cancel()
                    progressJob?.cancel()
                }
            }
        }
    }

    private fun updateView(
        visible: Boolean,
        location: Point,
        fpDetectRunning: Boolean,
        length: Int,
        thickness: Int,
        rotation: Float,
    ) {
        logger.sfpsProgressBarStateChanged(visible, location, fpDetectRunning, length, rotation)
        view.updateView(visible, location, length, thickness, rotation)
        // We have to hide the SFPS indicator as the progress bar will
        // be shown at the same location
        if (!SideFpsControllerRefactor.isEnabled) {
            if (visible) {
                logger.hidingSfpsIndicator()
                sfpsController.get().hideIndicator()
            } else if (fpDetectRunning) {
                logger.showingSfpsIndicator()
                sfpsController.get().showIndicator()
            }
        }
    }

    inner class SfpsProgressBarCommand : Command {
        private var animator: ValueAnimator? = null
        override fun execute(pw: PrintWriter, args: List<String>) {
            if (args.isEmpty() || args[0] == "show" && args.size != 6) {
                pw.println("invalid command")
                help(pw)
            } else {
                when (args[0]) {
                    "show" -> {
                        animator?.cancel()
                        updateView(
                            visible = true,
                            location = Point(Integer.parseInt(args[1]), Integer.parseInt(args[2])),
                            fpDetectRunning = true,
                            length = Integer.parseInt(args[3]),
                            thickness = Integer.parseInt(args[4]),
                            rotation = Integer.parseInt(args[5]).toFloat(),
                        )
                        animator =
                            ValueAnimator.ofFloat(0.0f, 1.0f).apply {
                                repeatMode = ValueAnimator.REVERSE
                                repeatCount = ValueAnimator.INFINITE
                                addUpdateListener { view.setProgress(it.animatedValue as Float) }
                            }
                        animator?.start()
                    }
                    "hide" -> {
                        animator?.cancel()
                        updateView(
                            visible = false,
                            location = Point(0, 0),
                            fpDetectRunning = false,
                            length = 0,
                            thickness = 0,
                            rotation = 0.0f,
                        )
                    }
                }
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $spfsProgressBarCommand <command>")
            pw.println("Available commands:")
            pw.println("  show x y width height rotation")
            pw.println("  hide")
        }
    }
}
