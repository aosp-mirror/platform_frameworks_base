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
package com.android.systemui.statusbar.policy

import android.app.Notification
import android.os.Handler
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun
import com.android.systemui.statusbar.policy.HeadsUpManagerTestUtil.createFullScreenIntentEntry
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.settings.FakeGlobalSettings
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWithLooper
@RunWith(AndroidJUnit4::class)
@EnableFlags(NotificationThrottleHun.FLAG_NAME)
class AvalancheControllerTest : SysuiTestCase() {

    // For creating mocks
    @get:Rule var rule: MockitoRule = MockitoJUnit.rule()
    @Mock private val runnableMock: Runnable? = null

    // For creating AvalancheController
    @Mock private lateinit var dumpManager: DumpManager
    private lateinit var mAvalancheController: AvalancheController

    // For creating TestableHeadsUpManager
    @Mock private val mAccessibilityMgr: AccessibilityManagerWrapper? = null
    private val mUiEventLoggerFake = UiEventLoggerFake()
    @Mock private lateinit var mHeadsUpManagerLogger: HeadsUpManagerLogger

    @Mock private lateinit var mBgHandler: Handler

    private val mLogger = Mockito.spy(HeadsUpManagerLogger(logcatLogBuffer()))
    private val mGlobalSettings = FakeGlobalSettings()
    private val mSystemClock = FakeSystemClock()
    private val mExecutor = FakeExecutor(mSystemClock)
    private lateinit var testableHeadsUpManager: BaseHeadsUpManager

    @Before
    fun setUp() {
        // Use default non-a11y timeout
        Mockito.`when`(
                mAccessibilityMgr!!.getRecommendedTimeoutMillis(
                    ArgumentMatchers.anyInt(),
                    ArgumentMatchers.anyInt()
                )
            )
            .then { i: InvocationOnMock -> i.getArgument(0) }

        // Initialize AvalancheController and TestableHeadsUpManager during setUp instead of
        // declaration, where mocks are null
        mAvalancheController = AvalancheController(dumpManager, mUiEventLoggerFake,
                mHeadsUpManagerLogger, mBgHandler)

        testableHeadsUpManager =
            TestableHeadsUpManager(
                mContext,
                mLogger,
                mExecutor,
                mGlobalSettings,
                mSystemClock,
                mAccessibilityMgr,
                mUiEventLoggerFake,
                mAvalancheController
            )
    }

    private fun createHeadsUpEntry(id: Int): BaseHeadsUpManager.HeadsUpEntry {
        return testableHeadsUpManager.createHeadsUpEntry(
            NotificationEntryBuilder()
                .setSbn(HeadsUpManagerTestUtil.createSbn(id, Notification.Builder(mContext, "")))
                .build()
        )
    }

    private fun createFsiHeadsUpEntry(id: Int): BaseHeadsUpManager.HeadsUpEntry {
        return testableHeadsUpManager.createHeadsUpEntry(createFullScreenIntentEntry(id, mContext))
    }

    @Test
    fun testUpdate_isShowing_runsRunnable() {
        // Entry is showing
        val headsUpEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.headsUpEntryShowing = headsUpEntry

        // Update
        mAvalancheController.update(headsUpEntry, runnableMock!!, "testLabel")

        // Runnable was run
        Mockito.verify(runnableMock, Mockito.times(1)).run()
    }

    @Test
    fun testUpdate_noneShowingAndNotNext_showNow() {
        val headsUpEntry = createHeadsUpEntry(id = 0)

        // None showing
        mAvalancheController.headsUpEntryShowing = null

        // Entry is NOT next
        mAvalancheController.clearNext()

        // Update
        mAvalancheController.update(headsUpEntry, runnableMock!!, "testLabel")

        // Entry is showing now
        assertThat(mAvalancheController.headsUpEntryShowing).isEqualTo(headsUpEntry)
    }

