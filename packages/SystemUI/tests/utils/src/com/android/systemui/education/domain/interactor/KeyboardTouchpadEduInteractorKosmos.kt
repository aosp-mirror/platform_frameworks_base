/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.domain.interactor

import android.hardware.input.InputManager
import com.android.systemui.education.data.repository.fakeEduClock
import com.android.systemui.inputdevice.data.repository.UserInputDeviceRepository
import com.android.systemui.inputdevice.tutorial.tutorialSchedulerRepository
import com.android.systemui.keyboard.data.repository.keyboardRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.touchpad.data.repository.touchpadRepository
import com.android.systemui.user.data.repository.userRepository
import org.mockito.kotlin.mock

var Kosmos.keyboardTouchpadEduInteractor by
    Kosmos.Fixture {
        KeyboardTouchpadEduInteractor(
            backgroundScope = testScope.backgroundScope,
            contextualEducationInteractor = contextualEducationInteractor,
            userInputDeviceRepository =
                UserInputDeviceRepository(
                    testDispatcher,
                    keyboardRepository,
                    touchpadRepository,
                    userRepository
                ),
            clock = fakeEduClock
        )
    }

var Kosmos.mockEduInputManager by Kosmos.Fixture { mock<InputManager>() }

var Kosmos.keyboardTouchpadEduStatsInteractor by
    Kosmos.Fixture {
        KeyboardTouchpadEduStatsInteractorImpl(
            backgroundScope = testScope.backgroundScope,
            contextualEducationInteractor = contextualEducationInteractor,
            inputDeviceRepository =
                UserInputDeviceRepository(
                    testDispatcher,
                    keyboardRepository,
                    touchpadRepository,
                    userRepository
                ),
            tutorialSchedulerRepository,
            fakeEduClock
        )
    }
