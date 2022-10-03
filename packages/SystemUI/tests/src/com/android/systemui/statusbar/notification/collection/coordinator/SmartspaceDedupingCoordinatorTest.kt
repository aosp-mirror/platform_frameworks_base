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

package com.android.systemui.statusbar.notification.collection.coordinator

import android.app.smartspace.SmartspaceTarget
import android.content.ComponentName
import android.os.UserHandle
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.FakeSystemClock
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
class SmartspaceDedupingCoordinatorTest : SysuiTestCase() {

    @Mock
    private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock
    private lateinit var smartspaceController: LockscreenSmartspaceController
    @Mock
    private lateinit var notifPipeline: NotifPipeline
    @Mock
    private lateinit var pluggableListener: Pluggable.PluggableListener<NotifFilter>

    private lateinit var filter: NotifFilter
    private lateinit var collectionListener: NotifCollectionListener
    private lateinit var statusBarListener: StatusBarStateController.StateListener
    private lateinit var newTargetListener: SmartspaceTargetListener

    private lateinit var entry1HasRecentlyAlerted: NotificationEntry
    private lateinit var entry2HasNotRecentlyAlerted: NotificationEntry
    private lateinit var entry3NotAssociatedWithTarget: NotificationEntry
    private lateinit var entry4HasNotRecentlyAlerted: NotificationEntry
    private lateinit var target1: SmartspaceTarget
    private lateinit var target2: SmartspaceTarget
    private lateinit var target4: SmartspaceTarget

    private val clock = FakeSystemClock()
    private val executor = FakeExecutor(clock)
    private val now = clock.currentTimeMillis()

    private lateinit var deduper: SmartspaceDedupingCoordinator

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        // Mock out some behavior
        `when`(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)

        // Build the deduper
        deduper = SmartspaceDedupingCoordinator(
                statusBarStateController,
                smartspaceController,
                notifPipeline,
                executor,
                clock
        )

        // Attach the deduper and capture the listeners/filters that it registers
        deduper.attach(notifPipeline)

        filter = withArgCaptor {
            verify(notifPipeline).addPreGroupFilter(capture())
        }
        filter.setInvalidationListener(pluggableListener)

        collectionListener = withArgCaptor {
            verify(notifPipeline).addCollectionListener(capture())
        }

        statusBarListener = withArgCaptor {
            verify(statusBarStateController).addCallback(capture())
        }

        newTargetListener = withArgCaptor {
            verify(smartspaceController).addListener(capture())
        }

        // Initialize some test data
        entry1HasRecentlyAlerted = NotificationEntryBuilder()
                .setPkg(PACKAGE_1)
                .setId(11)
                .setLastAudiblyAlertedMs(now - 10000)
                .build()
        entry2HasNotRecentlyAlerted = NotificationEntryBuilder()
                .setPkg(PACKAGE_2)
                .setId(22)
                .build()
        entry3NotAssociatedWithTarget = NotificationEntryBuilder()
                .setPkg("com.test.package.3")
                .setId(33)
                .setLastAudiblyAlertedMs(now - 10000)
                .build()
        entry4HasNotRecentlyAlerted = NotificationEntryBuilder()
                .setPkg(PACKAGE_2)
                .setId(44)
                .build()

