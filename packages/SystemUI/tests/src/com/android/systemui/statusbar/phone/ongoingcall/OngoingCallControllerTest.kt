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
import android.content.Intent
import android.service.notification.NotificationListenerService.REASON_USER_STOPPED
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
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
class OngoingCallControllerTest : SysuiTestCase() {

    private val clock = FakeSystemClock()
    private val mainExecutor = FakeExecutor(clock)
    private val uiEventLoggerFake = UiEventLoggerFake()

    private lateinit var controller: OngoingCallController
    private lateinit var notifCollectionListener: NotifCollectionListener

    @Mock private lateinit var mockOngoingCallListener: OngoingCallListener
    @Mock private lateinit var mockActivityStarter: ActivityStarter
    @Mock private lateinit var mockIActivityManager: IActivityManager

    private lateinit var chipView: View

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        TestableLooper.get(this).runWithLooper {
            chipView = LayoutInflater.from(mContext).inflate(R.layout.ongoing_call_chip, null)
        }

        MockitoAnnotations.initMocks(this)
        val featureFlags = mock(FeatureFlags::class.java)
        `when`(featureFlags.isOngoingCallStatusBarChipEnabled).thenReturn(true)
        val notificationCollection = mock(CommonNotifCollection::class.java)

        controller = OngoingCallController(
                notificationCollection,
                featureFlags,
                clock,
                mockActivityStarter,
                mainExecutor,
                mockIActivityManager,
                OngoingCallLogger(uiEventLoggerFake))
        controller.init()
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
    fun onEntryUpdated_isOngoingCallNotif_listenerNotified() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        verify(mockOngoingCallListener).onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun onEntryUpdated_notOngoingCallNotif_listenerNotNotified() {
        notifCollectionListener.onEntryUpdated(createNotCallNotifEntry())

        verify(mockOngoingCallListener, never()).onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun onEntryUpdated_ongoingCallNotifThenScreeningCallNotif_listenerNotifiedTwice() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
        notifCollectionListener.onEntryUpdated(createScreeningCallNotifEntry())

        verify(mockOngoingCallListener, times(2))
                .onOngoingCallStateChanged(anyBoolean())
    }

    /** Regression test for b/191472854. */
    @Test
    fun onEntryUpdated_notifHasNullContentIntent_noCrash() {
        notifCollectionListener.onEntryUpdated(
                createCallNotifEntry(ongoingCallStyle, nullContentIntent = true))
    }

    /** Regression test for b/192379214. */
    @Test
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
    fun onEntryUpdated_calledManyTimes_uidObserverUnregisteredManyTimes() {
        val numCalls = 4

        for (i in 0 until numCalls) {
            // Re-create the notification each time so that it's considered a different object and
            // observers will get re-registered (and hopefully unregistered).
            notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())
        }

        // There should be 1 observer still registered, so we should unregister n-1 times.
        verify(mockIActivityManager, times(numCalls - 1)).unregisterUidObserver(any())
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

    /** Regression test for b/201097913. */
    @Test
    fun onEntryCleanUp_callNotifAddedThenRemoved_listenerNotified() {
        val ongoingCallNotifEntry = createOngoingCallNotifEntry()
        notifCollectionListener.onEntryAdded(ongoingCallNotifEntry)
        reset(mockOngoingCallListener)

        notifCollectionListener.onEntryCleanUp(ongoingCallNotifEntry)

        verify(mockOngoingCallListener).onOngoingCallStateChanged(anyBoolean())
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


    @Test
    fun onEntryRemoved_notifKeyDoesNotMatchOngoingCallNotif_listenerNotNotified() {
        notifCollectionListener.onEntryAdded(createOngoingCallNotifEntry())
        reset(mockOngoingCallListener)

        notifCollectionListener.onEntryRemoved(createNotCallNotifEntry(), REASON_USER_STOPPED)

        verify(mockOngoingCallListener, never()).onOngoingCallStateChanged(anyBoolean())
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
        mainExecutor.runAllReady();

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
        mainExecutor.runAllReady();

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

    @Test
    fun notifyChipVisibilityChanged_visibleEventLogged() {
        controller.notifyChipVisibilityChanged(true)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0))
                .isEqualTo(OngoingCallLogger.OngoingCallEvents.ONGOING_CALL_VISIBLE.id)
    }
    // Other tests for notifyChipVisibilityChanged are in [OngoingCallLogger], since
    // [OngoingCallController.notifyChipVisibilityChanged] just delegates to that class.

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
            `when`(contentIntent.intent).thenReturn(mock(Intent::class.java))
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