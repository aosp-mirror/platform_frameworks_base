/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.CommandQueue
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
class PhoneStatusBarViewControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var commandQueue: CommandQueue

    private lateinit var view: PhoneStatusBarView
    private lateinit var controller: PhoneStatusBarViewController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        view = PhoneStatusBarView(mContext, null)
        controller = PhoneStatusBarViewController(view, commandQueue)
    }

    @Test
    fun constructor_setsPanelEnabledProviderOnView() {
        var providerUsed = false
        `when`(commandQueue.panelsEnabled()).then {
            providerUsed = true
            true
        }

        // If the constructor correctly set a [PanelEnabledProvider], then it should be used
        // when [PhoneStatusBarView.panelEnabled] is called.
        view.panelEnabled()

        assertThat(providerUsed).isTrue()
    }
}
