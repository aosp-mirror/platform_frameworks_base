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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.compose.theme.PlatformTheme
import com.android.systemui.touchpad.tutorial.ui.GestureViewModelFactory
import com.android.systemui.touchpad.tutorial.ui.HomeGestureTutorialViewModel
import com.android.systemui.touchpad.tutorial.ui.Screen.BACK_GESTURE
import com.android.systemui.touchpad.tutorial.ui.Screen.HOME_GESTURE
import com.android.systemui.touchpad.tutorial.ui.Screen.TUTORIAL_SELECTION
import com.android.systemui.touchpad.tutorial.ui.TouchpadTutorialViewModel
import javax.inject.Inject

class TouchpadTutorialActivity
@Inject
constructor(
    private val viewModelFactory: TouchpadTutorialViewModel.Factory,
) : ComponentActivity() {

    private val vm by viewModels<TouchpadTutorialViewModel>(factoryProducer = { viewModelFactory })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PlatformTheme { TouchpadTutorialScreen(vm) { finish() } } }
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
                onActionKeyTutorialClicked = {},
                onDoneButtonClicked = closeTutorial
            )
        BACK_GESTURE ->
            BackGestureTutorialScreen(
                onDoneButtonClicked = { vm.goTo(TUTORIAL_SELECTION) },
                onBack = { vm.goTo(TUTORIAL_SELECTION) }
            )
        HOME_GESTURE -> HomeGestureTutorialScreen()
    }
}

@Composable
fun HomeGestureTutorialScreen() {
    val vm = viewModel<HomeGestureTutorialViewModel>(factory = GestureViewModelFactory())
}
