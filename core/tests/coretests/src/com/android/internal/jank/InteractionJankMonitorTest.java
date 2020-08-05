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

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.os.HandlerThread;
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
import org.testng.Assert;

import java.util.HashMap;
import java.util.Map;

@SmallTest
public class InteractionJankMonitorTest {
    private ViewAttachTestActivity mActivity;
    private View mView;
    private FrameTracker mTracker;

    @Rule
    public ActivityTestRule<ViewAttachTestActivity> mRule =
            new ActivityTestRule<>(ViewAttachTestActivity.class);

    @Before
    public void setup() {
        // Prepare an activity for getting ThreadedRenderer later.
        mActivity = mRule.getActivity();
        mView = mActivity.getWindow().getDecorView();
        assertThat(mView.isAttachedToWindow()).isTrue();

        InteractionJankMonitor.reset();

        // Prepare a FrameTracker to inject.
        Session session = new Session(CUJ_NOTIFICATION_SHADE_GESTURE);
        FrameMetricsWrapper wrapper = Mockito.spy(new FrameTracker.FrameMetricsWrapper());
        ThreadedRendererWrapper renderer =
                Mockito.spy(new ThreadedRendererWrapper(mView.getThreadedRenderer()));
        Handler handler = mActivity.getMainThreadHandler();
        mTracker = Mockito.spy(new FrameTracker(session, handler, renderer, wrapper));
    }

    @Test
    public void testBeginEnd() {
        // Should throw exception if the view is not attached.
        Assert.assertThrows(IllegalStateException.class,
                () -> InteractionJankMonitor.init(new View(mActivity)));

        // Verify we init InteractionJankMonitor correctly.
        Map<String, FrameTracker> map = new HashMap<>();
        HandlerThread worker = Mockito.spy(new HandlerThread("Aot-test"));
        doNothing().when(worker).start();
        InteractionJankMonitor.init(mView, mView.getThreadedRenderer(), map, worker);
        verify(worker).start();

        // Simulate a trace session and see if begin / end are invoked.
        Session session = new Session(CUJ_NOTIFICATION_SHADE_GESTURE);
        assertThat(map.get(session.getName())).isNull();
        InteractionJankMonitor.begin(CUJ_NOTIFICATION_SHADE_GESTURE, mTracker);
        verify(mTracker).begin();
        assertThat(map.get(session.getName())).isEqualTo(mTracker);
        InteractionJankMonitor.end(CUJ_NOTIFICATION_SHADE_GESTURE);
        verify(mTracker).end();
        assertThat(map.get(session.getName())).isNull();
    }

    @Test
    public void testCheckInitState() {
        // Should throw exception if invoking begin / end without init invocation.
        Assert.assertThrows(IllegalStateException.class,
                () -> InteractionJankMonitor.begin(CUJ_NOTIFICATION_SHADE_GESTURE));
        Assert.assertThrows(IllegalStateException.class,
                () -> InteractionJankMonitor.end(CUJ_NOTIFICATION_SHADE_GESTURE));

        // Everything should be fine if invoking init first.
        boolean thrown = false;
        try {
            InteractionJankMonitor.init(mActivity.getWindow().getDecorView());
            InteractionJankMonitor.begin(CUJ_NOTIFICATION_SHADE_GESTURE);
            InteractionJankMonitor.end(CUJ_NOTIFICATION_SHADE_GESTURE);
        } catch (Exception ex) {
            thrown = true;
        } finally {
            assertThat(thrown).isFalse();
        }
    }

}
