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
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.collection.coordinator

import android.app.Notification
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_LOW
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeTransformGroupsListener
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.modifyEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.data.repository.FakeHeadsUpRowRepository
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.shared.NotificationMinimalismPrototype
import com.android.systemui.statusbar.notification.stack.data.repository.headsUpNotificationRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.StringSubject
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(NotificationMinimalismPrototype.FLAG_NAME)
class LockScreenMinimalismCoordinatorTest : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply {
            testDispatcher = UnconfinedTestDispatcher()
            statusBarStateController =
                mock<SysuiStatusBarStateController>().also { mock ->
                    doAnswer { statusBarState.ordinal }.whenever(mock).state
                }
            fakeSettings.putInt(Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS, 1)
        }
    private val notifPipeline: NotifPipeline = mock()
    private var statusBarState: StatusBarState = StatusBarState.KEYGUARD

    @Test
    fun topUnseenSectioner() {
        val solo = NotificationEntryBuilder().setTag("solo").build()
        val child1 = NotificationEntryBuilder().setTag("child1").build()
        val child2 = NotificationEntryBuilder().setTag("child2").build()
        val parent = NotificationEntryBuilder().setTag("parent").build()
        val group = GroupEntryBuilder().addChild(child1).addChild(child2).setSummary(parent).build()

        runCoordinatorTest {
            kosmos.activeNotificationListRepository.topUnseenNotificationKey.value = solo.key
            assertThat(topUnseenSectioner.isInSection(solo)).isTrue()
            assertThat(topUnseenSectioner.isInSection(child1)).isFalse()
            assertThat(topUnseenSectioner.isInSection(child2)).isFalse()
            assertThat(topUnseenSectioner.isInSection(parent)).isFalse()
            assertThat(topUnseenSectioner.isInSection(group)).isFalse()

            kosmos.activeNotificationListRepository.topUnseenNotificationKey.value = child1.key
            assertThat(topUnseenSectioner.isInSection(solo)).isFalse()
            assertThat(topUnseenSectioner.isInSection(child1)).isTrue()
            assertThat(topUnseenSectioner.isInSection(child2)).isFalse()
            assertThat(topUnseenSectioner.isInSection(parent)).isFalse()
            assertThat(topUnseenSectioner.isInSection(group)).isTrue()

            kosmos.activeNotificationListRepository.topUnseenNotificationKey.value = parent.key
            assertThat(topUnseenSectioner.isInSection(solo)).isFalse()
            assertThat(topUnseenSectioner.isInSection(child1)).isFalse()
            assertThat(topUnseenSectioner.isInSection(child2)).isFalse()
            assertThat(topUnseenSectioner.isInSection(parent)).isTrue()
            assertThat(topUnseenSectioner.isInSection(group)).isTrue()

            kosmos.activeNotificationListRepository.topOngoingNotificationKey.value = solo.key
            kosmos.activeNotificationListRepository.topUnseenNotificationKey.value = null
            assertThat(topUnseenSectioner.isInSection(solo)).isFalse()
            assertThat(topUnseenSectioner.isInSection(child1)).isFalse()
            assertThat(topUnseenSectioner.isInSection(child2)).isFalse()
            assertThat(topUnseenSectioner.isInSection(parent)).isFalse()
            assertThat(topUnseenSectioner.isInSection(group)).isFalse()
        }
    }

    @Test
    fun topOngoingSectioner() {
        val solo = NotificationEntryBuilder().setTag("solo").build()
        val child1 = NotificationEntryBuilder().setTag("child1").build()
        val child2 = NotificationEntryBuilder().setTag("child2").build()
        val parent = NotificationEntryBuilder().setTag("parent").build()
        val group = GroupEntryBuilder().addChild(child1).addChild(child2).setSummary(parent).build()

        runCoordinatorTest {
            kosmos.activeNotificationListRepository.topOngoingNotificationKey.value = solo.key
            assertThat(topOngoingSectioner.isInSection(solo)).isTrue()
            assertThat(topOngoingSectioner.isInSection(child1)).isFalse()
            assertThat(topOngoingSectioner.isInSection(child2)).isFalse()
            assertThat(topOngoingSectioner.isInSection(parent)).isFalse()
            assertThat(topOngoingSectioner.isInSection(group)).isFalse()

            kosmos.activeNotificationListRepository.topOngoingNotificationKey.value = child1.key
            assertThat(topOngoingSectioner.isInSection(solo)).isFalse()
            assertThat(topOngoingSectioner.isInSection(child1)).isTrue()
            assertThat(topOngoingSectioner.isInSection(child2)).isFalse()
            assertThat(topOngoingSectioner.isInSection(parent)).isFalse()
            assertThat(topOngoingSectioner.isInSection(group)).isTrue()

            kosmos.activeNotificationListRepository.topOngoingNotificationKey.value = parent.key
            assertThat(topOngoingSectioner.isInSection(solo)).isFalse()
            assertThat(topOngoingSectioner.isInSection(child1)).isFalse()
            assertThat(topOngoingSectioner.isInSection(child2)).isFalse()
            assertThat(topOngoingSectioner.isInSection(parent)).isTrue()
            assertThat(topOngoingSectioner.isInSection(group)).isTrue()

            kosmos.activeNotificationListRepository.topOngoingNotificationKey.value = null
            kosmos.activeNotificationListRepository.topUnseenNotificationKey.value = solo.key
            assertThat(topOngoingSectioner.isInSection(solo)).isFalse()
            assertThat(topOngoingSectioner.isInSection(child1)).isFalse()
            assertThat(topOngoingSectioner.isInSection(child2)).isFalse()
            assertThat(topOngoingSectioner.isInSection(parent)).isFalse()
            assertThat(topOngoingSectioner.isInSection(group)).isFalse()
        }
    }

    @Test
    fun testPromoter() {
        val child1 = NotificationEntryBuilder().setTag("child1").build()
        val child2 = NotificationEntryBuilder().setTag("child2").build()
        val child3 = NotificationEntryBuilder().setTag("child3").build()
        val parent = NotificationEntryBuilder().setTag("parent").build()
        GroupEntryBuilder()
            .addChild(child1)
            .addChild(child2)
            .addChild(child3)
            .setSummary(parent)
            .build()

        runCoordinatorTest {
            kosmos.activeNotificationListRepository.topOngoingNotificationKey.value = null
            kosmos.activeNotificationListRepository.topUnseenNotificationKey.value = null
            assertThat(promoter.shouldPromoteToTopLevel(child1)).isFalse()
            assertThat(promoter.shouldPromoteToTopLevel(child2)).isFalse()
            assertThat(promoter.shouldPromoteToTopLevel(child3)).isFalse()
            assertThat(promoter.shouldPromoteToTopLevel(parent)).isFalse()

            kosmos.activeNotificationListRepository.topOngoingNotificationKey.value = child1.key
            kosmos.activeNotificationListRepository.topUnseenNotificationKey.value = null
            assertThat(promoter.shouldPromoteToTopLevel(child1)).isTrue()
            assertThat(promoter.shouldPromoteToTopLevel(child2)).isFalse()
            assertThat(promoter.shouldPromoteToTopLevel(child3)).isFalse()
            assertThat(promoter.shouldPromoteToTopLevel(parent)).isFalse()

            kosmos.activeNotificationListRepository.topOngoingNotificationKey.value = null
            kosmos.activeNotificationListRepository.topUnseenNotificationKey.value = child2.key
            assertThat(promoter.shouldPromoteToTopLevel(child1)).isFalse()
            assertThat(promoter.shouldPromoteToTopLevel(child2))
                .isEqualTo(NotificationMinimalismPrototype.ungroupTopUnseen)
            assertThat(promoter.shouldPromoteToTopLevel(child3)).isFalse()
            assertThat(promoter.shouldPromoteToTopLevel(parent)).isFalse()

            kosmos.activeNotificationListRepository.topOngoingNotificationKey.value = child1.key
            kosmos.activeNotificationListRepository.topUnseenNotificationKey.value = child2.key
            assertThat(promoter.shouldPromoteToTopLevel(child1)).isTrue()
            assertThat(promoter.shouldPromoteToTopLevel(child2))
                .isEqualTo(NotificationMinimalismPrototype.ungroupTopUnseen)
            assertThat(promoter.shouldPromoteToTopLevel(child3)).isFalse()
            assertThat(promoter.shouldPromoteToTopLevel(parent)).isFalse()
        }
    }

    @Test
    fun topOngoingIdentifier() {
        val solo1 = defaultEntryBuilder().setTag("solo1").setRank(1).build()
        val solo2 = defaultEntryBuilder().setTag("solo2").setRank(2).build()
        val parent = defaultEntryBuilder().setTag("parent").setRank(3).build()
        val child1 = defaultEntryBuilder().setTag("child1").setRank(4).build()
        val child2 = defaultEntryBuilder().setTag("child2").setRank(5).build()
        val group = GroupEntryBuilder().setSummary(parent).addChild(child1).addChild(child2).build()
        val listEntryList = listOf(group, solo1, solo2)

        runCoordinatorTest {
            // TEST: base case - no entries in the list
            onBeforeTransformGroupsListener.onBeforeTransformGroups(emptyList())
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(null)

            // TEST: none of these are unseen or ongoing yet, so don't pick them
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(null)

            // TEST: when solo2 is the only one colorized, it gets picked up
            solo2.setColorizedFgs(true)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(solo2.key)
            assertThatTopUnseenKey().isEqualTo(null)

            // TEST: once solo1 is colorized, it takes priority for being ranked higher
            solo1.setColorizedFgs(true)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(solo1.key)
            assertThatTopUnseenKey().isEqualTo(null)

            // TEST: changing just the rank of solo1 causes it to pick up solo2 instead
            solo1.modifyEntry { setRank(20) }
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(solo2.key)
            assertThatTopUnseenKey().isEqualTo(null)

            // TEST: switching to SHADE disables the whole thing
            statusBarState = StatusBarState.SHADE
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(null)

            // TEST: switching back to KEYGUARD picks up the same entry again
            statusBarState = StatusBarState.KEYGUARD
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(solo2.key)
            assertThatTopUnseenKey().isEqualTo(null)

            // TEST: updating to not colorized revokes the top-ongoing status
            solo2.setColorizedFgs(false)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(solo1.key)
            assertThatTopUnseenKey().isEqualTo(null)

            // TEST: updating the importance to LOW revokes top-ongoing status
            solo1.modifyEntry { setImportance(IMPORTANCE_LOW) }
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(null)
        }
    }

    @Test
    fun topUnseenIdentifier() {
        val solo1 = defaultEntryBuilder().setTag("solo1").setRank(1).build()
        val solo2 = defaultEntryBuilder().setTag("solo2").setRank(2).build()
        val parent = defaultEntryBuilder().setTag("parent").setRank(4).build()
        val child1 = defaultEntryBuilder().setTag("child1").setRank(5).build()
        val child2 = defaultEntryBuilder().setTag("child2").setRank(6).build()
        val group = GroupEntryBuilder().setSummary(parent).addChild(child1).addChild(child2).build()
        val listEntryList = listOf(group, solo1, solo2)
        val notificationEntryList = listOf(solo1, solo2, parent, child1, child2)

        runCoordinatorTest {
            // All entries are added (and now unseen)
            notificationEntryList.forEach { collectionListener.onEntryAdded(it) }

            // TEST: Filtered out entries are ignored
            onBeforeTransformGroupsListener.onBeforeTransformGroups(emptyList())
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(null)

            // TEST: top-ranked unseen child is selected (not the summary)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listOf(group))
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(child1.key)

            // TEST: top-ranked entry is picked
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(solo1.key)

            // TEST: if top-ranked unseen is colorized, fall back to #2 ranked unseen
            solo1.setColorizedFgs(true)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(solo1.key)
            assertThatTopUnseenKey().isEqualTo(solo2.key)

            // TEST: no more colorized entries
            solo1.setColorizedFgs(false)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(solo1.key)

            // TEST: if the rank of solo1 is reduced, solo2 will be preferred
            solo1.modifyEntry { setRank(3) }
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(solo2.key)

            // TEST: switching to SHADE state will disable the entire selector
            statusBarState = StatusBarState.SHADE
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(null)

            // TEST: switching back to KEYGUARD re-enables the selector
            statusBarState = StatusBarState.KEYGUARD
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(solo2.key)

            // TEST: QS Expansion does not mark entries as seen
            setShadeAndQsExpansionThenWait(0f, 1f)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(solo2.key)

            // TEST: Shade expansion does mark entries as seen
            setShadeAndQsExpansionThenWait(1f, 0f)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(null)

            // TEST: Entries updated while shade is expanded are NOT marked unseen
            collectionListener.onEntryUpdated(solo1)
            collectionListener.onEntryUpdated(solo2)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(null)

            // TEST: Entries updated after shade is collapsed ARE marked unseen
            setShadeAndQsExpansionThenWait(0f, 0f)
            collectionListener.onEntryUpdated(solo1)
            collectionListener.onEntryUpdated(solo2)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(solo2.key)

            // TEST: low importance disqualifies the entry for top unseen
            solo2.modifyEntry { setImportance(IMPORTANCE_LOW) }
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopOngoingKey().isEqualTo(null)
            assertThatTopUnseenKey().isEqualTo(solo1.key)
        }
    }

    @Test
    fun topUnseenIdentifier_headsUpMarksSeen() {
        val solo1 = defaultEntryBuilder().setTag("solo1").setRank(1).build()
        val solo2 = defaultEntryBuilder().setTag("solo2").setRank(2).build()
        val listEntryList = listOf(solo1, solo2)
        val notificationEntryList = listOf(solo1, solo2)

        val hunRepo1 = solo1.fakeHeadsUpRowRepository()
        val hunRepo2 = solo2.fakeHeadsUpRowRepository()

        runCoordinatorTest {
            // All entries are added (and now unseen)
            notificationEntryList.forEach { collectionListener.onEntryAdded(it) }

            // TEST: top-ranked entry is picked
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopUnseenKey().isEqualTo(solo1.key)

            // TEST: heads up state and waiting isn't enough to be seen
            kosmos.headsUpNotificationRepository.orderedHeadsUpRows.value =
                listOf(hunRepo1, hunRepo2)
            testScheduler.advanceTimeBy(1.seconds)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopUnseenKey().isEqualTo(solo1.key)

            // TEST: even being pinned doesn't take effect immediately
            hunRepo1.isPinned.value = true
            testScheduler.advanceTimeBy(0.5.seconds)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopUnseenKey().isEqualTo(solo1.key)

            // TEST: after being pinned a full second, solo1 is seen
            testScheduler.advanceTimeBy(0.5.seconds)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopUnseenKey().isEqualTo(solo2.key)

            // TEST: repeat; being heads up and pinned for 1 second triggers seen
            kosmos.headsUpNotificationRepository.orderedHeadsUpRows.value = listOf(hunRepo2)
            hunRepo1.isPinned.value = false
            hunRepo2.isPinned.value = true
            testScheduler.advanceTimeBy(1.seconds)
            onBeforeTransformGroupsListener.onBeforeTransformGroups(listEntryList)
            assertThatTopUnseenKey().isEqualTo(null)
        }
    }

    private fun NotificationEntry.fakeHeadsUpRowRepository() =
        FakeHeadsUpRowRepository(key = key, elementKey = Any())

    private fun KeyguardCoordinatorTestScope.setShadeAndQsExpansionThenWait(
        shadeExpansion: Float,
        qsExpansion: Float
    ) {
        kosmos.shadeTestUtil.setShadeAndQsExpansion(shadeExpansion, qsExpansion)
        // The coordinator waits a fraction of a second for the shade expansion to stick.
        testScheduler.advanceTimeBy(1.seconds)
    }

    private fun defaultEntryBuilder() = NotificationEntryBuilder().setImportance(IMPORTANCE_DEFAULT)

    private fun runCoordinatorTest(testBlock: suspend KeyguardCoordinatorTestScope.() -> Unit) {
        kosmos.lockScreenMinimalismCoordinator.attach(notifPipeline)
        kosmos.testScope.runTest(dispatchTimeoutMs = 1.seconds.inWholeMilliseconds) {
            KeyguardCoordinatorTestScope(
                    kosmos.lockScreenMinimalismCoordinator,
                    kosmos.testScope,
                    kosmos.fakeSettings,
                )
                .testBlock()
        }
    }

    private inner class KeyguardCoordinatorTestScope(
        private val coordinator: LockScreenMinimalismCoordinator,
        private val scope: TestScope,
        private val fakeSettings: FakeSettings,
    ) : CoroutineScope by scope {
        fun assertThatTopOngoingKey(): StringSubject {
            return assertThat(
                kosmos.activeNotificationListRepository.topOngoingNotificationKey.value
            )
        }

        fun assertThatTopUnseenKey(): StringSubject {
            return assertThat(
                kosmos.activeNotificationListRepository.topUnseenNotificationKey.value
            )
        }

        val testScheduler: TestCoroutineScheduler
            get() = scope.testScheduler

        val promoter: NotifPromoter
            get() = coordinator.unseenNotifPromoter

        val topUnseenSectioner: NotifSectioner
            get() = coordinator.topUnseenSectioner

        val topOngoingSectioner: NotifSectioner
            get() = coordinator.topOngoingSectioner

        val onBeforeTransformGroupsListener: OnBeforeTransformGroupsListener =
            argumentCaptor { verify(notifPipeline).addOnBeforeTransformGroupsListener(capture()) }
                .lastValue

        val collectionListener: NotifCollectionListener =
            argumentCaptor { verify(notifPipeline).addCollectionListener(capture()) }.lastValue
    }

    companion object {

        private fun NotificationEntry.setColorizedFgs(colorized: Boolean) {
            sbn.notification.setColorizedFgs(colorized)
        }

        private fun Notification.setColorizedFgs(colorized: Boolean) {
            extras.putBoolean(Notification.EXTRA_COLORIZED, colorized)
            flags =
                if (colorized) {
                    flags or Notification.FLAG_FOREGROUND_SERVICE
                } else {
                    flags and Notification.FLAG_FOREGROUND_SERVICE.inv()
                }
        }
    }
}
