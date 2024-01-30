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

package com.android.systemui.recordissue

import android.app.Dialog
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.widget.Button
import android.widget.Switch
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.model.SysUiState
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class RecordIssueDialogDelegateTest : SysuiTestCase() {

    private lateinit var dialog: SystemUIDialog
    private lateinit var latch: CountDownLatch

    @Before
    fun setup() {
        val dialogFactory =
            SystemUIDialog.Factory(
                context,
                mock<FeatureFlags>(),
                mock<SystemUIDialogManager>(),
                mock<SysUiState>().apply {
                    whenever(setFlag(anyInt(), anyBoolean())).thenReturn(this)
                },
                mock<BroadcastDispatcher>(),
                mock<DialogLaunchAnimator>()
            )

        latch = CountDownLatch(1)
        dialog = RecordIssueDialogDelegate(dialogFactory) { latch.countDown() }.createDialog()
        dialog.show()
    }

    @After
    fun teardown() {
        dialog.dismiss()
    }

    @Test
    fun dialog_hasCorrectUiElements_afterCreation() {
        dialog.requireViewById<Switch>(R.id.screenrecord_switch)
        dialog.requireViewById<Button>(R.id.issue_type_button)

        assertThat(dialog.getButton(Dialog.BUTTON_POSITIVE).text)
            .isEqualTo(context.getString(R.string.qs_record_issue_start))
        assertThat(dialog.getButton(Dialog.BUTTON_NEGATIVE).text)
            .isEqualTo(context.getString(R.string.cancel))
    }

    @Test
    fun onStarted_isCalled_afterStartButtonIsClicked() {
        dialog.getButton(Dialog.BUTTON_POSITIVE).callOnClick()
        latch.await(1L, TimeUnit.MILLISECONDS)
    }
}
