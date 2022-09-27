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
import static android.app.WindowConfiguration.WINDOW_CONFIG_BOUNDS;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.os.Handler;
import android.os.IBinder;
import android.testing.AndroidTestingRunner;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransaction.Change;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import org.junit.After;
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
    private RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    @Mock
    private ShellExecutor mTestExecutor;
    @Mock
    private Handler mMockHandler;
    @Mock
    private Transitions mMockTransitions;
    private TestShellExecutor mExecutor;

    private DesktopModeController mController;
    private DesktopModeTaskRepository mDesktopModeTaskRepository;
    private ShellInit mShellInit;
    private StaticMockitoSession mMockitoSession;

    @Before
    public void setUp() {
        mMockitoSession = mockitoSession().mockStatic(DesktopModeStatus.class).startMocking();
        when(DesktopModeStatus.isActive(any())).thenReturn(true);

        mShellInit = Mockito.spy(new ShellInit(mTestExecutor));
        mExecutor = new TestShellExecutor();

        mDesktopModeTaskRepository = new DesktopModeTaskRepository();

        mController = new DesktopModeController(mContext, mShellInit, mShellTaskOrganizer,
                mRootTaskDisplayAreaOrganizer, mMockTransitions,
                mDesktopModeTaskRepository, mMockHandler, mExecutor);

        when(mShellTaskOrganizer.prepareClearFreeformForStandardTasks(anyInt())).thenReturn(
                new WindowContainerTransaction());

        mShellInit.init();
        clearInvocations(mShellTaskOrganizer);
        clearInvocations(mRootTaskDisplayAreaOrganizer);
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
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
        when(mShellTaskOrganizer.prepareClearFreeformForStandardTasks(
                mContext.getDisplayId())).thenReturn(taskWct);

        // Create a fake DisplayAreaInfo to check if windowing mode change is set correctly
        MockToken displayMockToken = new MockToken();
        DisplayAreaInfo displayAreaInfo = new DisplayAreaInfo(displayMockToken.mToken,
                mContext.getDisplayId(), 0);
        when(mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(mContext.getDisplayId()))
                .thenReturn(displayAreaInfo);

        // The test
        mController.updateDesktopModeActive(true);

        ArgumentCaptor<WindowContainerTransaction> arg = ArgumentCaptor.forClass(
                WindowContainerTransaction.class);
        verify(mRootTaskDisplayAreaOrganizer).applyTransaction(arg.capture());

        // WCT should have 2 changes - clear task wm mode and set display wm mode
        WindowContainerTransaction wct = arg.getValue();
        assertThat(wct.getChanges()).hasSize(2);

        // Verify executed WCT has a change for setting task windowing mode to undefined
        Change taskWmModeChange = wct.getChanges().get(taskMockToken.binder());
        assertThat(taskWmModeChange).isNotNull();
        assertThat(taskWmModeChange.getWindowingMode()).isEqualTo(WINDOWING_MODE_UNDEFINED);

        // Verify executed WCT has a change for setting display windowing mode to freeform
        Change displayWmModeChange = wct.getChanges().get(displayAreaInfo.token.asBinder());
        assertThat(displayWmModeChange).isNotNull();
        assertThat(displayWmModeChange.getWindowingMode()).isEqualTo(WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testDesktopModeDisabled_taskWmAndBoundsClearedDisplaySetToFullscreen() {
        // Create a fake WCT to simulate setting task windowing mode to undefined
        WindowContainerTransaction taskWmWct = new WindowContainerTransaction();
        MockToken taskWmMockToken = new MockToken();
        taskWmWct.setWindowingMode(taskWmMockToken.token(), WINDOWING_MODE_UNDEFINED);
        when(mShellTaskOrganizer.prepareClearFreeformForStandardTasks(
                mContext.getDisplayId())).thenReturn(taskWmWct);

        // Create a fake WCT to simulate clearing task bounds
        WindowContainerTransaction taskBoundsWct = new WindowContainerTransaction();
        MockToken taskBoundsMockToken = new MockToken();
        taskBoundsWct.setBounds(taskBoundsMockToken.token(), null);
        when(mShellTaskOrganizer.prepareClearBoundsForStandardTasks(
                mContext.getDisplayId())).thenReturn(taskBoundsWct);

        // Create a fake DisplayAreaInfo to check if windowing mode change is set correctly
        MockToken displayMockToken = new MockToken();
        DisplayAreaInfo displayAreaInfo = new DisplayAreaInfo(displayMockToken.mToken,
                mContext.getDisplayId(), 0);
        when(mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(mContext.getDisplayId()))
                .thenReturn(displayAreaInfo);

        // The test
        mController.updateDesktopModeActive(false);

        ArgumentCaptor<WindowContainerTransaction> arg = ArgumentCaptor.forClass(
                WindowContainerTransaction.class);
        verify(mRootTaskDisplayAreaOrganizer).applyTransaction(arg.capture());

        // WCT should have 3 changes - clear task wm mode and bounds and set display wm mode
        WindowContainerTransaction wct = arg.getValue();
        assertThat(wct.getChanges()).hasSize(3);

        // Verify executed WCT has a change for setting task windowing mode to undefined
        Change taskWmMode = wct.getChanges().get(taskWmMockToken.binder());
        assertThat(taskWmMode).isNotNull();
        assertThat(taskWmMode.getWindowingMode()).isEqualTo(WINDOWING_MODE_UNDEFINED);

        // Verify executed WCT has a change for clearing task bounds
        Change bounds = wct.getChanges().get(taskBoundsMockToken.binder());
        assertThat(bounds).isNotNull();
        assertThat(bounds.getWindowSetMask() & WINDOW_CONFIG_BOUNDS).isNotEqualTo(0);
        assertThat(bounds.getConfiguration().windowConfiguration.getBounds().isEmpty()).isTrue();

        // Verify executed WCT has a change for setting display windowing mode to fullscreen
        Change displayWmModeChange = wct.getChanges().get(displayAreaInfo.token.asBinder());
        assertThat(displayWmModeChange).isNotNull();
        assertThat(displayWmModeChange.getWindowingMode()).isEqualTo(WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testShowDesktopApps() {
        // Set up two active tasks on desktop
        mDesktopModeTaskRepository.addActiveTask(1);
        mDesktopModeTaskRepository.addActiveTask(2);
        MockToken token1 = new MockToken();
        MockToken token2 = new MockToken();
        ActivityManager.RunningTaskInfo taskInfo1 = new TestRunningTaskInfoBuilder().setToken(
                token1.token()).setLastActiveTime(100).build();
        ActivityManager.RunningTaskInfo taskInfo2 = new TestRunningTaskInfoBuilder().setToken(
                token2.token()).setLastActiveTime(200).build();
        when(mShellTaskOrganizer.getRunningTaskInfo(1)).thenReturn(taskInfo1);
        when(mShellTaskOrganizer.getRunningTaskInfo(2)).thenReturn(taskInfo2);

        // Run show desktop apps logic
        mController.showDesktopApps();
        ArgumentCaptor<WindowContainerTransaction> wctCaptor = ArgumentCaptor.forClass(
                WindowContainerTransaction.class);
        verify(mShellTaskOrganizer).applyTransaction(wctCaptor.capture());
        WindowContainerTransaction wct = wctCaptor.getValue();

        // Check wct has reorder calls
        assertThat(wct.getHierarchyOps()).hasSize(2);

        // Task 2 has activity later, must be first
        WindowContainerTransaction.HierarchyOp op1 = wct.getHierarchyOps().get(0);
        assertThat(op1.getType()).isEqualTo(HIERARCHY_OP_TYPE_REORDER);
        assertThat(op1.getContainer()).isEqualTo(token2.binder());

        // Task 1 should be second
        WindowContainerTransaction.HierarchyOp op2 = wct.getHierarchyOps().get(0);
        assertThat(op2.getType()).isEqualTo(HIERARCHY_OP_TYPE_REORDER);
        assertThat(op2.getContainer()).isEqualTo(token2.binder());
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
