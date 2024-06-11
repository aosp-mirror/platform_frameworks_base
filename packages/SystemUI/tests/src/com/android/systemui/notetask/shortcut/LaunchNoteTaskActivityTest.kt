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

package com.android.systemui.notetask.shortcut

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import com.android.dx.mockito.inline.extended.ExtendedMockito.verify
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.SingleActivityFactory
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskEntryPoint
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
@TestableLooper.RunWithLooper
class LaunchNoteTaskActivityTest : SysuiTestCase() {

    @Mock lateinit var noteTaskController: NoteTaskController

    @Rule
    @JvmField
    val activityRule =
        ActivityTestRule<LaunchNoteTaskActivity>(
            /* activityFactory= */ SingleActivityFactory {
                LaunchNoteTaskActivity(controller = noteTaskController)
            },
            /* initialTouchMode= */ false,
            /* launchActivity= */ false,
        )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @After
    fun tearDown() {
        activityRule.finishActivity()
    }

    @Test
    fun startActivityOnNonWorkProfileUser_shouldLaunchNoteTask() {
        activityRule.launchActivity(/* startIntent= */ null)

        verify(noteTaskController)
            .showNoteTaskAsUser(eq(NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT), any())
    }
}
