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
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;

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
        mRenderer = Mockito.spy(new ThreadedRendererWrapper(view.getThreadedRenderer()));
        doNothing().when(mRenderer).addObserver(any());
        doNothing().when(mRenderer).removeObserver(any());

        Session session = new Session(CUJ_NOTIFICATION_SHADE_GESTURE);
        mTracker = Mockito.spy(new FrameTracker(session, handler, mRenderer, mWrapper));
        doNothing().when(mTracker).triggerPerfetto();
    }

    @Test
    public void testIsJankyFrame() {
        // We skip the first frame.
        doReturn(1L).when(mWrapper).getMetric(FrameMetrics.FIRST_DRAW_FRAME);
        doReturn(TimeUnit.MILLISECONDS.toNanos(20L))
                .when(mWrapper).getMetric(FrameMetrics.TOTAL_DURATION);
        assertThat(mTracker.isJankyFrame(mWrapper)).isFalse();

        // Should exceed the criteria.
        doReturn(0L).when(mWrapper).getMetric(FrameMetrics.FIRST_DRAW_FRAME);
        doReturn(TimeUnit.MILLISECONDS.toNanos(20L))
                .when(mWrapper).getMetric(FrameMetrics.TOTAL_DURATION);
        assertThat(mTracker.isJankyFrame(mWrapper)).isTrue();

        // Should be safe.
        doReturn(TimeUnit.MILLISECONDS.toNanos(10L))
                .when(mWrapper).getMetric(FrameMetrics.TOTAL_DURATION);
        assertThat(mTracker.isJankyFrame(mWrapper)).isFalse();
    }

    @Test
    public void testBeginEnd() {
        // assert the initial values
        assertThat(mTracker.mBeginTime).isEqualTo(FrameTracker.UNKNOWN_TIMESTAMP);
        assertThat(mTracker.mEndTime).isEqualTo(FrameTracker.UNKNOWN_TIMESTAMP);

        // Observer should be only added once in continuous calls.
        mTracker.begin();
        mTracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // assert the values after begin call.
        assertThat(mTracker.mBeginTime).isNotEqualTo(FrameTracker.UNKNOWN_TIMESTAMP);
        assertThat(mTracker.mEndTime).isEqualTo(FrameTracker.UNKNOWN_TIMESTAMP);

        // simulate the callback during trace session
        // assert the isJankyFrame should be invoked as well.
        doReturn(System.nanoTime()).when(mWrapper).getMetric(FrameMetrics.VSYNC_TIMESTAMP);
        doReturn(true).when(mTracker).isJankyFrame(any());
        mTracker.onFrameMetricsAvailable(0);
        verify(mTracker).isJankyFrame(any());

        // end the trace session, simulate a callback came after the end call.
        // assert the end time should be set, the observer should be removed.
        // triggerPerfetto should be invoked as well.
        mTracker.end();
        doReturn(System.nanoTime()).when(mWrapper).getMetric(FrameMetrics.VSYNC_TIMESTAMP);
        assertThat(mTracker.mEndTime).isNotEqualTo(FrameTracker.UNKNOWN_TIMESTAMP);
        mTracker.onFrameMetricsAvailable(0);
        verify(mRenderer).removeObserver(any());
        verify(mTracker).triggerPerfetto();
    }
}
