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

import android.content.Intent
import android.content.pm.UserInfo
import android.os.UserHandle
import android.os.UserManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.intercepting.SingleActivityFactory
import com.android.dx.mockito.inline.extended.ExtendedMockito.verify
import com.android.systemui.SysuiTestCase
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskEntryPoint
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@TestableLooper.RunWithLooper
class LaunchNoteTaskActivityTest : SysuiTestCase() {

    @Mock lateinit var noteTaskController: NoteTaskController
    @Mock lateinit var userManager: UserManager
    private val userTracker: FakeUserTracker = FakeUserTracker()

    @Rule
    @JvmField
    val activityRule =
        ActivityTestRule<LaunchNoteTaskActivity>(
            /* activityFactory= */ object :
                SingleActivityFactory<LaunchNoteTaskActivity>(LaunchNoteTaskActivity::class.java) {
                override fun create(intent: Intent?) =
                    LaunchNoteTaskActivity(
                        controller = noteTaskController,
                        userManager = userManager,
                        userTracker = userTracker
                    )
            },
            /* initialTouchMode= */ false,
            /* launchActivity= */ false,
        )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(userManager.isManagedProfile(eq(workProfileUser.id))).thenReturn(true)
    }

    @After
    fun tearDown() {
        activityRule.finishActivity()
    }

    @Test
    fun startActivityOnNonWorkProfileUser_shouldLaunchNoteTask() {
        activityRule.launchActivity(/* startIntent= */ null)

        verify(noteTaskController).showNoteTask(eq(NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT))
    }

    @Test
    fun startActivityOnWorkProfileUser_shouldLaunchProxyActivity() {
        userTracker.set(listOf(mainUser, workProfileUser), selectedUserIndex = 1)
        whenever(userManager.isManagedProfile).thenReturn(true)

        activityRule.launchActivity(/* startIntent= */ null)

        val mainUserHandle: UserHandle = mainUser.userHandle
        verify(noteTaskController).startNoteTaskProxyActivityForUser(eq(mainUserHandle))
    }

    private companion object {
        val mainUser = UserInfo(/* id= */ 0, /* name= */ "primary", /* flags= */ UserInfo.FLAG_MAIN)
        val workProfileUser =
            UserInfo(/* id= */ 10, /* name= */ "work", /* flags= */ UserInfo.FLAG_PROFILE)
    }
}
