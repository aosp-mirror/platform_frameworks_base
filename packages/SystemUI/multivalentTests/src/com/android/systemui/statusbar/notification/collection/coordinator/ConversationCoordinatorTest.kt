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

import android.app.Flags
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.SortBySectionTimeFlag
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
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.PeopleNotificationType
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_FULL_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_IMPORTANT_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_NON_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifierImpl
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class ConversationCoordinatorTest : SysuiTestCase() {
    // captured listeners and pluggables:
    private lateinit var promoter: NotifPromoter
    private lateinit var peopleAlertingSectioner: NotifSectioner
    private lateinit var peopleSilentSectioner: NotifSectioner
    private lateinit var peopleComparator: NotifComparator
    private lateinit var beforeRenderListListener: OnBeforeRenderListListener

    private lateinit var peopleNotificationIdentifier: PeopleNotificationIdentifier
    private lateinit var peopleAlertingSection: NotifSection

    @Mock private lateinit var pipeline: NotifPipeline
    @Mock private lateinit var conversationIconManager: ConversationIconManager
    @Mock private lateinit var headerController: NodeController

    private lateinit var coordinator: ConversationCoordinator

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        peopleNotificationIdentifier =
            PeopleNotificationIdentifierImpl(mock(), GroupMembershipManagerImpl())
        coordinator =
            ConversationCoordinator(
                peopleNotificationIdentifier,
                conversationIconManager,
                HighPriorityProvider(peopleNotificationIdentifier, GroupMembershipManagerImpl()),
                headerController
            )

        coordinator.attach(pipeline)

        // capture arguments:
        promoter = withArgCaptor { verify(pipeline).addPromoter(capture()) }
        beforeRenderListListener = withArgCaptor {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }

        peopleAlertingSectioner = coordinator.peopleAlertingSectioner
        peopleSilentSectioner = coordinator.peopleSilentSectioner
        if (!SortBySectionTimeFlag.isEnabled)
            peopleComparator = peopleAlertingSectioner.comparator!!

        peopleAlertingSection = NotifSection(peopleAlertingSectioner, 0)
    }

    @Test
    fun priorityPeopleSectionerClaimsOnlyImportantConversations() {
        val sectioner = coordinator.priorityPeopleSectioner
        assertTrue(sectioner.isInSection(makeEntryOfPeopleType(TYPE_IMPORTANT_PERSON)))
        assertFalse(sectioner.isInSection(makeEntryOfPeopleType(TYPE_FULL_PERSON)))
        assertFalse(sectioner.isInSection(makeEntryOfPeopleType(TYPE_PERSON)))
        assertFalse(sectioner.isInSection(makeEntryOfPeopleType(TYPE_NON_PERSON)))
        assertFalse(sectioner.isInSection(NotificationEntryBuilder().build()))
    }

    @Test
    fun testPromotesImportantConversations() {
        assertTrue(promoter.shouldPromoteToTopLevel(makeEntryOfPeopleType(TYPE_IMPORTANT_PERSON)))
        assertFalse(promoter.shouldPromoteToTopLevel(makeEntryOfPeopleType(TYPE_FULL_PERSON)))
        assertFalse(promoter.shouldPromoteToTopLevel(makeEntryOfPeopleType(TYPE_PERSON)))
        assertFalse(promoter.shouldPromoteToTopLevel(makeEntryOfPeopleType(TYPE_NON_PERSON)))
        assertFalse(promoter.shouldPromoteToTopLevel(NotificationEntryBuilder().build()))
    }

    @Test
    fun testPromotedImportantConversationsMakesSummaryUnimportant() {
        val importantChannel =
            mock<NotificationChannel>().also {
                whenever(it.isImportantConversation).thenReturn(true)
            }
        val otherChannel =
            mock<NotificationChannel>().also {
                whenever(it.isImportantConversation).thenReturn(false)
            }
        val importantChild =
            makeEntryOfPeopleType(TYPE_IMPORTANT_PERSON) { setChannel(importantChannel) }
        val altChildA =
            makeEntryOfPeopleType(TYPE_FULL_PERSON) { setChannel(otherChannel).setTag("A") }
        val altChildB =
            makeEntryOfPeopleType(TYPE_FULL_PERSON) { setChannel(otherChannel).setTag("B") }
        val summary =
            makeEntryOfPeopleType(TYPE_IMPORTANT_PERSON) { setChannel(importantChannel).setId(2) }
        val groupEntry =
            GroupEntryBuilder()
                .setParent(GroupEntry.ROOT_ENTRY)
                .setSummary(summary)
                .setChildren(listOf(importantChild, altChildA, altChildB))
                .build()
        assertTrue(promoter.shouldPromoteToTopLevel(importantChild))
        assertFalse(promoter.shouldPromoteToTopLevel(altChildA))
        assertFalse(promoter.shouldPromoteToTopLevel(altChildB))
        NotificationEntryBuilder.setNewParent(importantChild, GroupEntry.ROOT_ENTRY)
        GroupEntryBuilder.getRawChildren(groupEntry).remove(importantChild)
        beforeRenderListListener.onBeforeRenderList(listOf(importantChild, groupEntry))
        verify(conversationIconManager).setUnimportantConversations(eq(listOf(summary.key)))
    }

    @Test
    fun testInAlertingPeopleSectionWhenTheImportanceIsAtLeastDefault() {
        // GIVEN
        val alertingEntry = makeEntryOfPeopleType(TYPE_PERSON) { setImportance(IMPORTANCE_DEFAULT) }

        // put alerting people notifications in this section
        assertThat(peopleAlertingSectioner.isInSection(alertingEntry)).isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_SORT_SECTION_BY_TIME)
    fun testInAlertingPeopleSectionWhenTheImportanceIsLowerThanDefault() {
        // GIVEN
        val silentEntry = makeEntryOfPeopleType(TYPE_PERSON) { setImportance(IMPORTANCE_LOW) }

        // THEN put silent people notifications in alerting section
        assertThat(peopleAlertingSectioner.isInSection(silentEntry)).isTrue()
    }

    @Test
    @DisableFlags(Flags.FLAG_SORT_SECTION_BY_TIME)
    fun testInSilentPeopleSectionWhenTheImportanceIsLowerThanDefault() {
        // GIVEN
        val silentEntry = makeEntryOfPeopleType(TYPE_PERSON) { setImportance(IMPORTANCE_LOW) }

        // THEN put silent people notifications in this section
        assertThat(peopleSilentSectioner.isInSection(silentEntry)).isTrue()
        // People Alerting sectioning happens before the silent one.
        // It claims high important conversations and rest of conversations will be considered as
        // silent.
        assertThat(peopleAlertingSectioner.isInSection(silentEntry)).isFalse()
    }

    @Test
    fun testNotInPeopleSection() {
        // GIVEN
        val entry = makeEntryOfPeopleType(TYPE_NON_PERSON) { setImportance(IMPORTANCE_LOW) }
        val importantEntry =
            makeEntryOfPeopleType(TYPE_NON_PERSON) { setImportance(IMPORTANCE_HIGH) }

        // THEN - only put people notification either silent or alerting
        if (!SortBySectionTimeFlag.isEnabled) {
            assertThat(peopleSilentSectioner.isInSection(entry)).isFalse()
        }
        assertThat(peopleAlertingSectioner.isInSection(importantEntry)).isFalse()
    }

    @Test
    fun testInAlertingPeopleSectionWhenThereIsAnImportantChild() {
        // GIVEN
        val altChildA =
            makeEntryOfPeopleType(TYPE_NON_PERSON) { setTag("A").setImportance(IMPORTANCE_DEFAULT) }
        val altChildB =
            makeEntryOfPeopleType(TYPE_NON_PERSON) { setTag("B").setImportance(IMPORTANCE_LOW) }
        val summary = makeEntryOfPeopleType(TYPE_PERSON) { setId(2).setImportance(IMPORTANCE_LOW) }
        val groupEntry =
            GroupEntryBuilder()
                .setParent(GroupEntry.ROOT_ENTRY)
                .setSummary(summary)
                .setChildren(listOf(altChildA, altChildB))
                .build()
        // THEN
        assertThat(peopleAlertingSectioner.isInSection(groupEntry)).isTrue()
    }

    @Test
    @DisableFlags(Flags.FLAG_SORT_SECTION_BY_TIME)
    fun testComparatorPutsImportantPeopleFirst() {
        val entryA =
            makeEntryOfPeopleType(TYPE_IMPORTANT_PERSON) {
                setSection(peopleAlertingSection).setTag("A")
            }
        val entryB =
            makeEntryOfPeopleType(TYPE_PERSON) { setSection(peopleAlertingSection).setTag("B") }

        // only put people notifications in this section
        assertThat(peopleComparator.compare(entryA, entryB)).isEqualTo(-1)
    }

    @Test
    @DisableFlags(Flags.FLAG_SORT_SECTION_BY_TIME)
    fun testComparatorEquatesPeopleWithSameType() {
        val entryA =
            makeEntryOfPeopleType(TYPE_PERSON) { setSection(peopleAlertingSection).setTag("A") }
        val entryB =
            makeEntryOfPeopleType(TYPE_PERSON) { setSection(peopleAlertingSection).setTag("B") }

        // only put people notifications in this section
        assertThat(peopleComparator.compare(entryA, entryB)).isEqualTo(0)
    }

    @Test
    @EnableFlags(Flags.FLAG_SORT_SECTION_BY_TIME)
    fun testNoSecondarySortForConversations() {
        assertThat(peopleAlertingSectioner.comparator).isNull()
    }

    private fun makeEntryOfPeopleType(
        @PeopleNotificationType type: Int,
        buildBlock: NotificationEntryBuilder.() -> Unit = {}
    ): NotificationEntry {
        val channel: NotificationChannel = mock()
        whenever(channel.isImportantConversation).thenReturn(type == TYPE_IMPORTANT_PERSON)
        val entry =
            NotificationEntryBuilder()
                .updateRanking {
                    it.setIsConversation(type != TYPE_NON_PERSON)
                    it.setShortcutInfo(if (type >= TYPE_FULL_PERSON) mock() else null)
                    it.setChannel(channel)
                }
                .also(buildBlock)
                .build()
        assertEquals(type, peopleNotificationIdentifier.getPeopleNotificationType(entry))
        return entry
    }
}
