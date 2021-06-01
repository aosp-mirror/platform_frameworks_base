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

package com.android.internal.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.annotation.UiThreadTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.view.ScrollCaptureViewHelper.ScrollResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;

public class ScrollViewCaptureHelperTest {

    private FrameLayout mParent;
    private ScrollView mTarget;
    private LinearLayout mContent;
    private WindowManager mWm;

    private WindowManager.LayoutParams mWindowLayoutParams;

    private static final int CHILD_VIEWS = 12;
    public static final int CHILD_VIEW_HEIGHT = 300;

    private static final int WINDOW_WIDTH = 800;
    private static final int WINDOW_HEIGHT = 1200;

    private static final int CAPTURE_HEIGHT = 600;

    private Random mRandom;

    private Context mContext;
    private float mDensity;

    @Before
    @UiThreadTest
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mDensity = mContext.getResources().getDisplayMetrics().density;

        mRandom = new Random();
        mParent = new FrameLayout(mContext);

        mTarget = new ScrollView(mContext);
        mParent.addView(mTarget, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        mContent = new LinearLayout(mContext);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mTarget.addView(mContent, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        for (int i = 0; i < CHILD_VIEWS; i++) {
            TextView view = new TextView(mContext);
            view.setText("Child #" + i);
            view.setTextColor(Color.WHITE);
            view.setTextSize(30f);
            view.setBackgroundColor(Color.rgb(mRandom.nextFloat(), mRandom.nextFloat(),
                    mRandom.nextFloat()));
            mContent.addView(view, new ViewGroup.LayoutParams(MATCH_PARENT, CHILD_VIEW_HEIGHT));
        }

        // Window -> Parent -> Target -> Content

        mWm = mContext.getSystemService(WindowManager.class);

        // Setup the window that we are going to use
        mWindowLayoutParams = new WindowManager.LayoutParams(WINDOW_WIDTH, WINDOW_HEIGHT,
                TYPE_APPLICATION_OVERLAY, FLAG_NOT_TOUCHABLE, PixelFormat.OPAQUE);
        mWindowLayoutParams.setTitle("ScrollViewCaptureHelper");
        mWindowLayoutParams.gravity = Gravity.CENTER;
        mWm.addView(mParent, mWindowLayoutParams);
    }

    @After
    @UiThreadTest
    public void tearDown() {
        mWm.removeViewImmediate(mParent);
    }

    @Test
    @UiThreadTest
    public void onPrepareForStart() {
        ScrollViewCaptureHelper svc = new ScrollViewCaptureHelper();
        Rect scrollBounds = svc.onComputeScrollBounds(mTarget);
        svc.onPrepareForStart(mTarget, scrollBounds);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_up_fromTop() {
        final int startScrollY = assertScrollToY(mTarget, 0);

        ScrollViewCaptureHelper svc = new ScrollViewCaptureHelper();
        Rect scrollBounds = svc.onComputeScrollBounds(mTarget);
        svc.onPrepareForStart(mTarget, scrollBounds);

        assertTrue(scrollBounds.height() > CAPTURE_HEIGHT);

        Rect request = new Rect(0, -CAPTURE_HEIGHT, scrollBounds.width(), 0);

        ScrollResult scrollResult = svc.onScrollRequested(mTarget,
                scrollBounds, request);

        // The result is an empty rectangle and no scrolling, since it
        // is not possible to physically scroll further up to make the
        // requested area visible at all (it doesn't exist).
        assertEmpty(scrollResult.availableArea);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_down_fromTop() {
        final int startScrollY = assertScrollToY(mTarget, 0);

        ScrollViewCaptureHelper svc = new ScrollViewCaptureHelper();
        Rect scrollBounds = svc.onComputeScrollBounds(mTarget);
        svc.onPrepareForStart(mTarget, scrollBounds);

        assertTrue(scrollBounds.height() > CAPTURE_HEIGHT);

        // Capture between y = +1200 to +1500 pixels BELOW current top
        Rect request = new Rect(0, WINDOW_HEIGHT, scrollBounds.width(),
                WINDOW_HEIGHT + CAPTURE_HEIGHT);

        ScrollResult scrollResult = svc.onScrollRequested(mTarget, scrollBounds, request);
        assertRectEquals(request, scrollResult.requestedArea);
        assertRectEquals(request, scrollResult.availableArea);
        assertRequestedRectCompletelyVisible(startScrollY, request, getVisibleRect(mContent));
        assertEquals(CAPTURE_HEIGHT + (WINDOW_HEIGHT - CAPTURE_HEIGHT) / 2,
                scrollResult.scrollDelta);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_up_fromMiddle() {
        final int startScrollY = assertScrollToY(mTarget, WINDOW_HEIGHT);

        ScrollViewCaptureHelper svc = new ScrollViewCaptureHelper();
        Rect scrollBounds = svc.onComputeScrollBounds(mTarget);
        svc.onPrepareForStart(mTarget, scrollBounds);

        Rect request = new Rect(0, -CAPTURE_HEIGHT, scrollBounds.width(), 0);

        ScrollResult scrollResult = svc.onScrollRequested(mTarget, scrollBounds, request);
        assertRectEquals(request, scrollResult.requestedArea);
        assertRectEquals(request, scrollResult.availableArea);
        assertRequestedRectCompletelyVisible(startScrollY, request, getVisibleRect(mContent));
        assertEquals(-CAPTURE_HEIGHT - (WINDOW_HEIGHT - CAPTURE_HEIGHT) / 2,
                scrollResult.scrollDelta);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_down_fromMiddle() {
        final int startScrollY = assertScrollToY(mTarget, WINDOW_HEIGHT);

        ScrollViewCaptureHelper svc = new ScrollViewCaptureHelper();
        Rect scrollBounds = svc.onComputeScrollBounds(mTarget);
        svc.onPrepareForStart(mTarget, scrollBounds);

        Rect request = new Rect(0, WINDOW_HEIGHT, scrollBounds.width(),
                WINDOW_HEIGHT + CAPTURE_HEIGHT);

        ScrollResult scrollResult = svc.onScrollRequested(mTarget, scrollBounds, request);
        assertRectEquals(request, scrollResult.requestedArea);
        assertRectEquals(request, scrollResult.availableArea);
        assertRequestedRectCompletelyVisible(startScrollY, request, getVisibleRect(mContent));
        assertEquals(CAPTURE_HEIGHT + (WINDOW_HEIGHT - CAPTURE_HEIGHT) / 2,
                scrollResult.scrollDelta);

    }

    @Test
    @UiThreadTest
    public void onScrollRequested_up_fromBottom() {
        final int startScrollY = assertScrollToY(mTarget, WINDOW_HEIGHT * 2);

        ScrollViewCaptureHelper svc = new ScrollViewCaptureHelper();
        Rect scrollBounds = svc.onComputeScrollBounds(mTarget);
        svc.onPrepareForStart(mTarget, scrollBounds);

        Rect request = new Rect(0, -CAPTURE_HEIGHT, scrollBounds.width(), 0);

        ScrollResult scrollResult = svc.onScrollRequested(mTarget, scrollBounds, request);
        assertRectEquals(request, scrollResult.requestedArea);
        assertRectEquals(request, scrollResult.availableArea);
        assertRequestedRectCompletelyVisible(startScrollY, request, getVisibleRect(mContent));
        assertEquals(-CAPTURE_HEIGHT - (WINDOW_HEIGHT - CAPTURE_HEIGHT) / 2,
                scrollResult.scrollDelta);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_down_fromBottom() {
        final int startScrollY = assertScrollToY(mTarget, WINDOW_HEIGHT * 2);

        ScrollViewCaptureHelper svc = new ScrollViewCaptureHelper();
        Rect scrollBounds = svc.onComputeScrollBounds(mTarget);
        svc.onPrepareForStart(mTarget, scrollBounds);

        Rect request = new Rect(0, WINDOW_HEIGHT, scrollBounds.width(),
                WINDOW_HEIGHT + CAPTURE_HEIGHT);

        ScrollResult scrollResult = svc.onScrollRequested(mTarget, scrollBounds, request);
        assertRectEquals(request, scrollResult.requestedArea);

        // The result is an empty rectangle and no scrolling, since it
        // is not possible to physically scroll further down to make the
        // requested area visible at all (it doesn't exist).
        assertEmpty(scrollResult.availableArea);
        assertEquals(0, scrollResult.scrollDelta);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_offTopEdge() {
        final int startScrollY = assertScrollToY(mTarget, 0);

        ScrollViewCaptureHelper svc = new ScrollViewCaptureHelper();
        Rect scrollBounds = svc.onComputeScrollBounds(mTarget);
        svc.onPrepareForStart(mTarget, scrollBounds);

        // Create a request which lands halfway off the top of the content
        //from -1500 to -900, (starting at 1200 = -300 to +300 within the content)
        int top = 0;
        Rect request = new Rect(
                0, top - (CAPTURE_HEIGHT / 2),
                scrollBounds.width(), top + (CAPTURE_HEIGHT / 2));

        ScrollResult scrollResult = svc.onScrollRequested(mTarget, scrollBounds, request);
        assertRectEquals(request, scrollResult.requestedArea);

        ScrollResult result = svc.onScrollRequested(mTarget, scrollBounds, request);
        // The result is a partial result
        Rect expectedResult = new Rect(request);
        expectedResult.top += 300; // top half clipped
        assertRectEquals(expectedResult, result.availableArea);
        assertRequestedRectPartiallyVisible(startScrollY, request, getVisibleRect(mContent));
        assertEquals(0, scrollResult.scrollDelta);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_offBottomEdge() {
        final int startScrollY = assertScrollToY(mTarget, WINDOW_HEIGHT * 2); // 2400

        ScrollViewCaptureHelper svc = new ScrollViewCaptureHelper();
        Rect scrollBounds = svc.onComputeScrollBounds(mTarget);
        svc.onPrepareForStart(mTarget, scrollBounds);

        // Create a request which lands halfway off the bottom of the content
        //from 600 to to 1200, (starting at 2400 = 3000 to  3600 within the content)

        int bottom = WINDOW_HEIGHT;
        Rect request = new Rect(
                0, bottom - (CAPTURE_HEIGHT / 2),
                scrollBounds.width(), bottom + (CAPTURE_HEIGHT / 2));

        ScrollResult result = svc.onScrollRequested(mTarget, scrollBounds, request);

        Rect expectedResult = new Rect(request);
        expectedResult.bottom -= 300; // bottom half clipped
        assertRectEquals(expectedResult, result.availableArea);
        assertRequestedRectPartiallyVisible(startScrollY, request, getVisibleRect(mContent));
        assertEquals(0, result.scrollDelta);
    }

    @Test
    @UiThreadTest
    public void onPrepareForEnd() {
        ScrollViewCaptureHelper svc = new ScrollViewCaptureHelper();
        svc.onPrepareForEnd(mTarget);
    }


    static void assertEmpty(Rect r) {
        if (r != null && !r.isEmpty()) {
            fail("Not true that " + r + " is empty");
        }
    }

    static void assertContains(Rect parent, Rect child) {
        if (!parent.contains(child)) {
            fail("Not true that " + parent + " contains " + child);
        }
    }

    static void assertRectEquals(Rect parent, Rect child) {
        if (!parent.equals(child)) {
            fail("Not true that " + parent + " is equal to " + child);
        }
    }

    static Rect getVisibleRect(View v) {
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        v.getLocalVisibleRect(r);
        return r;
    }


    static int assertScrollToY(View v, int scrollY) {
        v.scrollTo(0, scrollY);
        int dest = v.getScrollY();
        assertEquals(scrollY, dest);
        return scrollY;
    }

    static void assertRequestedRectCompletelyVisible(int startScrollY, Rect requestRect,
            Rect localVisibleNow) {
        Rect captured = new Rect(localVisibleNow);
        captured.offset(0, -startScrollY); // make relative

        if (!captured.contains(requestRect)) {
            fail("Not true that all of " + requestRect + " is contained by " + captured);
        }
    }
    static void assertRequestedRectPartiallyVisible(int startScrollY, Rect requestRect,
            Rect localVisibleNow) {
        Rect captured = new Rect(localVisibleNow);
        captured.offset(0, -startScrollY); // make relative

        if (!Rect.intersects(captured, requestRect)) {
            fail("Not true that any of " + requestRect + " intersects " + captured);
        }
    }
}
