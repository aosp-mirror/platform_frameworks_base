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

package com.android.systemui.statusbar.notification.row

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.testing.ViewUtils
import android.view.LayoutInflater
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class NotificationGutsTest : SysuiTestCase() {

    private lateinit var guts: NotificationGuts
    private lateinit var gutsContentView: View

    @Mock
    private lateinit var gutsContent: NotificationGuts.GutsContent

    @Mock
    private lateinit var gutsClosedListener: NotificationGuts.OnGutsClosedListener

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val layoutInflater = LayoutInflater.from(mContext)
        guts = layoutInflater.inflate(R.layout.notification_guts, null) as NotificationGuts
        gutsContentView = View(mContext)

        whenever(gutsContent.contentView).thenReturn(gutsContentView)

        ViewUtils.attachView(guts)
    }

    @After
    fun tearDown() {
        ViewUtils.detachView(guts)
    }

    @Test
    fun setGutsContent() {
        guts.gutsContent = gutsContent

        verify(gutsContent).setGutsParent(guts)
    }

    @Test
    fun openControls() {
        guts.gutsContent = gutsContent

        guts.openControls(true, 0, 0, false, null)
    }

    @Test
    fun closeControlsWithSave() {
        guts.gutsContent = gutsContent
        guts.setClosedListener(gutsClosedListener)

        guts.closeControls(gutsContentView, true)

        verify(gutsContent).handleCloseControls(true, false)
        verify(gutsClosedListener).onGutsClosed(guts)
    }

    @Test
    fun closeControlsWithoutSave() {
        guts.gutsContent = gutsContent
        guts.setClosedListener(gutsClosedListener)

        guts.closeControls(gutsContentView, false)

        verify(gutsContent).handleCloseControls(false, false)
        verify(gutsClosedListener).onGutsClosed(guts)
    }
}
