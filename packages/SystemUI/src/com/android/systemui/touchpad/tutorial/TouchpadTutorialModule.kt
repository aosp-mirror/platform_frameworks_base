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
import com.android.systemui.touchpad.tutorial.ui.gesture.VelocityTracker
import com.android.systemui.touchpad.tutorial.ui.gesture.VerticalVelocityTracker
import com.android.systemui.touchpad.tutorial.ui.view.TouchpadTutorialActivity
import com.android.systemui.touchpad.tutorial.ui.viewmodel.BackGestureRecognizerProvider
import com.android.systemui.touchpad.tutorial.ui.viewmodel.BackGestureScreenViewModel
import com.android.systemui.touchpad.tutorial.ui.viewmodel.EasterEggGestureViewModel
import com.android.systemui.touchpad.tutorial.ui.viewmodel.EasterEggRecognizerProvider
import com.android.systemui.touchpad.tutorial.ui.viewmodel.GestureRecognizerAdapter
import com.android.systemui.touchpad.tutorial.ui.viewmodel.HomeGestureRecognizerProvider
import com.android.systemui.touchpad.tutorial.ui.viewmodel.HomeGestureScreenViewModel
import com.android.systemui.touchpad.tutorial.ui.viewmodel.RecentAppsGestureRecognizerProvider
import com.android.systemui.touchpad.tutorial.ui.viewmodel.RecentAppsGestureScreenViewModel
import com.android.systemui.touchpad.tutorial.ui.viewmodel.SwitchAppsGestureRecognizerProvider
import com.android.systemui.touchpad.tutorial.ui.viewmodel.SwitchAppsGestureScreenViewModel
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
        fun touchpadScreensProvider(
            backGestureScreenViewModel: BackGestureScreenViewModel,
            homeGestureScreenViewModel: HomeGestureScreenViewModel,
            easterEggGestureViewModel: EasterEggGestureViewModel,
        ): TouchpadTutorialScreensProvider {
            return ScreensProvider(
                backGestureScreenViewModel,
                homeGestureScreenViewModel,
                easterEggGestureViewModel,
            )
        }

        @Provides
        fun switchAppsViewModel(
            recognizerProvider: SwitchAppsGestureRecognizerProvider,
            adapterFactory: GestureRecognizerAdapter.Factory,
        ): SwitchAppsGestureScreenViewModel {
            return SwitchAppsGestureScreenViewModel(adapterFactory.create(recognizerProvider))
        }

        @Provides
        fun recentAppsViewModel(
            recognizerProvider: RecentAppsGestureRecognizerProvider,
            adapterFactory: GestureRecognizerAdapter.Factory,
        ): RecentAppsGestureScreenViewModel {
            return RecentAppsGestureScreenViewModel(adapterFactory.create(recognizerProvider))
        }

        @Provides
        fun backViewModel(
            recognizerProvider: BackGestureRecognizerProvider,
            adapterFactory: GestureRecognizerAdapter.Factory,
        ): BackGestureScreenViewModel {
            return BackGestureScreenViewModel(adapterFactory.create(recognizerProvider))
        }

        @Provides
        fun homeViewModel(
            recognizerProvider: HomeGestureRecognizerProvider,
            adapterFactory: GestureRecognizerAdapter.Factory,
        ): HomeGestureScreenViewModel {
            return HomeGestureScreenViewModel(adapterFactory.create(recognizerProvider))
        }

        @Provides
        fun easterEggViewModel(
            adapterFactory: GestureRecognizerAdapter.Factory
        ): EasterEggGestureViewModel {
            return EasterEggGestureViewModel(adapterFactory.create(EasterEggRecognizerProvider()))
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

        @Provides fun velocityTracker(): VelocityTracker = VerticalVelocityTracker()
    }
}

private class ScreensProvider(
    val backGestureScreenViewModel: BackGestureScreenViewModel,
    val homeGestureScreenViewModel: HomeGestureScreenViewModel,
    val easterEggGestureViewModel: EasterEggGestureViewModel,
) : TouchpadTutorialScreensProvider {
    @Composable
    override fun BackGesture(onDoneButtonClicked: () -> Unit, onBack: () -> Unit) {
        BackGestureTutorialScreen(
            backGestureScreenViewModel,
            easterEggGestureViewModel,
            onDoneButtonClicked,
            onBack,
        )
    }

    @Composable
    override fun HomeGesture(onDoneButtonClicked: () -> Unit, onBack: () -> Unit) {
        HomeGestureTutorialScreen(
            homeGestureScreenViewModel,
            easterEggGestureViewModel,
            onDoneButtonClicked,
            onBack,
        )
    }
}
