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
import android.os.PowerManager
import android.service.controls.ControlsProviderService
import android.service.dreams.DreamService
import android.window.TaskFragmentInfo
import com.android.systemui.controls.settings.ControlsSettingsRepository
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dreams.DreamLogger
import com.android.systemui.dreams.homecontrols.domain.interactor.HomeControlsComponentInteractor
import com.android.systemui.dreams.homecontrols.domain.interactor.HomeControlsComponentInteractor.Companion.MAX_UPDATE_CORRELATION_DELAY
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.DreamLog
import com.android.systemui.util.wakelock.WakeLock
import com.android.systemui.util.wakelock.WakeLock.Builder.NO_TIMEOUT
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeControlsDreamService
@Inject
constructor(
    private val controlsSettingsRepository: ControlsSettingsRepository,
    private val taskFragmentFactory: TaskFragmentComponent.Factory,
    private val homeControlsComponentInteractor: HomeControlsComponentInteractor,
    private val wakeLockBuilder: WakeLock.Builder,
    private val dreamActivityProvider: DreamActivityProvider,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @DreamLog logBuffer: LogBuffer
) : DreamService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(bgDispatcher + serviceJob)
    private val logger = DreamLogger(logBuffer, TAG)
    private lateinit var taskFragmentComponent: TaskFragmentComponent
    private val wakeLock: WakeLock by lazy {
        wakeLockBuilder
            .setMaxTimeout(NO_TIMEOUT)
            .setTag(TAG)
            .setLevelsAndFlags(PowerManager.SCREEN_BRIGHT_WAKE_LOCK)
            .build()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val activity = dreamActivityProvider.getActivity(this)
        if (activity == null) {
            finish()
            return
        }

        // Start monitoring package updates to possibly restart the dream if the home controls
        // package is updated while we are dreaming.
        serviceScope.launch { homeControlsComponentInteractor.monitorUpdatesAndRestart() }

        taskFragmentComponent =
            taskFragmentFactory
                .create(
                    activity = activity,
                    onCreateCallback = this::onTaskFragmentCreated,
                    onInfoChangedCallback = this::onTaskFragmentInfoChanged,
                    hide = { endDream() }
                )
                .apply { createTaskFragment() }

        wakeLock.acquire(TAG)
    }

    private fun onTaskFragmentInfoChanged(taskFragmentInfo: TaskFragmentInfo) {
        if (taskFragmentInfo.isEmpty) {
            logger.d("Finishing dream due to TaskFragment being empty")
            endDream()
        }
    }

    private fun endDream() {
        homeControlsComponentInteractor.onDreamEndUnexpectedly()
        finish()
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
        wakeLock.release(TAG)
        taskFragmentComponent.destroy()
        serviceScope.launch {
            delay(CANCELLATION_DELAY_AFTER_DETACHED)
            serviceJob.cancel("Dream detached from window")
        }
    }

    private companion object {
        /**
         * Defines how long after the dream ends that we should keep monitoring for package updates
         * to attempt a restart of the dream. This should be larger than
         * [MAX_UPDATE_CORRELATION_DELAY] as it also includes the time the package update takes to
         * complete.
         */
        val CANCELLATION_DELAY_AFTER_DETACHED = 5.seconds
        const val TAG = "HomeControlsDreamService"
    }
}
