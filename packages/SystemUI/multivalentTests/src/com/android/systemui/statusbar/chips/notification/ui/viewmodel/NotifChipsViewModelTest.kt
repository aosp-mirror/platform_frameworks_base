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

package com.android.systemui.statusbar.chips.notification.ui.viewmodel

import android.platform.test.annotations.EnableFlags
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.notification.domain.interactor.statusBarNotificationChipsInteractor
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
@EnableFlags(StatusBarNotifChips.FLAG_NAME)
class NotifChipsViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository

    private val underTest = kosmos.notifChipsViewModel

    @Test
    fun chips_noNotifs_empty() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chips)

            setNotifs(emptyList())

            assertThat(latest).isEmpty()
        }

    @Test
    fun chips_notifMissingStatusBarChipIconView_empty() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chips)

            setNotifs(listOf(activeNotificationModel(key = "notif", statusBarChipIcon = null)))

            assertThat(latest).isEmpty()
        }

    @Test
    fun chips_oneNotif_statusBarIconViewMatches() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chips)

            val icon = mock<StatusBarIconView>()
            setNotifs(listOf(activeNotificationModel(key = "notif", statusBarChipIcon = icon)))

            assertThat(latest).hasSize(1)
            val chip = latest!![0]
            assertThat(chip).isInstanceOf(OngoingActivityChipModel.Shown.ShortTimeDelta::class.java)
            assertThat(chip.icon).isEqualTo(OngoingActivityChipModel.ChipIcon.StatusBarView(icon))
        }

    @Test
    fun chips_twoNotifs_twoChips() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chips)

            val firstIcon = mock<StatusBarIconView>()
            val secondIcon = mock<StatusBarIconView>()
            setNotifs(
                listOf(
                    activeNotificationModel(key = "notif1", statusBarChipIcon = firstIcon),
                    activeNotificationModel(key = "notif2", statusBarChipIcon = secondIcon),
                )
            )

            assertThat(latest).hasSize(2)
            assertIsNotifChip(latest!![0], firstIcon)
            assertIsNotifChip(latest!![1], secondIcon)
        }

    @Test
    fun chips_clickingChipNotifiesInteractor() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chips)
            val latestChipTap by
                collectLastValue(
                    kosmos.statusBarNotificationChipsInteractor.promotedNotificationChipTapEvent
                )

            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "clickTest",
                        statusBarChipIcon = mock<StatusBarIconView>(),
                    )
                )
            )
            val chip = latest!![0]

            chip.onClickListener!!.onClick(mock<View>())

            assertThat(latestChipTap).isEqualTo("clickTest")
        }

    private fun setNotifs(notifs: List<ActiveNotificationModel>) {
        activeNotificationListRepository.activeNotifications.value =
            ActiveNotificationsStore.Builder()
                .apply { notifs.forEach { addIndividualNotif(it) } }
                .build()
        testScope.runCurrent()
    }

    companion object {
        fun assertIsNotifChip(latest: OngoingActivityChipModel?, expectedIcon: StatusBarIconView) {
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.IconOnly::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown).icon)
                .isEqualTo(OngoingActivityChipModel.ChipIcon.StatusBarView(expectedIcon))
        }
    }
}
