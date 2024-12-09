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

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.data.repository.activityManagerRepository
import com.android.systemui.activity.data.repository.fake
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.notification.domain.model.NotificationChipModel
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
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
    val factory = kosmos.singleNotificationChipInteractorFactory

    @Test
    fun notificationChip_startsWithStartingModel() =
        kosmos.runTest {
            val icon = mock<StatusBarIconView>()
            val startingNotif =
                activeNotificationModel(key = "notif1", statusBarChipIcon = icon, whenTime = 5432)

            val underTest = factory.create(startingNotif)

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest!!.key).isEqualTo("notif1")
            assertThat(latest!!.statusBarChipIconView).isEqualTo(icon)
            assertThat(latest!!.whenTime).isEqualTo(5432)
        }

    @Test
    fun notificationChip_updatesAfterSet() =
        kosmos.runTest {
            val originalIconView = mock<StatusBarIconView>()
            val underTest =
                factory.create(
                    activeNotificationModel(key = "notif1", statusBarChipIcon = originalIconView)
                )

            val latest by collectLastValue(underTest.notificationChip)

            val newIconView = mock<StatusBarIconView>()
            underTest.setNotification(
                activeNotificationModel(
                    key = "notif1",
                    statusBarChipIcon = newIconView,
                    whenTime = 6543,
                )
            )

            assertThat(latest!!.key).isEqualTo("notif1")
            assertThat(latest!!.statusBarChipIconView).isEqualTo(newIconView)
            assertThat(latest!!.whenTime).isEqualTo(6543)
        }

    @Test
    fun notificationChip_ignoresSetWithDifferentKey() =
        kosmos.runTest {
            val originalIconView = mock<StatusBarIconView>()
            val underTest =
                factory.create(
                    activeNotificationModel(key = "notif1", statusBarChipIcon = originalIconView)
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
                factory.create(activeNotificationModel(key = "notif1", statusBarChipIcon = null))

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest).isNull()
        }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun notificationChip_cdEnabled_missingStatusBarIconChipView_inConstructor_emitsNotNull() =
        kosmos.runTest {
            val underTest =
                factory.create(
                    activeNotificationModel(
                        key = "notif1",
                        statusBarChipIcon = null,
                        whenTime = 123L,
                    )
                )

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest)
                .isEqualTo(
                    NotificationChipModel("notif1", statusBarChipIconView = null, whenTime = 123L)
                )
        }

    @Test
    fun notificationChip_missingStatusBarIconChipView_inSet_emitsNull() =
        kosmos.runTest {
            val startingNotif = activeNotificationModel(key = "notif1", statusBarChipIcon = mock())
            val underTest = factory.create(startingNotif)
            val latest by collectLastValue(underTest.notificationChip)
            assertThat(latest).isNotNull()

            underTest.setNotification(
                activeNotificationModel(key = "notif1", statusBarChipIcon = null)
            )

            assertThat(latest).isNull()
        }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun notificationChip_missingStatusBarIconChipView_inSet_cdEnabled_emitsNotNull() =
        kosmos.runTest {
            val startingNotif = activeNotificationModel(key = "notif1", statusBarChipIcon = mock())
            val underTest = factory.create(startingNotif)
            val latest by collectLastValue(underTest.notificationChip)
            assertThat(latest).isNotNull()

            underTest.setNotification(
                activeNotificationModel(key = "notif1", statusBarChipIcon = null, whenTime = 123L)
            )

            assertThat(latest)
                .isEqualTo(
                    NotificationChipModel(
                        key = "notif1",
                        statusBarChipIconView = null,
                        whenTime = 123L,
                    )
                )
        }

    @Test
    fun notificationChip_appIsVisibleOnCreation_emitsNull() =
        kosmos.runTest {
            activityManagerRepository.fake.startingIsAppVisibleValue = true

            val underTest =
                factory.create(
                    activeNotificationModel(key = "notif", uid = UID, statusBarChipIcon = mock())
                )

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest).isNull()
        }

    @Test
    fun notificationChip_appNotVisibleOnCreation_emitsValue() =
        kosmos.runTest {
            activityManagerRepository.fake.startingIsAppVisibleValue = false

            val underTest =
                factory.create(
                    activeNotificationModel(key = "notif", uid = UID, statusBarChipIcon = mock())
                )

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest).isNotNull()
        }

    @Test
    fun notificationChip_hidesWhenAppIsVisible() =
        kosmos.runTest {
            val underTest =
                factory.create(
                    activeNotificationModel(key = "notif", uid = UID, statusBarChipIcon = mock())
                )

            val latest by collectLastValue(underTest.notificationChip)

            activityManagerRepository.fake.setIsAppVisible(UID, false)
            assertThat(latest).isNotNull()

            activityManagerRepository.fake.setIsAppVisible(UID, true)
            assertThat(latest).isNull()

            activityManagerRepository.fake.setIsAppVisible(UID, false)
            assertThat(latest).isNotNull()
        }

    // Note: This test is theoretically impossible because the notification key should contain the
    // UID, so if the UID changes then the key would also change and a new interactor would be
    // created. But, test it just in case.
    @Test
    fun notificationChip_updatedUid_rechecksAppVisibility_oldObserverUnregistered() =
        kosmos.runTest {
            activityManagerRepository.fake.startingIsAppVisibleValue = false

            val hiddenUid = 100
            val shownUid = 101

            val underTest =
                factory.create(
                    activeNotificationModel(
                        key = "notif",
                        uid = hiddenUid,
                        statusBarChipIcon = mock(),
                    )
                )
            val latest by collectLastValue(underTest.notificationChip)
            assertThat(latest).isNotNull()

            // WHEN the notif gets a new UID that starts as visible
            activityManagerRepository.fake.startingIsAppVisibleValue = true
            underTest.setNotification(
                activeNotificationModel(key = "notif", uid = shownUid, statusBarChipIcon = mock())
            )

            // THEN we re-fetch the app visibility state with the new UID, and since that UID is
            // visible, we hide the chip
            assertThat(latest).isNull()
        }

    companion object {
        private const val UID = 885
    }
}
