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

package com.android.systemui.dreams.homecontrols

import android.content.Intent
import android.service.controls.ControlsProviderService
import android.service.dreams.DreamService
import android.window.TaskFragmentInfo
import com.android.systemui.controls.settings.ControlsSettingsRepository
import com.android.systemui.dreams.DreamLogger
import com.android.systemui.dreams.homecontrols.domain.interactor.HomeControlsComponentInteractor
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.DreamLog
import javax.inject.Inject

class HomeControlsDreamService
@Inject
constructor(
    private val controlsSettingsRepository: ControlsSettingsRepository,
    private val taskFragmentFactory: TaskFragmentComponent.Factory,
    private val homeControlsComponentInteractor: HomeControlsComponentInteractor,
    private val dreamActivityProvider: DreamActivityProvider,
    @DreamLog logBuffer: LogBuffer
) : DreamService() {
    private lateinit var taskFragmentComponent: TaskFragmentComponent

    private val logger = DreamLogger(logBuffer, "HomeControlsDreamService")

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val activity = dreamActivityProvider.getActivity(this)
        if (activity == null) {
            finish()
            return
        }
        taskFragmentComponent =
            taskFragmentFactory
                .create(
                    activity = activity,
                    onCreateCallback = this::onTaskFragmentCreated,
                    onInfoChangedCallback = this::onTaskFragmentInfoChanged,
                    hide = { finish() }
                )
                .apply { createTaskFragment() }
    }

    private fun onTaskFragmentInfoChanged(taskFragmentInfo: TaskFragmentInfo) {
        if (taskFragmentInfo.isEmpty) {
            logger.d("Finishing dream due to TaskFragment being empty")
            finish()
        }
    }

    private fun onTaskFragmentCreated(taskFragmentInfo: TaskFragmentInfo) {
        val setting = controlsSettingsRepository.allowActionOnTrivialControlsInLockscreen.value
        val componentName = homeControlsComponentInteractor.panelComponent.value
        logger.d("Starting embedding $componentName")
        val intent =
            Intent().apply {
                component = componentName
                putExtra(ControlsProviderService.EXTRA_LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS, setting)
                putExtra(
                    ControlsProviderService.EXTRA_CONTROLS_SURFACE,
                    ControlsProviderService.CONTROLS_SURFACE_DREAM
                )
            }
        taskFragmentComponent.startActivityInTaskFragment(intent)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        taskFragmentComponent.destroy()
    }
}
