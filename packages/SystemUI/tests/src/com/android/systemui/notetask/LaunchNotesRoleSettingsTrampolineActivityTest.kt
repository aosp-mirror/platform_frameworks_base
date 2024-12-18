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

import android.content.Context
import android.content.Intent
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import com.android.dx.mockito.inline.extended.ExtendedMockito.verify
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.SingleActivityFactory
import com.android.systemui.notetask.LaunchNotesRoleSettingsTrampolineActivity.Companion.ACTION_MANAGE_NOTES_ROLE_FROM_QUICK_AFFORDANCE
import com.android.systemui.notetask.NoteTaskEntryPoint.QUICK_AFFORDANCE
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
class LaunchNotesRoleSettingsTrampolineActivityTest : SysuiTestCase() {

    @Mock lateinit var noteTaskController: NoteTaskController

    @Rule
    @JvmField
    val activityRule =
        ActivityTestRule(
            /* activityFactory= */ SingleActivityFactory {
                LaunchNotesRoleSettingsTrampolineActivity(noteTaskController)
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
    fun startActivity_noAction_shouldLaunchNotesRoleSettingTaskWithNullEntryPoint() {
        activityRule.launchActivity(/* startIntent= */ null)

        verify(noteTaskController).startNotesRoleSetting(any(Context::class.java), eq(null))
    }

    @Test
    fun startActivity_quickAffordanceAction_shouldLaunchNotesRoleSettingTaskWithQuickAffordanceEntryPoint() { // ktlint-disable max-line-length
        activityRule.launchActivity(Intent(ACTION_MANAGE_NOTES_ROLE_FROM_QUICK_AFFORDANCE))

        verify(noteTaskController)
            .startNotesRoleSetting(any(Context::class.java), eq(QUICK_AFFORDANCE))
    }
}
