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

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.SysUITestComponent
import com.android.SysUITestModule
import com.android.TestMocksModule
import com.android.collectLastValue
import com.android.runTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.data.repository.FakeDeviceEntryRepository
import com.android.systemui.statusbar.data.repository.NotificationListenerSettingsRepository
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.FakeNotificationsKeyguardViewStateRepository
import com.android.systemui.statusbar.notification.shared.activeNotificationModel
import com.android.systemui.statusbar.notification.shared.byIsAmbient
import com.android.systemui.statusbar.notification.shared.byIsLastMessageFromReply
import com.android.systemui.statusbar.notification.shared.byIsPulsing
import com.android.systemui.statusbar.notification.shared.byIsRowDismissed
import com.android.systemui.statusbar.notification.shared.byIsSilent
import com.android.systemui.statusbar.notification.shared.byIsSuppressedFromStatusBar
import com.android.systemui.statusbar.notification.shared.byKey
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.wm.shell.bubbles.Bubbles
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class NotificationIconsInteractorTest : SysuiTestCase() {

    private val bubbles: Bubbles = mock()

    @Component(modules = [SysUITestModule::class])
    @SysUISingleton
    interface TestComponent : SysUITestComponent<NotificationIconsInteractor> {

        val activeNotificationListRepository: ActiveNotificationListRepository
        val keyguardViewStateRepository: FakeNotificationsKeyguardViewStateRepository

        @Component.Factory
        interface Factory {
            fun create(@BindsInstance test: SysuiTestCase, mocks: TestMocksModule): TestComponent
        }
    }

    val testComponent: TestComponent =
        DaggerNotificationIconsInteractorTest_TestComponent.factory()
            .create(test = this, mocks = TestMocksModule(bubbles = Optional.of(bubbles)))

    @Before
    fun setup() {
        testComponent.apply {
            activeNotificationListRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply { testIcons.forEach(::addIndividualNotif) }
                    .build()
        }
    }

    @Test
    fun filteredEntrySet() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet())
            assertThat(filteredSet).containsExactlyElementsIn(testIcons)
        }

    @Test
    fun filteredEntrySet_noExpandedBubbles() =
        testComponent.runTest {
            whenever(bubbles.isBubbleExpanded(eq("notif1"))).thenReturn(true)
            val filteredSet by collectLastValue(underTest.filteredNotifSet())
            assertThat(filteredSet).comparingElementsUsing(byKey).doesNotContain("notif1")
        }

    @Test
    fun filteredEntrySet_noAmbient() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showAmbient = false))
            assertThat(filteredSet).comparingElementsUsing(byIsAmbient).doesNotContain(true)
            assertThat(filteredSet)
                .comparingElementsUsing(byIsSuppressedFromStatusBar)
                .doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noLowPriority() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showLowPriority = false))
            assertThat(filteredSet).comparingElementsUsing(byIsSilent).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noDismissed() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showDismissed = false))
            assertThat(filteredSet).comparingElementsUsing(byIsRowDismissed).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noRepliedMessages() =
        testComponent.runTest {
            val filteredSet by
                collectLastValue(underTest.filteredNotifSet(showRepliedMessages = false))
            assertThat(filteredSet)
                .comparingElementsUsing(byIsLastMessageFromReply)
                .doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noPulsing_notifsNotFullyHidden() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showPulsing = false))
            keyguardViewStateRepository.setNotificationsFullyHidden(false)
            assertThat(filteredSet).comparingElementsUsing(byIsPulsing).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noPulsing_notifsFullyHidden() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showPulsing = false))
            keyguardViewStateRepository.setNotificationsFullyHidden(true)
            assertThat(filteredSet).comparingElementsUsing(byIsPulsing).contains(true)
        }
}

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AlwaysOnDisplayNotificationIconsInteractorTest : SysuiTestCase() {

    private val bubbles: Bubbles = mock()

    @Component(modules = [SysUITestModule::class])
    @SysUISingleton
    interface TestComponent : SysUITestComponent<AlwaysOnDisplayNotificationIconsInteractor> {

        val activeNotificationListRepository: ActiveNotificationListRepository
        val deviceEntryRepository: FakeDeviceEntryRepository
        val keyguardViewStateRepository: FakeNotificationsKeyguardViewStateRepository

        @Component.Factory
        interface Factory {
            fun create(@BindsInstance test: SysuiTestCase, mocks: TestMocksModule): TestComponent
        }
    }

    private val testComponent: TestComponent =
        DaggerAlwaysOnDisplayNotificationIconsInteractorTest_TestComponent.factory()
            .create(test = this, mocks = TestMocksModule(bubbles = Optional.of(bubbles)))

    @Before
    fun setup() {
        testComponent.apply {
            activeNotificationListRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply { testIcons.forEach(::addIndividualNotif) }
                    .build()
        }
    }

    @Test
    fun filteredEntrySet_noExpandedBubbles() =
        testComponent.runTest {
            whenever(bubbles.isBubbleExpanded(eq("notif1"))).thenReturn(true)
            val filteredSet by collectLastValue(underTest.aodNotifs)
            assertThat(filteredSet).comparingElementsUsing(byKey).doesNotContain("notif1")
        }

    @Test
    fun filteredEntrySet_noAmbient() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            assertThat(filteredSet).comparingElementsUsing(byIsAmbient).doesNotContain(true)
            assertThat(filteredSet)
                .comparingElementsUsing(byIsSuppressedFromStatusBar)
                .doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noDismissed() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            assertThat(filteredSet).comparingElementsUsing(byIsRowDismissed).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noRepliedMessages() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            assertThat(filteredSet)
                .comparingElementsUsing(byIsLastMessageFromReply)
                .doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_showPulsing_notifsNotFullyHidden_bypassDisabled() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            deviceEntryRepository.setBypassEnabled(false)
            keyguardViewStateRepository.setNotificationsFullyHidden(false)
            assertThat(filteredSet).comparingElementsUsing(byIsPulsing).contains(true)
        }

    @Test
    fun filteredEntrySet_showPulsing_notifsFullyHidden_bypassDisabled() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            deviceEntryRepository.setBypassEnabled(false)
            keyguardViewStateRepository.setNotificationsFullyHidden(true)
            assertThat(filteredSet).comparingElementsUsing(byIsPulsing).contains(true)
        }

    @Test
    fun filteredEntrySet_noPulsing_notifsNotFullyHidden_bypassEnabled() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            deviceEntryRepository.setBypassEnabled(true)
            keyguardViewStateRepository.setNotificationsFullyHidden(false)
            assertThat(filteredSet).comparingElementsUsing(byIsPulsing).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_showPulsing_notifsFullyHidden_bypassEnabled() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            deviceEntryRepository.setBypassEnabled(true)
            keyguardViewStateRepository.setNotificationsFullyHidden(true)
            assertThat(filteredSet).comparingElementsUsing(byIsPulsing).contains(true)
        }
}

