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

package com.android.wm.shell.desktopmode;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.WindowConfiguration;
import android.os.Handler;
import android.os.IBinder;
import android.testing.AndroidTestingRunner;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransaction.Change;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.RootDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.sysui.ShellInit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DesktopModeControllerTest extends ShellTestCase {

    @Mock
    private ShellTaskOrganizer mShellTaskOrganizer;
    @Mock
    private RootDisplayAreaOrganizer mRootDisplayAreaOrganizer;
    @Mock
    private ShellExecutor mTestExecutor;
    @Mock
    private Handler mMockHandler;

    private DesktopModeController mController;
    private ShellInit mShellInit;

    @Before
    public void setUp() {
        mShellInit = Mockito.spy(new ShellInit(mTestExecutor));

        mController = new DesktopModeController(mContext, mShellInit, mShellTaskOrganizer,
                mRootDisplayAreaOrganizer, mMockHandler);

        mShellInit.init();
    }

    @Test
    public void instantiate_addInitCallback() {
        verify(mShellInit, times(1)).addInitCallback(any(), any());
    }

    @Test
    public void testDesktopModeEnabled_taskWmClearedDisplaySetToFreeform() {
        // Create a fake WCT to simulate setting task windowing mode to undefined
        WindowContainerTransaction taskWct = new WindowContainerTransaction();
        MockToken taskMockToken = new MockToken();
        taskWct.setWindowingMode(taskMockToken.token(), WINDOWING_MODE_UNDEFINED);
        when(mShellTaskOrganizer.prepareClearFreeformForTasks(mContext.getDisplayId())).thenReturn(
                taskWct);

        // Create a fake WCT to simulate setting display windowing mode to freeform
        WindowContainerTransaction displayWct = new WindowContainerTransaction();
        MockToken displayMockToken = new MockToken();
        displayWct.setWindowingMode(displayMockToken.token(), WINDOWING_MODE_FREEFORM);
        when(mRootDisplayAreaOrganizer.prepareWindowingModeChange(mContext.getDisplayId(),
                WINDOWING_MODE_FREEFORM)).thenReturn(displayWct);

        // The test
        mController.updateDesktopModeEnabled(true);

        ArgumentCaptor<WindowContainerTransaction> arg = ArgumentCaptor.forClass(
                WindowContainerTransaction.class);
        verify(mRootDisplayAreaOrganizer).applyTransaction(arg.capture());

        // WCT should have 2 changes - clear task wm mode and set display wm mode
        WindowContainerTransaction wct = arg.getValue();
        assertThat(wct.getChanges()).hasSize(2);

        // Verify executed WCT has a change for setting task windowing mode to undefined
        Change taskWmModeChange = wct.getChanges().get(taskMockToken.binder());
        assertThat(taskWmModeChange).isNotNull();
        assertThat(taskWmModeChange.getWindowingMode()).isEqualTo(WINDOWING_MODE_UNDEFINED);

        // Verify executed WCT has a change for setting display windowing mode to freeform
        Change displayWmModeChange = wct.getChanges().get(displayMockToken.binder());
        assertThat(displayWmModeChange).isNotNull();
        assertThat(displayWmModeChange.getWindowingMode()).isEqualTo(WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testDesktopModeDisabled_taskWmAndBoundsClearedDisplaySetToFullscreen() {
        // Create a fake WCT to simulate setting task windowing mode to undefined
        WindowContainerTransaction taskWmWct = new WindowContainerTransaction();
        MockToken taskWmMockToken = new MockToken();
        taskWmWct.setWindowingMode(taskWmMockToken.token(), WINDOWING_MODE_UNDEFINED);
        when(mShellTaskOrganizer.prepareClearFreeformForTasks(mContext.getDisplayId())).thenReturn(
                taskWmWct);

        // Create a fake WCT to simulate clearing task bounds
        WindowContainerTransaction taskBoundsWct = new WindowContainerTransaction();
        MockToken taskBoundsMockToken = new MockToken();
        taskBoundsWct.setBounds(taskBoundsMockToken.token(), null);
        when(mShellTaskOrganizer.prepareClearBoundsForTasks(mContext.getDisplayId())).thenReturn(
                taskBoundsWct);

        // Create a fake WCT to simulate setting display windowing mode to fullscreen
        WindowContainerTransaction displayWct = new WindowContainerTransaction();
        MockToken displayMockToken = new MockToken();
        displayWct.setWindowingMode(displayMockToken.token(), WINDOWING_MODE_FULLSCREEN);
        when(mRootDisplayAreaOrganizer.prepareWindowingModeChange(mContext.getDisplayId(),
                WINDOWING_MODE_FULLSCREEN)).thenReturn(displayWct);

        // The test
        mController.updateDesktopModeEnabled(false);

        ArgumentCaptor<WindowContainerTransaction> arg = ArgumentCaptor.forClass(
                WindowContainerTransaction.class);
        verify(mRootDisplayAreaOrganizer).applyTransaction(arg.capture());

        // WCT should have 3 changes - clear task wm mode and bounds and set display wm mode
        WindowContainerTransaction wct = arg.getValue();
        assertThat(wct.getChanges()).hasSize(3);

        // Verify executed WCT has a change for setting task windowing mode to undefined
        Change taskWmModeChange = wct.getChanges().get(taskWmMockToken.binder());
        assertThat(taskWmModeChange).isNotNull();
        assertThat(taskWmModeChange.getWindowingMode()).isEqualTo(WINDOWING_MODE_UNDEFINED);

        // Verify executed WCT has a change for clearing task bounds
        Change taskBoundsChange = wct.getChanges().get(taskBoundsMockToken.binder());
        assertThat(taskBoundsChange).isNotNull();
        assertThat(taskBoundsChange.getWindowSetMask()
                & WindowConfiguration.WINDOW_CONFIG_BOUNDS).isNotEqualTo(0);
        assertThat(taskBoundsChange.getConfiguration().windowConfiguration.getBounds().isEmpty())
                .isTrue();

        // Verify executed WCT has a change for setting display windowing mode to fullscreen
        Change displayWmModeChange = wct.getChanges().get(displayMockToken.binder());
        assertThat(displayWmModeChange).isNotNull();
        assertThat(displayWmModeChange.getWindowingMode()).isEqualTo(WINDOWING_MODE_FULLSCREEN);
    }

    private static class MockToken {
        private final WindowContainerToken mToken;
        private final IBinder mBinder;

        MockToken() {
            mToken = mock(WindowContainerToken.class);
            mBinder = mock(IBinder.class);
            when(mToken.asBinder()).thenReturn(mBinder);
        }

        WindowContainerToken token() {
            return mToken;
        }

        IBinder binder() {
            return mBinder;
        }
    }
}
