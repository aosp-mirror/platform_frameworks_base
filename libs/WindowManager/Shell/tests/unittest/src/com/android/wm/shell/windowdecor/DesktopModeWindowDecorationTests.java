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
import static android.view.WindowInsets.Type.captionBar;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.wm.shell.MockSurfaceControlHelper.createMockSurfaceControlTransaction;
import static com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.windowdecor.DesktopModeWindowDecoration.CLOSE_MAXIMIZE_MENU_DELAY_MS;
import static com.android.wm.shell.windowdecor.WindowDecoration.INVALID_CORNER_RADIUS;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
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
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.TypedArray;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
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
import com.android.wm.shell.desktopmode.DesktopModeEventLogger;
import com.android.wm.shell.desktopmode.DesktopRepository;
import com.android.wm.shell.desktopmode.DesktopUserRepositories;
import com.android.wm.shell.desktopmode.WindowDecorCaptionHandleRepository;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.windowdecor.WindowDecoration.RelayoutParams;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHost;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHostSupplier;
import com.android.wm.shell.windowdecor.viewholder.AppHeaderViewHolder;

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

import java.util.List;
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
    private DesktopUserRepositories mMockDesktopUserRepositories;
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
    private WindowDecorViewHostSupplier<WindowDecorViewHost> mMockWindowDecorViewHostSupplier;
    @Mock
    private WindowDecorViewHost mMockWindowDecorViewHost;
    @Mock
    private WindowDecoration.SurfaceControlViewHostFactory mMockSurfaceControlViewHostFactory;
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
    private Consumer<Intent> mMockOpenInBrowserClickListener;
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
    @Mock
    private DesktopModeEventLogger mDesktopModeEventLogger;
    @Mock
    private DesktopRepository mDesktopRepository;
    @Captor
    private ArgumentCaptor<Function1<Boolean, Unit>> mOnMaxMenuHoverChangeListener;
    @Captor
    private ArgumentCaptor<Runnable> mCloseMaxMenuRunnable;

    private final InsetsState mInsetsState = createInsetsState(statusBars(), /* visible= */true);
    private SurfaceControl.Transaction mMockTransaction;
    private StaticMockitoSession mMockitoSession;
    private TestableContext mTestableContext;
    private final ShellExecutor mBgExecutor = new TestShellExecutor();
    private final AssistContent mAssistContent = new AssistContent();
    private final Region mExclusionRegion = Region.obtain();

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
        final ActivityInfo activityInfo = createActivityInfo();
        when(mMockPackageManager.getActivityInfo(any(), anyInt())).thenReturn(activityInfo);
        final ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = activityInfo;
        when(mMockPackageManager.resolveActivity(any(), anyInt())).thenReturn(resolveInfo);
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController).getDisplay(Display.DEFAULT_DISPLAY);
        doReturn(mInsetsState).when(mMockDisplayController).getInsetsState(anyInt());
        when(mMockHandleMenuFactory.create(any(), any(), anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(mMockHandleMenu);
        when(mMockMultiInstanceHelper.supportsMultiInstanceSplit(any())).thenReturn(false);
        when(mMockAppHeaderViewHolderFactory.create(any(), any(), any(), any(), any(), any(), any(),
                any())).thenReturn(mMockAppHeaderViewHolder);
        when(mMockDesktopUserRepositories.getCurrent()).thenReturn(mDesktopRepository);
        when(mMockDesktopUserRepositories.getProfile(anyInt())).thenReturn(mDesktopRepository);
        when(mMockWindowDecorViewHostSupplier.acquire(any(), eq(defaultDisplay)))
                .thenReturn(mMockWindowDecorViewHost);
        when(mMockWindowDecorViewHost.getSurfaceControl()).thenReturn(mock(SurfaceControl.class));
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

        spyWindowDecor.relayout(taskInfo, false /* hasGlobalFocus */, mExclusionRegion);

        // Menus should close if open before the task being invisible causes relayout to return.
        verify(spyWindowDecor).closeHandleMenu();
        verify(spyWindowDecor).closeMaximizeMenu();

    }

    @Test
    public void updateRelayoutParams_noSysPropFlagsSet_windowShadowsAreSetForFreeform() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams, mContext, taskInfo, mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mShadowRadius)
                .isNotEqualTo(WindowDecoration.INVALID_SHADOW_RADIUS);
    }

    @Test
    public void updateRelayoutParams_noSysPropFlagsSet_windowShadowsAreNotSetForFullscreen() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams, mContext, taskInfo, mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mShadowRadius).isEqualTo(WindowDecoration.INVALID_SHADOW_RADIUS);
    }

    @Test
    public void updateRelayoutParams_noSysPropFlagsSet_windowShadowsAreNotSetForSplit() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams, mContext, taskInfo, mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mShadowRadius).isEqualTo(WindowDecoration.INVALID_SHADOW_RADIUS);
    }

    @Test
    public void updateRelayoutParams_noSysPropFlagsSet_roundedCornersSetForFreeform() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        fillRoundedCornersResources(/* fillValue= */ 30);
        RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mCornerRadius).isGreaterThan(0);
    }

    @Test
    public void updateRelayoutParams_noSysPropFlagsSet_roundedCornersNotSetForFullscreen() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        fillRoundedCornersResources(/* fillValue= */ 30);
        RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mCornerRadius).isEqualTo(INVALID_CORNER_RADIUS);
    }

    @Test
    public void updateRelayoutParams_noSysPropFlagsSet_roundedCornersNotSetForSplit() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        fillRoundedCornersResources(/* fillValue= */ 30);
        RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mCornerRadius).isEqualTo(INVALID_CORNER_RADIUS);
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
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

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
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mWindowDecorConfig.densityDpi).isEqualTo(systemDensity);
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_ACCESSIBLE_CUSTOM_HEADERS)
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
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.hasInputFeatureSpy()).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_ACCESSIBLE_CUSTOM_HEADERS)
    public void updateRelayoutParams_freeformAndTransparentAppearance_limitedTouchRegion() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskInfo.taskDescription.setTopOpaqueSystemBarsAppearance(
                APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mLimitTouchRegionToSystemAreas).isTrue();
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_ACCESSIBLE_CUSTOM_HEADERS)
    public void updateRelayoutParams_freeformButOpaqueAppearance_disallowsInputFallthrough() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskInfo.taskDescription.setTopOpaqueSystemBarsAppearance(0);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.hasInputFeatureSpy()).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_ACCESSIBLE_CUSTOM_HEADERS)
    public void updateRelayoutParams_freeformButOpaqueAppearance_unlimitedTouchRegion() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskInfo.taskDescription.setTopOpaqueSystemBarsAppearance(0);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mLimitTouchRegionToSystemAreas).isFalse();
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_ACCESSIBLE_CUSTOM_HEADERS)
    public void updateRelayoutParams_fullscreen_disallowsInputFallthrough() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.hasInputFeatureSpy()).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_ACCESSIBLE_CUSTOM_HEADERS)
    public void updateRelayoutParams_fullscreen_unlimitedTouchRegion() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mLimitTouchRegionToSystemAreas).isFalse();
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
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

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
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

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
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

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
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

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
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

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
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

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
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(
                (relayoutParams.mInsetSourceFlags & FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR) == 0)
                .isTrue();
    }

    @Test
    public void updateRelayoutParams_handle_bottomSplitIsInsetSource() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        final RelayoutParams relayoutParams = new RelayoutParams();
        when(mMockSplitScreenController.isLeftRightSplit()).thenReturn(false);
        when(mMockSplitScreenController.getSplitPosition(taskInfo.taskId))
                .thenReturn(SPLIT_POSITION_BOTTOM_OR_RIGHT);

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ true,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mIsInsetSource).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void updateRelayoutParams_header_addsPaddingInFullImmersive() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskInfo.configuration.windowConfiguration.setBounds(new Rect(0, 0, 1000, 2000));
        final InsetsState insetsState = createInsetsState(List.of(
                createInsetsSource(
                        0 /* id */, statusBars(), true /* visible */, new Rect(0, 0, 1000, 50)),
                createInsetsSource(
                        1 /* id */, captionBar(), true /* visible */, new Rect(0, 0, 1000, 100))));
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ true,
                insetsState,
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        // Takes status bar inset as padding, ignores caption bar inset.
        assertThat(relayoutParams.mCaptionTopPadding).isEqualTo(50);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void updateRelayoutParams_header_notAnInsetsSourceInFullyImmersive() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ true,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mIsInsetSource).isFalse();
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void updateRelayoutParams_header_statusBarInvisible_captionVisible() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ false,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        // Header is always shown because it's assumed the status bar is always visible.
        assertThat(relayoutParams.mIsCaptionVisible).isTrue();
    }

    @Test
    public void updateRelayoutParams_handle_statusBarVisibleAndNotOverKeyguard_captionVisible() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mIsCaptionVisible).isTrue();
    }

    @Test
    public void updateRelayoutParams_handle_statusBarInvisible_captionNotVisible() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ false,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mIsCaptionVisible).isFalse();
    }

    @Test
    public void updateRelayoutParams_handle_overKeyguard_captionNotVisible() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ true,
                /* inFullImmersiveMode */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mIsCaptionVisible).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void updateRelayoutParams_header_fullyImmersive_captionVisFollowsStatusBar() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ true,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mIsCaptionVisible).isTrue();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ false,
                /* isKeyguardVisibleAndOccluded */ false,
                /* inFullImmersiveMode */ true,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mIsCaptionVisible).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void updateRelayoutParams_header_fullyImmersive_overKeyguard_captionNotVisible() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        final RelayoutParams relayoutParams = new RelayoutParams();

        DesktopModeWindowDecoration.updateRelayoutParams(
                relayoutParams,
                mTestableContext,
                taskInfo,
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop */ false,
                /* isStatusBarVisible */ true,
                /* isKeyguardVisibleAndOccluded */ true,
                /* inFullImmersiveMode */ true,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        assertThat(relayoutParams.mIsCaptionVisible).isFalse();
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
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop= */ false,
                /* isStatusBarVisible= */ true,
                /* isKeyguardVisibleAndOccluded= */ false,
                /* inFullImmersiveMode= */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

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
                mMockSplitScreenController,
                /* applyStartTransactionOnDraw= */ true,
                /* shouldSetTaskPositionAndCrop= */ false,
                /* isStatusBarVisible= */ true,
                /* isKeyguardVisibleAndOccluded= */ false,
                /* inFullImmersiveMode= */ false,
                new InsetsState(),
                /* hasGlobalFocus= */ true,
                mExclusionRegion);

        // App Headers must be rendered in sync with the task animation, so it cannot be delayed.
        assertThat(relayoutParams.mAsyncViewHost).isFalse();
    }

    @Test
    public void relayout_fullscreenTask_appliesTransactionImmediately() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        spyWindowDecor.relayout(taskInfo, true /* hasGlobalFocus */, mExclusionRegion);

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

        spyWindowDecor.relayout(taskInfo, true /* hasGlobalFocus */, mExclusionRegion);

        verify(mMockTransaction, never()).apply();
        verify(mMockRootSurfaceControl).applyTransactionOnDraw(mMockTransaction);
    }

    @Test
    public void createMaximizeMenu_showsMenu() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final MaximizeMenu menu = mock(MaximizeMenu.class);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                new FakeMaximizeMenuFactory(menu));
        assertFalse(decoration.isMaximizeMenuActive());

        createMaximizeMenu(decoration);

        verify(menu).show(anyBoolean(), anyInt(), anyBoolean(), anyBoolean(), any(), any(), any(),
                any(), mOnMaxMenuHoverChangeListener.capture(), any());
        assertTrue(decoration.isMaximizeMenuActive());
    }

    @Test
    public void maximizeMenu_unHoversMenu_schedulesCloseMenu() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final MaximizeMenu menu = mock(MaximizeMenu.class);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                new FakeMaximizeMenuFactory(menu));
        decoration.setAppHeaderMaximizeButtonHovered(false);
        createMaximizeMenu(decoration);
        verify(menu).show(anyBoolean(), anyInt(), anyBoolean(), anyBoolean(), any(), any(), any(),
                any(), mOnMaxMenuHoverChangeListener.capture(), any());

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
        createMaximizeMenu(decoration);

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
        createMaximizeMenu(decoration);
        verify(menu).show(anyBoolean(), anyInt(), anyBoolean(), anyBoolean(), any(), any(), any(),
                any(), mOnMaxMenuHoverChangeListener.capture(), any());

        mOnMaxMenuHoverChangeListener.getValue().invoke(true);

        verify(mMockHandler).removeCallbacks(any());
    }

    @Test
    public void maximizeMenu_hoversButton_cancelsCloseMenu() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final MaximizeMenu menu = mock(MaximizeMenu.class);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                new FakeMaximizeMenuFactory(menu));
        createMaximizeMenu(decoration);
        verify(menu).show(anyBoolean(), anyInt(), anyBoolean(), anyBoolean(), any(), any(), any(),
                any(), mOnMaxMenuHoverChangeListener.capture(), any());

        decoration.setAppHeaderMaximizeButtonHovered(true);

        verify(mMockHandler).removeCallbacks(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void createMaximizeMenu_taskRequestsImmersive_showsImmersiveOption() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        taskInfo.requestedVisibleTypes = ~statusBars();
        final MaximizeMenu menu = mock(MaximizeMenu.class);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                new FakeMaximizeMenuFactory(menu));

        createMaximizeMenu(decoration);

        verify(menu).show(
                anyBoolean(),
                anyInt(),
                /* showImmersiveOption= */ eq(true),
                anyBoolean(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void createMaximizeMenu_taskDoesNotRequestImmersive_hiddenImmersiveOption() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        taskInfo.requestedVisibleTypes = statusBars();
        final MaximizeMenu menu = mock(MaximizeMenu.class);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                new FakeMaximizeMenuFactory(menu));

        createMaximizeMenu(decoration);

        verify(menu).show(
                anyBoolean(),
                anyInt(),
                /* showImmersiveOption= */ eq(false),
                anyBoolean(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void createMaximizeMenu_taskResizable_showsSnapOptions() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        taskInfo.isResizeable = true;
        final MaximizeMenu menu = mock(MaximizeMenu.class);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                new FakeMaximizeMenuFactory(menu));

        createMaximizeMenu(decoration);

        verify(menu).show(
                anyBoolean(),
                anyInt(),
                anyBoolean(),
                /* showSnapOptions= */ eq(true),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void createMaximizeMenu_taskUnresizable_hiddenSnapOptions() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        taskInfo.isResizeable = false;
        final MaximizeMenu menu = mock(MaximizeMenu.class);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                new FakeMaximizeMenuFactory(menu));

        createMaximizeMenu(decoration);

        verify(menu).show(
                anyBoolean(),
                anyInt(),
                anyBoolean(),
                /* showSnapOptions= */ eq(false),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
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
    public void capturedLink_capturedLinkNotResetToSameLink() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final DesktopModeWindowDecoration decor = createWindowDecoration(
                taskInfo, TEST_URI1 /* captured link */, null /* web uri */,
                null /* generic link */);
        final ArgumentCaptor<Function1<Intent, Unit>> openInBrowserCaptor =
                ArgumentCaptor.forClass(Function1.class);

        createHandleMenu(decor);
        verify(mMockHandleMenu).show(any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                openInBrowserCaptor.capture(),
                any(),
                any(),
                any(),
                anyBoolean()
        );
        // Run runnable to set captured link to used
        openInBrowserCaptor.getValue().invoke(new Intent(Intent.ACTION_MAIN, TEST_URI1));

        // Relayout decor with same captured link
        decor.relayout(taskInfo, true /* hasGlobalFocus */, mExclusionRegion);

        // Verify handle menu's browser link not set to captured link since link is already used
        createHandleMenu(decor);
        verifyHandleMenuCreated(null /* uri */);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_TO_WEB)
    public void capturedLink_capturedLinkSetToUsedAfterClick() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final DesktopModeWindowDecoration decor = createWindowDecoration(
                taskInfo, TEST_URI1 /* captured link */, null /* web uri */,
                null /* generic link */);
        final ArgumentCaptor<Function1<Intent, Unit>> openInBrowserCaptor =
                ArgumentCaptor.forClass(Function1.class);

        // Simulate menu opening and clicking open in browser button
        createHandleMenu(decor);
        verify(mMockHandleMenu).show(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                openInBrowserCaptor.capture(),
                any(),
                any(),
                any(),
                anyBoolean()
        );
        openInBrowserCaptor.getValue().invoke(new Intent(Intent.ACTION_MAIN, TEST_URI1));

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
        final ArgumentCaptor<Function1<Intent, Unit>> openInBrowserCaptor =
                ArgumentCaptor.forClass(Function1.class);
        createHandleMenu(decor);
        verify(mMockHandleMenu).show(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                openInBrowserCaptor.capture(),
                any(),
                any(),
                any(),
                anyBoolean()
        );

        openInBrowserCaptor.getValue().invoke(new Intent(Intent.ACTION_MAIN, TEST_URI1));

        verify(mMockOpenInBrowserClickListener).accept(
                argThat(intent -> intent.getData() == TEST_URI1));
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
                any(),
                any(),
                closeClickListener.capture(),
                any(),
                anyBoolean()
        );

        closeClickListener.getValue().invoke();

        verify(mMockHandleMenu).close();
        assertFalse(decoration.isHandleMenuActive());
    }

    @Test
    public void createHandleMenu_immersiveWindow_forceShowsSystemBars() {
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        final DesktopModeWindowDecoration decoration = createWindowDecoration(taskInfo,
                true /* relayout */);
        when(mMockDesktopUserRepositories.getCurrent()
                .isTaskInFullImmersiveState(taskInfo.taskId)).thenReturn(true);

        createHandleMenu(decoration);

        verify(mMockHandleMenu).show(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                /* forceShowSystemBars= */ eq(true)
        );
    }

    @Test
    @DisableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION,
            Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_TO_WEB_EDUCATION_INTEGRATION})
    public void notifyCaptionStateChanged_flagDisabled_doNoNotify() {
        when(DesktopModeStatus.canEnterDesktopMode(mContext)).thenReturn(true);
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        spyWindowDecor.relayout(taskInfo, true /* hasGlobalFocus */, mExclusionRegion);

        verify(mMockCaptionHandleRepository, never()).notifyCaptionChanged(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
    public void notifyCaptionStateChanged_inFullscreenMode_notifiesAppHandleVisible() {
        when(DesktopModeStatus.canEnterDesktopMode(mContext)).thenReturn(true);
        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(/* visible= */ true);
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        ArgumentCaptor<CaptionState> captionStateArgumentCaptor = ArgumentCaptor.forClass(
                CaptionState.class);

        spyWindowDecor.relayout(taskInfo, true /* hasGlobalFocus */, mExclusionRegion);

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

        spyWindowDecor.relayout(taskInfo, true /* hasGlobalFocus */, mExclusionRegion);
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
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_UNDEFINED);
        ArgumentCaptor<CaptionState> captionStateArgumentCaptor = ArgumentCaptor.forClass(
                CaptionState.class);

        spyWindowDecor.relayout(taskInfo, false /* hasGlobalFocus */, mExclusionRegion);

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
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        ArgumentCaptor<CaptionState> captionStateArgumentCaptor = ArgumentCaptor.forClass(
                CaptionState.class);

        spyWindowDecor.relayout(taskInfo, true /* hasGlobalFocus */, mExclusionRegion);
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
        final DesktopModeWindowDecoration spyWindowDecor = spy(createWindowDecoration(taskInfo));
        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        ArgumentCaptor<CaptionState> captionStateArgumentCaptor = ArgumentCaptor.forClass(
                CaptionState.class);

        spyWindowDecor.relayout(taskInfo, true /* hasGlobalFocus */, mExclusionRegion);
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

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_TO_WEB)
    public void browserApp_webUriUsedForBrowserApp() {
        // Make {@link AppToWebUtils#isBrowserApp} return true
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.handleAllWebDataURI = true;
        resolveInfo.activityInfo = createActivityInfo();
        when(mMockPackageManager.queryIntentActivitiesAsUser(any(), anyInt(), anyInt()))
                .thenReturn(List.of(resolveInfo));

        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(true /* visible */);
        final DesktopModeWindowDecoration decor = createWindowDecoration(
                taskInfo, TEST_URI1 /* captured link */, TEST_URI2 /* web uri */,
                TEST_URI3 /* generic link */);

        // Verify web uri used for browser applications
        createHandleMenu(decor);
        verifyHandleMenuCreated(TEST_URI2);
    }


    private void verifyHandleMenuCreated(@Nullable Uri uri) {
        verify(mMockHandleMenuFactory).create(any(), any(), anyInt(), any(), any(),
                any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyBoolean(), argThat(intent ->
                        (uri == null && intent == null) || intent.getData().equals(uri)),
                anyInt(), anyInt(), anyInt(), anyInt());
    }

    private void createMaximizeMenu(DesktopModeWindowDecoration decoration) {
        final Function0<Unit> l = () -> Unit.INSTANCE;
        decoration.setOnMaximizeOrRestoreClickListener(l);
        decoration.setOnImmersiveOrRestoreClickListener(l);
        decoration.setOnLeftSnapClickListener(l);
        decoration.setOnRightSnapClickListener(l);
        decoration.createMaximizeMenu();
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
                mMockDesktopUserRepositories, mMockShellTaskOrganizer, taskInfo,
                mMockSurfaceControl, mMockHandler, mBgExecutor, mMockChoreographer, mMockSyncQueue,
                mMockAppHeaderViewHolderFactory, mMockRootTaskDisplayAreaOrganizer,
                mMockGenericLinksParser, mMockAssistContentRequester, SurfaceControl.Builder::new,
                mMockTransactionSupplier, WindowContainerTransaction::new, SurfaceControl::new,
                new WindowManagerWrapper(mMockWindowManager), mMockSurfaceControlViewHostFactory,
                mMockWindowDecorViewHostSupplier, maximizeMenuFactory, mMockHandleMenuFactory,
                mMockMultiInstanceHelper, mMockCaptionHandleRepository, mDesktopModeEventLogger);
        windowDecor.setCaptionListeners(mMockTouchEventListener, mMockTouchEventListener,
                mMockTouchEventListener, mMockTouchEventListener);
        windowDecor.setExclusionRegionListener(mMockExclusionRegionListener);
        windowDecor.setOpenInBrowserClickListener(mMockOpenInBrowserClickListener);
        windowDecor.mDecorWindowContext = mContext;
        if (relayout) {
            windowDecor.relayout(taskInfo, true /* hasGlobalFocus */, mExclusionRegion);
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

    private static ActivityInfo createActivityInfo() {
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = "DesktopModeWindowDecorationTestPackage";
        final ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.applicationInfo = applicationInfo;
        activityInfo.name = "DesktopModeWindowDecorationTest";
        return activityInfo;
    }

    private static boolean hasNoInputChannelFeature(RelayoutParams params) {
        return (params.mInputFeatures & WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL)
                != 0;
    }

    private InsetsSource createInsetsSource(int id, @WindowInsets.Type.InsetsType int type,
            boolean visible, @NonNull Rect frame) {
        final InsetsSource source = new InsetsSource(id, type);
        source.setVisible(visible);
        source.setFrame(frame);
        return source;
    }

    private InsetsState createInsetsState(@NonNull List<InsetsSource> sources) {
        final InsetsState state = new InsetsState();
        sources.forEach(state::addSource);
        return state;
    }

    private InsetsState createInsetsState(@WindowInsets.Type.InsetsType int type, boolean visible) {
        final InsetsSource source = createInsetsSource(0 /* id */, type, visible, new Rect());
        return createInsetsState(List.of(source));
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
