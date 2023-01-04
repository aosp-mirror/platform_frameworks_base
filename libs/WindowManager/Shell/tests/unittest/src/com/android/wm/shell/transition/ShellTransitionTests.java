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

package com.android.wm.shell.transition;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_UNSPECIFIED;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FIRST_CUSTOM;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.TransitionInfo.FLAG_DISPLAY_HAS_ALERT_WINDOWS;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;
import static android.window.TransitionInfo.FLAG_TRANSLUCENT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.IWindowContainerToken;
import android.window.RemoteTransition;
import android.window.TransitionFilter;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;
import android.window.WindowOrganizer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.sysui.ShellSharedConstants;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

import java.util.ArrayList;

/**
 * Tests for the shell transitions.
 *
 * Build/Install/Run:
 * atest WMShellUnitTests:ShellTransitionTests
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ShellTransitionTests extends ShellTestCase {

    private final WindowOrganizer mOrganizer = mock(WindowOrganizer.class);
    private final TransactionPool mTransactionPool = mock(TransactionPool.class);
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final TestShellExecutor mMainExecutor = new TestShellExecutor();
    private final ShellExecutor mAnimExecutor = new TestShellExecutor();
    private final TestTransitionHandler mDefaultHandler = new TestTransitionHandler();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Before
    public void setUp() {
        doAnswer(invocation -> invocation.getArguments()[1])
                .when(mOrganizer).startTransition(any(), any());
    }

    @Test
    public void instantiate_addInitCallback() {
        ShellInit shellInit = mock(ShellInit.class);
        final Transitions t = new Transitions(mContext, shellInit, mock(ShellController.class),
                mOrganizer, mTransactionPool, createTestDisplayController(), mMainExecutor,
                mMainHandler, mAnimExecutor);
        verify(shellInit, times(1)).addInitCallback(any(), eq(t));
    }

    @Test
    public void instantiateController_addExternalInterface() {
        ShellInit shellInit = new ShellInit(mMainExecutor);
        ShellController shellController = mock(ShellController.class);
        final Transitions t = new Transitions(mContext, shellInit, shellController,
                mOrganizer, mTransactionPool, createTestDisplayController(), mMainExecutor,
                mMainHandler, mAnimExecutor);
        shellInit.init();
        verify(shellController, times(1)).addExternalInterface(
                eq(ShellSharedConstants.KEY_EXTRA_SHELL_SHELL_TRANSITIONS), any(), any());
    }

    @Test
    public void testBasicTransitionFlow() {
        Transitions transitions = createTestTransitions();
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        IBinder transitToken = new Binder();
        transitions.requestStartTransition(transitToken,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        verify(mOrganizer, times(1)).startTransition(eq(transitToken), any());
        TransitionInfo info = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        transitions.onTransitionReady(transitToken, info, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        assertEquals(1, mDefaultHandler.activeCount());
        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();
        verify(mOrganizer, times(1)).finishTransition(eq(transitToken), any(), any());
    }

    @Test
    public void testNonDefaultHandler() {
        Transitions transitions = createTestTransitions();
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        final WindowContainerTransaction handlerWCT = new WindowContainerTransaction();
        // Make a test handler that only responds to multi-window triggers AND only animates
        // Change transitions.
        TestTransitionHandler testHandler = new TestTransitionHandler() {
            @Override
            public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                    @NonNull SurfaceControl.Transaction startTransaction,
                    @NonNull SurfaceControl.Transaction finishTransaction,
                    @NonNull Transitions.TransitionFinishCallback finishCallback) {
                for (TransitionInfo.Change chg : info.getChanges()) {
                    if (chg.getMode() == TRANSIT_CHANGE) {
                        return super.startAnimation(transition, info, startTransaction,
                                finishTransaction, finishCallback);
                    }
                }
                return false;
            }

            @Nullable
            @Override
            public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                    @NonNull TransitionRequestInfo request) {
                final RunningTaskInfo task = request.getTriggerTask();
                return (task != null && task.getWindowingMode() == WINDOWING_MODE_MULTI_WINDOW)
                        ? handlerWCT : null;
            }
        };
        transitions.addHandler(testHandler);

        IBinder transitToken = new Binder();
        TransitionInfo open = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();

        // Make a request that will be rejected by the testhandler.
        transitions.requestStartTransition(transitToken,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        verify(mOrganizer, times(1)).startTransition(eq(transitToken), isNull());
        transitions.onTransitionReady(transitToken, open, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        assertEquals(1, mDefaultHandler.activeCount());
        assertEquals(0, testHandler.activeCount());
        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();

        // Make a request that will be handled by testhandler but not animated by it.
        RunningTaskInfo mwTaskInfo =
                createTaskInfo(1, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        // Make the wct non-empty.
        handlerWCT.setFocusable(new WindowContainerToken(mock(IWindowContainerToken.class)), true);
        transitions.requestStartTransition(transitToken,
                new TransitionRequestInfo(TRANSIT_OPEN, mwTaskInfo, null /* remote */));
        verify(mOrganizer, times(1)).startTransition(
                eq(transitToken), eq(handlerWCT));
        transitions.onTransitionReady(transitToken, open, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        assertEquals(1, mDefaultHandler.activeCount());
        assertEquals(0, testHandler.activeCount());
        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();

        // Make a request that will be handled AND animated by testhandler.
        // Add an aggressive handler (doesn't handle but always animates) on top to make sure that
        // the test handler gets first shot at animating since it claimed to handle it.
        TestTransitionHandler topHandler = new TestTransitionHandler();
        transitions.addHandler(topHandler);
        transitions.requestStartTransition(transitToken,
                new TransitionRequestInfo(TRANSIT_CHANGE, mwTaskInfo, null /* remote */));
        verify(mOrganizer, times(2)).startTransition(
                eq(transitToken), eq(handlerWCT));
        TransitionInfo change = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(TRANSIT_CHANGE).build();
        transitions.onTransitionReady(transitToken, change, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        assertEquals(0, mDefaultHandler.activeCount());
        assertEquals(1, testHandler.activeCount());
        assertEquals(0, topHandler.activeCount());
        testHandler.finishAll();
        mMainExecutor.flushAll();
    }

    @Test
    public void testRequestRemoteTransition() {
        Transitions transitions = createTestTransitions();
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        final boolean[] remoteCalled = new boolean[]{false};
        final WindowContainerTransaction remoteFinishWCT = new WindowContainerTransaction();
        IRemoteTransition testRemote = new IRemoteTransition.Stub() {
            @Override
            public void startAnimation(IBinder token, TransitionInfo info,
                    SurfaceControl.Transaction t,
                    IRemoteTransitionFinishedCallback finishCallback) throws RemoteException {
                remoteCalled[0] = true;
                finishCallback.onTransitionFinished(remoteFinishWCT, null /* sct */);
            }

            @Override
            public void mergeAnimation(IBinder token, TransitionInfo info,
                    SurfaceControl.Transaction t, IBinder mergeTarget,
                    IRemoteTransitionFinishedCallback finishCallback) throws RemoteException {
            }
        };
        IBinder transitToken = new Binder();
        transitions.requestStartTransition(transitToken,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */,
                        new RemoteTransition(testRemote)));
        verify(mOrganizer, times(1)).startTransition(eq(transitToken), any());
        TransitionInfo info = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        transitions.onTransitionReady(transitToken, info, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        assertEquals(0, mDefaultHandler.activeCount());
        assertTrue(remoteCalled[0]);
        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();
        verify(mOrganizer, times(1)).finishTransition(eq(transitToken), eq(remoteFinishWCT), any());
    }

    @Test
    public void testTransitionFilterActivityType() {
        TransitionFilter filter = new TransitionFilter();
        filter.mRequirements =
                new TransitionFilter.Requirement[]{new TransitionFilter.Requirement()};
        filter.mRequirements[0].mActivityType = ACTIVITY_TYPE_HOME;
        filter.mRequirements[0].mModes = new int[]{TRANSIT_OPEN, TRANSIT_TO_FRONT};

        final TransitionInfo openHome = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN,
                        createTaskInfo(1, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME)).build();
        assertTrue(filter.matches(openHome));

        final TransitionInfo openStd = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN, createTaskInfo(
                        1, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD)).build();
        assertFalse(filter.matches(openStd));
    }

    @Test
    public void testTransitionFilterMultiRequirement() {
        // filter that requires at-least one opening and one closing app
        TransitionFilter filter = new TransitionFilter();
        filter.mRequirements = new TransitionFilter.Requirement[]{
                new TransitionFilter.Requirement(), new TransitionFilter.Requirement()};
        filter.mRequirements[0].mModes = new int[]{TRANSIT_OPEN, TRANSIT_TO_FRONT};
        filter.mRequirements[1].mModes = new int[]{TRANSIT_CLOSE, TRANSIT_TO_BACK};

        final TransitionInfo openOnly = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).build();
        assertFalse(filter.matches(openOnly));

        final TransitionInfo openClose = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        assertTrue(filter.matches(openClose));
    }

    @Test
    public void testTransitionFilterNotRequirement() {
        // filter that requires one opening and NO translucent apps
        TransitionFilter filter = new TransitionFilter();
        filter.mRequirements = new TransitionFilter.Requirement[]{
                new TransitionFilter.Requirement(), new TransitionFilter.Requirement()};
        filter.mRequirements[0].mModes = new int[]{TRANSIT_OPEN, TRANSIT_TO_FRONT};
        filter.mRequirements[1].mFlags = FLAG_TRANSLUCENT;
        filter.mRequirements[1].mNot = true;

        final TransitionInfo openOnly = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).build();
        assertTrue(filter.matches(openOnly));

        final TransitionInfo openAndTranslucent = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        openAndTranslucent.getChanges().get(1).setFlags(FLAG_TRANSLUCENT);
        assertFalse(filter.matches(openAndTranslucent));
    }

    @Test
    public void testTransitionFilterChecksTypeSet() {
        TransitionFilter filter = new TransitionFilter();
        filter.mTypeSet = new int[]{TRANSIT_OPEN, TRANSIT_TO_FRONT};

        final TransitionInfo openOnly = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).build();
        assertTrue(filter.matches(openOnly));

        final TransitionInfo toFrontOnly = new TransitionInfoBuilder(TRANSIT_TO_FRONT)
                .addChange(TRANSIT_TO_FRONT).build();
        assertTrue(filter.matches(toFrontOnly));

        final TransitionInfo closeOnly = new TransitionInfoBuilder(TRANSIT_CLOSE)
                .addChange(TRANSIT_CLOSE).build();
        assertFalse(filter.matches(closeOnly));
    }

    @Test
    public void testTransitionFilterChecksFlags() {
        TransitionFilter filter = new TransitionFilter();
        filter.mFlags = TRANSIT_FLAG_KEYGUARD_GOING_AWAY;

        final TransitionInfo withFlag = new TransitionInfoBuilder(TRANSIT_TO_BACK,
                TRANSIT_FLAG_KEYGUARD_GOING_AWAY)
                .addChange(TRANSIT_TO_BACK).build();
        assertTrue(filter.matches(withFlag));

        final TransitionInfo withoutFlag = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).build();
        assertFalse(filter.matches(withoutFlag));
    }

    @Test
    public void testTransitionFilterChecksNotFlags() {
        TransitionFilter filter = new TransitionFilter();
        filter.mNotFlags = TRANSIT_FLAG_KEYGUARD_GOING_AWAY;

        final TransitionInfo withFlag = new TransitionInfoBuilder(TRANSIT_TO_BACK,
                TRANSIT_FLAG_KEYGUARD_GOING_AWAY)
                .addChange(TRANSIT_TO_BACK).build();
        assertFalse(filter.matches(withFlag));

        final TransitionInfo withoutFlag = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).build();
        assertTrue(filter.matches(withoutFlag));
    }

    @Test
    public void testRegisteredRemoteTransition() {
        Transitions transitions = createTestTransitions();
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        final boolean[] remoteCalled = new boolean[]{false};
        IRemoteTransition testRemote = new IRemoteTransition.Stub() {
            @Override
            public void startAnimation(IBinder token, TransitionInfo info,
                    SurfaceControl.Transaction t,
                    IRemoteTransitionFinishedCallback finishCallback) throws RemoteException {
                remoteCalled[0] = true;
                finishCallback.onTransitionFinished(null /* wct */, null /* sct */);
            }

            @Override
            public void mergeAnimation(IBinder token, TransitionInfo info,
                    SurfaceControl.Transaction t, IBinder mergeTarget,
                    IRemoteTransitionFinishedCallback finishCallback) throws RemoteException {
            }
        };

        TransitionFilter filter = new TransitionFilter();
        filter.mRequirements =
                new TransitionFilter.Requirement[]{new TransitionFilter.Requirement()};
        filter.mRequirements[0].mModes = new int[]{TRANSIT_OPEN, TRANSIT_TO_FRONT};

        transitions.registerRemote(filter, new RemoteTransition(testRemote));
        mMainExecutor.flushAll();

        IBinder transitToken = new Binder();
        transitions.requestStartTransition(transitToken,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        verify(mOrganizer, times(1)).startTransition(eq(transitToken), any());
        TransitionInfo info = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        transitions.onTransitionReady(transitToken, info, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        assertEquals(0, mDefaultHandler.activeCount());
        assertTrue(remoteCalled[0]);
        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();
        verify(mOrganizer, times(1)).finishTransition(eq(transitToken), any(), any());
    }

    @Test
    public void testOneShotRemoteHandler() {
        Transitions transitions = createTestTransitions();
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        final boolean[] remoteCalled = new boolean[]{false};
        final WindowContainerTransaction remoteFinishWCT = new WindowContainerTransaction();
        IRemoteTransition testRemote = new IRemoteTransition.Stub() {
            @Override
            public void startAnimation(IBinder token, TransitionInfo info,
                    SurfaceControl.Transaction t,
                    IRemoteTransitionFinishedCallback finishCallback) throws RemoteException {
                remoteCalled[0] = true;
                finishCallback.onTransitionFinished(remoteFinishWCT, null /* sct */);
            }

            @Override
            public void mergeAnimation(IBinder token, TransitionInfo info,
                    SurfaceControl.Transaction t, IBinder mergeTarget,
                    IRemoteTransitionFinishedCallback finishCallback) throws RemoteException {
            }
        };

        final int transitType = TRANSIT_FIRST_CUSTOM + 1;

        OneShotRemoteHandler oneShot = new OneShotRemoteHandler(mMainExecutor,
                new RemoteTransition(testRemote));
        // Verify that it responds to the remote but not other things.
        IBinder transitToken = new Binder();
        assertNotNull(oneShot.handleRequest(transitToken,
                new TransitionRequestInfo(transitType, null, new RemoteTransition(testRemote))));
        assertNull(oneShot.handleRequest(transitToken,
                new TransitionRequestInfo(transitType, null, null)));

        Transitions.TransitionFinishCallback testFinish =
                mock(Transitions.TransitionFinishCallback.class);
        // Verify that it responds to animation properly
        oneShot.setTransition(transitToken);
        IBinder anotherToken = new Binder();
        assertFalse(oneShot.startAnimation(anotherToken, new TransitionInfo(transitType, 0),
                mock(SurfaceControl.Transaction.class), mock(SurfaceControl.Transaction.class),
                testFinish));
        assertTrue(oneShot.startAnimation(transitToken, new TransitionInfo(transitType, 0),
                mock(SurfaceControl.Transaction.class), mock(SurfaceControl.Transaction.class),
                testFinish));
    }

    @Test
    public void testTransitionQueueing() {
        Transitions transitions = createTestTransitions();
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        IBinder transitToken1 = new Binder();
        transitions.requestStartTransition(transitToken1,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        TransitionInfo info1 = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        transitions.onTransitionReady(transitToken1, info1, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        assertEquals(1, mDefaultHandler.activeCount());

        IBinder transitToken2 = new Binder();
        transitions.requestStartTransition(transitToken2,
                new TransitionRequestInfo(TRANSIT_CLOSE, null /* trigger */, null /* remote */));
        TransitionInfo info2 = new TransitionInfoBuilder(TRANSIT_CLOSE)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        transitions.onTransitionReady(transitToken2, info2, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        // default handler doesn't merge by default, so it shouldn't increment active count.
        assertEquals(1, mDefaultHandler.activeCount());
        assertEquals(0, mDefaultHandler.mergeCount());
        verify(mOrganizer, times(0)).finishTransition(eq(transitToken1), any(), any());
        verify(mOrganizer, times(0)).finishTransition(eq(transitToken2), any(), any());

        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();
        // first transition finished
        verify(mOrganizer, times(1)).finishTransition(eq(transitToken1), any(), any());
        verify(mOrganizer, times(0)).finishTransition(eq(transitToken2), any(), any());
        // But now the "queued" transition is running
        assertEquals(1, mDefaultHandler.activeCount());

        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();
        verify(mOrganizer, times(1)).finishTransition(eq(transitToken2), any(), any());
    }

    @Test
    public void testTransitionMerging() {
        Transitions transitions = createTestTransitions();
        mDefaultHandler.setSimulateMerge(true);
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        IBinder transitToken1 = new Binder();
        transitions.requestStartTransition(transitToken1,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        TransitionInfo info1 = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        transitions.onTransitionReady(transitToken1, info1, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        assertEquals(1, mDefaultHandler.activeCount());

        IBinder transitToken2 = new Binder();
        transitions.requestStartTransition(transitToken2,
                new TransitionRequestInfo(TRANSIT_CLOSE, null /* trigger */, null /* remote */));
        TransitionInfo info2 = new TransitionInfoBuilder(TRANSIT_CLOSE)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        transitions.onTransitionReady(transitToken2, info2, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        // it should still only have 1 active, but then show 1 merged
        assertEquals(1, mDefaultHandler.activeCount());
        assertEquals(1, mDefaultHandler.mergeCount());
        verify(mOrganizer, times(0)).finishTransition(eq(transitToken1), any(), any());
        // We don't tell organizer it is finished yet (since we still want to maintain ordering)
        verify(mOrganizer, times(0)).finishTransition(eq(transitToken2), any(), any());

        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();
        // transition + merged all finished.
        verify(mOrganizer, times(1)).finishTransition(eq(transitToken1), any(), any());
        verify(mOrganizer, times(1)).finishTransition(eq(transitToken2), any(), any());
        // Make sure nothing was queued
        assertEquals(0, mDefaultHandler.activeCount());
    }

    @Test
    public void testShouldRotateSeamlessly() throws Exception {
        final RunningTaskInfo taskInfo =
                createTaskInfo(1, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final RunningTaskInfo taskInfoPip =
                createTaskInfo(1, WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD);

        final DisplayController displays = createTestDisplayController();
        final @Surface.Rotation int upsideDown = displays
                .getDisplayLayout(DEFAULT_DISPLAY).getUpsideDownRotation();

        TransitionInfo.Change displayChange = new ChangeBuilder(TRANSIT_CHANGE)
                .setFlags(FLAG_IS_DISPLAY).setRotate().build();
        // Set non-square display so nav bar won't be allowed to move.
        displayChange.getStartAbsBounds().set(0, 0, 1000, 2000);
        final TransitionInfo normalDispRotate = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(displayChange)
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfo).setRotate().build())
                .build();
        assertEquals(ROTATION_ANIMATION_ROTATE, DefaultTransitionHandler.getRotationAnimationHint(
                displayChange, normalDispRotate, displays));

        // Seamless if all tasks are seamless
        final TransitionInfo rotateSeamless = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(displayChange)
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfo)
                        .setRotate(ROTATION_ANIMATION_SEAMLESS).build())
                .build();
        assertEquals(ROTATION_ANIMATION_SEAMLESS, DefaultTransitionHandler.getRotationAnimationHint(
                displayChange, rotateSeamless, displays));

        // Not seamless if there is PiP (or any other non-seamless task)
        final TransitionInfo pipDispRotate = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(displayChange)
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfo)
                        .setRotate(ROTATION_ANIMATION_SEAMLESS).build())
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfoPip)
                        .setRotate().build())
                .build();
        assertEquals(ROTATION_ANIMATION_ROTATE, DefaultTransitionHandler.getRotationAnimationHint(
                displayChange, pipDispRotate, displays));

        // Not seamless if there is no changed task.
        final TransitionInfo noTask = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(displayChange)
                .build();
        assertEquals(ROTATION_ANIMATION_ROTATE, DefaultTransitionHandler.getRotationAnimationHint(
                displayChange, noTask, displays));

        // Not seamless if one of rotations is upside-down
        displayChange = new ChangeBuilder(TRANSIT_CHANGE).setFlags(FLAG_IS_DISPLAY)
                .setRotate(upsideDown, ROTATION_ANIMATION_UNSPECIFIED).build();
        final TransitionInfo seamlessUpsideDown = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(displayChange)
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfo)
                        .setRotate(upsideDown, ROTATION_ANIMATION_SEAMLESS).build())
                .build();
        assertEquals(ROTATION_ANIMATION_ROTATE, DefaultTransitionHandler.getRotationAnimationHint(
                displayChange, seamlessUpsideDown, displays));

        // Not seamless if system alert windows
        displayChange = new ChangeBuilder(TRANSIT_CHANGE)
                .setFlags(FLAG_IS_DISPLAY | FLAG_DISPLAY_HAS_ALERT_WINDOWS).setRotate().build();
        final TransitionInfo seamlessButAlert = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(displayChange)
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfo)
                        .setRotate(ROTATION_ANIMATION_SEAMLESS).build())
                .build();
        assertEquals(ROTATION_ANIMATION_ROTATE, DefaultTransitionHandler.getRotationAnimationHint(
                displayChange, seamlessButAlert, displays));

        // Seamless if display is explicitly seamless.
        displayChange = new ChangeBuilder(TRANSIT_CHANGE).setFlags(FLAG_IS_DISPLAY)
                .setRotate(ROTATION_ANIMATION_SEAMLESS).build();
        final TransitionInfo seamlessDisplay = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(displayChange)
                // The animation hint of task will be ignored.
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfo)
                        .setRotate(ROTATION_ANIMATION_ROTATE).build())
                .build();
        assertEquals(ROTATION_ANIMATION_SEAMLESS, DefaultTransitionHandler.getRotationAnimationHint(
                displayChange, seamlessDisplay, displays));
    }

    @Test
    public void testRunWhenIdle() {
        Transitions transitions = createTestTransitions();
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        Runnable runnable1 = mock(Runnable.class);
        Runnable runnable2 = mock(Runnable.class);
        Runnable runnable3 = mock(Runnable.class);
        Runnable runnable4 = mock(Runnable.class);

        transitions.runOnIdle(runnable1);

        // runnable1 is executed immediately because there are no active transitions.
        verify(runnable1, times(1)).run();

        clearInvocations(runnable1);

        IBinder transitToken1 = new Binder();
        transitions.requestStartTransition(transitToken1,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        TransitionInfo info1 = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        transitions.onTransitionReady(transitToken1, info1, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        assertEquals(1, mDefaultHandler.activeCount());

        transitions.runOnIdle(runnable2);
        transitions.runOnIdle(runnable3);

        // runnable2 and runnable3 aren't executed immediately because there is an active
        // transaction.

        IBinder transitToken2 = new Binder();
        transitions.requestStartTransition(transitToken2,
                new TransitionRequestInfo(TRANSIT_CLOSE, null /* trigger */, null /* remote */));
        TransitionInfo info2 = new TransitionInfoBuilder(TRANSIT_CLOSE)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        transitions.onTransitionReady(transitToken2, info2, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        assertEquals(1, mDefaultHandler.activeCount());

        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();
        // first transition finished
        verify(mOrganizer, times(1)).finishTransition(eq(transitToken1), any(), any());
        verify(mOrganizer, times(0)).finishTransition(eq(transitToken2), any(), any());
        // But now the "queued" transition is running
        assertEquals(1, mDefaultHandler.activeCount());

        // runnable2 and runnable3 are still not executed because the second transition is still
        // active.
        verify(runnable2, times(0)).run();
        verify(runnable3, times(0)).run();

        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();
        verify(mOrganizer, times(1)).finishTransition(eq(transitToken2), any(), any());

        // runnable2 and runnable3 are executed after the second transition finishes because there
        // are no other active transitions, runnable1 isn't executed again.
        verify(runnable1, times(0)).run();
        verify(runnable2, times(1)).run();
        verify(runnable3, times(1)).run();

        clearInvocations(runnable2);
        clearInvocations(runnable3);

        transitions.runOnIdle(runnable4);

        // runnable4 is executed immediately because there are no active transitions, all other
        // runnables aren't executed again.
        verify(runnable1, times(0)).run();
        verify(runnable2, times(0)).run();
        verify(runnable3, times(0)).run();
        verify(runnable4, times(1)).run();
    }

    @Test
    public void testObserverLifecycle_basicTransitionFlow() {
        Transitions transitions = createTestTransitions();
        Transitions.TransitionObserver observer = mock(Transitions.TransitionObserver.class);
        transitions.registerObserver(observer);
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        IBinder transitToken = new Binder();
        transitions.requestStartTransition(transitToken,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        TransitionInfo info = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        transitions.onTransitionReady(transitToken, info, startT, finishT);

        InOrder observerOrder = inOrder(observer);
        observerOrder.verify(observer).onTransitionReady(transitToken, info, startT, finishT);
        observerOrder.verify(observer).onTransitionStarting(transitToken);
        verify(observer, times(0)).onTransitionFinished(eq(transitToken), anyBoolean());
        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();
        verify(observer).onTransitionFinished(transitToken, false);
    }

    @Test
    public void testObserverLifecycle_queueing() {
        Transitions transitions = createTestTransitions();
        Transitions.TransitionObserver observer = mock(Transitions.TransitionObserver.class);
        transitions.registerObserver(observer);
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        IBinder transitToken1 = new Binder();
        transitions.requestStartTransition(transitToken1,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        TransitionInfo info1 = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        SurfaceControl.Transaction startT1 = mock(SurfaceControl.Transaction.class);
        SurfaceControl.Transaction finishT1 = mock(SurfaceControl.Transaction.class);
        transitions.onTransitionReady(transitToken1, info1, startT1, finishT1);
        verify(observer).onTransitionReady(transitToken1, info1, startT1, finishT1);

        IBinder transitToken2 = new Binder();
        transitions.requestStartTransition(transitToken2,
                new TransitionRequestInfo(TRANSIT_CLOSE, null /* trigger */, null /* remote */));
        TransitionInfo info2 = new TransitionInfoBuilder(TRANSIT_CLOSE)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        SurfaceControl.Transaction startT2 = mock(SurfaceControl.Transaction.class);
        SurfaceControl.Transaction finishT2 = mock(SurfaceControl.Transaction.class);
        transitions.onTransitionReady(transitToken2, info2, startT2, finishT2);
        verify(observer, times(1)).onTransitionReady(transitToken2, info2, startT2, finishT2);
        verify(observer, times(0)).onTransitionStarting(transitToken2);
        verify(observer, times(0)).onTransitionFinished(eq(transitToken1), anyBoolean());
        verify(observer, times(0)).onTransitionFinished(eq(transitToken2), anyBoolean());

        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();
        // first transition finished
        verify(observer, times(1)).onTransitionFinished(transitToken1, false);
        verify(observer, times(1)).onTransitionStarting(transitToken2);
        verify(observer, times(0)).onTransitionFinished(eq(transitToken2), anyBoolean());

        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();
        verify(observer, times(1)).onTransitionFinished(transitToken2, false);
    }


    @Test
    public void testObserverLifecycle_merging() {
        Transitions transitions = createTestTransitions();
        Transitions.TransitionObserver observer = mock(Transitions.TransitionObserver.class);
        transitions.registerObserver(observer);
        mDefaultHandler.setSimulateMerge(true);
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        IBinder transitToken1 = new Binder();
        transitions.requestStartTransition(transitToken1,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        TransitionInfo info1 = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        SurfaceControl.Transaction startT1 = mock(SurfaceControl.Transaction.class);
        SurfaceControl.Transaction finishT1 = mock(SurfaceControl.Transaction.class);
        transitions.onTransitionReady(transitToken1, info1, startT1, finishT1);

        IBinder transitToken2 = new Binder();
        transitions.requestStartTransition(transitToken2,
                new TransitionRequestInfo(TRANSIT_CLOSE, null /* trigger */, null /* remote */));
        TransitionInfo info2 = new TransitionInfoBuilder(TRANSIT_CLOSE)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        SurfaceControl.Transaction startT2 = mock(SurfaceControl.Transaction.class);
        SurfaceControl.Transaction finishT2 = mock(SurfaceControl.Transaction.class);
        transitions.onTransitionReady(transitToken2, info2, startT2, finishT2);

        InOrder observerOrder = inOrder(observer);
        observerOrder.verify(observer).onTransitionReady(transitToken2, info2, startT2, finishT2);
        observerOrder.verify(observer).onTransitionMerged(transitToken2, transitToken1);
        verify(observer, times(0)).onTransitionFinished(eq(transitToken1), anyBoolean());

        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();
        // transition + merged all finished.
        verify(observer, times(1)).onTransitionFinished(transitToken1, false);
        // Merged transition won't receive any lifecycle calls beyond ready
        verify(observer, times(0)).onTransitionStarting(transitToken2);
        verify(observer, times(0)).onTransitionFinished(eq(transitToken2), anyBoolean());
    }

    @Test
    public void testObserverLifecycle_mergingAfterQueueing() {
        Transitions transitions = createTestTransitions();
        Transitions.TransitionObserver observer = mock(Transitions.TransitionObserver.class);
        transitions.registerObserver(observer);
        mDefaultHandler.setSimulateMerge(true);
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        // Make a test handler that only responds to multi-window triggers AND only animates
        // Change transitions.
        final WindowContainerTransaction handlerWCT = new WindowContainerTransaction();
        TestTransitionHandler testHandler = new TestTransitionHandler() {
            @Override
            public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                    @NonNull SurfaceControl.Transaction startTransaction,
                    @NonNull SurfaceControl.Transaction finishTransaction,
                    @NonNull Transitions.TransitionFinishCallback finishCallback) {
                for (TransitionInfo.Change chg : info.getChanges()) {
                    if (chg.getMode() == TRANSIT_CHANGE) {
                        return super.startAnimation(transition, info, startTransaction,
                                finishTransaction, finishCallback);
                    }
                }
                return false;
            }

            @Nullable
            @Override
            public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                    @NonNull TransitionRequestInfo request) {
                final RunningTaskInfo task = request.getTriggerTask();
                return (task != null && task.getWindowingMode() == WINDOWING_MODE_MULTI_WINDOW)
                        ? handlerWCT : null;
            }
        };
        transitions.addHandler(testHandler);

        // Use test handler to play an animation
        IBinder transitToken1 = new Binder();
        RunningTaskInfo mwTaskInfo =
                createTaskInfo(1, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        transitions.requestStartTransition(transitToken1,
                new TransitionRequestInfo(TRANSIT_CHANGE, mwTaskInfo, null /* remote */));
        TransitionInfo change = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(TRANSIT_CHANGE).build();
        SurfaceControl.Transaction startT1 = mock(SurfaceControl.Transaction.class);
        SurfaceControl.Transaction finishT1 = mock(SurfaceControl.Transaction.class);
        transitions.onTransitionReady(transitToken1, change, startT1, finishT1);

        // Request the second transition that should be handled by the default handler
        IBinder transitToken2 = new Binder();
        TransitionInfo open = new TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN).addChange(TRANSIT_CLOSE).build();
        transitions.requestStartTransition(transitToken2,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        SurfaceControl.Transaction startT2 = mock(SurfaceControl.Transaction.class);
        SurfaceControl.Transaction finishT2 = mock(SurfaceControl.Transaction.class);
        transitions.onTransitionReady(transitToken2, open, startT2, finishT2);
        verify(observer).onTransitionReady(transitToken2, open, startT2, finishT2);
        verify(observer, times(0)).onTransitionStarting(transitToken2);

        // Request the third transition that should be merged into the second one
        IBinder transitToken3 = new Binder();
        transitions.requestStartTransition(transitToken3,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        SurfaceControl.Transaction startT3 = mock(SurfaceControl.Transaction.class);
        SurfaceControl.Transaction finishT3 = mock(SurfaceControl.Transaction.class);
        transitions.onTransitionReady(transitToken3, open, startT3, finishT3);
        verify(observer, times(0)).onTransitionStarting(transitToken2);
        verify(observer).onTransitionReady(transitToken3, open, startT3, finishT3);
        verify(observer, times(0)).onTransitionStarting(transitToken3);

        testHandler.finishAll();
        mMainExecutor.flushAll();

        verify(observer).onTransitionFinished(transitToken1, false);

        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();

        InOrder observerOrder = inOrder(observer);
        observerOrder.verify(observer).onTransitionStarting(transitToken2);
        observerOrder.verify(observer).onTransitionMerged(transitToken3, transitToken2);
        observerOrder.verify(observer).onTransitionFinished(transitToken2, false);

        // Merged transition won't receive any lifecycle calls beyond ready
        verify(observer, times(0)).onTransitionStarting(transitToken3);
        verify(observer, times(0)).onTransitionFinished(eq(transitToken3), anyBoolean());
    }

    class TransitionInfoBuilder {
        final TransitionInfo mInfo;

        TransitionInfoBuilder(@WindowManager.TransitionType int type) {
            this(type, 0 /* flags */);
        }

        TransitionInfoBuilder(@WindowManager.TransitionType int type,
                @WindowManager.TransitionFlags int flags) {
            mInfo = new TransitionInfo(type, flags);
            mInfo.setRootLeash(createMockSurface(true /* valid */), 0, 0);
        }

        TransitionInfoBuilder addChange(@WindowManager.TransitionType int mode,
                RunningTaskInfo taskInfo) {
            final TransitionInfo.Change change =
                    new TransitionInfo.Change(null /* token */, createMockSurface(true));
            change.setMode(mode);
            change.setTaskInfo(taskInfo);
            mInfo.addChange(change);
            return this;
        }

        TransitionInfoBuilder addChange(@WindowManager.TransitionType int mode) {
            return addChange(mode, null /* taskInfo */);
        }

        TransitionInfoBuilder addChange(TransitionInfo.Change change) {
            mInfo.addChange(change);
            return this;
        }

        TransitionInfo build() {
            return mInfo;
        }
    }

    class ChangeBuilder {
        final TransitionInfo.Change mChange;

        ChangeBuilder(@WindowManager.TransitionType int mode) {
            mChange = new TransitionInfo.Change(null /* token */, createMockSurface(true));
            mChange.setMode(mode);
        }

        ChangeBuilder setFlags(@TransitionInfo.ChangeFlags int flags) {
            mChange.setFlags(flags);
            return this;
        }

        ChangeBuilder setTask(RunningTaskInfo taskInfo) {
            mChange.setTaskInfo(taskInfo);
            return this;
        }

        ChangeBuilder setRotate(int anim) {
            return setRotate(Surface.ROTATION_90, anim);
        }

        ChangeBuilder setRotate() {
            return setRotate(ROTATION_ANIMATION_UNSPECIFIED);
        }

        ChangeBuilder setRotate(@Surface.Rotation int target, int anim) {
            mChange.setRotation(Surface.ROTATION_0, target);
            mChange.setRotationAnimation(anim);
            return this;
        }

        TransitionInfo.Change build() {
            return mChange;
        }
    }

    class TestTransitionHandler implements Transitions.TransitionHandler {
        ArrayList<Transitions.TransitionFinishCallback> mFinishes = new ArrayList<>();
        final ArrayList<IBinder> mMerged = new ArrayList<>();
        boolean mSimulateMerge = false;

        @Override
        public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            mFinishes.add(finishCallback);
            return true;
        }

        @Override
        public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            if (!mSimulateMerge) return;
            mMerged.add(transition);
            finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
        }

        @Nullable
        @Override
        public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @NonNull TransitionRequestInfo request) {
            return null;
        }

        void setSimulateMerge(boolean sim) {
            mSimulateMerge = sim;
        }

        void finishAll() {
            final ArrayList<Transitions.TransitionFinishCallback> finishes = mFinishes;
            mFinishes = new ArrayList<>();
            for (int i = finishes.size() - 1; i >= 0; --i) {
                finishes.get(i).onTransitionFinished(null /* wct */, null /* wctCB */);
            }
        }

        int activeCount() {
            return mFinishes.size();
        }

        int mergeCount() {
            return mMerged.size();
        }
    }

    private static SurfaceControl createMockSurface(boolean valid) {
        SurfaceControl sc = mock(SurfaceControl.class);
        doReturn(valid).when(sc).isValid();
        return sc;
    }

    private static RunningTaskInfo createTaskInfo(int taskId, int windowingMode, int activityType) {
        RunningTaskInfo taskInfo = new RunningTaskInfo();
        taskInfo.taskId = taskId;
        taskInfo.configuration.windowConfiguration.setWindowingMode(windowingMode);
        taskInfo.configuration.windowConfiguration.setActivityType(activityType);
        return taskInfo;
    }

    private static RunningTaskInfo createTaskInfo(int taskId) {
        RunningTaskInfo taskInfo = new RunningTaskInfo();
        taskInfo.taskId = taskId;
        return taskInfo;
    }

    private DisplayController createTestDisplayController() {
        DisplayLayout displayLayout = mock(DisplayLayout.class);
        doReturn(Surface.ROTATION_180).when(displayLayout).getUpsideDownRotation();
        // By default we ignore nav bar in deciding if a seamless rotation is allowed.
        doReturn(true).when(displayLayout).allowSeamlessRotationDespiteNavBarMoving();

        DisplayController out = mock(DisplayController.class);
        doReturn(displayLayout).when(out).getDisplayLayout(DEFAULT_DISPLAY);
        return out;
    }

    private Transitions createTestTransitions() {
        ShellInit shellInit = new ShellInit(mMainExecutor);
        final Transitions t = new Transitions(mContext, shellInit, mock(ShellController.class),
                mOrganizer, mTransactionPool, createTestDisplayController(), mMainExecutor,
                mMainHandler, mAnimExecutor);
        shellInit.init();
        return t;
    }
}
