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

package com.android.systemui.globalactions

import android.service.quickaccesswallet.QuickAccessWalletClient
import android.testing.AndroidTestingRunner
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.plugins.ActivityStarter
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyObject
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.never
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class GlobalActionsInfoProviderTest : SysuiTestCase() {

    @Mock
    private lateinit var walletClient: QuickAccessWalletClient
    @Mock
    private lateinit var controlsController: ControlsController
    @Mock
    private lateinit var activityStarter: ActivityStarter

    private lateinit var infoProvider: GlobalActionsInfoProvider

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        infoProvider = GlobalActionsInfoProvider(
                context,
                walletClient,
                controlsController,
                activityStarter
        )
    }

    @Test
    fun testAddPanel_doesNothing() {
        val parent = mock(ViewGroup::class.java)
        infoProvider.addPanel(context, parent, 1, { })
        verify(parent, never()).addView(anyObject(), anyInt())
    }

    @Test
    fun testShouldShowPanel() {
        assertThat(infoProvider.shouldShowMessage()).isFalse()
    }
}