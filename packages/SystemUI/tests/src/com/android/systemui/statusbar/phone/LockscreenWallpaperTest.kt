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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone

import android.app.WallpaperManager
import android.content.pm.UserInfo
import android.os.Looper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.utils.os.FakeHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.verify

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class LockscreenWallpaperTest : SysuiTestCase() {

    private lateinit var underTest: LockscreenWallpaper

    private val testScope = TestScope(StandardTestDispatcher())
    private val userRepository = FakeUserRepository()

    private val wallpaperManager: WallpaperManager = mock()

    @Before
    fun setUp() {
        whenever(wallpaperManager.isLockscreenLiveWallpaperEnabled).thenReturn(false)
        whenever(wallpaperManager.isWallpaperSupported).thenReturn(true)
        underTest =
            LockscreenWallpaper(
                /* wallpaperManager= */ wallpaperManager,
                /* iWallpaperManager= */ mock(),
                /* keyguardUpdateMonitor= */ mock(),
                /* dumpManager= */ mock(),
                /* mediaManager= */ mock(),
                /* mainHandler= */ FakeHandler(Looper.getMainLooper()),
                /* javaAdapter= */ JavaAdapter(testScope.backgroundScope),
                /* userRepository= */ userRepository,
                /* userTracker= */ mock(),
            )
        underTest.start()
    }

    @Test
    fun getBitmap_matchesUserIdFromUserRepo() =
        testScope.runTest {
            val info = UserInfo(/* id= */ 5, /* name= */ "id5", /* flags= */ 0)
            userRepository.setUserInfos(listOf(info))
            userRepository.setSelectedUserInfo(info)

            underTest.bitmap

            verify(wallpaperManager).getWallpaperFile(any(), eq(5))
        }

    @Test
    fun getBitmap_usesOldUserIfNewUserInProgress() =
        testScope.runTest {
            val info5 = UserInfo(/* id= */ 5, /* name= */ "id5", /* flags= */ 0)
            val info6 = UserInfo(/* id= */ 6, /* name= */ "id6", /* flags= */ 0)
            userRepository.setUserInfos(listOf(info5, info6))
            userRepository.setSelectedUserInfo(info5)

            // WHEN the selection of user 6 is only in progress
            userRepository.setSelectedUserInfo(
                info6,
                selectionStatus = SelectionStatus.SELECTION_IN_PROGRESS
            )

            underTest.bitmap

            // THEN we still use user 5 for wallpaper selection
            verify(wallpaperManager).getWallpaperFile(any(), eq(5))
        }
}
