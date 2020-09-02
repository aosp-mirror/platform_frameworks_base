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

package com.android.internal.jank;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_GESTURE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.view.FrameMetrics;
import android.view.View;
import android.view.ViewAttachTestActivity;

import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;

import com.android.internal.jank.FrameTracker.FrameMetricsWrapper;
import com.android.internal.jank.FrameTracker.ThreadedRendererWrapper;
import com.android.internal.jank.InteractionJankMonitor.Session;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.concurrent.TimeUnit;

@SmallTest
public class FrameTrackerTest {
    private ViewAttachTestActivity mActivity;

    @Rule
    public ActivityTestRule<ViewAttachTestActivity> mRule =
            new ActivityTestRule<>(ViewAttachTestActivity.class);

    private FrameTracker mTracker;
    private ThreadedRendererWrapper mRenderer;
    private FrameMetricsWrapper mWrapper;

    @Before
    public void setup() {
        // Prepare an activity for getting ThreadedRenderer later.
        mActivity = mRule.getActivity();
        View view = mActivity.getWindow().getDecorView();
        assertThat(view.isAttachedToWindow()).isTrue();

        Handler handler = mRule.getActivity().getMainThreadHandler();
        mWrapper = Mockito.spy(new FrameMetricsWrapper());
        // For simplicity - provide current timestamp anytime mWrapper asked for VSYNC_TIMESTAMP
        when(mWrapper.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
                .then(unusedInvocation -> System.nanoTime());
        mRenderer = Mockito.spy(new ThreadedRendererWrapper(view.getThreadedRenderer()));
        doNothing().when(mRenderer).addObserver(any());
        doNothing().when(mRenderer).removeObserver(any());

        Session session = new Session(CUJ_NOTIFICATION_SHADE_GESTURE);
        mTracker = Mockito.spy(new FrameTracker(session, handler, mRenderer, mWrapper));
        doNothing().when(mTracker).triggerPerfetto();
    }

    @Test
    public void testIgnoresSecondBegin() {
        // Observer should be only added once in continuous calls.
        mTracker.begin();
        mTracker.begin();
        verify(mRenderer, only()).addObserver(any());
    }

    @Test
    public void testOnlyFirstFrameOverThreshold() {
        // Just provide current timestamp anytime mWrapper asked for VSYNC_TIMESTAMP
        when(mWrapper.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
                .then(unusedInvocation -> System.nanoTime());

        mTracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame with a long duration - should not be taken into account
        setupFirstFrameMockWithDuration(100);
        mTracker.onFrameMetricsAvailable(0);

        // send another frame with a short duration - should not be considered janky
        setupOtherFrameMockWithDuration(5);
        mTracker.onFrameMetricsAvailable(0);

        // end the trace session, the last janky frame is after the end() so is discarded.
        mTracker.end();
        setupOtherFrameMockWithDuration(500);
        mTracker.onFrameMetricsAvailable(0);

        verify(mRenderer).removeObserver(any());
        verify(mTracker, never()).triggerPerfetto();
    }

    @Test
    public void testOtherFrameOverThreshold() {
        mTracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame - not janky
        setupFirstFrameMockWithDuration(4);
        mTracker.onFrameMetricsAvailable(0);

        // send another frame - should be considered janky
        setupOtherFrameMockWithDuration(40);
        mTracker.onFrameMetricsAvailable(0);

        // end the trace session
        mTracker.end();
        setupOtherFrameMockWithDuration(5);
        mTracker.onFrameMetricsAvailable(0);

        verify(mRenderer).removeObserver(any());

        // We detected a janky frame - trigger Perfetto
        verify(mTracker).triggerPerfetto();
    }

    @Test
    public void testLastFrameOverThresholdBeforeEnd() {
        mTracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame - not janky
        setupFirstFrameMockWithDuration(4);
        mTracker.onFrameMetricsAvailable(0);

        // send another frame - not janky
        setupOtherFrameMockWithDuration(4);
        mTracker.onFrameMetricsAvailable(0);

        // end the trace session, simulate one more valid callback came after the end call.
        when(mWrapper.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
                .thenReturn(System.nanoTime());
        setupOtherFrameMockWithDuration(50);
        mTracker.end();
        mTracker.onFrameMetricsAvailable(0);

        // One more callback with VSYNC after the end() timestamp.
        when(mWrapper.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
                .thenReturn(System.nanoTime());
        setupOtherFrameMockWithDuration(5);
        mTracker.onFrameMetricsAvailable(0);

        verify(mRenderer).removeObserver(any());

        // We detected a janky frame - trigger Perfetto
        verify(mTracker).triggerPerfetto();
    }

    private void setupFirstFrameMockWithDuration(long durationMillis) {
        doReturn(1L).when(mWrapper).getMetric(FrameMetrics.FIRST_DRAW_FRAME);
        doReturn(TimeUnit.MILLISECONDS.toNanos(durationMillis))
                .when(mWrapper).getMetric(FrameMetrics.TOTAL_DURATION);
    }

    private void setupOtherFrameMockWithDuration(long durationMillis) {
        doReturn(0L).when(mWrapper).getMetric(FrameMetrics.FIRST_DRAW_FRAME);
        doReturn(TimeUnit.MILLISECONDS.toNanos(durationMillis))
                .when(mWrapper).getMetric(FrameMetrics.TOTAL_DURATION);
    }
}
