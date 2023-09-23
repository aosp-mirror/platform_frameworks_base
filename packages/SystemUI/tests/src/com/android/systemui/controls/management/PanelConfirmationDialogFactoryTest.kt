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

package com.android.systemui.controls.management

import android.content.DialogInterface
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@SmallTest
@RunWith(AndroidTestingRunner::class)
class PanelConfirmationDialogFactoryTest : SysuiTestCase() {

    @Test
    fun testDialogHasCorrectInfo() {
        val mockDialog: SystemUIDialog = mock() { `when`(context).thenReturn(mContext) }
        val factory = PanelConfirmationDialogFactory { mockDialog }
        val appName = "appName"

        factory.createConfirmationDialog(context, appName) {}

        verify(mockDialog).setCanceledOnTouchOutside(true)
        verify(mockDialog)
            .setTitle(context.getString(R.string.controls_panel_authorization_title, appName))
        verify(mockDialog)
            .setMessage(context.getString(R.string.controls_panel_authorization, appName))
    }

    @Test
    fun testDialogPositiveButton() {
        val mockDialog: SystemUIDialog = mock() { `when`(context).thenReturn(mContext) }
        val factory = PanelConfirmationDialogFactory { mockDialog }

        var response: Boolean? = null

        factory.createConfirmationDialog(context, "") { response = it }

        val captor: ArgumentCaptor<DialogInterface.OnClickListener> = argumentCaptor()
        verify(mockDialog).setPositiveButton(eq(R.string.controls_dialog_ok), capture(captor))

        captor.value.onClick(mockDialog, DialogInterface.BUTTON_POSITIVE)

        assertThat(response).isTrue()
    }

    @Test
    fun testDialogNeutralButton() {
        val mockDialog: SystemUIDialog = mock() { `when`(context).thenReturn(mContext) }
        val factory = PanelConfirmationDialogFactory { mockDialog }

        var response: Boolean? = null

        factory.createConfirmationDialog(context, "") { response = it }

        val captor: ArgumentCaptor<DialogInterface.OnClickListener> = argumentCaptor()
        verify(mockDialog).setNeutralButton(eq(R.string.cancel), capture(captor))

        captor.value.onClick(mockDialog, DialogInterface.BUTTON_NEUTRAL)

        assertThat(response).isFalse()
    }

    @Test
    fun testDialogCancel() {
        val mockDialog: SystemUIDialog = mock() { `when`(context).thenReturn(mContext) }
        val factory = PanelConfirmationDialogFactory { mockDialog }

        var response: Boolean? = null

        factory.createConfirmationDialog(context, "") { response = it }

        val captor: ArgumentCaptor<DialogInterface.OnCancelListener> = argumentCaptor()
        verify(mockDialog).setOnCancelListener(capture(captor))

        captor.value.onCancel(mockDialog)

        assertThat(response).isFalse()
    }
}
