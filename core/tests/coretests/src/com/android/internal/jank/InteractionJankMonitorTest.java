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
import static com.android.internal.jank.InteractionJankMonitor.CUJ_LOCKSCREEN_LAUNCH_CAMERA;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_ADD;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_APP_START;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_HEADS_UP_APPEAR;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_REMOVE;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_ROW_EXPAND;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_ROW_SWIPE;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_SCROLL_FLING;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_SPLIT_SCREEN_RESIZE;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_TO_STATSD_INTERACTION_TYPE;
import static com.android.internal.jank.InteractionJankMonitor.MAX_LENGTH_OF_CUJ_NAME;
import static com.android.internal.jank.InteractionJankMonitor.getNameOfCuj;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

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
import android.util.SparseArray;
import android.view.View;
import android.view.ViewAttachTestActivity;

import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;

import com.android.internal.jank.FrameTracker.ChoreographerWrapper;
import com.android.internal.jank.FrameTracker.FrameMetricsWrapper;
import com.android.internal.jank.FrameTracker.StatsLogWrapper;
import com.android.internal.jank.FrameTracker.SurfaceControlWrapper;
import com.android.internal.jank.FrameTracker.ThreadedRendererWrapper;
import com.android.internal.jank.FrameTracker.ViewRootWrapper;
import com.android.internal.jank.InteractionJankMonitor.Configuration;
import com.android.internal.jank.InteractionJankMonitor.Session;
import com.android.internal.util.FrameworkStatsLog;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SmallTest
public class InteractionJankMonitorTest {
    private static final String CUJ_POSTFIX = "";
    private static final SparseArray<String> ENUM_NAME_EXCEPTION_MAP = new SparseArray<>();
    private static final SparseArray<String> CUJ_NAME_EXCEPTION_MAP = new SparseArray<>();
    private static final String ENUM_NAME_PREFIX =
            "UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__";

    private ViewAttachTestActivity mActivity;
    private View mView;
    private HandlerThread mWorker;

    @Rule
    public ActivityTestRule<ViewAttachTestActivity> mRule =
            new ActivityTestRule<>(ViewAttachTestActivity.class);

    @Rule
    public final Expect mExpect = Expect.create();

