/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Build/Install/Run:
 *  atest WmTests:PersisterQueueTests
 */
@MediumTest
@Presubmit
public class PersisterQueueTests {
    private static final long INTER_WRITE_DELAY_MS = 50;
    private static final long PRE_TASK_DELAY_MS = 300;
    // We allow at most 0.2s more than the expected timeout.
    private static final long TIMEOUT_ALLOWANCE = 200;

    private final PersisterQueue mTarget =
            new PersisterQueue(INTER_WRITE_DELAY_MS, PRE_TASK_DELAY_MS);

    private TestPersisterQueueListener mListener;
    private TestWriteQueueItemFactory mFactory;

    @Before
    public void setUp() throws Exception {
        mListener = new TestPersisterQueueListener();
        mListener.setExpectedOnPreProcessItemCallbackTimes(1);
        mTarget.addListener(mListener);

        mFactory = new TestWriteQueueItemFactory();

        mTarget.startPersisting();

        assertTrue("Target didn't call callback on start up.",
                mListener.waitForAllExpectedCallbackDone(TIMEOUT_ALLOWANCE));
    }

    @After
    public void tearDown() throws Exception {
        mTarget.stopPersisting();
        mTarget.removeListener(mListener);
    }

    @Test
    public void testCallCallbackOnStartUp() {
        // The onPreProcessItem() must be called on start up.
        assertEquals(1, mListener.mProbablyDoneResults.size());
        // The last one must be called with probably done being true.
        assertTrue("The last probablyDone must be true.", mListener.mProbablyDoneResults.get(0));
    }

    @Test
    public void testProcessOneItem() throws Exception {
        mFactory.setExpectedProcessedItemNumber(1);
        mListener.setExpectedOnPreProcessItemCallbackTimes(1);

        final long dispatchTime = SystemClock.uptimeMillis();
        mTarget.addItem(mFactory.createItem(), false);
        assertTrue("Target didn't process item enough times.",
                mFactory.waitForAllExpectedItemsProcessed(PRE_TASK_DELAY_MS + TIMEOUT_ALLOWANCE));
        assertEquals("Target didn't process item.", 1, mFactory.getTotalProcessedItemCount());
        final long processDuration = SystemClock.uptimeMillis() - dispatchTime;
        assertTrue("Target didn't wait enough time before processing item. duration: "
                        + processDuration + "ms pretask delay: " + PRE_TASK_DELAY_MS + "ms",
                processDuration >= PRE_TASK_DELAY_MS);

        assertTrue("Target didn't call callback enough times.",
                mListener.waitForAllExpectedCallbackDone(TIMEOUT_ALLOWANCE));
        // Once before processing this item, once after that.
        assertEquals(2, mListener.mProbablyDoneResults.size());
        // The last one must be called with probably done being true.
        assertTrue("The last probablyDone must be true.", mListener.mProbablyDoneResults.get(1));
    }

    @Test
    public void testProcessOneItem_Flush() throws Exception {
        mFactory.setExpectedProcessedItemNumber(1);
        mListener.setExpectedOnPreProcessItemCallbackTimes(1);

        final long dispatchTime = SystemClock.uptimeMillis();
        mTarget.addItem(mFactory.createItem(), true);
        assertTrue("Target didn't process item enough times.",
                mFactory.waitForAllExpectedItemsProcessed(TIMEOUT_ALLOWANCE));
        assertEquals("Target didn't process item.", 1, mFactory.getTotalProcessedItemCount());
        final long processDuration = SystemClock.uptimeMillis() - dispatchTime;
        assertTrue("Target didn't process item immediately when flushing. duration: "
                        + processDuration + "ms pretask delay: "
                        + PRE_TASK_DELAY_MS + "ms",
                processDuration < PRE_TASK_DELAY_MS);

        assertTrue("Target didn't call callback enough times.",
                mListener.waitForAllExpectedCallbackDone(TIMEOUT_ALLOWANCE));
        // Once before processing this item, once after that.
        assertEquals(2, mListener.mProbablyDoneResults.size());
        // The last one must be called with probably done being true.
        assertTrue("The last probablyDone must be true.", mListener.mProbablyDoneResults.get(1));
    }

