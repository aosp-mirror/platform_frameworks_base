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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedList;
import java.util.function.Consumer;

/**
 * Tests of {@link ScrollCaptureTargetResolver}.
 */
@RunWith(AndroidJUnit4.class)
public class ScrollCaptureTargetResolverTest {

    private static final long TEST_TIMEOUT_MS = 2000;
    private static final long RESOLVER_TIMEOUT_MS = 1000;

    private Handler mHandler;
    private TargetConsumer mTargetConsumer;

    @Before
    public void setUp() {
        mTargetConsumer = new TargetConsumer();
        mHandler = new Handler(getTargetContext().getMainLooper());
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void testEmptyQueue() throws InterruptedException {
        ScrollCaptureTargetResolver resolver = new ScrollCaptureTargetResolver(new LinkedList<>());
        resolver.start(mHandler, RESOLVER_TIMEOUT_MS, mTargetConsumer);

        // Test only
        resolver.waitForResult();

        ScrollCaptureTarget result = mTargetConsumer.getLastValue();
        assertNull("Expected null due to empty queue", result);
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void testNoValidTargets() throws InterruptedException {
        LinkedList<ScrollCaptureTarget> targetQueue = new LinkedList<>();

        // Supplies scrollBounds = null
        FakeScrollCaptureCallback callback1 = new FakeScrollCaptureCallback();
        callback1.setScrollBounds(null);
        ScrollCaptureTarget target1 = createTarget(callback1, new Rect(20, 30, 40, 50),
                new Point(0, 0), View.SCROLL_CAPTURE_HINT_AUTO);

        // Supplies scrollBounds = empty rect
        FakeScrollCaptureCallback callback2 = new FakeScrollCaptureCallback();
        callback2.setScrollBounds(new Rect());
        ScrollCaptureTarget target2 = createTarget(callback2, new Rect(20, 30, 40, 50),
                new Point(0, 20), View.SCROLL_CAPTURE_HINT_INCLUDE);

        targetQueue.add(target1);
        targetQueue.add(target2);

        ScrollCaptureTargetResolver resolver = new ScrollCaptureTargetResolver(targetQueue);
        resolver.start(mHandler, RESOLVER_TIMEOUT_MS, mTargetConsumer);

        // Test only
        resolver.waitForResult();

        ScrollCaptureTarget result = mTargetConsumer.getLastValue();
        assertNull("Expected null due to no valid targets", result);
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void testSingleTarget() throws InterruptedException {
        FakeScrollCaptureCallback callback = new FakeScrollCaptureCallback();
        ScrollCaptureTarget target = createTarget(callback,
                new Rect(20, 30, 40, 50), new Point(10, 10),
                View.SCROLL_CAPTURE_HINT_AUTO);
        callback.setScrollBounds(new Rect(2, 2, 18, 18));

        LinkedList<ScrollCaptureTarget> targetQueue = new LinkedList<>();
        targetQueue.add(target);
        ScrollCaptureTargetResolver resolver = new ScrollCaptureTargetResolver(targetQueue);
        resolver.start(mHandler, RESOLVER_TIMEOUT_MS, mTargetConsumer);

        // Test only
        resolver.waitForResult();

        ScrollCaptureTarget result = mTargetConsumer.getLastValue();
        assertSame("Excepted the same target as a result", target, result);
        assertEquals("result has wrong scroll bounds",
                new Rect(2, 2, 18, 18), result.getScrollBounds());
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void testSingleTarget_backgroundThread() throws InterruptedException {
        BackgroundTestCallback callback1 = new BackgroundTestCallback();
        ScrollCaptureTarget target1 = createTarget(callback1,
                new Rect(20, 30, 40, 50), new Point(10, 10),
                View.SCROLL_CAPTURE_HINT_AUTO);
        callback1.setDelay(100);
        callback1.setScrollBounds(new Rect(2, 2, 18, 18));

        LinkedList<ScrollCaptureTarget> targetQueue = new LinkedList<>();
        targetQueue.add(target1);

        ScrollCaptureTargetResolver resolver = new ScrollCaptureTargetResolver(targetQueue);
        resolver.start(mHandler, RESOLVER_TIMEOUT_MS, mTargetConsumer);

        // Test only
        resolver.waitForResult();

        ScrollCaptureTarget result = mTargetConsumer.getLastValue();
        assertSame("Excepted the single target1 as a result", target1, result);
        assertEquals("Result has wrong scroll bounds",
                new Rect(2, 2, 18, 18), result.getScrollBounds());
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void testPreferNonEmptyBounds() throws InterruptedException {
        LinkedList<ScrollCaptureTarget> targetQueue = new LinkedList<>();

        FakeScrollCaptureCallback callback1 = new FakeScrollCaptureCallback();
        callback1.setScrollBounds(new Rect());
        ScrollCaptureTarget target1 = createTarget(callback1, new Rect(20, 30, 40, 50),
                new Point(0, 0), View.SCROLL_CAPTURE_HINT_AUTO);

        FakeScrollCaptureCallback callback2 = new FakeScrollCaptureCallback();
        callback2.setScrollBounds(new Rect(0, 0, 20, 20));
        ScrollCaptureTarget target2 = createTarget(callback2, new Rect(20, 30, 40, 50),
                new Point(0, 20), View.SCROLL_CAPTURE_HINT_INCLUDE);

        FakeScrollCaptureCallback callback3 = new FakeScrollCaptureCallback();
        callback3.setScrollBounds(null);
        ScrollCaptureTarget target3 = createTarget(callback3, new Rect(20, 30, 40, 50),
                new Point(0, 40), View.SCROLL_CAPTURE_HINT_AUTO);

        targetQueue.add(target1);
        targetQueue.add(target2); // scrollBounds not null or empty()
        targetQueue.add(target3);

        ScrollCaptureTargetResolver resolver = new ScrollCaptureTargetResolver(targetQueue);
        resolver.start(mHandler, RESOLVER_TIMEOUT_MS, mTargetConsumer);
        resolver.waitForResult();

        ScrollCaptureTarget result = mTargetConsumer.getLastValue();
        assertEquals("Expected " + target2 + " as a result", target2, result);
        assertEquals("result has wrong scroll bounds",
                new Rect(0, 0, 20, 20), result.getScrollBounds());
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void testPreferHintInclude() throws InterruptedException {
        LinkedList<ScrollCaptureTarget> targetQueue = new LinkedList<>();

        FakeScrollCaptureCallback callback1 = new FakeScrollCaptureCallback();
        callback1.setScrollBounds(new Rect(0, 0, 20, 20));
        ScrollCaptureTarget target1 = createTarget(callback1, new Rect(20, 30, 40, 50),
                new Point(0, 0), View.SCROLL_CAPTURE_HINT_AUTO);

        FakeScrollCaptureCallback callback2 = new FakeScrollCaptureCallback();
        callback2.setScrollBounds(new Rect(1, 1, 19, 19));
        ScrollCaptureTarget target2 = createTarget(callback2, new Rect(20, 30, 40, 50),
                new Point(0, 20), View.SCROLL_CAPTURE_HINT_INCLUDE);

        FakeScrollCaptureCallback callback3 = new FakeScrollCaptureCallback();
        callback3.setScrollBounds(new Rect(2, 2, 18, 18));
        ScrollCaptureTarget target3 = createTarget(callback3, new Rect(20, 30, 40, 50),
                new Point(0, 40), View.SCROLL_CAPTURE_HINT_AUTO);

        targetQueue.add(target1);
        targetQueue.add(target2); // * INCLUDE > AUTO
        targetQueue.add(target3);

        ScrollCaptureTargetResolver resolver = new ScrollCaptureTargetResolver(targetQueue);
        resolver.start(mHandler, RESOLVER_TIMEOUT_MS, mTargetConsumer);

        resolver.waitForResult();

        ScrollCaptureTarget result = mTargetConsumer.getLastValue();
        assertEquals("input = " + targetQueue + " Expected " + target2
                + " as the result, due to hint=INCLUDE", target2, result);
        assertEquals("result has wrong scroll bounds",
                new Rect(1, 1, 19, 19), result.getScrollBounds());
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void testDescendantPreferred() throws InterruptedException {
        LinkedList<ScrollCaptureTarget> targetQueue = new LinkedList<>();

        ViewGroup targetView1 = new FakeRootView(getTargetContext(), 0, 0, 60, 60); // 60x60
        ViewGroup targetView2 = new FakeRootView(getTargetContext(), 20, 30, 40, 50); // 20x20
        ViewGroup targetView3 = new FakeRootView(getTargetContext(), 5, 5, 15, 15); // 10x10

        targetView1.addView(targetView2);
        targetView2.addView(targetView3);

        // Create first target with an unrelated parent
        FakeScrollCaptureCallback callback1 = new FakeScrollCaptureCallback();
        callback1.setScrollBounds(new Rect(0, 0, 60, 60));
        ScrollCaptureTarget target1 = createTargetWithView(targetView1, callback1,
                new Rect(0, 0, 60, 60),
                new Point(0, 0), View.SCROLL_CAPTURE_HINT_AUTO);

        // Create second target associated with a view within parent2
        FakeScrollCaptureCallback callback2 = new FakeScrollCaptureCallback();
        callback2.setScrollBounds(new Rect(0, 0, 20, 20));
        ScrollCaptureTarget target2 = createTargetWithView(targetView2, callback2,
                new Rect(0, 0, 20, 20),
                new Point(20, 30), View.SCROLL_CAPTURE_HINT_AUTO);

        // Create third target associated with a view within parent3
        FakeScrollCaptureCallback callback3 = new FakeScrollCaptureCallback();
        callback3.setScrollBounds(new Rect(0, 0, 15, 15));
        ScrollCaptureTarget target3 = createTargetWithView(targetView3, callback3,
                new Rect(0, 0, 15, 15),
                new Point(25, 35), View.SCROLL_CAPTURE_HINT_AUTO);

        targetQueue.add(target1); // auto, 60x60
        targetQueue.add(target2); // auto, 20x20
        targetQueue.add(target3); // auto, 15x15 <- innermost scrollable

        ScrollCaptureTargetResolver resolver = new ScrollCaptureTargetResolver(targetQueue);
        resolver.start(mHandler, RESOLVER_TIMEOUT_MS, mTargetConsumer);

        // Test only
        resolver.waitForResult();

        ScrollCaptureTarget result = mTargetConsumer.getLastValue();
        assertSame("Expected target3 as the result, due to relation", target3, result);
        assertEquals("result has wrong scroll bounds",
                new Rect(0, 0, 15, 15), result.getScrollBounds());
    }

    /**
     * If a timeout expires, late results are ignored.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    public void testTimeout() throws InterruptedException {
        LinkedList<ScrollCaptureTarget> targetQueue = new LinkedList<>();

        // callback 1, 10x10, hint=AUTO, responds immediately from bg thread
        BackgroundTestCallback callback1 = new BackgroundTestCallback();
        callback1.setScrollBounds(new Rect(5, 5, 15, 15));
        ScrollCaptureTarget target1 = createTarget(
                callback1, new Rect(20, 30, 40, 50), new Point(10, 10),
                View.SCROLL_CAPTURE_HINT_AUTO);
        targetQueue.add(target1);

        // callback 2, 20x20, hint=AUTO, responds after 5s from bg thread
        BackgroundTestCallback callback2 = new BackgroundTestCallback();
        callback2.setScrollBounds(new Rect(0, 0, 20, 20));
        callback2.setDelay(5000);
        ScrollCaptureTarget target2 = createTarget(
                callback2, new Rect(20, 30, 40, 50), new Point(10, 10),
                View.SCROLL_CAPTURE_HINT_AUTO);
        targetQueue.add(target2);

        // callback 3, 20x20, hint=INCLUDE, responds after 10s from bg thread
        BackgroundTestCallback callback3 = new BackgroundTestCallback();
        callback3.setScrollBounds(new Rect(0, 0, 20, 20));
        callback3.setDelay(10000);
        ScrollCaptureTarget target3 = createTarget(
                callback3, new Rect(20, 30, 40, 50), new Point(10, 10),
                View.SCROLL_CAPTURE_HINT_INCLUDE);
        targetQueue.add(target3);

        // callback 1 will be received
        // callback 2 & 3 will be ignored due to timeout

        ScrollCaptureTargetResolver resolver = new ScrollCaptureTargetResolver(targetQueue);
        resolver.start(mHandler, RESOLVER_TIMEOUT_MS, mTargetConsumer);

        resolver.waitForResult();

        ScrollCaptureTarget result = mTargetConsumer.getLastValue();
        assertSame("Expected target1 as the result, due to timeouts of others", target1, result);
        assertEquals("result has wrong scroll bounds",
                new Rect(5, 5, 15, 15), result.getScrollBounds());
        assertEquals("callback1 should have been called",
                1, callback1.getOnScrollCaptureSearchCount());
        assertEquals("callback2 should have been called",
                1, callback2.getOnScrollCaptureSearchCount());
        assertEquals("callback3 should have been called",
                1, callback3.getOnScrollCaptureSearchCount());
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void testWithCallbackMultipleReplies() throws InterruptedException {
        // Calls response methods 3 times each
        RepeatingCaptureCallback callback1 = new RepeatingCaptureCallback(3);
        callback1.setScrollBounds(new Rect(2, 2, 18, 18));
        ScrollCaptureTarget target1 = createTarget(callback1, new Rect(20, 30, 40, 50),
                new Point(10, 10), View.SCROLL_CAPTURE_HINT_AUTO);

        FakeScrollCaptureCallback callback2 = new FakeScrollCaptureCallback();
        callback2.setScrollBounds(new Rect(0, 0, 20, 20));
        ScrollCaptureTarget target2 = createTarget(callback2, new Rect(20, 30, 40, 50),
                new Point(10, 10), View.SCROLL_CAPTURE_HINT_AUTO);

        LinkedList<ScrollCaptureTarget> targetQueue = new LinkedList<>();
        targetQueue.add(target1);
        targetQueue.add(target2);

        ScrollCaptureTargetResolver resolver = new ScrollCaptureTargetResolver(targetQueue);
        resolver.start(mHandler, RESOLVER_TIMEOUT_MS, mTargetConsumer);

        resolver.waitForResult();

        ScrollCaptureTarget result = mTargetConsumer.getLastValue();
        assertSame("Expected target2 as the result, due to hint=INCLUDE", target2, result);
        assertEquals("result has wrong scroll bounds",
                new Rect(0, 0, 20, 20), result.getScrollBounds());
        assertEquals("callback1 should have been called once",
                1, callback1.getOnScrollCaptureSearchCount());
        assertEquals("callback2 should have been called once",
                1, callback2.getOnScrollCaptureSearchCount());
    }

    private static class TargetConsumer implements Consumer<ScrollCaptureTarget> {
        volatile ScrollCaptureTarget mResult;
        int mAcceptCount;

        ScrollCaptureTarget getLastValue() {
            return mResult;
        }

        int acceptCount() {
            return mAcceptCount;
        }

        @Override
        public void accept(@Nullable ScrollCaptureTarget t) {
            mAcceptCount++;
            mResult = t;
        }
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


    static class FakeRootView extends ViewGroup implements ViewParent {
        FakeRootView(Context context, int l, int t, int r, int b) {
            super(context);
            layout(l, t, r, b);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
        }
    }

    static class FakeScrollCaptureCallback implements ScrollCaptureCallback {
        private Rect mScrollBounds;
        private long mDelayMillis;
        private int mOnScrollCaptureSearchCount;

        public int getOnScrollCaptureSearchCount() {
            return mOnScrollCaptureSearchCount;
        }

        @Override
        public void onScrollCaptureSearch(Consumer<Rect> onReady) {
            mOnScrollCaptureSearchCount++;
            run(() -> {
                Rect b = getScrollBounds();
                onReady.accept(b);
            });
        }

        @Override
        public void onScrollCaptureStart(ScrollCaptureSession session, Runnable onReady) {
            run(onReady);
        }

        @Override
        public void onScrollCaptureImageRequest(ScrollCaptureSession session, Rect captureArea) {
            run(() -> session.notifyBufferSent(0, captureArea));
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
            delay();
            r.run();
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

    static class RepeatingCaptureCallback extends FakeScrollCaptureCallback {
        private int mRepeatCount;

        RepeatingCaptureCallback(int repeatCount) {
            mRepeatCount = repeatCount;
        }

        protected void run(Runnable r) {
            delay();
            for (int i = 0; i < mRepeatCount; i++) {
                r.run();
            }
        }
    }

    /** Response to async calls on an arbitrary background thread */
    static class BackgroundTestCallback extends FakeScrollCaptureCallback {
        static int sCount = 0;
        private void runOnBackgroundThread(Runnable r) {
            final Runnable target = () -> {
                delay();
                r.run();
            };
            Thread t = new Thread(target);
            synchronized (BackgroundTestCallback.this) {
                sCount++;
            }
            t.setName("Background-Thread-" + sCount);
            t.start();
        }

        @Override
        protected void run(Runnable r) {
            runOnBackgroundThread(r);
        }
    }
}
