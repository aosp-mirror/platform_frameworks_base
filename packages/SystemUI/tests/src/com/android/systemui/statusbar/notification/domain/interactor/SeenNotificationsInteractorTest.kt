/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.domain.interactor

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class SeenNotificationsInteractorTest : SysuiTestCase() {

    private val repository = ActiveNotificationListRepository()
    private val underTest = SeenNotificationsInteractor(repository)

    @Test
    fun testNoFilteredOutSeenNotifications() = runTest {
        val hasFilteredOutSeenNotifications by
            collectLastValue(underTest.hasFilteredOutSeenNotifications)

        underTest.setHasFilteredOutSeenNotifications(false)

        assertThat(hasFilteredOutSeenNotifications).isFalse()
    }

    @Test
    fun testHasFilteredOutSeenNotifications() = runTest {
        val hasFilteredOutSeenNotifications by
            collectLastValue(underTest.hasFilteredOutSeenNotifications)

        underTest.setHasFilteredOutSeenNotifications(true)

        assertThat(hasFilteredOutSeenNotifications).isTrue()
    }
}