    @Test
    public void testProcessTwoItems() throws Exception {
        mFactory.setExpectedProcessedItemNumber(2);
        mListener.setExpectedOnPreProcessItemCallbackTimes(2);

        final long dispatchTime = SystemClock.uptimeMillis();
        mTarget.addItem(mFactory.createItem(), false);
        mTarget.addItem(mFactory.createItem(), false);
        assertTrue("Target didn't call callback enough times.",
                mFactory.waitForAllExpectedItemsProcessed(PRE_TASK_DELAY_MS + INTER_WRITE_DELAY_MS
                        + TIMEOUT_ALLOWANCE));
        assertEquals("Target didn't process all items.", 2, mFactory.getTotalProcessedItemCount());
        final long processDuration = SystemClock.uptimeMillis() - dispatchTime;
        assertTrue("Target didn't wait enough time before processing item. duration: "
                        + processDuration + "ms pretask delay: " + PRE_TASK_DELAY_MS
                        + "ms inter write delay: " + INTER_WRITE_DELAY_MS + "ms",
                processDuration >= PRE_TASK_DELAY_MS + INTER_WRITE_DELAY_MS);
        assertTrue("Target didn't call the onPreProcess callback enough times",
                mListener.waitForAllExpectedCallbackDone(TIMEOUT_ALLOWANCE));
        // Once before processing this item, once after that.
        assertEquals(3, mListener.mProbablyDoneResults.size());
        // The first one must be called with probably done being false.
        assertFalse("The first probablyDone must be false.", mListener.mProbablyDoneResults.get(1));
        // The last one must be called with probably done being true.
        assertTrue("The last probablyDone must be true.", mListener.mProbablyDoneResults.get(2));
    }

    @Test
    public void testProcessTwoItems_OneAfterAnother() throws Exception {
        // First item
        mFactory.setExpectedProcessedItemNumber(1);
        mListener.setExpectedOnPreProcessItemCallbackTimes(1);
        long dispatchTime = SystemClock.uptimeMillis();
        mTarget.addItem(mFactory.createItem(), false);
        assertTrue("Target didn't process item enough times.",
                mFactory.waitForAllExpectedItemsProcessed(PRE_TASK_DELAY_MS + TIMEOUT_ALLOWANCE));
        long processDuration = SystemClock.uptimeMillis() - dispatchTime;
        assertTrue("Target didn't wait enough time before processing item."
                        + processDuration + "ms pretask delay: "
                        + PRE_TASK_DELAY_MS + "ms",
                processDuration >= PRE_TASK_DELAY_MS);
        assertEquals("Target didn't process item.", 1, mFactory.getTotalProcessedItemCount());
        assertTrue("Target didn't call callback enough times.",
                mListener.waitForAllExpectedCallbackDone(TIMEOUT_ALLOWANCE));

        // Second item
        mFactory.setExpectedProcessedItemNumber(1);
        mListener.setExpectedOnPreProcessItemCallbackTimes(1);
        dispatchTime = SystemClock.uptimeMillis();
        // Synchronize on the instance to make sure we schedule the item after it starts to wait for
        // task indefinitely.
        synchronized (mTarget) {
            mTarget.addItem(mFactory.createItem(), false);
        }
        assertTrue("Target didn't process item enough times.",
                mFactory.waitForAllExpectedItemsProcessed(PRE_TASK_DELAY_MS + TIMEOUT_ALLOWANCE));
        assertEquals("Target didn't process all items.", 2, mFactory.getTotalProcessedItemCount());
        processDuration = SystemClock.uptimeMillis() - dispatchTime;
        assertTrue("Target didn't wait enough time before processing item. Process time: "
                        + processDuration + "ms pre task delay: "
                        + PRE_TASK_DELAY_MS + "ms",
                processDuration >= PRE_TASK_DELAY_MS);

        assertTrue("Target didn't call callback enough times.",
                mListener.waitForAllExpectedCallbackDone(TIMEOUT_ALLOWANCE));
        // Once before processing this item, once after that.
        assertEquals(3, mListener.mProbablyDoneResults.size());
        // The last one must be called with probably done being true.
        assertTrue("The last probablyDone must be true.", mListener.mProbablyDoneResults.get(2));
    }

