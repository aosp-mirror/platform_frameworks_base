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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.testng.AssertJUnit.assertSame;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Exercises Scroll Capture search in {@link ViewGroup}.
 */
@Presubmit
@SmallTest
@RunWith(MockitoJUnitRunner.class)
public class ViewGroupScrollCaptureTest {

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    /** Make sure the hint flags are saved and loaded correctly. */
    @Test
    public void testSetScrollCaptureHint() throws Exception {
        final Context context = getInstrumentation().getContext();
        final MockViewGroup viewGroup = new MockViewGroup(context);

        assertNotNull(viewGroup);
        assertEquals("Default scroll capture hint flags should be [SCROLL_CAPTURE_HINT_AUTO]",
                ViewGroup.SCROLL_CAPTURE_HINT_AUTO, viewGroup.getScrollCaptureHint());

        viewGroup.setScrollCaptureHint(View.SCROLL_CAPTURE_HINT_INCLUDE);
        assertEquals("The scroll capture hint was not stored correctly.",
                ViewGroup.SCROLL_CAPTURE_HINT_INCLUDE, viewGroup.getScrollCaptureHint());

        viewGroup.setScrollCaptureHint(ViewGroup.SCROLL_CAPTURE_HINT_EXCLUDE);
        assertEquals("The scroll capture hint was not stored correctly.",
                ViewGroup.SCROLL_CAPTURE_HINT_EXCLUDE, viewGroup.getScrollCaptureHint());

        viewGroup.setScrollCaptureHint(ViewGroup.SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS);
        assertEquals("The scroll capture hint was not stored correctly.",
                ViewGroup.SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS,
                viewGroup.getScrollCaptureHint());

        viewGroup.setScrollCaptureHint(ViewGroup.SCROLL_CAPTURE_HINT_INCLUDE
                | ViewGroup.SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS);
        assertEquals("The scroll capture hint was not stored correctly.",
                ViewGroup.SCROLL_CAPTURE_HINT_INCLUDE
                        | ViewGroup.SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS,
                viewGroup.getScrollCaptureHint());

        viewGroup.setScrollCaptureHint(ViewGroup.SCROLL_CAPTURE_HINT_EXCLUDE
                | ViewGroup.SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS);
        assertEquals("The scroll capture hint was not stored correctly.",
                ViewGroup.SCROLL_CAPTURE_HINT_EXCLUDE
                        | ViewGroup.SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS,
                viewGroup.getScrollCaptureHint());
    }

    /** Make sure the hint flags are saved and loaded correctly. */
    @Test
    public void testSetScrollCaptureHint_mutuallyExclusiveFlags() throws Exception {
        final Context context = getInstrumentation().getContext();
        final MockViewGroup viewGroup = new MockViewGroup(context);

        viewGroup.setScrollCaptureHint(
                View.SCROLL_CAPTURE_HINT_INCLUDE | View.SCROLL_CAPTURE_HINT_EXCLUDE);
        assertEquals("Mutually exclusive flags were not resolved correctly",
                ViewGroup.SCROLL_CAPTURE_HINT_EXCLUDE, viewGroup.getScrollCaptureHint());
    }

    /**
     * Ensure a ViewGroup with 'scrollCaptureHint=auto', but no ScrollCaptureCallback set dispatches
     * correctly. Verifies that the framework helper is called. Verifies a that non-null callback
     * return results in an expected target in the results.
     */
    @MediumTest
    @Test
    public void testDispatchScrollCaptureSearch_noCallback_hintAuto() throws Exception {
        final Context context = getInstrumentation().getContext();
        final MockViewGroup viewGroup = new MockViewGroup(context, 0, 0, 200, 200);
        TestScrollCaptureCallback callback = new TestScrollCaptureCallback();

        // When system internal scroll capture is requested, this callback is returned.
        viewGroup.setScrollCaptureCallbackInternalForTest(callback);

        Rect localVisibleRect = new Rect(0, 0, 200, 200);
        Point windowOffset = new Point();
        ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(DIRECT_EXECUTOR);

        // Dispatch
        viewGroup.dispatchScrollCaptureSearch(localVisibleRect, windowOffset, results::addTarget);
        callback.completeSearchRequest(new Rect(1, 2, 3, 4));
        assertTrue(results.isComplete());

        // Verify the target is as expected.
        ScrollCaptureTarget target = results.getTopResult();
        assertNotNull("Target not found", target);
        assertSame("Target has the wrong callback", callback, target.getCallback());
        assertSame("Target has the wrong View", viewGroup, target.getContainingView());
        assertEquals("Target hint is incorrect", View.SCROLL_CAPTURE_HINT_AUTO,
                target.getContainingView().getScrollCaptureHint());
    }

