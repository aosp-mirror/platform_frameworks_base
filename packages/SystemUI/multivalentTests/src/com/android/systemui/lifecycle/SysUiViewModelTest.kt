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

package com.android.systemui.lifecycle

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.Assert
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class SysUiViewModelTest : SysuiTestCase() {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rememberActivated() {
        val keepAliveMutable = mutableStateOf(true)
        var isActive = false
        composeRule.setContent {
            val keepAlive by keepAliveMutable
            if (keepAlive) {
                rememberViewModel("test") {
                    FakeSysUiViewModel(
                        onActivation = { isActive = true },
                        onDeactivation = { isActive = false },
                    )
                }
            }
        }
        assertThat(isActive).isTrue()
    }

    @Test
    fun rememberActivated_withKey() {
        val keyMutable = mutableStateOf(1)
        var isActive1 = false
        var isActive2 = false
        composeRule.setContent {
            val key by keyMutable
            // Need to explicitly state the type to avoid a weird issue where the factory seems to
            // return Unit instead of FakeSysUiViewModel. It might be an issue with the compose
            // compiler.
            val unused: FakeSysUiViewModel =
                rememberViewModel("test", key) {
                    when (key) {
                        1 ->
                            FakeSysUiViewModel(
                                onActivation = { isActive1 = true },
                                onDeactivation = { isActive1 = false },
                            )
                        2 ->
                            FakeSysUiViewModel(
                                onActivation = { isActive2 = true },
                                onDeactivation = { isActive2 = false },
                            )
                        else -> error("unsupported key $key")
                    }
                }
        }
        assertThat(isActive1).isTrue()
        assertThat(isActive2).isFalse()

        composeRule.runOnUiThread { keyMutable.value = 2 }
        composeRule.waitForIdle()
        assertThat(isActive1).isFalse()
        assertThat(isActive2).isTrue()

        composeRule.runOnUiThread { keyMutable.value = 1 }
        composeRule.waitForIdle()
        assertThat(isActive1).isTrue()
        assertThat(isActive2).isFalse()
    }

    @Test
    fun rememberActivated_leavingTheComposition() {
        val keepAliveMutable = mutableStateOf(true)
        var isActive = false
        composeRule.setContent {
            val keepAlive by keepAliveMutable
            if (keepAlive) {
                rememberViewModel("test") {
                    FakeSysUiViewModel(
                        onActivation = { isActive = true },
                        onDeactivation = { isActive = false },
                    )
                }
            }
        }

        // Tear down the composable.
        composeRule.runOnUiThread { keepAliveMutable.value = false }
        composeRule.waitForIdle()

        assertThat(isActive).isFalse()
    }

    @Test
    fun viewModel_viewBinder() = runTest {
        Assert.setTestThread(Thread.currentThread())

        val view: View = mock { on { isAttachedToWindow } doReturn false }
        val viewModel = FakeViewModel()
        backgroundScope.launch {
            view.viewModel(
                traceName = "test",
                minWindowLifecycleState = WindowLifecycleState.ATTACHED,
                factory = { viewModel },
            ) {
                awaitCancellation()
            }
        }
        runCurrent()

        assertThat(viewModel.isActivated).isFalse()

        view.stub { on { isAttachedToWindow } doReturn true }
        argumentCaptor<View.OnAttachStateChangeListener>()
            .apply { verify(view).addOnAttachStateChangeListener(capture()) }
            .allValues
            .forEach { it.onViewAttachedToWindow(view) }
        runCurrent()

        assertThat(viewModel.isActivated).isTrue()
    }
}

private class FakeViewModel : ExclusiveActivatable() {
    var isActivated = false

    override suspend fun onActivated(): Nothing {
        isActivated = true
        try {
            awaitCancellation()
        } finally {
            isActivated = false
        }
    }
}
