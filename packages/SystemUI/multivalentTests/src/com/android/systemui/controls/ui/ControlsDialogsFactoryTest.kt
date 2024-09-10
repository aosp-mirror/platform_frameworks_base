/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.controls.ui

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.FakeSystemUIDialogController
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class ControlsDialogsFactoryTest : SysuiTestCase() {

    private companion object {
        const val APP_NAME = "Test App"
    }

    @Mock
    private lateinit var mockDialogFactory : SystemUIDialog.Factory

    private val fakeDialogController = FakeSystemUIDialogController(mContext)

    private lateinit var underTest: ControlsDialogsFactory

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(mockDialogFactory.create(any(Context::class.java)))
            .thenReturn(fakeDialogController.dialog)
        underTest = ControlsDialogsFactory(mockDialogFactory)
    }

    @Test
    fun testCreatesRemoveAppDialog() {
        val dialog = underTest.createRemoveAppDialog(mContext, APP_NAME) {}

        verify(dialog)
            .setTitle(
                eq(context.getString(R.string.controls_panel_remove_app_authorization, APP_NAME))
            )
        verify(dialog).setCanceledOnTouchOutside(eq(true))
    }

    @Test
    fun testPositiveClickRemoveAppDialogWorks() {
        var dialogResult: Boolean? = null
        underTest.createRemoveAppDialog(mContext, APP_NAME) { dialogResult = it }

        fakeDialogController.clickPositive()

        assertThat(dialogResult).isTrue()
    }

    @Test
    fun testNeutralClickRemoveAppDialogWorks() {
        var dialogResult: Boolean? = null
        underTest.createRemoveAppDialog(mContext, APP_NAME) { dialogResult = it }

        fakeDialogController.clickNeutral()

        assertThat(dialogResult).isFalse()
    }

    @Test
    fun testCancelRemoveAppDialogWorks() {
        var dialogResult: Boolean? = null
        underTest.createRemoveAppDialog(mContext, APP_NAME) { dialogResult = it }

        fakeDialogController.cancel()

        assertThat(dialogResult).isFalse()
    }
}
