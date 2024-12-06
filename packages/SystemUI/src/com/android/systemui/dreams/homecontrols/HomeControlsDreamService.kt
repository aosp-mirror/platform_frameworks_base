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

import android.annotation.SuppressLint
import android.content.Intent
import android.os.PowerManager
import android.service.controls.ControlsProviderService
import android.service.dreams.DreamService
import android.window.TaskFragmentInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher
import androidx.lifecycle.lifecycleScope
import com.android.systemui.dreams.DreamLogger
import com.android.systemui.dreams.homecontrols.service.TaskFragmentComponent
import com.android.systemui.dreams.homecontrols.shared.model.HomeControlsDataSource
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.DreamLog
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.wakelock.WakeLock
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * [DreamService] which embeds the user's chosen home controls app to allow it to display as a
 * screensaver. This service will run in the foreground user context.
 */
class HomeControlsDreamService
@Inject
constructor(private val factory: HomeControlsDreamServiceImpl.Factory) :
    DreamService(), LifecycleOwner {

    private val dispatcher = ServiceLifecycleDispatcher(this)
    override val lifecycle: Lifecycle
        get() = dispatcher.lifecycle

    private val impl: HomeControlsDreamServiceImpl by lazy { factory.create(this, this) }

    override fun onCreate() {
        dispatcher.onServicePreSuperOnCreate()
        super.onCreate()
    }

    override fun onDreamingStarted() {
        dispatcher.onServicePreSuperOnStart()
        super.onDreamingStarted()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        impl.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        dispatcher.onServicePreSuperOnDestroy()
        super.onDetachedFromWindow()
        impl.onDetachedFromWindow()
    }
}

/**
 * Implementation of the home controls dream service, which allows for injecting a [DreamService]
 * and [LifecycleOwner] for testing.
 */
class HomeControlsDreamServiceImpl
@AssistedInject
constructor(
    private val taskFragmentFactory: TaskFragmentComponent.Factory,
    private val wakeLockBuilder: WakeLock.Builder,
    private val powerManager: PowerManager,
    private val systemClock: SystemClock,
    private val dataSource: HomeControlsDataSource,
    @DreamLog logBuffer: LogBuffer,
    @Assisted private val service: DreamService,
    @Assisted lifecycleOwner: LifecycleOwner,
) : LifecycleOwner by lifecycleOwner {

    private val logger = DreamLogger(logBuffer, TAG)
    private lateinit var taskFragmentComponent: TaskFragmentComponent
    private val wakeLock: WakeLock by lazy {
        wakeLockBuilder
            .setMaxTimeout(WakeLock.Builder.NO_TIMEOUT)
            .setTag(TAG)
            .setLevelsAndFlags(PowerManager.SCREEN_BRIGHT_WAKE_LOCK)
            .build()
    }

    fun onAttachedToWindow() {
        val activity = service.activity
        if (activity == null) {
            service.finish()
            return
        }
        taskFragmentComponent =
            taskFragmentFactory
                .create(
                    activity = activity,
                    onCreateCallback = { launchActivity() },
                    onInfoChangedCallback = this::onTaskFragmentInfoChanged,
                    hide = { endDream(false) },
                )
                .apply { createTaskFragment() }

        wakeLock.acquire(TAG)
    }

    private fun onTaskFragmentInfoChanged(taskFragmentInfo: TaskFragmentInfo) {
        if (taskFragmentInfo.isEmpty) {
            logger.d("Finishing dream due to TaskFragment being empty")
            endDream(true)
        }
    }

    private fun endDream(handleRedirect: Boolean) {
        pokeUserActivity()
        if (handleRedirect && service.redirectWake) {
            service.wakeUp()
            lifecycleScope.launch {
                delay(ACTIVITY_RESTART_DELAY)
                launchActivity()
            }
        } else {
            service.finish()
        }
    }

    private fun launchActivity() {
        lifecycleScope.launch {
            val (componentName, setting) = dataSource.componentInfo.first()
            logger.d("Starting embedding $componentName")
            val intent =
                Intent().apply {
                    component = componentName
                    putExtra(
                        ControlsProviderService.EXTRA_LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS,
                        setting,
                    )
                    putExtra(
                        ControlsProviderService.EXTRA_CONTROLS_SURFACE,
                        ControlsProviderService.CONTROLS_SURFACE_DREAM,
                    )
                }
            taskFragmentComponent.startActivityInTaskFragment(intent)
        }
    }

    fun onDetachedFromWindow() {
        wakeLock.release(TAG)
        taskFragmentComponent.destroy()
    }

    @SuppressLint("MissingPermission")
    private fun pokeUserActivity() {
        powerManager.userActivity(
            systemClock.uptimeMillis(),
            PowerManager.USER_ACTIVITY_EVENT_OTHER,
            PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS,
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            service: DreamService,
            lifecycleOwner: LifecycleOwner,
        ): HomeControlsDreamServiceImpl
    }

    companion object {
        /**
         * Defines the delay after wakeup where we should attempt to restart the embedded activity.
         * When a wakeup is redirected, the dream service may keep running. In this case, we should
         * restart the activity if it finished. This delays ensures the activity is only restarted
         * after the wakeup transition has played.
         */
        val ACTIVITY_RESTART_DELAY = 334.milliseconds
        private const val TAG = "HomeControlsDreamServiceImpl"
    }
}
