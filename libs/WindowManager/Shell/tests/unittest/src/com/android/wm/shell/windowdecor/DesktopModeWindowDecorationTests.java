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
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT;
import static android.view.InsetsSource.FLAG_FORCE_CONSUMING;
import static android.view.InsetsSource.FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.wm.shell.MockSurfaceControlHelper.createMockSurfaceControlTransaction;
import static com.android.wm.shell.windowdecor.DesktopModeWindowDecoration.CLOSE_MAXIMIZE_MENU_DELAY_MS;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.assist.AssistContent;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.PointF;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemProperties;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.view.AttachedSurfaceControl;
import android.view.Choreographer;
import android.view.Display;
import android.view.GestureDetector;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.R;
import com.android.window.flags.Flags;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.apptoweb.AppToWebGenericLinksParser;
import com.android.wm.shell.apptoweb.AssistContentRequester;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.MultiInstanceHelper;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.CaptionState;
import com.android.wm.shell.desktopmode.WindowDecorCaptionHandleRepository;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.windowdecor.WindowDecoration.RelayoutParams;
import com.android.wm.shell.windowdecor.viewholder.AppHeaderViewHolder;
import com.android.wm.shell.windowdecor.viewhost.WindowDecorViewHost;
import com.android.wm.shell.windowdecor.viewhost.WindowDecorViewHostSupplier;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.quality.Strictness;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tests for {@link DesktopModeWindowDecoration}.
 *
 * Build/Install/Run:
 * atest WMShellUnitTests:DesktopModeWindowDecorationTests
 */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class DesktopModeWindowDecorationTests extends ShellTestCase {
    private static final String USE_WINDOW_SHADOWS_SYSPROP_KEY =
            "persist.wm.debug.desktop_use_window_shadows";
    private static final String FOCUSED_USE_WINDOW_SHADOWS_SYSPROP_KEY =
            "persist.wm.debug.desktop_use_window_shadows_focused_window";
    private static final String USE_ROUNDED_CORNERS_SYSPROP_KEY =
            "persist.wm.debug.desktop_use_rounded_corners";

    private static final Uri TEST_URI1 = Uri.parse("https://www.google.com/");
    private static final Uri TEST_URI2 = Uri.parse("https://docs.google.com/");
    private static final Uri TEST_URI3 = Uri.parse("https://slides.google.com/");

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(DEVICE_DEFAULT);

    @Mock
    private DisplayController mMockDisplayController;
    @Mock
    private SplitScreenController mMockSplitScreenController;
    @Mock
    private ShellTaskOrganizer mMockShellTaskOrganizer;
    @Mock
    private Choreographer mMockChoreographer;
    @Mock
    private SyncTransactionQueue mMockSyncQueue;
    @Mock
    private AppHeaderViewHolder.Factory mMockAppHeaderViewHolderFactory;
    @Mock
    private AppHeaderViewHolder mMockAppHeaderViewHolder;
    @Mock
    private RootTaskDisplayAreaOrganizer mMockRootTaskDisplayAreaOrganizer;
    @Mock
    private Supplier<SurfaceControl.Transaction> mMockTransactionSupplier;
    @Mock
    private SurfaceControl mMockSurfaceControl;
    @Mock
    private SurfaceControlViewHost mMockSurfaceControlViewHost;
    @Mock
    private AttachedSurfaceControl mMockRootSurfaceControl;
    @Mock
    private WindowDecoration.SurfaceControlViewHostFactory mMockSurfaceControlViewHostFactory;
    @Mock
    private WindowDecorViewHostSupplier mMockWindowDecorViewHostSupplier;
    @Mock
    private WindowDecorViewHost mMockWindowDecorViewHost;
    @Mock
    private TypedArray mMockRoundedCornersRadiusArray;
    @Mock
    private TestTouchEventListener mMockTouchEventListener;
    @Mock
    private DesktopModeWindowDecoration.ExclusionRegionListener mMockExclusionRegionListener;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private Handler mMockHandler;
    @Mock
    private Consumer<Uri> mMockOpenInBrowserClickListener;
    @Mock
    private AppToWebGenericLinksParser mMockGenericLinksParser;
    @Mock
    private WindowManager mMockWindowManager;
    @Mock
    private AssistContentRequester mMockAssistContentRequester;
    @Mock
    private HandleMenu mMockHandleMenu;
    @Mock
    private HandleMenuFactory mMockHandleMenuFactory;
    @Mock
    private MultiInstanceHelper mMockMultiInstanceHelper;
    @Mock
    private WindowDecorCaptionHandleRepository mMockCaptionHandleRepository;
    @Captor
    private ArgumentCaptor<Function1<Boolean, Unit>> mOnMaxMenuHoverChangeListener;
    @Captor
    private ArgumentCaptor<Runnable> mCloseMaxMenuRunnable;

    private final InsetsState mInsetsState = new InsetsState();
    private SurfaceControl.Transaction mMockTransaction;
    private StaticMockitoSession mMockitoSession;
    private TestableContext mTestableContext;
    private final ShellExecutor mBgExecutor = new TestShellExecutor();
    private final AssistContent mAssistContent = new AssistContent();

    /** Set up run before test class. */
    @BeforeClass
    public static void setUpClass() {
        // Reset the sysprop settings before running the test.
        SystemProperties.set(USE_WINDOW_SHADOWS_SYSPROP_KEY, "");
        SystemProperties.set(FOCUSED_USE_WINDOW_SHADOWS_SYSPROP_KEY, "");
        SystemProperties.set(USE_ROUNDED_CORNERS_SYSPROP_KEY, "");
    }

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        mMockitoSession = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(DesktopModeStatus.class)
                .startMocking();
        when(DesktopModeStatus.useDesktopOverrideDensity()).thenReturn(false);
        doReturn(mMockSurfaceControlViewHost).when(mMockSurfaceControlViewHostFactory).create(
                any(), any(), any());
        when(mMockSurfaceControlViewHost.getRootSurfaceControl())
                .thenReturn(mMockRootSurfaceControl);
        mMockTransaction = createMockSurfaceControlTransaction();
        doReturn(mMockTransaction).when(mMockTransactionSupplier).get();
        mTestableContext = new TestableContext(mContext);
        mTestableContext.ensureTestableResources();
        mContext.setMockPackageManager(mMockPackageManager);
        when(mMockMultiInstanceHelper.supportsMultiInstanceSplit(any()))
                .thenReturn(false);
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("applicationLabel");
        final ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.getActivityInfo(any(), anyInt())).thenReturn(activityInfo);
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController).getDisplay(Display.DEFAULT_DISPLAY);
        doReturn(mInsetsState).when(mMockDisplayController).getInsetsState(anyInt());
        when(mMockHandleMenuFactory.create(any(), any(), anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(mMockHandleMenu);
        when(mMockMultiInstanceHelper.supportsMultiInstanceSplit(any())).thenReturn(false);
        when(mMockWindowDecorViewHostSupplier.acquire(any(), eq(defaultDisplay)))
                .thenReturn(mMockWindowDecorViewHost);
        when(mMockWindowDecorViewHost.getSurfaceControl()).thenReturn(mock(SurfaceControl.class));
        when(mMockAppHeaderViewHolderFactory.create(any(), any(), any(), any(), any(), any(), any(),
                any())).thenReturn(mMockAppHeaderViewHolder);
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
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
    @EnableFlags(Flags.FLAG_ENABLE_APP_HEADER_WITH_TASK_DENSITY)
    public void updateRelayoutParams_appHeader_usesTaskDensity() {
        final int systemDensity = mTestableContext.getOrCreateTestableResources().getResources()
                .getConfiguration().densityDpi;
        final int customTaskDensity = systemDensity + 300;
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskInfo.configuration.densityDpi = customTaskDensity;
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat(relayoutParams.mWindowDecorConfig.densityDpi).isEqualTo(customTaskDensity);
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_APP_HEADER_WITH_TASK_DENSITY)
    public void updateRelayoutParams_appHeader_usesSystemDensity() {
        when(DesktopModeStatus.useDesktopOverrideDensity()).thenReturn(true);
        final int systemDensity = mTestableContext.getOrCreateTestableResources().getResources()
                .getConfiguration().densityDpi;
        final int customTaskDensity = systemDensity + 300;
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        taskInfo.configuration.densityDpi = customTaskDensity;
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat(relayoutParams.mWindowDecorConfig.densityDpi).isEqualTo(systemDensity);
    }

    @Test
    public void updateRelayoutParams_freeformAndTransparentAppearance_allowsInputFallthrough() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskInfo.taskDescription.setTopOpaqueSystemBarsAppearance(
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
        taskInfo.taskDescription.setTopOpaqueSystemBarsAppearance(0);
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
    @DisableFlags(Flags.FLAG_ENABLE_HANDLE_INPUT_FIX)
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
    @DisableFlags(Flags.FLAG_ENABLE_HANDLE_INPUT_FIX)
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

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION)
    public void updateRelayoutParams_defaultHeader_addsForceConsumingFlag() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskInfo.taskDescription.setTopOpaqueSystemBarsAppearance(0);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat((relayoutParams.mInsetSourceFlags & FLAG_FORCE_CONSUMING) != 0).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION)
    public void updateRelayoutParams_customHeader_noForceConsumptionFlag() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskInfo.taskDescription.setTopOpaqueSystemBarsAppearance(
                APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat((relayoutParams.mInsetSourceFlags & FLAG_FORCE_CONSUMING) == 0).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION_ALWAYS)
    public void updateRelayoutParams_header_addsForceConsumingCaptionBar() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat(
                (relayoutParams.mInsetSourceFlags & FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR) != 0)
                .isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION_ALWAYS)
    public void updateRelayoutParams_handle_skipsForceConsumingCaptionBar() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        assertThat(
                (relayoutParams.mInsetSourceFlags & FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR) == 0)
                .isTrue();
    }

    @Test
    public void updateRelayoutParams_handle_requestsAsyncViewHostRendering() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        // Make the task fullscreen so that its decoration is an App Handle.
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        // App Handles don't need to be rendered in sync with the task animation, per UX.
        assertThat(relayoutParams.mAsyncViewHost).isTrue();
    }

    @Test
    public void updateRelayoutParams_header_requestsSyncViewHostRendering() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        // Make the task freeform so that its decoration is an App Header.
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false);

        // App Headers must be rendered in sync with the task animation, so it cannot be delayed.
        assertThat(relayoutParams.mAsyncViewHost).isFalse();
    }

    @Test
    public void relayout_fullscreenTask_appliesTransactionImmediately() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        spyWindowDecor.relayout(taskInfo);

        verify(mMockTransaction).apply();
        verify(mMockRootSurfaceControl, never()).applyTransactionOnDraw(any());
    }

    @Test
    @Ignore("TODO(b/367235906): Due to MONITOR_INPUT permission error")
    public void relayout_freeformTask_appliesTransactionOnDraw() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        // Make non-resizable to avoid dealing with input-permissions (MONITOR_INPUT)
        taskInfo.isResizeable = false;

        spyWindowDecor.relayout(taskInfo);

        verify(mMockTransaction, never()).apply();
        verify(mMockWindowDecorViewHost).updateView(any(), any(), any(), eq(mMockTransaction));
    }

    @Test
    public void createMaximizeMenu_showsMenu() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final MaximizeMenu menu = mock(MaximizeMenu.class);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                new FakeMaximizeMenuFactory(menu));
        assertFalse(decoration.isMaximizeMenuActive());

        createMaximizeMenu(decoration, menu);

        assertTrue(decoration.isMaximizeMenuActive());
    }

    @Test
    public void maximizeMenu_unHoversMenu_schedulesCloseMenu() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final MaximizeMenu menu = mock(MaximizeMenu.class);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                new FakeMaximizeMenuFactory(menu));
        decoration.setAppHeaderMaximizeButtonHovered(false);
        createMaximizeMenu(decoration, menu);

        mOnMaxMenuHoverChangeListener.getValue().invoke(false);

        verify(mMockHandler)
                .postDelayed(mCloseMaxMenuRunnable.capture(), eq(CLOSE_MAXIMIZE_MENU_DELAY_MS));

        mCloseMaxMenuRunnable.getValue().run();
        verify(menu).close(any());
        assertFalse(decoration.isMaximizeMenuActive());
    }

    @Test
    public void maximizeMenu_unHoversButton_schedulesCloseMenu() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final MaximizeMenu menu = mock(MaximizeMenu.class);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                new FakeMaximizeMenuFactory(menu));
        decoration.setAppHeaderMaximizeButtonHovered(true);
        createMaximizeMenu(decoration, menu);

        decoration.setAppHeaderMaximizeButtonHovered(false);

        verify(mMockHandler)
                .postDelayed(mCloseMaxMenuRunnable.capture(), eq(CLOSE_MAXIMIZE_MENU_DELAY_MS));

        mCloseMaxMenuRunnable.getValue().run();
        verify(menu).close(any());
        assertFalse(decoration.isMaximizeMenuActive());
    }

    @Test
    public void maximizeMenu_hoversMenu_cancelsCloseMenu() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final MaximizeMenu menu = mock(MaximizeMenu.class);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                new FakeMaximizeMenuFactory(menu));
        createMaximizeMenu(decoration, menu);

        mOnMaxMenuHoverChangeListener.getValue().invoke(true);

        verify(mMockHandler).removeCallbacks(any());
    }

    @Test
    public void maximizeMenu_hoversButton_cancelsCloseMenu() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final MaximizeMenu menu = mock(MaximizeMenu.class);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                new FakeMaximizeMenuFactory(menu));
        createMaximizeMenu(decoration, menu);

        decoration.setAppHeaderMaximizeButtonHovered(true);

        verify(mMockHandler).removeCallbacks(any());
    }


    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_TO_WEB)
    public void capturedLink_handleMenuBrowserLinkSetToCapturedLinkIfValid() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final DesktopModeWindowDecoration decor = createWindowDecoration(
                taskInfo, TEST_URI1 /* captured link */, TEST_URI2 /* web uri */,
                TEST_URI3 /* generic link */);

        // Verify handle menu's browser link set as captured link
        createHandleMenu(decor);
        verifyHandleMenuCreated(TEST_URI1);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_TO_WEB)
    public void capturedLink_postsOnCapturedLinkExpiredRunnable() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final DesktopModeWindowDecoration decor = createWindowDecoration(
                taskInfo, TEST_URI1 /* captured link */, null /* web uri */,
                null /* generic link */);
        final ArgumentCaptor<Runnable> runnableArgument = ArgumentCaptor.forClass(Runnable.class);

        // Run runnable to set captured link to expired
        verify(mMockHandler).postDelayed(runnableArgument.capture(), anyLong());
        runnableArgument.getValue().run();

        // Verify captured link is no longer valid by verifying link is not set as handle menu
        // browser link.
        createHandleMenu(decor);
        verifyHandleMenuCreated(null /* uri */);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_TO_WEB)
    public void capturedLink_capturedLinkNotResetToSameLink() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final DesktopModeWindowDecoration decor = createWindowDecoration(
                taskInfo, TEST_URI1 /* captured link */, null /* web uri */,
                null /* generic link */);
        final ArgumentCaptor<Runnable> runnableArgument = ArgumentCaptor.forClass(Runnable.class);

        // Run runnable to set captured link to expired
        verify(mMockHandler).postDelayed(runnableArgument.capture(), anyLong());
        runnableArgument.getValue().run();

        // Relayout decor with same captured link
        decor.relayout(taskInfo);

        // Verify handle menu's browser link not set to captured link since link is expired
        createHandleMenu(decor);
        verifyHandleMenuCreated(null /* uri */);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_TO_WEB)
    public void capturedLink_capturedLinkStillUsedIfExpiredAfterHandleMenuCreation() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final DesktopModeWindowDecoration decor = createWindowDecoration(
                taskInfo, TEST_URI1 /* captured link */, null /* web uri */,
                null /* generic link */);
        final ArgumentCaptor<Runnable> runnableArgument = ArgumentCaptor.forClass(Runnable.class);

        // Create handle menu before link expires
        createHandleMenu(decor);

        // Run runnable to set captured link to expired
        verify(mMockHandler).postDelayed(runnableArgument.capture(), anyLong());
        runnableArgument.getValue().run();

        // Verify handle menu's browser link is set to captured link since menu was opened before
        // captured link expired
        createHandleMenu(decor);
        verifyHandleMenuCreated(TEST_URI1);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_TO_WEB)
    public void capturedLink_capturedLinkExpiresAfterClick() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final DesktopModeWindowDecoration decor = createWindowDecoration(
                taskInfo, TEST_URI1 /* captured link */, null /* web uri */,
                null /* generic link */);
        final ArgumentCaptor<Function1<Uri, Unit>> openInBrowserCaptor =
                ArgumentCaptor.forClass(Function1.class);

        // Simulate menu opening and clicking open in browser button
        createHandleMenu(decor);
        verify(mMockHandleMenu).show(
                any(),
                any(),
                any(),
                any(),
                any(),
                openInBrowserCaptor.capture(),
                any(),
                any()
        );
        openInBrowserCaptor.getValue().invoke(TEST_URI1);

        // Verify handle menu's browser link not set to captured link since link not valid after
        // open in browser clicked
        createHandleMenu(decor);
        verifyHandleMenuCreated(null /* uri */);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_TO_WEB)
    public void capturedLink_openInBrowserListenerCalledOnClick() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final DesktopModeWindowDecoration decor = createWindowDecoration(
                taskInfo, TEST_URI1 /* captured link */, null /* web uri */,
                null /* generic link */);
        final ArgumentCaptor<Function1<Uri, Unit>> openInBrowserCaptor =
                ArgumentCaptor.forClass(Function1.class);
        createHandleMenu(decor);
        verify(mMockHandleMenu).show(
                any(),
                any(),
                any(),
                any(),
                any(),
                openInBrowserCaptor.capture(),
                any(),
                any()
        );

        openInBrowserCaptor.getValue().invoke(TEST_URI1);

        verify(mMockOpenInBrowserClickListener).accept(TEST_URI1);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_TO_WEB)
    public void webUriLink_webUriLinkUsedWhenCapturedLinkUnavailable() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final DesktopModeWindowDecoration decor = createWindowDecoration(
                taskInfo, null /* captured link */, TEST_URI2 /* web uri */,
                TEST_URI3 /* generic link */);
        // Verify handle menu's browser link set as web uri link when captured link is unavailable
        createHandleMenu(decor);
        verifyHandleMenuCreated(TEST_URI2);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_TO_WEB)
    public void genericLink_genericLinkUsedWhenCapturedLinkAndWebUriUnavailable() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final DesktopModeWindowDecoration decor = createWindowDecoration(
                taskInfo, null /* captured link */, null /* web uri */,
                TEST_URI3 /* generic link */);

        // Verify handle menu's browser link set as generic link when captured link and web uri link
        // are unavailable
        createHandleMenu(decor);
        verifyHandleMenuCreated(TEST_URI3);
    }

    @Test
    public void handleMenu_onCloseMenuClick_closesMenu() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                true /* relayout */);
        final ArgumentCaptor<Function0<Unit>> closeClickListener =
                ArgumentCaptor.forClass(Function0.class);
        createHandleMenu(decoration);
        verify(mMockHandleMenu).show(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                closeClickListener.capture(),
                any()
        );

        closeClickListener.getValue().invoke();

        verify(mMockHandleMenu).close();
        assertFalse(decoration.isHandleMenuActive());
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
    public void notifyCaptionStateChanged_flagDisabled_doNoNotify() {
        when(DesktopModeStatus.canEnterDesktopMode(mContext)).thenReturn(true);
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        when(mMockDisplayController.getInsetsState(taskInfo.displayId))
                .thenReturn(createInsetsState(statusBars(), /* visible= */true));
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        spyWindowDecor.relayout(taskInfo);

        verify(mMockCaptionHandleRepository, never()).notifyCaptionChanged(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
    public void notifyCaptionStateChanged_inFullscreenMode_notifiesAppHandleVisible() {
        when(DesktopModeStatus.canEnterDesktopMode(mContext)).thenReturn(true);
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        when(mMockDisplayController.getInsetsState(taskInfo.displayId))
                .thenReturn(createInsetsState(statusBars(), /* visible= */true));
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        ArgumentCaptor<CaptionState> captionStateArgumentCaptor = ArgumentCaptor.forClass(
                CaptionState.class);

        spyWindowDecor.relayout(taskInfo);

        verify(mMockCaptionHandleRepository, atLeastOnce()).notifyCaptionChanged(
                captionStateArgumentCaptor.capture());
        assertThat(captionStateArgumentCaptor.getValue()).isInstanceOf(
                CaptionState.AppHandle.class);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
    @Ignore("TODO(b/367235906): Due to MONITOR_INPUT permission error")
    public void notifyCaptionStateChanged_inWindowingMode_notifiesAppHeaderVisible() {
        when(DesktopModeStatus.canEnterDesktopMode(mContext)).thenReturn(true);
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        when(mMockDisplayController.getInsetsState(taskInfo.displayId))
                .thenReturn(createInsetsState(statusBars(), /* visible= */true));
        when(mMockAppHeaderViewHolder.getAppChipLocationInWindow()).thenReturn(
                new Rect(/* left= */ 0, /* top= */ 1, /* right= */ 2, /* bottom= */ 3));
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        // Make non-resizable to avoid dealing with input-permissions (MONITOR_INPUT)
        taskInfo.isResizeable = false;
        ArgumentCaptor<Function0<Unit>> runnableArgumentCaptor = ArgumentCaptor.forClass(
                Function0.class);
        ArgumentCaptor<CaptionState> captionStateArgumentCaptor = ArgumentCaptor.forClass(
                CaptionState.class);

        spyWindowDecor.relayout(taskInfo);
        verify(mMockAppHeaderViewHolder, atLeastOnce()).runOnAppChipGlobalLayout(
                runnableArgumentCaptor.capture());
        runnableArgumentCaptor.getValue().invoke();

        verify(mMockCaptionHandleRepository, atLeastOnce()).notifyCaptionChanged(
                captionStateArgumentCaptor.capture());
        assertThat(captionStateArgumentCaptor.getValue()).isInstanceOf(
                CaptionState.AppHeader.class);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
    public void notifyCaptionStateChanged_taskNotVisible_notifiesNoCaptionVisible() {
        when(DesktopModeStatus.canEnterDesktopMode(mContext)).thenReturn(true);
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ false);
        when(mMockDisplayController.getInsetsState(taskInfo.displayId))
                .thenReturn(createInsetsState(statusBars(), /* visible= */true));
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_UNDEFINED);
        ArgumentCaptor<CaptionState> captionStateArgumentCaptor = ArgumentCaptor.forClass(
                CaptionState.class);

        spyWindowDecor.relayout(taskInfo);

        verify(mMockCaptionHandleRepository, atLeastOnce()).notifyCaptionChanged(
                captionStateArgumentCaptor.capture());
        assertThat(captionStateArgumentCaptor.getValue()).isInstanceOf(
                CaptionState.NoCaption.class);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
    public void notifyCaptionStateChanged_captionHandleExpanded_notifiesHandleMenuExpanded() {
        when(DesktopModeStatus.canEnterDesktopMode(mContext)).thenReturn(true);
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        when(mMockDisplayController.getInsetsState(taskInfo.displayId))
                .thenReturn(createInsetsState(statusBars(), /* visible= */true));
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        ArgumentCaptor<CaptionState> captionStateArgumentCaptor = ArgumentCaptor.forClass(
                CaptionState.class);

        spyWindowDecor.relayout(taskInfo);
        createHandleMenu(spyWindowDecor);

        verify(mMockCaptionHandleRepository, atLeastOnce()).notifyCaptionChanged(
                captionStateArgumentCaptor.capture());
        assertThat(captionStateArgumentCaptor.getValue()).isInstanceOf(
                CaptionState.AppHandle.class);
        assertThat(
                ((CaptionState.AppHandle) captionStateArgumentCaptor.getValue())
                        .isHandleMenuExpanded()).isEqualTo(
                true);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
    public void notifyCaptionStateChanged_captionHandleClosed_notifiesHandleMenuClosed() {
        when(DesktopModeStatus.canEnterDesktopMode(mContext)).thenReturn(true);
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        when(mMockDisplayController.getInsetsState(taskInfo.displayId))
                .thenReturn(createInsetsState(statusBars(), /* visible= */true));
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        ArgumentCaptor<CaptionState> captionStateArgumentCaptor = ArgumentCaptor.forClass(
                CaptionState.class);

        spyWindowDecor.relayout(taskInfo);
        createHandleMenu(spyWindowDecor);
        spyWindowDecor.closeHandleMenu();

        verify(mMockCaptionHandleRepository, atLeastOnce()).notifyCaptionChanged(
                captionStateArgumentCaptor.capture());
        assertThat(captionStateArgumentCaptor.getValue()).isInstanceOf(
                CaptionState.AppHandle.class);
        assertThat(
                ((CaptionState.AppHandle) captionStateArgumentCaptor.getValue())
                        .isHandleMenuExpanded()).isEqualTo(
                false);

    }

    private void verifyHandleMenuCreated(@Nullable Uri uri) {
        verify(mMockHandleMenuFactory).create(any(), any(), anyInt(), any(), any(),
                any(), anyBoolean(), anyBoolean(), anyBoolean(), eq(uri), anyInt(),
                anyInt(), anyInt());
    }

    private void createMaximizeMenu(DesktopModeWindowDecoration decoration, MaximizeMenu menu) {
        final Function0<Unit> l = () -> Unit.INSTANCE;
        decoration.setOnMaximizeOrRestoreClickListener(l);
        decoration.setOnLeftSnapClickListener(l);
        decoration.setOnRightSnapClickListener(l);
        decoration.createMaximizeMenu();
        verify(menu).show(any(), any(), any(), mOnMaxMenuHoverChangeListener.capture(), any());
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
            ActivityManager.RunningTaskInfo taskInfo, @Nullable Uri capturedLink,
            @Nullable Uri webUri, @Nullable Uri genericLink) {
        taskInfo.capturedLink = capturedLink;
        taskInfo.capturedLinkTimestamp = System.currentTimeMillis();
        mAssistContent.setWebUri(webUri);
        final String genericLinkString = genericLink == null ? null : genericLink.toString();
        doReturn(genericLinkString).when(mMockGenericLinksParser).getGenericLink(any());
        // Relayout to set captured link
        return createWindowDecoration(taskInfo, new FakeMaximizeMenuFactory(), true /* relayout */);
    }

    private DesktopModeWindowDecoration createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo) {
        return createWindowDecoration(taskInfo, new FakeMaximizeMenuFactory(),
                false /* relayout */);
    }

    private DesktopModeWindowDecoration createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo, boolean relayout) {
        return createWindowDecoration(taskInfo, new FakeMaximizeMenuFactory(), relayout);
    }

    private DesktopModeWindowDecoration createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo,
            MaximizeMenuFactory maximizeMenuFactory) {
        return createWindowDecoration(taskInfo, maximizeMenuFactory, false /* relayout */);
    }

    private DesktopModeWindowDecoration createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo,
            MaximizeMenuFactory maximizeMenuFactory,
            boolean relayout) {
        final DesktopModeWindowDecoration windowDecor = new DesktopModeWindowDecoration(mContext,
                mContext, mMockDisplayController, mMockSplitScreenController,
                mMockShellTaskOrganizer, taskInfo, mMockSurfaceControl, mMockHandler, mBgExecutor,
                mMockChoreographer, mMockSyncQueue, mMockAppHeaderViewHolderFactory,
                mMockRootTaskDisplayAreaOrganizer,
                mMockGenericLinksParser, mMockAssistContentRequester, SurfaceControl.Builder::new,
                mMockTransactionSupplier, WindowContainerTransaction::new, SurfaceControl::new,
                new WindowManagerWrapper(mMockWindowManager), mMockSurfaceControlViewHostFactory,
                mMockWindowDecorViewHostSupplier, maximizeMenuFactory, mMockHandleMenuFactory,
                mMockMultiInstanceHelper, mMockCaptionHandleRepository);
        windowDecor.setCaptionListeners(mMockTouchEventListener, mMockTouchEventListener,
                mMockTouchEventListener, mMockTouchEventListener);
        windowDecor.setExclusionRegionListener(mMockExclusionRegionListener);
        windowDecor.setOpenInBrowserClickListener(mMockOpenInBrowserClickListener);
        windowDecor.mDecorWindowContext = mContext;
        if (relayout) {
            windowDecor.relayout(taskInfo);
        }
        return windowDecor;
    }

    private ActivityManager.RunningTaskInfo createTaskInfo(boolean visible) {
        final ActivityManager.TaskDescription.Builder taskDescriptionBuilder =
                new ActivityManager.TaskDescription.Builder();
        ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setVisible(visible)
                .build();
        taskInfo.realActivity = new ComponentName("com.android.wm.shell.windowdecor",
                "DesktopModeWindowDecorationTests");
        taskInfo.baseActivity = new ComponentName("com.android.wm.shell.windowdecor",
                "DesktopModeWindowDecorationTests");
        return taskInfo;

    }

    private void createHandleMenu(@NonNull DesktopModeWindowDecoration decor) {
        decor.createHandleMenu(false);
        // Call DesktopModeWindowDecoration#onAssistContentReceived because decor waits to receive
        // {@link AssistContent} before creating the menu
        decor.onAssistContentReceived(mAssistContent);
    }

    private static boolean hasNoInputChannelFeature(RelayoutParams params) {
        return (params.mInputFeatures & WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL)
                != 0;
    }

    private InsetsState createInsetsState(@WindowInsets.Type.InsetsType int type, boolean visible) {
        final InsetsState state = new InsetsState();
        final InsetsSource source = new InsetsSource(/* id= */0, type);
        source.setVisible(visible);
        state.addSource(source);
        return state;
    }

    private static class TestTouchEventListener extends GestureDetector.SimpleOnGestureListener
            implements View.OnClickListener, View.OnTouchListener, View.OnLongClickListener,
            View.OnGenericMotionListener, DragDetector.MotionEventHandler {

        @Override
        public void onClick(View v) {}

        @Override
        public boolean onGenericMotion(View v, MotionEvent event) {
            return false;
        }

        @Override
        public boolean onLongClick(View v) {
            return false;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            return false;
        }

        @Override
        public boolean handleMotionEvent(@Nullable View v, MotionEvent ev) {
            return false;
        }
    }

    private static final class FakeMaximizeMenuFactory implements MaximizeMenuFactory {
        private final MaximizeMenu mMaximizeMenu;

        FakeMaximizeMenuFactory() {
            this(mock(MaximizeMenu.class));
        }

        FakeMaximizeMenuFactory(MaximizeMenu menu) {
            mMaximizeMenu = menu;
        }

        @NonNull
        @Override
        public MaximizeMenu create(@NonNull SyncTransactionQueue syncQueue,
                @NonNull RootTaskDisplayAreaOrganizer rootTdaOrganizer,
                @NonNull DisplayController displayController,
                @NonNull ActivityManager.RunningTaskInfo taskInfo,
                @NonNull Context decorWindowContext, @NonNull PointF menuPosition,
                @NonNull Supplier<SurfaceControl.Transaction> transactionSupplier) {
            return mMaximizeMenu;
        }
    }
}
