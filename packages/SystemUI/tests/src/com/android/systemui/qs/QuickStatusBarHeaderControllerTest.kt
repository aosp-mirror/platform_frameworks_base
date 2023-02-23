/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs

import android.content.Context
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class QuickStatusBarHeaderControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var view: QuickStatusBarHeader
    @Mock
    private lateinit var quickQSPanelController: QuickQSPanelController

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var context: Context

    private lateinit var controller: QuickStatusBarHeaderController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        `when`(view.resources).thenReturn(mContext.resources)
        `when`(view.isAttachedToWindow).thenReturn(true)
        `when`(view.context).thenReturn(context)

        controller = QuickStatusBarHeaderController(view, quickQSPanelController)
    }

    @After
    fun tearDown() {
        controller.onViewDetached()
    }

    @Test
    fun testListeningStatus() {
        controller.setListening(true)
        verify(quickQSPanelController).setListening(true)

        controller.setListening(false)
        verify(quickQSPanelController).setListening(false)
    }
}