        target1 = buildTargetFor(entry1HasRecentlyAlerted)
        target2 = buildTargetFor(entry2HasNotRecentlyAlerted)
        target4 = buildTargetFor(entry4HasNotRecentlyAlerted)
    }

    @Test
    fun testBasicFiltering() {
        // GIVEN a few notifications
        addEntries(
                entry2HasNotRecentlyAlerted,
                entry3NotAssociatedWithTarget,
                entry4HasNotRecentlyAlerted)

        // WHEN we receive smartspace targets associated with entry 2 and 3
        sendTargets(target2, target4)

        // THEN both pipelines are rerun
        verifyPipelinesInvalidated()

        // THEN the first target is filtered out, but the other ones aren't
        assertTrue(filter.shouldFilterOut(entry2HasNotRecentlyAlerted, now))
        assertFalse(filter.shouldFilterOut(entry3NotAssociatedWithTarget, now))
        assertFalse(filter.shouldFilterOut(entry4HasNotRecentlyAlerted, now))
    }

    @Test
    fun testDoNotFilterRecentlyAlertedNotifs() {
        // GIVEN one notif that recently alerted and a second that hasn't
        addEntries(entry1HasRecentlyAlerted, entry2HasNotRecentlyAlerted)

        // WHEN they become associated with smartspace targets
        sendTargets(target1, target2)

        // THEN neither is filtered (the first because it's recently alerted and the second
        // because it's not in the first position
        verifyPipelinesNotInvalidated()
        assertFalse(filter.shouldFilterOut(entry1HasRecentlyAlerted, now))
        assertFalse(filter.shouldFilterOut(entry2HasNotRecentlyAlerted, now))
    }

    @Test
    fun testFilterAlertedButNotRecentNotifs() {
        // GIVEN a notification that alerted, but a very long time ago
        val entryOldAlert = NotificationEntryBuilder(entry1HasRecentlyAlerted)
                .setLastAudiblyAlertedMs(now - 40000)
                .build()
        addEntries(entryOldAlert)

        // WHEN it becomes part of smartspace
        val target = buildTargetFor(entryOldAlert)
        sendTargets(target)

        // THEN it's still filtered out (because it's not in the alert window)
        verifyPipelinesInvalidated()
        assertTrue(filter.shouldFilterOut(entryOldAlert, now))
    }

    @Test
    fun testExceptionExpires() {
        // GIVEN a recently-alerted notif that is the primary smartspace target
        addEntries(entry1HasRecentlyAlerted)
        sendTargets(target1)
        clearPipelineInvocations()

        // WHEN we go beyond the target's exception window
        clock.advanceTime(20000)

        // THEN the pipeline is invalidated
        verifyPipelinesInvalidated()
        assertExecutorIsClear()
    }

    @Test
    fun testExceptionIsEventuallyFiltered() {
        // GIVEN a notif that has recently alerted
        addEntries(entry1HasRecentlyAlerted)

        // WHEN it becomes the primary smartspace target
        sendTargets(target1)

        // THEN it isn't filtered out (because it recently alerted)
        assertFalse(filter.shouldFilterOut(entry1HasRecentlyAlerted, now))

        // WHEN we pass the alert window
        clock.advanceTime(20000)

        // THEN the notif is once again filtered
        assertTrue(filter.shouldFilterOut(entry1HasRecentlyAlerted, clock.uptimeMillis()))
    }

    @Test
    fun testExceptionIsUpdated() {
        // GIVEN a notif that has recently alerted and is the primary smartspace target
        addEntries(entry1HasRecentlyAlerted)
        sendTargets(target1)
        clearPipelineInvocations()
        assertFalse(filter.shouldFilterOut(entry1HasRecentlyAlerted, clock.uptimeMillis()))

        // GIVEN the notif is updated with a much more recent alert time
        NotificationEntryBuilder(entry1HasRecentlyAlerted)
                .setLastAudiblyAlertedMs(clock.currentTimeMillis() - 500)
                .apply(entry1HasRecentlyAlerted)
        updateEntries(entry1HasRecentlyAlerted)
        assertFalse(filter.shouldFilterOut(entry1HasRecentlyAlerted, clock.uptimeMillis()))

        // WHEN we advance beyond the original exception window
        clock.advanceTime(25000)

        // THEN the original exception window doesn't fire
        verifyPipelinesNotInvalidated()
        assertFalse(filter.shouldFilterOut(entry1HasRecentlyAlerted, clock.uptimeMillis()))

        // WHEN we advance beyond the new exception window
        clock.advanceTime(4500)

        // THEN the pipelines are invalidated and no more timeouts are scheduled
        verifyPipelinesInvalidated()
        assertExecutorIsClear()
        assertTrue(filter.shouldFilterOut(entry1HasRecentlyAlerted, clock.uptimeMillis()))
    }

    @Test
    fun testReplacementIsCanceled() {
        // GIVEN a single notif and smartspace target
        addEntries(entry1HasRecentlyAlerted)
        sendTargets(target1)
        clearPipelineInvocations()

        // WHEN a higher-ranked target arrives
        val newerEntry = NotificationEntryBuilder()
                .setPkg(PACKAGE_2)
                .setId(55)
                .setLastAudiblyAlertedMs(now - 1000)
                .build()
        val newerTarget = buildTargetFor(newerEntry)
        sendTargets(newerTarget, target1)

        // THEN the timeout of the other target is canceled and it is no longer filtered
        assertExecutorIsClear()
        assertFalse(filter.shouldFilterOut(entry1HasRecentlyAlerted, clock.uptimeMillis()))
        verifyPipelinesInvalidated()
        clearPipelineInvocations()

        // WHEN the entry associated with the newer target later arrives
        addEntries(newerEntry)

        // THEN the entry is not filtered out (because it recently alerted)
        assertFalse(filter.shouldFilterOut(newerEntry, clock.uptimeMillis()))

        // WHEN its exception window passes
        clock.advanceTime(ALERT_WINDOW)

        // THEN we go back to filtering it
        verifyPipelinesInvalidated()
        assertExecutorIsClear()
        assertTrue(filter.shouldFilterOut(newerEntry, clock.uptimeMillis()))
    }

    @Test
    fun testRetractedIsCanceled() {
        // GIVEN A recently alerted target
        addEntries(entry1HasRecentlyAlerted)
        sendTargets(target1)

        // WHEN the entry is removed
        removeEntries(entry1HasRecentlyAlerted)

        // THEN its pending timeout is canceled
        assertExecutorIsClear()
        clock.advanceTime(ALERT_WINDOW)
        verifyPipelinesNotInvalidated()
    }

    @Test
    fun testTargetBeforeEntryFunctionsProperly() {
        // WHEN targets are added before their entries exist
        sendTargets(target2, target1)

        // THEN neither is filtered out
        assertFalse(filter.shouldFilterOut(entry2HasNotRecentlyAlerted, now))
        assertFalse(filter.shouldFilterOut(entry1HasRecentlyAlerted, now))

        // WHEN the entries are later added
        addEntries(entry2HasNotRecentlyAlerted, entry1HasRecentlyAlerted)

        // THEN the pipelines are not invalidated (because they're already going to be rerun)
        // but the first entry is still filtered out properly.
        verifyPipelinesNotInvalidated()
        assertTrue(filter.shouldFilterOut(entry2HasNotRecentlyAlerted, now))
    }

    @Test
    fun testLockscreenTracking() {
        // GIVEN a couple of smartspace targets that haven't alerted recently
        addEntries(entry2HasNotRecentlyAlerted, entry4HasNotRecentlyAlerted)
        sendTargets(target2, target4)
        clearPipelineInvocations()

        assertTrue(filter.shouldFilterOut(entry2HasNotRecentlyAlerted, now))

        // WHEN we are no longer on the keyguard
        statusBarListener.onStateChanged(StatusBarState.SHADE)

        // THEN the new pipeline is invalidated (but the old one isn't because it's not
        // necessary) because the notif should no longer be filtered out
        verify(pluggableListener).onPluggableInvalidated(eq(filter), any())
        assertFalse(filter.shouldFilterOut(entry2HasNotRecentlyAlerted, now))
    }

    private fun buildTargetFor(entry: NotificationEntry): SmartspaceTarget {
        return SmartspaceTarget
                .Builder("test", ComponentName("test", "class"), UserHandle.CURRENT)
                .setSourceNotificationKey(entry.key)
                .build()
    }

    private fun addEntries(vararg entries: NotificationEntry) {
        for (entry in entries) {
            `when`(notifPipeline.getEntry(entry.key)).thenReturn(entry)
            collectionListener.onEntryAdded(entry)
        }
    }

    private fun updateEntries(vararg entries: NotificationEntry) {
        for (entry in entries) {
            `when`(notifPipeline.getEntry(entry.key)).thenReturn(entry)
            collectionListener.onEntryUpdated(entry)
        }
    }

    private fun removeEntries(vararg entries: NotificationEntry) {
        for (entry in entries) {
            `when`(notifPipeline.getEntry(entry.key)).thenReturn(null)
            collectionListener.onEntryRemoved(entry, 0)
        }
    }

    private fun sendTargets(vararg targets: SmartspaceTarget) {
        newTargetListener.onSmartspaceTargetsUpdated(targets.toMutableList())
    }

    private fun verifyPipelinesInvalidated() {
        verify(pluggableListener).onPluggableInvalidated(eq(filter), any())
    }

    private fun assertExecutorIsClear() {
        assertEquals(0, executor.numPending())
    }

    private fun verifyPipelinesNotInvalidated() {
        verify(pluggableListener, never()).onPluggableInvalidated(eq(filter), any())
    }

    private fun clearPipelineInvocations() {
        clearInvocations(pluggableListener)
    }
}

private val ALERT_WINDOW = TimeUnit.SECONDS.toMillis(30)
private const val PACKAGE_1 = "com.test.package.1"
private const val PACKAGE_2 = "com.test.package.2"
