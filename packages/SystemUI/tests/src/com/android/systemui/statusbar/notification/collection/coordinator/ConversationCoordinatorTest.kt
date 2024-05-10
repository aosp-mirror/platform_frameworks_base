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
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManagerImpl
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.icon.ConversationIconManager
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_IMPORTANT_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_NON_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_PERSON
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
    private lateinit var peopleAlertingSectioner: NotifSectioner
    private lateinit var peopleSilentSectioner: NotifSectioner
    private lateinit var peopleComparator: NotifComparator
    private lateinit var beforeRenderListListener: OnBeforeRenderListListener

    @Mock private lateinit var pipeline: NotifPipeline
    @Mock private lateinit var conversationIconManager: ConversationIconManager
    @Mock private lateinit var peopleNotificationIdentifier: PeopleNotificationIdentifier
    @Mock private lateinit var channel: NotificationChannel
    @Mock private lateinit var headerController: NodeController
    private lateinit var entry: NotificationEntry
    private lateinit var entryA: NotificationEntry
    private lateinit var entryB: NotificationEntry

    private lateinit var coordinator: ConversationCoordinator

    private val featureFlags = FakeFeatureFlagsClassic()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        coordinator = ConversationCoordinator(
            peopleNotificationIdentifier,
            conversationIconManager,
            HighPriorityProvider(
                peopleNotificationIdentifier,
                GroupMembershipManagerImpl(featureFlags)
            ),
            headerController
        )
        whenever(channel.isImportantConversation).thenReturn(true)

        coordinator.attach(pipeline)

        // capture arguments:
        promoter = withArgCaptor {
            verify(pipeline).addPromoter(capture())
        }
        beforeRenderListListener = withArgCaptor {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }

        peopleAlertingSectioner = coordinator.peopleAlertingSectioner
        peopleSilentSectioner = coordinator.peopleSilentSectioner
        peopleComparator = peopleAlertingSectioner.comparator!!

        entry = NotificationEntryBuilder().setChannel(channel).build()

        val section = NotifSection(peopleAlertingSectioner, 0)
        entryA = NotificationEntryBuilder().setChannel(channel)
            .setSection(section).setTag("A").build()
        entryB = NotificationEntryBuilder().setChannel(channel)
            .setSection(section).setTag("B").build()
    }

    @Test
    fun testPromotesImportantConversations() {
        // only promote important conversations
        assertTrue(promoter.shouldPromoteToTopLevel(entry))
        assertFalse(promoter.shouldPromoteToTopLevel(NotificationEntryBuilder().build()))
    }

    @Test
    fun testPromotedImportantConversationsMakesSummaryUnimportant() {
        val altChildA = NotificationEntryBuilder().setTag("A").build()
        val altChildB = NotificationEntryBuilder().setTag("B").build()
        val summary = NotificationEntryBuilder().setId(2).setChannel(channel).build()
        val groupEntry = GroupEntryBuilder()
            .setParent(GroupEntry.ROOT_ENTRY)
            .setSummary(summary)
            .setChildren(listOf(entry, altChildA, altChildB))
            .build()
        assertTrue(promoter.shouldPromoteToTopLevel(entry))
        assertFalse(promoter.shouldPromoteToTopLevel(altChildA))
        assertFalse(promoter.shouldPromoteToTopLevel(altChildB))
        NotificationEntryBuilder.setNewParent(entry, GroupEntry.ROOT_ENTRY)
        GroupEntryBuilder.getRawChildren(groupEntry).remove(entry)
        beforeRenderListListener.onBeforeRenderList(listOf(entry, groupEntry))
        verify(conversationIconManager).setUnimportantConversations(eq(listOf(summary.key)))
    }

    @Test
    fun testInAlertingPeopleSectionWhenTheImportanceIsAtLeastDefault() {
        // GIVEN
        val alertingEntry = NotificationEntryBuilder().setChannel(channel)
                .setImportance(IMPORTANCE_DEFAULT).build()
        whenever(peopleNotificationIdentifier.getPeopleNotificationType(alertingEntry))
                .thenReturn(TYPE_PERSON)

        // put alerting people notifications in this section
        assertThat(peopleAlertingSectioner.isInSection(alertingEntry)).isTrue()
       }

    @Test
    fun testInSilentPeopleSectionWhenTheImportanceIsLowerThanDefault() {
        // GIVEN
        val silentEntry = NotificationEntryBuilder().setChannel(channel)
                .setImportance(IMPORTANCE_LOW).build()
        whenever(peopleNotificationIdentifier.getPeopleNotificationType(silentEntry))
                .thenReturn(TYPE_PERSON)

        // THEN put silent people notifications in this section
        assertThat(peopleSilentSectioner.isInSection(silentEntry)).isTrue()
        // People Alerting sectioning happens before the silent one.
        // It claims high important conversations and rest of conversations will be considered as silent.
        assertThat(peopleAlertingSectioner.isInSection(silentEntry)).isFalse()
    }

    @Test
    fun testNotInPeopleSection() {
        // GIVEN
        val entry = NotificationEntryBuilder().setChannel(channel)
                .setImportance(IMPORTANCE_LOW).build()
        val importantEntry = NotificationEntryBuilder().setChannel(channel)
                .setImportance(IMPORTANCE_HIGH).build()
        whenever(peopleNotificationIdentifier.getPeopleNotificationType(entry))
                .thenReturn(TYPE_NON_PERSON)
        whenever(peopleNotificationIdentifier.getPeopleNotificationType(importantEntry))
                .thenReturn(TYPE_NON_PERSON)

        // THEN - only put people notification either silent or alerting
        assertThat(peopleSilentSectioner.isInSection(entry)).isFalse()
        assertThat(peopleAlertingSectioner.isInSection(importantEntry)).isFalse()
    }

    @Test
    fun testInAlertingPeopleSectionWhenThereIsAnImportantChild() {
        // GIVEN
        val altChildA = NotificationEntryBuilder().setTag("A")
                .setImportance(IMPORTANCE_DEFAULT).build()
        val altChildB = NotificationEntryBuilder().setTag("B")
                .setImportance(IMPORTANCE_LOW).build()
        val summary = NotificationEntryBuilder().setId(2)
                .setImportance(IMPORTANCE_LOW).setChannel(channel).build()
        val groupEntry = GroupEntryBuilder()
                .setParent(GroupEntry.ROOT_ENTRY)
                .setSummary(summary)
                .setChildren(listOf(altChildA, altChildB))
                .build()
        whenever(peopleNotificationIdentifier.getPeopleNotificationType(summary))
                .thenReturn(TYPE_PERSON)
        // THEN
        assertThat(peopleAlertingSectioner.isInSection(groupEntry)).isTrue()
    }

    @Test
    fun testComparatorPutsImportantPeopleFirst() {
        whenever(peopleNotificationIdentifier.getPeopleNotificationType(entryA))
            .thenReturn(TYPE_IMPORTANT_PERSON)
        whenever(peopleNotificationIdentifier.getPeopleNotificationType(entryB))
            .thenReturn(TYPE_PERSON)

        // only put people notifications in this section
        assertThat(peopleComparator.compare(entryA, entryB)).isEqualTo(-1)
    }

    @Test
    fun testComparatorEquatesPeopleWithSameType() {
        whenever(peopleNotificationIdentifier.getPeopleNotificationType(entryA))
            .thenReturn(TYPE_PERSON)
        whenever(peopleNotificationIdentifier.getPeopleNotificationType(entryB))
            .thenReturn(TYPE_PERSON)

        // only put people notifications in this section
        assertThat(peopleComparator.compare(entryA, entryB)).isEqualTo(0)
    }
}
