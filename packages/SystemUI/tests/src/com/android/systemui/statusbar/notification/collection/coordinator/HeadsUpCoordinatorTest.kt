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
import com.android.systemui.dump.logcatLogBuffer
import com.android.systemui.statusbar.NotificationRemoteInputManager
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
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider
import com.android.systemui.statusbar.notification.row.NotifBindPipeline.BindCallback
import com.android.systemui.statusbar.phone.NotificationGroupTestHelper
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.ArrayList
import java.util.function.Consumer
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class HeadsUpCoordinatorTest : SysuiTestCase() {
    private lateinit var mCoordinator: HeadsUpCoordinator

    // captured listeners and pluggables:
    private lateinit var mCollectionListener: NotifCollectionListener
    private lateinit var mNotifPromoter: NotifPromoter
    private lateinit var mNotifLifetimeExtender: NotifLifetimeExtender
    private lateinit var mBeforeTransformGroupsListener: OnBeforeTransformGroupsListener
    private lateinit var mBeforeFinalizeFilterListener: OnBeforeFinalizeFilterListener
    private lateinit var mOnHeadsUpChangedListener: OnHeadsUpChangedListener
    private lateinit var mNotifSectioner: NotifSectioner
    private lateinit var mActionPressListener: Consumer<NotificationEntry>

    private val mNotifPipeline: NotifPipeline = mock()
    private val mLogger = HeadsUpCoordinatorLogger(logcatLogBuffer(), verbose = true)
    private val mHeadsUpManager: HeadsUpManager = mock()
    private val mHeadsUpViewBinder: HeadsUpViewBinder = mock()
    private val mNotificationInterruptStateProvider: NotificationInterruptStateProvider = mock()
    private val mRemoteInputManager: NotificationRemoteInputManager = mock()
    private val mEndLifetimeExtension: OnEndLifetimeExtensionCallback = mock()
    private val mHeaderController: NodeController = mock()

    private lateinit var mEntry: NotificationEntry
    private lateinit var mGroupSummary: NotificationEntry
    private lateinit var mGroupPriority: NotificationEntry
    private lateinit var mGroupSibling1: NotificationEntry
    private lateinit var mGroupSibling2: NotificationEntry
    private lateinit var mGroupChild1: NotificationEntry
    private lateinit var mGroupChild2: NotificationEntry
    private lateinit var mGroupChild3: NotificationEntry
    private val mSystemClock = FakeSystemClock()
    private val mExecutor = FakeExecutor(mSystemClock)
    private val mHuns: ArrayList<NotificationEntry> = ArrayList()
    private lateinit var mHelper: NotificationGroupTestHelper
    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mHelper = NotificationGroupTestHelper(mContext)
        mCoordinator = HeadsUpCoordinator(
            mLogger,
            mSystemClock,
            mHeadsUpManager,
            mHeadsUpViewBinder,
            mNotificationInterruptStateProvider,
            mRemoteInputManager,
            mHeaderController,
            mExecutor)
        mCoordinator.attach(mNotifPipeline)

        // capture arguments:
        mCollectionListener = withArgCaptor {
            verify(mNotifPipeline).addCollectionListener(capture())
        }
        mNotifPromoter = withArgCaptor {
            verify(mNotifPipeline).addPromoter(capture())
        }
        mNotifLifetimeExtender = withArgCaptor {
            verify(mNotifPipeline).addNotificationLifetimeExtender(capture())
        }
        mBeforeTransformGroupsListener = withArgCaptor {
            verify(mNotifPipeline).addOnBeforeTransformGroupsListener(capture())
        }
        mBeforeFinalizeFilterListener = withArgCaptor {
            verify(mNotifPipeline).addOnBeforeFinalizeFilterListener(capture())
        }
        mOnHeadsUpChangedListener = withArgCaptor {
            verify(mHeadsUpManager).addListener(capture())
        }
        mActionPressListener = withArgCaptor {
            verify(mRemoteInputManager).addActionPressListener(capture())
        }
        given(mHeadsUpManager.allEntries).willAnswer { mHuns.stream() }
        given(mHeadsUpManager.isAlerting(anyString())).willAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            mHuns.any { entry -> entry.key == key }
        }
        given(mHeadsUpManager.canRemoveImmediately(anyString())).willAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            !mHuns.any { entry -> entry.key == key }
        }
        whenever(mHeadsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L)
        mNotifSectioner = mCoordinator.sectioner
        mNotifLifetimeExtender.setCallback(mEndLifetimeExtension)
        mEntry = NotificationEntryBuilder().build()
        // Same summary we can use for either set of children
        mGroupSummary = mHelper.createSummaryNotification(GROUP_ALERT_ALL, 0, "summary", 500)
        // One set of children with GROUP_ALERT_SUMMARY
        mGroupPriority = mHelper.createChildNotification(GROUP_ALERT_SUMMARY, 0, "priority", 400)
        mGroupSibling1 = mHelper.createChildNotification(GROUP_ALERT_SUMMARY, 1, "sibling", 300)
        mGroupSibling2 = mHelper.createChildNotification(GROUP_ALERT_SUMMARY, 2, "sibling", 200)
        // Another set of children with GROUP_ALERT_ALL
        mGroupChild1 = mHelper.createChildNotification(GROUP_ALERT_ALL, 1, "child", 350)
        mGroupChild2 = mHelper.createChildNotification(GROUP_ALERT_ALL, 2, "child", 250)
        mGroupChild3 = mHelper.createChildNotification(GROUP_ALERT_ALL, 3, "child", 150)
    }

    @Test
    fun testCancelStickyNotification() {
        whenever(mHeadsUpManager.isSticky(anyString())).thenReturn(true)
        addHUN(mEntry)
        whenever(mHeadsUpManager.canRemoveImmediately(anyString())).thenReturn(false, true)
        whenever(mHeadsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L, 0L)
        assertTrue(mNotifLifetimeExtender.maybeExtendLifetime(mEntry, 0))
        mExecutor.advanceClockToLast()
        mExecutor.runAllReady()
        verify(mHeadsUpManager, times(0)).removeNotification(anyString(), eq(false))
        verify(mHeadsUpManager, times(1)).removeNotification(anyString(), eq(true))
    }

    @Test
    fun testCancelAndReAddStickyNotification() {
        whenever(mHeadsUpManager.isSticky(anyString())).thenReturn(true)
        addHUN(mEntry)
        whenever(mHeadsUpManager.canRemoveImmediately(anyString())).thenReturn(false, true, false)
        whenever(mHeadsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L)
        assertTrue(mNotifLifetimeExtender.maybeExtendLifetime(mEntry, 0))
        addHUN(mEntry)
        assertFalse(mNotifLifetimeExtender.maybeExtendLifetime(mEntry, 0))
        mExecutor.advanceClockToLast()
        mExecutor.runAllReady()
        assertTrue(mNotifLifetimeExtender.maybeExtendLifetime(mEntry, 0))
        verify(mHeadsUpManager, times(0)).removeNotification(anyString(), eq(false))
        verify(mHeadsUpManager, times(0)).removeNotification(anyString(), eq(true))
    }

    @Test
    fun hunNotRemovedWhenExtensionCancelled() {
        whenever(mHeadsUpManager.isSticky(anyString())).thenReturn(true)
        addHUN(mEntry)
        whenever(mHeadsUpManager.canRemoveImmediately(anyString())).thenReturn(false)
        whenever(mHeadsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L)
        assertTrue(mNotifLifetimeExtender.maybeExtendLifetime(mEntry, 0))
        mNotifLifetimeExtender.cancelLifetimeExtension(mEntry)
        mExecutor.advanceClockToLast()
        mExecutor.runAllReady()
        verify(mHeadsUpManager, times(0)).removeNotification(anyString(), any())
    }

    @Test
    fun hunExtensionCancelledWhenHunActionPressed() {
        whenever(mHeadsUpManager.isSticky(anyString())).thenReturn(true)
        addHUN(mEntry)
        whenever(mHeadsUpManager.canRemoveImmediately(anyString())).thenReturn(false)
        whenever(mHeadsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L)
        assertTrue(mNotifLifetimeExtender.maybeExtendLifetime(mEntry, 0))
        mActionPressListener.accept(mEntry)
        mExecutor.advanceClockToLast()
        mExecutor.runAllReady()
        verify(mHeadsUpManager, times(1)).removeNotification(eq(mEntry.key), eq(true))
    }

    @Test
    fun testCancelUpdatedStickyNotification() {
        whenever(mHeadsUpManager.isSticky(anyString())).thenReturn(true)
        addHUN(mEntry)
        whenever(mHeadsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L, 500L)
        assertTrue(mNotifLifetimeExtender.maybeExtendLifetime(mEntry, 0))
        addHUN(mEntry)
        mExecutor.advanceClockToLast()
        mExecutor.runAllReady()
        verify(mHeadsUpManager, times(0)).removeNotification(anyString(), eq(false))
        verify(mHeadsUpManager, times(0)).removeNotification(anyString(), eq(true))
    }

    @Test
    fun testCancelNotification() {
        whenever(mHeadsUpManager.isSticky(anyString())).thenReturn(false)
        addHUN(mEntry)
        whenever(mHeadsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L, 500L)
        assertTrue(mNotifLifetimeExtender.maybeExtendLifetime(mEntry, 0))
        mExecutor.advanceClockToLast()
        mExecutor.runAllReady()
        verify(mHeadsUpManager, times(1)).removeNotification(anyString(), eq(false))
        verify(mHeadsUpManager, times(0)).removeNotification(anyString(), eq(true))
    }

    @Test
    fun testPromotesAddedHUN() {
        // GIVEN the current entry should heads up
        whenever(mNotificationInterruptStateProvider.shouldHeadsUp(mEntry)).thenReturn(true)

        // WHEN the notification is added but not yet binding
        mCollectionListener.onEntryAdded(mEntry)
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mEntry), any())

        // THEN only promote mEntry
        assertTrue(mNotifPromoter.shouldPromoteToTopLevel(mEntry))
    }

    @Test
    fun testPromotesBindingHUN() {
        // GIVEN the current entry should heads up
        whenever(mNotificationInterruptStateProvider.shouldHeadsUp(mEntry)).thenReturn(true)

        // WHEN the notification started binding on the previous run
        mCollectionListener.onEntryAdded(mEntry)
        mBeforeTransformGroupsListener.onBeforeTransformGroups(listOf(mEntry))
        mBeforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(mEntry))
        verify(mHeadsUpViewBinder).bindHeadsUpView(eq(mEntry), any())

        // THEN only promote mEntry
        assertTrue(mNotifPromoter.shouldPromoteToTopLevel(mEntry))
    }

    @Test
    fun testPromotesCurrentHUN() {
        // GIVEN the current HUN is set to mEntry
        addHUN(mEntry)

        // THEN only promote the current HUN, mEntry
        assertTrue(mNotifPromoter.shouldPromoteToTopLevel(mEntry))
        assertFalse(mNotifPromoter.shouldPromoteToTopLevel(NotificationEntryBuilder()
            .setPkg("test-package2")
            .build()))
    }

    @Test
    fun testIncludeInSectionCurrentHUN() {
        // GIVEN the current HUN is set to mEntry
        addHUN(mEntry)

        // THEN only section the current HUN, mEntry
        assertTrue(mNotifSectioner.isInSection(mEntry))
        assertFalse(mNotifSectioner.isInSection(NotificationEntryBuilder()
            .setPkg("test-package")
            .build()))
    }

    @Test
    fun testLifetimeExtendsCurrentHUN() {
        // GIVEN there is a HUN, mEntry
        addHUN(mEntry)

        // THEN only the current HUN, mEntry, should be lifetimeExtended
        assertTrue(mNotifLifetimeExtender.maybeExtendLifetime(mEntry, /* cancellationReason */ 0))
        assertFalse(mNotifLifetimeExtender.maybeExtendLifetime(
            NotificationEntryBuilder()
                .setPkg("test-package")
                .build(), /* cancellationReason */ 0))
    }

    @Test
    fun testShowHUNOnInflationFinished() {
        // WHEN a notification should HUN and its inflation is finished
        whenever(mNotificationInterruptStateProvider.shouldHeadsUp(mEntry)).thenReturn(true)

        mCollectionListener.onEntryAdded(mEntry)
        mBeforeTransformGroupsListener.onBeforeTransformGroups(listOf(mEntry))
        mBeforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(mEntry))
        verify(mHeadsUpManager, never()).showNotification(mEntry)
        withArgCaptor<BindCallback> {
            verify(mHeadsUpViewBinder).bindHeadsUpView(eq(mEntry), capture())
        }.onBindFinished(mEntry)

        // THEN we tell the HeadsUpManager to show the notification
        verify(mHeadsUpManager).showNotification(mEntry)
    }

    @Test
    fun testNoHUNOnInflationFinished() {
        // WHEN a notification shouldn't HUN and its inflation is finished
        whenever(mNotificationInterruptStateProvider.shouldHeadsUp(mEntry)).thenReturn(false)
        mCollectionListener.onEntryAdded(mEntry)
        mBeforeTransformGroupsListener.onBeforeTransformGroups(listOf(mEntry))
        mBeforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(mEntry))

        // THEN we never bind the heads up view or tell HeadsUpManager to show the notification
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mEntry), any())
        verify(mHeadsUpManager, never()).showNotification(mEntry)
    }

    @Test
    fun testOnEntryRemovedRemovesHeadsUpNotification() {
        // GIVEN the current HUN is mEntry
        addHUN(mEntry)

        // WHEN mEntry is removed from the notification collection
        mCollectionListener.onEntryRemoved(mEntry, /* cancellation reason */ 0)
        whenever(mRemoteInputManager.isSpinning(any())).thenReturn(false)

        // THEN heads up manager should remove the entry
        verify(mHeadsUpManager).removeNotification(mEntry.key, false)
    }

    private fun addHUN(entry: NotificationEntry) {
        mHuns.add(entry)
        whenever(mHeadsUpManager.topEntry).thenReturn(entry)
        mOnHeadsUpChangedListener.onHeadsUpStateChanged(entry, true)
        mNotifLifetimeExtender.cancelLifetimeExtension(entry)
    }

    @Test
    fun testTransferIsolatedChildAlert_withGroupAlertSummary() {
        setShouldHeadsUp(mGroupSummary)
        whenever(mNotifPipeline.allNotifs).thenReturn(listOf(mGroupSummary, mGroupSibling1))

        mCollectionListener.onEntryAdded(mGroupSummary)
        mCollectionListener.onEntryAdded(mGroupSibling1)
        mBeforeTransformGroupsListener.onBeforeTransformGroups(listOf(mGroupSibling1))
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(any(), any())
        mBeforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(mGroupSibling1))

        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSummary), any())
        finishBind(mGroupSibling1)

        verify(mHeadsUpManager, never()).showNotification(mGroupSummary)
        verify(mHeadsUpManager).showNotification(mGroupSibling1)
    }

    @Test
    fun testTransferIsolatedChildAlert_withGroupAlertAll() {
        setShouldHeadsUp(mGroupSummary)
        whenever(mNotifPipeline.allNotifs).thenReturn(listOf(mGroupSummary, mGroupChild1))

        mCollectionListener.onEntryAdded(mGroupSummary)
        mCollectionListener.onEntryAdded(mGroupChild1)
        mBeforeTransformGroupsListener.onBeforeTransformGroups(listOf(mGroupChild1))
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(any(), any())
        mBeforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(mGroupChild1))

        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSummary), any())
        finishBind(mGroupChild1)

        verify(mHeadsUpManager, never()).showNotification(mGroupSummary)
        verify(mHeadsUpManager).showNotification(mGroupChild1)
    }

    @Test
    fun testTransferTwoIsolatedChildAlert_withGroupAlertSummary() {
        // WHEN a notification should HUN and its inflation is finished
        setShouldHeadsUp(mGroupSummary)
        whenever(mNotifPipeline.allNotifs)
            .thenReturn(listOf(mGroupSummary, mGroupSibling1, mGroupSibling2, mGroupPriority))

        mCollectionListener.onEntryAdded(mGroupSummary)
        mCollectionListener.onEntryAdded(mGroupSibling1)
        mCollectionListener.onEntryAdded(mGroupSibling2)
        val entryList = listOf(mGroupSibling1, mGroupSibling2)
        mBeforeTransformGroupsListener.onBeforeTransformGroups(entryList)
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(any(), any())
        mBeforeFinalizeFilterListener.onBeforeFinalizeFilter(entryList)

        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSummary), any())
        finishBind(mGroupSibling1)
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSibling2), any())

        // THEN we tell the HeadsUpManager to show the notification
        verify(mHeadsUpManager, never()).showNotification(mGroupSummary)
        verify(mHeadsUpManager).showNotification(mGroupSibling1)
        verify(mHeadsUpManager, never()).showNotification(mGroupSibling2)
    }

    @Test
    fun testTransferTwoIsolatedChildAlert_withGroupAlertAll() {
        // WHEN a notification should HUN and its inflation is finished
        setShouldHeadsUp(mGroupSummary)
        whenever(mNotifPipeline.allNotifs)
            .thenReturn(listOf(mGroupSummary, mGroupChild1, mGroupChild2, mGroupPriority))

        mCollectionListener.onEntryAdded(mGroupSummary)
        mCollectionListener.onEntryAdded(mGroupChild1)
        mCollectionListener.onEntryAdded(mGroupChild2)
        val entryList = listOf(mGroupChild1, mGroupChild2)
        mBeforeTransformGroupsListener.onBeforeTransformGroups(entryList)
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(any(), any())
        mBeforeFinalizeFilterListener.onBeforeFinalizeFilter(entryList)

        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSummary), any())
        finishBind(mGroupChild1)
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupChild2), any())

        // THEN we tell the HeadsUpManager to show the notification
        verify(mHeadsUpManager, never()).showNotification(mGroupSummary)
        verify(mHeadsUpManager).showNotification(mGroupChild1)
        verify(mHeadsUpManager, never()).showNotification(mGroupChild2)
    }

    @Test
    fun testTransferToPriorityOnAddWithTwoSiblings() {
        // WHEN a notification should HUN and its inflation is finished
        setShouldHeadsUp(mGroupSummary)
        whenever(mNotifPipeline.allNotifs)
            .thenReturn(listOf(mGroupSummary, mGroupSibling1, mGroupSibling2, mGroupPriority))

        mCollectionListener.onEntryAdded(mGroupSummary)
        mCollectionListener.onEntryAdded(mGroupPriority)
        mCollectionListener.onEntryAdded(mGroupSibling1)
        mCollectionListener.onEntryAdded(mGroupSibling2)

        val beforeTransformGroup = GroupEntryBuilder()
            .setSummary(mGroupSummary)
            .setChildren(listOf(mGroupSibling1, mGroupPriority, mGroupSibling2))
            .build()
        mBeforeTransformGroupsListener.onBeforeTransformGroups(listOf(beforeTransformGroup))
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(any(), any())

        val afterTransformGroup = GroupEntryBuilder()
            .setSummary(mGroupSummary)
            .setChildren(listOf(mGroupSibling1, mGroupSibling2))
            .build()
        mBeforeFinalizeFilterListener
            .onBeforeFinalizeFilter(listOf(mGroupPriority, afterTransformGroup))

        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSummary), any())
        finishBind(mGroupPriority)
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSibling1), any())
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSibling2), any())

        // THEN we tell the HeadsUpManager to show the notification
        verify(mHeadsUpManager, never()).showNotification(mGroupSummary)
        verify(mHeadsUpManager).showNotification(mGroupPriority)
        verify(mHeadsUpManager, never()).showNotification(mGroupSibling1)
        verify(mHeadsUpManager, never()).showNotification(mGroupSibling2)
    }

    @Test
    fun testTransferToPriorityOnUpdateWithTwoSiblings() {
        setShouldHeadsUp(mGroupSummary)
        whenever(mNotifPipeline.allNotifs)
            .thenReturn(listOf(mGroupSummary, mGroupSibling1, mGroupSibling2, mGroupPriority))

        mCollectionListener.onEntryUpdated(mGroupSummary)
        mCollectionListener.onEntryUpdated(mGroupPriority)
        mCollectionListener.onEntryUpdated(mGroupSibling1)
        mCollectionListener.onEntryUpdated(mGroupSibling2)

        val beforeTransformGroup = GroupEntryBuilder()
            .setSummary(mGroupSummary)
            .setChildren(listOf(mGroupSibling1, mGroupPriority, mGroupSibling2))
            .build()
        mBeforeTransformGroupsListener.onBeforeTransformGroups(listOf(beforeTransformGroup))
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(any(), any())

        val afterTransformGroup = GroupEntryBuilder()
            .setSummary(mGroupSummary)
            .setChildren(listOf(mGroupSibling1, mGroupSibling2))
            .build()
        mBeforeFinalizeFilterListener
            .onBeforeFinalizeFilter(listOf(mGroupPriority, afterTransformGroup))

        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSummary), any())
        finishBind(mGroupPriority)
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSibling1), any())
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSibling2), any())

        verify(mHeadsUpManager, never()).showNotification(mGroupSummary)
        verify(mHeadsUpManager).showNotification(mGroupPriority)
        verify(mHeadsUpManager, never()).showNotification(mGroupSibling1)
        verify(mHeadsUpManager, never()).showNotification(mGroupSibling2)
    }

    @Test
    fun testTransferToPriorityOnUpdateWithTwoNonUpdatedSiblings() {
        setShouldHeadsUp(mGroupSummary)
        whenever(mNotifPipeline.allNotifs)
            .thenReturn(listOf(mGroupSummary, mGroupSibling1, mGroupSibling2, mGroupPriority))

        mCollectionListener.onEntryUpdated(mGroupSummary)
        mCollectionListener.onEntryUpdated(mGroupPriority)

        val beforeTransformGroup = GroupEntryBuilder()
            .setSummary(mGroupSummary)
            .setChildren(listOf(mGroupSibling1, mGroupPriority, mGroupSibling2))
            .build()
        mBeforeTransformGroupsListener.onBeforeTransformGroups(listOf(beforeTransformGroup))
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(any(), any())

        val afterTransformGroup = GroupEntryBuilder()
            .setSummary(mGroupSummary)
            .setChildren(listOf(mGroupSibling1, mGroupSibling2))
            .build()
        mBeforeFinalizeFilterListener
            .onBeforeFinalizeFilter(listOf(mGroupPriority, afterTransformGroup))

        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSummary), any())
        finishBind(mGroupPriority)
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSibling1), any())
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSibling2), any())

        verify(mHeadsUpManager, never()).showNotification(mGroupSummary)
        verify(mHeadsUpManager).showNotification(mGroupPriority)
        verify(mHeadsUpManager, never()).showNotification(mGroupSibling1)
        verify(mHeadsUpManager, never()).showNotification(mGroupSibling2)
    }

    @Test
    fun testNoTransferToPriorityOnUpdateOfTwoSiblings() {
        setShouldHeadsUp(mGroupSummary)
        whenever(mNotifPipeline.allNotifs)
            .thenReturn(listOf(mGroupSummary, mGroupSibling1, mGroupSibling2, mGroupPriority))

        mCollectionListener.onEntryUpdated(mGroupSummary)
        mCollectionListener.onEntryUpdated(mGroupSibling1)
        mCollectionListener.onEntryUpdated(mGroupSibling2)

        val beforeTransformGroup = GroupEntryBuilder()
            .setSummary(mGroupSummary)
            .setChildren(listOf(mGroupSibling1, mGroupPriority, mGroupSibling2))
            .build()
        mBeforeTransformGroupsListener.onBeforeTransformGroups(listOf(beforeTransformGroup))
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(any(), any())

        val afterTransformGroup = GroupEntryBuilder()
            .setSummary(mGroupSummary)
            .setChildren(listOf(mGroupSibling1, mGroupSibling2))
            .build()
        mBeforeFinalizeFilterListener
            .onBeforeFinalizeFilter(listOf(mGroupPriority, afterTransformGroup))

        finishBind(mGroupSummary)
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupPriority), any())
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSibling1), any())
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSibling2), any())

        verify(mHeadsUpManager).showNotification(mGroupSummary)
        verify(mHeadsUpManager, never()).showNotification(mGroupPriority)
        verify(mHeadsUpManager, never()).showNotification(mGroupSibling1)
        verify(mHeadsUpManager, never()).showNotification(mGroupSibling2)
    }

    @Test
    fun testNoTransferTwoChildAlert_withGroupAlertSummary() {
        setShouldHeadsUp(mGroupSummary)
        whenever(mNotifPipeline.allNotifs)
            .thenReturn(listOf(mGroupSummary, mGroupSibling1, mGroupSibling2))

        mCollectionListener.onEntryAdded(mGroupSummary)
        mCollectionListener.onEntryAdded(mGroupSibling1)
        mCollectionListener.onEntryAdded(mGroupSibling2)
        val groupEntry = GroupEntryBuilder()
            .setSummary(mGroupSummary)
            .setChildren(listOf(mGroupSibling1, mGroupSibling2))
            .build()
        mBeforeTransformGroupsListener.onBeforeTransformGroups(listOf(groupEntry))
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(any(), any())
        mBeforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))

        finishBind(mGroupSummary)
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSibling1), any())
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupSibling2), any())

        verify(mHeadsUpManager).showNotification(mGroupSummary)
        verify(mHeadsUpManager, never()).showNotification(mGroupSibling1)
        verify(mHeadsUpManager, never()).showNotification(mGroupSibling2)
    }

    @Test
    fun testNoTransferTwoChildAlert_withGroupAlertAll() {
        setShouldHeadsUp(mGroupSummary)
        whenever(mNotifPipeline.allNotifs)
            .thenReturn(listOf(mGroupSummary, mGroupChild1, mGroupChild2))

        mCollectionListener.onEntryAdded(mGroupSummary)
        mCollectionListener.onEntryAdded(mGroupChild1)
        mCollectionListener.onEntryAdded(mGroupChild2)
        val groupEntry = GroupEntryBuilder()
            .setSummary(mGroupSummary)
            .setChildren(listOf(mGroupChild1, mGroupChild2))
            .build()
        mBeforeTransformGroupsListener.onBeforeTransformGroups(listOf(groupEntry))
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(any(), any())
        mBeforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))

        finishBind(mGroupSummary)
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupChild1), any())
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(eq(mGroupChild2), any())

        verify(mHeadsUpManager).showNotification(mGroupSummary)
        verify(mHeadsUpManager, never()).showNotification(mGroupChild1)
        verify(mHeadsUpManager, never()).showNotification(mGroupChild2)
    }

    private fun setShouldHeadsUp(entry: NotificationEntry, should: Boolean = true) {
        whenever(mNotificationInterruptStateProvider.shouldHeadsUp(entry)).thenReturn(should)
    }

    private fun finishBind(entry: NotificationEntry) {
        verify(mHeadsUpManager, never()).showNotification(entry)
        withArgCaptor<BindCallback> {
            verify(mHeadsUpViewBinder).bindHeadsUpView(eq(entry), capture())
        }.onBindFinished(entry)
    }
}
