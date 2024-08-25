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

package com.android.systemui.touchpad.tutorial

import android.app.Activity
import androidx.compose.runtime.Composable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger
import com.android.systemui.inputdevice.tutorial.TouchpadTutorialScreensProvider
import com.android.systemui.model.SysUiState
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.touchpad.tutorial.domain.interactor.TouchpadGesturesInteractor
import com.android.systemui.touchpad.tutorial.ui.composable.BackGestureTutorialScreen
import com.android.systemui.touchpad.tutorial.ui.composable.HomeGestureTutorialScreen
import com.android.systemui.touchpad.tutorial.ui.view.TouchpadTutorialActivity
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CoroutineScope

@Module
interface TouchpadTutorialModule {

    @Binds
    @IntoMap
    @ClassKey(TouchpadTutorialActivity::class)
    fun activity(impl: TouchpadTutorialActivity): Activity

    companion object {
        @Provides
        fun touchpadScreensProvider(): TouchpadTutorialScreensProvider {
            return ScreensProvider
        }

        @SysUISingleton
        @Provides
        fun touchpadGesturesInteractor(
            sysUiState: SysUiState,
            displayTracker: DisplayTracker,
            @Background backgroundScope: CoroutineScope,
            logger: InputDeviceTutorialLogger,
        ): TouchpadGesturesInteractor {
            return TouchpadGesturesInteractor(sysUiState, displayTracker, backgroundScope, logger)
        }
    }
}

private object ScreensProvider : TouchpadTutorialScreensProvider {
    @Composable
    override fun BackGesture(onDoneButtonClicked: () -> Unit, onBack: () -> Unit) {
        BackGestureTutorialScreen(onDoneButtonClicked, onBack)
    }

    @Composable
    override fun HomeGesture(onDoneButtonClicked: () -> Unit, onBack: () -> Unit) {
        HomeGestureTutorialScreen(onDoneButtonClicked, onBack)
    }
}
