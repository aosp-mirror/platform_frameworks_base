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
import static com.android.internal.jank.InteractionJankMonitor.CUJ_TO_STATSD_INTERACTION_TYPE;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_WALLPAPER_TRANSITION;
import static com.android.internal.util.FrameworkStatsLog.UI_INTERACTION_FRAME_INFO_REPORTED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.android.internal.jank.FrameTracker.StatsLogWrapper;
import com.android.internal.jank.FrameTracker.ThreadedRendererWrapper;
import com.android.internal.jank.InteractionJankMonitor.Configuration;
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

    private ThreadedRendererWrapper mRenderer;
    private FrameMetricsWrapper mWrapper;
    private SurfaceControlWrapper mSurfaceControlWrapper;
    private ViewRootWrapper mViewRootWrapper;
    private ChoreographerWrapper mChoreographer;
    private StatsLogWrapper mStatsLog;
    private ArgumentCaptor<OnJankDataListener> mListenerCapture;
    private SurfaceControl mSurfaceControl;

    @Before
    public void setup() {
        // Prepare an activity for getting ThreadedRenderer later.
        mActivity = mRule.getActivity();
        View view = mActivity.getWindow().getDecorView();
        assertThat(view.isAttachedToWindow()).isTrue();

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
        mStatsLog = mock(StatsLogWrapper.class);
    }

    private FrameTracker spyFrameTracker(int cuj, String postfix, boolean surfaceOnly) {
        Handler handler = mRule.getActivity().getMainThreadHandler();
        Session session = new Session(cuj, postfix);
        Configuration config = mock(Configuration.class);
        when(config.isSurfaceOnly()).thenReturn(surfaceOnly);
        when(config.getSurfaceControl()).thenReturn(mSurfaceControl);
        when(config.shouldDeferMonitor()).thenReturn(true);
        FrameTracker frameTracker = Mockito.spy(
                new FrameTracker(session, handler, mRenderer, mViewRootWrapper,
                        mSurfaceControlWrapper, mChoreographer, mWrapper, mStatsLog,
                        /* traceThresholdMissedFrames= */ 1,
                        /* traceThresholdFrameTimeMillis= */ -1,
                        /* FrameTrackerListener= */ null, config));
        doNothing().when(frameTracker).triggerPerfetto();
        doNothing().when(frameTracker).postTraceStartMarker();
        return frameTracker;
    }

    @Test
    public void testOnlyFirstWindowFrameOverThreshold() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX, /* surfaceOnly= */ false);

        // Just provide current timestamp anytime mWrapper asked for VSYNC_TIMESTAMP
        when(mWrapper.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
                .then(unusedInvocation -> System.nanoTime());

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        tracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame with a long duration - should not be taken into account
        sendFirstWindowFrame(tracker, 100, JANK_APP_DEADLINE_MISSED, 100L);

        // send another frame with a short duration - should not be considered janky
        sendFrame(tracker, 5, JANK_NONE, 101L);

        // end the trace session, the last janky frame is after the end() so is discarded.
        when(mChoreographer.getVsyncId()).thenReturn(102L);
        tracker.end(FrameTracker.REASON_END_NORMAL);
        sendFrame(tracker, 5, JANK_NONE, 102L);
        sendFrame(tracker, 500, JANK_APP_DEADLINE_MISSED, 103L);

        verify(tracker).removeObservers();
        verify(tracker, never()).triggerPerfetto();
        verify(mStatsLog).write(eq(UI_INTERACTION_FRAME_INFO_REPORTED),
                eq(CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE]),
                eq(2L) /* totalFrames */,
                eq(0L) /* missedFrames */,
                eq(5000000L) /* maxFrameTimeNanos */,
                eq(0L) /* missedSfFramesCount */,
                eq(0L) /* missedAppFramesCount */);
    }

    @Test
    public void testSfJank() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX, /* surfaceOnly= */ false);

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        tracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame - not janky
        sendFrame(tracker, 4, JANK_NONE, 100L);

        // send another frame - should be considered janky
        sendFrame(tracker, 40, JANK_SURFACEFLINGER_DEADLINE_MISSED, 101L);

        // end the trace session
        when(mChoreographer.getVsyncId()).thenReturn(102L);
        tracker.end(FrameTracker.REASON_END_NORMAL);
        sendFrame(tracker, 4, JANK_NONE, 102L);

        verify(tracker).removeObservers();

        // We detected a janky frame - trigger Perfetto
        verify(tracker).triggerPerfetto();

        verify(mStatsLog).write(eq(UI_INTERACTION_FRAME_INFO_REPORTED),
                eq(CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE]),
                eq(2L) /* totalFrames */,
                eq(1L) /* missedFrames */,
                eq(40000000L) /* maxFrameTimeNanos */,
                eq(1L) /* missedSfFramesCount */,
                eq(0L) /* missedAppFramesCount */);
    }

    @Test
    public void testFirstFrameJankyNoTrigger() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX, /* surfaceOnly= */ false);

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        tracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame - janky
        sendFrame(tracker, 40, JANK_APP_DEADLINE_MISSED, 100L);

        // send another frame - not jank
        sendFrame(tracker, 4, JANK_NONE, 101L);

        // end the trace session
        when(mChoreographer.getVsyncId()).thenReturn(102L);
        tracker.end(FrameTracker.REASON_END_NORMAL);
        sendFrame(tracker, 4, JANK_NONE, 102L);

        verify(tracker).removeObservers();

        // We detected a janky frame - trigger Perfetto
        verify(tracker, never()).triggerPerfetto();

        verify(mStatsLog).write(eq(UI_INTERACTION_FRAME_INFO_REPORTED),
                eq(CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE]),
                eq(2L) /* totalFrames */,
                eq(0L) /* missedFrames */,
                eq(4000000L) /* maxFrameTimeNanos */,
                eq(0L) /* missedSfFramesCount */,
                eq(0L) /* missedAppFramesCount */);
    }

    @Test
    public void testOtherFrameOverThreshold() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX, /* surfaceOnly= */ false);

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        tracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame - not janky
        sendFrame(tracker, 4, JANK_NONE, 100L);

        // send another frame - should be considered janky
        sendFrame(tracker, 40, JANK_APP_DEADLINE_MISSED, 101L);

        // end the trace session
        when(mChoreographer.getVsyncId()).thenReturn(102L);
        tracker.end(FrameTracker.REASON_END_NORMAL);
        sendFrame(tracker, 4, JANK_NONE, 102L);

        verify(tracker).removeObservers();

        // We detected a janky frame - trigger Perfetto
        verify(tracker).triggerPerfetto();

        verify(mStatsLog).write(eq(UI_INTERACTION_FRAME_INFO_REPORTED),
                eq(CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE]),
                eq(2L) /* totalFrames */,
                eq(1L) /* missedFrames */,
                eq(40000000L) /* maxFrameTimeNanos */,
                eq(0L) /* missedSfFramesCount */,
                eq(1L) /* missedAppFramesCount */);
    }

    @Test
    public void testLastFrameOverThresholdBeforeEnd() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX, /* surfaceOnly= */ false);

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        tracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame - not janky
        sendFrame(tracker, 4, JANK_NONE, 100L);

        // send another frame - not janky
        sendFrame(tracker, 4, JANK_NONE, 101L);

        // end the trace session, simulate one more valid callback came after the end call.
        when(mChoreographer.getVsyncId()).thenReturn(102L);
        tracker.end(FrameTracker.REASON_END_NORMAL);
        sendFrame(tracker, 50, JANK_APP_DEADLINE_MISSED, 102L);

        // One more callback with VSYNC after the end() vsync id.
        sendFrame(tracker, 4, JANK_NONE, 103L);

        verify(tracker).removeObservers();

        // We detected a janky frame - trigger Perfetto
        verify(tracker).triggerPerfetto();

        verify(mStatsLog).write(eq(UI_INTERACTION_FRAME_INFO_REPORTED),
                eq(CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE]),
                eq(2L) /* totalFrames */,
                eq(1L) /* missedFrames */,
                eq(50000000L) /* maxFrameTimeNanos */,
                eq(0L) /* missedSfFramesCount */,
                eq(1L) /* missedAppFramesCount */);
    }

    /**
     * b/223787365
     */
    @Test
    public void testNoOvercountingAfterEnd() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX, /* surfaceOnly= */ false);

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        tracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // send first frame - not janky
        sendFrame(tracker, 4, JANK_NONE, 100L);

        // send another frame - not janky
        sendFrame(tracker, 4, JANK_NONE, 101L);

        // end the trace session, simulate one more valid callback came after the end call.
        when(mChoreographer.getVsyncId()).thenReturn(102L);
        tracker.end(FrameTracker.REASON_END_NORMAL);

        // Send incomplete callback for 102L
        sendSfFrame(102L, JANK_NONE);

        // Send janky but complete callbck fo 103L
        sendFrame(tracker, 50, JANK_APP_DEADLINE_MISSED, 103L);

        verify(tracker).removeObservers();
        verify(tracker, never()).triggerPerfetto();
        verify(mStatsLog).write(eq(UI_INTERACTION_FRAME_INFO_REPORTED),
                eq(CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE]),
                eq(2L) /* totalFrames */,
                eq(0L) /* missedFrames */,
                eq(4000000L) /* maxFrameTimeNanos */,
                eq(0L) /* missedSfFramesCount */,
                eq(0L) /* missedAppFramesCount */);
    }

    @Test
    public void testBeginCancel() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX, /* surfaceOnly= */ false);

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        tracker.begin();
        verify(mRenderer).addObserver(any());

        // First frame - not janky
        sendFrame(tracker, 4, JANK_NONE, 100L);

        // normal frame - not janky
        sendFrame(tracker, 4, JANK_NONE, 101L);

        // a janky frame
        sendFrame(tracker, 50, JANK_APP_DEADLINE_MISSED, 102L);

        tracker.cancel(FrameTracker.REASON_CANCEL_NORMAL);
        verify(tracker).removeObservers();
        // Since the tracker has been cancelled, shouldn't trigger perfetto.
        verify(tracker, never()).triggerPerfetto();
    }

    @Test
    public void testCancelIfEndVsyncIdEqualsToBeginVsyncId() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX, /* surfaceOnly= */ false);

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        tracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // end the trace session
        when(mChoreographer.getVsyncId()).thenReturn(101L);
        tracker.end(FrameTracker.REASON_END_NORMAL);

        // Since the begin vsync id (101) equals to the end vsync id (101), will be treat as cancel.
        verify(tracker).cancel(FrameTracker.REASON_CANCEL_SAME_VSYNC);

        // Observers should be removed in this case, or FrameTracker object will be leaked.
        verify(tracker).removeObservers();

        // Should never trigger Perfetto since it is a cancel.
        verify(tracker, never()).triggerPerfetto();
    }

    @Test
    public void testCancelIfEndVsyncIdLessThanBeginVsyncId() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX, /* surfaceOnly= */ false);

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        tracker.begin();
        verify(mRenderer, only()).addObserver(any());

        // end the trace session at the same vsync id, end vsync id will less than the begin one.
        // Because the begin vsync id is supposed to the next frame,
        tracker.end(FrameTracker.REASON_END_NORMAL);

        // The begin vsync id (101) is larger than the end one (100), will be treat as cancel.
        verify(tracker).cancel(FrameTracker.REASON_CANCEL_SAME_VSYNC);

        // Observers should be removed in this case, or FrameTracker object will be leaked.
        verify(tracker).removeObservers();

        // Should never trigger Perfetto since it is a cancel.
        verify(tracker, never()).triggerPerfetto();
    }

    @Test
    public void testCancelWhenSessionNeverBegun() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX, /* surfaceOnly= */ false);

        tracker.cancel(FrameTracker.REASON_CANCEL_NORMAL);
        verify(tracker).removeObservers();
    }

    @Test
    public void testEndWhenSessionNeverBegun() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX, /* surfaceOnly= */ false);

        tracker.end(FrameTracker.REASON_END_NORMAL);
        verify(tracker).removeObservers();
    }

    @Test
    public void testSurfaceOnlyOtherFrameJanky() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_WALLPAPER_TRANSITION, CUJ_POSTFIX, /* surfaceOnly= */ true);

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        tracker.begin();
        verify(mSurfaceControlWrapper).addJankStatsListener(any(), any());

        // First frame - not janky
        sendFrame(tracker, JANK_NONE, 100L);
        // normal frame - not janky
        sendFrame(tracker, JANK_NONE, 101L);
        // a janky frame
        sendFrame(tracker, JANK_APP_DEADLINE_MISSED, 102L);

        when(mChoreographer.getVsyncId()).thenReturn(102L);
        tracker.end(FrameTracker.REASON_CANCEL_NORMAL);

        // an extra frame to trigger finish
        sendFrame(tracker, JANK_NONE, 103L);

        verify(mSurfaceControlWrapper).removeJankStatsListener(any());
        verify(tracker).triggerPerfetto();

        verify(mStatsLog).write(eq(UI_INTERACTION_FRAME_INFO_REPORTED),
                eq(CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_WALLPAPER_TRANSITION]),
                eq(2L) /* totalFrames */,
                eq(1L) /* missedFrames */,
                eq(0L) /* maxFrameTimeNanos */,
                eq(0L) /* missedSfFramesCount */,
                eq(1L) /* missedAppFramesCount */);
    }

    @Test
    public void testSurfaceOnlyFirstFrameJanky() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_WALLPAPER_TRANSITION, CUJ_POSTFIX, /* surfaceOnly= */ true);

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        tracker.begin();
        verify(mSurfaceControlWrapper).addJankStatsListener(any(), any());

        // First frame - janky
        sendFrame(tracker, JANK_APP_DEADLINE_MISSED, 100L);
        // normal frame - not janky
        sendFrame(tracker, JANK_NONE, 101L);
        // normal frame - not janky
        sendFrame(tracker, JANK_NONE, 102L);

        when(mChoreographer.getVsyncId()).thenReturn(102L);
        tracker.end(FrameTracker.REASON_CANCEL_NORMAL);

        // an extra frame to trigger finish
        sendFrame(tracker, JANK_NONE, 103L);

        verify(mSurfaceControlWrapper).removeJankStatsListener(any());
        verify(tracker, never()).triggerPerfetto();

        verify(mStatsLog).write(eq(UI_INTERACTION_FRAME_INFO_REPORTED),
                eq(CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_WALLPAPER_TRANSITION]),
                eq(2L) /* totalFrames */,
                eq(0L) /* missedFrames */,
                eq(0L) /* maxFrameTimeNanos */,
                eq(0L) /* missedSfFramesCount */,
                eq(0L) /* missedAppFramesCount */);
    }

    @Test
    public void testSurfaceOnlyLastFrameJanky() {
        FrameTracker tracker = spyFrameTracker(
                CUJ_WALLPAPER_TRANSITION, CUJ_POSTFIX, /* surfaceOnly= */ true);

        when(mChoreographer.getVsyncId()).thenReturn(100L);
        tracker.begin();
        verify(mSurfaceControlWrapper).addJankStatsListener(any(), any());

        // First frame - not janky
        sendFrame(tracker, JANK_NONE, 100L);
        // normal frame - not janky
        sendFrame(tracker, JANK_NONE, 101L);
        // normal frame - not janky
        sendFrame(tracker, JANK_NONE, 102L);

        when(mChoreographer.getVsyncId()).thenReturn(102L);
        tracker.end(FrameTracker.REASON_CANCEL_NORMAL);

        // janky frame, should be ignored, trigger finish
        sendFrame(tracker, JANK_APP_DEADLINE_MISSED, 103L);

        verify(mSurfaceControlWrapper).removeJankStatsListener(any());
        verify(tracker, never()).triggerPerfetto();

        verify(mStatsLog).write(eq(UI_INTERACTION_FRAME_INFO_REPORTED),
                eq(CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_WALLPAPER_TRANSITION]),
                eq(2L) /* totalFrames */,
                eq(0L) /* missedFrames */,
                eq(0L) /* maxFrameTimeNanos */,
                eq(0L) /* missedSfFramesCount */,
                eq(0L) /* missedAppFramesCount */);
    }

    private void sendFirstWindowFrame(FrameTracker tracker, long durationMillis,
            @JankType int jankType, long vsyncId) {
        sendFrame(tracker, durationMillis, jankType, vsyncId, /* firstWindowFrame= */ true);
    }

    private void sendFrame(FrameTracker tracker, long durationMillis,
            @JankType int jankType, long vsyncId) {
        sendFrame(tracker, durationMillis, jankType, vsyncId, /* firstWindowFrame= */ false);
    }

    /**
     * Used for surface only test.
     */
    private void sendFrame(FrameTracker tracker, @JankType int jankType, long vsyncId) {
        sendFrame(tracker, /* durationMillis= */ -1,
                jankType, vsyncId, /* firstWindowFrame= */ false);
    }

    private void sendFrame(FrameTracker tracker, long durationMillis,
            @JankType int jankType, long vsyncId, boolean firstWindowFrame) {
        if (!tracker.mSurfaceOnly) {
            sendHwuiFrame(tracker, durationMillis, vsyncId, firstWindowFrame);
        }
        sendSfFrame(vsyncId, jankType);
    }

    private void sendHwuiFrame(FrameTracker tracker, long durationMillis, long vsyncId,
            boolean firstWindowFrame) {
        when(mWrapper.getTiming()).thenReturn(new long[]{0, vsyncId});
        doReturn(firstWindowFrame ? 1L : 0L).when(mWrapper)
                .getMetric(FrameMetrics.FIRST_DRAW_FRAME);
        doReturn(TimeUnit.MILLISECONDS.toNanos(durationMillis))
                .when(mWrapper).getMetric(FrameMetrics.TOTAL_DURATION);
        tracker.onFrameMetricsAvailable(0);
    }

    private void sendSfFrame(long vsyncId, @JankType int jankType) {
        mListenerCapture.getValue().onJankDataAvailable(new JankData[] {
                new JankData(vsyncId, jankType)
        });
    }
}
