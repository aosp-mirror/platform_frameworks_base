/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.recents;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityTaskManager;
import android.app.IApplicationThread;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.HomeTransitionObserver;
import com.android.wm.shell.transition.Transitions;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.quality.Strictness;

import java.util.Optional;

/**
 * Tests for {@link RecentTasksController}
 *
 * Usage: atest WMShellUnitTests:RecentsTransitionHandlerTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class RecentsTransitionHandlerTest extends ShellTestCase {

    @Mock
    private Context mContext;
    @Mock
    private TaskStackListenerImpl mTaskStackListener;
    @Mock
    private ShellCommandHandler mShellCommandHandler;
    @Mock
    private DesktopModeTaskRepository mDesktopModeTaskRepository;
    @Mock
    private ActivityTaskManager mActivityTaskManager;
    @Mock
    private DisplayInsetsController mDisplayInsetsController;
    @Mock
    private IRecentTasksListener mRecentTasksListener;
    @Mock
    private TaskStackTransitionObserver mTaskStackTransitionObserver;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private ShellTaskOrganizer mShellTaskOrganizer;
    private RecentTasksController mRecentTasksController;
    private RecentTasksController mRecentTasksControllerReal;
    private RecentsTransitionHandler mRecentsTransitionHandler;
    private ShellInit mShellInit;
    private ShellController mShellController;
    private TestShellExecutor mMainExecutor;
    private static StaticMockitoSession sMockitoSession;

    @Before
    public void setUp() {
        sMockitoSession = mockitoSession().initMocks(this).strictness(Strictness.LENIENT)
                .mockStatic(DesktopModeStatus.class).startMocking();
        ExtendedMockito.doReturn(true)
                .when(() -> DesktopModeStatus.canEnterDesktopMode(any()));

        mMainExecutor = new TestShellExecutor();
        when(mContext.getPackageManager()).thenReturn(mock(PackageManager.class));
        when(mContext.getSystemService(KeyguardManager.class))
                .thenReturn(mock(KeyguardManager.class));
        mShellInit = spy(new ShellInit(mMainExecutor));
        mShellController = spy(new ShellController(mContext, mShellInit, mShellCommandHandler,
                mDisplayInsetsController, mMainExecutor));
        mRecentTasksControllerReal = new RecentTasksController(mContext, mShellInit,
                mShellController, mShellCommandHandler, mTaskStackListener, mActivityTaskManager,
                Optional.of(mDesktopModeTaskRepository), mTaskStackTransitionObserver,
                mMainExecutor);
        mRecentTasksController = spy(mRecentTasksControllerReal);
        mShellTaskOrganizer = new ShellTaskOrganizer(mShellInit, mShellCommandHandler,
                null /* sizeCompatUI */, Optional.empty(), Optional.of(mRecentTasksController),
                mMainExecutor);

        final Transitions transitions = mock(Transitions.class);
        doReturn(mMainExecutor).when(transitions).getMainExecutor();
        mRecentsTransitionHandler = new RecentsTransitionHandler(mShellInit, mShellTaskOrganizer,
                transitions, mRecentTasksController, mock(HomeTransitionObserver.class));

        mShellInit.init();
    }

    @After
    public void tearDown() {
        sMockitoSession.finishMocking();
    }

    @Test
    public void testStartSyntheticRecentsTransition_callsOnAnimationStart() throws Exception {
        final IRecentsAnimationRunner runner = mock(IRecentsAnimationRunner.class);
        doReturn(new Binder()).when(runner).asBinder();
        Bundle options = new Bundle();
        options.putBoolean("is_synthetic_recents_transition", true);
        IBinder transition = mRecentsTransitionHandler.startRecentsTransition(
                mock(PendingIntent.class), new Intent(), options, mock(IApplicationThread.class),
                runner);
        verify(runner).onAnimationStart(any(), any(), any(), any(), any(), any());

        // Finish and verify no transition remains
        mRecentsTransitionHandler.findController(transition).finish(true /* toHome */,
                false /* sendUserLeaveHint */, null /* finishCb */);
        mMainExecutor.flushAll();
        assertNull(mRecentsTransitionHandler.findController(transition));
    }

    @Test
    public void testStartSyntheticRecentsTransition_callsOnAnimationCancel() throws Exception {
        final IRecentsAnimationRunner runner = mock(IRecentsAnimationRunner.class);
        doReturn(new Binder()).when(runner).asBinder();
        Bundle options = new Bundle();
        options.putBoolean("is_synthetic_recents_transition", true);
        IBinder transition = mRecentsTransitionHandler.startRecentsTransition(
                mock(PendingIntent.class), new Intent(), options, mock(IApplicationThread.class),
                runner);
        verify(runner).onAnimationStart(any(), any(), any(), any(), any(), any());

        mRecentsTransitionHandler.findController(transition).cancel("test");
        mMainExecutor.flushAll();
        verify(runner).onAnimationCanceled(any(), any());
        assertNull(mRecentsTransitionHandler.findController(transition));
    }
}
