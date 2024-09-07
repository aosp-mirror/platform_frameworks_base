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
package com.android.systemui.notetask

import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.notetask.NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT_IN_MULTI_WINDOW_MODE
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** atest SystemUITests:NoteTaskInfoTest */
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskInfoTest : SysuiTestCase() {

    @Test
    fun launchMode_keyguardLocked_launchModeActivity() {
        val underTest = DEFAULT_INFO.copy(isKeyguardLocked = true)

        assertThat(underTest.launchMode).isEqualTo(NoteTaskLaunchMode.Activity)
    }

    @Test
    fun launchMode_multiWindowMode_launchModeActivity() {
        val underTest = DEFAULT_INFO.copy(entryPoint = WIDGET_PICKER_SHORTCUT_IN_MULTI_WINDOW_MODE)

        assertThat(underTest.launchMode).isEqualTo(NoteTaskLaunchMode.Activity)
    }

    @Test
    fun launchMode_keyguardUnlocked_launchModeAppBubble() {
        val underTest = DEFAULT_INFO.copy(isKeyguardLocked = false)

        assertThat(underTest.launchMode).isEqualTo(NoteTaskLaunchMode.AppBubble)
    }

    private companion object {

        val DEFAULT_INFO =
            NoteTaskInfo(
                packageName = "com.android.note.app",
                uid = 123456,
                user = UserHandle.of(0),
            )
    }
}