    @Test
    fun testUpdate_isNext_addsRunnable() {
        // Another entry is already showing
        val otherShowingEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.headsUpEntryShowing = otherShowingEntry

        // Entry is next
        val headsUpEntry = createHeadsUpEntry(id = 1)
        mAvalancheController.addToNext(headsUpEntry, runnableMock!!)

        // Entry has one Runnable
        val runnableList: List<Runnable?>? = mAvalancheController.nextMap[headsUpEntry]
        assertThat(runnableList).isNotNull()
        assertThat(runnableList!!.size).isEqualTo(1)

        // Update
        mAvalancheController.update(headsUpEntry, runnableMock, "testLabel")

        // Entry has two Runnables
        assertThat(runnableList.size).isEqualTo(2)
    }

    @Test
    fun testUpdate_isNotNextWithOtherHunShowing_isNext() {
        val headsUpEntry = createHeadsUpEntry(id = 0)

        // Another entry is already showing
        val otherShowingEntry = createHeadsUpEntry(id = 1)
        mAvalancheController.headsUpEntryShowing = otherShowingEntry

        // Entry is NOT next
        mAvalancheController.clearNext()

        // Update
        mAvalancheController.update(headsUpEntry, runnableMock!!, "testLabel")

        // Entry is next
        assertThat(mAvalancheController.nextMap.containsKey(headsUpEntry)).isTrue()
    }

    @Test
    fun testDelete_untracked_runnableRuns() {
        val headsUpEntry = createHeadsUpEntry(id = 0)

        // None showing
        mAvalancheController.headsUpEntryShowing = null

        // Nothing is next
        mAvalancheController.clearNext()

        // Delete
        mAvalancheController.delete(headsUpEntry, runnableMock!!, "testLabel")

        // Runnable was run
        Mockito.verify(runnableMock, Mockito.times(1)).run()
    }

    @Test
    fun testDelete_isNext_removedFromNext_runnableNotRun() {
        // Entry is next
        val headsUpEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.addToNext(headsUpEntry, runnableMock!!)

        // Delete
        mAvalancheController.delete(headsUpEntry, runnableMock, "testLabel")

        // Entry was removed from next
        assertThat(mAvalancheController.nextMap.containsKey(headsUpEntry)).isFalse()

        // Runnable was not run
        Mockito.verify(runnableMock, Mockito.times(0)).run()
    }

    @Test
    fun testDelete_wasDropped_removedFromDropSet() {
        // Entry was dropped
        val headsUpEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.debugDropSet.add(headsUpEntry)

        // Delete
        mAvalancheController.delete(headsUpEntry, runnableMock!!, "testLabel")

        // Entry was removed from dropSet
        assertThat(mAvalancheController.debugDropSet.contains(headsUpEntry)).isFalse()
    }

    @Test
    fun testDelete_wasDropped_runnableNotRun() {
        // Entry was dropped
        val headsUpEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.debugDropSet.add(headsUpEntry)

        // Delete
        mAvalancheController.delete(headsUpEntry, runnableMock!!, "testLabel")

        // Runnable was not run
        Mockito.verify(runnableMock, Mockito.times(0)).run()
    }

    @Test
    fun testDelete_isShowing_runnableRun() {
        // Entry is showing
        val headsUpEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.headsUpEntryShowing = headsUpEntry

        // Delete
        mAvalancheController.delete(headsUpEntry, runnableMock!!, "testLabel")

        // Runnable was run
        Mockito.verify(runnableMock, Mockito.times(1)).run()
    }

    @Test
    fun testDelete_isShowing_showNext() {
        // Entry is showing
        val showingEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.headsUpEntryShowing = showingEntry

        // There's another entry waiting to show next
        val nextEntry = createHeadsUpEntry(id = 1)
        mAvalancheController.addToNext(nextEntry, runnableMock!!)

        // Delete
        mAvalancheController.delete(showingEntry, runnableMock, "testLabel")

        // Next entry is shown
        assertThat(mAvalancheController.headsUpEntryShowing).isEqualTo(nextEntry)
    }


    @Test
    fun testDelete_deleteSecondToLastEntry_showingEntryKeyBecomesPreviousHunKey() {
        mAvalancheController.previousHunKey = ""

        // Entry is showing
        val firstEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.headsUpEntryShowing = firstEntry

        // There's another entry waiting to show next
        val secondEntry = createHeadsUpEntry(id = 1)
        mAvalancheController.addToNext(secondEntry, runnableMock!!)

        // Delete
        mAvalancheController.delete(firstEntry, runnableMock, "testLabel")

        // Next entry is shown
        assertThat(mAvalancheController.previousHunKey).isEqualTo(firstEntry.mEntry!!.key)
    }

