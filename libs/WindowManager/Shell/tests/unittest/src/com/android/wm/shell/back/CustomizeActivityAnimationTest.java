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

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.WindowConfiguration;
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
        spyOn(mCustomizeActivityAnimation.mCustomAnimationLoader);
        doReturn(mMockCloseAnimation).when(mCustomizeActivityAnimation.mCustomAnimationLoader)
                .load(any(), eq(false));
        doReturn(mMockOpenAnimation).when(mCustomizeActivityAnimation.mCustomAnimationLoader)
                .load(any(), eq(true));
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
        mCustomizeActivityAnimation.prepareNextAnimation(
                new BackNavigationInfo.CustomAnimationInfo("TestPackage"));
        final RemoteAnimationTarget close = createAnimationTarget(false);
        final RemoteAnimationTarget open = createAnimationTarget(true);
        // start animation with remote animation targets
        final CountDownLatch finishCalled = new CountDownLatch(1);
        final Runnable finishCallback = finishCalled::countDown;
        mCustomizeActivityAnimation.mBackAnimationRunner.startAnimation(
                new RemoteAnimationTarget[]{close, open}, null, null, finishCallback);
        verify(mMockCloseAnimation).initialize(eq(BOUND_SIZE), eq(BOUND_SIZE),
                eq(BOUND_SIZE), eq(BOUND_SIZE));
        verify(mMockOpenAnimation).initialize(eq(BOUND_SIZE), eq(BOUND_SIZE),
                eq(BOUND_SIZE), eq(BOUND_SIZE));

        try {
            mCustomizeActivityAnimation.mBackAnimationRunner.getCallback().onBackInvoked();
        } catch (RemoteException r) {
            fail("onBackInvoked throw remote exception");
        }
        verify(mCustomizeActivityAnimation).onGestureCommitted();
        finishCalled.await(1, TimeUnit.SECONDS);
    }

    @Test
    public void receiveFinishAfterCancel() throws InterruptedException {
        mCustomizeActivityAnimation.prepareNextAnimation(
                new BackNavigationInfo.CustomAnimationInfo("TestPackage"));
        final RemoteAnimationTarget close = createAnimationTarget(false);
        final RemoteAnimationTarget open = createAnimationTarget(true);
        // start animation with remote animation targets
        final CountDownLatch finishCalled = new CountDownLatch(1);
        final Runnable finishCallback = finishCalled::countDown;
        mCustomizeActivityAnimation.mBackAnimationRunner.startAnimation(
                new RemoteAnimationTarget[]{close, open}, null, null, finishCallback);
        verify(mMockCloseAnimation).initialize(eq(BOUND_SIZE), eq(BOUND_SIZE),
                eq(BOUND_SIZE), eq(BOUND_SIZE));
        verify(mMockOpenAnimation).initialize(eq(BOUND_SIZE), eq(BOUND_SIZE),
                eq(BOUND_SIZE), eq(BOUND_SIZE));

        try {
            mCustomizeActivityAnimation.mBackAnimationRunner.getCallback().onBackCancelled();
        } catch (RemoteException r) {
            fail("onBackCancelled throw remote exception");
        }
        finishCalled.await(1, TimeUnit.SECONDS);
    }

    @Test
    public void receiveFinishWithoutAnimationAfterInvoke() throws InterruptedException {
        mCustomizeActivityAnimation.prepareNextAnimation(
                new BackNavigationInfo.CustomAnimationInfo("TestPackage"));
        // start animation without any remote animation targets
        final CountDownLatch finishCalled = new CountDownLatch(1);
        final Runnable finishCallback = finishCalled::countDown;
        mCustomizeActivityAnimation.mBackAnimationRunner.startAnimation(
                new RemoteAnimationTarget[]{}, null, null, finishCallback);

        try {
            mCustomizeActivityAnimation.mBackAnimationRunner.getCallback().onBackInvoked();
        } catch (RemoteException r) {
            fail("onBackInvoked throw remote exception");
        }
        verify(mCustomizeActivityAnimation).onGestureCommitted();
        finishCalled.await(1, TimeUnit.SECONDS);
    }
}
