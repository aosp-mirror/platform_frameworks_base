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

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class KeyboardsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock
    lateinit var keyboardController: SoftwareKeyboardController

    @Test
    fun hideKeyboardAction_callControllerHide() {
        lateinit var action: () -> Unit
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboardController) {
                action = hideKeyboardAction()
            }
        }

        action()

        verify(keyboardController).hide()
    }

    @Test
    fun rememberLazyListStateAndHideKeyboardWhenStartScroll_notCallHideInitially() {
        setLazyColumn(scroll = false)

        verify(keyboardController, never()).hide()
    }

    @Test
    fun rememberLazyListStateAndHideKeyboardWhenStartScroll_callHideWhenScroll() {
        setLazyColumn(scroll = true)

        verify(keyboardController).hide()
    }

    private fun setLazyColumn(scroll: Boolean) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboardController) {
                val lazyListState = rememberLazyListStateAndHideKeyboardWhenStartScroll()
                LazyColumn(
                    modifier = Modifier.size(100.dp),
                    state = lazyListState,
                ) {
                    items(count = 10) {
                        Text(text = it.toString())
                    }
                }
                if (scroll) {
                    LaunchedEffect(Unit) {
                        lazyListState.animateScrollToItem(1)
                    }
                }
            }
        }
    }
}