    /**
     * Ensure a ViewGroup with 'scrollCaptureHint=exclude' is ignored. The Framework helper is
     * stubbed to return a callback. Verifies that the framework helper is not called (because of
     * exclude), and no scroll capture target is added to the results.
     */
    @MediumTest
    @Test
    public void testDispatchScrollCaptureSearch_noCallback_hintExclude() throws Exception {
        final Context context = getInstrumentation().getContext();
        final MockViewGroup viewGroup =
                new MockViewGroup(context, 0, 0, 200, 200, View.SCROLL_CAPTURE_HINT_EXCLUDE);

        TestScrollCaptureCallback callback = new TestScrollCaptureCallback();

        // When system internal scroll capture is requested, this callback is returned.
        viewGroup.setScrollCaptureCallbackInternalForTest(callback);

        Rect localVisibleRect = new Rect(0, 0, 200, 200);
        Point windowOffset = new Point();
        ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(DIRECT_EXECUTOR);
        assertTrue(results.isComplete());

        // Dispatch
        viewGroup.dispatchScrollCaptureSearch(localVisibleRect, windowOffset, results::addTarget);
        callback.verifyZeroInteractions();

        // Verify the results.
        assertTrue("Results should be empty.", results.isEmpty());
    }

    /**
     * Ensure that a ViewGroup with 'scrollCaptureHint=auto', and a scroll capture callback set
     * dispatches as expected. Also verifies that the system fallback support is not called, and the
     * the returned target is constructed correctly.
     */
    @MediumTest
    @Test
    public void testDispatchScrollCaptureSearch_withCallback_hintAuto() throws Exception {
        final Context context = getInstrumentation().getContext();
        MockViewGroup viewGroup = new MockViewGroup(context, 0, 0, 200, 200);

        TestScrollCaptureCallback callback = new TestScrollCaptureCallback();
        TestScrollCaptureCallback callback2 = new TestScrollCaptureCallback();

        // With an already provided scroll capture callback
        viewGroup.setScrollCaptureCallback(callback);

        // When system internal scroll capture is requested, this callback is returned.
        viewGroup.setScrollCaptureCallbackInternalForTest(callback2);

        Rect localVisibleRect = new Rect(0, 0, 200, 200);
        Point windowOffset = new Point();
        ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(DIRECT_EXECUTOR);

        // Dispatch to the ViewGroup
        viewGroup.dispatchScrollCaptureSearch(localVisibleRect, windowOffset, results::addTarget);
        callback.completeSearchRequest(new Rect(1, 2, 3, 4));

        // Verify the target is as expected.
        assertFalse(results.isEmpty());
        assertTrue(results.isComplete());

        // internal framework callback was not requested
        callback2.verifyZeroInteractions();

        ScrollCaptureTarget target = results.getTopResult();

        assertNotNull("Target not found", target);
        assertSame("Target has the wrong callback", callback, target.getCallback());
        assertSame("Target has the wrong View", viewGroup, target.getContainingView());
        assertEquals("Target hint is incorrect", View.SCROLL_CAPTURE_HINT_AUTO,
                target.getContainingView().getScrollCaptureHint());
    }

    /**
     * Ensure a ViewGroup with a callback set, but 'scrollCaptureHint=exclude' is ignored. The
     * exclude flag takes precedence.  Verifies that the framework helper is not called (because of
     * exclude, and a callback being set), and no scroll capture target is added to the results.
     */
    @MediumTest
    @Test
    public void testDispatchScrollCaptureSearch_withCallback_hintExclude() throws Exception {
        final Context context = getInstrumentation().getContext();
        MockViewGroup viewGroup =
                new MockViewGroup(context, 0, 0, 200, 200, View.SCROLL_CAPTURE_HINT_EXCLUDE);

        TestScrollCaptureCallback callback = new TestScrollCaptureCallback();

        // With an already provided scroll capture callback
        viewGroup.setScrollCaptureCallback(callback);

        Rect localVisibleRect = new Rect(0, 0, 200, 200);
        Point windowOffset = new Point();
        ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(DIRECT_EXECUTOR);

        // Dispatch to the ViewGroup itself
        viewGroup.dispatchScrollCaptureSearch(localVisibleRect, windowOffset, results::addTarget);
        callback.verifyZeroInteractions();

        // Has callback, but hint=excluded, so excluded.
        assertNull(results.getTopResult());
    }

