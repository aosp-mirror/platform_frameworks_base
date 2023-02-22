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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager.LayoutParams;
import android.window.TaskConstants;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.tests.R;

import org.junit.Before;
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

    private final List<SurfaceControl.Transaction> mMockSurfaceControlTransactions =
            new ArrayList<>();
    private final List<SurfaceControl.Builder> mMockSurfaceControlBuilders = new ArrayList<>();
    private SurfaceControl.Transaction mMockSurfaceControlStartT;
    private SurfaceControl.Transaction mMockSurfaceControlFinishT;
    private SurfaceControl.Transaction mMockSurfaceControlAddWindowT;
    private WindowDecoration.RelayoutParams mRelayoutParams = new WindowDecoration.RelayoutParams();

    @Before
    public void setUp() {
        mMockSurfaceControlStartT = createMockSurfaceControlTransaction();
        mMockSurfaceControlFinishT = createMockSurfaceControlTransaction();
        mMockSurfaceControlAddWindowT = createMockSurfaceControlTransaction();

        mRelayoutParams.mLayoutResId = 0;
        mRelayoutParams.mCaptionHeightId = R.dimen.test_freeform_decor_caption_height;
        // Caption should have fixed width except in testLayoutResultCalculation_fullWidthCaption()
        mRelayoutParams.mCaptionWidthId = R.dimen.test_freeform_decor_caption_width;
        mRelayoutParams.mShadowRadiusId = R.dimen.test_window_decor_shadow_radius;

        doReturn(mMockSurfaceControlViewHost).when(mMockSurfaceControlViewHostFactory)
                .create(any(), any(), any());
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
        taskInfo.isFocused = false;
        // Density is 2. Outsets are (20, 40, 60, 80) px. Shadow radius is 10px. Caption height is
        // 64px.
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;
        mRelayoutParams.setOutsets(
                R.dimen.test_window_decor_left_outset,
                R.dimen.test_window_decor_top_outset,
                R.dimen.test_window_decor_right_outset,
                R.dimen.test_window_decor_bottom_outset);

        final SurfaceControl taskSurface = mock(SurfaceControl.class);
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo, taskSurface);

        windowDecor.relayout(taskInfo);

        verify(decorContainerSurfaceBuilder, never()).build();
        verify(taskBackgroundSurfaceBuilder, never()).build();
        verify(captionContainerSurfaceBuilder, never()).build();
        verify(mMockSurfaceControlViewHostFactory, never()).create(any(), any(), any());

        verify(mMockSurfaceControlFinishT).hide(taskSurface);

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
                .setVisible(true)
                .build();
        taskInfo.isFocused = true;
        // Density is 2. Outsets are (20, 40, 60, 80) px. Shadow radius is 10px. Caption height is
        // 64px.
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;
        mRelayoutParams.setOutsets(
                R.dimen.test_window_decor_left_outset,
                R.dimen.test_window_decor_top_outset,
                R.dimen.test_window_decor_right_outset,
                R.dimen.test_window_decor_bottom_outset);
        final SurfaceControl taskSurface = mock(SurfaceControl.class);
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo, taskSurface);

        windowDecor.relayout(taskInfo);

        verify(decorContainerSurfaceBuilder).setParent(taskSurface);
        verify(decorContainerSurfaceBuilder).setContainerLayer();
        verify(mMockSurfaceControlStartT).setTrustedOverlay(decorContainerSurface, true);
        verify(mMockSurfaceControlStartT).setPosition(decorContainerSurface, -20, -40);
        verify(mMockSurfaceControlStartT).setWindowCrop(decorContainerSurface, 380, 220);

        verify(taskBackgroundSurfaceBuilder).setParent(taskSurface);
        verify(taskBackgroundSurfaceBuilder).setEffectLayer();
        verify(mMockSurfaceControlStartT).setWindowCrop(taskBackgroundSurface, 300, 100);
        verify(mMockSurfaceControlStartT)
                .setColor(taskBackgroundSurface, new float[] {1.f, 1.f, 0.f});
        verify(mMockSurfaceControlStartT).setShadowRadius(taskBackgroundSurface, 10);
        verify(mMockSurfaceControlStartT).setLayer(taskBackgroundSurface,
                TaskConstants.TASK_CHILD_LAYER_TASK_BACKGROUND);
        verify(mMockSurfaceControlStartT).show(taskBackgroundSurface);

        verify(captionContainerSurfaceBuilder).setParent(decorContainerSurface);
        verify(captionContainerSurfaceBuilder).setContainerLayer();
        verify(mMockSurfaceControlStartT).setPosition(captionContainerSurface, 20, 40);
        verify(mMockSurfaceControlStartT).setWindowCrop(captionContainerSurface, 432, 64);
        verify(mMockSurfaceControlStartT).show(captionContainerSurface);

        verify(mMockSurfaceControlViewHostFactory).create(any(), eq(defaultDisplay), any());

        verify(mMockSurfaceControlViewHost)
                .setView(same(mMockView),
                        argThat(lp -> lp.height == 64
                                && lp.width == 432
                                && (lp.flags & LayoutParams.FLAG_NOT_FOCUSABLE) != 0));
        if (ViewRootImpl.CAPTION_ON_SHELL) {
            verify(mMockView).setTaskFocusState(true);
            verify(mMockWindowContainerTransaction)
                    .addRectInsetsProvider(taskInfo.token,
                            new Rect(100, 300, 400, 364),
                            new int[] { InsetsState.ITYPE_CAPTION_BAR });
        }

        verify(mMockSurfaceControlFinishT)
                .setPosition(taskSurface, TASK_POSITION_IN_PARENT.x, TASK_POSITION_IN_PARENT.y);
        verify(mMockSurfaceControlFinishT)
                .setCrop(taskSurface, new Rect(-20, -40, 360, 180));
        verify(mMockSurfaceControlStartT)
                .show(taskSurface);

        assertEquals(380, mRelayoutResult.mWidth);
        assertEquals(220, mRelayoutResult.mHeight);
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
        final SurfaceControl taskBackgroundSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder taskBackgroundSurfaceBuilder =
                createMockSurfaceControlBuilder(taskBackgroundSurface);
        mMockSurfaceControlBuilders.add(taskBackgroundSurfaceBuilder);
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
        taskInfo.isFocused = true;
        // Density is 2. Outsets are (20, 40, 60, 80) px. Shadow radius is 10px. Caption height is
        // 64px.
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;
        mRelayoutParams.setOutsets(
                R.dimen.test_window_decor_left_outset,
                R.dimen.test_window_decor_top_outset,
                R.dimen.test_window_decor_right_outset,
                R.dimen.test_window_decor_bottom_outset);

        final SurfaceControl taskSurface = mock(SurfaceControl.class);
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo, taskSurface);

        windowDecor.relayout(taskInfo);

        verify(mMockSurfaceControlViewHost, never()).release();
        verify(t, never()).apply();
        verify(mMockWindowContainerTransaction, never())
                .removeInsetsProvider(eq(taskInfo.token), any());

        taskInfo.isVisible = false;
        windowDecor.relayout(taskInfo);

        final InOrder releaseOrder = inOrder(t, mMockSurfaceControlViewHost);
        releaseOrder.verify(mMockSurfaceControlViewHost).release();
        releaseOrder.verify(t).remove(captionContainerSurface);
        releaseOrder.verify(t).remove(decorContainerSurface);
        releaseOrder.verify(t).remove(taskBackgroundSurface);
        releaseOrder.verify(t).apply();
        verify(mMockWindowContainerTransaction).removeInsetsProvider(eq(taskInfo.token), any());
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
        verify(mMockSurfaceControlViewHostFactory).create(any(), eq(mockDisplay), any());
        verify(mMockSurfaceControlViewHost).setView(same(mMockView), any());
    }

    @Test
    public void testAddWindow() {
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
        taskInfo.isFocused = true;
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;
        mRelayoutParams.setOutsets(
                R.dimen.test_window_decor_left_outset,
                R.dimen.test_window_decor_top_outset,
                R.dimen.test_window_decor_right_outset,
                R.dimen.test_window_decor_bottom_outset);
        final SurfaceControl taskSurface = mock(SurfaceControl.class);
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo, taskSurface);
        windowDecor.relayout(taskInfo);

        final SurfaceControl additionalWindowSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder additionalWindowSurfaceBuilder =
                createMockSurfaceControlBuilder(additionalWindowSurface);
        mMockSurfaceControlBuilders.add(additionalWindowSurfaceBuilder);

        WindowDecoration.AdditionalWindow additionalWindow = windowDecor.addTestWindow();

        verify(additionalWindowSurfaceBuilder).setContainerLayer();
        verify(additionalWindowSurfaceBuilder).setParent(decorContainerSurface);
        verify(additionalWindowSurfaceBuilder).build();
        verify(mMockSurfaceControlAddWindowT).setPosition(additionalWindowSurface, 20, 40);
        verify(mMockSurfaceControlAddWindowT).setWindowCrop(additionalWindowSurface, 432, 64);
        verify(mMockSurfaceControlAddWindowT).show(additionalWindowSurface);
        verify(mMockSurfaceControlViewHostFactory, Mockito.times(2))
                .create(any(), eq(defaultDisplay), any());
        assertThat(additionalWindow.mWindowViewHost).isNotNull();

        additionalWindow.releaseView();

        assertThat(additionalWindow.mWindowViewHost).isNull();
        assertThat(additionalWindow.mWindowSurface).isNull();
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
        final SurfaceControl taskBackgroundSurface = mock(SurfaceControl.class);
        final SurfaceControl.Builder taskBackgroundSurfaceBuilder =
                createMockSurfaceControlBuilder(taskBackgroundSurface);
        mMockSurfaceControlBuilders.add(taskBackgroundSurfaceBuilder);
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
        taskInfo.isFocused = true;
        taskInfo.configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT * 2;
        mRelayoutParams.setOutsets(
                R.dimen.test_window_decor_left_outset,
                R.dimen.test_window_decor_top_outset,
                R.dimen.test_window_decor_right_outset,
                R.dimen.test_window_decor_bottom_outset);
        final SurfaceControl taskSurface = mock(SurfaceControl.class);
        final TestWindowDecoration windowDecor = createWindowDecoration(taskInfo, taskSurface);

        mRelayoutParams.mCaptionWidthId = Resources.ID_NULL;
        windowDecor.relayout(taskInfo);

        verify(captionContainerSurfaceBuilder).setParent(decorContainerSurface);
        verify(captionContainerSurfaceBuilder).setContainerLayer();
        verify(mMockSurfaceControlStartT).setPosition(captionContainerSurface, 20, 40);
        // Width of the captionContainerSurface should match the width of TASK_BOUNDS
        verify(mMockSurfaceControlStartT).setWindowCrop(captionContainerSurface, 300, 64);
        verify(mMockSurfaceControlStartT).show(captionContainerSurface);
    }

    private TestWindowDecoration createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo, SurfaceControl testSurface) {
        return new TestWindowDecoration(InstrumentationRegistry.getInstrumentation().getContext(),
                mMockDisplayController, mMockShellTaskOrganizer,
                taskInfo, testSurface,
                new MockObjectSupplier<>(mMockSurfaceControlBuilders,
                        () -> createMockSurfaceControlBuilder(mock(SurfaceControl.class))),
                new MockObjectSupplier<>(mMockSurfaceControlTransactions,
                        () -> mock(SurfaceControl.Transaction.class)),
                () -> mMockWindowContainerTransaction, mMockSurfaceControlViewHostFactory);
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
        TestWindowDecoration(Context context, DisplayController displayController,
                ShellTaskOrganizer taskOrganizer, ActivityManager.RunningTaskInfo taskInfo,
                SurfaceControl taskSurface,
                Supplier<SurfaceControl.Builder> surfaceControlBuilderSupplier,
                Supplier<SurfaceControl.Transaction> surfaceControlTransactionSupplier,
                Supplier<WindowContainerTransaction> windowContainerTransactionSupplier,
                SurfaceControlViewHostFactory surfaceControlViewHostFactory) {
            super(context, displayController, taskOrganizer, taskInfo, taskSurface,
                    surfaceControlBuilderSupplier, surfaceControlTransactionSupplier,
                    windowContainerTransactionSupplier, surfaceControlViewHostFactory);
        }

        @Override
        void relayout(ActivityManager.RunningTaskInfo taskInfo) {
            relayout(mRelayoutParams, mMockSurfaceControlStartT, mMockSurfaceControlFinishT,
                    mMockWindowContainerTransaction, mMockView, mRelayoutResult);
        }

        private WindowDecoration.AdditionalWindow addTestWindow() {
            final Resources resources = mDecorWindowContext.getResources();
            int x = mRelayoutParams.mCaptionX;
            int y = mRelayoutParams.mCaptionY;
            int width = loadDimensionPixelSize(resources, mRelayoutParams.mCaptionWidthId);
            int height = loadDimensionPixelSize(resources, mRelayoutParams.mCaptionHeightId);
            String name = "Test Window";
            WindowDecoration.AdditionalWindow additionalWindow =
                    addWindow(R.layout.desktop_mode_decor_handle_menu, name,
                            mMockSurfaceControlAddWindowT,
                            x - mRelayoutResult.mDecorContainerOffsetX,
                            y - mRelayoutResult.mDecorContainerOffsetY,
                            width, height);
            return additionalWindow;
        }
    }
}
