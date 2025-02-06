/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.chips.sharetoapp.ui.view

import android.content.DialogInterface
import android.content.applicationContext
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.View
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.mediaProjectionChipInteractor
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.endMediaProjectionDialogHelper
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class EndGenericShareToAppDialogDelegateTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val sysuiDialog = mock<SystemUIDialog>()
    private lateinit var underTest: EndGenericShareToAppDialogDelegate

    @Test
    fun positiveButton_clickStopsRecording() =
        kosmos.testScope.runTest {
            createAndSetDelegate()
            underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

            assertThat(kosmos.fakeMediaProjectionRepository.stopProjectingInvoked).isFalse()

            val clickListener = argumentCaptor<DialogInterface.OnClickListener>()
            verify(sysuiDialog).setPositiveButton(any(), clickListener.capture())
            clickListener.firstValue.onClick(mock<DialogInterface>(), 0)
            runCurrent()

            assertThat(kosmos.fakeMediaProjectionRepository.stopProjectingInvoked).isTrue()
        }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun accessibilityDataSensitive_flagEnabled_appliesSetting() {
        createAndSetDelegate()

        val window = mock<Window>()
        val decorView = mock<View>()
        whenever(sysuiDialog.window).thenReturn(window)
        whenever(window.decorView).thenReturn(decorView)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(decorView).setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }

    @Test
    @DisableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun accessibilityDataSensitive_flagDisabled_doesNotApplySetting() {
        createAndSetDelegate()

        val window = mock<Window>()
        val decorView = mock<View>()
        whenever(sysuiDialog.window).thenReturn(window)
        whenever(window.decorView).thenReturn(decorView)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(decorView, never()).setAccessibilityDataSensitive(any())
    }

    private fun createAndSetDelegate() {
        underTest =
            EndGenericShareToAppDialogDelegate(
                kosmos.endMediaProjectionDialogHelper,
                kosmos.applicationContext,
                stopAction = kosmos.mediaProjectionChipInteractor::stopProjecting,
            )
    }
}
