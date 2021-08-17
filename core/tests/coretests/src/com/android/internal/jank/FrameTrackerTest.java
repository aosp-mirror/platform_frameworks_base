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

import static android.view.SurfaceControl.JankData.JANK_APP_DEADLINE_MISSED;
import static android.view.SurfaceControl.JankData.JANK_NONE;
import static android.view.SurfaceControl.JankData.JANK_SURFACEFLINGER_DEADLINE_MISSED;

import static com.android.internal.jank.FrameTracker.SurfaceControlWrapper;
import static com.android.internal.jank.FrameTracker.ViewRootWrapper;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.view.FrameMetrics;
import android.view.SurfaceControl;
import android.view.SurfaceControl.JankData;
import android.view.SurfaceControl.JankData.JankType;
import android.view.SurfaceControl.OnJankDataListener;
import android.view.View;
import android.view.ViewAttachTestActivity;

import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;

import com.android.internal.jank.FrameTracker.ChoreographerWrapper;
import com.android.internal.jank.FrameTracker.FrameMetricsWrapper;
import com.android.internal.jank.FrameTracker.ThreadedRendererWrapper;
import com.android.internal.jank.InteractionJankMonitor.Session;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.concurrent.TimeUnit;

@SmallTest
public class FrameTrackerTest {
    private static final String CUJ_POSTFIX = "";
    private ViewAttachTestActivity mActivity;

    @Rule
    public ActivityTestRule<ViewAttachTestActivity> mRule =
            new ActivityTestRule<>(ViewAttachTestActivity.class);

    private FrameTracker mTracker;
    private ThreadedRendererWrapper mRenderer;
    private FrameMetricsWrapper mWrapper;
    private SurfaceControlWrapper mSurfaceControlWrapper;
    private ViewRootWrapper mViewRootWrapper;
    private ChoreographerWrapper mChoreographer;
    private ArgumentCaptor<OnJankDataListener> mListenerCapture;
    private SurfaceControl mSurfaceControl;

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

        mSurfaceControl = new SurfaceControl.Builder().setName("Surface").build();
        mViewRootWrapper = mock(ViewRootWrapper.class);
        when(mViewRootWrapper.getSurfaceControl()).thenReturn(mSurfaceControl);
        mSurfaceControlWrapper = mock(SurfaceControlWrapper.class);

        mListenerCapture = ArgumentCaptor.forClass(OnJankDataListener.class);
        doNothing().when(mSurfaceControlWrapper).addJankStatsListener(
                mListenerCapture.capture(), any());
        doNothing().when(mSurfaceControlWrapper).removeJankStatsListener(
                mListenerCapture.capture());

        mChoreographer = mock(ChoreographerWrapper.class);

