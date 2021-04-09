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

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.service.notification.NotificationListenerService.REASON_USER_STOPPED
import androidx.test.filters.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class OngoingCallControllerTest : SysuiTestCase() {

    private lateinit var controller: OngoingCallController
    private lateinit var notifCollectionListener: NotifCollectionListener

    @Mock private lateinit var mockOngoingCallListener: OngoingCallListener

    private lateinit var chipView: LinearLayout

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        TestableLooper.get(this).runWithLooper {
            chipView = LayoutInflater.from(mContext)
                    .inflate(R.layout.ongoing_call_chip, null) as LinearLayout
        }

        MockitoAnnotations.initMocks(this)
        val featureFlags = mock(FeatureFlags::class.java)
        `when`(featureFlags.isOngoingCallStatusBarChipEnabled).thenReturn(true)
        val notificationCollection = mock(CommonNotifCollection::class.java)

        controller = OngoingCallController(notificationCollection, featureFlags, FakeSystemClock())
        controller.init()
        controller.addCallback(mockOngoingCallListener)
        controller.setChipView(chipView)

        val collectionListenerCaptor = ArgumentCaptor.forClass(NotifCollectionListener::class.java)
        verify(notificationCollection).addCollectionListener(collectionListenerCaptor.capture())
        notifCollectionListener = collectionListenerCaptor.value!!
    }

    @Test
    fun onEntryUpdated_isOngoingCallNotif_listenerNotifiedWithRightCallTime() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        verify(mockOngoingCallListener).onOngoingCallStarted(anyBoolean())
    }

    @Test
    fun onEntryUpdated_notOngoingCallNotif_listenerNotNotified() {
        notifCollectionListener.onEntryUpdated(createNotCallNotifEntry())

        verify(mockOngoingCallListener, never()).onOngoingCallStarted(anyBoolean())
    }

    @Test
    fun onEntryRemoved_ongoingCallNotif_listenerNotified() {
        notifCollectionListener.onEntryRemoved(createOngoingCallNotifEntry(), REASON_USER_STOPPED)

        verify(mockOngoingCallListener).onOngoingCallEnded(anyBoolean())
    }

    @Test
    fun onEntryRemoved_notOngoingCallNotif_listenerNotNotified() {
        notifCollectionListener.onEntryRemoved(createNotCallNotifEntry(), REASON_USER_STOPPED)

        verify(mockOngoingCallListener, never()).onOngoingCallEnded(anyBoolean())
    }

    @Test
    fun hasOngoingCall_noOngoingCallNotifSent_returnsFalse() {
        assertThat(controller.hasOngoingCall).isFalse()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentAndChipViewSet_returnsTrue() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        assertThat(controller.hasOngoingCall).isTrue()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentButNoChipView_returnsFalse() {
        controller.setChipView(null)
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        assertThat(controller.hasOngoingCall).isFalse()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentThenRemoved_returnsFalse() {
        val ongoingCallNotifEntry = createOngoingCallNotifEntry()

        notifCollectionListener.onEntryUpdated(ongoingCallNotifEntry)
        notifCollectionListener.onEntryRemoved(ongoingCallNotifEntry, REASON_USER_STOPPED)

        assertThat(controller.hasOngoingCall).isFalse()
    }

    private fun createOngoingCallNotifEntry(): NotificationEntry {
        val notificationEntryBuilder = NotificationEntryBuilder()
        notificationEntryBuilder.modifyNotification(context).style = ongoingCallStyle
        return notificationEntryBuilder.build()
    }

    private fun createNotCallNotifEntry() = NotificationEntryBuilder().build()
}

private val ongoingCallStyle = Notification.CallStyle.forOngoingCall(
        Person.Builder().setName("name").build(),
        /* hangUpIntent= */ mock(PendingIntent::class.java))
