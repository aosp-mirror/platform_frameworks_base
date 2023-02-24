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

package com.android.wm.shell.floating;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.wm.shell.floating.FloatingTasksController.SMALLEST_SCREEN_WIDTH_DP_TO_BE_TABLET;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TaskViewTransitions;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.floating.views.FloatingTaskLayer;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

/**
 * Tests for the floating tasks controller.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class FloatingTasksControllerTest extends ShellTestCase {
    // Some behavior in the controller constructor is dependent on this so we can only
    // validate if it's working for the real value for those things.
    private static final boolean FLOATING_TASKS_ACTUALLY_ENABLED =
            SystemProperties.getBoolean("persist.wm.debug.floating_tasks", false);

    @Mock private ShellInit mShellInit;
    @Mock private ShellController mShellController;
    @Mock private WindowManager mWindowManager;
    @Mock private ShellTaskOrganizer mTaskOrganizer;
    @Captor private ArgumentCaptor<FloatingTaskLayer> mFloatingTaskLayerCaptor;

    private FloatingTasksController mController;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        WindowMetrics windowMetrics = mock(WindowMetrics.class);
        WindowInsets windowInsets = mock(WindowInsets.class);
        Insets insets = Insets.of(0, 0, 0, 0);
        when(mWindowManager.getCurrentWindowMetrics()).thenReturn(windowMetrics);
        when(windowMetrics.getWindowInsets()).thenReturn(windowInsets);
        when(windowMetrics.getBounds()).thenReturn(new Rect(0, 0, 1000, 1000));
        when(windowInsets.getInsetsIgnoringVisibility(anyInt())).thenReturn(insets);

        // For the purposes of this test, just run everything synchronously
        ShellExecutor shellExecutor = new TestShellExecutor();
        when(mTaskOrganizer.getExecutor()).thenReturn(shellExecutor);
    }

    @After
    public void tearDown() {
        if (mController != null) {
            mController.removeTask();
            mController = null;
        }
    }

    private void setUpTabletConfig() {
        Configuration config = mock(Configuration.class);
        config.smallestScreenWidthDp = SMALLEST_SCREEN_WIDTH_DP_TO_BE_TABLET;
        mController.setConfig(config);
    }

    private void setUpPhoneConfig() {
        Configuration config = mock(Configuration.class);
        config.smallestScreenWidthDp = SMALLEST_SCREEN_WIDTH_DP_TO_BE_TABLET - 1;
        mController.setConfig(config);
    }

    private void createController() {
        mController = new FloatingTasksController(mContext,
                mShellInit,
                mShellController,
                mock(ShellCommandHandler.class),
                Optional.empty(),
                mWindowManager,
                mTaskOrganizer,
                mock(TaskViewTransitions.class),
                mock(ShellExecutor.class),
                mock(ShellExecutor.class),
                mock(SyncTransactionQueue.class));
        spyOn(mController);
    }

    //
    // Shell specific
    //
    @Test
    public void instantiateController_addInitCallback() {
        if (FLOATING_TASKS_ACTUALLY_ENABLED) {
            createController();
            setUpTabletConfig();

            verify(mShellInit, times(1)).addInitCallback(any(), any());
        }
    }

    @Test
    public void instantiateController_doesntAddInitCallback() {
        if (!FLOATING_TASKS_ACTUALLY_ENABLED) {
            createController();

            verify(mShellInit, never()).addInitCallback(any(), any());
        }
    }

    @Test
    public void onInit_registerConfigChangeListener() {
        if (FLOATING_TASKS_ACTUALLY_ENABLED) {
            createController();
            setUpTabletConfig();
            mController.onInit();

            verify(mShellController, times(1)).addConfigurationChangeListener(any());
        }
    }

    //
    // Tests for floating layer, which is only available for tablets.
    //

    @Test
    public void testIsFloatingLayerAvailable_true() {
        createController();
        setUpTabletConfig();
        assertThat(mController.isFloatingLayerAvailable()).isTrue();
    }

    @Test
    public void testIsFloatingLayerAvailable_false() {
        createController();
        setUpPhoneConfig();
        assertThat(mController.isFloatingLayerAvailable()).isFalse();
    }

    //
    // Tests for floating tasks being enabled, guarded by sysprop flag.
    //

    @Test
    public void testIsFloatingTasksEnabled_true() {
        createController();
        mController.setFloatingTasksEnabled(true);
        setUpTabletConfig();
        assertThat(mController.isFloatingTasksEnabled()).isTrue();
    }

    @Test
    public void testIsFloatingTasksEnabled_false() {
        createController();
        mController.setFloatingTasksEnabled(false);
        setUpTabletConfig();
        assertThat(mController.isFloatingTasksEnabled()).isFalse();
    }

    //
    // Tests for behavior depending on flags
    //

    @Test
    public void testShowTaskIntent_enabled() {
        createController();
        mController.setFloatingTasksEnabled(true);
        setUpTabletConfig();

        mController.showTask(mock(Intent.class));
        verify(mWindowManager).addView(mFloatingTaskLayerCaptor.capture(), any());
        assertThat(mFloatingTaskLayerCaptor.getValue().getTaskViewCount()).isEqualTo(1);
    }

    @Test
    public void testShowTaskIntent_notEnabled() {
        createController();
        mController.setFloatingTasksEnabled(false);
        setUpTabletConfig();

        mController.showTask(mock(Intent.class));
        verify(mWindowManager, never()).addView(any(), any());
    }

    @Test
    public void testRemoveTask() {
        createController();
        mController.setFloatingTasksEnabled(true);
        setUpTabletConfig();

        mController.showTask(mock(Intent.class));
        verify(mWindowManager).addView(mFloatingTaskLayerCaptor.capture(), any());
        assertThat(mFloatingTaskLayerCaptor.getValue().getTaskViewCount()).isEqualTo(1);

        mController.removeTask();
        verify(mWindowManager).removeView(mFloatingTaskLayerCaptor.capture());
        assertThat(mFloatingTaskLayerCaptor.getValue().getTaskViewCount()).isEqualTo(0);
    }
}
