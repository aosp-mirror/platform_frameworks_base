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

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.FakeSystemUIDialogController
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsDialogsFactoryTest : SysuiTestCase() {

    private companion object {
        const val APP_NAME = "Test App"
    }

    private val fakeDialogController = FakeSystemUIDialogController()

    private lateinit var underTest: ControlsDialogsFactory

    @Before
    fun setup() {
        underTest = ControlsDialogsFactory { fakeDialogController.dialog }
    }

    @Test
    fun testCreatesRemoveAppDialog() {
        val dialog = underTest.createRemoveAppDialog(context, APP_NAME) {}

        verify(dialog)
            .setTitle(
                eq(context.getString(R.string.controls_panel_remove_app_authorization, APP_NAME))
            )
        verify(dialog).setCanceledOnTouchOutside(eq(true))
    }

    @Test
    fun testPositiveClickRemoveAppDialogWorks() {
        var dialogResult: Boolean? = null
        underTest.createRemoveAppDialog(context, APP_NAME) { dialogResult = it }

        fakeDialogController.clickPositive()

        assertThat(dialogResult).isTrue()
    }

    @Test
    fun testNeutralClickRemoveAppDialogWorks() {
        var dialogResult: Boolean? = null
        underTest.createRemoveAppDialog(context, APP_NAME) { dialogResult = it }

        fakeDialogController.clickNeutral()

        assertThat(dialogResult).isFalse()
    }

    @Test
    fun testCancelRemoveAppDialogWorks() {
        var dialogResult: Boolean? = null
        underTest.createRemoveAppDialog(context, APP_NAME) { dialogResult = it }

        fakeDialogController.cancel()

        assertThat(dialogResult).isFalse()
    }
}
