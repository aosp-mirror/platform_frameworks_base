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
package com.android.dream.lowlight

import android.Manifest
import android.annotation.IntDef
import android.annotation.RequiresPermission
import android.app.DreamManager
import android.content.ComponentName
import android.util.Log
import com.android.dream.lowlight.dagger.LowLightDreamModule
import com.android.dream.lowlight.dagger.qualifiers.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Maintains the ambient light mode of the environment the device is in, and sets a low light dream
 * component, if present, as the system dream when the ambient light mode is low light.
 *
 * @hide
 */
class LowLightDreamManager @Inject constructor(
    @Application private val coroutineScope: CoroutineScope,
    private val dreamManager: DreamManager,
    private val lowLightTransitionCoordinator: LowLightTransitionCoordinator,
    @param:Named(LowLightDreamModule.LOW_LIGHT_DREAM_COMPONENT)
    private val lowLightDreamComponent: ComponentName?,
    @param:Named(LowLightDreamModule.LOW_LIGHT_TRANSITION_TIMEOUT_MS)
    private val lowLightTransitionTimeoutMs: Long
) {
    /**
     * @hide
     */
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(
        prefix = ["AMBIENT_LIGHT_MODE_"],
        value = [
            AMBIENT_LIGHT_MODE_UNKNOWN,
            AMBIENT_LIGHT_MODE_REGULAR,
            AMBIENT_LIGHT_MODE_LOW_LIGHT
        ]
    )
    annotation class AmbientLightMode

    private var mTransitionJob: Job? = null
    private var mAmbientLightMode = AMBIENT_LIGHT_MODE_UNKNOWN
    private val mLowLightTransitionTimeout =
        lowLightTransitionTimeoutMs.toDuration(DurationUnit.MILLISECONDS)

    /**
     * Sets the current ambient light mode.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.WRITE_DREAM_STATE)
    fun setAmbientLightMode(@AmbientLightMode ambientLightMode: Int) {
        if (lowLightDreamComponent == null) {
            if (DEBUG) {
                Log.d(
                    TAG,
                    "ignore ambient light mode change because low light dream component is empty"
                )
            }
            return
        }
        if (mAmbientLightMode == ambientLightMode) {
            return
        }
        if (DEBUG) {
            Log.d(
                TAG, "ambient light mode changed from $mAmbientLightMode to $ambientLightMode"
            )
        }
        mAmbientLightMode = ambientLightMode
        val shouldEnterLowLight = mAmbientLightMode == AMBIENT_LIGHT_MODE_LOW_LIGHT

        // Cancel any previous transitions
        mTransitionJob?.cancel()
        mTransitionJob = coroutineScope.launch {
            try {
                lowLightTransitionCoordinator.waitForLowLightTransitionAnimation(
                    timeout = mLowLightTransitionTimeout,
                    entering = shouldEnterLowLight
                )
            } catch (ex: TimeoutCancellationException) {
                Log.e(TAG, "timed out while waiting for low light animation", ex)
            } catch (ex: CancellationException) {
                Log.w(TAG, "low light transition animation cancelled")
                // Catch the cancellation so that we still set the system dream component if the
                // animation is cancelled, such as by a user tapping to wake as the transition to
                // low light happens.
            }
            dreamManager.setSystemDreamComponent(
                if (shouldEnterLowLight) lowLightDreamComponent else null
            )
        }
    }

    companion object {
        private const val TAG = "LowLightDreamManager"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        /**
         * Constant for ambient light mode being unknown.
         *
         * @hide
         */
        const val AMBIENT_LIGHT_MODE_UNKNOWN = 0

        /**
         * Constant for ambient light mode being regular / bright.
         *
         * @hide
         */
        const val AMBIENT_LIGHT_MODE_REGULAR = 1

        /**
         * Constant for ambient light mode being low light / dim.
         *
         * @hide
         */
        const val AMBIENT_LIGHT_MODE_LOW_LIGHT = 2
    }
}
