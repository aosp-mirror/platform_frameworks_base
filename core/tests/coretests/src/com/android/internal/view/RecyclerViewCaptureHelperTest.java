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
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.view.ScrollCaptureViewHelper.ScrollResult;
import com.android.internal.widget.LinearLayoutManager;
import com.android.internal.widget.RecyclerView;

import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

@RunWith(AndroidJUnit4.class)
public class RecyclerViewCaptureHelperTest {
    private static final int CHILD_VIEWS = 12;
    private static final int CHILD_VIEW_HEIGHT = 300;
    private static final int WINDOW_WIDTH = 800;
    private static final int WINDOW_HEIGHT = 1200;
    private static final int CAPTURE_HEIGHT = 600;

    private FrameLayout mParent;
    private RecyclerView mTarget;
    private WindowManager mWm;

    private WindowManager.LayoutParams mWindowLayoutParams;

    private Context mContext;
    private float mDensity;
    private LinearLayoutManager mLinearLayoutManager;
    private Instrumentation mInstrumentation;

    @Before
    @UiThreadTest
    public void setUp() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getContext();
        mDensity = mContext.getResources().getDisplayMetrics().density;

        mParent = new FrameLayout(mContext);

        mTarget = new RecyclerView(mContext);
        mParent.addView(mTarget, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        mTarget.setAdapter(new TestAdapter());
        mLinearLayoutManager =
                new LinearLayoutManager(mContext, LinearLayoutManager.VERTICAL, false);
        mTarget.setLayoutManager(mLinearLayoutManager);
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
    public void onScrollRequested_up_fromTop() {
        mTarget.scrollBy(0, -(WINDOW_HEIGHT * 3));
        // mTarget.createSnapshot(new ViewDebug.HardwareCanvasProvider(), false);

        RecyclerViewCaptureHelper rvc = new RecyclerViewCaptureHelper();
        Rect scrollBounds = rvc.onComputeScrollBounds(mTarget);
        rvc.onPrepareForStart(mTarget, scrollBounds);

        assertThat(scrollBounds.height()).isGreaterThan(CAPTURE_HEIGHT);

        Rect request = new Rect(0, -CAPTURE_HEIGHT, scrollBounds.width(), 0);

        ScrollResult scrollResult = rvc.onScrollRequested(mTarget,
                scrollBounds, request);

        // The result is an empty rectangle and no scrolling, since it
        // is not possible to physically scroll further up to make the
        // requested area visible at all (it doesn't exist).
        assertEmpty(scrollResult.availableArea);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_down_fromTop() {
        mTarget.scrollBy(0, -(WINDOW_HEIGHT * 3));

        RecyclerViewCaptureHelper rvc = new RecyclerViewCaptureHelper();
        Rect scrollBounds = rvc.onComputeScrollBounds(mTarget);
        rvc.onPrepareForStart(mTarget, scrollBounds);

        assertThat(scrollBounds.height()).isGreaterThan(CAPTURE_HEIGHT);

        // Capture between y = +1200 to +1800 pixels BELOW current top
        Rect request = new Rect(0, WINDOW_HEIGHT, scrollBounds.width(),
                WINDOW_HEIGHT + CAPTURE_HEIGHT);

        ScrollResult scrollResult = rvc.onScrollRequested(mTarget, scrollBounds, request);
        assertThat(request).isEqualTo(scrollResult.requestedArea);
        assertThat(request).isEqualTo(scrollResult.availableArea);
        // Capture height centered in the window
        assertThat(scrollResult.scrollDelta).isEqualTo(
                CAPTURE_HEIGHT + (WINDOW_HEIGHT - CAPTURE_HEIGHT) / 2);
        assertAvailableAreaCompletelyVisible(scrollResult, mTarget);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_up_fromMiddle() {
        mTarget.scrollBy(0, WINDOW_HEIGHT);

        RecyclerViewCaptureHelper helper = new RecyclerViewCaptureHelper();
        Rect scrollBounds = helper.onComputeScrollBounds(mTarget);
        helper.onPrepareForStart(mTarget, scrollBounds);

        Rect request = new Rect(0, -CAPTURE_HEIGHT, scrollBounds.width(), 0);

        ScrollResult scrollResult = helper.onScrollRequested(mTarget, scrollBounds, request);
        assertThat(request).isEqualTo(scrollResult.requestedArea);
        assertThat(request).isEqualTo(scrollResult.availableArea);
        assertThat(scrollResult.scrollDelta).isEqualTo(
                -CAPTURE_HEIGHT - (WINDOW_HEIGHT - CAPTURE_HEIGHT) / 2);
        assertAvailableAreaCompletelyVisible(scrollResult, mTarget);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_down_fromMiddle() {
        mTarget.scrollBy(0, WINDOW_HEIGHT);

        RecyclerViewCaptureHelper helper = new RecyclerViewCaptureHelper();
        Rect scrollBounds = helper.onComputeScrollBounds(mTarget);
        helper.onPrepareForStart(mTarget, scrollBounds);

        Rect request = new Rect(0, WINDOW_HEIGHT, scrollBounds.width(),
                WINDOW_HEIGHT + CAPTURE_HEIGHT);

        ScrollResult scrollResult = helper.onScrollRequested(mTarget, scrollBounds, request);
        assertThat(request).isEqualTo(scrollResult.requestedArea);
        assertThat(request).isEqualTo(scrollResult.availableArea);
        assertThat(scrollResult.scrollDelta).isEqualTo(
                CAPTURE_HEIGHT + (WINDOW_HEIGHT - CAPTURE_HEIGHT) / 2);
        assertAvailableAreaCompletelyVisible(scrollResult, mTarget);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_up_fromBottom() {
        mTarget.scrollBy(0, WINDOW_HEIGHT * 2);

        RecyclerViewCaptureHelper helper = new RecyclerViewCaptureHelper();
        Rect scrollBounds = helper.onComputeScrollBounds(mTarget);
        helper.onPrepareForStart(mTarget, scrollBounds);

        Rect request = new Rect(0, -CAPTURE_HEIGHT, scrollBounds.width(), 0);

        ScrollResult scrollResult = helper.onScrollRequested(mTarget, scrollBounds, request);
        assertThat(request).isEqualTo(scrollResult.requestedArea);
        assertThat(request).isEqualTo(scrollResult.availableArea);
        assertThat(scrollResult.scrollDelta).isEqualTo(
                -CAPTURE_HEIGHT - (WINDOW_HEIGHT - CAPTURE_HEIGHT) / 2);
        assertAvailableAreaCompletelyVisible(scrollResult, mTarget);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_down_fromBottom() {
        mTarget.scrollBy(0, WINDOW_HEIGHT * 3);

        RecyclerViewCaptureHelper rvc = new RecyclerViewCaptureHelper();
        Rect scrollBounds = rvc.onComputeScrollBounds(mTarget);
        rvc.onPrepareForStart(mTarget, scrollBounds);

        Rect request = new Rect(0, WINDOW_HEIGHT, scrollBounds.width(),
                WINDOW_HEIGHT + CAPTURE_HEIGHT);

        ScrollResult scrollResult = rvc.onScrollRequested(mTarget,
                scrollBounds, request);
        Truth.assertThat(request).isEqualTo(scrollResult.requestedArea);

        // The result is an empty rectangle and no scrolling, since it
        // is not possible to physically scroll further down to make the
        // requested area visible at all (it doesn't exist).
        assertEmpty(scrollResult.availableArea);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_offTopEdge() {
        mTarget.scrollBy(0, -(WINDOW_HEIGHT * 3));

        RecyclerViewCaptureHelper helper = new RecyclerViewCaptureHelper();
        Rect scrollBounds = helper.onComputeScrollBounds(mTarget);
        helper.onPrepareForStart(mTarget, scrollBounds);

        // Create a request which lands halfway off the top of the content
        //from -1500 to -900, (starting at 1200 = -300 to +300 within the content)
        int top = 0;
        Rect request = new Rect(
                0, top - (CAPTURE_HEIGHT / 2),
                scrollBounds.width(), top + (CAPTURE_HEIGHT / 2));

        ScrollResult scrollResult = helper.onScrollRequested(mTarget, scrollBounds, request);
        assertThat(request).isEqualTo(scrollResult.requestedArea);

        ScrollResult result = helper.onScrollRequested(mTarget, scrollBounds, request);
        // The result is a partial result
        Rect expectedResult = new Rect(request);
        expectedResult.top += (CAPTURE_HEIGHT / 2); // top half clipped
        assertThat(expectedResult).isEqualTo(result.availableArea);
        assertThat(scrollResult.scrollDelta).isEqualTo(0);
        assertAvailableAreaPartiallyVisible(scrollResult, mTarget);
    }

    @Test
    @UiThreadTest
    public void onScrollRequested_offBottomEdge() {
        mTarget.scrollBy(0, WINDOW_HEIGHT * 2);

        RecyclerViewCaptureHelper helper = new RecyclerViewCaptureHelper();
        Rect scrollBounds = helper.onComputeScrollBounds(mTarget);
        helper.onPrepareForStart(mTarget, scrollBounds);

        // Create a request which lands halfway off the bottom of the content
        //from 600 to to 1200, (starting at 2400 = 3000 to  3600 within the content)

        int bottom = WINDOW_HEIGHT;
        Rect request = new Rect(
                0, bottom - (CAPTURE_HEIGHT / 2),
                scrollBounds.width(), bottom + (CAPTURE_HEIGHT / 2));

        ScrollResult result = helper.onScrollRequested(mTarget, scrollBounds, request);

        Rect expectedResult = new Rect(request);
        expectedResult.bottom -= 300; // bottom half clipped
        assertThat(expectedResult).isEqualTo(result.availableArea);
        assertThat(result.scrollDelta).isEqualTo(0);
        assertAvailableAreaPartiallyVisible(result, mTarget);
    }

    static final class TestViewHolder extends RecyclerView.ViewHolder {
        TestViewHolder(View itemView) {
            super(itemView);
        }
    }

    static final class TestAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Random mRandom = new Random();

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new TestViewHolder(new TextView(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TextView view = (TextView) holder.itemView;
            view.setText("Child #" + position);
            view.setTextColor(Color.WHITE);
            view.setTextSize(30f);
            view.setBackgroundColor(Color.rgb(mRandom.nextFloat(), mRandom.nextFloat(),
                    mRandom.nextFloat()));
            view.setMinHeight(CHILD_VIEW_HEIGHT);
        }

        @Override
        public int getItemCount() {
            return CHILD_VIEWS;
        }
    }

    static void assertEmpty(Rect r) {
        if (r != null && !r.isEmpty()) {
            fail("Not true that " + r + " is empty");
        }
    }

    static Rect getVisibleRect(View v) {
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        v.getLocalVisibleRect(r);
        return r;
    }

    static void assertAvailableAreaCompletelyVisible(ScrollResult result, View container) {
        Rect requested = new Rect(result.availableArea);
        requested.offset(0, -result.scrollDelta); // make relative
        Rect localVisible = getVisibleRect(container);
        if (!localVisible.contains(requested)) {
            fail("Not true that all of " + requested + " is contained by " + localVisible);
        }
    }

    static void assertAvailableAreaPartiallyVisible(ScrollResult result, View container) {
        Rect requested = new Rect(result.availableArea);
        requested.offset(0, -result.scrollDelta); // make relative
        Rect localVisible = getVisibleRect(container);
        if (!Rect.intersects(localVisible, requested)) {
            fail("Not true that any of " + requested + " is contained by " + localVisible);
        }
    }
}
