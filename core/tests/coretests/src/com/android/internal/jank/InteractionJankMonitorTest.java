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

import static com.android.internal.jank.FrameTracker.SurfaceControlWrapper;
import static com.android.internal.jank.FrameTracker.ViewRootWrapper;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_TO_STATSD_INTERACTION_TYPE;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.provider.DeviceConfig;
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
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

@SmallTest
public class InteractionJankMonitorTest {
    private ViewAttachTestActivity mActivity;
    private View mView;
    private HandlerThread mWorker;

    @Rule
    public ActivityTestRule<ViewAttachTestActivity> mRule =
            new ActivityTestRule<>(ViewAttachTestActivity.class);

    @Before
    public void setup() {
        // Prepare an activity for getting ThreadedRenderer later.
        mActivity = mRule.getActivity();
        mView = mActivity.getWindow().getDecorView();
        assertThat(mView.isAttachedToWindow()).isTrue();

        Handler handler = spy(new Handler(mActivity.getMainLooper()));
        doReturn(true).when(handler).sendMessageAtTime(any(), anyLong());
        mWorker = spy(new HandlerThread("Interaction-jank-monitor-test"));
        doNothing().when(mWorker).start();
        doReturn(handler).when(mWorker).getThreadHandler();
    }

    @Test
    public void testBeginEnd() {
        // Should return false if the view is not attached.
        InteractionJankMonitor monitor = spy(new InteractionJankMonitor(mWorker));
        verify(mWorker).start();

        Session session = new Session(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
        FrameTracker tracker = spy(new FrameTracker(session, mWorker.getThreadHandler(),
                new ThreadedRendererWrapper(mView.getThreadedRenderer()),
                new ViewRootWrapper(mView.getViewRootImpl()), new SurfaceControlWrapper(),
                mock(FrameTracker.ChoreographerWrapper.class),
                new FrameMetricsWrapper(), /*traceThresholdMissedFrames=*/ 1,
                /*traceThresholdFrameTimeMillis=*/ -1, null));
        doReturn(tracker).when(monitor).createFrameTracker(any(), any());

        // Simulate a trace session and see if begin / end are invoked.
        assertThat(monitor.begin(mView, session.getCuj())).isTrue();
        verify(tracker).begin();
        assertThat(monitor.end(session.getCuj())).isTrue();
        verify(tracker).end(FrameTracker.REASON_END_NORMAL);
    }

    @Test
    public void testDisabledThroughDeviceConfig() {
        InteractionJankMonitor monitor = new InteractionJankMonitor(mWorker);

        HashMap<String, String> propertiesValues = new HashMap<>();
        propertiesValues.put("enabled", "false");
        DeviceConfig.Properties properties = new DeviceConfig.Properties(
                DeviceConfig.NAMESPACE_INTERACTION_JANK_MONITOR, propertiesValues);
        monitor.getPropertiesChangedListener().onPropertiesChanged(properties);

        assertThat(monitor.begin(mView, CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isFalse();
        assertThat(monitor.end(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isFalse();
    }

    @Test
    public void testCheckInitState() {
        InteractionJankMonitor monitor = new InteractionJankMonitor(mWorker);
        View view = new View(mActivity);
        assertThat(view.isAttachedToWindow()).isFalse();

        // Should return false if the view passed in is not attached to window yet.
        assertThat(monitor.begin(view, CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isFalse();
        assertThat(monitor.end(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isFalse();
    }

    @Test
    public void testBeginCancel() {
        InteractionJankMonitor monitor = spy(new InteractionJankMonitor(mWorker));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);

        Session session = new Session(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
        FrameTracker tracker = spy(new FrameTracker(session, mWorker.getThreadHandler(),
                new ThreadedRendererWrapper(mView.getThreadedRenderer()),
                new ViewRootWrapper(mView.getViewRootImpl()), new SurfaceControlWrapper(),
                mock(FrameTracker.ChoreographerWrapper.class),
                new FrameMetricsWrapper(), /*traceThresholdMissedFrames=*/ 1,
                /*traceThresholdFrameTimeMillis=*/ -1, null));
        doReturn(tracker).when(monitor).createFrameTracker(any(), any());

        assertThat(monitor.begin(mView, session.getCuj())).isTrue();
        verify(tracker).begin();
        verify(mWorker.getThreadHandler(), atLeastOnce()).sendMessageAtTime(captor.capture(),
                anyLong());
        Runnable runnable = captor.getValue().getCallback();
        assertThat(runnable).isNotNull();
        mWorker.getThreadHandler().removeCallbacks(runnable);
        runnable.run();
        verify(tracker).cancel(FrameTracker.REASON_CANCEL_NORMAL);
    }

    @Test
    public void testCujTypeEnumCorrectlyDefined() throws Exception {
        List<Field> cujEnumFields =
                Arrays.stream(InteractionJankMonitor.class.getDeclaredFields())
                        .filter(field -> field.getName().startsWith("CUJ_")
                                && Modifier.isStatic(field.getModifiers())
                                && field.getType() == int.class)
                        .collect(Collectors.toList());

        HashSet<Integer> allValues = new HashSet<>();
        for (Field field : cujEnumFields) {
            int fieldValue = field.getInt(null);
            assertWithMessage(
                    "Field %s must have a mapping to a value in CUJ_TO_STATSD_INTERACTION_TYPE",
                    field.getName())
                    .that(fieldValue < CUJ_TO_STATSD_INTERACTION_TYPE.length)
                    .isTrue();
            assertWithMessage("All CujType values must be unique. Field %s repeats existing value.",
                    field.getName())
                    .that(allValues.add(fieldValue))
                    .isTrue();
        }
    }
}
