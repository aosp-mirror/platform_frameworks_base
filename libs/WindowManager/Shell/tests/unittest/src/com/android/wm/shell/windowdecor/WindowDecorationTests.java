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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT;
import static android.view.InsetsSource.FLAG_FORCE_CONSUMING;
import static android.view.InsetsSource.FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR;
import static android.view.WindowInsets.Type.captionBar;
import static android.view.WindowInsets.Type.mandatorySystemGestures;
import static android.view.WindowInsets.Type.statusBars;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.wm.shell.MockSurfaceControlHelper.createMockSurfaceControlBuilder;
import static com.android.wm.shell.MockSurfaceControlHelper.createMockSurfaceControlTransaction;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.quality.Strictness.LENIENT;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.AndroidTestingRunner;
import android.util.DisplayMetrics;
import android.view.AttachedSurfaceControl;
import android.view.Display;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager.LayoutParams;
import android.window.SurfaceSyncGroup;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.desktopmode.DesktopModeEventLogger;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;
import com.android.wm.shell.tests.R;
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewContainer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
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
    private static final Rect TASK_BOUNDS = new Rect(100, 300, 400, 400);
    private static final Point TASK_POSITION_IN_PARENT = new Point(40, 60);
    private static final int CORNER_RADIUS = 20;
    private static final int STATUS_BAR_INSET_SOURCE_ID = 0;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(DEVICE_DEFAULT);

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
    private AttachedSurfaceControl mMockRootSurfaceControl;
    @Mock
    private TestView mMockView;
    @Mock
    private WindowContainerTransaction mMockWindowContainerTransaction;
    @Mock
    private SurfaceSyncGroup mMockSurfaceSyncGroup;
    @Mock
    private SurfaceControl mMockTaskSurface;
    @Mock
    private DesktopModeEventLogger mDesktopModeEventLogger;

    private final List<SurfaceControl.Transaction> mMockSurfaceControlTransactions =
            new ArrayList<>();
    private final List<SurfaceControl.Builder> mMockSurfaceControlBuilders = new ArrayList<>();
    private final InsetsState mInsetsState = new InsetsState();
    private SurfaceControl.Transaction mMockSurfaceControlStartT;
    private SurfaceControl.Transaction mMockSurfaceControlFinishT;
    private SurfaceControl.Transaction mMockSurfaceControlAddWindowT;
    private WindowDecoration.RelayoutParams mRelayoutParams = new WindowDecoration.RelayoutParams();
    private int mCaptionMenuWidthId;

    @Before
    public void setUp() {
        mMockSurfaceControlStartT = createMockSurfaceControlTransaction();
        mMockSurfaceControlFinishT = createMockSurfaceControlTransaction();
        mMockSurfaceControlAddWindowT = createMockSurfaceControlTransaction();

        mRelayoutParams.mLayoutResId = 0;
        mRelayoutParams.mCaptionHeightId = R.dimen.test_freeform_decor_caption_height;
        mCaptionMenuWidthId = R.dimen.test_freeform_decor_caption_menu_width;
        mRelayoutParams.mShadowRadiusId = R.dimen.test_window_decor_shadow_radius;
        mRelayoutParams.mCornerRadius = CORNER_RADIUS;

        when(mMockDisplayController.getDisplay(Display.DEFAULT_DISPLAY))
                .thenReturn(mock(Display.class));
        doReturn(mMockSurfaceControlViewHost).when(mMockSurfaceControlViewHostFactory)
                .create(any(), any(), any());
        when(mMockSurfaceControlViewHost.getRootSurfaceControl())
                .thenReturn(mMockRootSurfaceControl);
        when(mMockView.findViewById(anyInt())).thenReturn(mMockView);

        // Add status bar inset so that WindowDecoration does not think task is in immersive mode
        mInsetsState.getOrCreateSource(STATUS_BAR_INSET_SOURCE_ID, statusBars()).setVisible(true);
        doReturn(mInsetsState).when(mMockDisplayController).getInsetsState(anyInt());
    }

    @Test
    public void testLayoutResultCalculation_invisibleTask() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final SurfaceControl decorContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder decorContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(decorContainerSurface);
        mMockSurfaceControlBuilders.add(decorContainerSurfaceBuilder);
        final SurfaceControl taskBackgroundSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder taskBackgroundSurfaceBuilder =
                createMockSurfaceControlBuilder(taskBackgroundSurface);
        mMockSurfaceControlBuilders.add(taskBackgroundSurfaceBuilder);
        final SurfaceControl captionContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder captionContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(captionContainerSurface);
        mMockSurfaceControlBuilders.add(captionContainerSurfaceBuilder);

        final ActivityManager.TaskDescription.Builder taskDescriptionBuilder =
                new ActivityManager.TaskDescription.Builder()
                        .setBackgroundColor(Color.YELLOW);
        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setBounds(TASK_BOUNDS)
                .setPositionInParent(TASK_POSITION_IN_PARENT.x, TASK_POSITION_IN_PARENT.y)
                .setVisible(false)
                .build();
        // Density is 2. Shadow radius is 10px. Caption height is 64px.
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;

        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);

        windowDecor.relayout(taskInfo, false /* hasGlobalFocus */);

        verify(decorContainerSurfaceBuilder, never()).build();
        verify(taskBackgroundSurfaceBuilder, never()).build();
        verify(captionContainerSurfaceBuilder, never()).build();
        verify(mMockSurfaceControlViewHostFactory, never()).create(any(), any(), any());

        assertNull(mRelayoutResult.mRootView);
    }

    @Test
    public void testLayoutResultCalculation_visibleFocusedTask() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final SurfaceControl decorContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder decorContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(decorContainerSurface);
        mMockSurfaceControlBuilders.add(decorContainerSurfaceBuilder);
        final SurfaceControl captionContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder captionContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(captionContainerSurface);
        mMockSurfaceControlBuilders.add(captionContainerSurfaceBuilder);

        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setBounds(TASK_BOUNDS)
                .setPositionInParent(TASK_POSITION_IN_PARENT.x, TASK_POSITION_IN_PARENT.y)
                .setVisible(true)
                .setWindowingMode(WINDOWING_MODE_FREEFORM)
                .build();
        // Density is 2. Shadow radius is 10px. Caption height is 64px.
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);
        mRelayoutParams.mIsCaptionVisible = true;

        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        verify(decorContainerSurfaceBuilder).setParent(mMockTaskSurface);
        verify(decorContainerSurfaceBuilder).setContainerLayer();
        verify(mMockSurfaceControlStartT).setTrustedOverlay(decorContainerSurface, true);
        verify(mMockSurfaceControlStartT).setWindowCrop(decorContainerSurface, 300, 100);

        verify(captionContainerSurfaceBuilder).setParent(decorContainerSurface);
        verify(captionContainerSurfaceBuilder).setContainerLayer();
        verify(mMockSurfaceControlStartT).setWindowCrop(captionContainerSurface, 300, 64);
        verify(mMockSurfaceControlStartT).show(captionContainerSurface);

        verify(mMockSurfaceControlViewHostFactory).create(any(), eq(defaultDisplay), any());

        verify(mMockSurfaceControlViewHost)
                .setView(same(mMockView),
                        argThat(lp -> lp.height == 64
                                && lp.width == 300
                                && (lp.flags & LayoutParams.FLAG_NOT_FOCUSABLE) != 0));
        verify(mMockView).setTaskFocusState(true);
        verify(mMockWindowContainerTransaction).addInsetsSource(
                eq(taskInfo.token),
                any(),
                eq(0 /* index */),
                eq(WindowInsets.Type.captionBar()),
                eq(new Rect(100, 300, 400, 364)),
                any(),
                anyInt());

        verify(mMockSurfaceControlStartT).setCornerRadius(mMockTaskSurface, CORNER_RADIUS);
        verify(mMockSurfaceControlFinishT).setCornerRadius(mMockTaskSurface, CORNER_RADIUS);
        verify(mMockSurfaceControlStartT).setShadowRadius(mMockTaskSurface, 10);

        assertEquals(300, mRelayoutResult.mWidth);
        assertEquals(100, mRelayoutResult.mHeight);
    }

    @Test
    public void testLayoutResultCalculation_visibleFocusedTaskToInvisible() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final SurfaceControl decorContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder decorContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(decorContainerSurface);
        mMockSurfaceControlBuilders.add(decorContainerSurfaceBuilder);
        final SurfaceControl captionContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder captionContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(captionContainerSurface);
        mMockSurfaceControlBuilders.add(captionContainerSurfaceBuilder);

        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mMockSurfaceControlTransactions.add(t);

        final ActivityManager.TaskDescription.Builder taskDescriptionBuilder =
                new ActivityManager.TaskDescription.Builder()
                        .setBackgroundColor(Color.YELLOW);
        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setBounds(TASK_BOUNDS)
                .setPositionInParent(TASK_POSITION_IN_PARENT.x, TASK_POSITION_IN_PARENT.y)
                .setVisible(true)
                .build();
        // Density is 2. Shadow radius is 10px. Caption height is 64px.
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;

        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);
        mRelayoutParams.mIsCaptionVisible = true;

        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        verify(mMockSurfaceControlViewHost, never()).release();
        verify(t, never()).apply();
        verify(mMockWindowContainerTransaction, never())
                .removeInsetsSource(eq(taskInfo.token), any(), anyInt(), anyInt());

        final SurfaceControl.Transaction t2 = mock(SurfaceControl.Transaction.class);
        mMockSurfaceControlTransactions.add(t2);
        taskInfo.isVisible = false;
        windowDecor.relayout(taskInfo, false /* hasGlobalFocus */);

        final InOrder releaseOrder = inOrder(t2, mMockSurfaceControlViewHost);
        releaseOrder.verify(mMockSurfaceControlViewHost).release();
        releaseOrder.verify(t2).remove(captionContainerSurface);
        releaseOrder.verify(t2).remove(decorContainerSurface);
        releaseOrder.verify(t2).apply();
        // Expect to remove two insets sources, the caption insets and the mandatory gesture insets.
        verify(mMockWindowContainerTransaction, Mockito.times(2))
                .removeInsetsSource(eq(taskInfo.token), any(), anyInt(), anyInt());
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

        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);
        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

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
        verify(mMockSurfaceControlViewHostFactory).create(any(), eq(mockDisplay), any());
        verify(mMockSurfaceControlViewHost).setView(same(mMockView), any());
    }

    @Test
    public void testAddViewHostViewContainer() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final SurfaceControl decorContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder decorContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(decorContainerSurface);
        mMockSurfaceControlBuilders.add(decorContainerSurfaceBuilder);
        final SurfaceControl captionContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder captionContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(captionContainerSurface);
        mMockSurfaceControlBuilders.add(captionContainerSurfaceBuilder);

        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mMockSurfaceControlTransactions.add(t);
        final ActivityManager.TaskDescription.Builder taskDescriptionBuilder =
                new ActivityManager.TaskDescription.Builder()
                        .setBackgroundColor(Color.YELLOW);
        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setWindowingMode(WINDOWING_MODE_FREEFORM)
                .setBounds(TASK_BOUNDS)
                .setPositionInParent(TASK_POSITION_IN_PARENT.x, TASK_POSITION_IN_PARENT.y)
                .setVisible(true)
                .build();
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);
        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        final SurfaceControl additionalWindowSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder additionalWindowSurfaceBuilder =
                createMockSurfaceControlBuilder(additionalWindowSurface);
        mMockSurfaceControlBuilders.add(additionalWindowSurfaceBuilder);

        windowDecor.addTestViewContainer();

        verify(additionalWindowSurfaceBuilder).setContainerLayer();
        verify(additionalWindowSurfaceBuilder).setParent(decorContainerSurface);
        verify(additionalWindowSurfaceBuilder).build();
        verify(mMockSurfaceControlAddWindowT).setPosition(additionalWindowSurface, 0, 0);
        final int width = WindowDecoration.loadDimensionPixelSize(
                windowDecor.mDecorWindowContext.getResources(), mCaptionMenuWidthId);
        final int height = WindowDecoration.loadDimensionPixelSize(
                windowDecor.mDecorWindowContext.getResources(), mRelayoutParams.mCaptionHeightId);
        verify(mMockSurfaceControlAddWindowT).setWindowCrop(additionalWindowSurface, width, height);
        verify(mMockSurfaceControlAddWindowT).show(additionalWindowSurface);
        verify(mMockSurfaceControlViewHostFactory, Mockito.times(2))
                .create(any(), eq(defaultDisplay), any());
    }

    @Test
    public void testLayoutResultCalculation_fullWidthCaption() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final SurfaceControl decorContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder decorContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(decorContainerSurface);
        mMockSurfaceControlBuilders.add(decorContainerSurfaceBuilder);
        final SurfaceControl captionContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder captionContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(captionContainerSurface);
        mMockSurfaceControlBuilders.add(captionContainerSurfaceBuilder);

        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mMockSurfaceControlTransactions.add(t);
        final ActivityManager.TaskDescription.Builder taskDescriptionBuilder =
                new ActivityManager.TaskDescription.Builder()
                        .setBackgroundColor(Color.YELLOW);
        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setBounds(TASK_BOUNDS)
                .setPositionInParent(TASK_POSITION_IN_PARENT.x, TASK_POSITION_IN_PARENT.y)
                .setVisible(true)
                .build();
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);

        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        verify(captionContainerSurfaceBuilder).setParent(decorContainerSurface);
        verify(captionContainerSurfaceBuilder).setContainerLayer();
        // Width of the captionContainerSurface should match the width of TASK_BOUNDS
        verify(mMockSurfaceControlStartT).setWindowCrop(captionContainerSurface, 300, 64);
        verify(mMockSurfaceControlStartT).show(captionContainerSurface);
    }

    @Test
    public void testRelayout_applyTransactionInSyncWithDraw() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final SurfaceControl decorContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder decorContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(decorContainerSurface);
        mMockSurfaceControlBuilders.add(decorContainerSurfaceBuilder);
        final SurfaceControl captionContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder captionContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(captionContainerSurface);
        mMockSurfaceControlBuilders.add(captionContainerSurfaceBuilder);

        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mMockSurfaceControlTransactions.add(t);
        final ActivityManager.TaskDescription.Builder taskDescriptionBuilder =
                new ActivityManager.TaskDescription.Builder()
                        .setBackgroundColor(Color.YELLOW);
        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setBounds(TASK_BOUNDS)
                .setPositionInParent(TASK_POSITION_IN_PARENT.x, TASK_POSITION_IN_PARENT.y)
                .setVisible(true)
                .build();
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);

        windowDecor.relayout(taskInfo, true /* applyStartTransactionOnDraw */,
                true /* hasGlobalFocus */);

        verify(mMockRootSurfaceControl).applyTransactionOnDraw(mMockSurfaceControlStartT);
    }

    @Test
    public void testRelayout_withPadding_setsOnResult() {
        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setBounds(TASK_BOUNDS)
                .setPositionInParent(TASK_POSITION_IN_PARENT.x, TASK_POSITION_IN_PARENT.y)
                .setVisible(true)
                .build();
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);
        mRelayoutParams.mCaptionTopPadding = 50;

        windowDecor.relayout(taskInfo, false /* applyStartTransactionOnDraw */,
                true /* hasGlobalFocus */);

        assertEquals(50, mRelayoutResult.mCaptionTopPadding);
    }

    @Test
    public void testRelayout_fluidResizeEnabled_freeformTask_setTaskSurfaceColor() {
        StaticMockitoSession mockitoSession = mockitoSession().mockStatic(
                DesktopModeStatus.class).strictness(
                LENIENT).startMocking();
        when(DesktopModeStatus.isVeiledResizeEnabled()).thenReturn(false);

        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final SurfaceControl decorContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder decorContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(decorContainerSurface);
        mMockSurfaceControlBuilders.add(decorContainerSurfaceBuilder);
        final SurfaceControl captionContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder captionContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(captionContainerSurface);
        mMockSurfaceControlBuilders.add(captionContainerSurfaceBuilder);

        final ActivityManager.TaskDescription.Builder taskDescriptionBuilder =
                new ActivityManager.TaskDescription.Builder()
                        .setBackgroundColor(Color.YELLOW);

        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setVisible(true)
                .setWindowingMode(WINDOWING_MODE_FREEFORM)
                .build();
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);

        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        verify(mMockSurfaceControlStartT).setColor(mMockTaskSurface, new float[]{1.f, 1.f, 0.f});

        mockitoSession.finishMocking();
    }

    @Test
    public void testInsetsAddedWhenCaptionIsVisible() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final ActivityManager.TaskDescription.Builder taskDescriptionBuilder =
                new ActivityManager.TaskDescription.Builder();
        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setVisible(true)
                .build();
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);

        mRelayoutParams.mIsCaptionVisible = true;
        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        verify(mMockWindowContainerTransaction).addInsetsSource(eq(taskInfo.token), any(),
                eq(0) /* index */, eq(captionBar()), any(), any(), anyInt());
        verify(mMockWindowContainerTransaction).addInsetsSource(eq(taskInfo.token), any(),
                eq(0) /* index */, eq(mandatorySystemGestures()), any(), any(), anyInt());
    }

    @Test
    public void testRelayout_fluidResizeEnabled_fullscreenTask_clearTaskSurfaceColor() {
        StaticMockitoSession mockitoSession = mockitoSession().mockStatic(
                DesktopModeStatus.class).strictness(LENIENT).startMocking();
        when(DesktopModeStatus.isVeiledResizeEnabled()).thenReturn(false);

        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final SurfaceControl decorContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder decorContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(decorContainerSurface);
        mMockSurfaceControlBuilders.add(decorContainerSurfaceBuilder);
        final SurfaceControl captionContainerSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder captionContainerSurfaceBuilder =
                createMockSurfaceControlBuilder(captionContainerSurface);
        mMockSurfaceControlBuilders.add(captionContainerSurfaceBuilder);

        final ActivityManager.TaskDescription.Builder taskDescriptionBuilder =
                new ActivityManager.TaskDescription.Builder()
                        .setBackgroundColor(Color.YELLOW);
        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setVisible(true)
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN)
                .build();
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);

        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        verify(mMockSurfaceControlStartT).unsetColor(mMockTaskSurface);

        mockitoSession.finishMocking();
    }

    @Test
    public void testRelayout_captionHidden_neverWasVisible_insetsNotRemoved() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setVisible(true)
                .setBounds(new Rect(0, 0, 1000, 1000))
                .build();
        // Hidden from the beginning, so no insets were ever added.
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);
        mRelayoutParams.mIsCaptionVisible = false;
        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        // Never added.
        verify(mMockWindowContainerTransaction, never()).addInsetsSource(eq(taskInfo.token), any(),
                eq(0) /* index */, eq(captionBar()), any(), any(), anyInt());
        verify(mMockWindowContainerTransaction, never()).addInsetsSource(eq(taskInfo.token), any(),
                eq(0) /* index */, eq(mandatorySystemGestures()), any(), any(), anyInt());
        // No need to remove them if they were never added.
        verify(mMockWindowContainerTransaction, never()).removeInsetsSource(eq(taskInfo.token),
                any(), eq(0) /* index */, eq(captionBar()));
        verify(mMockWindowContainerTransaction, never()).removeInsetsSource(eq(taskInfo.token),
                any(), eq(0) /* index */, eq(mandatorySystemGestures()));
    }

    @Test
    public void testRelayout_notAnInsetsSource_doesNotAddInsets() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setVisible(true)
                .setBounds(new Rect(0, 0, 1000, 1000))
                .build();
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);

        mRelayoutParams.mIsInsetSource = false;
        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        // Never added.
        verify(mMockWindowContainerTransaction, never()).addInsetsSource(eq(taskInfo.token), any(),
                eq(0) /* index */, eq(captionBar()), any(), any(), anyInt());
        verify(mMockWindowContainerTransaction, never()).addInsetsSource(eq(taskInfo.token), any(),
                eq(0) /* index */, eq(mandatorySystemGestures()), any(), any(), anyInt());
    }

    @Test
    public void testRelayout_notAnInsetsSource_hadInsetsBefore_removesInsets() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setVisible(true)
                .setBounds(new Rect(0, 0, 1000, 1000))
                .build();
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);

        mRelayoutParams.mIsCaptionVisible = true;
        mRelayoutParams.mIsInsetSource = true;
        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        mRelayoutParams.mIsCaptionVisible = true;
        mRelayoutParams.mIsInsetSource = false;
        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        // Insets should be removed.
        verify(mMockWindowContainerTransaction).removeInsetsSource(eq(taskInfo.token), any(),
                eq(0) /* index */, eq(captionBar()));
        verify(mMockWindowContainerTransaction).removeInsetsSource(eq(taskInfo.token), any(),
                eq(0) /* index */, eq(mandatorySystemGestures()));
    }

    @Test
    public void testClose_withExistingInsets_insetsRemoved() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setVisible(true)
                .setBounds(new Rect(0, 0, 1000, 1000))
                .build();
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);

        // Relayout will add insets.
        mRelayoutParams.mIsCaptionVisible = true;
        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);
        verify(mMockWindowContainerTransaction).addInsetsSource(eq(taskInfo.token), any(),
                eq(0) /* index */, eq(captionBar()), any(), any(), anyInt());
        verify(mMockWindowContainerTransaction).addInsetsSource(eq(taskInfo.token), any(),
                eq(0) /* index */, eq(mandatorySystemGestures()), any(), any(), anyInt());

        windowDecor.close();

        // Insets should be removed.
        verify(mMockWindowContainerTransaction).removeInsetsSource(eq(taskInfo.token), any(),
                eq(0) /* index */, eq(captionBar()));
        verify(mMockWindowContainerTransaction).removeInsetsSource(eq(taskInfo.token), any(),
                eq(0) /* index */, eq(mandatorySystemGestures()));
    }

    @Test
    public void testClose_withoutExistingInsets_insetsNotRemoved() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setVisible(true)
                .setBounds(new Rect(0, 0, 1000, 1000))
                .build();
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);

        windowDecor.close();

        // No need to remove insets.
        verify(mMockWindowContainerTransaction, never()).removeInsetsSource(eq(taskInfo.token),
                any(), eq(0) /* index */, eq(captionBar()));
        verify(mMockWindowContainerTransaction, never()).removeInsetsSource(eq(taskInfo.token),
                any(), eq(0) /* index */, eq(mandatorySystemGestures()));
    }

    @Test
    public void testRelayout_captionFrameChanged_insetsReapplied() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);
        mInsetsState.getOrCreateSource(STATUS_BAR_INSET_SOURCE_ID, captionBar()).setVisible(true);
        final WindowContainerToken token = TestRunningTaskInfoBuilder.createMockWCToken();
        final TestRunningTaskInfoBuilder builder = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setVisible(true);
        mRelayoutParams.mIsCaptionVisible = true;

        // Relayout twice with different bounds.
        final ActivityManager.RunningTaskInfo firstTaskInfo =
                builder.setToken(token).setBounds(new Rect(0, 0, 1000, 1000)).build();
        final TestWindowDecoration windowDecor = createWindowDecoration(firstTaskInfo);
        windowDecor.relayout(firstTaskInfo, true /* hasGlobalFocus */);
        final ActivityManager.RunningTaskInfo secondTaskInfo =
                builder.setToken(token).setBounds(new Rect(50, 50, 1000, 1000)).build();
        windowDecor.relayout(secondTaskInfo, true /* hasGlobalFocus */);

        // Insets should be applied twice.
        verify(mMockWindowContainerTransaction, times(2)).addInsetsSource(eq(token), any(),
                eq(0) /* index */, eq(captionBar()), any(), any(), anyInt());
        verify(mMockWindowContainerTransaction, times(2)).addInsetsSource(eq(token), any(),
                eq(0) /* index */, eq(mandatorySystemGestures()), any(), any(), anyInt());
    }

    @Test
    public void testRelayout_captionFrameUnchanged_insetsNotApplied() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);
        mInsetsState.getOrCreateSource(STATUS_BAR_INSET_SOURCE_ID, captionBar()).setVisible(true);
        final WindowContainerToken token = TestRunningTaskInfoBuilder.createMockWCToken();
        final TestRunningTaskInfoBuilder builder = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setVisible(true);
        mRelayoutParams.mIsCaptionVisible = true;

        // Relayout twice with the same bounds.
        final ActivityManager.RunningTaskInfo firstTaskInfo =
                builder.setToken(token).setBounds(new Rect(0, 0, 1000, 1000)).build();
        final TestWindowDecoration windowDecor = createWindowDecoration(firstTaskInfo);
        windowDecor.relayout(firstTaskInfo, true /* hasGlobalFocus */);
        final ActivityManager.RunningTaskInfo secondTaskInfo =
                builder.setToken(token).setBounds(new Rect(0, 0, 1000, 1000)).build();
        windowDecor.relayout(secondTaskInfo, true /* hasGlobalFocus */);

        // Insets should only need to be applied once.
        verify(mMockWindowContainerTransaction, times(1)).addInsetsSource(eq(token), any(),
                eq(0) /* index */, eq(captionBar()), any(), any(), anyInt());
        verify(mMockWindowContainerTransaction, times(1)).addInsetsSource(eq(token), any(),
                eq(0) /* index */, eq(mandatorySystemGestures()), any(), any(), anyInt());
    }

    @Test
    public void testRelayout_captionInsetSourceFlags() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);
        final WindowContainerToken token = TestRunningTaskInfoBuilder.createMockWCToken();
        final TestRunningTaskInfoBuilder builder = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setVisible(true);

        final ActivityManager.RunningTaskInfo taskInfo =
                builder.setToken(token).setBounds(new Rect(0, 0, 1000, 1000)).build();
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);
        mRelayoutParams.mIsCaptionVisible = true;
        mRelayoutParams.mInsetSourceFlags =
                FLAG_FORCE_CONSUMING | FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR;
        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        // Caption inset source should add params' flags.
        verify(mMockWindowContainerTransaction).addInsetsSource(eq(token), any(),
                eq(0) /* index */, eq(captionBar()), any(), any(),
                eq(FLAG_FORCE_CONSUMING | FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR));
    }

    @Test
    public void testTaskPositionAndCropNotSetWhenFalse() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setBounds(TASK_BOUNDS)
                .setPositionInParent(TASK_POSITION_IN_PARENT.x, TASK_POSITION_IN_PARENT.y)
                .setVisible(true)
                .setWindowingMode(WINDOWING_MODE_FREEFORM)
                .build();
        // Density is 2. Shadow radius is 10px. Caption height is 64px.
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);


        mRelayoutParams.mSetTaskVisibilityPositionAndCrop = false;
        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        verify(mMockSurfaceControlStartT, never()).setWindowCrop(
                eq(mMockTaskSurface), anyInt(), anyInt());
        verify(mMockSurfaceControlFinishT, never()).setPosition(
                eq(mMockTaskSurface), anyFloat(), anyFloat());
        verify(mMockSurfaceControlFinishT, never()).setWindowCrop(
                eq(mMockTaskSurface), anyInt(), anyInt());
    }

    @Test
    public void testTaskPositionAndCropSetWhenSetTrue() {
        final Display defaultDisplay = mock(Display.class);
        doReturn(defaultDisplay).when(mMockDisplayController)
                .getDisplay(Display.DEFAULT_DISPLAY);

        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setBounds(TASK_BOUNDS)
                .setPositionInParent(TASK_POSITION_IN_PARENT.x, TASK_POSITION_IN_PARENT.y)
                .setVisible(true)
                .setWindowingMode(WINDOWING_MODE_FREEFORM)
                .build();
        // Density is 2. Shadow radius is 10px. Caption height is 64px.
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo);

        mRelayoutParams.mSetTaskVisibilityPositionAndCrop = true;
        windowDecor.relayout(taskInfo, true /* hasGlobalFocus */);

        verify(mMockSurfaceControlStartT).setWindowCrop(
                eq(mMockTaskSurface), anyInt(), anyInt());
        verify(mMockSurfaceControlFinishT).setPosition(
                eq(mMockTaskSurface), anyFloat(), anyFloat());
        verify(mMockSurfaceControlFinishT).setWindowCrop(
                eq(mMockTaskSurface), anyInt(), anyInt());
    }

    @Test
    public void updateViewHost_applyTransactionOnDrawIsTrue_surfaceControlIsUpdated() {
        final TestWindowDecoration windowDecor = createWindowDecoration(
                new TestRunningTaskInfoBuilder().build());
        mRelayoutParams.mApplyStartTransactionOnDraw = true;
        mRelayoutResult.mRootView = mMockView;

        windowDecor.updateViewHost(mRelayoutParams, mMockSurfaceControlStartT, mRelayoutResult);

        verify(mMockRootSurfaceControl).applyTransactionOnDraw(mMockSurfaceControlStartT);
    }

    @Test
    public void updateViewHost_nullDrawTransaction_applyTransactionOnDrawIsTrue_throwsException() {
        final TestWindowDecoration windowDecor = createWindowDecoration(
                new TestRunningTaskInfoBuilder().build());
        mRelayoutParams.mApplyStartTransactionOnDraw = true;
        mRelayoutResult.mRootView = mMockView;

        assertThrows(IllegalArgumentException.class,
                () -> windowDecor.updateViewHost(
                        mRelayoutParams, null /* onDrawTransaction */, mRelayoutResult));
    }

    @Test
    public void updateViewHost_nullDrawTransaction_applyTransactionOnDrawIsFalse_doesNotThrow() {
        final TestWindowDecoration windowDecor = createWindowDecoration(
                new TestRunningTaskInfoBuilder().build());
        mRelayoutParams.mApplyStartTransactionOnDraw = false;
        mRelayoutResult.mRootView = mMockView;

        windowDecor.updateViewHost(mRelayoutParams, null /* onDrawTransaction */, mRelayoutResult);
    }

    @Test
    public void onStatusBarVisibilityChange() {
        final ActivityManager.RunningTaskInfo task = createTaskInfo();
        task.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        when(mMockDisplayController.getInsetsState(task.displayId))
                .thenReturn(createInsetsState(statusBars(), true /* visible */));
        final TestWindowDecoration decor = spy(createWindowDecoration(task));
        decor.relayout(task, true /* hasGlobalFocus */);
        assertTrue(decor.mIsStatusBarVisible);

        decor.onInsetsStateChanged(createInsetsState(statusBars(), false /* visible */));

        verify(decor, times(2)).relayout(task, true /* hasGlobalFocus */);
    }

    @Test
    public void onStatusBarVisibilityChange_noChange_doesNotRelayout() {
        final ActivityManager.RunningTaskInfo task = createTaskInfo();
        task.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        when(mMockDisplayController.getInsetsState(task.displayId))
                .thenReturn(createInsetsState(statusBars(), true /* visible */));
        final TestWindowDecoration decor = spy(createWindowDecoration(task));
        decor.relayout(task, true /* hasGlobalFocus */);

        decor.onInsetsStateChanged(createInsetsState(statusBars(), true /* visible */));

        verify(decor, times(1)).relayout(task, true /* hasGlobalFocus */);
    }

    @Test
    public void onKeyguardStateChange() {
        final ActivityManager.RunningTaskInfo task = createTaskInfo();
        when(mMockDisplayController.getInsetsState(task.displayId))
                .thenReturn(createInsetsState(statusBars(), true /* visible */));
        final TestWindowDecoration decor = spy(createWindowDecoration(task));
        decor.relayout(task, true /* hasGlobalFocus */);
        assertFalse(decor.mIsKeyguardVisibleAndOccluded);

        decor.onKeyguardStateChanged(true /* visible */, true /* occluding */);

        assertTrue(decor.mIsKeyguardVisibleAndOccluded);
        verify(decor, times(2)).relayout(task, true /* hasGlobalFocus */);
    }

    @Test
    public void onKeyguardStateChange_noChange_doesNotRelayout() {
        final ActivityManager.RunningTaskInfo task = createTaskInfo();
        when(mMockDisplayController.getInsetsState(task.displayId))
                .thenReturn(createInsetsState(statusBars(), true /* visible */));
        final TestWindowDecoration decor = spy(createWindowDecoration(task));
        decor.relayout(task, true /* hasGlobalFocus */);
        assertFalse(decor.mIsKeyguardVisibleAndOccluded);

        decor.onKeyguardStateChanged(false /* visible */, true /* occluding */);

        verify(decor, times(1)).relayout(task, true /* hasGlobalFocus */);
    }

    private ActivityManager.RunningTaskInfo createTaskInfo() {
        final ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setVisible(true)
                .build();
        return taskInfo;
    }

    private InsetsState createInsetsState(@WindowInsets.Type.InsetsType int type, boolean visible) {
        final InsetsState state = new InsetsState();
        final InsetsSource source = new InsetsSource(0, type);
        source.setVisible(visible);
        state.addSource(source);
        return state;
    }

    private TestWindowDecoration createWindowDecoration(ActivityManager.RunningTaskInfo taskInfo) {
        return new TestWindowDecoration(mContext, mContext, mMockDisplayController,
                mMockShellTaskOrganizer, taskInfo, mMockTaskSurface,
                new MockObjectSupplier<>(mMockSurfaceControlBuilders,
                        () -> createMockSurfaceControlBuilder(mock(SurfaceControl.class))),
                new MockObjectSupplier<>(mMockSurfaceControlTransactions,
                        () -> mock(SurfaceControl.Transaction.class)),
                () -> mMockWindowContainerTransaction, () -> mMockTaskSurface,
                mMockSurfaceControlViewHostFactory, mDesktopModeEventLogger);
    }

    private class MockObjectSupplier<T> implements Supplier<T> {
        private final List<T> mObjects;
        private final Supplier<T> mDefaultSupplier;
        private int mNumOfCalls = 0;

        private MockObjectSupplier(List<T> objects, Supplier<T> defaultSupplier) {
            mObjects = objects;
            mDefaultSupplier = defaultSupplier;
        }

        @Override
        public T get() {
            final T mock = mNumOfCalls < mObjects.size()
                    ? mObjects.get(mNumOfCalls) : mDefaultSupplier.get();
            ++mNumOfCalls;
            return mock;
        }
    }

    private static class TestView extends View implements TaskFocusStateConsumer {
        private TestView(Context context) {
            super(context);
        }

        @Override
        public void setTaskFocusState(boolean focused) {}
    }

    private class TestWindowDecoration extends WindowDecoration<TestView> {
        TestWindowDecoration(Context context, @NonNull Context userContext,
                DisplayController displayController,
                ShellTaskOrganizer taskOrganizer, ActivityManager.RunningTaskInfo taskInfo,
                SurfaceControl taskSurface,
                Supplier<SurfaceControl.Builder> surfaceControlBuilderSupplier,
                Supplier<SurfaceControl.Transaction> surfaceControlTransactionSupplier,
                Supplier<WindowContainerTransaction> windowContainerTransactionSupplier,
                Supplier<SurfaceControl> surfaceControlSupplier,
                SurfaceControlViewHostFactory surfaceControlViewHostFactory,
                DesktopModeEventLogger desktopModeEventLogger) {
            super(context, userContext, displayController, taskOrganizer, taskInfo, taskSurface,
                    surfaceControlBuilderSupplier, surfaceControlTransactionSupplier,
                    windowContainerTransactionSupplier, surfaceControlSupplier,
                    surfaceControlViewHostFactory, desktopModeEventLogger);
        }

        @Override
        void relayout(ActivityManager.RunningTaskInfo taskInfo, boolean hasGlobalFocus) {
            relayout(taskInfo, false /* applyStartTransactionOnDraw */, hasGlobalFocus);
        }

        @Override
        Rect calculateValidDragArea() {
            return null;
        }

        @Override
        int getCaptionViewId() {
            return R.id.caption;
        }

        @Override
        TestView inflateLayout(Context context, int layoutResId) {
            if (layoutResId == R.layout.caption_layout) {
                return mMockView;
            }
            return super.inflateLayout(context, layoutResId);
        }

        void relayout(ActivityManager.RunningTaskInfo taskInfo,
                boolean applyStartTransactionOnDraw, boolean hasGlobalFocus) {
            mRelayoutParams.mRunningTaskInfo = taskInfo;
            mRelayoutParams.mApplyStartTransactionOnDraw = applyStartTransactionOnDraw;
            mRelayoutParams.mLayoutResId = R.layout.caption_layout;
            mRelayoutParams.mHasGlobalFocus = hasGlobalFocus;
            relayout(mRelayoutParams, mMockSurfaceControlStartT, mMockSurfaceControlFinishT,
                    mMockWindowContainerTransaction, mMockView, mRelayoutResult);
        }

        private AdditionalViewContainer addTestViewContainer() {
            final Resources resources = mDecorWindowContext.getResources();
            final int width = loadDimensionPixelSize(resources, mCaptionMenuWidthId);
            final int height = loadDimensionPixelSize(resources, mRelayoutParams.mCaptionHeightId);
            final String name = "Test Window";
            final AdditionalViewContainer additionalViewContainer =
                    addWindow(R.layout.desktop_mode_window_decor_handle_menu, name,
                            mMockSurfaceControlAddWindowT, mMockSurfaceSyncGroup, 0 /* x */,
                            0 /* y */, width, height);
            return additionalViewContainer;
        }
    }
}
