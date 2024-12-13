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
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.notification.domain.interactor.statusBarNotificationChipsInteractor
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.UnconfinedFakeHeadsUpRowRepository
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.headsup.PinnedStatus
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.stack.data.repository.headsUpNotificationRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(StatusBarNotifChips.FLAG_NAME)
class NotifChipsViewModelTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().useUnconfinedTestDispatcher().apply {
            // Don't be in lockscreen so that HUNs are allowed
            fakeKeyguardTransitionRepository =
                FakeKeyguardTransitionRepository(initInLockscreen = false, testScope = testScope)
        }
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository

    private val underTest by lazy { kosmos.notifChipsViewModel }

    @Before
    fun setUp() {
        kosmos.statusBarNotificationChipsInteractor.start()
    }

    @Test
    fun chips_noNotifs_empty() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

            setNotifs(emptyList())

            assertThat(latest).isEmpty()
        }

    @Test
    fun chips_notifMissingStatusBarChipIconView_empty() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

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
    fun chips_onePromotedNotif_statusBarIconViewMatches() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

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
            val chip = latest!![0]
            assertThat(chip).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat(chip.icon).isEqualTo(OngoingActivityChipModel.ChipIcon.StatusBarView(icon))
        }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun chips_onePromotedNotif_connectedDisplaysFlagEnabled_statusBarIconMatches() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

            val notifKey = "notif"
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = notifKey,
                        statusBarChipIcon = null,
                        promotedContent = PromotedNotificationContentModel.Builder(notifKey).build(),
                    )
                )
            )

            assertThat(latest).hasSize(1)
            val chip = latest!![0]
            assertThat(chip).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat(chip.icon)
                .isEqualTo(OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon(notifKey))
        }

    @Test
    fun chips_onePromotedNotif_colorMatches() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

            val promotedContentBuilder =
                PromotedNotificationContentModel.Builder("notif").apply {
                    this.colors =
                        PromotedNotificationContentModel.Colors(
                            backgroundColor = 56,
                            primaryTextColor = 89,
                        )
                }
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = mock<StatusBarIconView>(),
                        promotedContent = promotedContentBuilder.build(),
                    )
                )
            )

            assertThat(latest).hasSize(1)
            val colors = latest!![0].colors
            assertThat(colors).isInstanceOf(ColorsModel.Custom::class.java)
            assertThat((colors as ColorsModel.Custom).backgroundColorInt).isEqualTo(56)
            assertThat((colors as ColorsModel.Custom).primaryTextColorInt).isEqualTo(89)
        }

    @Test
    fun chips_onlyForPromotedNotifs() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

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
            assertIsNotifChip(latest!![0], firstIcon)
            assertIsNotifChip(latest!![1], secondIcon)
        }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun chips_connectedDisplaysFlagEnabled_onlyForPromotedNotifs() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

            val firstKey = "notif1"
            val secondKey = "notif2"
            val thirdKey = "notif3"
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = firstKey,
                        statusBarChipIcon = null,
                        promotedContent = PromotedNotificationContentModel.Builder(firstKey).build(),
                    ),
                    activeNotificationModel(
                        key = secondKey,
                        statusBarChipIcon = null,
                        promotedContent =
                            PromotedNotificationContentModel.Builder(secondKey).build(),
                    ),
                    activeNotificationModel(
                        key = thirdKey,
                        statusBarChipIcon = null,
                        promotedContent = null,
                    ),
                )
            )

            assertThat(latest).hasSize(2)
            assertIsNotifKey(latest!![0], firstKey)
            assertIsNotifKey(latest!![1], secondKey)
        }

    @Test
    fun chips_hasShortCriticalText_usesTextInsteadOfTime() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

            val promotedContentBuilder =
                PromotedNotificationContentModel.Builder("notif").apply {
                    this.shortCriticalText = "Arrived"
                    this.time =
                        PromotedNotificationContentModel.When(
                            time = 6543L,
                            mode = PromotedNotificationContentModel.When.Mode.BasicTime,
                        )
                }
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = mock<StatusBarIconView>(),
                        promotedContent = promotedContentBuilder.build(),
                    )
                )
            )

            assertThat(latest).hasSize(1)
            assertThat(latest!![0]).isInstanceOf(OngoingActivityChipModel.Shown.Text::class.java)
            assertThat((latest!![0] as OngoingActivityChipModel.Shown.Text).text)
                .isEqualTo("Arrived")
        }

    @Test
    fun chips_noTime_isIconOnly() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

            val promotedContentBuilder =
                PromotedNotificationContentModel.Builder("notif").apply { this.time = null }
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = mock<StatusBarIconView>(),
                        promotedContent = promotedContentBuilder.build(),
                    )
                )
            )

            assertThat(latest).hasSize(1)
            assertThat(latest!![0])
                .isInstanceOf(OngoingActivityChipModel.Shown.IconOnly::class.java)
        }

    @Test
    fun chips_basicTime_isShortTimeDelta() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

            val promotedContentBuilder =
                PromotedNotificationContentModel.Builder("notif").apply {
                    this.time =
                        PromotedNotificationContentModel.When(
                            time = 6543L,
                            mode = PromotedNotificationContentModel.When.Mode.BasicTime,
                        )
                }
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = mock<StatusBarIconView>(),
                        promotedContent = promotedContentBuilder.build(),
                    )
                )
            )

            assertThat(latest).hasSize(1)
            assertThat(latest!![0])
                .isInstanceOf(OngoingActivityChipModel.Shown.ShortTimeDelta::class.java)
        }

    @Test
    fun chips_countUpTime_isTimer() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

            val promotedContentBuilder =
                PromotedNotificationContentModel.Builder("notif").apply {
                    this.time =
                        PromotedNotificationContentModel.When(
                            time = 6543L,
                            mode = PromotedNotificationContentModel.When.Mode.CountUp,
                        )
                }
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = mock<StatusBarIconView>(),
                        promotedContent = promotedContentBuilder.build(),
                    )
                )
            )

            assertThat(latest).hasSize(1)
            assertThat(latest!![0]).isInstanceOf(OngoingActivityChipModel.Shown.Timer::class.java)
        }

    @Test
    fun chips_countDownTime_isTimer() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

            val promotedContentBuilder =
                PromotedNotificationContentModel.Builder("notif").apply {
                    this.time =
                        PromotedNotificationContentModel.When(
                            time = 6543L,
                            mode = PromotedNotificationContentModel.When.Mode.CountDown,
                        )
                }
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = mock<StatusBarIconView>(),
                        promotedContent = promotedContentBuilder.build(),
                    )
                )
            )

            assertThat(latest).hasSize(1)
            assertThat(latest!![0]).isInstanceOf(OngoingActivityChipModel.Shown.Timer::class.java)
        }

    @Test
    fun chips_noHeadsUp_showsTime() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

            val promotedContentBuilder =
                PromotedNotificationContentModel.Builder("notif").apply {
                    this.time =
                        PromotedNotificationContentModel.When(
                            time = 6543L,
                            mode = PromotedNotificationContentModel.When.Mode.BasicTime,
                        )
                }
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = mock<StatusBarIconView>(),
                        promotedContent = promotedContentBuilder.build(),
                    )
                )
            )

            // WHEN there's no HUN
            kosmos.headsUpNotificationRepository.setNotifications(emptyList())

            // THEN the chip shows the time
            assertThat(latest!![0])
                .isInstanceOf(OngoingActivityChipModel.Shown.ShortTimeDelta::class.java)
        }

    @Test
    fun chips_hasHeadsUpByUser_onlyShowsIcon() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)

            val promotedContentBuilder =
                PromotedNotificationContentModel.Builder("notif").apply {
                    this.time =
                        PromotedNotificationContentModel.When(
                            time = 6543L,
                            mode = PromotedNotificationContentModel.When.Mode.BasicTime,
                        )
                }
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = mock<StatusBarIconView>(),
                        promotedContent = promotedContentBuilder.build(),
                    )
                )
            )

            // WHEN there's a HUN pinned by a user
            kosmos.headsUpNotificationRepository.setNotifications(
                UnconfinedFakeHeadsUpRowRepository(
                    key = "notif",
                    pinnedStatus = MutableStateFlow(PinnedStatus.PinnedByUser),
                )
            )

            assertThat(latest!![0])
                .isInstanceOf(OngoingActivityChipModel.Shown.IconOnly::class.java)
        }

    @Test
    fun chips_clickingChipNotifiesInteractor() =
        kosmos.runTest {
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
                        promotedContent =
                            PromotedNotificationContentModel.Builder("clickTest").build(),
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
    }

    companion object {
        fun assertIsNotifChip(latest: OngoingActivityChipModel?, expectedIcon: StatusBarIconView) {
            assertThat((latest as OngoingActivityChipModel.Shown).icon)
                .isEqualTo(OngoingActivityChipModel.ChipIcon.StatusBarView(expectedIcon))
        }

        fun assertIsNotifKey(latest: OngoingActivityChipModel?, expectedKey: String) {
            assertThat((latest as OngoingActivityChipModel.Shown).icon)
                .isEqualTo(OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon(expectedKey))
        }
    }
}
