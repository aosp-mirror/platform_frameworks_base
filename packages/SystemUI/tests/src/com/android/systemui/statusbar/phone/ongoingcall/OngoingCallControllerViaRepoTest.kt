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

package com.android.systemui.statusbar.phone.ongoingcall

import android.app.ActivityManager
import android.app.IActivityManager
import android.app.IUidObserver
import android.app.PendingIntent
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON
import com.android.systemui.Flags.FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS
import com.android.systemui.Flags.FLAG_STATUS_BAR_USE_REPOS_FOR_CALL_CHIP
import com.android.systemui.SysuiTestCase
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.dump.DumpManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.plugins.activityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.data.repository.fakeStatusBarModeRepository
import com.android.systemui.statusbar.gesture.SwipeStatusBarAwayGestureHandler
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.CallType
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.ongoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
@EnableFlags(FLAG_STATUS_BAR_USE_REPOS_FOR_CALL_CHIP)
class OngoingCallControllerViaRepoTest : SysuiTestCase() {
    private val kosmos = Kosmos()

    private val clock = kosmos.fakeSystemClock
    private val mainExecutor = kosmos.fakeExecutor
    private val testScope = kosmos.testScope
    private val statusBarModeRepository = kosmos.fakeStatusBarModeRepository
    private val ongoingCallRepository = kosmos.ongoingCallRepository
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository

    private lateinit var controller: OngoingCallController

    private val mockSwipeStatusBarAwayGestureHandler = mock<SwipeStatusBarAwayGestureHandler>()
    private val mockOngoingCallListener = mock<OngoingCallListener>()
    private val mockActivityStarter = kosmos.activityStarter
    private val mockIActivityManager = mock<IActivityManager>()
    private val mockStatusBarWindowController = mock<StatusBarWindowController>()

    private lateinit var chipView: View

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        TestableLooper.get(this).runWithLooper {
            chipView = LayoutInflater.from(mContext).inflate(R.layout.ongoing_activity_chip, null)
        }

        controller =
            OngoingCallController(
                testScope.backgroundScope,
                context,
                ongoingCallRepository,
                mock<CommonNotifCollection>(),
                kosmos.activeNotificationsInteractor,
                clock,
                mockActivityStarter,
                mainExecutor,
                mockIActivityManager,
                DumpManager(),
                mockStatusBarWindowController,
                mockSwipeStatusBarAwayGestureHandler,
                statusBarModeRepository,
                logcatLogBuffer("OngoingCallControllerViaRepoTest"),
            )
        controller.start()
        controller.addCallback(mockOngoingCallListener)
        controller.setChipView(chipView)

        // Let the controller get the starting value from activeNotificationsInteractor
        testScope.runCurrent()
        reset(mockOngoingCallListener)

