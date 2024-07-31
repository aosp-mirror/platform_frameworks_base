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

package android.view;

import static androidx.test.InstrumentationRegistry.getTargetContext;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Tests of {@link ScrollCaptureSearchResults}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ScrollCaptureSearchResultsTest {


    private static final Rect EMPTY_RECT = new Rect();
    private static final String TAG = "Test";

    private final Executor mDirectExec = Runnable::run;
    private Executor mBgExec;

    @Before
    public void setUp() {
        mBgExec = Executors.newSingleThreadExecutor();
    }

    @Test
    public void testNoTargets() {
        ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(mDirectExec);
        assertTrue(results.isComplete());

        assertNull("Expected null due to empty queue", results.getTopResult());
    }

    @Test
    public void testNoValidTargets() {
        ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(mDirectExec);

        FakeScrollCaptureCallback callback1 = new FakeScrollCaptureCallback(mDirectExec);
        callback1.setScrollBounds(EMPTY_RECT);
        ScrollCaptureTarget target1 = createTarget(callback1, new Rect(20, 30, 40, 50),
                new Point(0, 0), View.SCROLL_CAPTURE_HINT_AUTO);

        // Supplies scrollBounds = empty rect
        FakeScrollCaptureCallback callback2 = new FakeScrollCaptureCallback(mDirectExec);
        callback2.setScrollBounds(EMPTY_RECT);
        ScrollCaptureTarget target2 = createTarget(callback2, new Rect(20, 30, 40, 50),
                new Point(0, 20), View.SCROLL_CAPTURE_HINT_INCLUDE);

        results.addTarget(target1);
        results.addTarget(target2);

        assertTrue(results.isComplete());
        assertNull("Expected null due to no valid targets", results.getTopResult());
    }

    @Test
    public void testSingleTarget() {
        ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(mDirectExec);
        FakeScrollCaptureCallback callback = new FakeScrollCaptureCallback(mDirectExec);
        ScrollCaptureTarget target = createTarget(callback,
                new Rect(20, 30, 40, 50), new Point(10, 10),
                View.SCROLL_CAPTURE_HINT_AUTO);
        callback.setScrollBounds(new Rect(2, 2, 18, 18));

        results.addTarget(target);
        assertTrue(results.isComplete());

        ScrollCaptureTarget result = results.getTopResult();
        assertSame("Excepted the same target as a result", target, result);
        assertEquals("result has wrong scroll bounds",
                new Rect(2, 2, 18, 18), result.getScrollBounds());
    }

    @Test
    public void testSingleTarget_backgroundThread() throws InterruptedException {
        FakeScrollCaptureCallback callback1 = new FakeScrollCaptureCallback(mBgExec);
        ScrollCaptureTarget target1 = createTarget(callback1,
                new Rect(20, 30, 40, 50), new Point(10, 10),
                View.SCROLL_CAPTURE_HINT_AUTO);
        callback1.setDelay(100);
        callback1.setScrollBounds(new Rect(2, 2, 18, 18));

        ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(mDirectExec);
        results.addTarget(target1);

        CountDownLatch latch = new CountDownLatch(1);
        results.setOnCompleteListener(latch::countDown);
        if (!latch.await(200, TimeUnit.MILLISECONDS)) {
            fail("onComplete listener was expected");
        }

        ScrollCaptureTarget result = results.getTopResult();
        assertSame("Excepted the single target1 as a result", target1, result);
        assertEquals("Result has wrong scroll bounds",
                new Rect(2, 2, 18, 18), result.getScrollBounds());
    }

    @Test
    public void testRanking() {

        // 1 - Empty
        FakeScrollCaptureCallback callback1 = new FakeScrollCaptureCallback(mDirectExec);
        callback1.setScrollBounds(EMPTY_RECT);
        ViewGroup targetView1 = new FakeView(getTargetContext(), 0, 0, 60, 60, 1);
        ScrollCaptureTarget target1 = createTargetWithView(targetView1, callback1,
                new Rect(0, 0, 60, 60), new Point(0, 0), View.SCROLL_CAPTURE_HINT_AUTO);

        // 2 - 10x10 + HINT_INCLUDE
        FakeScrollCaptureCallback callback2 = new FakeScrollCaptureCallback(mDirectExec);
        callback2.setScrollBounds(new Rect(0, 0, 10, 10));
        ViewGroup targetView2 = new FakeView(getTargetContext(), 0, 0, 60, 60, 2);
        ScrollCaptureTarget target2 = createTargetWithView(targetView2, callback2,
                 new Rect(0, 0, 60, 60), new Point(0, 0), View.SCROLL_CAPTURE_HINT_INCLUDE);

        // 3 - 20x20 + AUTO
        FakeScrollCaptureCallback callback3 = new FakeScrollCaptureCallback(mDirectExec);
        callback3.setScrollBounds(new Rect(0, 0, 20, 20));
        ViewGroup targetView3 = new FakeView(getTargetContext(), 0, 0, 60, 60, 3);
        ScrollCaptureTarget target3 = createTargetWithView(targetView3, callback3,
                new Rect(0, 0, 60, 60), new Point(0, 0), View.SCROLL_CAPTURE_HINT_AUTO);

        // 4 - 30x30 + AUTO
        FakeScrollCaptureCallback callback4 = new FakeScrollCaptureCallback(mDirectExec);
        callback4.setScrollBounds(new Rect(0, 0, 10, 10));
        ViewGroup targetView4 = new FakeView(getTargetContext(), 0, 0, 60, 60, 4);
        ScrollCaptureTarget target4 = createTargetWithView(targetView4, callback4,
                new Rect(0, 0, 60, 60), new Point(0, 0), View.SCROLL_CAPTURE_HINT_AUTO);

        // 5 - 10x10 + child of #4
        FakeScrollCaptureCallback callback5 = new FakeScrollCaptureCallback(mDirectExec);
        callback5.setScrollBounds(new Rect(0, 0, 10, 10));
        ViewGroup targetView5 = new FakeView(getTargetContext(), 0, 0, 60, 60, 5);
        ScrollCaptureTarget target5 = createTargetWithView(targetView5, callback5,
                new Rect(0, 0, 60, 60), new Point(0, 0), View.SCROLL_CAPTURE_HINT_AUTO);
        targetView4.addView(targetView5);

        // 6 - 20x20 + child of #4
        FakeScrollCaptureCallback callback6 = new FakeScrollCaptureCallback(mDirectExec);
        callback6.setScrollBounds(new Rect(0, 0, 20, 20));
        ViewGroup targetView6 = new FakeView(getTargetContext(), 0, 0, 60, 60, 6);
        ScrollCaptureTarget target6 = createTargetWithView(targetView6, callback6,
                new Rect(0, 0, 60, 60), new Point(0, 0), View.SCROLL_CAPTURE_HINT_AUTO);
        targetView4.addView(targetView6);

        ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(mDirectExec);
        results.addTarget(target1);
        results.addTarget(target2);
        results.addTarget(target3);
        results.addTarget(target4);
        results.addTarget(target5);
        results.addTarget(target6);
        assertTrue(results.isComplete());

        // Verify "top" result
        assertEquals(target2, results.getTopResult());

        // Verify priority ("best" first)
        assertThat(results.getTargets())
                .containsExactly(
                        target2,
                        target6,
                        target5,
                        target4,
                        target3,
                        target1);
    }

    /**
     * If a timeout expires, late results are ignored.
     */
    @Test
    public void testTimeout() {
        ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(mDirectExec);

        // callback 1, 10x10, hint=AUTO, responds after 100ms from bg thread
        FakeScrollCaptureCallback callback1 = new FakeScrollCaptureCallback(mBgExec);
        callback1.setScrollBounds(new Rect(5, 5, 15, 15));
        callback1.setDelay(100);
        ScrollCaptureTarget target1 = createTarget(
                callback1, new Rect(20, 30, 40, 50), new Point(10, 10),
                View.SCROLL_CAPTURE_HINT_AUTO);
        results.addTarget(target1);

        // callback 2, 20x20, hint=AUTO, responds after 5s from bg thread
        FakeScrollCaptureCallback callback2 = new FakeScrollCaptureCallback(mBgExec);
        callback2.setScrollBounds(new Rect(0, 0, 20, 20));
        callback2.setDelay(1000);
        ScrollCaptureTarget target2 = createTarget(
                callback2, new Rect(20, 30, 40, 50), new Point(10, 10),
                View.SCROLL_CAPTURE_HINT_AUTO);
        results.addTarget(target2);

        // callback 3, 20x20, hint=INCLUDE, responds after 10s from bg thread
        FakeScrollCaptureCallback callback3 = new FakeScrollCaptureCallback(mBgExec);
        callback3.setScrollBounds(new Rect(0, 0, 20, 20));
        callback3.setDelay(1500);
        ScrollCaptureTarget target3 = createTarget(
                callback3, new Rect(20, 30, 40, 50), new Point(10, 10),
                View.SCROLL_CAPTURE_HINT_INCLUDE);
        results.addTarget(target3);

        // callback 1 will be received
        // callback 2 & 3 will be ignored due to timeout
        SystemClock.sleep(500);
        results.finish();

        ScrollCaptureTarget result = results.getTopResult();
        assertSame("Expected target1 as the result, due to timeouts of others", target1, result);
        assertEquals("callback1 should have been called",
                1, callback1.getOnScrollCaptureSearchCount());
        assertEquals("callback2 should have been called",
                1, callback2.getOnScrollCaptureSearchCount());
        assertEquals("callback3 should have been called",
                1, callback3.getOnScrollCaptureSearchCount());

        assertEquals("result has wrong scroll bounds",
                new Rect(5, 5, 15, 15), result.getScrollBounds());
        assertNull("target2 should not have been updated",
                target2.getScrollBounds());
        assertNull("target3 should not have been updated",
                target3.getScrollBounds());
    }

    @Test
    public void testWithCallbackMultipleReplies() {
        // Calls response methods 3 times each
        ScrollCaptureCallback callback1 = new CallbackStub() {
            @Override
            public void onScrollCaptureSearch(@NonNull CancellationSignal signal,
                    @NonNull Consumer<Rect> onReady) {
                onReady.accept(new Rect(1, 2, 3, 4));
                onReady.accept(new Rect(9, 10, 11, 12));
            }
        };

        ScrollCaptureTarget target1 = createTarget(callback1, new Rect(20, 30, 40, 50),
                new Point(10, 10), View.SCROLL_CAPTURE_HINT_AUTO);

        ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(mDirectExec);
        results.addTarget(target1);
        assertTrue(results.isComplete());

        ScrollCaptureTarget result = results.getTopResult();
        assertSame("Expected target1", target1, result);
        assertEquals("result has wrong scroll bounds",
                new Rect(1, 2, 3, 4), result.getScrollBounds());
    }

    private void setupTargetView(View view, Rect localVisibleRect, int scrollCaptureHint) {
        view.setScrollCaptureHint(scrollCaptureHint);
        view.onVisibilityAggregated(true);
        // Treat any offset as padding, outset localVisibleRect on all sides and use this as
        // child bounds
        Rect bounds = new Rect(localVisibleRect);
        bounds.inset(-bounds.left, -bounds.top, bounds.left, bounds.top);
        view.layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
        view.onVisibilityAggregated(true);
    }

    private ScrollCaptureTarget createTarget(ScrollCaptureCallback callback, Rect localVisibleRect,
            Point positionInWindow, int scrollCaptureHint) {
        View mockView = new View(getTargetContext());
        return createTargetWithView(mockView, callback, localVisibleRect, positionInWindow,
                scrollCaptureHint);
    }

    private ScrollCaptureTarget createTargetWithView(View view, ScrollCaptureCallback callback,
            Rect localVisibleRect, Point positionInWindow, int scrollCaptureHint) {
        setupTargetView(view, localVisibleRect, scrollCaptureHint);
        return new ScrollCaptureTarget(view, localVisibleRect, positionInWindow, callback);
    }


    static class FakeView extends ViewGroup implements ViewParent {
        FakeView(Context context, int l, int t, int r, int b, int id) {
            super(context);
            layout(l, t, r, b);
            setId(id);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
        }
    }

    static class FakeScrollCaptureCallback implements ScrollCaptureCallback {
        private final Executor mExecutor;
        private Rect mScrollBounds;
        private long mDelayMillis;
        private int mOnScrollCaptureSearchCount;
        FakeScrollCaptureCallback(Executor executor) {
            mExecutor = executor;
        }
        public int getOnScrollCaptureSearchCount() {
            return mOnScrollCaptureSearchCount;
        }

        @Override
        public void onScrollCaptureSearch(CancellationSignal signal, Consumer<Rect> onReady) {
            mOnScrollCaptureSearchCount++;
            run(() -> {
                Rect b = getScrollBounds();
                onReady.accept(b);
            });
        }

        @Override
        public void onScrollCaptureStart(ScrollCaptureSession session, CancellationSignal signal,
                Runnable onReady) {
            run(onReady);
        }

        @Override
        public void onScrollCaptureImageRequest(ScrollCaptureSession session,
                CancellationSignal signal, Rect captureArea, Consumer<Rect> onReady) {
            run(() -> onReady.accept(captureArea));
        }

        @Override
        public void onScrollCaptureEnd(Runnable onReady) {
            run(onReady);
        }

        public void setScrollBounds(@Nullable Rect scrollBounds) {
            mScrollBounds = scrollBounds;
        }

        public void setDelay(long delayMillis) {
            mDelayMillis = delayMillis;
        }

        protected Rect getScrollBounds() {
            return mScrollBounds;
        }

        protected void run(Runnable r) {
            mExecutor.execute(() -> {
                delay();
                r.run();
            });
        }

        protected void delay() {
            if (mDelayMillis > 0) {
                try {
                    Thread.sleep(mDelayMillis);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }
    }
    static class CallbackStub implements ScrollCaptureCallback {
        @Override
        public void onScrollCaptureSearch(@NonNull CancellationSignal signal,
                @NonNull Consumer<Rect> onReady) {
        }

        @Override
        public void onScrollCaptureStart(@NonNull ScrollCaptureSession session,
                @NonNull CancellationSignal signal, @NonNull Runnable onReady) {
        }

        @Override
        public void onScrollCaptureImageRequest(@NonNull ScrollCaptureSession session,
                @NonNull CancellationSignal signal, @NonNull Rect captureArea,
                Consumer<Rect> onReady) {
        }

        @Override
        public void onScrollCaptureEnd(@NonNull Runnable onReady) {
        }
    }
}
