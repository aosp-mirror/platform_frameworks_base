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
import android.test.suitebuilder.annotation.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** atest SystemUITests:NoteTaskInfoTest */
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskInfoTest : SysuiTestCase() {

    private fun createNoteTaskInfo(): NoteTaskInfo =
        NoteTaskInfo(packageName = NOTES_PACKAGE_NAME, uid = NOTES_UID, UserHandle.of(0))

    @Test
    fun launchMode_keyguardLocked_launchModeActivity() {
        val underTest = createNoteTaskInfo().copy(isKeyguardLocked = true)

        assertThat(underTest.launchMode).isEqualTo(NoteTaskLaunchMode.Activity)
    }

    @Test
    fun launchMode_keyguardUnlocked_launchModeActivity() {
        val underTest = createNoteTaskInfo().copy(isKeyguardLocked = false)

        assertThat(underTest.launchMode).isEqualTo(NoteTaskLaunchMode.AppBubble)
    }

    private companion object {
        const val NOTES_PACKAGE_NAME = "com.android.note.app"
        const val NOTES_UID = 123456
    }
}
