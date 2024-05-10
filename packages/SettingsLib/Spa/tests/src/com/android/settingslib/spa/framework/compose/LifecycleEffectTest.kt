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
 */

package com.android.settingslib.spa.framework.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifecycleEffectTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun onStart_isCalled() {
        var onStartIsCalled = false
        composeTestRule.setContent {
            LifecycleEffect(onStart = { onStartIsCalled = true })
        }

        assertThat(onStartIsCalled).isTrue()
    }

    @Test
    fun onStop_isCalled() {
        var onStopIsCalled = false
        val testLifecycleOwner = TestLifecycleOwner()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides testLifecycleOwner) {
                LifecycleEffect(onStop = { onStopIsCalled = true })
            }
            LaunchedEffect(Unit) {
                testLifecycleOwner.currentState = Lifecycle.State.CREATED
            }
        }

        assertThat(onStopIsCalled).isTrue()
    }
}