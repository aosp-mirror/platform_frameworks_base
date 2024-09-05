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

package com.android.systemui.statusbar.notification.icon.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.data.repository.notificationListenerSettingsRepository
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.notificationsKeyguardViewStateRepository
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.headsUpNotificationIconInteractor
import com.android.systemui.statusbar.notification.shared.byIsAmbient
import com.android.systemui.statusbar.notification.shared.byIsLastMessageFromReply
import com.android.systemui.statusbar.notification.shared.byIsPulsing
import com.android.systemui.statusbar.notification.shared.byIsRowDismissed
import com.android.systemui.statusbar.notification.shared.byIsSilent
import com.android.systemui.statusbar.notification.shared.byIsSuppressedFromStatusBar
import com.android.systemui.statusbar.notification.shared.byKey
import com.android.systemui.statusbar.notification.stack.domain.interactor.notificationsKeyguardInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.wm.shell.bubbles.bubbles
import com.android.wm.shell.bubbles.bubblesOptional
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationIconsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository
    private val notificationsKeyguardInteractor = kosmos.notificationsKeyguardInteractor

    private val underTest =
        NotificationIconsInteractor(
            kosmos.activeNotificationsInteractor,
            kosmos.bubblesOptional,
            kosmos.headsUpNotificationIconInteractor,
            kosmos.notificationsKeyguardViewStateRepository
        )

    @Before
    fun setup() {
        testScope.apply {
            activeNotificationListRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply { testIcons.forEach(::addIndividualNotif) }
                    .build()
        }
    }

    @Test
    fun filteredEntrySet() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet())
            assertThat(filteredSet).containsExactlyElementsIn(testIcons)
        }

    @Test
    fun filteredEntrySet_noExpandedBubbles() =
        testScope.runTest {
            whenever(kosmos.bubbles.isBubbleExpanded(eq("notif1"))).thenReturn(true)
            val filteredSet by collectLastValue(underTest.filteredNotifSet())
            assertThat(filteredSet).comparingElementsUsing(byKey).doesNotContain("notif1")
        }

    @Test
    fun filteredEntrySet_noAmbient() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showAmbient = false))
            assertThat(filteredSet).comparingElementsUsing(byIsAmbient).doesNotContain(true)
            assertThat(filteredSet)
                .comparingElementsUsing(byIsSuppressedFromStatusBar)
                .doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noLowPriority() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showLowPriority = false))
            assertThat(filteredSet).comparingElementsUsing(byIsSilent).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noDismissed() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showDismissed = false))
            assertThat(filteredSet).comparingElementsUsing(byIsRowDismissed).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noRepliedMessages() =
        testScope.runTest {
            val filteredSet by
                collectLastValue(underTest.filteredNotifSet(showRepliedMessages = false))
            assertThat(filteredSet)
                .comparingElementsUsing(byIsLastMessageFromReply)
                .doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noPulsing_notifsNotFullyHidden() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showPulsing = false))
            notificationsKeyguardInteractor.setNotificationsFullyHidden(false)
            assertThat(filteredSet).comparingElementsUsing(byIsPulsing).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noPulsing_notifsFullyHidden() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showPulsing = false))
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            assertThat(filteredSet).comparingElementsUsing(byIsPulsing).contains(true)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AlwaysOnDisplayNotificationIconsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val underTest =
        AlwaysOnDisplayNotificationIconsInteractor(
            kosmos.testDispatcher,
            kosmos.deviceEntryInteractor,
            kosmos.notificationIconsInteractor,
        )

    @Before
    fun setup() {
        kosmos.activeNotificationListRepository.activeNotifications.value =
            ActiveNotificationsStore.Builder()
                .apply { testIcons.forEach(::addIndividualNotif) }
                .build()
    }

    @Test
    fun filteredEntrySet_noExpandedBubbles() =
        testScope.runTest {
            whenever(kosmos.bubbles.isBubbleExpanded(eq("notif1"))).thenReturn(true)
            val filteredSet by collectLastValue(underTest.aodNotifs)
            assertThat(filteredSet).comparingElementsUsing(byKey).doesNotContain("notif1")
        }

    @Test
    fun filteredEntrySet_noAmbient() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            assertThat(filteredSet).comparingElementsUsing(byIsAmbient).doesNotContain(true)
            assertThat(filteredSet)
                .comparingElementsUsing(byIsSuppressedFromStatusBar)
                .doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noDismissed() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            assertThat(filteredSet).comparingElementsUsing(byIsRowDismissed).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noRepliedMessages() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            assertThat(filteredSet)
                .comparingElementsUsing(byIsLastMessageFromReply)
                .doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_showPulsing_notifsNotFullyHidden_bypassDisabled() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            kosmos.fakeDeviceEntryRepository.setBypassEnabled(false)
            kosmos.notificationsKeyguardInteractor.setNotificationsFullyHidden(false)
            assertThat(filteredSet).comparingElementsUsing(byIsPulsing).contains(true)
        }

    @Test
    fun filteredEntrySet_showPulsing_notifsFullyHidden_bypassDisabled() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            kosmos.fakeDeviceEntryRepository.setBypassEnabled(false)
            kosmos.notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            assertThat(filteredSet).comparingElementsUsing(byIsPulsing).contains(true)
        }

    @Test
    fun filteredEntrySet_noPulsing_notifsNotFullyHidden_bypassEnabled() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            kosmos.fakeDeviceEntryRepository.setBypassEnabled(true)
            kosmos.notificationsKeyguardInteractor.setNotificationsFullyHidden(false)
            assertThat(filteredSet).comparingElementsUsing(byIsPulsing).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_showPulsing_notifsFullyHidden_bypassEnabled() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            kosmos.fakeDeviceEntryRepository.setBypassEnabled(true)
            kosmos.notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            assertThat(filteredSet).comparingElementsUsing(byIsPulsing).contains(true)
        }
}

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarNotificationIconsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest =
        StatusBarNotificationIconsInteractor(
            kosmos.testDispatcher,
            kosmos.notificationIconsInteractor,
            kosmos.notificationListenerSettingsRepository,
        )

    @Before
    fun setup() {
        testScope.apply {
            kosmos.activeNotificationListRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply { testIcons.forEach(::addIndividualNotif) }
                    .build()
        }
    }

    @Test
    fun filteredEntrySet_noExpandedBubbles() =
        testScope.runTest {
            whenever(kosmos.bubbles.isBubbleExpanded(eq("notif1"))).thenReturn(true)
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            assertThat(filteredSet).comparingElementsUsing(byKey).doesNotContain("notif1")
        }

    @Test
    fun filteredEntrySet_noAmbient() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            assertThat(filteredSet).comparingElementsUsing(byIsAmbient).doesNotContain(true)
            assertThat(filteredSet)
                .comparingElementsUsing(byIsSuppressedFromStatusBar)
                .doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noLowPriority_whenDontShowSilentIcons() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            kosmos.notificationListenerSettingsRepository.showSilentStatusIcons.value = false
            assertThat(filteredSet).comparingElementsUsing(byIsSilent).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_showLowPriority_whenShowSilentIcons() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            kosmos.notificationListenerSettingsRepository.showSilentStatusIcons.value = true
            assertThat(filteredSet).comparingElementsUsing(byIsSilent).contains(true)
        }

    @Test
    fun filteredEntrySet_noDismissed() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            assertThat(filteredSet).comparingElementsUsing(byIsRowDismissed).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noRepliedMessages() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            assertThat(filteredSet)
                .comparingElementsUsing(byIsLastMessageFromReply)
                .doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_includesIsolatedIcon() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            kosmos.headsUpNotificationIconInteractor.setIsolatedIconNotificationKey("notif5")
            assertThat(filteredSet).comparingElementsUsing(byKey).contains("notif5")
        }
}

private val testIcons =
    listOf(
        activeNotificationModel(
            key = "notif1",
        ),
        activeNotificationModel(
            key = "notif2",
            isAmbient = true,
        ),
        activeNotificationModel(
            key = "notif3",
            isRowDismissed = true,
        ),
        activeNotificationModel(
            key = "notif4",
            isSilent = true,
        ),
        activeNotificationModel(
            key = "notif5",
            isLastMessageFromReply = true,
        ),
        activeNotificationModel(
            key = "notif6",
            isSuppressedFromStatusBar = true,
        ),
        activeNotificationModel(
            key = "notif7",
            isPulsing = true,
        ),
    )
