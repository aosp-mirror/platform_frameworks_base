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

package com.android.systemui.statusbar.chips.notification.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.statusBarChipsLogger
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class SingleNotificationChipInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    val logger = kosmos.statusBarChipsLogger

    @Test
    fun notificationChip_startsWithStartingModel() =
        kosmos.runTest {
            val icon = mock<StatusBarIconView>()
            val startingNotif = activeNotificationModel(key = "notif1", statusBarChipIcon = icon)

            val underTest = SingleNotificationChipInteractor(startingNotif, logger)

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest!!.key).isEqualTo("notif1")
            assertThat(latest!!.statusBarChipIconView).isEqualTo(icon)
        }

    @Test
    fun notificationChip_updatesAfterSet() =
        kosmos.runTest {
            val originalIconView = mock<StatusBarIconView>()
            val underTest =
                SingleNotificationChipInteractor(
                    activeNotificationModel(key = "notif1", statusBarChipIcon = originalIconView),
                    logger,
                )

            val latest by collectLastValue(underTest.notificationChip)

            val newIconView = mock<StatusBarIconView>()
            underTest.setNotification(
                activeNotificationModel(key = "notif1", statusBarChipIcon = newIconView)
            )

            assertThat(latest!!.key).isEqualTo("notif1")
            assertThat(latest!!.statusBarChipIconView).isEqualTo(newIconView)
        }

    @Test
    fun notificationChip_ignoresSetWithDifferentKey() =
        kosmos.runTest {
            val originalIconView = mock<StatusBarIconView>()
            val underTest =
                SingleNotificationChipInteractor(
                    activeNotificationModel(key = "notif1", statusBarChipIcon = originalIconView),
                    logger,
                )

            val latest by collectLastValue(underTest.notificationChip)

            val newIconView = mock<StatusBarIconView>()
            underTest.setNotification(
                activeNotificationModel(key = "other_notif", statusBarChipIcon = newIconView)
            )

            assertThat(latest!!.key).isEqualTo("notif1")
            assertThat(latest!!.statusBarChipIconView).isEqualTo(originalIconView)
        }

    @Test
    fun notificationChip_missingStatusBarIconChipView_inConstructor_emitsNull() =
        kosmos.runTest {
            val underTest =
                SingleNotificationChipInteractor(
                    activeNotificationModel(key = "notif1", statusBarChipIcon = null),
                    logger,
                )

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest).isNull()
        }

    @Test
    fun notificationChip_missingStatusBarIconChipView_inSet_emitsNull() =
        kosmos.runTest {
            val startingNotif = activeNotificationModel(key = "notif1", statusBarChipIcon = mock())
            val underTest = SingleNotificationChipInteractor(startingNotif, logger)
            val latest by collectLastValue(underTest.notificationChip)
            assertThat(latest).isNotNull()

            underTest.setNotification(
                activeNotificationModel(key = "notif1", statusBarChipIcon = null)
            )

            assertThat(latest).isNull()
        }
}
