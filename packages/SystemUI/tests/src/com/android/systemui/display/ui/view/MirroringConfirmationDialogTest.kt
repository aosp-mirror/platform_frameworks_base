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
 */

package com.android.systemui.display.ui.view

import android.graphics.Insets
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.WindowInsets
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class MirroringConfirmationDialogTest : SysuiTestCase() {

    private lateinit var dialog: MirroringConfirmationDialog

    private val onStartMirroringCallback = mock<View.OnClickListener>()
    private val onCancelCallback = mock<View.OnClickListener>()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        dialog =
            MirroringConfirmationDialog(
                context,
                onStartMirroringCallback,
                onCancelCallback,
                navbarBottomInsetsProvider = { 0 },
            )
    }

    @Test
    fun startMirroringButton_clicked_callsCorrectCallback() {
        dialog.show()

        dialog.requireViewById<View>(R.id.enable_display).callOnClick()

        verify(onStartMirroringCallback).onClick(any())
        verify(onCancelCallback, never()).onClick(any())
    }

    @Test
    fun cancelButton_clicked_callsCorrectCallback() {
        dialog.show()

        dialog.requireViewById<View>(R.id.cancel).callOnClick()

        verify(onCancelCallback).onClick(any())
        verify(onStartMirroringCallback, never()).onClick(any())
    }

    @Test
    fun onCancel_afterEnablingMirroring_cancelCallbackNotCalled() {
        dialog.show()
        dialog.requireViewById<View>(R.id.enable_display).callOnClick()

        dialog.cancel()

        verify(onCancelCallback, never()).onClick(any())
        verify(onStartMirroringCallback).onClick(any())
    }

    @Test
    fun onDismiss_afterEnablingMirroring_cancelCallbackNotCalled() {
        dialog.show()
        dialog.requireViewById<View>(R.id.enable_display).callOnClick()

        dialog.dismiss()

        verify(onCancelCallback, never()).onClick(any())
        verify(onStartMirroringCallback).onClick(any())
    }

    @Test
    fun onInsetsChanged_navBarInsets_updatesBottomPadding() {
        dialog.show()

        val insets = buildInsets(WindowInsets.Type.navigationBars(), TEST_BOTTOM_INSETS)
        dialog.onInsetsChanged(WindowInsets.Type.navigationBars(), insets)

        assertThat(dialog.requireViewById<View>(R.id.cd_bottom_sheet).paddingBottom)
            .isEqualTo(TEST_BOTTOM_INSETS)
    }

    @Test
    fun onInsetsChanged_otherType_doesNotUpdateBottomPadding() {
        dialog.show()

        val insets = buildInsets(WindowInsets.Type.ime(), TEST_BOTTOM_INSETS)
        dialog.onInsetsChanged(WindowInsets.Type.ime(), insets)

        assertThat(dialog.requireViewById<View>(R.id.cd_bottom_sheet).paddingBottom)
            .isNotEqualTo(TEST_BOTTOM_INSETS)
    }

    private fun buildInsets(@WindowInsets.Type.InsetsType type: Int, bottom: Int): WindowInsets {
        return WindowInsets.Builder().setInsets(type, Insets.of(0, 0, 0, bottom)).build()
    }

    @After
    fun teardown() {
        if (::dialog.isInitialized) {
            dialog.dismiss()
        }
    }

    private companion object {
        const val TEST_BOTTOM_INSETS = 1000 // arbitrarily high number
    }
}
