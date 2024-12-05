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
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarNotificationChipsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository

    private val underTest by lazy {
        kosmos.statusBarNotificationChipsInteractor.also { it.start() }
    }

    @Test
    @DisableFlags(StatusBarNotifChips.FLAG_NAME)
    fun notificationChips_flagOff_noNotifs() =
        testScope.runTest {
            val latest by collectLastValue(underTest.notificationChips)

            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = mock<StatusBarIconView>(),
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )

            assertThat(latest).isEmpty()
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun notificationChips_noNotifs_empty() =
        testScope.runTest {
            val latest by collectLastValue(underTest.notificationChips)

            setNotifs(emptyList())

            assertThat(latest).isEmpty()
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun notificationChips_notifMissingStatusBarChipIconView_empty() =
        testScope.runTest {
            val latest by collectLastValue(underTest.notificationChips)

            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = null,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )

            assertThat(latest).isEmpty()
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun notificationChips_onePromotedNotif_statusBarIconViewMatches() =
        testScope.runTest {
            val latest by collectLastValue(underTest.notificationChips)

            val icon = mock<StatusBarIconView>()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = icon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )

            assertThat(latest).hasSize(1)
            assertThat(latest!![0].key).isEqualTo("notif")
            assertThat(latest!![0].statusBarChipIconView).isEqualTo(icon)
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun notificationChips_onlyForPromotedNotifs() =
        testScope.runTest {
            val latest by collectLastValue(underTest.notificationChips)

            val firstIcon = mock<StatusBarIconView>()
            val secondIcon = mock<StatusBarIconView>()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif1",
                        statusBarChipIcon = firstIcon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif1").build(),
                    ),
                    activeNotificationModel(
                        key = "notif2",
                        statusBarChipIcon = secondIcon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif2").build(),
                    ),
                    activeNotificationModel(
                        key = "notif3",
                        statusBarChipIcon = mock<StatusBarIconView>(),
                        promotedContent = null,
                    ),
                )
            )

            assertThat(latest).hasSize(2)
            assertThat(latest!![0].key).isEqualTo("notif1")
            assertThat(latest!![0].statusBarChipIconView).isEqualTo(firstIcon)
            assertThat(latest!![1].key).isEqualTo("notif2")
            assertThat(latest!![1].statusBarChipIconView).isEqualTo(secondIcon)
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun notificationChips_notifUpdatesGoThrough() =
        testScope.runTest {
            val latest by collectLastValue(underTest.notificationChips)

            val firstIcon = mock<StatusBarIconView>()
            val secondIcon = mock<StatusBarIconView>()
            val thirdIcon = mock<StatusBarIconView>()

            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = firstIcon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )
            assertThat(latest).hasSize(1)
            assertThat(latest!![0].key).isEqualTo("notif")
            assertThat(latest!![0].statusBarChipIconView).isEqualTo(firstIcon)

            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = secondIcon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )
            assertThat(latest).hasSize(1)
            assertThat(latest!![0].key).isEqualTo("notif")
            assertThat(latest!![0].statusBarChipIconView).isEqualTo(secondIcon)

            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = thirdIcon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )
            assertThat(latest).hasSize(1)
            assertThat(latest!![0].key).isEqualTo("notif")
            assertThat(latest!![0].statusBarChipIconView).isEqualTo(thirdIcon)
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun notificationChips_promotedNotifDisappearsThenReappears() =
        testScope.runTest {
            val latest by collectLastValue(underTest.notificationChips)

            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = mock(),
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )
            assertThat(latest).hasSize(1)
            assertThat(latest!![0].key).isEqualTo("notif")

            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = mock(),
                        promotedContent = null,
                    )
                )
            )
            assertThat(latest).isEmpty()

            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = mock(),
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )
            assertThat(latest).hasSize(1)
            assertThat(latest!![0].key).isEqualTo("notif")
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun notificationChips_notifChangesKey() =
        testScope.runTest {
            val latest by collectLastValue(underTest.notificationChips)

            val firstIcon = mock<StatusBarIconView>()
            val secondIcon = mock<StatusBarIconView>()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif|uid1",
                        statusBarChipIcon = firstIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("notif|uid1").build(),
                    )
                )
            )
            assertThat(latest).hasSize(1)
            assertThat(latest!![0].key).isEqualTo("notif|uid1")
            assertThat(latest!![0].statusBarChipIconView).isEqualTo(firstIcon)

            // WHEN a notification changes UID, which is a key change
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif|uid2",
                        statusBarChipIcon = secondIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("notif|uid2").build(),
                    )
                )
            )

            // THEN we correctly update
            assertThat(latest).hasSize(1)
            assertThat(latest!![0].key).isEqualTo("notif|uid2")
            assertThat(latest!![0].statusBarChipIconView).isEqualTo(secondIcon)
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun onPromotedNotificationChipTapped_emitsKeys() =
        testScope.runTest {
            val latest by collectValues(underTest.promotedNotificationChipTapEvent)

            underTest.onPromotedNotificationChipTapped("fakeKey")

            assertThat(latest).hasSize(1)
            assertThat(latest[0]).isEqualTo("fakeKey")

            underTest.onPromotedNotificationChipTapped("fakeKey2")

            assertThat(latest).hasSize(2)
            assertThat(latest[1]).isEqualTo("fakeKey2")
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun onPromotedNotificationChipTapped_sameKeyTwice_emitsTwice() =
        testScope.runTest {
            val latest by collectValues(underTest.promotedNotificationChipTapEvent)

            underTest.onPromotedNotificationChipTapped("fakeKey")
            underTest.onPromotedNotificationChipTapped("fakeKey")

            assertThat(latest).hasSize(2)
            assertThat(latest[0]).isEqualTo("fakeKey")
            assertThat(latest[1]).isEqualTo("fakeKey")
        }

    private fun setNotifs(notifs: List<ActiveNotificationModel>) {
        activeNotificationListRepository.activeNotifications.value =
            ActiveNotificationsStore.Builder()
                .apply { notifs.forEach { addIndividualNotif(it) } }
                .build()
    }
}
