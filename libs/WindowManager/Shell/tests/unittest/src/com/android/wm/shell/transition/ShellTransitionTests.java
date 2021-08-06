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
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_UNSPECIFIED;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FIRST_CUSTOM;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.IDisplayWindowListener;
import android.view.IWindowManager;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.TransitionFilter;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;
import android.window.WindowOrganizer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;

/**
 * Tests for the shell transitions.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ShellTransitionTests {

    private final WindowOrganizer mOrganizer = mock(WindowOrganizer.class);
    private final TransactionPool mTransactionPool = mock(TransactionPool.class);
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final TestShellExecutor mMainExecutor = new TestShellExecutor();
    private final ShellExecutor mAnimExecutor = new TestShellExecutor();
    private final TestTransitionHandler mDefaultHandler = new TestTransitionHandler();

    @Before
    public void setUp() {
        doAnswer(invocation -> invocation.getArguments()[1])
                .when(mOrganizer).startTransition(anyInt(), any(), any());
    }

    @Test
    public void testBasicTransitionFlow() {
        Transitions transitions = createTestTransitions();
        transitions.replaceDefaultHandlerForTest(mDefaultHandler);

        IBinder transitToken = new Binder();
        transitions.requestStartTransition(transitToken,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        verify(mOrganizer, times(1)).startTransition(eq(TRANSIT_OPEN), eq(transitToken), any());
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
        verify(mOrganizer, times(1)).startTransition(eq(TRANSIT_OPEN), eq(transitToken), isNull());
        transitions.onTransitionReady(transitToken, open, mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class));
        assertEquals(1, mDefaultHandler.activeCount());
        assertEquals(0, testHandler.activeCount());
        mDefaultHandler.finishAll();
        mMainExecutor.flushAll();

        // Make a request that will be handled by testhandler but not animated by it.
        RunningTaskInfo mwTaskInfo =
                createTaskInfo(1, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        transitions.requestStartTransition(transitToken,
                new TransitionRequestInfo(TRANSIT_OPEN, mwTaskInfo, null /* remote */));
        verify(mOrganizer, times(1)).startTransition(
                eq(TRANSIT_OPEN), eq(transitToken), eq(handlerWCT));
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
        verify(mOrganizer, times(1)).startTransition(
                eq(TRANSIT_CHANGE), eq(transitToken), eq(handlerWCT));
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
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, testRemote));
        verify(mOrganizer, times(1)).startTransition(eq(TRANSIT_OPEN), eq(transitToken), any());
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

        transitions.registerRemote(filter, testRemote);
        mMainExecutor.flushAll();

        IBinder transitToken = new Binder();
        transitions.requestStartTransition(transitToken,
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        verify(mOrganizer, times(1)).startTransition(eq(TRANSIT_OPEN), eq(transitToken), any());
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

        OneShotRemoteHandler oneShot = new OneShotRemoteHandler(mMainExecutor, testRemote);
        // Verify that it responds to the remote but not other things.
        IBinder transitToken = new Binder();
        assertNotNull(oneShot.handleRequest(transitToken,
                new TransitionRequestInfo(transitType, null, testRemote)));
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

        final TransitionInfo normalDispRotate = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setFlags(FLAG_IS_DISPLAY).setRotate()
                        .build())
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfo).setRotate().build())
                .build();
        assertFalse(DefaultTransitionHandler.isRotationSeamless(normalDispRotate, displays));

        // Seamless if all tasks are seamless
        final TransitionInfo rotateSeamless = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setFlags(FLAG_IS_DISPLAY).setRotate()
                        .build())
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfo)
                        .setRotate(ROTATION_ANIMATION_SEAMLESS).build())
                .build();
        assertTrue(DefaultTransitionHandler.isRotationSeamless(rotateSeamless, displays));

        // Not seamless if there is PiP (or any other non-seamless task)
        final TransitionInfo pipDispRotate = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setFlags(FLAG_IS_DISPLAY).setRotate()
                        .build())
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfo)
                        .setRotate(ROTATION_ANIMATION_SEAMLESS).build())
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfoPip)
                        .setRotate().build())
                .build();
        assertFalse(DefaultTransitionHandler.isRotationSeamless(pipDispRotate, displays));

        // Not seamless if one of rotations is upside-down
        final TransitionInfo seamlessUpsideDown = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setFlags(FLAG_IS_DISPLAY)
                        .setRotate(upsideDown, ROTATION_ANIMATION_UNSPECIFIED).build())
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfo)
                        .setRotate(upsideDown, ROTATION_ANIMATION_SEAMLESS).build())
                .build();
        assertFalse(DefaultTransitionHandler.isRotationSeamless(seamlessUpsideDown, displays));

        // Not seamless if system alert windows
        final TransitionInfo seamlessButAlert = new TransitionInfoBuilder(TRANSIT_CHANGE)
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setFlags(
                        FLAG_IS_DISPLAY | FLAG_DISPLAY_HAS_ALERT_WINDOWS).setRotate().build())
                .addChange(new ChangeBuilder(TRANSIT_CHANGE).setTask(taskInfo)
                        .setRotate(ROTATION_ANIMATION_SEAMLESS).build())
                .build();
        assertFalse(DefaultTransitionHandler.isRotationSeamless(seamlessButAlert, displays));
    }

    class TransitionInfoBuilder {
        final TransitionInfo mInfo;

        TransitionInfoBuilder(@WindowManager.TransitionType int type) {
            mInfo = new TransitionInfo(type, 0 /* flags */);
            mInfo.setRootLeash(createMockSurface(true /* valid */), 0, 0);
        }

        TransitionInfoBuilder addChange(@WindowManager.TransitionType int mode,
                RunningTaskInfo taskInfo) {
            final TransitionInfo.Change change =
                    new TransitionInfo.Change(null /* token */, null /* leash */);
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
            mChange = new TransitionInfo.Change(null /* token */, null /* leash */);
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
        IWindowManager mockWM = mock(IWindowManager.class);
        final IDisplayWindowListener[] displayListener = new IDisplayWindowListener[1];
        try {
            doAnswer(new Answer() {
                @Override
                public Object answer(InvocationOnMock invocation) {
                    displayListener[0] = invocation.getArgument(0);
                    return null;
                }
            }).when(mockWM).registerDisplayWindowListener(any());
        } catch (RemoteException e) {
            // No remote stuff happening, so this can't be hit
        }
        DisplayController out = new DisplayController(mContext, mockWM, mMainExecutor);
        try {
            displayListener[0].onDisplayAdded(DEFAULT_DISPLAY);
            mMainExecutor.flushAll();
        } catch (RemoteException e) {
            // Again, no remote stuff
        }
        return out;
    }

    private Transitions createTestTransitions() {
        return new Transitions(mOrganizer, mTransactionPool, createTestDisplayController(),
                mContext, mMainExecutor, mAnimExecutor);
    }
//
//    private class TestDisplayController extends DisplayController {
//        private final DisplayLayout mTestDisplayLayout;
//        TestDisplayController() {
//            super(mContext, mock(IWindowManager.class), mMainExecutor);
//            mTestDisplayLayout = new DisplayLayout();
//            mTestDisplayLayout.
//        }
//
//        @Override
//        DisplayLayout
//    }

}