    @Test
    public void testFindLastItemNotReturnDifferentType() {
        synchronized (mTarget) {
            mTarget.addItem(mFactory.createItem(), false);
            assertNull(mTarget.findLastItem(TestItem::shouldKeepOnFilter,
                    FilterableTestItem.class));
        }
    }

    @Test
    public void testFindLastItemNotReturnMismatchItem() {
        synchronized (mTarget) {
            mTarget.addItem(mFactory.createFilterableItem(false), false);
            assertNull(mTarget.findLastItem(TestItem::shouldKeepOnFilter,
                    FilterableTestItem.class));
        }
    }

    @Test
    public void testFindLastItemReturnMatchedItem() {
        synchronized (mTarget) {
            final FilterableTestItem item = mFactory.createFilterableItem(true);
            mTarget.addItem(item, false);
            assertSame(item, mTarget.findLastItem(TestItem::shouldKeepOnFilter,
                    FilterableTestItem.class));
        }
    }

    @Test
    public void testRemoveItemsNotRemoveDifferentType() throws Exception {
        mListener.setExpectedOnPreProcessItemCallbackTimes(1);
        synchronized (mTarget) {
            mTarget.addItem(mFactory.createItem(), false);
            mTarget.removeItems(TestItem::shouldKeepOnFilter, FilterableTestItem.class);
        }
        assertTrue("Target didn't call callback enough times.",
                mListener.waitForAllExpectedCallbackDone(PRE_TASK_DELAY_MS + TIMEOUT_ALLOWANCE));
        assertEquals("Target didn't process item.", 1, mFactory.getTotalProcessedItemCount());
    }

    @Test
    public void testRemoveItemsNotRemoveMismatchedItem() throws Exception {
        mListener.setExpectedOnPreProcessItemCallbackTimes(1);
        synchronized (mTarget) {
            mTarget.addItem(mFactory.createFilterableItem(false), false);
            mTarget.removeItems(TestItem::shouldKeepOnFilter, FilterableTestItem.class);
        }
        assertTrue("Target didn't call callback enough times.",
                mListener.waitForAllExpectedCallbackDone(PRE_TASK_DELAY_MS + TIMEOUT_ALLOWANCE));
        assertEquals("Target didn't process item.", 1, mFactory.getTotalProcessedItemCount());
    }

    @Test
    public void testUpdateLastOrAddItemUpdatesMatchedItem() throws Exception {
        mListener.setExpectedOnPreProcessItemCallbackTimes(1);
        final FilterableTestItem scheduledItem = mFactory.createFilterableItem(true);
        final FilterableTestItem expected = mFactory.createFilterableItem(true);
        synchronized (mTarget) {
            mTarget.addItem(scheduledItem, false);
            mTarget.updateLastOrAddItem(expected, false);
        }

        assertSame(expected, scheduledItem.mUpdateFromItem);
        assertTrue("Target didn't call callback enough times.",
                mListener.waitForAllExpectedCallbackDone(PRE_TASK_DELAY_MS + TIMEOUT_ALLOWANCE));
        assertEquals("Target didn't process item.", 1, mFactory.getTotalProcessedItemCount());
    }

    @Test
    public void testUpdateLastOrAddItemUpdatesAddItemWhenNoMatch() throws Exception {
        mListener.setExpectedOnPreProcessItemCallbackTimes(2);
        final FilterableTestItem scheduledItem = mFactory.createFilterableItem(false);
        final FilterableTestItem expected = mFactory.createFilterableItem(true);
        synchronized (mTarget) {
            mTarget.addItem(scheduledItem, false);
            mTarget.updateLastOrAddItem(expected, false);
        }

        assertNull(scheduledItem.mUpdateFromItem);
        assertTrue("Target didn't call callback enough times.",
                mListener.waitForAllExpectedCallbackDone(PRE_TASK_DELAY_MS + INTER_WRITE_DELAY_MS
                        + TIMEOUT_ALLOWANCE));
        assertEquals("Target didn't process item.", 2, mFactory.getTotalProcessedItemCount());
    }