    @Test
    fun testDelete_deleteLastEntry_previousHunKeyCleared() {
        mAvalancheController.previousHunKey = "key"

        // Nothing waiting to show
        mAvalancheController.clearNext()

        // One entry is showing
        val showingEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.headsUpEntryShowing = showingEntry

        // Delete
        mAvalancheController.delete(showingEntry, runnableMock!!, "testLabel")

        // Next entry is shown
        assertThat(mAvalancheController.previousHunKey).isEqualTo("");
    }

    @Test
    fun testGetDurationMs_untrackedEntryEmptyAvalanche_useAutoDismissTime() {
        val givenEntry = createHeadsUpEntry(id = 0)

        // Nothing is showing
        mAvalancheController.headsUpEntryShowing = null

        // Nothing is next
        mAvalancheController.clearNext()

        val durationMs = mAvalancheController.getDurationMs(givenEntry, autoDismissMs = 5000)
        assertThat(durationMs).isEqualTo(5000)
    }

    @Test
    fun testGetDurationMs_untrackedEntryNonEmptyAvalanche_useAutoDismissTime() {
        val givenEntry = createHeadsUpEntry(id = 0)

        // Given entry not tracked
        mAvalancheController.headsUpEntryShowing = createHeadsUpEntry(id = 1)

        mAvalancheController.clearNext()
        val nextEntry = createHeadsUpEntry(id = 2)
        mAvalancheController.addToNext(nextEntry, runnableMock!!)

        val durationMs = mAvalancheController.getDurationMs(givenEntry, autoDismissMs = 5000)
        assertThat(durationMs).isEqualTo(5000)
    }

    @Test
    fun testGetDurationMs_lastEntry_useAutoDismissTime() {
        // Entry is showing
        val showingEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.headsUpEntryShowing = showingEntry

        // Nothing is next
        mAvalancheController.clearNext()

        val durationMs = mAvalancheController.getDurationMs(showingEntry, autoDismissMs = 5000)
        assertThat(durationMs).isEqualTo(5000)
    }

    @Test
    fun testGetDurationMs_nextEntryLowerPriority_5000() {
        // Entry is showing
        val showingEntry = createFsiHeadsUpEntry(id = 1)
        mAvalancheController.headsUpEntryShowing = showingEntry

        // There's another entry waiting to show next
        val nextEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.addToNext(nextEntry, runnableMock!!)

        // Next entry has lower priority
        assertThat(nextEntry.compareNonTimeFields(showingEntry)).isEqualTo(1)

        val durationMs = mAvalancheController.getDurationMs(showingEntry, autoDismissMs = 5000)
        assertThat(durationMs).isEqualTo(5000)
    }

    @Test
    fun testGetDurationMs_nextEntrySamePriority_1000() {
        // Entry is showing
        val showingEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.headsUpEntryShowing = showingEntry

        // There's another entry waiting to show next
        val nextEntry = createHeadsUpEntry(id = 1)
        mAvalancheController.addToNext(nextEntry, runnableMock!!)

        // Same priority
        assertThat(nextEntry.compareNonTimeFields(showingEntry)).isEqualTo(0)

        val durationMs = mAvalancheController.getDurationMs(showingEntry, autoDismissMs = 5000)
        assertThat(durationMs).isEqualTo(1000)
    }

    @Test
    fun testGetDurationMs_nextEntryHigherPriority_500() {
        // Entry is showing
        val showingEntry = createHeadsUpEntry(id = 0)
        mAvalancheController.headsUpEntryShowing = showingEntry

        // There's another entry waiting to show next
        val nextEntry = createFsiHeadsUpEntry(id = 1)
        mAvalancheController.addToNext(nextEntry, runnableMock!!)

        // Next entry has higher priority
        assertThat(nextEntry.compareNonTimeFields(showingEntry)).isEqualTo(-1)

        val durationMs = mAvalancheController.getDurationMs(showingEntry, autoDismissMs = 5000)
        assertThat(durationMs).isEqualTo(500)
    }
}
