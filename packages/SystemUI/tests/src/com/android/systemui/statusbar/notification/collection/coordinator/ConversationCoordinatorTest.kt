/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator

import android.app.NotificationChannel
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_PERSON
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ConversationCoordinatorTest : SysuiTestCase() {
    // captured listeners and pluggables:
    private lateinit var promoter: NotifPromoter
    private lateinit var peopleSectioner: NotifSectioner

    @Mock private lateinit var pipeline: NotifPipeline
    @Mock private lateinit var peopleNotificationIdentifier: PeopleNotificationIdentifier
    @Mock private lateinit var channel: NotificationChannel
    @Mock private lateinit var headerController: NodeController
    private lateinit var entry: NotificationEntry

    private lateinit var coordinator: ConversationCoordinator

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        coordinator = ConversationCoordinator(peopleNotificationIdentifier, headerController)
        whenever(channel.isImportantConversation).thenReturn(true)

        coordinator.attach(pipeline)

        // capture arguments:
        val notifPromoterCaptor = ArgumentCaptor.forClass(NotifPromoter::class.java)
        verify(pipeline).addPromoter(notifPromoterCaptor.capture())
        promoter = notifPromoterCaptor.value

        peopleSectioner = coordinator.sectioner

        entry = NotificationEntryBuilder().setChannel(channel).build()
    }

    @Test
    fun testPromotesImportantConversations() {
        // only promote important conversations
        assertTrue(promoter.shouldPromoteToTopLevel(entry))
        assertFalse(promoter.shouldPromoteToTopLevel(NotificationEntryBuilder().build()))
    }

    @Test
    fun testInPeopleSection() {
        whenever(peopleNotificationIdentifier.getPeopleNotificationType(entry))
            .thenReturn(TYPE_PERSON)

        // only put people notifications in this section
        assertTrue(peopleSectioner.isInSection(entry))
        assertFalse(peopleSectioner.isInSection(NotificationEntryBuilder().build()))
    }
}
