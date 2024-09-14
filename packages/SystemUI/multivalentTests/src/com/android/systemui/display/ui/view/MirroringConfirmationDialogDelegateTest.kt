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

import android.app.Dialog
import android.graphics.Insets
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.app.animation.Interpolators
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class MirroringConfirmationDialogDelegateTest : SysuiTestCase() {

    private lateinit var underTest: MirroringConfirmationDialogDelegate

    private val onStartMirroringCallback = mock<View.OnClickListener>()
    private val onCancelCallback = mock<View.OnClickListener>()
    private val windowDecorView: View = mock {}
    private val windowInsetsAnimationCallbackCaptor =
        ArgumentCaptor.forClass(WindowInsetsAnimation.Callback::class.java)
    private val dialog: Dialog =
        mock<Dialog> {
            var view: View? = null
            whenever(setContentView(any<Int>())).then {
                view =
                    LayoutInflater.from(this@MirroringConfirmationDialogDelegateTest.context)
                        .inflate(it.arguments[0] as Int, null, false)
                Unit
            }
            whenever(requireViewById<View>(any<Int>())).then {
                view?.requireViewById(it.arguments[0] as Int)
            }
            val window: Window = mock { whenever(decorView).thenReturn(windowDecorView) }
            whenever(this.window).thenReturn(window)
        }

    @Before
    fun setUp() {
        underTest =
            MirroringConfirmationDialogDelegate(
                context = context,
                showConcurrentDisplayInfo = false,
                onStartMirroringClickListener = onStartMirroringCallback,
                onCancelMirroring = onCancelCallback,
                navbarBottomInsetsProvider = { 0 },
            )
    }

    @Test
    fun startMirroringButton_clicked_callsCorrectCallback() {
        underTest.onCreate(dialog, null)

        dialog.requireViewById<View>(R.id.enable_display).callOnClick()

        verify(onStartMirroringCallback).onClick(any())
        verify(onCancelCallback, never()).onClick(any())
    }

    @Test
    fun cancelButton_clicked_callsCorrectCallback() {
        underTest.onCreate(dialog, null)

        dialog.requireViewById<View>(R.id.cancel).callOnClick()

        verify(onCancelCallback).onClick(any())
        verify(onStartMirroringCallback, never()).onClick(any())
    }

    @Test
    fun onCancel_afterEnablingMirroring_cancelCallbackNotCalled() {
        underTest.onCreate(dialog, null)
        dialog.requireViewById<View>(R.id.enable_display).callOnClick()

        underTest.onStop(dialog)

        verify(onCancelCallback, never()).onClick(any())
        verify(onStartMirroringCallback).onClick(any())
    }

    @Test
    fun onDismiss_afterEnablingMirroring_cancelCallbackNotCalled() {
        underTest.onCreate(dialog, null)
        dialog.requireViewById<View>(R.id.enable_display).callOnClick()

        underTest.onStop(dialog)

        verify(onCancelCallback, never()).onClick(any())
        verify(onStartMirroringCallback).onClick(any())
    }

    @Test
    fun onInsetsChanged_navBarInsets_updatesBottomPadding() {
        underTest.onCreate(dialog, null)
        underTest.onStart(dialog)

        val insets = buildInsets(WindowInsets.Type.navigationBars(), TEST_BOTTOM_INSETS)

        triggerInsetsChanged(WindowInsets.Type.navigationBars(), insets)

        assertThat(dialog.requireViewById<View>(R.id.cd_bottom_sheet).paddingBottom)
            .isEqualTo(TEST_BOTTOM_INSETS)
    }

    @Test
    fun onInsetsChanged_otherType_doesNotUpdateBottomPadding() {
        underTest.onCreate(dialog, null)
        underTest.onStart(dialog)

        val insets = buildInsets(WindowInsets.Type.ime(), TEST_BOTTOM_INSETS)
        triggerInsetsChanged(WindowInsets.Type.ime(), insets)

        assertThat(dialog.requireViewById<View>(R.id.cd_bottom_sheet).paddingBottom)
            .isNotEqualTo(TEST_BOTTOM_INSETS)
    }

    private fun buildInsets(@WindowInsets.Type.InsetsType type: Int, bottom: Int): WindowInsets {
        return WindowInsets.Builder().setInsets(type, Insets.of(0, 0, 0, bottom)).build()
    }

    private fun triggerInsetsChanged(type: Int, insets: WindowInsets) {
        verify(windowDecorView)
            .setWindowInsetsAnimationCallback(capture(windowInsetsAnimationCallbackCaptor))
        windowInsetsAnimationCallbackCaptor.value.onProgress(
            insets,
            listOf(WindowInsetsAnimation(type, Interpolators.INSTANT, 0))
        )
    }

    private companion object {
        const val TEST_BOTTOM_INSETS = 1000 // arbitrarily high number
    }
}
