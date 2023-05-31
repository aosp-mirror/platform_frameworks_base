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
 *
 */

package com.android.systemui.power.domain.interactor

import android.os.PowerManager
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.PowerRepository
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Hosts business logic for interacting with the power system. */
@SysUISingleton
class PowerInteractor
@Inject
constructor(
    private val repository: PowerRepository,
    private val falsingCollector: FalsingCollector,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val statusBarStateController: StatusBarStateController,
) {
    /** Whether the screen is on or off. */
    val isInteractive: Flow<Boolean> = repository.isInteractive

    /**
     * Wakes up the device if the device was dozing.
     *
     * @param why a string explaining why we're waking the device for debugging purposes. Should be
     *   in SCREAMING_SNAKE_CASE.
     * @param wakeReason the PowerManager-based reason why we're waking the device.
     */
    fun wakeUpIfDozing(why: String, @PowerManager.WakeReason wakeReason: Int) {
        if (
            statusBarStateController.isDozing && screenOffAnimationController.allowWakeUpIfDozing()
        ) {
            repository.wakeUp(why, wakeReason)
            falsingCollector.onScreenOnFromTouch()
        }
    }
}
