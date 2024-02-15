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

import android.app.Notification.GROUP_ALERT_ALL
import android.app.Notification.GROUP_ALERT_SUMMARY
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeTransformGroupsListener
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender.OnEndLifetimeExtensionCallback
import com.android.systemui.statusbar.notification.collection.provider.LaunchFullScreenIntentProvider
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider.FullScreenIntentDecision
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderWrapper.DecisionImpl
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderWrapper.FullScreenIntentDecisionImpl
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider
import com.android.systemui.statusbar.notification.row.NotifBindPipeline.BindCallback
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone
import com.android.systemui.statusbar.phone.NotificationGroupTestHelper
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.FakeSystemClock
import java.util.ArrayList
import java.util.function.Consumer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.clearInvocations
import org.mockito.BDDMockito.given
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class HeadsUpCoordinatorTest : SysuiTestCase() {
    private lateinit var coordinator: HeadsUpCoordinator

    // captured listeners and pluggables:
    private lateinit var collectionListener: NotifCollectionListener
    private lateinit var notifPromoter: NotifPromoter
    private lateinit var notifLifetimeExtender: NotifLifetimeExtender
    private lateinit var beforeTransformGroupsListener: OnBeforeTransformGroupsListener
    private lateinit var beforeFinalizeFilterListener: OnBeforeFinalizeFilterListener
    private lateinit var onHeadsUpChangedListener: OnHeadsUpChangedListener
    private lateinit var notifSectioner: NotifSectioner
    private lateinit var actionPressListener: Consumer<NotificationEntry>

    private val notifPipeline: NotifPipeline = mock()
    private val logger = HeadsUpCoordinatorLogger(logcatLogBuffer(), verbose = true)
    private val headsUpManager: HeadsUpManagerPhone = mock()
    private val headsUpViewBinder: HeadsUpViewBinder = mock()
    private val visualInterruptionDecisionProvider: VisualInterruptionDecisionProvider = mock()
    private val remoteInputManager: NotificationRemoteInputManager = mock()
    private val endLifetimeExtension: OnEndLifetimeExtensionCallback = mock()
    private val headerController: NodeController = mock()
    private val launchFullScreenIntentProvider: LaunchFullScreenIntentProvider = mock()
    private val flags: NotifPipelineFlags = mock()

    private lateinit var entry: NotificationEntry
    private lateinit var groupSummary: NotificationEntry
    private lateinit var groupPriority: NotificationEntry
    private lateinit var groupSibling1: NotificationEntry
    private lateinit var groupSibling2: NotificationEntry
    private lateinit var groupChild1: NotificationEntry
    private lateinit var groupChild2: NotificationEntry
    private lateinit var groupChild3: NotificationEntry
    private val systemClock = FakeSystemClock()
    private val executor = FakeExecutor(systemClock)
    private val huns: ArrayList<NotificationEntry> = ArrayList()
    private lateinit var helper: NotificationGroupTestHelper
    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        helper = NotificationGroupTestHelper(mContext)
        coordinator = HeadsUpCoordinator(
            logger,
            systemClock,
            headsUpManager,
            headsUpViewBinder,
            visualInterruptionDecisionProvider,
            remoteInputManager,
            launchFullScreenIntentProvider,
            flags,
            headerController,
            executor)
        coordinator.attach(notifPipeline)

        // capture arguments:
        collectionListener = withArgCaptor {
            verify(notifPipeline).addCollectionListener(capture())
        }
        notifPromoter = withArgCaptor {
            verify(notifPipeline).addPromoter(capture())
        }
        notifLifetimeExtender = withArgCaptor {
            verify(notifPipeline).addNotificationLifetimeExtender(capture())
        }
        beforeTransformGroupsListener = withArgCaptor {
            verify(notifPipeline).addOnBeforeTransformGroupsListener(capture())
        }
        beforeFinalizeFilterListener = withArgCaptor {
            verify(notifPipeline).addOnBeforeFinalizeFilterListener(capture())
        }
        onHeadsUpChangedListener = withArgCaptor {
            verify(headsUpManager).addListener(capture())
        }
        actionPressListener = withArgCaptor {
            verify(remoteInputManager).addActionPressListener(capture())
        }
        given(headsUpManager.allEntries).willAnswer { huns.stream() }
        given(headsUpManager.isHeadsUpEntry(anyString())).willAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            huns.any { entry -> entry.key == key }
        }
        given(headsUpManager.canRemoveImmediately(anyString())).willAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            !huns.any { entry -> entry.key == key }
        }
        whenever(headsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L)
        notifSectioner = coordinator.sectioner
        notifLifetimeExtender.setCallback(endLifetimeExtension)
        entry = NotificationEntryBuilder().build()
        // Same summary we can use for either set of children
        groupSummary = helper.createSummaryNotification(GROUP_ALERT_ALL, 0, "summary", 500)
        // One set of children with GROUP_ALERT_SUMMARY
        groupPriority = helper.createChildNotification(GROUP_ALERT_SUMMARY, 0, "priority", 400)
        groupSibling1 = helper.createChildNotification(GROUP_ALERT_SUMMARY, 1, "sibling", 300)
        groupSibling2 = helper.createChildNotification(GROUP_ALERT_SUMMARY, 2, "sibling", 200)
        // Another set of children with GROUP_ALERT_ALL
        groupChild1 = helper.createChildNotification(GROUP_ALERT_ALL, 1, "child", 350)
        groupChild2 = helper.createChildNotification(GROUP_ALERT_ALL, 2, "child", 250)
        groupChild3 = helper.createChildNotification(GROUP_ALERT_ALL, 3, "child", 150)

        // Set the default HUN decision
        setDefaultShouldHeadsUp(false)

        // Set the default FSI decision
        setDefaultShouldFullScreen(FullScreenIntentDecision.NO_FULL_SCREEN_INTENT)
    }

    @Test
    fun testCancelStickyNotification() {
        whenever(headsUpManager.isSticky(anyString())).thenReturn(true)
        addHUN(entry)
        whenever(headsUpManager.canRemoveImmediately(anyString())).thenReturn(false, true)
        whenever(headsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L, 0L)
        assertTrue(notifLifetimeExtender.maybeExtendLifetime(entry, 0))
        executor.advanceClockToLast()
        executor.runAllReady()
        verify(headsUpManager, times(0)).removeNotification(anyString(), eq(false))
        verify(headsUpManager, times(1)).removeNotification(anyString(), eq(true))
    }

    @Test
    fun testCancelAndReAddStickyNotification() {
        whenever(headsUpManager.isSticky(anyString())).thenReturn(true)
        addHUN(entry)
        whenever(headsUpManager.canRemoveImmediately(anyString())).thenReturn(false, true, false)
        whenever(headsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L)
        assertTrue(notifLifetimeExtender.maybeExtendLifetime(entry, 0))
        addHUN(entry)
        assertFalse(notifLifetimeExtender.maybeExtendLifetime(entry, 0))
        executor.advanceClockToLast()
        executor.runAllReady()
        assertTrue(notifLifetimeExtender.maybeExtendLifetime(entry, 0))
        verify(headsUpManager, times(0)).removeNotification(anyString(), eq(false))
        verify(headsUpManager, times(0)).removeNotification(anyString(), eq(true))
    }

    @Test
    fun hunNotRemovedWhenExtensionCancelled() {
        whenever(headsUpManager.isSticky(anyString())).thenReturn(true)
        addHUN(entry)
        whenever(headsUpManager.canRemoveImmediately(anyString())).thenReturn(false)
        whenever(headsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L)
        assertTrue(notifLifetimeExtender.maybeExtendLifetime(entry, 0))
        notifLifetimeExtender.cancelLifetimeExtension(entry)
        executor.advanceClockToLast()
        executor.runAllReady()
        verify(headsUpManager, times(0)).removeNotification(anyString(), any())
    }

    @Test
    fun actionPressCancelsExistingLifetimeExtension() {
        whenever(headsUpManager.isSticky(anyString())).thenReturn(true)
        addHUN(entry)

        whenever(headsUpManager.canRemoveImmediately(anyString())).thenReturn(false)
        whenever(headsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L)
        assertTrue(notifLifetimeExtender.maybeExtendLifetime(entry, /* reason = */ 0))

        actionPressListener.accept(entry)
        executor.runAllReady()
        verify(endLifetimeExtension, times(1)).onEndLifetimeExtension(notifLifetimeExtender, entry)

        collectionListener.onEntryRemoved(entry, /* reason = */ 0)
        verify(headsUpManager, times(1)).removeNotification(eq(entry.key), any())
    }

    @Test
    fun actionPressPreventsFutureLifetimeExtension() {
        whenever(headsUpManager.isSticky(anyString())).thenReturn(true)
        addHUN(entry)

        actionPressListener.accept(entry)
        verify(headsUpManager, times(1)).setUserActionMayIndirectlyRemove(entry)

        whenever(headsUpManager.canRemoveImmediately(anyString())).thenReturn(true)
        assertFalse(notifLifetimeExtender.maybeExtendLifetime(entry, 0))

        collectionListener.onEntryRemoved(entry, /* reason = */ 0)
        verify(headsUpManager, times(1)).removeNotification(eq(entry.key), any())
    }

    @Test
    fun testCancelUpdatedStickyNotification() {
        whenever(headsUpManager.isSticky(anyString())).thenReturn(true)
        addHUN(entry)
        whenever(headsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L, 500L)
        assertTrue(notifLifetimeExtender.maybeExtendLifetime(entry, 0))
        addHUN(entry)
        executor.advanceClockToLast()
        executor.runAllReady()
        verify(headsUpManager, times(0)).removeNotification(anyString(), eq(false))
        verify(headsUpManager, times(0)).removeNotification(anyString(), eq(true))
    }

    @Test
    fun testCancelNotification() {
        whenever(headsUpManager.isSticky(anyString())).thenReturn(false)
        addHUN(entry)
        whenever(headsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L, 500L)
        assertTrue(notifLifetimeExtender.maybeExtendLifetime(entry, 0))
        executor.advanceClockToLast()
        executor.runAllReady()
        verify(headsUpManager, times(1)).removeNotification(anyString(), eq(false))
        verify(headsUpManager, times(0)).removeNotification(anyString(), eq(true))
    }

    @Test
    fun testOnEntryAdded_shouldFullScreen() {
        setShouldFullScreen(entry, FullScreenIntentDecision.FSI_KEYGUARD_SHOWING)
        collectionListener.onEntryAdded(entry)
        verify(launchFullScreenIntentProvider).launchFullScreenIntent(entry)
    }

    @Test
    fun testOnEntryAdded_shouldNotFullScreen() {
        setShouldFullScreen(entry, FullScreenIntentDecision.NO_FULL_SCREEN_INTENT)
        collectionListener.onEntryAdded(entry)
        verify(launchFullScreenIntentProvider, never()).launchFullScreenIntent(any())
    }

    @Test
    fun testPromotesAddedHUN() {
        // GIVEN the current entry should heads up
        setShouldHeadsUp(entry, true)

        // WHEN the notification is added but not yet binding
        collectionListener.onEntryAdded(entry)
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(entry), any())

        // THEN only promote mEntry
        assertTrue(notifPromoter.shouldPromoteToTopLevel(entry))
    }

    @Test
    fun testPromotesBindingHUN() {
        // GIVEN the current entry should heads up
        setShouldHeadsUp(entry, true)

        // WHEN the notification started binding on the previous run
        collectionListener.onEntryAdded(entry)
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))
        verify(headsUpViewBinder).bindHeadsUpView(eq(entry), any())

        // THEN only promote mEntry
        assertTrue(notifPromoter.shouldPromoteToTopLevel(entry))
    }

    @Test
    fun testPromotesCurrentHUN() {
        // GIVEN the current HUN is set to mEntry
        addHUN(entry)

        // THEN only promote the current HUN, mEntry
        assertTrue(notifPromoter.shouldPromoteToTopLevel(entry))
        assertFalse(notifPromoter.shouldPromoteToTopLevel(NotificationEntryBuilder()
            .setPkg("test-package2")
            .build()))
    }

    @Test
    fun testIncludeInSectionCurrentHUN() {
        // GIVEN the current HUN is set to mEntry
        addHUN(entry)

        // THEN only section the current HUN, mEntry
        assertTrue(notifSectioner.isInSection(entry))
        assertFalse(notifSectioner.isInSection(NotificationEntryBuilder()
            .setPkg("test-package")
            .build()))
    }

    @Test
    fun testLifetimeExtendsCurrentHUN() {
        // GIVEN there is a HUN, mEntry
        addHUN(entry)

        // THEN only the current HUN, mEntry, should be lifetimeExtended
        assertTrue(notifLifetimeExtender.maybeExtendLifetime(entry, /* cancellationReason */ 0))
        assertFalse(notifLifetimeExtender.maybeExtendLifetime(
            NotificationEntryBuilder()
                .setPkg("test-package")
                .build(), /* cancellationReason */ 0))
    }

    @Test
    fun testShowHUNOnInflationFinished() {
        // WHEN a notification should HUN and its inflation is finished
        setShouldHeadsUp(entry, true)

        collectionListener.onEntryAdded(entry)
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))
        verify(headsUpManager, never()).showNotification(entry)
        withArgCaptor<BindCallback> {
            verify(headsUpViewBinder).bindHeadsUpView(eq(entry), capture())
        }.onBindFinished(entry)

        // THEN we tell the HeadsUpManager to show the notification
        verify(headsUpManager).showNotification(entry)
    }

    @Test
    fun testNoHUNOnInflationFinished() {
        // WHEN a notification shouldn't HUN and its inflation is finished
        setShouldHeadsUp(entry, false)
        collectionListener.onEntryAdded(entry)
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // THEN we never bind the heads up view or tell HeadsUpManager to show the notification
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(entry), any())
        verify(headsUpManager, never()).showNotification(entry)
    }

    @Test
    fun testOnEntryUpdated_toAlert() {
        // GIVEN that an entry is posted that should not heads up
        setShouldHeadsUp(entry, false)
        collectionListener.onEntryAdded(entry)

        // WHEN it's updated to heads up
        setShouldHeadsUp(entry)
        collectionListener.onEntryUpdated(entry)
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // THEN the notification alerts
        finishBind(entry)
        verify(headsUpManager).showNotification(entry)
    }

    @Test
    fun testOnEntryUpdated_toNotAlert() {
        // GIVEN that an entry is posted that should heads up
        setShouldHeadsUp(entry)
        collectionListener.onEntryAdded(entry)

        // WHEN it's updated to not heads up
        setShouldHeadsUp(entry, false)
        collectionListener.onEntryUpdated(entry)
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // THEN the notification is never bound or shown
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        verify(headsUpManager, never()).showNotification(any())
    }

    @Test
    fun testOnEntryRemovedRemovesHeadsUpNotification() {
        // GIVEN the current HUN is mEntry
        addHUN(entry)

        // WHEN mEntry is removed from the notification collection
        collectionListener.onEntryRemoved(entry, /* cancellation reason */ 0)
        whenever(remoteInputManager.isSpinning(any())).thenReturn(false)

        // THEN heads up manager should remove the entry
        verify(headsUpManager).removeNotification(entry.key, false)
    }

    private fun addHUN(entry: NotificationEntry) {
        huns.add(entry)
        whenever(headsUpManager.getTopEntry()).thenReturn(entry)
        onHeadsUpChangedListener.onHeadsUpStateChanged(entry, true)
        notifLifetimeExtender.cancelLifetimeExtension(entry)
    }

    @Test
    fun testTransferIsolatedChildAlert_withGroupAlertSummary() {
        setShouldHeadsUp(groupSummary)
        whenever(notifPipeline.allNotifs).thenReturn(listOf(groupSummary, groupSibling1))

        collectionListener.onEntryAdded(groupSummary)
        collectionListener.onEntryAdded(groupSibling1)
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(groupSibling1))
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupSibling1))

        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSummary), any())
        finishBind(groupSibling1)

        verify(headsUpManager, never()).showNotification(groupSummary)
        verify(headsUpManager).showNotification(groupSibling1)

        // In addition make sure we have explicitly marked the summary as having interrupted due
        // to the alert being transferred
        assertTrue(groupSummary.hasInterrupted())
    }

    @Test
    fun testTransferIsolatedChildAlert_withGroupAlertAll() {
        setShouldHeadsUp(groupSummary)
        whenever(notifPipeline.allNotifs).thenReturn(listOf(groupSummary, groupChild1))

        collectionListener.onEntryAdded(groupSummary)
        collectionListener.onEntryAdded(groupChild1)
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(groupChild1))
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupChild1))

        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSummary), any())
        finishBind(groupChild1)

        verify(headsUpManager, never()).showNotification(groupSummary)
        verify(headsUpManager).showNotification(groupChild1)
        assertTrue(groupSummary.hasInterrupted())
    }

    @Test
    fun testTransferTwoIsolatedChildAlert_withGroupAlertSummary() {
        // WHEN a notification should HUN and its inflation is finished
        setShouldHeadsUp(groupSummary)
        whenever(notifPipeline.allNotifs)
            .thenReturn(listOf(groupSummary, groupSibling1, groupSibling2, groupPriority))

        collectionListener.onEntryAdded(groupSummary)
        collectionListener.onEntryAdded(groupSibling1)
        collectionListener.onEntryAdded(groupSibling2)
        val entryList = listOf(groupSibling1, groupSibling2)
        beforeTransformGroupsListener.onBeforeTransformGroups(entryList)
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(entryList)

        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSummary), any())
        finishBind(groupSibling1)
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSibling2), any())

        // THEN we tell the HeadsUpManager to show the notification
        verify(headsUpManager, never()).showNotification(groupSummary)
        verify(headsUpManager).showNotification(groupSibling1)
        verify(headsUpManager, never()).showNotification(groupSibling2)
        assertTrue(groupSummary.hasInterrupted())
    }

    @Test
    fun testTransferTwoIsolatedChildAlert_withGroupAlertAll() {
        // WHEN a notification should HUN and its inflation is finished
        setShouldHeadsUp(groupSummary)
        whenever(notifPipeline.allNotifs)
            .thenReturn(listOf(groupSummary, groupChild1, groupChild2, groupPriority))

        collectionListener.onEntryAdded(groupSummary)
        collectionListener.onEntryAdded(groupChild1)
        collectionListener.onEntryAdded(groupChild2)
        val entryList = listOf(groupChild1, groupChild2)
        beforeTransformGroupsListener.onBeforeTransformGroups(entryList)
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(entryList)

        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSummary), any())
        finishBind(groupChild1)
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupChild2), any())

        // THEN we tell the HeadsUpManager to show the notification
        verify(headsUpManager, never()).showNotification(groupSummary)
        verify(headsUpManager).showNotification(groupChild1)
        verify(headsUpManager, never()).showNotification(groupChild2)
        assertTrue(groupSummary.hasInterrupted())
    }

    @Test
    fun testTransferToPriorityOnAddWithTwoSiblings() {
        // WHEN a notification should HUN and its inflation is finished
        setShouldHeadsUp(groupSummary)
        whenever(notifPipeline.allNotifs)
            .thenReturn(listOf(groupSummary, groupSibling1, groupSibling2, groupPriority))

        collectionListener.onEntryAdded(groupSummary)
        collectionListener.onEntryAdded(groupPriority)
        collectionListener.onEntryAdded(groupSibling1)
        collectionListener.onEntryAdded(groupSibling2)

        val beforeTransformGroup = GroupEntryBuilder()
            .setSummary(groupSummary)
            .setChildren(listOf(groupSibling1, groupPriority, groupSibling2))
            .build()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(beforeTransformGroup))
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())

        val afterTransformGroup = GroupEntryBuilder()
            .setSummary(groupSummary)
            .setChildren(listOf(groupSibling1, groupSibling2))
            .build()
        beforeFinalizeFilterListener
            .onBeforeFinalizeFilter(listOf(groupPriority, afterTransformGroup))

        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSummary), any())
        finishBind(groupPriority)
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSibling1), any())
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSibling2), any())

        // THEN we tell the HeadsUpManager to show the notification
        verify(headsUpManager, never()).showNotification(groupSummary)
        verify(headsUpManager).showNotification(groupPriority)
        verify(headsUpManager, never()).showNotification(groupSibling1)
        verify(headsUpManager, never()).showNotification(groupSibling2)
        assertTrue(groupSummary.hasInterrupted())
    }

    @Test
    fun testTransferToPriorityOnUpdateWithTwoSiblings() {
        setShouldHeadsUp(groupSummary)
        whenever(notifPipeline.allNotifs)
            .thenReturn(listOf(groupSummary, groupSibling1, groupSibling2, groupPriority))

        collectionListener.onEntryUpdated(groupSummary)
        collectionListener.onEntryUpdated(groupPriority)
        collectionListener.onEntryUpdated(groupSibling1)
        collectionListener.onEntryUpdated(groupSibling2)

        val beforeTransformGroup = GroupEntryBuilder()
            .setSummary(groupSummary)
            .setChildren(listOf(groupSibling1, groupPriority, groupSibling2))
            .build()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(beforeTransformGroup))
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())

        val afterTransformGroup = GroupEntryBuilder()
            .setSummary(groupSummary)
            .setChildren(listOf(groupSibling1, groupSibling2))
            .build()
        beforeFinalizeFilterListener
            .onBeforeFinalizeFilter(listOf(groupPriority, afterTransformGroup))

        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSummary), any())
        finishBind(groupPriority)
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSibling1), any())
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSibling2), any())

        verify(headsUpManager, never()).showNotification(groupSummary)
        verify(headsUpManager).showNotification(groupPriority)
        verify(headsUpManager, never()).showNotification(groupSibling1)
        verify(headsUpManager, never()).showNotification(groupSibling2)
        assertTrue(groupSummary.hasInterrupted())
    }

    @Test
    fun testTransferToPriorityOnUpdateWithTwoNonUpdatedSiblings() {
        setShouldHeadsUp(groupSummary)
        whenever(notifPipeline.allNotifs)
            .thenReturn(listOf(groupSummary, groupSibling1, groupSibling2, groupPriority))

        collectionListener.onEntryUpdated(groupSummary)
        collectionListener.onEntryUpdated(groupPriority)

        val beforeTransformGroup = GroupEntryBuilder()
            .setSummary(groupSummary)
            .setChildren(listOf(groupSibling1, groupPriority, groupSibling2))
            .build()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(beforeTransformGroup))
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())

        val afterTransformGroup = GroupEntryBuilder()
            .setSummary(groupSummary)
            .setChildren(listOf(groupSibling1, groupSibling2))
            .build()
        beforeFinalizeFilterListener
            .onBeforeFinalizeFilter(listOf(groupPriority, afterTransformGroup))

        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSummary), any())
        finishBind(groupPriority)
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSibling1), any())
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSibling2), any())

        verify(headsUpManager, never()).showNotification(groupSummary)
        verify(headsUpManager).showNotification(groupPriority)
        verify(headsUpManager, never()).showNotification(groupSibling1)
        verify(headsUpManager, never()).showNotification(groupSibling2)
        assertTrue(groupSummary.hasInterrupted())
    }

    @Test
    fun testNoTransferToPriorityOnUpdateOfTwoSiblings() {
        setShouldHeadsUp(groupSummary)
        whenever(notifPipeline.allNotifs)
            .thenReturn(listOf(groupSummary, groupSibling1, groupSibling2, groupPriority))

        collectionListener.onEntryUpdated(groupSummary)
        collectionListener.onEntryUpdated(groupSibling1)
        collectionListener.onEntryUpdated(groupSibling2)

        val beforeTransformGroup = GroupEntryBuilder()
            .setSummary(groupSummary)
            .setChildren(listOf(groupSibling1, groupPriority, groupSibling2))
            .build()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(beforeTransformGroup))
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())

        val afterTransformGroup = GroupEntryBuilder()
            .setSummary(groupSummary)
            .setChildren(listOf(groupSibling1, groupSibling2))
            .build()
        beforeFinalizeFilterListener
            .onBeforeFinalizeFilter(listOf(groupPriority, afterTransformGroup))

        finishBind(groupSummary)
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupPriority), any())
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSibling1), any())
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSibling2), any())

        verify(headsUpManager).showNotification(groupSummary)
        verify(headsUpManager, never()).showNotification(groupPriority)
        verify(headsUpManager, never()).showNotification(groupSibling1)
        verify(headsUpManager, never()).showNotification(groupSibling2)
    }

    @Test
    fun testNoTransferTwoChildAlert_withGroupAlertSummary() {
        setShouldHeadsUp(groupSummary)
        whenever(notifPipeline.allNotifs)
            .thenReturn(listOf(groupSummary, groupSibling1, groupSibling2))

        collectionListener.onEntryAdded(groupSummary)
        collectionListener.onEntryAdded(groupSibling1)
        collectionListener.onEntryAdded(groupSibling2)
        val groupEntry = GroupEntryBuilder()
            .setSummary(groupSummary)
            .setChildren(listOf(groupSibling1, groupSibling2))
            .build()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(groupEntry))
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))

        finishBind(groupSummary)
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSibling1), any())
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSibling2), any())

        verify(headsUpManager).showNotification(groupSummary)
        verify(headsUpManager, never()).showNotification(groupSibling1)
        verify(headsUpManager, never()).showNotification(groupSibling2)
    }

    @Test
    fun testNoTransferTwoChildAlert_withGroupAlertAll() {
        setShouldHeadsUp(groupSummary)
        whenever(notifPipeline.allNotifs)
            .thenReturn(listOf(groupSummary, groupChild1, groupChild2))

        collectionListener.onEntryAdded(groupSummary)
        collectionListener.onEntryAdded(groupChild1)
        collectionListener.onEntryAdded(groupChild2)
        val groupEntry = GroupEntryBuilder()
            .setSummary(groupSummary)
            .setChildren(listOf(groupChild1, groupChild2))
            .build()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(groupEntry))
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))

        finishBind(groupSummary)
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupChild1), any())
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupChild2), any())

        verify(headsUpManager).showNotification(groupSummary)
        verify(headsUpManager, never()).showNotification(groupChild1)
        verify(headsUpManager, never()).showNotification(groupChild2)
    }

    @Test
    fun testNoTransfer_groupSummaryNotAlerting() {
        // When we have a group where the summary should not alert and exactly one child should
        // alert, we should never mark the group summary as interrupted (because it doesn't).
        setShouldHeadsUp(groupSummary, false)
        setShouldHeadsUp(groupChild1, true)
        setShouldHeadsUp(groupChild2, false)

        collectionListener.onEntryAdded(groupSummary)
        collectionListener.onEntryAdded(groupChild1)
        collectionListener.onEntryAdded(groupChild2)
        val groupEntry = GroupEntryBuilder()
            .setSummary(groupSummary)
            .setChildren(listOf(groupChild1, groupChild2))
            .build()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(groupEntry))
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))

        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupSummary), any())
        finishBind(groupChild1)
        verify(headsUpViewBinder, never()).bindHeadsUpView(eq(groupChild2), any())

        verify(headsUpManager, never()).showNotification(groupSummary)
        verify(headsUpManager).showNotification(groupChild1)
        verify(headsUpManager, never()).showNotification(groupChild2)
        assertFalse(groupSummary.hasInterrupted())
    }

    @Test
    fun testOnRankingApplied_newEntryShouldAlert() {
        // GIVEN that mEntry has never interrupted in the past, and now should
        // and is new enough to do so
        assertFalse(entry.hasInterrupted())
        coordinator.setUpdateTime(entry, systemClock.currentTimeMillis())
        setShouldHeadsUp(entry)
        whenever(notifPipeline.allNotifs).thenReturn(listOf(entry))

        // WHEN a ranking applied update occurs
        collectionListener.onRankingApplied()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // THEN the notification is shown
        finishBind(entry)
        verify(headsUpManager).showNotification(entry)
    }

    @Test
    fun testOnRankingApplied_alreadyAlertedEntryShouldNotAlertAgain() {
        // GIVEN that mEntry has alerted in the past, even if it's new
        entry.setInterruption()
        coordinator.setUpdateTime(entry, systemClock.currentTimeMillis())
        setShouldHeadsUp(entry)
        whenever(notifPipeline.allNotifs).thenReturn(listOf(entry))

        // WHEN a ranking applied update occurs
        collectionListener.onRankingApplied()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // THEN the notification is never bound or shown
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        verify(headsUpManager, never()).showNotification(any())
    }

    @Test
    fun testOnRankingApplied_entryUpdatedToHun() {
        // GIVEN that mEntry is added in a state where it should not HUN
        setShouldHeadsUp(entry, false)
        collectionListener.onEntryAdded(entry)

        // and it is then updated such that it should now HUN
        setShouldHeadsUp(entry)
        whenever(notifPipeline.allNotifs).thenReturn(listOf(entry))

        // WHEN a ranking applied update occurs
        collectionListener.onRankingApplied()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // THEN the notification is shown
        finishBind(entry)
        verify(headsUpManager).showNotification(entry)
    }

    @Test
    fun testOnRankingApplied_entryUpdatedButTooOld() {
        // GIVEN that mEntry is added in a state where it should not HUN
        setShouldHeadsUp(entry, false)
        collectionListener.onEntryAdded(entry)

        // and it was actually added 10s ago
        coordinator.setUpdateTime(entry, systemClock.currentTimeMillis() - 10000)

        // WHEN it is updated to HUN and then a ranking update occurs
        setShouldHeadsUp(entry)
        whenever(notifPipeline.allNotifs).thenReturn(listOf(entry))
        collectionListener.onRankingApplied()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // THEN the notification is never bound or shown
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        verify(headsUpManager, never()).showNotification(any())
    }

    @Test
    fun onEntryAdded_whenLaunchingFSI_doesLogDecision() {
        // GIVEN A new notification can FSI
        setShouldFullScreen(entry, FullScreenIntentDecision.FSI_DEVICE_NOT_INTERACTIVE)
        collectionListener.onEntryAdded(entry)

        verify(launchFullScreenIntentProvider).launchFullScreenIntent(entry)
        verifyLoggedFullScreenIntentDecision(
            entry,
            FullScreenIntentDecision.FSI_DEVICE_NOT_INTERACTIVE
        )
    }

    @Test
    fun onEntryAdded_whenNotLaunchingFSI_doesLogDecision() {
        // GIVEN A new notification can't FSI
        setShouldFullScreen(entry, FullScreenIntentDecision.NO_FULL_SCREEN_INTENT)
        collectionListener.onEntryAdded(entry)

        verify(launchFullScreenIntentProvider, never()).launchFullScreenIntent(any())
        verifyLoggedFullScreenIntentDecision(entry, FullScreenIntentDecision.NO_FULL_SCREEN_INTENT)
    }

    @Test
    fun onEntryAdded_whenNotLaunchingFSIBecauseOfDnd_doesLogDecision() {
        // GIVEN A new notification can't FSI because of DND
        setShouldFullScreen(entry, FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND)
        collectionListener.onEntryAdded(entry)

        verify(launchFullScreenIntentProvider, never()).launchFullScreenIntent(any())
        verifyLoggedFullScreenIntentDecision(
            entry,
            FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND
        )
    }

    @Test
    fun testOnRankingApplied_updateToFullScreen() {
        // GIVEN that mEntry was previously suppressed from full-screen only by DND
        setShouldFullScreen(entry, FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND)
        collectionListener.onEntryAdded(entry)

        // at this point, it should not have full screened, but should have logged
        verify(launchFullScreenIntentProvider, never()).launchFullScreenIntent(any())
        verifyLoggedFullScreenIntentDecision(
            entry,
            FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND
        )
        clearInterruptionProviderInvocations()

        // and it is then updated to allow full screen AND HUN
        setShouldFullScreen(entry, FullScreenIntentDecision.FSI_DEVICE_NOT_INTERACTIVE)
        setShouldHeadsUp(entry)
        whenever(notifPipeline.allNotifs).thenReturn(listOf(entry))
        collectionListener.onRankingApplied()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // THEN it should full screen and log but it should NOT HUN
        verify(launchFullScreenIntentProvider).launchFullScreenIntent(entry)
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        verify(headsUpManager, never()).showNotification(any())
        verifyLoggedFullScreenIntentDecision(
            entry,
            FullScreenIntentDecision.FSI_DEVICE_NOT_INTERACTIVE
        )
        clearInterruptionProviderInvocations()

        // WHEN ranking updates again and the pipeline reruns
        clearInvocations(launchFullScreenIntentProvider)
        collectionListener.onRankingApplied()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // VERIFY that the FSI does not launch again or log
        verify(launchFullScreenIntentProvider, never()).launchFullScreenIntent(any())
        verifyNoFullScreenIntentDecisionLogged()
    }

    @Test
    fun testOnRankingApplied_withOnlyDndSuppressionAllowsFsiLater() {
        // GIVEN that mEntry was previously suppressed from full-screen only by DND
        setShouldFullScreen(entry, FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND)
        collectionListener.onEntryAdded(entry)

        // at this point, it should not have full screened, but should have logged
        verify(launchFullScreenIntentProvider, never()).launchFullScreenIntent(any())
        verifyLoggedFullScreenIntentDecision(
            entry,
            FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND
        )
        clearInterruptionProviderInvocations()

        // ranking is applied with only DND blocking FSI
        setShouldFullScreen(entry, FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND)
        collectionListener.onRankingApplied()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // THEN it should still not yet full screen or HUN
        verify(launchFullScreenIntentProvider, never()).launchFullScreenIntent(any())
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        verify(headsUpManager, never()).showNotification(any())

        // Same decision as before; is not logged
        verifyNoFullScreenIntentDecisionLogged()
        clearInterruptionProviderInvocations()

        // and it is then updated to allow full screen AND HUN
        setShouldFullScreen(entry, FullScreenIntentDecision.FSI_DEVICE_NOT_INTERACTIVE)
        setShouldHeadsUp(entry)
        whenever(notifPipeline.allNotifs).thenReturn(listOf(entry))
        collectionListener.onRankingApplied()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // THEN it should full screen and log but it should NOT HUN
        verify(launchFullScreenIntentProvider).launchFullScreenIntent(entry)
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        verify(headsUpManager, never()).showNotification(any())
        verifyLoggedFullScreenIntentDecision(
            entry,
            FullScreenIntentDecision.FSI_DEVICE_NOT_INTERACTIVE
        )
        clearInterruptionProviderInvocations()
    }

    @Test
    fun testOnRankingApplied_newNonFullScreenAnswerInvalidatesCandidate() {
        // GIVEN that mEntry was previously suppressed from full-screen only by DND
        whenever(notifPipeline.allNotifs).thenReturn(listOf(entry))
        setShouldFullScreen(entry, FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND)
        collectionListener.onEntryAdded(entry)

        // at this point, it should not have full screened
        verify(launchFullScreenIntentProvider, never()).launchFullScreenIntent(entry)

        // now some other condition blocks FSI in addition to DND
        setShouldFullScreen(entry, FullScreenIntentDecision.NO_FSI_SUPPRESSED_BY_DND)
        collectionListener.onRankingApplied()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // THEN it should NOT full screen or HUN
        verify(launchFullScreenIntentProvider, never()).launchFullScreenIntent(any())
        verify(headsUpViewBinder, never()).bindHeadsUpView(any(), any())
        verify(headsUpManager, never()).showNotification(any())

        // NOW the DND logic changes and FSI and HUN are available
        clearInvocations(launchFullScreenIntentProvider)
        setShouldFullScreen(entry, FullScreenIntentDecision.FSI_DEVICE_NOT_INTERACTIVE)
        setShouldHeadsUp(entry)
        collectionListener.onRankingApplied()
        beforeTransformGroupsListener.onBeforeTransformGroups(listOf(entry))
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry))

        // VERIFY that the FSI didn't happen, but that we do HUN
        verify(launchFullScreenIntentProvider, never()).launchFullScreenIntent(any())
        finishBind(entry)
        verify(headsUpManager).showNotification(entry)
    }

    @Test
    fun testOnRankingApplied_noFSIWhenAlsoSuppressedForOtherReasons() {
        // GIVEN that mEntry is suppressed by DND (functionally), but not *only* DND
        setShouldFullScreen(entry, FullScreenIntentDecision.NO_FSI_SUPPRESSED_BY_DND)
        collectionListener.onEntryAdded(entry)

        // and it is updated to full screen later
        setShouldFullScreen(entry, FullScreenIntentDecision.FSI_DEVICE_NOT_INTERACTIVE)
        collectionListener.onRankingApplied()

        // THEN it should still not full screen because something else was blocking it before
        verify(launchFullScreenIntentProvider, never()).launchFullScreenIntent(entry)
    }

    @Test
    fun testOnRankingApplied_noFSIWhenTooOld() {
        // GIVEN that mEntry is suppressed only by DND
        setShouldFullScreen(entry, FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND)
        collectionListener.onEntryAdded(entry)

        // but it's >10s old
        coordinator.addForFSIReconsideration(entry, systemClock.currentTimeMillis() - 10000)

        // and it is updated to full screen later
        setShouldFullScreen(entry, FullScreenIntentDecision.FSI_KEYGUARD_SHOWING)
        collectionListener.onRankingApplied()

        // THEN it should still not full screen because it's too old
        verify(launchFullScreenIntentProvider, never()).launchFullScreenIntent(entry)
    }

    private fun setDefaultShouldHeadsUp(should: Boolean) {
        whenever(visualInterruptionDecisionProvider.makeAndLogHeadsUpDecision(any()))
            .thenReturn(DecisionImpl.of(should))
        whenever(visualInterruptionDecisionProvider.makeUnloggedHeadsUpDecision(any()))
            .thenReturn(DecisionImpl.of(should))
    }

    private fun setShouldHeadsUp(entry: NotificationEntry, should: Boolean = true) {
        whenever(visualInterruptionDecisionProvider.makeAndLogHeadsUpDecision(entry))
            .thenReturn(DecisionImpl.of(should))
        whenever(visualInterruptionDecisionProvider.makeUnloggedHeadsUpDecision(entry))
            .thenReturn(DecisionImpl.of(should))
    }

    private fun setDefaultShouldFullScreen(
        originalDecision: FullScreenIntentDecision
    ) {
        val provider = visualInterruptionDecisionProvider
        whenever(provider.makeUnloggedFullScreenIntentDecision(any())).thenAnswer {
            val entry: NotificationEntry = it.getArgument(0)
            FullScreenIntentDecisionImpl(entry, originalDecision)
        }
    }

    private fun setShouldFullScreen(
        entry: NotificationEntry,
        originalDecision: FullScreenIntentDecision
    ) {
        whenever(
            visualInterruptionDecisionProvider.makeUnloggedFullScreenIntentDecision(entry)
        ).thenAnswer {
            FullScreenIntentDecisionImpl(entry, originalDecision)
        }
    }

    private fun verifyLoggedFullScreenIntentDecision(
        entry: NotificationEntry,
        originalDecision: FullScreenIntentDecision
    ) {
        val decision = withArgCaptor {
            verify(visualInterruptionDecisionProvider).logFullScreenIntentDecision(capture())
        }
        check(decision is FullScreenIntentDecisionImpl)
        assertEquals(entry, decision.originalEntry)
        assertEquals(originalDecision, decision.originalDecision)
    }

    private fun verifyNoFullScreenIntentDecisionLogged() {
        verify(visualInterruptionDecisionProvider, never()).logFullScreenIntentDecision(any())
    }

    private fun clearInterruptionProviderInvocations() {
        clearInvocations(visualInterruptionDecisionProvider)
    }

    private fun finishBind(entry: NotificationEntry) {
        verify(headsUpManager, never()).showNotification(entry)
        withArgCaptor<BindCallback> {
            verify(headsUpViewBinder).bindHeadsUpView(eq(entry), capture())
        }.onBindFinished(entry)
    }
}
