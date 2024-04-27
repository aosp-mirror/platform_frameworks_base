/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.view.WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Handler;
import android.os.SystemProperties;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.view.Choreographer;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.WindowManager;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.windowdecor.WindowDecoration.RelayoutParams;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.function.Supplier;

/**
 * Tests for {@link DesktopModeWindowDecoration}.
 *
 * Build/Install/Run:
 * atest WMShellUnitTests:DesktopModeWindowDecorationTests
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DesktopModeWindowDecorationTests extends ShellTestCase {
    private static final String USE_WINDOW_SHADOWS_SYSPROP_KEY =
            "persist.wm.debug.desktop_use_window_shadows";
    private static final String FOCUSED_USE_WINDOW_SHADOWS_SYSPROP_KEY =
            "persist.wm.debug.desktop_use_window_shadows_focused_window";
    private static final String USE_ROUNDED_CORNERS_SYSPROP_KEY =
            "persist.wm.debug.desktop_use_rounded_corners";

    @Mock
    private DisplayController mMockDisplayController;
    @Mock
    private ShellTaskOrganizer mMockShellTaskOrganizer;
    @Mock
    private Handler mMockHandler;
    @Mock
    private Choreographer mMockChoreographer;
    @Mock
    private SyncTransactionQueue mMockSyncQueue;
    @Mock
    private RootTaskDisplayAreaOrganizer mMockRootTaskDisplayAreaOrganizer;
    @Mock
    private Supplier<SurfaceControl.Transaction> mMockTransactionSupplier;
    @Mock
    private SurfaceControl.Transaction mMockTransaction;
    @Mock
    private SurfaceControl mMockSurfaceControl;
    @Mock
    private SurfaceControlViewHost mMockSurfaceControlViewHost;
    @Mock
    private WindowDecoration.SurfaceControlViewHostFactory mMockSurfaceControlViewHostFactory;
    @Mock
    private TypedArray mMockRoundedCornersRadiusArray;

    private final Configuration mConfiguration = new Configuration();

    private TestableContext mTestableContext;

    /** Set up run before test class. */
    @BeforeClass
    public static void setUpClass() {
        // Reset the sysprop settings before running the test.
        SystemProperties.set(USE_WINDOW_SHADOWS_SYSPROP_KEY, "");
        SystemProperties.set(FOCUSED_USE_WINDOW_SHADOWS_SYSPROP_KEY, "");
        SystemProperties.set(USE_ROUNDED_CORNERS_SYSPROP_KEY, "");
    }

    @Before
    public void setUp() {
        doReturn(mMockSurfaceControlViewHost).when(mMockSurfaceControlViewHostFactory).create(
                any(), any(), any());
        doReturn(mMockTransaction).when(mMockTransactionSupplier).get();
        mTestableContext = new TestableContext(mContext);
        mTestableContext.ensureTestableResources();
    }

    @Test
    public void testMenusClosedWhenTaskIsInvisible() {
        doReturn(mMockTransaction).when(mMockTransaction).hide(any());

        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(false /* visible */);
        final DesktopModeWindowDecoration spyWindowDecor =
                spy(createWindowDecoration(taskInfo));

        spyWindowDecor.relayout(taskInfo);

        // Menus should close if open before the task being invisible causes relayout to return.
        verify(spyWindowDecor).closeHandleMenu();
        verify(spyWindowDecor).closeMaximizeMenu();

    }

    @Test
    public void updateRelayoutParams_noSysPropFlagsSet_windowShadowsAreEnabled() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams, mContext, taskInfo, /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat(relayoutParams.mShadowRadiusId).isNotEqualTo(Resources.ID_NULL);
    }

    @Test
    public void updateRelayoutParams_noSysPropFlagsSet_roundedCornersAreEnabled() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        fillRoundedCornersResources(/* fillValue= */ 30);
        RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat(relayoutParams.mCornerRadius).isGreaterThan(0);
    }

    @Test
    public void updateRelayoutParams_freeformAndTransparentAppearance_allowsInputFallthrough() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskInfo.taskDescription.setSystemBarsAppearance(
                APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat(relayoutParams.hasInputFeatureSpy()).isTrue();
    }

    @Test
    public void updateRelayoutParams_freeformButOpaqueAppearance_disallowsInputFallthrough() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskInfo.taskDescription.setSystemBarsAppearance(0);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat(relayoutParams.hasInputFeatureSpy()).isFalse();
    }

    @Test
    public void updateRelayoutParams_fullscreen_disallowsInputFallthrough() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat(relayoutParams.hasInputFeatureSpy()).isFalse();
    }

    @Test
    public void updateRelayoutParams_freeform_inputChannelNeeded() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat(hasNoInputChannelFeature(relayoutParams)).isFalse();
    }

    @Test
    public void updateRelayoutParams_fullscreen_inputChannelNotNeeded() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat(hasNoInputChannelFeature(relayoutParams)).isTrue();
    }

    @Test
    public void updateRelayoutParams_multiwindow_inputChannelNotNeeded() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat(hasNoInputChannelFeature(relayoutParams)).isTrue();
    }

    private void fillRoundedCornersResources(int fillValue) {
        when(mMockRoundedCornersRadiusArray.getDimensionPixelSize(anyInt(), anyInt()))
                .thenReturn(fillValue);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.array.config_roundedCornerRadiusArray, mMockRoundedCornersRadiusArray);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.dimen.rounded_corner_radius, fillValue);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.array.config_roundedCornerTopRadiusArray, mMockRoundedCornersRadiusArray);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.dimen.rounded_corner_radius_top, fillValue);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.array.config_roundedCornerBottomRadiusArray, mMockRoundedCornersRadiusArray);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.dimen.rounded_corner_radius_bottom, fillValue);
    }


    private DesktopModeWindowDecoration createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo) {
        return new DesktopModeWindowDecoration(mContext, mMockDisplayController,
                mMockShellTaskOrganizer, taskInfo, mMockSurfaceControl, mConfiguration,
                mMockHandler, mMockChoreographer, mMockSyncQueue, mMockRootTaskDisplayAreaOrganizer,
                SurfaceControl.Builder::new, mMockTransactionSupplier,
                WindowContainerTransaction::new, SurfaceControl::new,
                mMockSurfaceControlViewHostFactory);
    }

    private ActivityManager.RunningTaskInfo createTaskInfo(boolean visible) {
        final ActivityManager.TaskDescription.Builder taskDescriptionBuilder =
                new ActivityManager.TaskDescription.Builder();
        ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setVisible(visible)
                .build();
        taskInfo.topActivityInfo = new ActivityInfo();
        taskInfo.topActivityInfo.applicationInfo = new ApplicationInfo();
        taskInfo.realActivity = new ComponentName("com.android.wm.shell.windowdecor",
                "DesktopModeWindowDecorationTests");
        taskInfo.baseActivity = new ComponentName("com.android.wm.shell.windowdecor",
                "DesktopModeWindowDecorationTests");
        return taskInfo;

    }

    private static boolean hasNoInputChannelFeature(RelayoutParams params) {
        return (params.mInputFeatures & WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL)
                != 0;
    }
}
