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
 */

package com.android.systemui.user

import android.os.UserManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.LayoutInflater
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class UserSwitcherActivityTest : SysuiTestCase() {
    @Mock
    private lateinit var activity: UserSwitcherActivity
    @Mock
    private lateinit var userSwitcherController: UserSwitcherController
    @Mock
    private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock
    private lateinit var layoutInflater: LayoutInflater
    @Mock
    private lateinit var falsingManager: FalsingManager
    @Mock
    private lateinit var userManager: UserManager
    @Mock
    private lateinit var userTracker: UserTracker

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        activity = UserSwitcherActivity(
            userSwitcherController,
            broadcastDispatcher,
            layoutInflater,
            falsingManager,
            userManager,
            userTracker
        )
    }

    @Test
    fun testMaxColumns() {
        assertThat(activity.getMaxColumns(3)).isEqualTo(4)
        assertThat(activity.getMaxColumns(4)).isEqualTo(4)
        assertThat(activity.getMaxColumns(5)).isEqualTo(3)
        assertThat(activity.getMaxColumns(6)).isEqualTo(3)
        assertThat(activity.getMaxColumns(7)).isEqualTo(4)
        assertThat(activity.getMaxColumns(9)).isEqualTo(5)
    }
}
