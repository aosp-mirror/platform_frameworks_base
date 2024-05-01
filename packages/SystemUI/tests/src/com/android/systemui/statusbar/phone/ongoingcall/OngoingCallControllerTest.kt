/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.service.notification.NotificationListenerService.REASON_USER_STOPPED
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.gesture.SwipeStatusBarAwayGestureHandler
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.data.repository.FakeStatusBarModeRepository
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.OngoingCallRepository
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

private const val CALL_UID = 900

// A process state that represents the process being visible to the user.
private const val PROC_STATE_VISIBLE = ActivityManager.PROCESS_STATE_TOP

// A process state that represents the process being invisible to the user.
private const val PROC_STATE_INVISIBLE = ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
class OngoingCallControllerTest : SysuiTestCase() {

    private val clock = FakeSystemClock()
    private val mainExecutor = FakeExecutor(clock)
    private val uiEventLoggerFake = UiEventLoggerFake()
    private val testScope = TestScope()
    private val statusBarModeRepository = FakeStatusBarModeRepository()
    private val ongoingCallRepository = OngoingCallRepository()

    private lateinit var controller: OngoingCallController
    private lateinit var notifCollectionListener: NotifCollectionListener

    @Mock private lateinit var mockSwipeStatusBarAwayGestureHandler:
        SwipeStatusBarAwayGestureHandler
    @Mock private lateinit var mockOngoingCallListener: OngoingCallListener
    @Mock private lateinit var mockActivityStarter: ActivityStarter
    @Mock private lateinit var mockIActivityManager: IActivityManager
    @Mock private lateinit var mockStatusBarWindowController: StatusBarWindowController

    private lateinit var chipView: View

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        TestableLooper.get(this).runWithLooper {
            chipView = LayoutInflater.from(mContext).inflate(R.layout.ongoing_call_chip, null)
        }

        MockitoAnnotations.initMocks(this)
        val notificationCollection = mock(CommonNotifCollection::class.java)

        controller = OngoingCallController(
            testScope.backgroundScope,
            context,
            ongoingCallRepository,
            notificationCollection,
            clock,
            mockActivityStarter,
            mainExecutor,
            mockIActivityManager,
            OngoingCallLogger(uiEventLoggerFake),
            DumpManager(),
            mockStatusBarWindowController,
            mockSwipeStatusBarAwayGestureHandler,
            statusBarModeRepository,
        )
        controller.start()
        controller.addCallback(mockOngoingCallListener)
        controller.setChipView(chipView)

        val collectionListenerCaptor = ArgumentCaptor.forClass(NotifCollectionListener::class.java)
        verify(notificationCollection).addCollectionListener(collectionListenerCaptor.capture())
        notifCollectionListener = collectionListenerCaptor.value!!