    /**
     * Test scroll capture search dispatch to child views.
     * <p>
     * Verifies computation of child visible bounds.
     * TODO: with scrollX / scrollY, split up into discrete tests
     */
    @MediumTest
    @Test
    public void testDispatchScrollCaptureSearch_toChildren() throws Exception {
        final Context context = getInstrumentation().getContext();
        final MockViewGroup viewGroup = new MockViewGroup(context, 0, 0, 200, 200);

        Rect localVisibleRect = new Rect(25, 50, 175, 150);
        Point windowOffset = new Point(0, 0);

        //        visible area
        //       |<- l=25,    |
        //       |    r=175 ->|
        // +--------------------------+
        // | view1 (0, 0, 200, 25)    |
        // +---------------+----------+
        // |               |          |
        // | view2         | view4    | --+
        // | (0, 25,       |    (inv) |   | visible area
        // |      150, 100)|          |   |
        // +---------------+----------+   | t=50, b=150
        // | view3         | view5    |   |
        // | (0, 100       |(150, 100 | --+
        // |     200, 200) | 200, 200)|
        // |               |          |
        // |               |          |
        // +---------------+----------+ (200,200)

        // View 1 is fully clipped and not visible.
        final MockView view1 = new MockView(context, 0, 0, 200, 25);
        viewGroup.addView(view1);

        // View 2 is partially visible. (75x75)
        final MockView view2 = new MockView(context, 0, 25, 150, 100);
        viewGroup.addView(view2);

        TestScrollCaptureCallback callback1 = new TestScrollCaptureCallback();

        // View 3 is partially visible (175x50)
        // Pretend View3 can scroll by having framework provide fallback support
        final MockView view3 = new MockView(context, 0, 100, 200, 200);
        // When system internal scroll capture is requested for this view, return this callback.
        view3.setScrollCaptureCallbackInternalForTest(callback1);
        viewGroup.addView(view3);

        // View 4 is invisible and should be ignored.
        final MockView view4 = new MockView(context, 150, 25, 200, 100, View.INVISIBLE);
        viewGroup.addView(view4);

        TestScrollCaptureCallback callback2 = new TestScrollCaptureCallback();

        // View 5 is partially visible and explicitly included via flag. (25x50)
        final MockView view5 = new MockView(context, 150, 100, 200, 200);
        view5.setScrollCaptureCallback(callback2);
        view5.setScrollCaptureHint(View.SCROLL_CAPTURE_HINT_INCLUDE);
        viewGroup.addView(view5);

        // Where targets are added
        final ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(DIRECT_EXECUTOR);

        // Dispatch to the ViewGroup
        viewGroup.dispatchScrollCaptureSearch(localVisibleRect, windowOffset, results::addTarget);
        callback1.completeSearchRequest(new Rect(0, 0, 200, 100));
        callback2.completeSearchRequest(new Rect(0, 0, 50, 100));
        assertTrue(results.isComplete());

        // View 1 is entirely clipped by the parent and not visible, dispatch
        // skips this view entirely.
        view1.assertDispatchScrollCaptureSearchCount(0);
        view1.assertCreateScrollCaptureCallbackInternalCount(0);

        // View 2, verify the computed localVisibleRect and windowOffset are correctly transformed
        // to the child coordinate space
        view2.assertDispatchScrollCaptureSearchCount(1);
        view2.assertDispatchScrollCaptureSearchLastArgs(
                new Rect(25, 25, 150, 75), new Point(0, 25));
        // No callback set, so the framework is asked for support
        view2.assertCreateScrollCaptureCallbackInternalCount(1);

        // View 3, verify the computed localVisibleRect and windowOffset are correctly transformed
        // to the child coordinate space
        view3.assertDispatchScrollCaptureSearchCount(1);
        view3.assertDispatchScrollCaptureSearchLastArgs(
                new Rect(25, 0, 175, 50), new Point(0, 100));
        // No callback set, so the framework is asked for support
        view3.assertCreateScrollCaptureCallbackInternalCount(1);

        // view4 is invisible, so it should be skipped entirely.
        view4.assertDispatchScrollCaptureSearchCount(0);
        view4.assertCreateScrollCaptureCallbackInternalCount(0);

        // view5 is partially visible
        view5.assertDispatchScrollCaptureSearchCount(1);
        view5.assertDispatchScrollCaptureSearchLastArgs(
                new Rect(0, 0, 25, 50), new Point(150, 100));
        // view5 has a callback set on it, so internal framework support should not be consulted.
        view5.assertCreateScrollCaptureCallbackInternalCount(0);

        // 2 views should have been returned, view3 & view5
        assertFalse(results.isEmpty());
        assertTrue(results.isComplete());

        ScrollCaptureTarget target = results.getTopResult();
        assertNotNull("Target not found", target);
        assertSame("Result is the wrong View", view5, target.getContainingView());
        assertSame("Result is the wrong callback", callback2, target.getCallback());
        assertEquals("First target hint is incorrect", View.SCROLL_CAPTURE_HINT_INCLUDE,
                target.getContainingView().getScrollCaptureHint());
    }