@SmallTest
@RunWith(AndroidTestingRunner::class)
class StatusBarNotificationIconsInteractorTest : SysuiTestCase() {

    private val bubbles: Bubbles = mock()

    @Component(modules = [SysUITestModule::class])
    @SysUISingleton
    interface TestComponent : SysUITestComponent<StatusBarNotificationIconsInteractor> {

        val activeNotificationListRepository: ActiveNotificationListRepository
        val keyguardViewStateRepository: FakeNotificationsKeyguardViewStateRepository
        val notificationListenerSettingsRepository: NotificationListenerSettingsRepository

        @Component.Factory
        interface Factory {
            fun create(@BindsInstance test: SysuiTestCase, mocks: TestMocksModule): TestComponent
        }
    }

    val testComponent: TestComponent =
        DaggerStatusBarNotificationIconsInteractorTest_TestComponent.factory()
            .create(test = this, mocks = TestMocksModule(bubbles = Optional.of(bubbles)))

    @Before
    fun setup() {
        testComponent.apply {
            activeNotificationListRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply { testIcons.forEach(::addIndividualNotif) }
                    .build()
        }
    }

    @Test
    fun filteredEntrySet_noExpandedBubbles() =
        testComponent.runTest {
            whenever(bubbles.isBubbleExpanded(eq("notif1"))).thenReturn(true)
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            assertThat(filteredSet).comparingElementsUsing(byKey).doesNotContain("notif1")
        }

    @Test
    fun filteredEntrySet_noAmbient() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            assertThat(filteredSet).comparingElementsUsing(byIsAmbient).doesNotContain(true)
            assertThat(filteredSet)
                .comparingElementsUsing(byIsSuppressedFromStatusBar)
                .doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noLowPriority_whenDontShowSilentIcons() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            notificationListenerSettingsRepository.showSilentStatusIcons.value = false
            assertThat(filteredSet).comparingElementsUsing(byIsSilent).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_showLowPriority_whenShowSilentIcons() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            notificationListenerSettingsRepository.showSilentStatusIcons.value = true
            assertThat(filteredSet).comparingElementsUsing(byIsSilent).contains(true)
        }

    @Test
    fun filteredEntrySet_noDismissed() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            assertThat(filteredSet).comparingElementsUsing(byIsRowDismissed).doesNotContain(true)
        }

    @Test
    fun filteredEntrySet_noRepliedMessages() =
        testComponent.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            assertThat(filteredSet)
                .comparingElementsUsing(byIsLastMessageFromReply)
                .doesNotContain(true)
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
