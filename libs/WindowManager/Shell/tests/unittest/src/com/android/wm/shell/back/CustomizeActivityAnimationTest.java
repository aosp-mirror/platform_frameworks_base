/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.WindowConfiguration;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Choreographer;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.window.BackNavigationInfo;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class CustomizeActivityAnimationTest extends ShellTestCase {
    private static final int BOUND_SIZE = 100;
    @Mock
    private BackAnimationBackground mBackAnimationBackground;
    @Mock
    private Animation mMockCloseAnimation;
    @Mock
    private Animation mMockOpenAnimation;

    private CustomizeActivityAnimation mCustomizeActivityAnimation;

    @Before
    public void setUp() throws Exception {
        mCustomizeActivityAnimation = new CustomizeActivityAnimation(mContext,
                mBackAnimationBackground, mock(SurfaceControl.Transaction.class),
                mock(Choreographer.class));
        spyOn(mCustomizeActivityAnimation);
        spyOn(mCustomizeActivityAnimation.mCustomAnimationLoader.mTransitionAnimation);
    }

    RemoteAnimationTarget createAnimationTarget(boolean open) {
        SurfaceControl topWindowLeash = new SurfaceControl();
        return new RemoteAnimationTarget(1,
                open ? RemoteAnimationTarget.MODE_OPENING : RemoteAnimationTarget.MODE_CLOSING,
                topWindowLeash, false, new Rect(), new Rect(), -1,
                new Point(0, 0), new Rect(0, 0, BOUND_SIZE, BOUND_SIZE), new Rect(),
                new WindowConfiguration(), true, null, null, null, false, -1);
    }

    @Test
    public void receiveFinishAfterInvoke() throws InterruptedException {
        spyOn(mCustomizeActivityAnimation.mCustomAnimationLoader);
        doReturn(mMockCloseAnimation).when(mCustomizeActivityAnimation.mCustomAnimationLoader)
                .loadAnimation(any(), eq(false));
        doReturn(mMockOpenAnimation).when(mCustomizeActivityAnimation.mCustomAnimationLoader)
                .loadAnimation(any(), eq(true));

        mCustomizeActivityAnimation.prepareNextAnimation(
                new BackNavigationInfo.CustomAnimationInfo("TestPackage"), 0);
        final RemoteAnimationTarget close = createAnimationTarget(false);
        final RemoteAnimationTarget open = createAnimationTarget(true);
        // start animation with remote animation targets
        final CountDownLatch finishCalled = new CountDownLatch(1);
        final Runnable finishCallback = finishCalled::countDown;
        mCustomizeActivityAnimation
                .getRunner()
                .startAnimation(
                        new RemoteAnimationTarget[] {close, open}, null, null, finishCallback);
        verify(mMockCloseAnimation).initialize(eq(BOUND_SIZE), eq(BOUND_SIZE),
                eq(BOUND_SIZE), eq(BOUND_SIZE));
        verify(mMockOpenAnimation).initialize(eq(BOUND_SIZE), eq(BOUND_SIZE),
                eq(BOUND_SIZE), eq(BOUND_SIZE));

        try {
            mCustomizeActivityAnimation.getRunner().getCallback().onBackInvoked();
        } catch (RemoteException r) {
            fail("onBackInvoked throw remote exception");
        }
        verify(mCustomizeActivityAnimation).onGestureCommitted();
        finishCalled.await(1, TimeUnit.SECONDS);
    }

    @Test
    public void receiveFinishAfterCancel() throws InterruptedException {
        spyOn(mCustomizeActivityAnimation.mCustomAnimationLoader);
        doReturn(mMockCloseAnimation).when(mCustomizeActivityAnimation.mCustomAnimationLoader)
                .loadAnimation(any(), eq(false));
        doReturn(mMockOpenAnimation).when(mCustomizeActivityAnimation.mCustomAnimationLoader)
                .loadAnimation(any(), eq(true));

        mCustomizeActivityAnimation.prepareNextAnimation(
                new BackNavigationInfo.CustomAnimationInfo("TestPackage"), 0);
        final RemoteAnimationTarget close = createAnimationTarget(false);
        final RemoteAnimationTarget open = createAnimationTarget(true);
        // start animation with remote animation targets
        final CountDownLatch finishCalled = new CountDownLatch(1);
        final Runnable finishCallback = finishCalled::countDown;
        mCustomizeActivityAnimation
                .getRunner()
                .startAnimation(
                        new RemoteAnimationTarget[] {close, open}, null, null, finishCallback);
        verify(mMockCloseAnimation).initialize(eq(BOUND_SIZE), eq(BOUND_SIZE),
                eq(BOUND_SIZE), eq(BOUND_SIZE));
        verify(mMockOpenAnimation).initialize(eq(BOUND_SIZE), eq(BOUND_SIZE),
                eq(BOUND_SIZE), eq(BOUND_SIZE));

        try {
            mCustomizeActivityAnimation.getRunner().getCallback().onBackCancelled();
        } catch (RemoteException r) {
            fail("onBackCancelled throw remote exception");
        }
        finishCalled.await(1, TimeUnit.SECONDS);
    }

    @Test
    public void receiveFinishWithoutAnimationAfterInvoke() throws InterruptedException {
        mCustomizeActivityAnimation.prepareNextAnimation(
                new BackNavigationInfo.CustomAnimationInfo("TestPackage"), 0);
        // start animation without any remote animation targets
        final CountDownLatch finishCalled = new CountDownLatch(1);
        final Runnable finishCallback = finishCalled::countDown;
        mCustomizeActivityAnimation
                .getRunner()
                .startAnimation(new RemoteAnimationTarget[] {}, null, null, finishCallback);

        try {
            mCustomizeActivityAnimation.getRunner().getCallback().onBackInvoked();
        } catch (RemoteException r) {
            fail("onBackInvoked throw remote exception");
        }
        verify(mCustomizeActivityAnimation).onGestureCommitted();
        finishCalled.await(1, TimeUnit.SECONDS);
    }

    @Test
    public void testLoadCustomAnimation() {
        testLoadCustomAnimation(10, 20, 0);
    }

    @Test
    public void testLoadCustomAnimationNoEnter() {
        testLoadCustomAnimation(0, 10, 0);
    }

    @Test
    public void testLoadWindowAnimations() {
        testLoadCustomAnimation(0, 0, 30);
    }

    @Test
    public void testCustomAnimationHigherThanWindowAnimations() {
        testLoadCustomAnimation(10, 20, 30);
    }

    private void testLoadCustomAnimation(int enterResId, int exitResId, int windowAnimations) {
        final String testPackage = "TestPackage";
        BackNavigationInfo.Builder builder = new BackNavigationInfo.Builder()
                .setCustomAnimation(testPackage, enterResId, exitResId, Color.GREEN)
                .setWindowAnimations(testPackage, windowAnimations);
        final BackNavigationInfo.CustomAnimationInfo info = builder.build()
                .getCustomAnimationInfo();

        doReturn(mMockOpenAnimation).when(mCustomizeActivityAnimation.mCustomAnimationLoader
                        .mTransitionAnimation)
                .loadAppTransitionAnimation(eq(testPackage), eq(enterResId));
        doReturn(mMockCloseAnimation).when(mCustomizeActivityAnimation.mCustomAnimationLoader
                        .mTransitionAnimation)
                .loadAppTransitionAnimation(eq(testPackage), eq(exitResId));
        doReturn(mMockCloseAnimation).when(mCustomizeActivityAnimation.mCustomAnimationLoader
                        .mTransitionAnimation)
                .loadAnimationAttr(eq(testPackage), eq(windowAnimations), anyInt(), anyBoolean());
        doReturn(mMockOpenAnimation).when(mCustomizeActivityAnimation.mCustomAnimationLoader
                        .mTransitionAnimation).loadDefaultAnimationAttr(anyInt(), anyBoolean());

        CustomizeActivityAnimation.AnimationLoadResult result =
                mCustomizeActivityAnimation.mCustomAnimationLoader.loadAll(info);

        if (exitResId != 0) {
            if (enterResId == 0) {
                verify(mCustomizeActivityAnimation.mCustomAnimationLoader.mTransitionAnimation,
                        never()).loadAppTransitionAnimation(eq(testPackage), eq(enterResId));
                verify(mCustomizeActivityAnimation.mCustomAnimationLoader.mTransitionAnimation)
                        .loadDefaultAnimationAttr(anyInt(), anyBoolean());
            } else {
                assertEquals(result.mEnterAnimation, mMockOpenAnimation);
            }
            assertEquals(result.mBackgroundColor, Color.GREEN);
            assertEquals(result.mCloseAnimation, mMockCloseAnimation);
            verify(mCustomizeActivityAnimation.mCustomAnimationLoader.mTransitionAnimation, never())
                    .loadAnimationAttr(eq(testPackage), anyInt(), anyInt(), anyBoolean());
        } else if (windowAnimations != 0) {
            verify(mCustomizeActivityAnimation.mCustomAnimationLoader.mTransitionAnimation,
                    times(2)).loadAnimationAttr(eq(testPackage), anyInt(), anyInt(), anyBoolean());
            assertEquals(result.mCloseAnimation, mMockCloseAnimation);
        }
    }
}
