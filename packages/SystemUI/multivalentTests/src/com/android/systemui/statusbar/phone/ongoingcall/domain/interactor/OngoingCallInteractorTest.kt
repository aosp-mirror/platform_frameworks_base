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

package com.android.systemui.statusbar.phone.ongoingcall.domain.interactor

import android.app.PendingIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.data.repository.activityManagerRepository
import com.android.systemui.activity.data.repository.fake
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.data.repository.fakeStatusBarModeRepository
import com.android.systemui.statusbar.gesture.swipeStatusBarAwayGestureHandler
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.shared.CallType
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.statusbar.window.fakeStatusBarWindowControllerStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class OngoingCallInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos().useUnconfinedTestDispatcher()
    private val repository = kosmos.activeNotificationListRepository
    private val underTest = kosmos.ongoingCallInteractor

    @Before
    fun setUp() {
        underTest.start()
    }

    @Test
    fun noNotification_emitsNoCall() = runTest {
        val state by collectLastValue(underTest.ongoingCallState)
        assertThat(state).isInstanceOf(OngoingCallModel.NoCall::class.java)
    }

    @Test
    fun ongoingCallNotification_setsAllFields() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.ongoingCallState)

            // Set up notification with icon view and intent
            val testIconView: StatusBarIconView = mock()
            val testIntent: PendingIntent = mock()
            val testPromotedContent =
                PromotedNotificationContentModel.Builder("promotedCall").build()
            repository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "promotedCall",
                                whenTime = 1000L,
                                callType = CallType.Ongoing,
                                statusBarChipIcon = testIconView,
                                contentIntent = testIntent,
                                promotedContent = testPromotedContent,
                            )
                        )
                    }
                    .build()

            // Verify model is InCall and has the correct icon, intent, and promoted content.
            assertThat(latest).isInstanceOf(OngoingCallModel.InCall::class.java)
            val model = latest as OngoingCallModel.InCall
            assertThat(model.notificationIconView).isSameInstanceAs(testIconView)
            assertThat(model.intent).isSameInstanceAs(testIntent)
            assertThat(model.promotedContent).isSameInstanceAs(testPromotedContent)
        }

    @Test
    fun ongoingCallNotification_emitsInCall() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.ongoingCallState)

            repository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                whenTime = 1000L,
                                callType = CallType.Ongoing,
                            )
                        )
                    }
                    .build()

            assertThat(latest).isInstanceOf(OngoingCallModel.InCall::class.java)
        }

    @Test
    fun notificationRemoved_emitsNoCall() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.ongoingCallState)

            repository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                whenTime = 1000L,
                                callType = CallType.Ongoing,
                            )
                        )
                    }
                    .build()

            repository.activeNotifications.value = ActiveNotificationsStore()
            assertThat(latest).isInstanceOf(OngoingCallModel.NoCall::class.java)
        }

    @Test
    fun ongoingCallNotification_appVisibleInitially_emitsInCallWithVisibleApp() =
        kosmos.runTest {
            kosmos.activityManagerRepository.fake.startingIsAppVisibleValue = true
            val latest by collectLastValue(underTest.ongoingCallState)

            repository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                whenTime = 1000L,
                                callType = CallType.Ongoing,
                                uid = UID,
                            )
                        )
                    }
                    .build()

            assertThat(latest).isInstanceOf(OngoingCallModel.InCallWithVisibleApp::class.java)
        }

    @Test
    fun ongoingCallNotification_appNotVisibleInitially_emitsInCall() =
        kosmos.runTest {
            kosmos.activityManagerRepository.fake.startingIsAppVisibleValue = false
            val latest by collectLastValue(underTest.ongoingCallState)

            repository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                whenTime = 1000L,
                                callType = CallType.Ongoing,
                                uid = UID,
                            )
                        )
                    }
                    .build()

            assertThat(latest).isInstanceOf(OngoingCallModel.InCall::class.java)
        }

    @Test
    fun ongoingCallNotification_visibilityChanges_updatesState() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.ongoingCallState)

            // Start with notification and app not visible
            kosmos.activityManagerRepository.fake.startingIsAppVisibleValue = false
            repository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                whenTime = 1000L,
                                callType = CallType.Ongoing,
                                uid = UID,
                            )
                        )
                    }
                    .build()
            assertThat(latest).isInstanceOf(OngoingCallModel.InCall::class.java)

            // App becomes visible
            kosmos.activityManagerRepository.fake.setIsAppVisible(UID, true)
            assertThat(latest).isInstanceOf(OngoingCallModel.InCallWithVisibleApp::class.java)

            // App becomes invisible again
            kosmos.activityManagerRepository.fake.setIsAppVisible(UID, false)
            assertThat(latest).isInstanceOf(OngoingCallModel.InCall::class.java)
        }

    @Test
    fun ongoingCallNotification_setsRequiresStatusBarVisibleTrue() =
        kosmos.runTest {
            val isStatusBarRequired by collectLastValue(underTest.isStatusBarRequiredForOngoingCall)
            val requiresStatusBarVisibleInRepository by
                collectLastValue(
                    kosmos.fakeStatusBarModeRepository.defaultDisplay
                        .ongoingProcessRequiresStatusBarVisible
                )
            val requiresStatusBarVisibleInWindowController by
                collectLastValue(
                    kosmos.fakeStatusBarWindowControllerStore.defaultDisplay
                        .ongoingProcessRequiresStatusBarVisible
                )
            postOngoingCallNotification()

            assertThat(isStatusBarRequired).isTrue()
            assertThat(requiresStatusBarVisibleInRepository).isTrue()
            assertThat(requiresStatusBarVisibleInWindowController).isTrue()
        }

    @Test
    fun notificationRemoved_setsRequiresStatusBarVisibleFalse() =
        kosmos.runTest {
            val isStatusBarRequired by collectLastValue(underTest.isStatusBarRequiredForOngoingCall)
            val requiresStatusBarVisibleInRepository by
                collectLastValue(
                    kosmos.fakeStatusBarModeRepository.defaultDisplay
                        .ongoingProcessRequiresStatusBarVisible
                )
            val requiresStatusBarVisibleInWindowController by
                collectLastValue(
                    kosmos.fakeStatusBarWindowControllerStore.defaultDisplay
                        .ongoingProcessRequiresStatusBarVisible
                )

            postOngoingCallNotification()

            repository.activeNotifications.value = ActiveNotificationsStore()

            assertThat(isStatusBarRequired).isFalse()
            assertThat(requiresStatusBarVisibleInRepository).isFalse()
            assertThat(requiresStatusBarVisibleInWindowController).isFalse()
        }

    @Test
    fun ongoingCallNotification_appBecomesVisible_setsRequiresStatusBarVisibleFalse() =
        kosmos.runTest {
            val ongoingCallState by collectLastValue(underTest.ongoingCallState)

            val requiresStatusBarVisibleInRepository by
                collectLastValue(
                    kosmos.fakeStatusBarModeRepository.defaultDisplay
                        .ongoingProcessRequiresStatusBarVisible
                )
            val requiresStatusBarVisibleInWindowController by
                collectLastValue(
                    kosmos.fakeStatusBarWindowControllerStore.defaultDisplay
                        .ongoingProcessRequiresStatusBarVisible
                )

            kosmos.activityManagerRepository.fake.startingIsAppVisibleValue = false

            postOngoingCallNotification()

            assertThat(ongoingCallState).isInstanceOf(OngoingCallModel.InCall::class.java)
            assertThat(requiresStatusBarVisibleInRepository).isTrue()
            assertThat(requiresStatusBarVisibleInWindowController).isTrue()

            kosmos.activityManagerRepository.fake.setIsAppVisible(UID, true)

            assertThat(ongoingCallState)
                .isInstanceOf(OngoingCallModel.InCallWithVisibleApp::class.java)
            assertThat(requiresStatusBarVisibleInRepository).isFalse()
            assertThat(requiresStatusBarVisibleInWindowController).isFalse()
        }

    @Test
    fun gestureHandler_inCall_notFullscreen_doesNotListen() =
        kosmos.runTest {
            val ongoingCallState by collectLastValue(underTest.ongoingCallState)

            clearInvocations(kosmos.swipeStatusBarAwayGestureHandler)
            // Set up notification but not in fullscreen
            kosmos.fakeStatusBarModeRepository.defaultDisplay.isInFullscreenMode.value = false
            postOngoingCallNotification()

            assertThat(ongoingCallState).isInstanceOf(OngoingCallModel.InCall::class.java)
            verify(kosmos.swipeStatusBarAwayGestureHandler, never())
                .addOnGestureDetectedCallback(any(), any())
        }

    @Test
    fun gestureHandler_inCall_fullscreen_addsListener() =
        kosmos.runTest {
            val isGestureListeningEnabled by collectLastValue(underTest.isGestureListeningEnabled)

            // Set up notification and fullscreen mode
            kosmos.fakeStatusBarModeRepository.defaultDisplay.isInFullscreenMode.value = true
            postOngoingCallNotification()

            assertThat(isGestureListeningEnabled).isTrue()
            verify(kosmos.swipeStatusBarAwayGestureHandler)
                .addOnGestureDetectedCallback(any(), any())
        }

    @Test
    fun gestureHandler_inCall_fullscreen_chipSwiped_removesListener() =
        kosmos.runTest {
            val swipeAwayState by collectLastValue(underTest.isChipSwipedAway)

            // Set up notification and fullscreen mode
            kosmos.fakeStatusBarModeRepository.defaultDisplay.isInFullscreenMode.value = true
            postOngoingCallNotification()

            clearInvocations(kosmos.swipeStatusBarAwayGestureHandler)

            underTest.onStatusBarSwiped()

            assertThat(swipeAwayState).isTrue()
            verify(kosmos.swipeStatusBarAwayGestureHandler).removeOnGestureDetectedCallback(any())
        }

    @Test
    fun chipSwipedAway_setsRequiresStatusBarVisibleFalse() =
        kosmos.runTest {
            val isStatusBarRequiredForOngoingCall by
                collectLastValue(underTest.isStatusBarRequiredForOngoingCall)
            val requiresStatusBarVisibleInRepository by
                collectLastValue(
                    kosmos.fakeStatusBarModeRepository.defaultDisplay
                        .ongoingProcessRequiresStatusBarVisible
                )
            val requiresStatusBarVisibleInWindowController by
                collectLastValue(
                    kosmos.fakeStatusBarWindowControllerStore.defaultDisplay
                        .ongoingProcessRequiresStatusBarVisible
                )

            // Start with an ongoing call (which should set status bar required)
            postOngoingCallNotification()

            assertThat(isStatusBarRequiredForOngoingCall).isTrue()
            assertThat(requiresStatusBarVisibleInRepository).isTrue()
            assertThat(requiresStatusBarVisibleInWindowController).isTrue()

            // Swipe away the chip
            underTest.onStatusBarSwiped()

            // Verify status bar is no longer required
            assertThat(requiresStatusBarVisibleInRepository).isFalse()
            assertThat(requiresStatusBarVisibleInWindowController).isFalse()
        }

    private fun postOngoingCallNotification() {
        repository.activeNotifications.value =
            ActiveNotificationsStore.Builder()
                .apply {
                    addIndividualNotif(
                        activeNotificationModel(
                            key = "notif1",
                            whenTime = 1000L,
                            callType = CallType.Ongoing,
                            uid = UID,
                        )
                    )
                }
                .build()
    }

    companion object {
        private const val UID = 885
    }
}