    /**
     * Tests the effect of padding on scroll capture search dispatch.
     * <p>
     * Verifies computation of child visible bounds with padding.
     */
    @MediumTest
    @Test
    public void testOnScrollCaptureSearch_withPadding() {
        final Context context = getInstrumentation().getContext();

        Rect windowBounds = new Rect(0, 0, 200, 200);
        Point windowOffset = new Point(0, 0);

        final MockViewGroup parent = new MockViewGroup(context, 0, 0, 200, 200);
        parent.setPadding(25, 50, 25, 50);
        parent.setClipToPadding(true); // (default)

        final MockView view1 = new MockView(context, 0, -100, 200, 100);
        parent.addView(view1);

        final MockView view2 = new MockView(context, 0, 0, 200, 200);
        parent.addView(view2);

        final MockViewGroup view3 = new MockViewGroup(context, 0, 100, 200, 300);
        parent.addView(view3);
        view3.setPadding(25, 25, 25, 25);
        view3.setClipToPadding(true);

        // Where targets are added
        final ScrollCaptureSearchResults results = new ScrollCaptureSearchResults(DIRECT_EXECUTOR);

        // Dispatch to the ViewGroup
        parent.dispatchScrollCaptureSearch(windowBounds, windowOffset, results::addTarget);

        // Verify padding (with clipToPadding) is subtracted from visibleBounds
        parent.assertOnScrollCaptureSearchLastArgs(new Rect(25, 50, 175, 150), new Point(0, 0));

        view1.assertOnScrollCaptureSearchLastArgs(
                new Rect(25, 150, 175, 200), new Point(0, -100));

        view2.assertOnScrollCaptureSearchLastArgs(
                new Rect(25, 50, 175, 150), new Point(0, 0));

        // Account for padding on view3 as well (top == 25px)
        view3.assertOnScrollCaptureSearchLastArgs(
                new Rect(25, 25, 175, 50), new Point(0, 100));
    }

    public static final class MockView extends View {
        private ScrollCaptureCallback mInternalCallback;

        private int mDispatchScrollCaptureSearchNumCalls;
        private Rect mDispatchScrollCaptureSearchLastLocalVisibleRect;
        private Point mDispatchScrollCaptureSearchLastWindowOffset;
        private int mCreateScrollCaptureCallbackInternalCount;
        private Rect mOnScrollCaptureSearchLastLocalVisibleRect;
        private Point mOnScrollCaptureSearchLastWindowOffset;

        MockView(Context context) {
            this(context, /* left */ 0, /* top */0, /* right */ 0, /* bottom */0);
        }

        MockView(Context context, int left, int top, int right, int bottom) {
            this(context, left, top, right, bottom, View.VISIBLE);
        }

        MockView(Context context, int left, int top, int right, int bottom, int visibility) {
            super(context);
            setVisibility(visibility);
            setFrame(left, top, right, bottom);
        }

        public void setScrollCaptureCallbackInternalForTest(ScrollCaptureCallback internal) {
            mInternalCallback = internal;
        }

        void assertDispatchScrollCaptureSearchCount(int count) {
            assertEquals("Unexpected number of calls to dispatchScrollCaptureSearch",
                    count, mDispatchScrollCaptureSearchNumCalls);
        }

        void assertDispatchScrollCaptureSearchLastArgs(Rect localVisibleRect, Point windowOffset) {
            assertEquals("arg localVisibleRect was incorrect.",
                    localVisibleRect, mDispatchScrollCaptureSearchLastLocalVisibleRect);
            assertEquals("arg windowOffset was incorrect.",
                    windowOffset, mDispatchScrollCaptureSearchLastWindowOffset);
        }

