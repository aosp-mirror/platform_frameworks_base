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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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
import android.os.Handler;
import android.os.IBinder;
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
import android.window.BackNavigationInfo;
import android.window.IBackAnimationFinishedCallback;
import android.window.IOnBackInvokedCallback;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.sysui.ShellInit;

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
    private IOnBackInvokedCallback mIOnBackInvokedCallback;

    @Mock
    private IBackAnimationFinishedCallback mBackAnimationFinishedCallback;

    @Mock
    private IRemoteAnimationRunner mBackAnimationRunner;

    private BackAnimationController mController;

    private int mEventTime = 0;
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
        mController = new BackAnimationController(mShellInit,
                mShellExecutor, new Handler(mTestableLooper.getLooper()),
                mActivityTaskManager, mContext,
                mContentResolver);
        mShellInit.init();
        mEventTime = 0;
        mShellExecutor.flushAll();
    }

    private void createNavigationInfo(int backType, IOnBackInvokedCallback onBackInvokedCallback) {
        BackNavigationInfo.Builder builder = new BackNavigationInfo.Builder()
                .setType(backType)
                .setOnBackNavigationDone(new RemoteCallback((bundle) -> {}))
                .setOnBackInvokedCallback(onBackInvokedCallback)
                .setPrepareRemoteAnimation(true);

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
        doMotionEvent(MotionEvent.ACTION_UP, 0);
    }

    @Test
    public void instantiateController_addInitCallback() {
        verify(mShellInit, times(1)).addInitCallback(any(), any());
    }

    @Test
    public void verifyAnimationFinishes() {
        RemoteAnimationTarget animationTarget = createAnimationTarget();
        boolean[] backNavigationDone = new boolean[]{false};
        boolean[] triggerBack = new boolean[]{false};
        createNavigationInfo(new BackNavigationInfo.Builder()
                .setType(BackNavigationInfo.TYPE_CROSS_ACTIVITY)
                .setOnBackNavigationDone(
                        new RemoteCallback(result -> {
                            backNavigationDone[0] = true;
                            triggerBack[0] = result.getBoolean(KEY_TRIGGER_BACK);
                        })));
        triggerBackGesture();
        assertTrue("Navigation Done callback not called", backNavigationDone[0]);
        assertTrue("TriggerBack should have been true", triggerBack[0]);
    }

    @Test
    public void backToHome_dispatchesEvents() throws RemoteException {
        mController.setBackToLauncherCallback(mIOnBackInvokedCallback, mBackAnimationRunner);
        createNavigationInfo(BackNavigationInfo.TYPE_RETURN_TO_HOME, mIOnBackInvokedCallback);

        doMotionEvent(MotionEvent.ACTION_DOWN, 0);

        // Check that back start and progress is dispatched when first move.
        doMotionEvent(MotionEvent.ACTION_MOVE, 100);

        simulateRemoteAnimationStart(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        verify(mIOnBackInvokedCallback).onBackStarted();
        verify(mBackAnimationRunner).onAnimationStart(anyInt(), any(), any(), any(), any());
        ArgumentCaptor<BackEvent> backEventCaptor = ArgumentCaptor.forClass(BackEvent.class);
        verify(mIOnBackInvokedCallback, atLeastOnce()).onBackProgressed(backEventCaptor.capture());

        // Check that back invocation is dispatched.
        mController.setTriggerBack(true);   // Fake trigger back
        doMotionEvent(MotionEvent.ACTION_UP, 0);
        verify(mIOnBackInvokedCallback).onBackInvoked();
    }

    @Test
    public void animationDisabledFromSettings() throws RemoteException {
        // Toggle the setting off
        Settings.Global.putString(mContentResolver, Settings.Global.ENABLE_BACK_ANIMATION, "0");
        ShellInit shellInit = new ShellInit(mShellExecutor);
        mController = new BackAnimationController(shellInit,
                mShellExecutor, new Handler(mTestableLooper.getLooper()),
                mActivityTaskManager, mContext,
                mContentResolver);
        shellInit.init();
        mController.setBackToLauncherCallback(mIOnBackInvokedCallback, mBackAnimationRunner);

        IOnBackInvokedCallback appCallback = mock(IOnBackInvokedCallback.class);
        ArgumentCaptor<BackEvent> backEventCaptor = ArgumentCaptor.forClass(BackEvent.class);

        createNavigationInfo(BackNavigationInfo.TYPE_RETURN_TO_HOME, appCallback);

        triggerBackGesture();

        verify(appCallback, never()).onBackStarted();
        verify(appCallback, never()).onBackProgressed(backEventCaptor.capture());
        verify(appCallback, times(1)).onBackInvoked();

        verify(mIOnBackInvokedCallback, never()).onBackStarted();
        verify(mIOnBackInvokedCallback, never()).onBackProgressed(backEventCaptor.capture());
        verify(mIOnBackInvokedCallback, never()).onBackInvoked();
        verify(mBackAnimationRunner, never()).onAnimationStart(
                anyInt(), any(), any(), any(), any());
    }

    @Test
    public void ignoresGesture_transitionInProgress() throws RemoteException {
        mController.setBackToLauncherCallback(mIOnBackInvokedCallback, mBackAnimationRunner);
        createNavigationInfo(BackNavigationInfo.TYPE_RETURN_TO_HOME, null);

        triggerBackGesture();
        simulateRemoteAnimationStart(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        // Check that back invocation is dispatched.
        verify(mIOnBackInvokedCallback).onBackInvoked();
        verify(mBackAnimationRunner).onAnimationStart(anyInt(), any(), any(), any(), any());

        reset(mIOnBackInvokedCallback);
        reset(mBackAnimationRunner);

        // Verify that we prevent animation from restarting if another gestures happens before
        // the previous transition is finished.
        doMotionEvent(MotionEvent.ACTION_DOWN, 0);
        verifyNoMoreInteractions(mIOnBackInvokedCallback);
        mController.onBackAnimationFinished();

        // Verify that more events from a rejected swipe cannot start animation.
        doMotionEvent(MotionEvent.ACTION_MOVE, 100);
        doMotionEvent(MotionEvent.ACTION_UP, 0);
        verifyNoMoreInteractions(mIOnBackInvokedCallback);

        // Verify that we start accepting gestures again once transition finishes.
        doMotionEvent(MotionEvent.ACTION_DOWN, 0);
        doMotionEvent(MotionEvent.ACTION_MOVE, 100);

        simulateRemoteAnimationStart(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        verify(mIOnBackInvokedCallback).onBackStarted();
        verify(mBackAnimationRunner).onAnimationStart(anyInt(), any(), any(), any(), any());
    }

    @Test
    public void acceptsGesture_transitionTimeout() throws RemoteException {
        mController.setBackToLauncherCallback(mIOnBackInvokedCallback, mBackAnimationRunner);
        createNavigationInfo(BackNavigationInfo.TYPE_RETURN_TO_HOME, null);

        triggerBackGesture();
        simulateRemoteAnimationStart(BackNavigationInfo.TYPE_RETURN_TO_HOME);

        reset(mIOnBackInvokedCallback);

        // Simulate transition timeout.
        mShellExecutor.flushAll();
        mController.onBackAnimationFinished();

        doMotionEvent(MotionEvent.ACTION_DOWN, 0);
        doMotionEvent(MotionEvent.ACTION_MOVE, 100);

        simulateRemoteAnimationStart(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        verify(mIOnBackInvokedCallback).onBackStarted();
    }


    @Test
    public void cancelBackInvokeWhenLostFocus() throws RemoteException {
        mController.setBackToLauncherCallback(mIOnBackInvokedCallback, mBackAnimationRunner);

        createNavigationInfo(BackNavigationInfo.TYPE_RETURN_TO_HOME, null);

        doMotionEvent(MotionEvent.ACTION_DOWN, 0);
        // Check that back start and progress is dispatched when first move.
        doMotionEvent(MotionEvent.ACTION_MOVE, 100);

        simulateRemoteAnimationStart(BackNavigationInfo.TYPE_RETURN_TO_HOME);
        verify(mIOnBackInvokedCallback).onBackStarted();
        verify(mBackAnimationRunner).onAnimationStart(anyInt(), any(), any(), any(), any());

        // Check that back invocation is dispatched.
        mController.setTriggerBack(true);   // Fake trigger back

        // In case the focus has been changed.
        IBinder token = mock(IBinder.class);
        mController.mFocusObserver.focusLost(token);
        mShellExecutor.flushAll();
        verify(mIOnBackInvokedCallback).onBackCancelled();

        // No more back invoke.
        doMotionEvent(MotionEvent.ACTION_UP, 0);
        verify(mIOnBackInvokedCallback, never()).onBackInvoked();
    }

    private void doMotionEvent(int actionDown, int coordinate) {
        mController.onMotionEvent(
                coordinate, coordinate,
                actionDown,
                BackEvent.EDGE_LEFT);
        mEventTime += 10;
    }

    private void simulateRemoteAnimationStart(int type) throws RemoteException {
        RemoteAnimationTarget animationTarget = createAnimationTarget();
        RemoteAnimationTarget[] targets = new RemoteAnimationTarget[]{animationTarget};
        if (mController.mBackAnimationAdapter != null) {
            mController.mBackAnimationAdapter.getRunner().onAnimationStart(type,
                    targets, null, null, mBackAnimationFinishedCallback);
            mShellExecutor.flushAll();
        }
    }
}