    @BeforeClass
    public static void initialize() {
        ENUM_NAME_EXCEPTION_MAP.put(CUJ_NOTIFICATION_ADD, getEnumName("SHADE_NOTIFICATION_ADD"));
        ENUM_NAME_EXCEPTION_MAP.put(CUJ_NOTIFICATION_APP_START, getEnumName("SHADE_APP_LAUNCH"));
        ENUM_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_HEADS_UP_APPEAR, getEnumName("SHADE_HEADS_UP_APPEAR"));
        ENUM_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR, getEnumName("SHADE_HEADS_UP_DISAPPEAR"));
        ENUM_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_REMOVE, getEnumName("SHADE_NOTIFICATION_REMOVE"));
        ENUM_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, getEnumName("NOTIFICATION_SHADE_SWIPE"));
        ENUM_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE, getEnumName("SHADE_QS_EXPAND_COLLAPSE"));
        ENUM_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE, getEnumName("SHADE_QS_SCROLL_SWIPE"));
        ENUM_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_SHADE_ROW_EXPAND, getEnumName("SHADE_ROW_EXPAND"));
        ENUM_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_SHADE_ROW_SWIPE, getEnumName("SHADE_ROW_SWIPE"));
        ENUM_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_SHADE_SCROLL_FLING, getEnumName("SHADE_SCROLL_FLING"));

        CUJ_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, "CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE");
        CUJ_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE,
                "CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE");
        CUJ_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE, "CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE");
        CUJ_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_SHADE_ROW_EXPAND, "CUJ_NOTIFICATION_SHADE_ROW_EXPAND");
        CUJ_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_SHADE_ROW_SWIPE, "CUJ_NOTIFICATION_SHADE_ROW_SWIPE");
        CUJ_NAME_EXCEPTION_MAP.put(
                CUJ_NOTIFICATION_SHADE_SCROLL_FLING, "CUJ_NOTIFICATION_SHADE_SCROLL_FLING");
        CUJ_NAME_EXCEPTION_MAP.put(CUJ_LOCKSCREEN_LAUNCH_CAMERA, "CUJ_LOCKSCREEN_LAUNCH_CAMERA");
        CUJ_NAME_EXCEPTION_MAP.put(CUJ_SPLIT_SCREEN_RESIZE, "CUJ_SPLIT_SCREEN_RESIZE");
    }

    private static String getEnumName(String name) {
        return formatSimple("%s%s", ENUM_NAME_PREFIX, name);
    }

    @Before
    public void setup() {
        // Prepare an activity for getting ThreadedRenderer later.
        mActivity = mRule.getActivity();
        mView = mActivity.getWindow().getDecorView();
        assertThat(mView.isAttachedToWindow()).isTrue();

        Handler handler = spy(new Handler(mActivity.getMainLooper()));
        doReturn(true).when(handler).sendMessageAtTime(any(), anyLong());
        mWorker = mock(HandlerThread.class);
        doReturn(handler).when(mWorker).getThreadHandler();
    }

    @Test
    public void testBeginEnd() {
        InteractionJankMonitor monitor = createMockedInteractionJankMonitor();
        FrameTracker tracker = createMockedFrameTracker(monitor, null);
        doReturn(tracker).when(monitor).createFrameTracker(any(), any());
        doNothing().when(tracker).begin();
        doReturn(true).when(tracker).end(anyInt());

        // Simulate a trace session and see if begin / end are invoked.
        assertThat(monitor.begin(mView, CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isTrue();
        verify(tracker).begin();
        assertThat(monitor.end(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isTrue();
        verify(tracker).end(REASON_END_NORMAL);
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
    public void testBeginTimeout() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        InteractionJankMonitor monitor = createMockedInteractionJankMonitor();
        FrameTracker tracker = createMockedFrameTracker(monitor, null);
        doReturn(tracker).when(monitor).createFrameTracker(any(), any());
        doNothing().when(tracker).begin();
        doReturn(true).when(tracker).cancel(anyInt());

        assertThat(monitor.begin(mView, CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE)).isTrue();
        verify(tracker).begin();
        verify(monitor).scheduleTimeoutAction(anyInt(), anyLong(), captor.capture());
        Runnable runnable = captor.getValue();
        assertThat(runnable).isNotNull();
        mWorker.getThreadHandler().removeCallbacks(runnable);
        runnable.run();
        verify(monitor).cancel(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, REASON_CANCEL_TIMEOUT);
        verify(tracker).cancel(REASON_CANCEL_TIMEOUT);
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

    @Test
    public void testCujsMapToEnumsCorrectly() {
        List<Field> cujs = Arrays.stream(InteractionJankMonitor.class.getDeclaredFields())
                .filter(f -> f.getName().startsWith("CUJ_")
                        && Modifier.isStatic(f.getModifiers())
                        && f.getType() == int.class)
                .collect(Collectors.toList());

        Map<Integer, String> enumsMap = Arrays.stream(FrameworkStatsLog.class.getDeclaredFields())
                .filter(f -> f.getName().startsWith(ENUM_NAME_PREFIX)
                        && Modifier.isStatic(f.getModifiers())
                        && f.getType() == int.class)
                .collect(Collectors.toMap(this::getIntFieldChecked, Field::getName));

        assertThat(enumsMap.size() - 1).isEqualTo(cujs.size());

        cujs.forEach(f -> {
            final int cuj = getIntFieldChecked(f);
            final String cujName = f.getName();
            final String expectedEnumName = ENUM_NAME_EXCEPTION_MAP.contains(cuj)
                    ? ENUM_NAME_EXCEPTION_MAP.get(cuj)
                    : formatSimple("%s%s", ENUM_NAME_PREFIX, cujName.substring(4));
            final int enumKey = CUJ_TO_STATSD_INTERACTION_TYPE[cuj];
            final String enumName = enumsMap.get(enumKey);
            final String expectedNameOfCuj = CUJ_NAME_EXCEPTION_MAP.contains(cuj)
                    ? CUJ_NAME_EXCEPTION_MAP.get(cuj)
                    : formatSimple("CUJ_%s", getNameOfCuj(cuj));
            mExpect
                    .withMessage(formatSimple(
                            "%s (%d) not matches %s (%d)", cujName, cuj, enumName, enumKey))
                    .that(expectedEnumName.equals(enumName))
                    .isTrue();
            mExpect
                    .withMessage(
                            formatSimple("getNameOfCuj(%d) not matches: %s, expected=%s",
                                    cuj, cujName, expectedNameOfCuj))
                    .that(cujName.equals(expectedNameOfCuj))
                    .isTrue();
        });
    }

    @Test
    public void testCujNameLimit() {
        Arrays.stream(InteractionJankMonitor.class.getDeclaredFields())
                .filter(f -> f.getName().startsWith("CUJ_")
                        && Modifier.isStatic(f.getModifiers())
                        && f.getType() == int.class)
                .forEach(f -> {
                    final int cuj = getIntFieldChecked(f);
                    mExpect
                            .withMessage(formatSimple(
                                    "Too long CUJ(%d) name: %s", cuj, getNameOfCuj(cuj)))
                            .that(getNameOfCuj(cuj).length())
                            .isAtMost(MAX_LENGTH_OF_CUJ_NAME);
                });
    }

    @Test
    public void testSessionNameLengthLimit() {
        final int cujType = CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE;
        final String cujName = getNameOfCuj(cujType);
        final String cujTag = "ThisIsTheCujTag";
        final String tooLongTag = cujTag.repeat(10);

        // Normal case, no postfix.
        Session noPostfix = new Session(cujType, "");
        assertThat(noPostfix.getName()).isEqualTo(formatSimple("J<%s>", cujName));

        // Normal case, with postfix.
        Session withPostfix = new Session(cujType, cujTag);
        assertThat(withPostfix.getName()).isEqualTo(formatSimple("J<%s::%s>", cujName, cujTag));

        // Since the length of the cuj name is tested in another test, no need to test it here.
        // Too long postfix case, should trim the postfix and keep the cuj name completed.
        final String expectedTrimmedName = formatSimple("J<%s::%s>", cujName,
                "ThisIsTheCujTagThisIsTheCujTagThisIsTheCujTagThisIsTheCujTagThisIsTheCujTagT...");
        Session longPostfix = new Session(cujType, tooLongTag);
        assertThat(longPostfix.getName()).isEqualTo(expectedTrimmedName);
    }

    private InteractionJankMonitor createMockedInteractionJankMonitor() {
        InteractionJankMonitor monitor = spy(new InteractionJankMonitor(mWorker));
        doReturn(true).when(monitor).shouldMonitor(anyInt());
        return monitor;
    }

    private FrameTracker createMockedFrameTracker(InteractionJankMonitor monitor,
            FrameTracker.FrameTrackerListener listener) {
        Session session = spy(new Session(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE, CUJ_POSTFIX));
        doReturn(false).when(session).logToStatsd();

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
        when(configuration.getHandler()).thenReturn(mView.getHandler());

        FrameTracker tracker = spy(new FrameTracker(monitor, session, mWorker.getThreadHandler(),
                threadedRenderer, viewRoot, surfaceControl, choreographer,
                new FrameMetricsWrapper(), new StatsLogWrapper(),
                /* traceThresholdMissedFrames= */ 1,
                /* traceThresholdFrameTimeMillis= */ -1, listener, configuration));

        doNothing().when(tracker).postTraceStartMarker(any());
        doNothing().when(tracker).triggerPerfetto();
        doReturn(configuration.getHandler()).when(tracker).getHandler();

        return tracker;
    }

    private int getIntFieldChecked(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }
}
