/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.testing.AndroidTestingRunner
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.screenshot.data.repository.profileTypeRepository
import com.android.systemui.screenshot.policy.TestUserIds
import com.android.systemui.screenshot.resources.Messages
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@RunWith(AndroidTestingRunner::class)
class AnnouncementResolverTest {
    private val kosmos = Kosmos()

    private val screenshotMessage = "Saving screenshot"
    private val workMessage = "Saving to work profile"
    private val privateMessage = "Saving to private profile"

    private val messages =
        mock(Messages::class.java).also {
            whenever(it.savingScreenshotAnnouncement).thenReturn(screenshotMessage)
            whenever(it.savingToWorkProfileAnnouncement).thenReturn(workMessage)
            whenever(it.savingToPrivateProfileAnnouncement).thenReturn(privateMessage)
        }

    private val announcementResolver =
        AnnouncementResolver(
            messages,
            kosmos.profileTypeRepository,
            TestScope(UnconfinedTestDispatcher())
        )

    @Test
    fun personalProfile() = runTest {
        assertThat(announcementResolver.getScreenshotAnnouncement(TestUserIds.PERSONAL))
            .isEqualTo(screenshotMessage)
    }

    @Test
    fun workProfile() = runTest {
        assertThat(announcementResolver.getScreenshotAnnouncement(TestUserIds.WORK))
            .isEqualTo(workMessage)
    }

    @Test
    fun privateProfile() = runTest {
        assertThat(announcementResolver.getScreenshotAnnouncement(TestUserIds.PRIVATE))
            .isEqualTo(privateMessage)
    }
}
