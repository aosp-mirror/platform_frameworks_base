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
 *
 */

package com.android.systemui.util

import android.content.DialogInterface
import android.content.DialogInterface.BUTTON_NEGATIVE
import android.content.DialogInterface.BUTTON_NEUTRAL
import android.content.DialogInterface.BUTTON_POSITIVE
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class TestableAlertDialogTest : SysuiTestCase() {

    @Test
    fun dialogNotShowingWhenCreated() {
        val dialog = TestableAlertDialog(context)

        assertThat(dialog.isShowing).isFalse()
    }

    @Test
    fun dialogShownDoesntCrash() {
        val dialog = TestableAlertDialog(context)

        dialog.show()
    }

    @Test
    fun dialogShowing() {
        val dialog = TestableAlertDialog(context)

        dialog.show()

        assertThat(dialog.isShowing).isTrue()
    }

    @Test
    fun showListenerCalled() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnShowListener = mock()
        dialog.setOnShowListener(listener)

        dialog.show()

        verify(listener).onShow(dialog)
    }

    @Test
    fun showListenerRemoved() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnShowListener = mock()
        dialog.setOnShowListener(listener)
        dialog.setOnShowListener(null)

        dialog.show()

        verify(listener, never()).onShow(any())
    }

    @Test
    fun dialogHiddenNotShowing() {
        val dialog = TestableAlertDialog(context)

        dialog.show()
        dialog.hide()

        assertThat(dialog.isShowing).isFalse()
    }

    @Test
    fun dialogDismissNotShowing() {
        val dialog = TestableAlertDialog(context)

        dialog.show()
        dialog.dismiss()

        assertThat(dialog.isShowing).isFalse()
    }

    @Test
    fun dismissListenerCalled_ifShowing() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnDismissListener = mock()
        dialog.setOnDismissListener(listener)

        dialog.show()
        dialog.dismiss()

        verify(listener).onDismiss(dialog)
    }

    @Test
    fun dismissListenerNotCalled_ifNotShowing() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnDismissListener = mock()
        dialog.setOnDismissListener(listener)

        dialog.dismiss()

        verify(listener, never()).onDismiss(any())
    }

    @Test
    fun dismissListenerRemoved() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnDismissListener = mock()
        dialog.setOnDismissListener(listener)
        dialog.setOnDismissListener(null)

        dialog.show()
        dialog.dismiss()

        verify(listener, never()).onDismiss(any())
    }

    @Test
    fun cancelListenerCalled_showing() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnCancelListener = mock()
        dialog.setOnCancelListener(listener)

        dialog.show()
        dialog.cancel()

        verify(listener).onCancel(dialog)
    }

    @Test
    fun cancelListenerCalled_notShowing() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnCancelListener = mock()
        dialog.setOnCancelListener(listener)

        dialog.cancel()

        verify(listener).onCancel(dialog)
    }

    @Test
    fun dismissCalledOnCancel_showing() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnDismissListener = mock()
        dialog.setOnDismissListener(listener)

        dialog.show()
        dialog.cancel()

        verify(listener).onDismiss(dialog)
    }

    @Test
    fun dialogCancelNotShowing() {
        val dialog = TestableAlertDialog(context)

        dialog.show()
        dialog.cancel()

        assertThat(dialog.isShowing).isFalse()
    }

    @Test
    fun cancelListenerRemoved() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnCancelListener = mock()
        dialog.setOnCancelListener(listener)
        dialog.setOnCancelListener(null)

        dialog.show()
        dialog.cancel()

        verify(listener, never()).onCancel(any())
    }

    @Test
    fun positiveButtonClick() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnClickListener = mock()
        dialog.setButton(BUTTON_POSITIVE, "", listener)

        dialog.show()
        dialog.clickButton(BUTTON_POSITIVE)

        verify(listener).onClick(dialog, BUTTON_POSITIVE)
    }

    @Test
    fun positiveButtonListener_noCalledWhenClickOtherButtons() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnClickListener = mock()
        dialog.setButton(BUTTON_POSITIVE, "", listener)

        dialog.show()
        dialog.clickButton(BUTTON_NEUTRAL)
        dialog.clickButton(BUTTON_NEGATIVE)

        verify(listener, never()).onClick(any(), anyInt())
    }

    @Test
    fun negativeButtonClick() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnClickListener = mock()
        dialog.setButton(BUTTON_NEGATIVE, "", listener)

        dialog.show()
        dialog.clickButton(BUTTON_NEGATIVE)

        verify(listener).onClick(dialog, DialogInterface.BUTTON_NEGATIVE)
    }

    @Test
    fun negativeButtonListener_noCalledWhenClickOtherButtons() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnClickListener = mock()
        dialog.setButton(BUTTON_NEGATIVE, "", listener)

        dialog.show()
        dialog.clickButton(BUTTON_NEUTRAL)
        dialog.clickButton(BUTTON_POSITIVE)

        verify(listener, never()).onClick(any(), anyInt())
    }

    @Test
    fun neutralButtonClick() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnClickListener = mock()
        dialog.setButton(BUTTON_NEUTRAL, "", listener)

        dialog.show()
        dialog.clickButton(BUTTON_NEUTRAL)

        verify(listener).onClick(dialog, BUTTON_NEUTRAL)
    }

    @Test
    fun neutralButtonListener_noCalledWhenClickOtherButtons() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnClickListener = mock()
        dialog.setButton(BUTTON_NEUTRAL, "", listener)

        dialog.show()
        dialog.clickButton(BUTTON_POSITIVE)
        dialog.clickButton(BUTTON_NEGATIVE)

        verify(listener, never()).onClick(any(), anyInt())
    }

    @Test
    fun sameClickListenerCalledCorrectly() {
        val dialog = TestableAlertDialog(context)
        val listener: DialogInterface.OnClickListener = mock()
        dialog.setButton(BUTTON_POSITIVE, "", listener)
        dialog.setButton(BUTTON_NEUTRAL, "", listener)
        dialog.setButton(BUTTON_NEGATIVE, "", listener)

        dialog.show()
        dialog.clickButton(BUTTON_POSITIVE)
        dialog.clickButton(BUTTON_NEGATIVE)
        dialog.clickButton(BUTTON_NEUTRAL)

        val inOrder = inOrder(listener)
        inOrder.verify(listener).onClick(dialog, BUTTON_POSITIVE)
        inOrder.verify(listener).onClick(dialog, BUTTON_NEGATIVE)
        inOrder.verify(listener).onClick(dialog, BUTTON_NEUTRAL)
    }

    @Test(expected = IllegalArgumentException::class)
    fun clickBadButton() {
        val dialog = TestableAlertDialog(context)

        dialog.clickButton(10000)
    }

    @Test
    fun clickButtonDismisses_positive() {
        val dialog = TestableAlertDialog(context)

        dialog.show()
        dialog.clickButton(BUTTON_POSITIVE)

        assertThat(dialog.isShowing).isFalse()
    }

    @Test
    fun clickButtonDismisses_negative() {
        val dialog = TestableAlertDialog(context)

        dialog.show()
        dialog.clickButton(BUTTON_NEGATIVE)

        assertThat(dialog.isShowing).isFalse()
    }

    @Test
    fun clickButtonDismisses_neutral() {
        val dialog = TestableAlertDialog(context)

        dialog.show()
        dialog.clickButton(BUTTON_NEUTRAL)

        assertThat(dialog.isShowing).isFalse()
    }
}
