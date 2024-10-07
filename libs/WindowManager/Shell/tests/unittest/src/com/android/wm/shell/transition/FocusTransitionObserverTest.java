/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.transition;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;
import static android.window.TransitionInfo.FLAG_MOVED_TO_TOP;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.app.ActivityManager.RunningTaskInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionInfo.TransitionMode;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.window.flags.Flags;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.shared.IFocusTransitionListener;
import com.android.wm.shell.shared.TransactionPool;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the focus transition observer.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ENABLE_DISPLAY_FOCUS_IN_SHELL_TRANSITIONS)
public class FocusTransitionObserverTest extends ShellTestCase {

    static final int SECONDARY_DISPLAY_ID = 1;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private IFocusTransitionListener mListener;
    private Transitions mTransition;
    private FocusTransitionObserver mFocusTransitionObserver;

    @Before
    public void setUp() {
        mListener = mock(IFocusTransitionListener.class);
        when(mListener.asBinder()).thenReturn(mock(IBinder.class));

        mFocusTransitionObserver = new FocusTransitionObserver();
        mTransition =
                new Transitions(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        mock(ShellInit.class), mock(ShellController.class),
                        mock(ShellTaskOrganizer.class), mock(TransactionPool.class),
                        mock(DisplayController.class), new TestShellExecutor(),
                        new Handler(Looper.getMainLooper()), new TestShellExecutor(),
                        mock(HomeTransitionObserver.class),
                mFocusTransitionObserver);
        mFocusTransitionObserver.setRemoteFocusTransitionListener(mTransition, mListener);
    }

    @Test
    public void testOnlyDisplayChangeAffectsDisplayFocus() throws RemoteException {
        final IBinder binder = mock(IBinder.class);
        final SurfaceControl.Transaction tx = mock(SurfaceControl.Transaction.class);

        // Open a task on the secondary display, but it doesn't change display focus because it only
        // has a task change.
        TransitionInfo info = mock(TransitionInfo.class);
        final List<TransitionInfo.Change> changes = new ArrayList<>();
        setupTaskChange(changes, 123 /* taskId */, TRANSIT_OPEN, SECONDARY_DISPLAY_ID,
                true /* focused */);
        when(info.getChanges()).thenReturn(changes);
        mFocusTransitionObserver.onTransitionReady(binder, info, tx, tx);
        verify(mListener, never()).onFocusedDisplayChanged(SECONDARY_DISPLAY_ID);
        clearInvocations(mListener);

        // Moving the secondary display to front must change display focus to it.
        changes.clear();
        setupDisplayToTopChange(changes, SECONDARY_DISPLAY_ID);
        when(info.getChanges()).thenReturn(changes);
        mFocusTransitionObserver.onTransitionReady(binder, info, tx, tx);
        verify(mListener, times(1))
                .onFocusedDisplayChanged(SECONDARY_DISPLAY_ID);

        // Moving the secondary display to front must change display focus back to it.
        changes.clear();
        setupDisplayToTopChange(changes, DEFAULT_DISPLAY);
        when(info.getChanges()).thenReturn(changes);
        mFocusTransitionObserver.onTransitionReady(binder, info, tx, tx);
        verify(mListener, times(1)).onFocusedDisplayChanged(DEFAULT_DISPLAY);
    }

    private void setupTaskChange(List<TransitionInfo.Change> changes, int taskId,
            @TransitionMode int mode, int displayId, boolean focused) {
        TransitionInfo.Change change = mock(TransitionInfo.Change.class);
        RunningTaskInfo taskInfo = mock(RunningTaskInfo.class);
        taskInfo.taskId = taskId;
        taskInfo.isFocused = focused;
        when(change.hasFlags(FLAG_MOVED_TO_TOP)).thenReturn(focused);
        taskInfo.displayId = displayId;
        when(change.getTaskInfo()).thenReturn(taskInfo);
        when(change.getMode()).thenReturn(mode);
        changes.add(change);
    }

    private void setupDisplayToTopChange(List<TransitionInfo.Change> changes, int displayId) {
        TransitionInfo.Change change = mock(TransitionInfo.Change.class);
        when(change.hasFlags(FLAG_MOVED_TO_TOP)).thenReturn(true);
        when(change.hasFlags(FLAG_IS_DISPLAY)).thenReturn(true);
        when(change.getEndDisplayId()).thenReturn(displayId);
        changes.add(change);
    }
}
