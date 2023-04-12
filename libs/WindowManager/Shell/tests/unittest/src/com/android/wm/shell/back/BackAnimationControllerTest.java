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

package com.android.wm.shell.back;

import static android.window.BackNavigationInfo.KEY_TRIGGER_BACK;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.IActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.pm.ApplicationInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContentResolver;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.view.IRemoteAnimationRunner;
import android.view.MotionEvent;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.BackEvent;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.IBackAnimationFinishedCallback;
import android.window.IOnBackInvokedCallback;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.sysui.ShellSharedConstants;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * atest WMShellUnitTests:BackAnimationControllerTest
 */
@TestableLooper.RunWithLooper
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class BackAnimationControllerTest extends ShellTestCase {

    private static final String ANIMATION_ENABLED = "1";
    private final TestShellExecutor mShellExecutor = new TestShellExecutor();
    private ShellInit mShellInit;

    @Rule
    public TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getInstrumentation().getContext());

    @Mock
    private IActivityTaskManager mActivityTaskManager;

    @Mock
    private IOnBackInvokedCallback mAppCallback;

    @Mock
    private IOnBackInvokedCallback mAnimatorCallback;

    @Mock
    private IBackAnimationFinishedCallback mBackAnimationFinishedCallback;

    @Mock
    private IRemoteAnimationRunner mBackAnimationRunner;

    @Mock
    private ShellController mShellController;

    @Mock
    private BackAnimationBackground mAnimationBackground;

    private BackAnimationController mController;
    private TestableContentResolver mContentResolver;
    private TestableLooper mTestableLooper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext.getApplicationInfo().privateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
        mContentResolver = new TestableContentResolver(mContext);
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        Settings.Global.putString(mContentResolver, Settings.Global.ENABLE_BACK_ANIMATION,
                ANIMATION_ENABLED);
        mTestableLooper = TestableLooper.get(this);
        mShellInit = spy(new ShellInit(mShellExecutor));
        mController = new BackAnimationController(mShellInit, mShellController,
                mShellExecutor, new Handler(mTestableLooper.getLooper()),
                mActivityTaskManager, mContext,
                mContentResolver, mAnimationBackground);
        mController.setEnableUAnimation(true);
        mShellInit.init();
        mShellExecutor.flushAll();
    }

    private void createNavigationInfo(int backType, boolean enableAnimation) {
        BackNavigationInfo.Builder builder = new BackNavigationInfo.Builder()
                .setType(backType)
                .setOnBackNavigationDone(new RemoteCallback((bundle) -> {}))
                .setOnBackInvokedCallback(mAppCallback)
                .setPrepareRemoteAnimation(enableAnimation);

        createNavigationInfo(builder);
    }

    private void createNavigationInfo(BackNavigationInfo.Builder builder) {
        try {
            doReturn(builder.build()).when(mActivityTaskManager)
                    .startBackNavigation(any(), any());
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
    }

    RemoteAnimationTarget createAnimationTarget() {
        SurfaceControl topWindowLeash = new SurfaceControl();
        return new RemoteAnimationTarget(-1, RemoteAnimationTarget.MODE_CLOSING, topWindowLeash,
                false, new Rect(), new Rect(), -1,
                new Point(0, 0), new Rect(), new Rect(), new WindowConfiguration(),
                true, null, null, null, false, -1);
    }

    private void triggerBackGesture() {
        doMotionEvent(MotionEvent.ACTION_DOWN, 0);
        doMotionEvent(MotionEvent.ACTION_MOVE, 0);
        mController.setTriggerBack(true);
    }

    private void releaseBackGesture() {
        doMotionEvent(MotionEvent.ACTION_UP, 0);
    }

    @Test
    public void instantiateController_addInitCallback() {
        verify(mShellInit, times(1)).addInitCallback(any(), any());
    }

    @Test
    public void instantiateController_addExternalInterface() {
        verify(mShellController, times(1)).addExternalInterface(
                eq(ShellSharedConstants.KEY_EXTRA_SHELL_BACK_ANIMATION), any(), any());
    }

    @Test
    public void verifyNavigationFinishes() throws RemoteException {
        final int[] testTypes = new int[] {BackNavigationInfo.TYPE_RETURN_TO_HOME,
                BackNavigationInfo.TYPE_CROSS_TASK,
                BackNavigationInfo.TYPE_CROSS_ACTIVITY,
                BackNavigationInfo.TYPE_DIALOG_CLOSE,
                BackNavigationInfo.TYPE_CALLBACK };

        for (int type: testTypes) {
            registerAnimation(type);
        }

        for (int type: testTypes) {
            final ResultListener result  = new ResultListener();
            createNavigationInfo(new BackNavigationInfo.Builder()
                    .setType(type)
                    .setOnBackInvokedCallback(mAppCallback)
                    .setPrepareRemoteAnimation(true)
                    .setOnBackNavigationDone(new RemoteCallback(result)));
            triggerBackGesture();
            simulateRemoteAnimationStart(type);
            mShellExecutor.flushAll();
            releaseBackGesture();
            simulateRemoteAnimationFinished();
            mShellExecutor.flushAll();

            assertTrue("Navigation Done callback not called for "
                    + BackNavigationInfo.typeToString(type), result.mBackNavigationDone);
            assertTrue("TriggerBack should have been true", result.mTriggerBack);
        }
    }

    @Test
    public void backToHome_dispatchesEvents() throws RemoteException {
        registerAnimation(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        createNavigationInfo(BackNavigationInfo.TYPE_RETURN_TO_HOME, true);

        doMotionEvent(MotionEvent.ACTION_DOWN, 0);

        // Check that back start and progress is dispatched when first move.
        doMotionEvent(MotionEvent.ACTION_MOVE, 100);

        simulateRemoteAnimationStart(BackNavigationInfo.TYPE_RETURN_TO_HOME);

        verify(mAnimatorCallback).onBackStarted(any(BackMotionEvent.class));
        verify(mBackAnimationRunner).onAnimationStart(anyInt(), any(), any(), any(), any());
        ArgumentCaptor<BackMotionEvent> backEventCaptor =
                ArgumentCaptor.forClass(BackMotionEvent.class);
        verify(mAnimatorCallback, atLeastOnce()).onBackProgressed(backEventCaptor.capture());

        // Check that back invocation is dispatched.
        mController.setTriggerBack(true);   // Fake trigger back
        doMotionEvent(MotionEvent.ACTION_UP, 0);
        verify(mAnimatorCallback).onBackInvoked();
    }

    @Test
    public void animationDisabledFromSettings() throws RemoteException {
        // Toggle the setting off
        Settings.Global.putString(mContentResolver, Settings.Global.ENABLE_BACK_ANIMATION, "0");
        ShellInit shellInit = new ShellInit(mShellExecutor);
        mController = new BackAnimationController(shellInit, mShellController,
                mShellExecutor, new Handler(mTestableLooper.getLooper()),
                mActivityTaskManager, mContext,
                mContentResolver, mAnimationBackground);
        shellInit.init();
        registerAnimation(BackNavigationInfo.TYPE_RETURN_TO_HOME);

        ArgumentCaptor<BackMotionEvent> backEventCaptor =
                ArgumentCaptor.forClass(BackMotionEvent.class);

        createNavigationInfo(BackNavigationInfo.TYPE_RETURN_TO_HOME, false);

        triggerBackGesture();
        releaseBackGesture();

        verify(mAppCallback, times(1)).onBackInvoked();

        verify(mAnimatorCallback, never()).onBackStarted(any());
        verify(mAnimatorCallback, never()).onBackProgressed(backEventCaptor.capture());
        verify(mAnimatorCallback, never()).onBackInvoked();
        verify(mBackAnimationRunner, never()).onAnimationStart(
                anyInt(), any(), any(), any(), any());
    }

    @Test
    public void ignoresGesture_transitionInProgress() throws RemoteException {
        registerAnimation(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        createNavigationInfo(BackNavigationInfo.TYPE_RETURN_TO_HOME, true);

        triggerBackGesture();
        simulateRemoteAnimationStart(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        releaseBackGesture();

        // Check that back invocation is dispatched.
        verify(mAnimatorCallback).onBackInvoked();
        verify(mBackAnimationRunner).onAnimationStart(anyInt(), any(), any(), any(), any());

        reset(mAnimatorCallback);
        reset(mBackAnimationRunner);

        // Verify that we prevent animation from restarting if another gestures happens before
        // the previous transition is finished.
        doMotionEvent(MotionEvent.ACTION_DOWN, 0);
        verifyNoMoreInteractions(mAnimatorCallback);

        // Finish back navigation.
        simulateRemoteAnimationFinished();

        // Verify that more events from a rejected swipe cannot start animation.
        doMotionEvent(MotionEvent.ACTION_MOVE, 100);
        doMotionEvent(MotionEvent.ACTION_UP, 0);
        verifyNoMoreInteractions(mAnimatorCallback);

        // Verify that we start accepting gestures again once transition finishes.
        doMotionEvent(MotionEvent.ACTION_DOWN, 0);
        doMotionEvent(MotionEvent.ACTION_MOVE, 100);

        simulateRemoteAnimationStart(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        verify(mAnimatorCallback).onBackStarted(any());
        verify(mBackAnimationRunner).onAnimationStart(anyInt(), any(), any(), any(), any());
    }

    @Test
    public void acceptsGesture_transitionTimeout() throws RemoteException {
        registerAnimation(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        createNavigationInfo(BackNavigationInfo.TYPE_RETURN_TO_HOME, true);

        // In case it is still running in animation.
        doNothing().when(mAnimatorCallback).onBackInvoked();

        triggerBackGesture();
        simulateRemoteAnimationStart(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        mShellExecutor.flushAll();

        releaseBackGesture();

        // Simulate transition timeout.
        mShellExecutor.flushAll();
        reset(mAnimatorCallback);

        doMotionEvent(MotionEvent.ACTION_DOWN, 0);
        doMotionEvent(MotionEvent.ACTION_MOVE, 100);
        simulateRemoteAnimationStart(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        verify(mAnimatorCallback).onBackStarted(any());
    }

    @Test
    public void cancelBackInvokeWhenLostFocus() throws RemoteException {
        registerAnimation(BackNavigationInfo.TYPE_RETURN_TO_HOME);

        createNavigationInfo(BackNavigationInfo.TYPE_RETURN_TO_HOME, true);

        doMotionEvent(MotionEvent.ACTION_DOWN, 0);
        // Check that back start and progress is dispatched when first move.
        doMotionEvent(MotionEvent.ACTION_MOVE, 100);

        simulateRemoteAnimationStart(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        verify(mAnimatorCallback).onBackStarted(any());
        verify(mBackAnimationRunner).onAnimationStart(anyInt(), any(), any(), any(), any());

        // Check that back invocation is dispatched.
        mController.setTriggerBack(true);   // Fake trigger back

        // In case the focus has been changed.
        mController.mNavigationObserver.sendResult(null);
        mShellExecutor.flushAll();
        verify(mAnimatorCallback).onBackCancelled();

        // No more back invoke.
        doMotionEvent(MotionEvent.ACTION_UP, 0);
        verify(mAnimatorCallback, never()).onBackInvoked();
    }

    @Test
    public void animationNotDefined() throws RemoteException {
        final int[] testTypes = new int[] {
                BackNavigationInfo.TYPE_RETURN_TO_HOME,
                BackNavigationInfo.TYPE_CROSS_TASK,
                BackNavigationInfo.TYPE_CROSS_ACTIVITY,
                BackNavigationInfo.TYPE_DIALOG_CLOSE};

        for (int type: testTypes) {
            unregisterAnimation(type);
        }

        for (int type: testTypes) {
            final ResultListener result = new ResultListener();
            createNavigationInfo(new BackNavigationInfo.Builder()
                    .setType(type)
                    .setOnBackInvokedCallback(mAppCallback)
                    .setPrepareRemoteAnimation(true)
                    .setOnBackNavigationDone(new RemoteCallback(result)));
            triggerBackGesture();
            simulateRemoteAnimationStart(type);
            mShellExecutor.flushAll();

            releaseBackGesture();
            mShellExecutor.flushAll();

            assertTrue("Navigation Done callback not called for "
                    + BackNavigationInfo.typeToString(type), result.mBackNavigationDone);
            assertTrue("TriggerBack should have been true", result.mTriggerBack);
        }

        verify(mAppCallback, never()).onBackStarted(any());
        verify(mAppCallback, never()).onBackProgressed(any());
        verify(mAppCallback, times(testTypes.length)).onBackInvoked();

        verify(mAnimatorCallback, never()).onBackStarted(any());
        verify(mAnimatorCallback, never()).onBackProgressed(any());
        verify(mAnimatorCallback, never()).onBackInvoked();
    }

    @Test
    public void callbackShouldDeliverProgress() throws RemoteException {
        registerAnimation(BackNavigationInfo.TYPE_RETURN_TO_HOME);

        final int type = BackNavigationInfo.TYPE_CALLBACK;
        final ResultListener result = new ResultListener();
        createNavigationInfo(new BackNavigationInfo.Builder()
                .setType(type)
                .setOnBackInvokedCallback(mAppCallback)
                .setOnBackNavigationDone(new RemoteCallback(result)));
        triggerBackGesture();
        mShellExecutor.flushAll();
        releaseBackGesture();
        mShellExecutor.flushAll();

        assertTrue("Navigation Done callback not called for "
                + BackNavigationInfo.typeToString(type), result.mBackNavigationDone);
        assertTrue("TriggerBack should have been true", result.mTriggerBack);

        verify(mAppCallback, times(1)).onBackStarted(any());
        verify(mAppCallback, times(1)).onBackProgressed(any());
        verify(mAppCallback, times(1)).onBackInvoked();

        verify(mAnimatorCallback, never()).onBackStarted(any());
        verify(mAnimatorCallback, never()).onBackProgressed(any());
        verify(mAnimatorCallback, never()).onBackInvoked();
    }

    @Test
    public void testBackToActivity() throws RemoteException {
        final CrossActivityAnimation animation = new CrossActivityAnimation(mContext,
                mAnimationBackground);
        verifySystemBackBehavior(
                BackNavigationInfo.TYPE_CROSS_ACTIVITY, animation.mBackAnimationRunner);
    }

    @Test
    public void testBackToTask() throws RemoteException {
        final CrossTaskBackAnimation animation = new CrossTaskBackAnimation(mContext,
                mAnimationBackground);
        verifySystemBackBehavior(
                BackNavigationInfo.TYPE_CROSS_TASK, animation.mBackAnimationRunner);
    }

    private void verifySystemBackBehavior(int type, BackAnimationRunner animation)
            throws RemoteException {
        final BackAnimationRunner animationRunner = spy(animation);
        final IRemoteAnimationRunner runner = spy(animationRunner.getRunner());
        final IOnBackInvokedCallback callback = spy(animationRunner.getCallback());

        // Set up the monitoring objects.
        doNothing().when(runner).onAnimationStart(anyInt(), any(), any(), any(), any());
        doReturn(runner).when(animationRunner).getRunner();
        doReturn(callback).when(animationRunner).getCallback();

        mController.registerAnimation(type, animationRunner);

        createNavigationInfo(type, true);

        doMotionEvent(MotionEvent.ACTION_DOWN, 0);

        // Check that back start and progress is dispatched when first move.
        doMotionEvent(MotionEvent.ACTION_MOVE, 100);

        simulateRemoteAnimationStart(type);

        verify(callback).onBackStarted(any(BackMotionEvent.class));
        verify(animationRunner).startAnimation(any(), any(), any(), any());

        // Check that back invocation is dispatched.
        mController.setTriggerBack(true);   // Fake trigger back
        doMotionEvent(MotionEvent.ACTION_UP, 0);
        verify(callback).onBackInvoked();
    }

    private void doMotionEvent(int actionDown, int coordinate) {
        mController.onMotionEvent(
                coordinate, coordinate,
                actionDown,
                BackEvent.EDGE_LEFT);
    }

    private void simulateRemoteAnimationStart(int type) throws RemoteException {
        RemoteAnimationTarget animationTarget = createAnimationTarget();
        RemoteAnimationTarget[] targets = new RemoteAnimationTarget[]{animationTarget};
        if (mController.mBackAnimationAdapter != null) {
            mController.mBackAnimationAdapter.getRunner().onAnimationStart(
                    targets, null, null, mBackAnimationFinishedCallback);
            mShellExecutor.flushAll();
        }
    }

    private void simulateRemoteAnimationFinished() {
        mController.onBackAnimationFinished();
        mController.finishBackNavigation();
    }

    private void registerAnimation(int type) {
        mController.registerAnimation(type,
                new BackAnimationRunner(mAnimatorCallback, mBackAnimationRunner));
    }

    private void unregisterAnimation(int type) {
        mController.unregisterAnimation(type);
    }

    private static class ResultListener implements RemoteCallback.OnResultListener {
        boolean mBackNavigationDone = false;
        boolean mTriggerBack = false;
        @Override
        public void onResult(@Nullable Bundle result) {
            mBackNavigationDone = true;
            mTriggerBack = result.getBoolean(KEY_TRIGGER_BACK);
        }
    };
}