    @Test
    public void testRemoveItemsRemoveMatchedItem() throws Exception {
        mListener.setExpectedOnPreProcessItemCallbackTimes(1);
        synchronized (mTarget) {
            mTarget.addItem(mFactory.createItem(), false);
            mTarget.addItem(mFactory.createFilterableItem(true), false);
            mTarget.removeItems(TestItem::shouldKeepOnFilter, FilterableTestItem.class);
        }
        assertTrue("Target didn't call callback enough times.",
                mListener.waitForAllExpectedCallbackDone(PRE_TASK_DELAY_MS + TIMEOUT_ALLOWANCE));
        assertEquals("Target didn't process item.", 1, mFactory.getTotalProcessedItemCount());
    }

    @Test
    public void testFlushWaitSynchronously() {
        final long dispatchTime = SystemClock.uptimeMillis();
        mTarget.addItem(mFactory.createItem(), false);
        mTarget.addItem(mFactory.createItem(), false);
        mTarget.flush();
        assertEquals("Flush should wait until all items are processed before return.",
                2, mFactory.getTotalProcessedItemCount());
        final long processTime = SystemClock.uptimeMillis() - dispatchTime;
        assertWithMessage("Flush should trigger immediate flush without delays. processTime: "
                + processTime).that(processTime).isLessThan(TIMEOUT_ALLOWANCE);
    }

    private static class TestWriteQueueItemFactory {
        private final AtomicInteger mItemCount = new AtomicInteger(0);;
        private CountDownLatch mLatch;

        int getTotalProcessedItemCount() {
            return mItemCount.get();
        }

        void setExpectedProcessedItemNumber(int countDown) {
            mLatch = new CountDownLatch(countDown);
        }

        boolean waitForAllExpectedItemsProcessed(long timeoutInMilliseconds)
                throws InterruptedException {
            return mLatch.await(timeoutInMilliseconds, TimeUnit.MILLISECONDS);
        }

        TestItem createItem() {
            return new TestItem(mItemCount, mLatch);
        }

        FilterableTestItem createFilterableItem(boolean shouldKeepOnFilter) {
            return new FilterableTestItem(shouldKeepOnFilter, mItemCount, mLatch);
        }
    }

    private static class TestItem<T extends TestItem<T>>
            implements PersisterQueue.WriteQueueItem<T> {
        private AtomicInteger mItemCount;
        private CountDownLatch mLatch;

        TestItem(AtomicInteger itemCount, CountDownLatch latch) {
            mItemCount = itemCount;
            mLatch = latch;
        }

        @Override
        public void process() {
            mItemCount.getAndIncrement();
            if (mLatch != null) {
                // Count down the latch at the last step is necessary, as it's a kind of lock to the
                // next assert in many test cases.
                mLatch.countDown();
            }
        }

        boolean shouldKeepOnFilter() {
            return true;
        }
    }

    private static class FilterableTestItem extends TestItem<FilterableTestItem> {
        private boolean mShouldKeepOnFilter;

        private FilterableTestItem mUpdateFromItem;

        private FilterableTestItem(boolean shouldKeepOnFilter, AtomicInteger mItemCount,
                CountDownLatch mLatch) {
            super(mItemCount, mLatch);
            mShouldKeepOnFilter = shouldKeepOnFilter;
        }

        @Override
        public boolean matches(FilterableTestItem item) {
            return item.mShouldKeepOnFilter;
        }

        @Override
        public void updateFrom(FilterableTestItem item) {
            mUpdateFromItem = item;
        }

        @Override
        boolean shouldKeepOnFilter() {
            return mShouldKeepOnFilter;
        }
    }

    private class TestPersisterQueueListener implements PersisterQueue.Listener {
        CountDownLatch mCallbackLatch;
        final List<Boolean> mProbablyDoneResults = new ArrayList<>();

        @Override
        public void onPreProcessItem(boolean queueEmpty) {
            mProbablyDoneResults.add(queueEmpty);
            mCallbackLatch.countDown();
        }

        void setExpectedOnPreProcessItemCallbackTimes(int countDown) {
            mCallbackLatch = new CountDownLatch(countDown);
        }

        boolean waitForAllExpectedCallbackDone(long timeoutInMilliseconds)
                throws InterruptedException {
            return mCallbackLatch.await(timeoutInMilliseconds, TimeUnit.MILLISECONDS);
        }
    }
}