        whenever(
                mockIActivityManager.getUidProcessState(
                    eq(CALL_UID),
                    any(),
                )
            )
            .thenReturn(PROC_STATE_INVISIBLE)
    }

    @After
    fun tearDown() {
        controller.tearDownChipView()
    }

    @Test
    fun interactorHasOngoingCallNotif_listenerAndRepoNotified() =
        testScope.runTest {
            setNotifOnRepo(
                activeNotificationModel(
                    key = "ongoingNotif",
                    callType = CallType.Ongoing,
                    uid = CALL_UID,
                    whenTime = 567,
                )
            )

            verify(mockOngoingCallListener).onOngoingCallStateChanged(any())
            val repoState = ongoingCallRepository.ongoingCallState.value
            assertThat(repoState).isInstanceOf(OngoingCallModel.InCall::class.java)
            assertThat((repoState as OngoingCallModel.InCall).startTimeMs).isEqualTo(567)
        }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON)
    fun interactorHasOngoingCallNotif_notifIconFlagOff_repoHasNoNotifIcon() =
        testScope.runTest {
            val icon = mock<StatusBarIconView>()
            setNotifOnRepo(
                activeNotificationModel(
                    key = "ongoingNotif",
                    callType = CallType.Ongoing,
                    uid = CALL_UID,
                    statusBarChipIcon = icon,
                    whenTime = 567,
                )
            )

            val repoState = ongoingCallRepository.ongoingCallState.value
            assertThat(repoState).isInstanceOf(OngoingCallModel.InCall::class.java)
            assertThat((repoState as OngoingCallModel.InCall).notificationIconView).isNull()
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON)
    fun interactorHasOngoingCallNotif_notifIconFlagOn_repoHasNotifIcon() =
        testScope.runTest {
            val icon = mock<StatusBarIconView>()

            setNotifOnRepo(
                activeNotificationModel(
                    key = "ongoingNotif",
                    callType = CallType.Ongoing,
                    uid = CALL_UID,
                    statusBarChipIcon = icon,
                    whenTime = 567,
                )
            )

            val repoState = ongoingCallRepository.ongoingCallState.value
            assertThat(repoState).isInstanceOf(OngoingCallModel.InCall::class.java)
            assertThat((repoState as OngoingCallModel.InCall).notificationIconView).isEqualTo(icon)
        }

    @Test
    fun notifRepoHasOngoingCallNotif_isOngoingCallNotif_windowControllerUpdated() {
        setCallNotifOnRepo()

        verify(mockStatusBarWindowController).setOngoingProcessRequiresStatusBarVisible(true)
    }

    @Test
    fun notifRepoHasNoCallNotif_listenerAndRepoNotNotified() {
        setNoNotifsOnRepo()

        verify(mockOngoingCallListener, never()).onOngoingCallStateChanged(any())
        assertThat(ongoingCallRepository.ongoingCallState.value)
            .isInstanceOf(OngoingCallModel.NoCall::class.java)
    }

    @Test
    fun notifRepoHasOngoingCallNotifThenScreeningNotif_listenerNotifiedTwice() {
        setNotifOnRepo(
            activeNotificationModel(
                key = "notif",
                callType = CallType.Ongoing,
            )
        )

        setNotifOnRepo(
            activeNotificationModel(
                key = "notif",
                callType = CallType.Screening,
            )
        )

        verify(mockOngoingCallListener, times(2)).onOngoingCallStateChanged(any())
    }

    @Test
    fun notifRepoHasOngoingCallNotifThenScreeningNotif_repoUpdated() {
        setNotifOnRepo(
            activeNotificationModel(
                key = "notif",
                callType = CallType.Ongoing,
            )
        )

        setNotifOnRepo(
            activeNotificationModel(
                key = "notif",
                callType = CallType.Screening,
            )
        )

        assertThat(ongoingCallRepository.ongoingCallState.value)
            .isInstanceOf(OngoingCallModel.NoCall::class.java)
    }

    /** Regression test for b/191472854. */
    @Test
    fun notifRepoHasCallNotif_notifHasNullContentIntent_noCrash() {
        setNotifOnRepo(
            activeNotificationModel(
                key = "notif",
                callType = CallType.Ongoing,
                contentIntent = null,
            )
        )
    }

    /** Regression test for b/192379214. */
    @Test
    @DisableFlags(android.app.Flags.FLAG_SORT_SECTION_BY_TIME, FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    fun notifRepoHasCallNotif_notificationWhenIsZero_timeHidden() {
        setNotifOnRepo(
            activeNotificationModel(
                key = "notif",
                callType = CallType.Ongoing,
                contentIntent = null,
                whenTime = 0,
            )
        )

        chipView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        assertThat(chipView.findViewById<View>(R.id.ongoing_activity_chip_time)?.measuredWidth)
            .isEqualTo(0)
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    fun notifRepoHasCallNotif_notificationWhenIsValid_timeShown() {
        setNotifOnRepo(
            activeNotificationModel(
                key = "notif",
                callType = CallType.Ongoing,
                whenTime = clock.currentTimeMillis(),
            )
        )

        chipView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        assertThat(chipView.findViewById<View>(R.id.ongoing_activity_chip_time)?.measuredWidth)
            .isGreaterThan(0)
    }

    /** Regression test for b/194731244. */
    @Test
    fun repoHasCallNotif_updatedManyTimes_uidObserverOnlyRegisteredOnce() {
        for (i in 0 until 4) {
            // Re-create the notification each time so that it's considered a different object and
            // will re-trigger the whole flow.
            setNotifOnRepo(
                activeNotificationModel(
                    key = "notif$i",
                    callType = CallType.Ongoing,
                    whenTime = 44,
                )
            )
        }

        verify(mockIActivityManager, times(1)).registerUidObserver(any(), any(), any(), any())
    }

    /** Regression test for b/216248574. */
    @Test
    fun repoHasCallNotif_getUidProcessStateThrowsException_noCrash() {
        whenever(
                mockIActivityManager.getUidProcessState(
                    eq(CALL_UID),
                    any(),
                )
            )
            .thenThrow(SecurityException())

        // No assert required, just check no crash
        setCallNotifOnRepo()
    }

    /** Regression test for b/216248574. */
    @Test
    fun repoHasCallNotif_registerUidObserverThrowsException_noCrash() {
        whenever(
                mockIActivityManager.registerUidObserver(
                    any(),
                    any(),
                    any(),
                    any(),
                )
            )
            .thenThrow(SecurityException())

        // No assert required, just check no crash
        setCallNotifOnRepo()
    }

    /** Regression test for b/216248574. */
    @Test
    fun repoHasCallNotif_packageNameProvidedToActivityManager() {
        setCallNotifOnRepo()

        val packageNameCaptor = argumentCaptor<String>()
        verify(mockIActivityManager)
            .registerUidObserver(any(), any(), any(), packageNameCaptor.capture())
        assertThat(packageNameCaptor.firstValue).isNotNull()
    }

    @Test
    fun repo_callNotifAddedThenRemoved_listenerNotified() {
        setCallNotifOnRepo()
        reset(mockOngoingCallListener)

        setNoNotifsOnRepo()

        verify(mockOngoingCallListener).onOngoingCallStateChanged(any())
    }

    @Test
    fun repo_callNotifAddedThenRemoved_repoUpdated() {
        setCallNotifOnRepo()

        setNoNotifsOnRepo()

        assertThat(ongoingCallRepository.ongoingCallState.value)
            .isInstanceOf(OngoingCallModel.NoCall::class.java)
    }

    @Test
    fun repo_callNotifAddedThenRemoved_windowControllerUpdated() {
        reset(mockStatusBarWindowController)

        setCallNotifOnRepo()

        setNoNotifsOnRepo()

        verify(mockStatusBarWindowController).setOngoingProcessRequiresStatusBarVisible(false)
    }

    @Test
    fun hasOngoingCall_noOngoingCallNotifSent_returnsFalse() {
        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_repoHasUnrelatedNotif_returnsFalse() {
        setNotifOnRepo(
            activeNotificationModel(
                key = "unrelated",
                callType = CallType.None,
                uid = CALL_UID,
            )
        )

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_repoHasScreeningCall_returnsFalse() {
        setNotifOnRepo(
            activeNotificationModel(
                key = "unrelated",
                callType = CallType.Screening,
                uid = CALL_UID,
            )
        )

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_repoHasCallNotifAndCallAppNotVisible_returnsTrue() {
        whenever(
                mockIActivityManager.getUidProcessState(
                    eq(CALL_UID),
                    any(),
                )
            )
            .thenReturn(PROC_STATE_INVISIBLE)

        setNotifOnRepo(
            activeNotificationModel(
                key = "notif",
                callType = CallType.Ongoing,
                uid = CALL_UID,
            )
        )

        assertThat(controller.hasOngoingCall()).isTrue()
    }

    @Test
    fun hasOngoingCall_repoHasCallNotifButCallAppVisible_returnsFalse() {
        whenever(mockIActivityManager.getUidProcessState(eq(CALL_UID), any()))
            .thenReturn(PROC_STATE_VISIBLE)

        setNotifOnRepo(
            activeNotificationModel(
                key = "notif",
                callType = CallType.Ongoing,
                uid = CALL_UID,
            )
        )

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_repoHasCallNotifButInvalidChipView_returnsFalse() {
        val invalidChipView = LinearLayout(context)
        controller.setChipView(invalidChipView)

        setNotifOnRepo(
            activeNotificationModel(
                key = "notif",
                callType = CallType.Ongoing,
                uid = CALL_UID,
            )
        )

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_repoHasCallNotifThenDoesNot_returnsFalse() {
        setCallNotifOnRepo()
        setNoNotifsOnRepo()

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_repoHasCallNotifThenScreeningNotif_returnsFalse() {
        setCallNotifOnRepo()
        setNotifOnRepo(
            activeNotificationModel(
                key = "screening",
                callType = CallType.Screening,
                uid = CALL_UID,
            )
        )

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    /**
     * This test fakes a theme change during an ongoing call.
     *
     * When a theme change happens,
     * [com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment] and its views get
     * re-created, so [OngoingCallController.setChipView] gets called with a new view. If there's an
     * active ongoing call when the theme changes, the new view needs to be updated with the call
     * information.
     */
    @Test
    fun setChipView_whenRepoHasOngoingCall_listenerNotifiedWithNewView() {
        // Start an ongoing call.
        setCallNotifOnRepo()
        reset(mockOngoingCallListener)

        lateinit var newChipView: View
        TestableLooper.get(this).runWithLooper {
            newChipView =
                LayoutInflater.from(mContext).inflate(R.layout.ongoing_activity_chip, null)
        }

        // Change the chip view associated with the controller.
        controller.setChipView(newChipView)

        verify(mockOngoingCallListener).onOngoingCallStateChanged(any())
    }

    @Test
    fun callProcessChangesToVisible_listenerNotified() {
        // Start the call while the process is invisible.
        whenever(mockIActivityManager.getUidProcessState(eq(CALL_UID), any()))
            .thenReturn(PROC_STATE_INVISIBLE)
        setCallNotifOnRepo()
        reset(mockOngoingCallListener)

        val captor = argumentCaptor<IUidObserver.Stub>()
        verify(mockIActivityManager).registerUidObserver(captor.capture(), any(), any(), any())
        val uidObserver = captor.firstValue

        // Update the process to visible.
        uidObserver.onUidStateChanged(CALL_UID, PROC_STATE_VISIBLE, 0, 0)
        mainExecutor.advanceClockToLast()
        mainExecutor.runAllReady()

        verify(mockOngoingCallListener).onOngoingCallStateChanged(any())
    }

    @Test
    fun callProcessChangesToInvisible_listenerNotified() {
        // Start the call while the process is visible.
        whenever(mockIActivityManager.getUidProcessState(eq(CALL_UID), any()))
            .thenReturn(PROC_STATE_VISIBLE)
        setCallNotifOnRepo()
        reset(mockOngoingCallListener)

        val captor = argumentCaptor<IUidObserver.Stub>()
        verify(mockIActivityManager).registerUidObserver(captor.capture(), any(), any(), any())
        val uidObserver = captor.firstValue

        // Update the process to invisible.
        uidObserver.onUidStateChanged(CALL_UID, PROC_STATE_INVISIBLE, 0, 0)
        mainExecutor.advanceClockToLast()
        mainExecutor.runAllReady()

        verify(mockOngoingCallListener).onOngoingCallStateChanged(any())
    }

    /** Regression test for b/212467440. */
    @Test
    @DisableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    fun chipClicked_activityStarterTriggeredWithUnmodifiedIntent() {
        val pendingIntent = mock<PendingIntent>()
        setNotifOnRepo(
            activeNotificationModel(
                key = "notif",
                uid = CALL_UID,
                contentIntent = pendingIntent,
                callType = CallType.Ongoing,
            )
        )

        chipView.performClick()

        // Ensure that the sysui didn't modify the notification's intent -- see b/212467440.
        verify(mockActivityStarter).postStartActivityDismissingKeyguard(eq(pendingIntent), any())
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    fun callNotificationAdded_chipIsClickable() {
        setCallNotifOnRepo()

        assertThat(chipView.hasOnClickListeners()).isTrue()
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    fun callNotificationAdded_newChipsEnabled_chipNotClickable() {
        setCallNotifOnRepo()

        assertThat(chipView.hasOnClickListeners()).isFalse()
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    fun fullscreenIsTrue_chipStillClickable() {
        setCallNotifOnRepo()
        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = true
        testScope.runCurrent()

        assertThat(chipView.hasOnClickListeners()).isTrue()
    }

    @Test
    fun callStartedInImmersiveMode_swipeGestureCallbackAdded() {
        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = true
        testScope.runCurrent()

        setCallNotifOnRepo()

        verify(mockSwipeStatusBarAwayGestureHandler).addOnGestureDetectedCallback(any(), any())
    }

    @Test
    fun callStartedNotInImmersiveMode_swipeGestureCallbackNotAdded() {
        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = false
        testScope.runCurrent()

        setCallNotifOnRepo()

        verify(mockSwipeStatusBarAwayGestureHandler, never())
            .addOnGestureDetectedCallback(any(), any())
    }

    @Test
    fun transitionToImmersiveMode_swipeGestureCallbackAdded() {
        setCallNotifOnRepo()

        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = true
        testScope.runCurrent()

        verify(mockSwipeStatusBarAwayGestureHandler).addOnGestureDetectedCallback(any(), any())
    }

    @Test
    fun transitionOutOfImmersiveMode_swipeGestureCallbackRemoved() {
        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = true
        testScope.runCurrent()

        setCallNotifOnRepo()
        reset(mockSwipeStatusBarAwayGestureHandler)

        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = false
        testScope.runCurrent()

        verify(mockSwipeStatusBarAwayGestureHandler).removeOnGestureDetectedCallback(any())
    }

    @Test
    fun callEndedWhileInImmersiveMode_swipeGestureCallbackRemoved() {
        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = true
        testScope.runCurrent()
        setCallNotifOnRepo()
        reset(mockSwipeStatusBarAwayGestureHandler)

        setNoNotifsOnRepo()

        verify(mockSwipeStatusBarAwayGestureHandler).removeOnGestureDetectedCallback(any())
    }

    private fun setCallNotifOnRepo() {
        setNotifOnRepo(
            activeNotificationModel(
                key = "ongoingNotif",
                callType = CallType.Ongoing,
                uid = CALL_UID,
                contentIntent = mock<PendingIntent>(),
            )
        )
    }

    private fun setNotifOnRepo(notif: ActiveNotificationModel) {
        activeNotificationListRepository.activeNotifications.value =
            ActiveNotificationsStore.Builder().apply { addIndividualNotif(notif) }.build()
        testScope.runCurrent()
    }

    private fun setNoNotifsOnRepo() {
        activeNotificationListRepository.activeNotifications.value =
            ActiveNotificationsStore.Builder().build()
        testScope.runCurrent()
    }
}

private const val CALL_UID = 900

// A process state that represents the process being visible to the user.
private const val PROC_STATE_VISIBLE = ActivityManager.PROCESS_STATE_TOP

// A process state that represents the process being invisible to the user.
private const val PROC_STATE_INVISIBLE = ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE
