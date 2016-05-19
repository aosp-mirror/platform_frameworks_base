/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.DragEvent;
import android.view.View;

import com.android.documentsui.testing.ClipDatas;
import com.android.documentsui.testing.DragEvents;
import com.android.documentsui.testing.TestTimer;
import com.android.documentsui.testing.Views;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Timer;
import java.util.TimerTask;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ItemDragListenerTest {

    private static final long DELAY_AFTER_HOVERING = ItemDragListener.SPRING_TIMEOUT + 1;

    private View mTestView;
    private TestDragHost mTestDragHost;
    private TestTimer mTestTimer;

    private TestDragListener mListener;

    @Before
    public void setUp() {
        mTestView = Views.createTestView();

        mTestTimer = new TestTimer();
        mTestDragHost = new TestDragHost();
        mListener = new TestDragListener(mTestDragHost, mTestTimer);
    }

    @Test
    public void testDragStarted_ReturnsTrue() {
        assertTrue(triggerDragEvent(DragEvent.ACTION_DRAG_STARTED));
    }

    @Test
    public void testDragEntered_HighlightsView() {
        triggerDragEvent(DragEvent.ACTION_DRAG_ENTERED);
        assertSame(mTestView, mTestDragHost.mHighlightedView);
    }

    @Test
    public void testDragExited_UnhighlightsView() {
        triggerDragEvent(DragEvent.ACTION_DRAG_ENTERED);

        triggerDragEvent(DragEvent.ACTION_DRAG_EXITED);
        assertNull(mTestDragHost.mHighlightedView);
    }

    @Test
    public void testDragEnded_UnhighlightsView() {
        triggerDragEvent(DragEvent.ACTION_DRAG_ENTERED);

        triggerDragEvent(DragEvent.ACTION_DRAG_ENDED);
        assertNull(mTestDragHost.mHighlightedView);
    }

    @Test
    public void testHover_OpensView() {
        triggerDragEvent(DragEvent.ACTION_DRAG_ENTERED);

        mTestTimer.fastForwardTo(DELAY_AFTER_HOVERING);

        assertSame(mTestView, mTestDragHost.mLastOpenedView);
    }

    @Test
    public void testDragExited_CancelsHoverTask() {
        triggerDragEvent(DragEvent.ACTION_DRAG_ENTERED);

        triggerDragEvent(DragEvent.ACTION_DRAG_EXITED);

        mTestTimer.fastForwardTo(DELAY_AFTER_HOVERING);

        assertNull(mTestDragHost.mLastOpenedView);
    }

    @Test
    public void testDragEnded_CancelsHoverTask() {
        triggerDragEvent(DragEvent.ACTION_DRAG_ENTERED);

        triggerDragEvent(DragEvent.ACTION_DRAG_ENDED);

        mTestTimer.fastForwardTo(DELAY_AFTER_HOVERING);

        assertNull(mTestDragHost.mLastOpenedView);
    }

    @Test
    public void testNoDropWithoutClipData() {
        triggerDragEvent(DragEvent.ACTION_DRAG_ENTERED);

        final DragEvent dropEvent = DragEvents.createTestDropEvent(null);
        assertFalse(mListener.onDrag(mTestView, dropEvent));
    }

    @Test
    public void testDoDropWithClipData() {
        triggerDragEvent(DragEvent.ACTION_DRAG_ENTERED);

        final ClipData data = ClipDatas.createTestClipData();
        final DragEvent dropEvent = DragEvents.createTestDropEvent(data);
        mListener.onDrag(mTestView, dropEvent);

        assertSame(mTestView, mListener.mLastDropOnView);
        assertSame(dropEvent, mListener.mLastDropEvent);
    }

    protected boolean triggerDragEvent(int actionId) {
        final DragEvent testEvent = DragEvents.createTestDragEvent(actionId);

        return mListener.onDrag(mTestView, testEvent);
    }

    private static class TestDragListener extends ItemDragListener<TestDragHost> {

        private View mLastDropOnView;
        private DragEvent mLastDropEvent;

        protected TestDragListener(TestDragHost dragHost, Timer timer) {
            super(dragHost, timer);
        }

        @Override
        public TimerTask createOpenTask(View v) {
            TimerTask task = super.createOpenTask(v);
            TestTimer.Task testTask = new TestTimer.Task(task);

            return testTask;
        }

        @Override
        public boolean handleDropEventChecked(View v, DragEvent event) {
            mLastDropOnView = v;
            mLastDropEvent = event;
            return true;
        }

    }

    private static class TestDragHost implements ItemDragListener.DragHost {
        private View mHighlightedView;
        private View mLastOpenedView;

        @Override
        public void setDropTargetHighlight(View v, boolean highlight) {
            mHighlightedView = highlight ? v : null;
        }

        @Override
        public void runOnUiThread(Runnable runnable) {
            runnable.run();
        }

        @Override
        public void onViewHovered(View v) {
            mLastOpenedView = v;
        }
    }
}
