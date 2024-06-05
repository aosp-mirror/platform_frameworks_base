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

import static android.text.TextUtils.formatSimple;

import static com.android.internal.jank.FrameTracker.REASON_CANCEL_TIMEOUT;
import static com.android.internal.jank.FrameTracker.REASON_END_NORMAL;
import static com.android.internal.jank.InteractionJankMonitor.Configuration.generateSessionName;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.view.View;
import android.view.ViewAttachTestActivity;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.SmallTest;

import com.android.internal.jank.FrameTracker.ChoreographerWrapper;
import com.android.internal.jank.FrameTracker.FrameMetricsWrapper;
import com.android.internal.jank.FrameTracker.StatsLogWrapper;
import com.android.internal.jank.FrameTracker.SurfaceControlWrapper;
import com.android.internal.jank.FrameTracker.ThreadedRendererWrapper;
import com.android.internal.jank.FrameTracker.ViewRootWrapper;
import com.android.internal.jank.InteractionJankMonitor.Configuration;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;

@SmallTest
public class InteractionJankMonitorTest {
    private ViewAttachTestActivity mActivity;
    private View mView;
    private Handler mHandler;
    private HandlerThread mWorker;

    @Rule
    public ActivityScenarioRule<ViewAttachTestActivity> mRule =
            new ActivityScenarioRule<>(ViewAttachTestActivity.class);

    @Rule
    public final Expect mExpect = Expect.create();

    @Before
    public void setup() {
        mRule.getScenario().onActivity(activity -> mActivity = activity);
        mView = mActivity.getWindow().getDecorView();
        assertThat(mView.isAttachedToWindow()).isTrue();

        mHandler = spy(new Handler(mActivity.getMainLooper()));
        doReturn(true).when(mHandler).sendMessageAtTime(any(), anyLong());
        mWorker = mock(HandlerThread.class);
        doReturn(mHandler).when(mWorker).getThreadHandler();
    }

    @Test
    public void testBeginEnd() {
        InteractionJankMonitor monitor = createMockedInteractionJankMonitor();
        FrameTracker tracker = createMockedFrameTracker();
        doReturn(tracker).when(monitor).createFrameTracker(any());
        doNothing().when(tracker).begin();
        doReturn(true).when(tracker).end(anyInt());

        // Simulate a trace session and see if begin / end are invoked.
        assertThat(monitor.begin(mView, Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isTrue();
        verify(tracker).begin();
        assertThat(monitor.end(Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isTrue();
        verify(tracker).end(REASON_END_NORMAL);
    }

    @Test
    public void testDisabledThroughDeviceConfig() {
        InteractionJankMonitor monitor = new InteractionJankMonitor(mWorker);

        HashMap<String, String> propertiesValues = new HashMap<>();
        propertiesValues.put("enabled", "false");
        DeviceConfig.Properties properties = new DeviceConfig.Properties(
                DeviceConfig.NAMESPACE_INTERACTION_JANK_MONITOR, propertiesValues);
        monitor.updateProperties(properties);

        assertThat(monitor.begin(mView, Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isFalse();
        assertThat(monitor.end(Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isFalse();
    }

    @Test
    public void testCheckInitState() {
        InteractionJankMonitor monitor = new InteractionJankMonitor(mWorker);
        View view = new View(mActivity);
        assertThat(view.isAttachedToWindow()).isFalse();

        // Should return false if the view passed in is not attached to window yet.
        assertThat(monitor.begin(view, Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isFalse();
        assertThat(monitor.end(Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isFalse();
    }

    @Test
    public void testBeginTimeout() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        InteractionJankMonitor monitor = createMockedInteractionJankMonitor();
        FrameTracker tracker = createMockedFrameTracker();
        doReturn(tracker).when(monitor).createFrameTracker(any());
        doNothing().when(tracker).begin();
        doReturn(true).when(tracker).cancel(anyInt());

        assertThat(monitor.begin(mView, Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isTrue();
        verify(tracker).begin();
        verify(monitor).scheduleTimeoutAction(any(), captor.capture());
        Runnable runnable = captor.getValue();
        assertThat(runnable).isNotNull();
        runnable.run();
        verify(monitor).cancel(Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, REASON_CANCEL_TIMEOUT);
        verify(tracker).cancel(REASON_CANCEL_TIMEOUT);
    }

    @Test
    public void testSessionNameLengthLimit() {
        final int cujType = Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE;
        final String cujName = Cuj.getNameOfCuj(cujType);
        final String cujTag = "ThisIsTheCujTag";
        final String tooLongTag = cujTag.repeat(10);

        // Normal case, no postfix.
        assertThat(generateSessionName(cujName, "")).isEqualTo(formatSimple("J<%s>", cujName));

        // Normal case, with postfix.
        assertThat(generateSessionName(cujName, cujTag))
                .isEqualTo(formatSimple("J<%s::%s>", cujName, cujTag));

        // Since the length of the cuj name is tested in another test, no need to test it here.
        // Too long postfix case, should trim the postfix and keep the cuj name completed.
        final String expectedTrimmedName = formatSimple("J<%s::%s>", cujName,
                "ThisIsTheCujTagThisIsTheCujTagThisIsTheCujTagThisIsTheCujTagThi...");
        assertThat(generateSessionName(cujName, tooLongTag)).isEqualTo(expectedTrimmedName);
    }

    private InteractionJankMonitor createMockedInteractionJankMonitor() {
        InteractionJankMonitor monitor = spy(new InteractionJankMonitor(mWorker));
        doReturn(true).when(monitor).shouldMonitor();
        return monitor;
    }

    private FrameTracker createMockedFrameTracker() {
        ThreadedRendererWrapper threadedRenderer = mock(ThreadedRendererWrapper.class);
        doNothing().when(threadedRenderer).addObserver(any());
        doNothing().when(threadedRenderer).removeObserver(any());

        ViewRootWrapper viewRoot = spy(new ViewRootWrapper(mView.getViewRootImpl()));
        doNothing().when(viewRoot).addSurfaceChangedCallback(any());
        doNothing().when(viewRoot).removeSurfaceChangedCallback(any());

        SurfaceControlWrapper surfaceControl = mock(SurfaceControlWrapper.class);
        doNothing().when(surfaceControl).addJankStatsListener(any(), any());
        doNothing().when(surfaceControl).removeJankStatsListener(any());

        final ChoreographerWrapper choreographer = mock(ChoreographerWrapper.class);
        doReturn(SystemClock.elapsedRealtime()).when(choreographer).getVsyncId();

        Configuration configuration = mock(Configuration.class);
        when(configuration.isSurfaceOnly()).thenReturn(false);
        when(configuration.getView()).thenReturn(mView);
        when(configuration.getDisplayId()).thenReturn(42);
        when(configuration.logToStatsd()).thenReturn(false);
        when(configuration.getHandler()).thenReturn(mHandler);

        FrameTracker tracker = spy(new FrameTracker(configuration,
                threadedRenderer, viewRoot, surfaceControl, choreographer,
                new FrameMetricsWrapper(), new StatsLogWrapper(null),
                /* traceThresholdMissedFrames= */ 1,
                /* traceThresholdFrameTimeMillis= */ -1,
                /* listener */ null));

        doNothing().when(tracker).postTraceStartMarker(any());

        return tracker;
    }
}
