/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.windowdecor;

import static com.android.wm.shell.MockSurfaceControlHelper.createMockSurfaceControlBuilder;
import static com.android.wm.shell.MockSurfaceControlHelper.createMockSurfaceControlTransaction;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.common.DisplayController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.function.Supplier;

/**
 * Tests for {@link WindowDecoration}.
 *
 * Build/Install/Run:
 * atest WMShellUnitTests:WindowDecorationTests
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class WindowDecorationTests extends ShellTestCase {
    private static final int CAPTION_HEIGHT_DP = 32;
    private static final int SHADOW_RADIUS_DP = 5;

    private final Rect mOutsetsDp = new Rect();
    private final WindowDecoration.RelayoutResult<TestView> mRelayoutResult =
            new WindowDecoration.RelayoutResult<>();

    @Mock
    private DisplayController mMockDisplayController;
    @Mock
    private ShellTaskOrganizer mMockShellTaskOrganizer;
    @Mock
    private WindowDecoration.SurfaceControlViewHostFactory mMockSurfaceControlViewHostFactory;
    @Mock
    private SurfaceControlViewHost mMockSurfaceControlViewHost;
    @Mock
    private TestView mMockView;
    @Mock
    private WindowContainerTransaction mMockWindowContainerTransaction;

    private SurfaceControl.Builder mMockSurfaceControlBuilder;
    private SurfaceControl.Transaction mMockSurfaceControlTransaction;

    @Before
    public void setUp() {
        mMockSurfaceControlBuilder = createMockSurfaceControlBuilder(mock(SurfaceControl.class));
        mMockSurfaceControlTransaction = createMockSurfaceControlTransaction();

        doReturn(mMockSurfaceControlViewHost).when(mMockSurfaceControlViewHostFactory)
                .create(any(), any(), any(), anyBoolean());
    }

    @Test
    public void testNotCrashWhenDisplayAppearsAfterTask() {
        doReturn(mock(Display.class)).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final int displayId = Display.DEFAULT_DISPLAY + 1;
        final ActivityManager.TaskDescription.Builder taskDescriptionBuilder =
                new ActivityManager.TaskDescription.Builder()
                        .setBackgroundColor(Color.BLACK);
        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(displayId)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setVisible(true)
                .build();

        final TestWindowDecoration windowDecor =
                createWindowDecoration(taskInfo, new SurfaceControl());
        windowDecor.relayout(taskInfo);

        // It shouldn't show the window decoration when it can't obtain the display instance.
        assertThat(mRelayoutResult.mRootView).isNull();

        final ArgumentCaptor<DisplayController.OnDisplaysChangedListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(DisplayController.OnDisplaysChangedListener.class);
        verify(mMockDisplayController).addDisplayWindowListener(listenerArgumentCaptor.capture());
        final DisplayController.OnDisplaysChangedListener listener =
                listenerArgumentCaptor.getValue();

        // Adding an irrelevant display shouldn't change the result.
        listener.onDisplayAdded(Display.DEFAULT_DISPLAY);
        assertThat(mRelayoutResult.mRootView).isNull();

        final Display mockDisplay = mock(Display.class);
        doReturn(mockDisplay).when(mMockDisplayController).getDisplay(displayId);

        listener.onDisplayAdded(displayId);

        // The listener should be removed when the display shows up.
        verify(mMockDisplayController).removeDisplayWindowListener(same(listener));

        assertThat(mRelayoutResult.mRootView).isSameInstanceAs(mMockView);
        verify(mMockSurfaceControlViewHostFactory)
                .create(any(), eq(mockDisplay), any(), anyBoolean());
        verify(mMockSurfaceControlViewHost).setView(same(mMockView), any());
    }

    private TestWindowDecoration createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo, SurfaceControl testSurface) {
        return new TestWindowDecoration(mContext, mMockDisplayController, mMockShellTaskOrganizer,
                taskInfo, testSurface, () -> mMockSurfaceControlBuilder,
                mMockSurfaceControlViewHostFactory);
    }

    private static class TestView extends View implements TaskFocusStateConsumer {
        private TestView(Context context) {
            super(context);
        }

        @Override
        public void setTaskFocusState(boolean focused) {}
    }

    private class TestWindowDecoration extends WindowDecoration<TestView> {
        TestWindowDecoration(Context context, DisplayController displayController,
                ShellTaskOrganizer taskOrganizer, ActivityManager.RunningTaskInfo taskInfo,
                SurfaceControl taskSurface,
                Supplier<SurfaceControl.Builder> surfaceControlBuilderSupplier,
                SurfaceControlViewHostFactory surfaceControlViewHostFactory) {
            super(context, displayController, taskOrganizer, taskInfo, taskSurface,
                    surfaceControlBuilderSupplier, surfaceControlViewHostFactory);
        }

        @Override
        void relayout(ActivityManager.RunningTaskInfo taskInfo) {
            relayout(null /* taskInfo */, 0 /* layoutResId */, mMockView, CAPTION_HEIGHT_DP,
                    mOutsetsDp, SHADOW_RADIUS_DP, mMockSurfaceControlTransaction,
                    mMockWindowContainerTransaction, mRelayoutResult);
        }
    }
}
