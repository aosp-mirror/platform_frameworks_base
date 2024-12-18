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

package com.android.systemui.wallpapers.data.repository

import android.app.WallpaperInfo
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.user.data.model.SelectedUserModel
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WallpaperRepositoryImplTest : SysuiTestCase() {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val userRepository = FakeUserRepository()
    private val wallpaperManager: WallpaperManager = mock()

    private val underTest: WallpaperRepositoryImpl by lazy {
        WallpaperRepositoryImpl(
            testScope.backgroundScope,
            testDispatcher,
            fakeBroadcastDispatcher,
            userRepository,
            wallpaperManager,
            context,
        )
    }

    @Before
    fun setUp() {
        whenever(wallpaperManager.isWallpaperSupported).thenReturn(true)
        context.orCreateTestableResources.addOverride(
            com.android.internal.R.bool.config_dozeSupportsAodWallpaper,
            true,
        )
    }

    @Test
    fun wallpaperInfo_nullInfo() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wallpaperInfo)

            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(null)

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            assertThat(latest).isNull()
        }

    @Test
    fun wallpaperInfo_hasInfoFromManager() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wallpaperInfo)

            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(UNSUPPORTED_WP)

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            assertThat(latest).isEqualTo(UNSUPPORTED_WP)
        }

    @Test
    fun wallpaperInfo_initialValueIsFetched() =
        testScope.runTest {
            whenever(wallpaperManager.getWallpaperInfoForUser(USER_WITH_SUPPORTED_WP.id))
                .thenReturn(SUPPORTED_WP)
            userRepository.setUserInfos(listOf(USER_WITH_SUPPORTED_WP))
            userRepository.setSelectedUserInfo(USER_WITH_SUPPORTED_WP)

            // Start up the repo and let it run the initial fetch
            underTest.wallpaperInfo
            runCurrent()

            assertThat(underTest.wallpaperInfo.value).isEqualTo(SUPPORTED_WP)
        }

    @Test
    fun wallpaperInfo_updatesOnUserChanged() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wallpaperInfo)

            val user3 = UserInfo(/* id= */ 3, /* name= */ "user3", /* flags= */ 0)
            val user3Wp = mock<WallpaperInfo>()
            whenever(wallpaperManager.getWallpaperInfoForUser(user3.id)).thenReturn(user3Wp)

            val user4 = UserInfo(/* id= */ 4, /* name= */ "user4", /* flags= */ 0)
            val user4Wp = mock<WallpaperInfo>()
            whenever(wallpaperManager.getWallpaperInfoForUser(user4.id)).thenReturn(user4Wp)

            userRepository.setUserInfos(listOf(user3, user4))

            // WHEN user3 is selected
            userRepository.setSelectedUserInfo(user3)

            // THEN user3's wallpaper is used
            assertThat(latest).isEqualTo(user3Wp)

            // WHEN the user is switched to user4
            userRepository.setSelectedUserInfo(user4)

            // THEN user4's wallpaper is used
            assertThat(latest).isEqualTo(user4Wp)
        }

    @Test
    fun wallpaperInfo_doesNotUpdateOnUserChanging() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wallpaperInfo)

            val user3 = UserInfo(/* id= */ 3, /* name= */ "user3", /* flags= */ 0)
            val user3Wp = mock<WallpaperInfo>()
            whenever(wallpaperManager.getWallpaperInfoForUser(user3.id)).thenReturn(user3Wp)

            val user4 = UserInfo(/* id= */ 4, /* name= */ "user4", /* flags= */ 0)
            val user4Wp = mock<WallpaperInfo>()
            whenever(wallpaperManager.getWallpaperInfoForUser(user4.id)).thenReturn(user4Wp)

            userRepository.setUserInfos(listOf(user3, user4))

            // WHEN user3 is selected
            userRepository.setSelectedUserInfo(user3)

            // THEN user3's wallpaper is used
            assertThat(latest).isEqualTo(user3Wp)

            // WHEN the user has started switching to user4 but hasn't finished yet
            userRepository.selectedUser.value =
                SelectedUserModel(user4, SelectionStatus.SELECTION_IN_PROGRESS)

            // THEN the wallpaper still matches user3
            assertThat(latest).isEqualTo(user3Wp)
        }

    @Test
    fun wallpaperInfo_updatesOnIntent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wallpaperInfo)

            val wp1 = mock<WallpaperInfo>()
            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(wp1)

            assertThat(latest).isEqualTo(wp1)

            // WHEN the info is new and a broadcast is sent
            val wp2 = mock<WallpaperInfo>()
            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(wp2)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            // THEN the flow updates
            assertThat(latest).isEqualTo(wp2)
        }

    @Test
    fun wallpaperInfo_wallpaperNotSupported_alwaysNull() =
        testScope.runTest {
            whenever(wallpaperManager.isWallpaperSupported).thenReturn(false)

            val latest by collectLastValue(underTest.wallpaperInfo)
            assertThat(latest).isNull()

            // Even WHEN there *is* current wallpaper
            val wp1 = mock<WallpaperInfo>()
            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(wp1)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            // THEN the value is still null because wallpaper isn't supported
            assertThat(latest).isNull()
        }

    @Test
    fun wallpaperInfo_deviceDoesNotSupportAmbientWallpaper_alwaysFalse() =
        testScope.runTest {
            context.orCreateTestableResources.addOverride(
                com.android.internal.R.bool.config_dozeSupportsAodWallpaper,
                false
            )

            val latest by collectLastValue(underTest.wallpaperInfo)
            assertThat(latest).isNull()

            // Even WHEN there *is* current wallpaper
            val wp1 = mock<WallpaperInfo>()
            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(wp1)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            // THEN the value is still null because wallpaper isn't supported
            assertThat(latest).isNull()
        }

    @Test
    fun wallpaperSupportsAmbientMode_nullInfo_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wallpaperSupportsAmbientMode)

            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(null)

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            assertThat(latest).isFalse()
        }

    @Test
    fun wallpaperSupportsAmbientMode_infoDoesNotSupport_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wallpaperSupportsAmbientMode)

            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(UNSUPPORTED_WP)

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            assertThat(latest).isFalse()
        }

    @Test
    fun wallpaperSupportsAmbientMode_infoSupports_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wallpaperSupportsAmbientMode)

            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(SUPPORTED_WP)

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            assertThat(latest).isTrue()
        }

    @Test
    fun wallpaperSupportsAmbientMode_initialValueIsFetched_true() =
        testScope.runTest {
            whenever(wallpaperManager.getWallpaperInfoForUser(USER_WITH_SUPPORTED_WP.id))
                .thenReturn(SUPPORTED_WP)
            userRepository.setUserInfos(listOf(USER_WITH_SUPPORTED_WP))
            userRepository.setSelectedUserInfo(USER_WITH_SUPPORTED_WP)

            // Start up the repo and let it run the initial fetch
            underTest.wallpaperSupportsAmbientMode
            runCurrent()

            // WHEN the repo initially starts up (underTest is lazy), then it fetches the current
            // value for the wallpaper
            assertThat(underTest.wallpaperSupportsAmbientMode.value).isTrue()
        }

    @Test
    fun wallpaperSupportsAmbientMode_initialValueIsFetched_false() =
        testScope.runTest {
            whenever(wallpaperManager.getWallpaperInfoForUser(USER_WITH_UNSUPPORTED_WP.id))
                .thenReturn(UNSUPPORTED_WP)
            userRepository.setUserInfos(listOf(USER_WITH_UNSUPPORTED_WP))
            userRepository.setSelectedUserInfo(USER_WITH_UNSUPPORTED_WP)

            // WHEN the repo initially starts up (underTest is lazy), then it fetches the current
            // value for the wallpaper
            assertThat(underTest.wallpaperSupportsAmbientMode.value).isFalse()
        }

    @Test
    fun wallpaperSupportsAmbientMode_updatesOnUserChanged() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wallpaperSupportsAmbientMode)

            whenever(wallpaperManager.getWallpaperInfoForUser(USER_WITH_SUPPORTED_WP.id))
                .thenReturn(SUPPORTED_WP)
            whenever(wallpaperManager.getWallpaperInfoForUser(USER_WITH_UNSUPPORTED_WP.id))
                .thenReturn(UNSUPPORTED_WP)
            userRepository.setUserInfos(listOf(USER_WITH_SUPPORTED_WP, USER_WITH_UNSUPPORTED_WP))

            // WHEN a user with supported wallpaper is selected
            userRepository.setSelectedUserInfo(USER_WITH_SUPPORTED_WP)

            // THEN it's true
            assertThat(latest).isTrue()

            // WHEN the user is switched to a user with unsupported wallpaper
            userRepository.setSelectedUserInfo(USER_WITH_UNSUPPORTED_WP)

            // THEN it's false
            assertThat(latest).isFalse()
        }

    @Test
    fun wallpaperSupportsAmbientMode_doesNotUpdateOnUserChanging() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wallpaperSupportsAmbientMode)

            whenever(wallpaperManager.getWallpaperInfoForUser(USER_WITH_SUPPORTED_WP.id))
                .thenReturn(SUPPORTED_WP)
            whenever(wallpaperManager.getWallpaperInfoForUser(USER_WITH_UNSUPPORTED_WP.id))
                .thenReturn(UNSUPPORTED_WP)
            userRepository.setUserInfos(listOf(USER_WITH_SUPPORTED_WP, USER_WITH_UNSUPPORTED_WP))

            // WHEN a user with supported wallpaper is selected
            userRepository.setSelectedUserInfo(USER_WITH_SUPPORTED_WP)

            // THEN it's true
            assertThat(latest).isTrue()

            // WHEN the user has started switching to a user with unsupported wallpaper but hasn't
            // finished yet
            userRepository.selectedUser.value =
                SelectedUserModel(USER_WITH_UNSUPPORTED_WP, SelectionStatus.SELECTION_IN_PROGRESS)

            // THEN it still matches the old user
            assertThat(latest).isTrue()
        }

    @Test
    fun wallpaperSupportsAmbientMode_updatesOnIntent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wallpaperSupportsAmbientMode)

            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(UNSUPPORTED_WP)

            assertThat(latest).isFalse()

            // WHEN the info now supports ambient mode and a broadcast is sent
            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(SUPPORTED_WP)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            // THEN the flow updates
            assertThat(latest).isTrue()
        }

    @Test
    fun wallpaperSupportsAmbientMode_wallpaperNotSupported_alwaysFalse() =
        testScope.runTest {
            whenever(wallpaperManager.isWallpaperSupported).thenReturn(false)

            val latest by collectLastValue(underTest.wallpaperSupportsAmbientMode)
            assertThat(latest).isFalse()

            // Even WHEN the current wallpaper *does* support ambient mode
            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(SUPPORTED_WP)

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            // THEN the value is still false because wallpaper isn't supported
            assertThat(latest).isFalse()
        }

    @Test
    fun wallpaperSupportsAmbientMode_deviceDoesNotSupportAmbientWallpaper_alwaysFalse() =
        testScope.runTest {
            context.orCreateTestableResources.addOverride(
                com.android.internal.R.bool.config_dozeSupportsAodWallpaper,
                false
            )

            val latest by collectLastValue(underTest.wallpaperSupportsAmbientMode)
            assertThat(latest).isFalse()

            // Even WHEN the current wallpaper *does* support ambient mode
            whenever(wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(SUPPORTED_WP)

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            // THEN the value is still false because the device doesn't support it
            assertThat(latest).isFalse()
        }

    private companion object {
        val USER_WITH_UNSUPPORTED_WP = UserInfo(/* id= */ 3, /* name= */ "user3", /* flags= */ 0)
        val UNSUPPORTED_WP =
            mock<WallpaperInfo>().apply { whenever(this.supportsAmbientMode()).thenReturn(false) }

        val USER_WITH_SUPPORTED_WP = UserInfo(/* id= */ 4, /* name= */ "user4", /* flags= */ 0)
        val SUPPORTED_WP =
            mock<WallpaperInfo>().apply { whenever(this.supportsAmbientMode()).thenReturn(true) }
    }
}
