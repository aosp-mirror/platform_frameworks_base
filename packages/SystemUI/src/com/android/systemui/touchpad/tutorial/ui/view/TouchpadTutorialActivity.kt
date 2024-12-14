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

package com.android.systemui.touchpad.tutorial.ui.view

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger.TutorialContext
import com.android.systemui.touchpad.tutorial.ui.composable.BackGestureTutorialScreen
import com.android.systemui.touchpad.tutorial.ui.composable.HomeGestureTutorialScreen
import com.android.systemui.touchpad.tutorial.ui.composable.RecentAppsGestureTutorialScreen
import com.android.systemui.touchpad.tutorial.ui.composable.TutorialSelectionScreen
import com.android.systemui.touchpad.tutorial.ui.viewmodel.Screen.BACK_GESTURE
import com.android.systemui.touchpad.tutorial.ui.viewmodel.Screen.HOME_GESTURE
import com.android.systemui.touchpad.tutorial.ui.viewmodel.Screen.RECENT_APPS_GESTURE
import com.android.systemui.touchpad.tutorial.ui.viewmodel.Screen.TUTORIAL_SELECTION
import com.android.systemui.touchpad.tutorial.ui.viewmodel.TouchpadTutorialViewModel
import javax.inject.Inject

class TouchpadTutorialActivity
@Inject
constructor(
    private val viewModelFactory: TouchpadTutorialViewModel.Factory,
    private val logger: InputDeviceTutorialLogger,
) : ComponentActivity() {

    private val vm by viewModels<TouchpadTutorialViewModel>(factoryProducer = { viewModelFactory })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlatformTheme { TouchpadTutorialScreen(vm, closeTutorial = ::finishTutorial) }
        }
        // required to handle 3+ fingers on touchpad
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
        logger.logOpenTutorial(TutorialContext.TOUCHPAD_TUTORIAL)
    }

    private fun finishTutorial() {
        logger.logCloseTutorial(TutorialContext.TOUCHPAD_TUTORIAL)
        finish()
    }

    override fun onResume() {
        super.onResume()
        vm.onOpened()
    }

    override fun onPause() {
        super.onPause()
        vm.onClosed()
    }
}

@Composable
fun TouchpadTutorialScreen(vm: TouchpadTutorialViewModel, closeTutorial: () -> Unit) {
    val activeScreen by vm.screen.collectAsStateWithLifecycle(STARTED)
    when (activeScreen) {
        TUTORIAL_SELECTION ->
            TutorialSelectionScreen(
                onBackTutorialClicked = { vm.goTo(BACK_GESTURE) },
                onHomeTutorialClicked = { vm.goTo(HOME_GESTURE) },
                onRecentAppsTutorialClicked = { vm.goTo(RECENT_APPS_GESTURE) },
                onDoneButtonClicked = closeTutorial
            )
        BACK_GESTURE ->
            BackGestureTutorialScreen(
                onDoneButtonClicked = { vm.goTo(TUTORIAL_SELECTION) },
                onBack = { vm.goTo(TUTORIAL_SELECTION) },
            )
        HOME_GESTURE ->
            HomeGestureTutorialScreen(
                onDoneButtonClicked = { vm.goTo(TUTORIAL_SELECTION) },
                onBack = { vm.goTo(TUTORIAL_SELECTION) },
            )
        RECENT_APPS_GESTURE ->
            RecentAppsGestureTutorialScreen(
                onDoneButtonClicked = { vm.goTo(TUTORIAL_SELECTION) },
                onBack = { vm.goTo(TUTORIAL_SELECTION) },
            )
    }
}
