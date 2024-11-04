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
package com.android.systemui.statusbar.window

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.policy.statusBarConfigurationController
import com.android.systemui.testKosmos
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarWindowControllerImplTest : SysuiTestCase() {

    private val kosmos =
        testKosmos().also { it.statusBarWindowViewInflater = it.fakeStatusBarWindowViewInflater }

    private val underTest = kosmos.statusBarWindowControllerImpl
    private val fakeStatusBarWindowViewInflater = kosmos.fakeStatusBarWindowViewInflater
    private val statusBarConfigurationController = kosmos.statusBarConfigurationController

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun attach_connectedDisplaysFlagEnabled_setsConfigControllerOnWindowView() {
        val windowView = fakeStatusBarWindowViewInflater.inflatedMockViews.first()

        underTest.attach()

        verify(windowView).setStatusBarConfigurationController(statusBarConfigurationController)
    }

    @Test
    @DisableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun attach_connectedDisplaysFlagDisabled_doesNotSetConfigControllerOnWindowView() {
        val mockWindowView = fakeStatusBarWindowViewInflater.inflatedMockViews.first()

        underTest.attach()

        verify(mockWindowView, never()).setStatusBarConfigurationController(any())
    }
}