        void assertCreateScrollCaptureCallbackInternalCount(int count) {
            assertEquals("Unexpected number of calls to createScrollCaptureCallbackInternal",
                    count, mCreateScrollCaptureCallbackInternalCount);
        }

        void reset() {
            mDispatchScrollCaptureSearchNumCalls = 0;
            mDispatchScrollCaptureSearchLastWindowOffset = null;
            mDispatchScrollCaptureSearchLastLocalVisibleRect = null;
            mCreateScrollCaptureCallbackInternalCount = 0;

        }

        @Override
        public void onScrollCaptureSearch(Rect localVisibleRect, Point windowOffset,
                Consumer<ScrollCaptureTarget> targets) {
            super.onScrollCaptureSearch(localVisibleRect, windowOffset, targets);
            mOnScrollCaptureSearchLastLocalVisibleRect = new Rect(localVisibleRect);
            mOnScrollCaptureSearchLastWindowOffset = new Point(windowOffset);
        }

        void assertOnScrollCaptureSearchLastArgs(Rect localVisibleRect, Point windowOffset) {
            assertEquals("arg localVisibleRect was incorrect.",
                    localVisibleRect, mOnScrollCaptureSearchLastLocalVisibleRect);
            assertEquals("arg windowOffset was incorrect.",
                    windowOffset, mOnScrollCaptureSearchLastWindowOffset);
        }

        @Override
        public void dispatchScrollCaptureSearch(Rect localVisibleRect, Point windowOffset,
                Consumer<ScrollCaptureTarget> results) {
            mDispatchScrollCaptureSearchNumCalls++;
            mDispatchScrollCaptureSearchLastLocalVisibleRect = new Rect(localVisibleRect);
            mDispatchScrollCaptureSearchLastWindowOffset = new Point(windowOffset);
            super.dispatchScrollCaptureSearch(localVisibleRect, windowOffset, results);
        }

        @Override
        @Nullable
        public ScrollCaptureCallback createScrollCaptureCallbackInternal(Rect localVisibleRect,
                Point offsetInWindow) {
            mCreateScrollCaptureCallbackInternalCount++;
            return mInternalCallback;
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
                Consumer<Rect> onComplete) {
        }

        @Override
        public void onScrollCaptureEnd(@NonNull Runnable onReady) {
        }
    };

    public static final class MockViewGroup extends ViewGroup {
        private ScrollCaptureCallback mInternalCallback;
        private Rect mOnScrollCaptureSearchLastLocalVisibleRect;
        private Point mOnScrollCaptureSearchLastWindowOffset;

        MockViewGroup(Context context) {
            this(context, /* left */ 0, /* top */0, /* right */ 0, /* bottom */0);
        }

        MockViewGroup(Context context, int left, int top, int right, int bottom) {
            this(context, left, top, right, bottom, View.SCROLL_CAPTURE_HINT_AUTO);
        }

        MockViewGroup(Context context, int left, int top, int right, int bottom,
                int scrollCaptureHint) {
            super(context);
            setScrollCaptureHint(scrollCaptureHint);
            setFrame(left, top, right, bottom);
        }

        public void setScrollCaptureCallbackInternalForTest(ScrollCaptureCallback internal) {
            mInternalCallback = internal;
        }

        @Override
        @Nullable
        public ScrollCaptureCallback createScrollCaptureCallbackInternal(Rect localVisibleRect,
                Point offsetInWindow) {
            return mInternalCallback;
        }

        @Override
        public void onScrollCaptureSearch(Rect localVisibleRect, Point windowOffset,
                Consumer<ScrollCaptureTarget> targets) {
            super.onScrollCaptureSearch(localVisibleRect, windowOffset, targets);
            mOnScrollCaptureSearchLastLocalVisibleRect = new Rect(localVisibleRect);
            mOnScrollCaptureSearchLastWindowOffset = new Point(windowOffset);
        }

        void assertOnScrollCaptureSearchLastArgs(Rect localVisibleRect, Point windowOffset) {
            assertEquals("arg localVisibleRect was incorrect.",
                    localVisibleRect, mOnScrollCaptureSearchLastLocalVisibleRect);
            assertEquals("arg windowOffset was incorrect.",
                    windowOffset, mOnScrollCaptureSearchLastWindowOffset);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // We don't layout this view.
        }
    }
}
