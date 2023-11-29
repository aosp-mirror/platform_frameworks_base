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

package com.android.systemui.scene.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class WindowRootViewVisibilityRepositoryTest : SysuiTestCase() {
    private val iStatusBarService = mock<IStatusBarService>()
    private val executor = FakeExecutor(FakeSystemClock())
    private val underTest = WindowRootViewVisibilityRepository(iStatusBarService, executor)

    @Test
    fun isLockscreenOrShadeVisible_true() {
        underTest.setIsLockscreenOrShadeVisible(true)

        assertThat(underTest.isLockscreenOrShadeVisible.value).isTrue()
    }

    @Test
    fun isLockscreenOrShadeVisible_false() {
        underTest.setIsLockscreenOrShadeVisible(false)

        assertThat(underTest.isLockscreenOrShadeVisible.value).isFalse()
    }

    @Test
    fun onLockscreenOrShadeInteractive_statusBarServiceNotified() {
        underTest.onLockscreenOrShadeInteractive(
            shouldClearNotificationEffects = true,
            notificationCount = 3,
        )
        executor.runAllReady()

        verify(iStatusBarService).onPanelRevealed(true, 3)
    }

    @Test
    fun onLockscreenOrShadeNotInteractive_statusBarServiceNotified() {
        underTest.onLockscreenOrShadeNotInteractive()
        executor.runAllReady()

        verify(iStatusBarService).onPanelHidden()
    }
}
