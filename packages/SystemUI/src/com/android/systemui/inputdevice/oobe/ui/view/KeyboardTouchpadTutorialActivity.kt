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
import com.android.compose.theme.PlatformTheme
import com.android.systemui.inputdevice.tutorial.TouchpadTutorialScreensProvider
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.KeyboardTouchpadTutorialViewModel
import java.util.Optional
import javax.inject.Inject

/**
 * Activity for out of the box experience for keyboard and touchpad. Note that it's possible that
 * either of them are actually not connected when this is launched
 */
class KeyboardTouchpadTutorialActivity
@Inject
constructor(
    private val viewModelFactory: KeyboardTouchpadTutorialViewModel.Factory,
    private val touchpadTutorialScreensProvider: Optional<TouchpadTutorialScreensProvider>,
) : ComponentActivity() {

    private val vm by
        viewModels<KeyboardTouchpadTutorialViewModel>(factoryProducer = { viewModelFactory })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlatformTheme {
                KeyboardTouchpadTutorialContainer(vm, touchpadTutorialScreensProvider) { finish() }
            }
        }
        // required to handle 3+ fingers on touchpad
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
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
fun KeyboardTouchpadTutorialContainer(
    vm: KeyboardTouchpadTutorialViewModel,
    touchpadTutorialScreensProvider: Optional<TouchpadTutorialScreensProvider>,
    closeTutorial: () -> Unit
) {}
