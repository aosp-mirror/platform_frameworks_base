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

package com.android.systemui.inputdevice.tutorial.ui.view

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
import androidx.lifecycle.lifecycleScope
import com.android.compose.theme.PlatformTheme
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger.TutorialContext
import com.android.systemui.inputdevice.tutorial.TouchpadTutorialScreensProvider
import com.android.systemui.inputdevice.tutorial.ui.composable.ActionKeyTutorialScreen
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.KeyboardTouchpadTutorialViewModel
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.KeyboardTouchpadTutorialViewModel.Factory.ViewModelFactoryAssistedProvider
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.Screen.ACTION_KEY
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.Screen.BACK_GESTURE
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.Screen.HOME_GESTURE
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Activity for out of the box experience for keyboard and touchpad. Note that it's possible that
 * either of them are actually not connected when this is launched
 */
class KeyboardTouchpadTutorialActivity
@Inject
constructor(
    private val viewModelFactoryAssistedProvider: ViewModelFactoryAssistedProvider,
    private val touchpadTutorialScreensProvider: Optional<TouchpadTutorialScreensProvider>,
    private val logger: InputDeviceTutorialLogger,
) : ComponentActivity() {

    companion object {
        const val INTENT_TUTORIAL_TYPE_KEY = "tutorial_type"
        const val INTENT_TUTORIAL_TYPE_TOUCHPAD = "touchpad"
        const val INTENT_TUTORIAL_TYPE_KEYBOARD = "keyboard"
        const val INTENT_TUTORIAL_TYPE_BOTH = "both"
    }

    private val vm by
        viewModels<KeyboardTouchpadTutorialViewModel>(
            factoryProducer = {
                viewModelFactoryAssistedProvider.create(touchpadTutorialScreensProvider.isPresent)
            }
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // required to handle 3+ fingers on touchpad
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_ALLOW_ACTION_KEY_EVENTS)
        lifecycle.addObserver(vm)
        lifecycleScope.launch {
            vm.closeActivity.collect { finish ->
                if (finish) {
                    logger.logCloseTutorial(TutorialContext.KEYBOARD_TOUCHPAD_TUTORIAL)
                    finish()
                }
            }
        }
        setContent {
            PlatformTheme { KeyboardTouchpadTutorialContainer(vm, touchpadTutorialScreensProvider) }
        }
        if (savedInstanceState == null) {
            logger.logOpenTutorial(TutorialContext.KEYBOARD_TOUCHPAD_TUTORIAL)
        }
    }
}

@Composable
fun KeyboardTouchpadTutorialContainer(
    vm: KeyboardTouchpadTutorialViewModel,
    touchpadScreens: Optional<TouchpadTutorialScreensProvider>,
) {
    val activeScreen by vm.screen.collectAsStateWithLifecycle(STARTED)
    when (activeScreen) {
        BACK_GESTURE ->
            touchpadScreens
                .get()
                .BackGesture(onDoneButtonClicked = vm::onDoneButtonClicked, onBack = vm::onBack)
        HOME_GESTURE ->
            touchpadScreens
                .get()
                .HomeGesture(onDoneButtonClicked = vm::onDoneButtonClicked, onBack = vm::onBack)
        ACTION_KEY ->
            ActionKeyTutorialScreen(
                onDoneButtonClicked = vm::onDoneButtonClicked,
                onBack = vm::onBack
            )
    }
}
