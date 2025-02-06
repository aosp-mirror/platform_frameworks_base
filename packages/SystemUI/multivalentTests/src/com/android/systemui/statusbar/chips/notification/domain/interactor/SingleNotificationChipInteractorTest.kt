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

import android.platform.test.annotations.DisableFlags
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
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
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
                activeNotificationModel(
                    key = "notif1",
                    appName = "Fake Name",
                    statusBarChipIcon = icon,
                    promotedContent = PROMOTED_CONTENT,
                )

            val underTest = factory.create(startingNotif, creationTime = 1)

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest!!.key).isEqualTo("notif1")
            assertThat(latest!!.appName).isEqualTo("Fake Name")
            assertThat(latest!!.statusBarChipIconView).isEqualTo(icon)
            assertThat(latest!!.promotedContent).isEqualTo(PROMOTED_CONTENT)
        }

    @Test
    fun notificationChip_updatesAfterSet() =
        kosmos.runTest {
            val originalIconView = mock<StatusBarIconView>()
            val underTest =
                factory.create(
                    activeNotificationModel(
                        key = "notif1",
                        appName = "Fake Name",
                        statusBarChipIcon = originalIconView,
                        promotedContent = PROMOTED_CONTENT,
                    ),
                    creationTime = 1,
                )

            val latest by collectLastValue(underTest.notificationChip)

            val newIconView = mock<StatusBarIconView>()
            underTest.setNotification(
                activeNotificationModel(
                    key = "notif1",
                    appName = "New Name",
                    statusBarChipIcon = newIconView,
                    promotedContent = PROMOTED_CONTENT,
                )
            )

            assertThat(latest!!.key).isEqualTo("notif1")
            assertThat(latest!!.appName).isEqualTo("New Name")
            assertThat(latest!!.statusBarChipIconView).isEqualTo(newIconView)
        }

    @Test
    fun notificationChip_ignoresSetWithDifferentKey() =
        kosmos.runTest {
            val originalIconView = mock<StatusBarIconView>()
            val underTest =
                factory.create(
                    activeNotificationModel(
                        key = "notif1",
                        statusBarChipIcon = originalIconView,
                        promotedContent = PROMOTED_CONTENT,
                    ),
                    creationTime = 1,
                )

            val latest by collectLastValue(underTest.notificationChip)

            val newIconView = mock<StatusBarIconView>()
            underTest.setNotification(
                activeNotificationModel(
                    key = "other_notif",
                    statusBarChipIcon = newIconView,
                    promotedContent = PROMOTED_CONTENT,
                )
            )

            assertThat(latest!!.key).isEqualTo("notif1")
            assertThat(latest!!.statusBarChipIconView).isEqualTo(originalIconView)
        }

    @Test
    fun notificationChip_ignoresSetWithNullPromotedContent() =
        kosmos.runTest {
            val originalIconView = mock<StatusBarIconView>()
            val underTest =
                factory.create(
                    activeNotificationModel(
                        key = "notif1",
                        statusBarChipIcon = originalIconView,
                        promotedContent = PROMOTED_CONTENT,
                    ),
                    creationTime = 1,
                )

            val latest by collectLastValue(underTest.notificationChip)

            val newIconView = mock<StatusBarIconView>()
            underTest.setNotification(
                activeNotificationModel(
                    key = "notif1",
                    statusBarChipIcon = newIconView,
                    promotedContent = null,
                )
            )

            assertThat(latest!!.statusBarChipIconView).isEqualTo(originalIconView)
        }

    @Test
    @DisableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun notificationChip_missingStatusBarIconChipView_cdFlagDisabled_inConstructor_emitsNull() =
        kosmos.runTest {
            val underTest =
                factory.create(
                    activeNotificationModel(
                        key = "notif1",
                        statusBarChipIcon = null,
                        promotedContent = PROMOTED_CONTENT,
                    ),
                    creationTime = 1,
                )

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest).isNull()
        }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun notificationChip_missingStatusBarIconChipView_cdFlagEnabled_inConstructor_emitsNotNull() =
        kosmos.runTest {
            val underTest =
                factory.create(
                    activeNotificationModel(
                        key = "notif1",
                        statusBarChipIcon = null,
                        promotedContent = PROMOTED_CONTENT,
                    ),
                    32L,
                )

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest).isNotNull()
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
                        promotedContent = PROMOTED_CONTENT,
                    ),
                    creationTime = 1,
                )

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest).isNotNull()
            assertThat(latest!!.key).isEqualTo("notif1")
        }

    @Test
    @DisableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun notificationChip_cdFlagDisabled_missingStatusBarIconChipView_inSet_emitsNull() =
        kosmos.runTest {
            val startingNotif =
                activeNotificationModel(
                    key = "notif1",
                    statusBarChipIcon = mock(),
                    promotedContent = PROMOTED_CONTENT,
                )
            val underTest = factory.create(startingNotif, creationTime = 1)
            val latest by collectLastValue(underTest.notificationChip)
            assertThat(latest).isNotNull()

            underTest.setNotification(
                activeNotificationModel(
                    key = "notif1",
                    statusBarChipIcon = null,
                    promotedContent = PROMOTED_CONTENT,
                )
            )

            assertThat(latest).isNull()
        }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun notificationChip_cdFlagEnabled_missingStatusBarIconChipView_inSet_emitsNotNull() =
        kosmos.runTest {
            val startingNotif =
                activeNotificationModel(
                    key = "notif1",
                    statusBarChipIcon = mock(),
                    promotedContent = PROMOTED_CONTENT,
                )
            val underTest = factory.create(startingNotif, 123L)
            val latest by collectLastValue(underTest.notificationChip)
            assertThat(latest).isNotNull()

            underTest.setNotification(
                activeNotificationModel(
                    key = "notif1",
                    statusBarChipIcon = null,
                    promotedContent = PROMOTED_CONTENT,
                )
            )

            assertThat(latest).isNotNull()
        }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun notificationChip_missingStatusBarIconChipView_inSet_cdEnabled_emitsNotNull() =
        kosmos.runTest {
            val startingNotif =
                activeNotificationModel(
                    key = "notif1",
                    statusBarChipIcon = mock(),
                    promotedContent = PROMOTED_CONTENT,
                )
            val underTest = factory.create(startingNotif, creationTime = 1)
            val latest by collectLastValue(underTest.notificationChip)
            assertThat(latest).isNotNull()

            underTest.setNotification(
                activeNotificationModel(
                    key = "notif1",
                    statusBarChipIcon = null,
                    promotedContent = PROMOTED_CONTENT,
                )
            )

            assertThat(latest).isNotNull()
            assertThat(latest!!.key).isEqualTo("notif1")
        }

    @Test
    fun notificationChip_missingPromotedContent_inConstructor_emitsNull() =
        kosmos.runTest {
            val underTest =
                factory.create(
                    activeNotificationModel(
                        key = "notif1",
                        statusBarChipIcon = mock(),
                        promotedContent = null,
                    ),
                    creationTime = 1,
                )

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest).isNull()
        }

    @Test
    fun notificationChip_appIsVisibleOnCreation_emitsNull() =
        kosmos.runTest {
            activityManagerRepository.fake.startingIsAppVisibleValue = true

            val underTest =
                factory.create(
                    activeNotificationModel(
                        key = "notif",
                        uid = UID,
                        statusBarChipIcon = mock(),
                        promotedContent = PROMOTED_CONTENT,
                    ),
                    creationTime = 1,
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
                    activeNotificationModel(
                        key = "notif",
                        uid = UID,
                        statusBarChipIcon = mock(),
                        promotedContent = PROMOTED_CONTENT,
                    ),
                    creationTime = 1,
                )

            val latest by collectLastValue(underTest.notificationChip)

            assertThat(latest).isNotNull()
        }

    @Test
    fun notificationChip_hidesWhenAppIsVisible() =
        kosmos.runTest {
            val underTest =
                factory.create(
                    activeNotificationModel(
                        key = "notif",
                        uid = UID,
                        statusBarChipIcon = mock(),
                        promotedContent = PROMOTED_CONTENT,
                    ),
                    creationTime = 1,
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
                        promotedContent = PROMOTED_CONTENT,
                    ),
                    creationTime = 1,
                )
            val latest by collectLastValue(underTest.notificationChip)
            assertThat(latest).isNotNull()

            // WHEN the notif gets a new UID that starts as visible
            activityManagerRepository.fake.startingIsAppVisibleValue = true
            underTest.setNotification(
                activeNotificationModel(
                    key = "notif",
                    uid = shownUid,
                    statusBarChipIcon = mock(),
                    promotedContent = PROMOTED_CONTENT,
                )
            )

            // THEN we re-fetch the app visibility state with the new UID, and since that UID is
            // visible, we hide the chip
            assertThat(latest).isNull()
        }

    companion object {
        private const val UID = 885
        private val PROMOTED_CONTENT = PromotedNotificationContentModel.Builder("notif1").build()
    }
}