        Session session = new Session(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX);
        mTracker = Mockito.spy(
                new FrameTracker(session, handler, mRenderer, mViewRootWrapper,
                        mSurfaceControlWrapper, mChoreographer, mWrapper,
                        /*traceThresholdMissedFrames=*/ 1, /*traceThresholdFrameTimeMillis=*/ -1,
                        null));
        doNothing().when(mTracker).triggerPerfetto();
        doNothing().when(mTracker).postTraceStartMarker();
    }

    @Test
    public void testOnlyFirstWindowFrameOverThreshold() {
        // Just provide current timestamp anytime mWrapper asked for VSYNC_TIMESTAMP
        when(mWrapper.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
                .then(unusedInvocation -> System.nanoTime());

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        mTracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame with a long duration - should not be taken into account
        sendFirstWindowFrame(100, JANK_APP_DEADLINE_MISSED, 100L);

        // send another frame with a short duration - should not be considered janky
        sendFirstWindowFrame(5, JANK_NONE, 101L);

        // end the trace session, the last janky frame is after the end() so is discarded.
        when(mChoreographer.getVsyncId()).thenReturn(102L);
        mTracker.end(FrameTracker.REASON_END_NORMAL);
        sendFrame(5, JANK_NONE, 102L);
        sendFrame(500, JANK_APP_DEADLINE_MISSED, 103L);

        verify(mTracker).removeObservers();
        verify(mTracker, never()).triggerPerfetto();
    }

    @Test
    public void testSfJank() {
        when(mChoreographer.getVsyncId()).thenReturn(100L);
        mTracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame - not janky
        sendFrame(4, JANK_NONE, 100L);

        // send another frame - should be considered janky
        sendFrame(40, JANK_SURFACEFLINGER_DEADLINE_MISSED, 101L);

        // end the trace session
        when(mChoreographer.getVsyncId()).thenReturn(102L);
        mTracker.end(FrameTracker.REASON_END_NORMAL);
        sendFrame(4, JANK_NONE, 102L);

        verify(mTracker).removeObservers();

        // We detected a janky frame - trigger Perfetto
        verify(mTracker).triggerPerfetto();
    }

    @Test
    public void testFirstFrameJankyNoTrigger() {
        when(mChoreographer.getVsyncId()).thenReturn(100L);
        mTracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame - janky
        sendFrame(40, JANK_APP_DEADLINE_MISSED, 100L);

        // send another frame - not jank
        sendFrame(4, JANK_NONE, 101L);

        // end the trace session
        when(mChoreographer.getVsyncId()).thenReturn(102L);
        mTracker.end(FrameTracker.REASON_END_NORMAL);
        sendFrame(4, JANK_NONE, 102L);

        verify(mTracker).removeObservers();

        // We detected a janky frame - trigger Perfetto
        verify(mTracker, never()).triggerPerfetto();
    }

    @Test
    public void testOtherFrameOverThreshold() {
        when(mChoreographer.getVsyncId()).thenReturn(100L);
        mTracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame - not janky
        sendFrame(4, JANK_NONE, 100L);

        // send another frame - should be considered janky
        sendFrame(40, JANK_APP_DEADLINE_MISSED, 101L);

        // end the trace session
        when(mChoreographer.getVsyncId()).thenReturn(102L);
        mTracker.end(FrameTracker.REASON_END_NORMAL);
        sendFrame(4, JANK_NONE, 102L);

        verify(mTracker).removeObservers();

        // We detected a janky frame - trigger Perfetto
        verify(mTracker).triggerPerfetto();
    }

    @Test
    public void testLastFrameOverThresholdBeforeEnd() {
        when(mChoreographer.getVsyncId()).thenReturn(100L);
        mTracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame - not janky
        sendFrame(4, JANK_NONE, 100L);

        // send another frame - not janky
        sendFrame(4, JANK_NONE, 101L);

        // end the trace session, simulate one more valid callback came after the end call.
        when(mChoreographer.getVsyncId()).thenReturn(102L);
        mTracker.end(FrameTracker.REASON_END_NORMAL);
        sendFrame(50, JANK_APP_DEADLINE_MISSED, 102L);

        // One more callback with VSYNC after the end() vsync id.
        sendFrame(4, JANK_NONE, 103L);

        verify(mTracker).removeObservers();

        // We detected a janky frame - trigger Perfetto
        verify(mTracker).triggerPerfetto();
    }

    @Test
    public void testBeginCancel() {
        when(mChoreographer.getVsyncId()).thenReturn(100L);
        mTracker.begin();
        verify(mRenderer).addObserver(any());

        // First frame - not janky
        sendFrame(4, JANK_NONE, 100L);

        // normal frame - not janky
        sendFrame(4, JANK_NONE, 101L);

        // a janky frame
        sendFrame(50, JANK_APP_DEADLINE_MISSED, 102L);

        mTracker.cancel(FrameTracker.REASON_CANCEL_NORMAL);
        verify(mTracker).removeObservers();
        // Since the tracker has been cancelled, shouldn't trigger perfetto.
        verify(mTracker, never()).triggerPerfetto();
    }

    @Test
    public void testCancelIfEndVsyncIdEqualsToBeginVsyncId() {
        when(mChoreographer.getVsyncId()).thenReturn(100L);
        mTracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // end the trace session
        when(mChoreographer.getVsyncId()).thenReturn(101L);
        mTracker.end(FrameTracker.REASON_END_NORMAL);

        // Since the begin vsync id (101) equals to the end vsync id (101), will be treat as cancel.
        verify(mTracker).cancel(FrameTracker.REASON_CANCEL_SAME_VSYNC);

        // Observers should be removed in this case, or FrameTracker object will be leaked.
        verify(mTracker).removeObservers();

        // Should never trigger Perfetto since it is a cancel.
        verify(mTracker, never()).triggerPerfetto();
    }

    @Test
    public void testCancelIfEndVsyncIdLessThanBeginVsyncId() {
        when(mChoreographer.getVsyncId()).thenReturn(100L);
        mTracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // end the trace session at the same vsync id, end vsync id will less than the begin one.
        // Because the begin vsync id is supposed to the next frame,
        mTracker.end(FrameTracker.REASON_END_NORMAL);

        // The begin vsync id (101) is larger than the end one (100), will be treat as cancel.
        verify(mTracker).cancel(FrameTracker.REASON_CANCEL_SAME_VSYNC);

        // Observers should be removed in this case, or FrameTracker object will be leaked.
        verify(mTracker).removeObservers();

        // Should never trigger Perfetto since it is a cancel.
        verify(mTracker, never()).triggerPerfetto();
    }

    @Test
    public void testCancelWhenSessionNeverBegun() {
        mTracker.cancel(FrameTracker.REASON_CANCEL_NORMAL);
        verify(mTracker).removeObservers();
    }

    @Test
    public void testEndWhenSessionNeverBegun() {
        mTracker.end(FrameTracker.REASON_END_NORMAL);
        verify(mTracker).removeObservers();
    }

    private void sendFirstWindowFrame(long durationMillis,
            @JankType int jankType, long vsyncId) {
        sendFrame(durationMillis, jankType, vsyncId, true /* firstWindowFrame */);
    }

    private void sendFrame(long durationMillis,
            @JankType int jankType, long vsyncId) {
        sendFrame(durationMillis, jankType, vsyncId, false /* firstWindowFrame */);
    }

    private void sendFrame(long durationMillis,
            @JankType int jankType, long vsyncId, boolean firstWindowFrame) {
        when(mWrapper.getTiming()).thenReturn(new long[] { 0, vsyncId });
        doReturn(firstWindowFrame ? 1L : 0L).when(mWrapper)
                .getMetric(FrameMetrics.FIRST_DRAW_FRAME);
        doReturn(TimeUnit.MILLISECONDS.toNanos(durationMillis))
                .when(mWrapper).getMetric(FrameMetrics.TOTAL_DURATION);
        mTracker.onFrameMetricsAvailable(0);
        mListenerCapture.getValue().onJankDataAvailable(new JankData[] {
                new JankData(vsyncId, jankType)
        });
    }
}
