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

package com.android.internal.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.annotation.UiThread;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.view.ScrollCaptureViewHelper.ScrollResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This test contains a set of operations designed to verify the behavior of a
 * ScrollCaptureViewHelper implementation. Subclasses define and initialize
 * the View hierarchy by overriding {@link #createScrollableContent} and provide the
 * helper instance by overriding {@link #createHelper()}.
 *
 * @param <T> The concrete View subclass handled by the helper
 * @param <H> The helper being tested
 */
public abstract class AbsCaptureHelperTest<T extends View, H extends ScrollCaptureViewHelper<T>> {

    private static final String TAG = "AbsCaptureHelperTest";

    static final int WINDOW_WIDTH = 800;
    static final int WINDOW_HEIGHT = 1200;

    static final int CAPTURE_HEIGHT = WINDOW_HEIGHT / 2;
    static final int CONTENT_HEIGHT = WINDOW_HEIGHT * 3;

    public static final int[] ITEM_COLORS = {
            0xef476f,
            0xffd166,
            0x06d6a0,
            0x118ab2,
            0x073b4c
    };

    enum ScrollPosition {
        /** Scroll to top edge */
        TOP,
        /** Scroll middle of content to the top edge of the window */
        MIDDLE,
        /** Scroll bottom edge of content to the bottom edge of the window. */
        BOTTOM
    }

    private WindowManager mWm;
    private FrameLayout mContentRoot;
    private T mTarget;
    private Rect mScrollBounds;
    private H mHelper;
    private CancellationSignal mCancellationSignal;

    private Instrumentation mInstrumentation;

    @Before
    public final void createWindow() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = mInstrumentation.getTargetContext();
        mWm = context.getSystemService(WindowManager.class);
        mCancellationSignal = new CancellationSignal();

        // Instantiate parent view on the main thread
        mInstrumentation.runOnMainSync(() -> mContentRoot = new FrameLayout(context));

        // Called this directly on the test thread so it can block until loaded if needed
        mTarget = createScrollableContent(mContentRoot);

        // Finish constructing the window on the UI thread
        mInstrumentation.runOnMainSync(() -> {
            mContentRoot.addView(mTarget, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            WindowManager.LayoutParams windowLayoutParams = new WindowManager.LayoutParams(
                    WINDOW_WIDTH,
                    WINDOW_HEIGHT,
                    TYPE_APPLICATION_OVERLAY,
                    FLAG_NOT_TOUCHABLE,
                    PixelFormat.OPAQUE);

            windowLayoutParams.setTitle("ScrollCaptureHelperTest");
            windowLayoutParams.gravity = Gravity.CENTER;
            mWm.addView(mContentRoot, windowLayoutParams);
        });
    }

    /**
     * Create and prepare the instance of the helper under test.
     *
     * @return a new instance of ScrollCaptureViewHelper
     */
    protected abstract H createHelper();

    /**
     * Create a view/hierarchy containing a scrollable view to control. There should be zero
     * padding or margins, and the test cases expect the scrollable content to be
     * {@link #CONTENT_HEIGHT} px tall.
     *
     * @param parent the parent viewgroup holding the view to test
     * @return an instance of T to test
     */
    @UiThread
    protected abstract T createScrollableContent(ViewGroup parent);

    /**
     * Manually adjust the position of the scrollable view as setup for test scenarios.
     *
     * @param target the view target to adjust scroll position
     * @param position the position to scroll to
     */
    @UiThread
    protected void setInitialScrollPosition(T target, ScrollPosition position) {
        Log.d(TAG, "scrollToPosition: " + position);
        switch (position) {
            case MIDDLE:
                target.scrollBy(0, WINDOW_HEIGHT);
                break;
            case BOTTOM:
                target.scrollBy(0, WINDOW_HEIGHT * 2);
                break;
        }
    }

    @Test
    public void onScrollRequested_up_fromTop() {
        initHelper(ScrollPosition.TOP);

        Rect request = new Rect(0, -CAPTURE_HEIGHT, WINDOW_WIDTH, 0);
        ScrollResult result = requestScrollSync(mHelper, mScrollBounds, request);

        assertEmpty(result.availableArea);
    }

    @Test
    public void onScrollRequested_down_fromTop() {
        initHelper(ScrollPosition.TOP);
        Rect request = new Rect(0, WINDOW_HEIGHT, WINDOW_WIDTH, WINDOW_HEIGHT + CAPTURE_HEIGHT);
        ScrollResult scrollResult = requestScrollSync(mHelper, mScrollBounds, request);

        assertThat(scrollResult.requestedArea).isEqualTo(request);
        assertThat(scrollResult.availableArea).isEqualTo(request);
        // Capture height centered in the window
        assertThat(scrollResult.scrollDelta).isEqualTo((WINDOW_HEIGHT / 2) + (CAPTURE_HEIGHT / 2));
        assertAvailableAreaCompletelyVisible(scrollResult, mTarget);
    }

    @Test
    public void onScrollRequested_up_fromMiddle() {
        initHelper(ScrollPosition.MIDDLE);

        Rect request = new Rect(0, -CAPTURE_HEIGHT, WINDOW_WIDTH, 0);
        ScrollResult scrollResult = requestScrollSync(mHelper, mScrollBounds, request);

        assertThat(scrollResult.requestedArea).isEqualTo(request);
        assertThat(scrollResult.availableArea).isEqualTo(request);
        assertThat(scrollResult.scrollDelta).isEqualTo(
                -CAPTURE_HEIGHT - (WINDOW_HEIGHT - CAPTURE_HEIGHT) / 2);
        assertAvailableAreaCompletelyVisible(scrollResult, mTarget);
    }

    @Test
    public void onScrollRequested_down_fromMiddle() {
        initHelper(ScrollPosition.MIDDLE);

        Rect request = new Rect(0, WINDOW_HEIGHT, WINDOW_WIDTH, WINDOW_HEIGHT + CAPTURE_HEIGHT);
        ScrollResult scrollResult = requestScrollSync(mHelper, mScrollBounds, request);

        assertThat(scrollResult.requestedArea).isEqualTo(request);
        assertThat(scrollResult.availableArea).isEqualTo(request);
        assertThat(scrollResult.scrollDelta).isEqualTo(
                CAPTURE_HEIGHT + (WINDOW_HEIGHT - CAPTURE_HEIGHT) / 2);
        assertAvailableAreaCompletelyVisible(scrollResult, mTarget);
    }

    @Test
    public void onScrollRequested_up_fromBottom() {
        initHelper(ScrollPosition.BOTTOM);

        Rect request = new Rect(0, -CAPTURE_HEIGHT, WINDOW_WIDTH, 0);
        ScrollResult scrollResult = requestScrollSync(mHelper, mScrollBounds, request);

        assertThat(scrollResult.requestedArea).isEqualTo(request);
        assertThat(scrollResult.availableArea).isEqualTo(request);
        assertThat(scrollResult.scrollDelta).isEqualTo(
                -CAPTURE_HEIGHT - (WINDOW_HEIGHT - CAPTURE_HEIGHT) / 2);
        assertAvailableAreaCompletelyVisible(scrollResult, mTarget);
    }

    @Test
    public void onScrollRequested_down_fromBottom() {
        initHelper(ScrollPosition.BOTTOM);

        Rect request = new Rect(0, WINDOW_HEIGHT, WINDOW_WIDTH, WINDOW_HEIGHT + CAPTURE_HEIGHT);
        ScrollResult scrollResult = requestScrollSync(mHelper, mScrollBounds, request);

        assertThat(scrollResult.requestedArea).isEqualTo(request);
        // The result is an empty rectangle and no scrolling, since it
        // is not possible to physically scroll further down to make the
        // requested area visible at all (it doesn't exist).
        assertEmpty(scrollResult.availableArea);
    }

    @Test
    public void onScrollRequested_offTopEdge() {
        initHelper(ScrollPosition.TOP);

        int top = 0;
        Rect request =
                new Rect(0, top - (CAPTURE_HEIGHT / 2), WINDOW_WIDTH, top + (CAPTURE_HEIGHT / 2));

        ScrollResult scrollResult = requestScrollSync(mHelper, mScrollBounds, request);

        // The result is a partial result
        Rect expectedResult = new Rect(request);
        expectedResult.top += (CAPTURE_HEIGHT / 2); // top half clipped
        assertThat(scrollResult.requestedArea).isEqualTo(request);
        assertThat(scrollResult.availableArea).isEqualTo(expectedResult);
        assertThat(scrollResult.scrollDelta).isEqualTo(0);
        assertAvailableAreaPartiallyVisible(scrollResult, mTarget);
    }

    @Test
    public void onScrollRequested_offBottomEdge() {
        initHelper(ScrollPosition.BOTTOM);

        Rect request = new Rect(0, WINDOW_HEIGHT, WINDOW_WIDTH, WINDOW_HEIGHT + CAPTURE_HEIGHT);
        request.offset(0, -(CAPTURE_HEIGHT / 2));

        ScrollResult scrollResult = requestScrollSync(mHelper, mScrollBounds, request);

        Rect expectedResult = new Rect(request);
        expectedResult.bottom -= 300; // bottom half clipped
        assertThat(scrollResult.availableArea).isEqualTo(expectedResult);
        assertThat(scrollResult.scrollDelta).isEqualTo(0);
        assertAvailableAreaPartiallyVisible(scrollResult, mTarget);
    }

    @After
    public final void removeWindow() {
        mInstrumentation.runOnMainSync(() -> {
            if (mContentRoot != null && mContentRoot.isAttachedToWindow()) {
                mWm.removeViewImmediate(mContentRoot);
            }
        });
    }

    private void initHelper(ScrollPosition position) {
        mHelper = createHelper();
        mInstrumentation.runOnMainSync(() -> {
            setInitialScrollPosition(mTarget, position);
            mScrollBounds = mHelper.onComputeScrollBounds(mTarget);
            mHelper.onPrepareForStart(mTarget, mScrollBounds);
        });
    }

    @NonNull
    private ScrollResult requestScrollSync(H helper, Rect scrollBounds, Rect request)  {
        AtomicReference<ScrollResult> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        mInstrumentation.runOnMainSync(() -> {
            helper.onPrepareForStart(mTarget, scrollBounds);
            helper.onScrollRequested(mTarget, scrollBounds, request, mCancellationSignal,
                    (result) -> {
                        resultRef.set(result);
                        latch.countDown();
                    });
        });
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                mCancellationSignal.cancel();
                fail("Timeout waiting for ScrollResult");
            }
        } catch (InterruptedException e) {
            mCancellationSignal.cancel();
            fail("Interrupted!");
        }
        ScrollResult result = resultRef.get();
        assertNotNull(result);
        return result;
    }

    static void assertEmpty(Rect r) {
        if (r != null && !r.isEmpty()) {
            fail("Not true that " + r + " is empty");
        }
    }

    /**
     * Returns the bounds of the view which are visible (not clipped).
     */
    static Rect getVisibleBounds(View v) {
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        v.getLocalVisibleRect(r);
        r.offset(-v.getScrollX(), -v.getScrollY());
        return r;
    }

    static void assertAvailableAreaCompletelyVisible(ScrollResult result, View container) {
        Rect localAvailable = new Rect(result.availableArea);
        localAvailable.offset(0, -result.scrollDelta); // make relative to view top
        Rect visibleBounds = getVisibleBounds(container);
        if (!visibleBounds.contains(localAvailable)) {
            fail("Not true that all of " + localAvailable + " is contained by " + visibleBounds);
        }
    }

    static void assertAvailableAreaPartiallyVisible(ScrollResult result, View container) {
        Rect requested = new Rect(result.availableArea);
        requested.offset(0, -result.scrollDelta); // make relative
        Rect localVisible = getVisibleBounds(container);
        if (!Rect.intersects(localVisible, requested)) {
            fail("Not true that any of " + requested + " is contained by " + localVisible);
        }
    }
}