        `when`(mockIActivityManager.getUidProcessState(eq(CALL_UID), nullable(String::class.java)))
                .thenReturn(PROC_STATE_INVISIBLE)
    }

    @After
    fun tearDown() {
        controller.tearDownChipView()
    }

    @Test
    fun onEntryUpdated_isOngoingCallNotif_listenerAndRepoNotified() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        verify(mockOngoingCallListener).onOngoingCallStateChanged(anyBoolean())
        assertThat(ongoingCallRepository.hasOngoingCall.value).isTrue()
    }

    @Test
    fun onEntryUpdated_isOngoingCallNotif_windowControllerUpdated() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        verify(mockStatusBarWindowController).setOngoingProcessRequiresStatusBarVisible(true)
    }

    @Test
    fun onEntryUpdated_notOngoingCallNotif_listenerAndRepoNotNotified() {
        notifCollectionListener.onEntryUpdated(createNotCallNotifEntry())

        verify(mockOngoingCallListener, never()).onOngoingCallStateChanged(anyBoolean())
        assertThat(ongoingCallRepository.hasOngoingCall.value).isFalse()
    }

    @Test
    fun onEntryUpdated_ongoingCallNotifThenScreeningCallNotif_listenerNotifiedTwice() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
        notifCollectionListener.onEntryUpdated(createScreeningCallNotifEntry())

        verify(mockOngoingCallListener, times(2))
                .onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun onEntryUpdated_ongoingCallNotifThenScreeningCallNotif_repoUpdated() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
        assertThat(ongoingCallRepository.hasOngoingCall.value).isTrue()

        notifCollectionListener.onEntryUpdated(createScreeningCallNotifEntry())

        assertThat(ongoingCallRepository.hasOngoingCall.value).isFalse()
    }

    /** Regression test for b/191472854. */
    @Test
    fun onEntryUpdated_notifHasNullContentIntent_noCrash() {
        notifCollectionListener.onEntryUpdated(
                createCallNotifEntry(ongoingCallStyle, nullContentIntent = true))
    }

    /** Regression test for b/192379214. */
    @Test
    @DisableFlags(android.app.Flags.FLAG_SORT_SECTION_BY_TIME)
    fun onEntryUpdated_notificationWhenIsZero_timeHidden() {
        val notification = NotificationEntryBuilder(createOngoingCallNotifEntry())
        notification.modifyNotification(context).setWhen(0)

        notifCollectionListener.onEntryUpdated(notification.build())
        chipView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        assertThat(chipView.findViewById<View>(R.id.ongoing_call_chip_time)?.measuredWidth)
                .isEqualTo(0)
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_SORT_SECTION_BY_TIME)
    fun onEntryUpdated_notificationWhenIsZero_timeShown() {
        val notification = NotificationEntryBuilder(createOngoingCallNotifEntry())
        notification.modifyNotification(context).setWhen(0)

        notifCollectionListener.onEntryUpdated(notification.build())
        chipView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        assertThat(chipView.findViewById<View>(R.id.ongoing_call_chip_time)?.measuredWidth)
                .isGreaterThan(0)
    }

    @Test
    fun onEntryUpdated_notificationWhenIsValid_timeShown() {
        val notification = NotificationEntryBuilder(createOngoingCallNotifEntry())
        notification.modifyNotification(context).setWhen(clock.currentTimeMillis())

        notifCollectionListener.onEntryUpdated(notification.build())
        chipView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        assertThat(chipView.findViewById<View>(R.id.ongoing_call_chip_time)?.measuredWidth)
                .isGreaterThan(0)
    }

    /** Regression test for b/194731244. */
    @Test
    fun onEntryUpdated_calledManyTimes_uidObserverOnlyRegisteredOnce() {
        for (i in 0 until 4) {
            // Re-create the notification each time so that it's considered a different object and
            // will re-trigger the whole flow.
            notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
        }

        verify(mockIActivityManager, times(1))
            .registerUidObserver(any(), any(), any(), any())
    }

    /** Regression test for b/216248574. */
    @Test
    fun entryUpdated_getUidProcessStateThrowsException_noCrash() {
        `when`(mockIActivityManager.getUidProcessState(eq(CALL_UID), nullable(String::class.java)))
                .thenThrow(SecurityException())

        // No assert required, just check no crash
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
    }

    /** Regression test for b/216248574. */
    @Test
    fun entryUpdated_registerUidObserverThrowsException_noCrash() {
        `when`(mockIActivityManager.registerUidObserver(
            any(), any(), any(), nullable(String::class.java)
        )).thenThrow(SecurityException())

        // No assert required, just check no crash
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
    }

    /** Regression test for b/216248574. */
    @Test
    fun entryUpdated_packageNameProvidedToActivityManager() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        val packageNameCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(mockIActivityManager).registerUidObserver(
            any(), any(), any(), packageNameCaptor.capture()
        )
        assertThat(packageNameCaptor.value).isNotNull()
    }

    /**
     * If a call notification is never added before #onEntryRemoved is called, then the listener
     * should never be notified.
     */
    @Test
    fun onEntryRemoved_callNotifNeverAddedBeforehand_listenerNotNotified() {
        notifCollectionListener.onEntryRemoved(createOngoingCallNotifEntry(), REASON_USER_STOPPED)

        verify(mockOngoingCallListener, never()).onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun onEntryRemoved_callNotifAddedThenRemoved_listenerNotified() {
        val ongoingCallNotifEntry = createOngoingCallNotifEntry()
        notifCollectionListener.onEntryAdded(ongoingCallNotifEntry)
        reset(mockOngoingCallListener)

        notifCollectionListener.onEntryRemoved(ongoingCallNotifEntry, REASON_USER_STOPPED)

        verify(mockOngoingCallListener).onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun onEntryRemoved_callNotifAddedThenRemoved_repoUpdated() {
        val ongoingCallNotifEntry = createOngoingCallNotifEntry()
        notifCollectionListener.onEntryAdded(ongoingCallNotifEntry)
        assertThat(ongoingCallRepository.hasOngoingCall.value).isTrue()

        notifCollectionListener.onEntryRemoved(ongoingCallNotifEntry, REASON_USER_STOPPED)

        assertThat(ongoingCallRepository.hasOngoingCall.value).isFalse()
    }

    @Test
    fun onEntryUpdated_callNotifAddedThenRemoved_windowControllerUpdated() {
        val ongoingCallNotifEntry = createOngoingCallNotifEntry()
        notifCollectionListener.onEntryAdded(ongoingCallNotifEntry)

        notifCollectionListener.onEntryRemoved(ongoingCallNotifEntry, REASON_USER_STOPPED)

        verify(mockStatusBarWindowController).setOngoingProcessRequiresStatusBarVisible(false)
    }

    /** Regression test for b/188491504. */
    @Test
    fun onEntryRemoved_removedNotifHasSameKeyAsAddedNotif_listenerNotified() {
        val ongoingCallNotifEntry = createOngoingCallNotifEntry()
        notifCollectionListener.onEntryAdded(ongoingCallNotifEntry)
        reset(mockOngoingCallListener)

        // Create another notification based on the ongoing call one, but remove the features that
        // made it a call notification.
        val removedEntryBuilder = NotificationEntryBuilder(ongoingCallNotifEntry)
        removedEntryBuilder.modifyNotification(context).style = null

        notifCollectionListener.onEntryRemoved(removedEntryBuilder.build(), REASON_USER_STOPPED)

        verify(mockOngoingCallListener).onOngoingCallStateChanged(anyBoolean())
    }

    /** Regression test for b/188491504. */
    @Test
    fun onEntryRemoved_removedNotifHasSameKeyAsAddedNotif_repoUpdated() {
        val ongoingCallNotifEntry = createOngoingCallNotifEntry()
        notifCollectionListener.onEntryAdded(ongoingCallNotifEntry)

        // Create another notification based on the ongoing call one, but remove the features that
        // made it a call notification.
        val removedEntryBuilder = NotificationEntryBuilder(ongoingCallNotifEntry)
        removedEntryBuilder.modifyNotification(context).style = null

        notifCollectionListener.onEntryRemoved(removedEntryBuilder.build(), REASON_USER_STOPPED)

        assertThat(ongoingCallRepository.hasOngoingCall.value).isFalse()
    }

    @Test
    fun onEntryRemoved_notifKeyDoesNotMatchOngoingCallNotif_listenerNotNotified() {
        notifCollectionListener.onEntryAdded(createOngoingCallNotifEntry())
        reset(mockOngoingCallListener)

        notifCollectionListener.onEntryRemoved(createNotCallNotifEntry(), REASON_USER_STOPPED)

        verify(mockOngoingCallListener, never()).onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun onEntryRemoved_notifKeyDoesNotMatchOngoingCallNotif_repoNotUpdated() {
        notifCollectionListener.onEntryAdded(createOngoingCallNotifEntry())

        notifCollectionListener.onEntryRemoved(createNotCallNotifEntry(), REASON_USER_STOPPED)

        assertThat(ongoingCallRepository.hasOngoingCall.value).isTrue()
    }

    @Test
    fun hasOngoingCall_noOngoingCallNotifSent_returnsFalse() {
        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_unrelatedNotifSent_returnsFalse() {
        notifCollectionListener.onEntryUpdated(createNotCallNotifEntry())

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_screeningCallNotifSent_returnsFalse() {
        notifCollectionListener.onEntryUpdated(createScreeningCallNotifEntry())

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentAndCallAppNotVisible_returnsTrue() {
        `when`(mockIActivityManager.getUidProcessState(eq(CALL_UID), nullable(String::class.java)))
                .thenReturn(PROC_STATE_INVISIBLE)

        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        assertThat(controller.hasOngoingCall()).isTrue()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentButCallAppVisible_returnsFalse() {
        `when`(mockIActivityManager.getUidProcessState(eq(CALL_UID), nullable(String::class.java)))
                .thenReturn(PROC_STATE_VISIBLE)

        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentButInvalidChipView_returnsFalse() {
        val invalidChipView = LinearLayout(context)
        controller.setChipView(invalidChipView)

        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentThenRemoved_returnsFalse() {
        val ongoingCallNotifEntry = createOngoingCallNotifEntry()

        notifCollectionListener.onEntryUpdated(ongoingCallNotifEntry)
        notifCollectionListener.onEntryRemoved(ongoingCallNotifEntry, REASON_USER_STOPPED)

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentThenScreeningCallNotifSent_returnsFalse() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
        notifCollectionListener.onEntryUpdated(createScreeningCallNotifEntry())

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentThenUnrelatedNotifSent_returnsTrue() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
        notifCollectionListener.onEntryUpdated(createNotCallNotifEntry())

        assertThat(controller.hasOngoingCall()).isTrue()
    }

    /**
     * This test fakes a theme change during an ongoing call.
     *
     * When a theme change happens, [CollapsedStatusBarFragment] and its views get re-created, so
     * [OngoingCallController.setChipView] gets called with a new view. If there's an active ongoing
     * call when the theme changes, the new view needs to be updated with the call information.
     */
    @Test
    fun setChipView_whenHasOngoingCallIsTrue_listenerNotifiedWithNewView() {
        // Start an ongoing call.
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
        reset(mockOngoingCallListener)

        lateinit var newChipView: View
        TestableLooper.get(this).runWithLooper {
            newChipView = LayoutInflater.from(mContext).inflate(R.layout.ongoing_call_chip, null)
        }

        // Change the chip view associated with the controller.
        controller.setChipView(newChipView)

        verify(mockOngoingCallListener).onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun callProcessChangesToVisible_listenerNotified() {
        // Start the call while the process is invisible.
        `when`(mockIActivityManager.getUidProcessState(eq(CALL_UID), nullable(String::class.java)))
                .thenReturn(PROC_STATE_INVISIBLE)
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
        reset(mockOngoingCallListener)

        val captor = ArgumentCaptor.forClass(IUidObserver.Stub::class.java)
        verify(mockIActivityManager).registerUidObserver(
                captor.capture(), any(), any(), nullable(String::class.java))
        val uidObserver = captor.value

        // Update the process to visible.
        uidObserver.onUidStateChanged(CALL_UID, PROC_STATE_VISIBLE, 0, 0)
        mainExecutor.advanceClockToLast()
        mainExecutor.runAllReady()

        verify(mockOngoingCallListener).onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun callProcessChangesToInvisible_listenerNotified() {
        // Start the call while the process is visible.
        `when`(mockIActivityManager.getUidProcessState(eq(CALL_UID), nullable(String::class.java)))
                .thenReturn(PROC_STATE_VISIBLE)
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
        reset(mockOngoingCallListener)

        val captor = ArgumentCaptor.forClass(IUidObserver.Stub::class.java)
        verify(mockIActivityManager).registerUidObserver(
                captor.capture(), any(), any(), nullable(String::class.java))
        val uidObserver = captor.value

        // Update the process to invisible.
        uidObserver.onUidStateChanged(CALL_UID, PROC_STATE_INVISIBLE, 0, 0)
        mainExecutor.advanceClockToLast()
        mainExecutor.runAllReady()

        verify(mockOngoingCallListener).onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun chipClicked_clickEventLogged() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        chipView.performClick()

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0))
                .isEqualTo(OngoingCallLogger.OngoingCallEvents.ONGOING_CALL_CLICKED.id)
    }

    /** Regression test for b/212467440. */
    @Test
    fun chipClicked_activityStarterTriggeredWithUnmodifiedIntent() {
        val notifEntry = createOngoingCallNotifEntry()
        val pendingIntent = notifEntry.sbn.notification.contentIntent
        notifCollectionListener.onEntryUpdated(notifEntry)

        chipView.performClick()

        // Ensure that the sysui didn't modify the notification's intent -- see b/212467440.
        verify(mockActivityStarter).postStartActivityDismissingKeyguard(eq(pendingIntent), any())
    }

    @Test
    fun notifyChipVisibilityChanged_visibleEventLogged() {
        controller.notifyChipVisibilityChanged(true)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0))
                .isEqualTo(OngoingCallLogger.OngoingCallEvents.ONGOING_CALL_VISIBLE.id)
    }
    // Other tests for notifyChipVisibilityChanged are in [OngoingCallLogger], since
    // [OngoingCallController.notifyChipVisibilityChanged] just delegates to that class.

    @Test
    fun callNotificationAdded_chipIsClickable() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        assertThat(chipView.hasOnClickListeners()).isTrue()
    }

    @Test
    fun fullscreenIsTrue_chipStillClickable() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = true
        testScope.runCurrent()

        assertThat(chipView.hasOnClickListeners()).isTrue()
    }

    // Swipe gesture tests

    @Test
    fun callStartedInImmersiveMode_swipeGestureCallbackAdded() {
        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = true
        testScope.runCurrent()

        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        verify(mockSwipeStatusBarAwayGestureHandler)
            .addOnGestureDetectedCallback(anyString(), any())
    }

    @Test
    fun callStartedNotInImmersiveMode_swipeGestureCallbackNotAdded() {
        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = false
        testScope.runCurrent()

        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        verify(mockSwipeStatusBarAwayGestureHandler, never())
            .addOnGestureDetectedCallback(anyString(), any())
    }

    @Test
    fun transitionToImmersiveMode_swipeGestureCallbackAdded() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = true
        testScope.runCurrent()

        verify(mockSwipeStatusBarAwayGestureHandler)
            .addOnGestureDetectedCallback(anyString(), any())
    }

    @Test
    fun transitionOutOfImmersiveMode_swipeGestureCallbackRemoved() {
        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = true
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
        reset(mockSwipeStatusBarAwayGestureHandler)

        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = false
        testScope.runCurrent()

        verify(mockSwipeStatusBarAwayGestureHandler)
            .removeOnGestureDetectedCallback(anyString())
    }

    @Test
    fun callEndedWhileInImmersiveMode_swipeGestureCallbackRemoved() {
        statusBarModeRepository.defaultDisplay.isInFullscreenMode.value = true
        testScope.runCurrent()
        val ongoingCallNotifEntry = createOngoingCallNotifEntry()
        notifCollectionListener.onEntryAdded(ongoingCallNotifEntry)
        reset(mockSwipeStatusBarAwayGestureHandler)

        notifCollectionListener.onEntryRemoved(ongoingCallNotifEntry, REASON_USER_STOPPED)

        verify(mockSwipeStatusBarAwayGestureHandler)
            .removeOnGestureDetectedCallback(anyString())
    }

    // TODO(b/195839150): Add test
    //  swipeGesturedTriggeredPreviously_entersImmersiveModeAgain_callbackNotAdded(). That's
    //  difficult to add now because we have no way to trigger [SwipeStatusBarAwayGestureHandler]'s
    //  callbacks in test.

    // END swipe gesture tests

    private fun createOngoingCallNotifEntry() = createCallNotifEntry(ongoingCallStyle)

    private fun createScreeningCallNotifEntry() = createCallNotifEntry(screeningCallStyle)

    private fun createCallNotifEntry(
        callStyle: Notification.CallStyle,
        nullContentIntent: Boolean = false
    ): NotificationEntry {
        val notificationEntryBuilder = NotificationEntryBuilder()
        notificationEntryBuilder.modifyNotification(context).style = callStyle
        notificationEntryBuilder.setUid(CALL_UID)

        if (nullContentIntent) {
            notificationEntryBuilder.modifyNotification(context).setContentIntent(null)
        } else {
            val contentIntent = mock(PendingIntent::class.java)
            notificationEntryBuilder.modifyNotification(context).setContentIntent(contentIntent)
        }

        return notificationEntryBuilder.build()
    }

    private fun createNotCallNotifEntry() = NotificationEntryBuilder().build()
}

private val person = Person.Builder().setName("name").build()
private val hangUpIntent = mock(PendingIntent::class.java)

private val ongoingCallStyle = Notification.CallStyle.forOngoingCall(person, hangUpIntent)
private val screeningCallStyle = Notification.CallStyle.forScreeningCall(
        person, hangUpIntent, /* answerIntent= */ mock(PendingIntent::class.java))