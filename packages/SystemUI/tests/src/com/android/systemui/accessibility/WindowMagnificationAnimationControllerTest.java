/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.testing.AndroidTestingRunner;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.animation.AccelerateInterpolator;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.model.SysUiState;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Ignore
@LargeTest
@RunWith(AndroidTestingRunner.class)
public class WindowMagnificationAnimationControllerTest extends SysuiTestCase {

    private static final float DEFAULT_SCALE = 4.0f;
    private static final float DEFAULT_CENTER_X = 400.0f;
    private static final float DEFAULT_CENTER_Y = 500.0f;
    // The duration couldn't too short, otherwise the ValueAnimator won't work in expectation.
    private static final long ANIMATION_DURATION_MS = 300;

    private AtomicReference<Float> mCurrentScale = new AtomicReference<>((float) 0);
    private AtomicReference<Float> mCurrentCenterX = new AtomicReference<>((float) 0);
    private AtomicReference<Float> mCurrentCenterY = new AtomicReference<>((float) 0);
    private ArgumentCaptor<Float> mScaleCaptor = ArgumentCaptor.forClass(Float.class);
    private ArgumentCaptor<Float> mCenterXCaptor = ArgumentCaptor.forClass(Float.class);
    private ArgumentCaptor<Float> mCenterYCaptor = ArgumentCaptor.forClass(Float.class);
    private final ArgumentCaptor<Float> mOffsetXCaptor = ArgumentCaptor.forClass(Float.class);
    private final ArgumentCaptor<Float> mOffsetYCaptor = ArgumentCaptor.forClass(Float.class);

    @Mock
    Handler mHandler;
    @Mock
    SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;
    @Mock
    WindowMagnifierCallback mWindowMagnifierCallback;
    @Mock
    IRemoteMagnificationAnimationCallback mAnimationCallback;
    @Mock
    IRemoteMagnificationAnimationCallback mAnimationCallback2;
    @Mock(answer = Answers.RETURNS_SELF)
    SysUiState mSysUiState;
    private SpyWindowMagnificationController mController;
    private WindowMagnificationController mSpyController;
    private WindowMagnificationAnimationController mWindowMagnificationAnimationController;
    private Instrumentation mInstrumentation;
    private long mWaitingAnimationPeriod;
    private long mWaitIntermediateAnimationPeriod;

    private TestableWindowManager mWindowManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        mWindowManager = spy(new TestableWindowManager(wm));
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);

        mWaitingAnimationPeriod = 2 * ANIMATION_DURATION_MS;
        mWaitIntermediateAnimationPeriod = ANIMATION_DURATION_MS / 2;
        mWindowMagnificationAnimationController = new WindowMagnificationAnimationController(
                mContext, newValueAnimator());
        mController = new SpyWindowMagnificationController(mContext, mHandler,
                mWindowMagnificationAnimationController,
                mSfVsyncFrameProvider, null, new SurfaceControl.Transaction(),
                mWindowMagnifierCallback, mSysUiState);
        mSpyController = mController.getSpyController();
    }

    @After
    public void tearDown() throws Exception {
        mInstrumentation.runOnMainSync(() -> mController.deleteWindowMagnification());
    }

    @Test
    public void enableWindowMagnification_disabled_expectedValuesAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod, mAnimationCallback);

        verify(mSpyController, atLeast(2)).enableWindowMagnificationInternal(
                mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture(),
                mOffsetXCaptor.capture(), mOffsetYCaptor.capture());
        verifyStartValue(mScaleCaptor, 1.0f);
        verifyStartValue(mCenterXCaptor, DEFAULT_CENTER_X);
        verifyStartValue(mCenterYCaptor, DEFAULT_CENTER_Y);
        verifyStartValue(mOffsetXCaptor, 0f);
        verifyStartValue(mOffsetYCaptor, 0f);
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X, DEFAULT_CENTER_Y);
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void enableWindowMagnificationWithoutCallback_disabled_expectedValues() {
        enableWindowMagnificationWithoutAnimation();

        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X, DEFAULT_CENTER_Y);
    }

    @Test
    public void enableWindowMagnificationWithoutCallback_enabled_expectedValues() {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod, mAnimationCallback);
        final float targetScale = DEFAULT_SCALE + 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                            targetCenterX, targetCenterY, null);
                });

        verifyFinalSpec(targetScale, targetCenterX, targetCenterY);
    }

    @Test
    public void enableWindowMagnificationWithScaleOne_disabled_NoAnimationAndInvokeCallback()
            throws RemoteException {
        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationAnimationController.enableWindowMagnification(1,
                            DEFAULT_CENTER_X, DEFAULT_CENTER_Y, mAnimationCallback);
                });
        SystemClock.sleep(mWaitingAnimationPeriod);

        verify(mSpyController).enableWindowMagnificationInternal(1, DEFAULT_CENTER_X,
                DEFAULT_CENTER_Y, 0f, 0f);
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void enableWindowMagnification_enabling_expectedValuesAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                mAnimationCallback);
        final float targetScale = DEFAULT_SCALE + 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        mInstrumentation.runOnMainSync(() -> {
            Mockito.reset(mSpyController);
            mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                    targetCenterX, targetCenterY, mAnimationCallback2);
            mCurrentScale.set(mController.getScale());
            mCurrentCenterX.set(mController.getCenterX());
            mCurrentCenterY.set(mController.getCenterY());
        });

        SystemClock.sleep(mWaitingAnimationPeriod);

        verify(mSpyController, atLeast(2)).enableWindowMagnificationInternal(
                mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture(),
                mOffsetXCaptor.capture(), mOffsetYCaptor.capture());
        verifyStartValue(mScaleCaptor, mCurrentScale.get());
        verifyStartValue(mCenterXCaptor, mCurrentCenterX.get());
        verifyStartValue(mCenterYCaptor, mCurrentCenterY.get());
        verifyStartValue(mOffsetXCaptor, 0f);
        verifyStartValue(mOffsetYCaptor, 0f);
        verifyFinalSpec(targetScale, targetCenterX, targetCenterY);
        verify(mAnimationCallback).onResult(false);
        verify(mAnimationCallback2).onResult(true);
    }

    @Test
    public void enableWindowMagnificationWithUnchanged_enabling_expectedValuesToDefault()
            throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(2);
        final MockMagnificationAnimationCallback animationCallback =
                new MockMagnificationAnimationCallback(countDownLatch);

        enableWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                animationCallback);
        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationAnimationController.enableWindowMagnification(Float.NaN,
                            Float.NaN, Float.NaN, animationCallback);
                });

        assertTrue(countDownLatch.await(mWaitingAnimationPeriod, TimeUnit.MILLISECONDS));
        // The callback in 2nd enableWindowMagnification will return true
        assertEquals(1, animationCallback.getSuccessCount());
        // The callback in 1st enableWindowMagnification will return false
        assertEquals(1, animationCallback.getFailedCount());
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X, DEFAULT_CENTER_Y);
    }

    @Test
    public void enableWindowMagnificationWithScaleOne_enabled_AnimationAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationWithoutAnimation();

        mInstrumentation.runOnMainSync(() -> {
            Mockito.reset(mSpyController);
            mWindowMagnificationAnimationController.enableWindowMagnification(1.0f,
                    DEFAULT_CENTER_X + 100, DEFAULT_CENTER_Y + 100, mAnimationCallback);
            mCurrentScale.set(mController.getScale());
            mCurrentCenterX.set(mController.getCenterX());
            mCurrentCenterY.set(mController.getCenterY());
        });

        SystemClock.sleep(mWaitingAnimationPeriod);

        verify(mSpyController, atLeast(2)).enableWindowMagnificationInternal(
                mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture(),
                mOffsetXCaptor.capture(), mOffsetYCaptor.capture());
        verifyStartValue(mScaleCaptor, mCurrentScale.get());
        verifyStartValue(mCenterXCaptor, mCurrentCenterX.get());
        verifyStartValue(mCenterYCaptor, mCurrentCenterY.get());
        verifyStartValue(mOffsetXCaptor, 0f);
        verifyStartValue(mOffsetYCaptor, 0f);
        // It presents the window magnification is disabled.
        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);

        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void
            enableMagnificationWithoutCallback_enabling_expectedValuesAndInvokeFormerCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                mAnimationCallback);
        final float targetScale = DEFAULT_SCALE - 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        mInstrumentation.runOnMainSync(() -> {
            Mockito.reset(mSpyController);
            mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                    targetCenterX, targetCenterY, null);
        });
        verifyFinalSpec(targetScale, targetCenterX, targetCenterY);
        verify(mAnimationCallback).onResult(false);
    }

    @Test
    public void enableWindowMagnificationWithSameSpec_enabling_NoAnimationAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                mAnimationCallback);

        mInstrumentation.runOnMainSync(() -> {
            Mockito.reset(mSpyController);
            mWindowMagnificationAnimationController.enableWindowMagnification(Float.NaN,
                    Float.NaN, Float.NaN, mAnimationCallback2);
        });
        SystemClock.sleep(mWaitingAnimationPeriod);

        verify(mSpyController, never()).enableWindowMagnificationInternal(anyFloat(), anyFloat(),
                anyFloat());
        verify(mAnimationCallback).onResult(false);
        verify(mAnimationCallback2).onResult(true);
    }

    @Test
    public void enableWindowMagnification_disabling_expectedValuesAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod, null);
        deleteWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                mAnimationCallback);
        final float targetScale = DEFAULT_SCALE + 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        mInstrumentation.runOnMainSync(
                () -> {
                    Mockito.reset(mSpyController);
                    mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                            targetCenterX, targetCenterY, mAnimationCallback2);
                    mCurrentScale.set(mController.getScale());
                    mCurrentCenterX.set(mController.getCenterX());
                    mCurrentCenterY.set(mController.getCenterY());
                });
        // Current spec shouldn't match given spec.
        verify(mAnimationCallback2, never()).onResult(anyBoolean());
        verify(mAnimationCallback).onResult(false);
        SystemClock.sleep(mWaitingAnimationPeriod);

        verify(mSpyController, atLeast(2)).enableWindowMagnificationInternal(
                mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture(),
                mOffsetXCaptor.capture(), mOffsetYCaptor.capture());
        //Animating in reverse, so we only check if the start values are greater than current.
        assertTrue(mScaleCaptor.getAllValues().get(0) > mCurrentScale.get());
        assertEquals(targetScale, mScaleCaptor.getValue(), 0f);
        assertTrue(mCenterXCaptor.getAllValues().get(0) > mCurrentCenterX.get());
        assertEquals(targetCenterX, mCenterXCaptor.getValue(), 0f);
        assertTrue(mCenterYCaptor.getAllValues().get(0) > mCurrentCenterY.get());
        assertEquals(targetCenterY, mCenterYCaptor.getValue(), 0f);
        verifyFinalSpec(targetScale, targetCenterX, targetCenterY);
        verify(mAnimationCallback2).onResult(true);
    }

    @Test
    public void
            enableMagnificationWithoutCallback_disabling_expectedValuesAndInvokeFormerCallback()
            throws RemoteException {
        enableWindowMagnificationWithoutAnimation();
        deleteWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                mAnimationCallback);
        final float targetScale = DEFAULT_SCALE + 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        mInstrumentation.runOnMainSync(
                () -> {
                    Mockito.reset(mSpyController);
                    mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                            targetCenterX, targetCenterY, null);
                });

        verify(mAnimationCallback).onResult(false);
        verifyFinalSpec(targetScale, targetCenterX, targetCenterY);
    }

    @Test
    public void enableWindowMagnificationWithSameSpec_disabling_NoAnimationAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod, null);
        deleteWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                mAnimationCallback);

        mInstrumentation.runOnMainSync(() -> {
            Mockito.reset(mSpyController);
            mWindowMagnificationAnimationController.enableWindowMagnification(Float.NaN,
                    Float.NaN, Float.NaN, mAnimationCallback2);
        });
        SystemClock.sleep(mWaitingAnimationPeriod);

        verify(mSpyController, never()).enableWindowMagnificationInternal(anyFloat(), anyFloat(),
                anyFloat());
        verify(mSpyController, never()).deleteWindowMagnification();
        verify(mAnimationCallback).onResult(false);
        verify(mAnimationCallback2).onResult(true);
    }

    @Test
    public void enableWindowMagnification_enabled_expectedValuesAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationWithoutAnimation();
        final float targetScale = DEFAULT_SCALE + 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        mInstrumentation.runOnMainSync(() -> {
            Mockito.reset(mSpyController);
            mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                    targetCenterX, targetCenterY, mAnimationCallback2);
            mCurrentScale.set(mController.getScale());
            mCurrentCenterX.set(mController.getCenterX());
            mCurrentCenterY.set(mController.getCenterY());
        });

        SystemClock.sleep(mWaitingAnimationPeriod);

        verify(mSpyController, atLeast(2)).enableWindowMagnificationInternal(
                mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture(),
                mOffsetXCaptor.capture(), mOffsetYCaptor.capture());
        verifyStartValue(mScaleCaptor, mCurrentScale.get());
        verifyStartValue(mCenterXCaptor, mCurrentCenterX.get());
        verifyStartValue(mCenterYCaptor, mCurrentCenterY.get());
        verifyStartValue(mOffsetXCaptor, 0f);
        verifyStartValue(mOffsetYCaptor, 0f);
        verifyFinalSpec(targetScale, targetCenterX, targetCenterY);
        verify(mAnimationCallback2).onResult(true);
    }

    @Test
    public void enableWindowMagnificationWithOffset_expectedValues() {
        final float offsetRatio = -0.1f;
        final Rect windowBounds = new Rect(mWindowManager.getCurrentWindowMetrics().getBounds());
        mInstrumentation.runOnMainSync(() -> {
            Mockito.reset(mSpyController);
            mWindowMagnificationAnimationController.enableWindowMagnification(DEFAULT_SCALE,
                    windowBounds.exactCenterX(), windowBounds.exactCenterY(),
                    offsetRatio, offsetRatio, mAnimationCallback);
        });
        SystemClock.sleep(mWaitingAnimationPeriod);
        final View attachedView = mWindowManager.getAttachedView();
        assertNotNull(attachedView);
        final Rect mirrorViewBound = new Rect();
        final View mirrorView = attachedView.findViewById(R.id.surface_view);
        assertNotNull(mirrorView);
        mirrorView.getBoundsOnScreen(mirrorViewBound);

        assertEquals(mirrorViewBound.exactCenterX() - windowBounds.exactCenterX(),
                Math.round(offsetRatio * mirrorViewBound.width() / 2), 0.1f);
        assertEquals(mirrorViewBound.exactCenterY() - windowBounds.exactCenterY(),
                Math.round(offsetRatio * mirrorViewBound.height() / 2), 0.1f);
    }

    @Test
    public void moveWindowMagnifierToPosition_enabled_expectedValues()
            throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final MockMagnificationAnimationCallback animationCallback =
                new MockMagnificationAnimationCallback(countDownLatch);
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;
        enableWindowMagnificationWithoutAnimation();

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                    targetCenterX, targetCenterY, animationCallback);
        });

        assertTrue(countDownLatch.await(mWaitingAnimationPeriod, TimeUnit.MILLISECONDS));
        assertEquals(1, animationCallback.getSuccessCount());
        assertEquals(0, animationCallback.getFailedCount());
        verifyFinalSpec(DEFAULT_SCALE, targetCenterX, targetCenterY);
    }

    @Test
    public void moveWindowMagnifierToPositionMultipleTimes_enabled_expectedValuesToLastOne()
            throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(4);
        final MockMagnificationAnimationCallback animationCallback =
                new MockMagnificationAnimationCallback(countDownLatch);
        enableWindowMagnificationWithoutAnimation();

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                    DEFAULT_CENTER_X + 10, DEFAULT_CENTER_Y + 10, animationCallback);
            mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                    DEFAULT_CENTER_X + 20, DEFAULT_CENTER_Y + 20, animationCallback);
            mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                    DEFAULT_CENTER_X + 30, DEFAULT_CENTER_Y + 30, animationCallback);
            mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                    DEFAULT_CENTER_X + 40, DEFAULT_CENTER_Y + 40, animationCallback);
        });

        assertTrue(countDownLatch.await(mWaitingAnimationPeriod, TimeUnit.MILLISECONDS));
        // only the last one callback will return true
        assertEquals(1, animationCallback.getSuccessCount());
        // the others will return false
        assertEquals(3, animationCallback.getFailedCount());
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X + 40, DEFAULT_CENTER_Y + 40);
    }

    @Test
    public void moveWindowMagnifierToPosition_enabling_expectedValuesToLastOne()
            throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(2);
        final MockMagnificationAnimationCallback animationCallback =
                new MockMagnificationAnimationCallback(countDownLatch);
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        enableWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                animationCallback);
        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                            targetCenterX, targetCenterY, animationCallback);
                });

        assertTrue(countDownLatch.await(mWaitingAnimationPeriod, TimeUnit.MILLISECONDS));
        // The callback in moveWindowMagnifierToPosition will return true
        assertEquals(1, animationCallback.getSuccessCount());
        // The callback in enableWindowMagnification will return false
        assertEquals(1, animationCallback.getFailedCount());
        verifyFinalSpec(DEFAULT_SCALE, targetCenterX, targetCenterY);
    }

    @Test
    public void moveWindowMagnifierToPositionWithCenterUnchanged_enabling_expectedValuesToDefault()
            throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(2);
        final MockMagnificationAnimationCallback animationCallback =
                new MockMagnificationAnimationCallback(countDownLatch);

        enableWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                animationCallback);
        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                            Float.NaN, Float.NaN, animationCallback);
                });

        assertTrue(countDownLatch.await(mWaitingAnimationPeriod, TimeUnit.MILLISECONDS));
        // The callback in moveWindowMagnifierToPosition will return true
        assertEquals(1, animationCallback.getSuccessCount());
        // The callback in enableWindowMagnification will return false
        assertEquals(1, animationCallback.getFailedCount());
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X, DEFAULT_CENTER_Y);
    }

    @Test
    public void enableWindowMagnificationWithSameScale_enabled_doNothingButInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod, null);

        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod, mAnimationCallback);

        verify(mSpyController, never()).enableWindowMagnificationInternal(anyFloat(), anyFloat(),
                anyFloat());
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void deleteWindowMagnification_enabled_expectedValuesAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationWithoutAnimation();

        deleteWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod, mAnimationCallback);

        verify(mSpyController, atLeast(2)).enableWindowMagnificationInternal(
                mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture(),
                mOffsetXCaptor.capture(), mOffsetYCaptor.capture());
        verifyStartValue(mScaleCaptor, DEFAULT_SCALE);
        verifyStartValue(mCenterXCaptor, Float.NaN);
        verifyStartValue(mCenterYCaptor, Float.NaN);
        verifyStartValue(mOffsetXCaptor, 0f);
        verifyStartValue(mOffsetYCaptor, 0f);
        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void deleteWindowMagnificationWithoutCallback_enabled_expectedValues() {
        enableWindowMagnificationWithoutAnimation();

        deleteWindowMagnificationAndWaitAnimating(0, null);

        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);
    }

    @Test
    public void deleteWindowMagnification_disabled_doNothingAndInvokeCallback()
            throws RemoteException {
        deleteWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod, mAnimationCallback);

        Mockito.verifyNoMoreInteractions(mSpyController);
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void deleteWindowMagnification_enabling_expectedValuesAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                mAnimationCallback);

        mInstrumentation.runOnMainSync(
                () -> {
                    Mockito.reset(mSpyController);
                    mWindowMagnificationAnimationController.deleteWindowMagnification(
                            mAnimationCallback2);
                    mCurrentScale.set(mController.getScale());
                    mCurrentCenterX.set(mController.getCenterX());
                    mCurrentCenterY.set(mController.getCenterY());
                });
        SystemClock.sleep(mWaitingAnimationPeriod);
        verify(mSpyController, atLeast(2)).enableWindowMagnificationInternal(
                mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture(),
                mOffsetXCaptor.capture(), mOffsetYCaptor.capture());

        //The animation is in verse, so we only check the start values should no be greater than
        // the current one.
        assertTrue(mScaleCaptor.getAllValues().get(0) <= mCurrentScale.get());
        assertEquals(1.0f, mScaleCaptor.getValue(), 0f);
        verifyStartValue(mCenterXCaptor, Float.NaN);
        verifyStartValue(mCenterYCaptor, Float.NaN);
        verifyStartValue(mOffsetXCaptor, 0f);
        verifyStartValue(mOffsetYCaptor, 0f);
        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);
        verify(mAnimationCallback).onResult(false);
        verify(mAnimationCallback2).onResult(true);
    }

    @Test
    public void deleteWindowMagnificationWithoutCallback_enabling_expectedValuesAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                mAnimationCallback);

        mInstrumentation.runOnMainSync(
                () -> {
                    Mockito.reset(mSpyController);
                    mWindowMagnificationAnimationController.deleteWindowMagnification(null);
                });

        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);
        verify(mAnimationCallback).onResult(false);
    }

    @Test
    public void deleteWindowMagnification_disabling_checkStartAndValues() throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod, null);
        deleteWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                mAnimationCallback);

        deleteWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod, mAnimationCallback2);

        verify(mSpyController, atLeast(2)).enableWindowMagnificationInternal(
                mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture(),
                mOffsetXCaptor.capture(), mOffsetYCaptor.capture());
        assertEquals(1.0f, mScaleCaptor.getValue(), 0f);
        verifyStartValue(mOffsetXCaptor, 0f);
        verifyStartValue(mOffsetYCaptor, 0f);
        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);
        verify(mAnimationCallback).onResult(false);
        verify(mAnimationCallback2).onResult(true);
    }

    @Test
    public void deleteWindowMagnificationWithoutCallback_disabling_checkStartAndValues()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod, null);
        deleteWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod,
                mAnimationCallback);

        deleteWindowMagnificationAndWaitAnimating(0, null);

        verify(mSpyController).deleteWindowMagnification();
        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);
        verify(mAnimationCallback).onResult(false);
    }

    @Test
    public void moveWindowMagnifier_enabled() {
        enableWindowMagnificationWithoutAnimation();

        mInstrumentation.runOnMainSync(
                () -> mController.moveWindowMagnifier(100f, 200f));

        verify(mSpyController).moveWindowMagnifier(100f, 200f);
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X + 100f, DEFAULT_CENTER_Y + 100f);
    }

    @Test
    public void moveWindowMagnifierToPosition_enabled() {
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;
        enableWindowMagnificationWithoutAnimation();

        mInstrumentation.runOnMainSync(
                () -> mController.moveWindowMagnifierToPosition(targetCenterX, targetCenterY,
                        mAnimationCallback));
        SystemClock.sleep(mWaitingAnimationPeriod);

        verifyFinalSpec(DEFAULT_SCALE, targetCenterX, targetCenterY);
    }

    private void verifyFinalSpec(float expectedScale, float expectedCenterX,
            float expectedCenterY) {
        assertEquals(expectedScale, mController.getScale(), 0f);
        assertEquals(expectedCenterX, mController.getCenterX(), 0f);
        assertEquals(expectedCenterY, mController.getCenterY(), 0f);
    }

    private void enableWindowMagnificationWithoutAnimation() {
        mInstrumentation.runOnMainSync(
                () -> {
                    Mockito.reset(mSpyController);
                    mWindowMagnificationAnimationController.enableWindowMagnification(DEFAULT_SCALE,
                            DEFAULT_CENTER_X, DEFAULT_CENTER_Y, null);
                });
    }

    private void enableWindowMagnificationAndWaitAnimating(long duration,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        mInstrumentation.runOnMainSync(
                () -> {
                    Mockito.reset(mSpyController);
                    mWindowMagnificationAnimationController.enableWindowMagnification(DEFAULT_SCALE,
                            DEFAULT_CENTER_X, DEFAULT_CENTER_Y, callback);
                });
        SystemClock.sleep(duration);
    }

    private void deleteWindowMagnificationAndWaitAnimating(long duration,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        mInstrumentation.runOnMainSync(
                () -> {
                    resetMockObjects();
                    mWindowMagnificationAnimationController.deleteWindowMagnification(callback);
                });
        SystemClock.sleep(duration);
    }

    private void verifyStartValue(ArgumentCaptor<Float> captor, float startValue) {
        assertEquals(startValue, captor.getAllValues().get(0), 0f);
    }

    private void resetMockObjects() {
        Mockito.reset(mSpyController);
    }

    /**
     * It observes the methods in {@link WindowMagnificationController} since we couldn't spy it
     * directly.
     */
    private static class SpyWindowMagnificationController extends WindowMagnificationController {
        private WindowMagnificationController mSpyController;

        SpyWindowMagnificationController(Context context, Handler handler,
                WindowMagnificationAnimationController animationController,
                SfVsyncFrameCallbackProvider sfVsyncFrameProvider,
                MirrorWindowControl mirrorWindowControl, SurfaceControl.Transaction transaction,
                WindowMagnifierCallback callback, SysUiState sysUiState) {
            super(context, handler, animationController, sfVsyncFrameProvider, mirrorWindowControl,
                    transaction, callback, sysUiState);
            mSpyController = Mockito.mock(WindowMagnificationController.class);
        }

        WindowMagnificationController getSpyController() {
            return mSpyController;
        }

        @Override
        void enableWindowMagnificationInternal(float scale, float centerX, float centerY) {
            super.enableWindowMagnificationInternal(scale, centerX, centerY);
            mSpyController.enableWindowMagnificationInternal(scale, centerX, centerY);
        }

        @Override
        void enableWindowMagnificationInternal(float scale, float centerX, float centerY,
                float magnificationOffsetFrameRatioX, float magnificationOffsetFrameRatioY) {
            super.enableWindowMagnificationInternal(scale, centerX, centerY,
                    magnificationOffsetFrameRatioX, magnificationOffsetFrameRatioY);
            mSpyController.enableWindowMagnificationInternal(scale, centerX, centerY,
                    magnificationOffsetFrameRatioX, magnificationOffsetFrameRatioY);
        }

        @Override
        void deleteWindowMagnification() {
            super.deleteWindowMagnification();
            mSpyController.deleteWindowMagnification();
        }

        @Override
        void moveWindowMagnifier(float offsetX, float offsetY) {
            super.moveWindowMagnifier(offsetX, offsetX);
            mSpyController.moveWindowMagnifier(offsetX, offsetY);
        }

        @Override
        void moveWindowMagnifierToPosition(float positionX, float positionY,
                IRemoteMagnificationAnimationCallback callback) {
            super.moveWindowMagnifierToPosition(positionX, positionY, callback);
            mSpyController.moveWindowMagnifierToPosition(positionX, positionY, callback);
        }

        @Override
        void setScale(float scale) {
            super.setScale(scale);
            mSpyController.setScale(scale);
        }

        @Override
        public void updateSysUIStateFlag() {
            super.updateSysUIStateFlag();
            mSpyController.updateSysUIStateFlag();
        }
    }

    private static ValueAnimator newValueAnimator() {
        final ValueAnimator valueAnimator = new ValueAnimator();
        valueAnimator.setDuration(ANIMATION_DURATION_MS);
        valueAnimator.setInterpolator(new AccelerateInterpolator(2.5f));
        valueAnimator.setFloatValues(0.0f, 1.0f);
        return valueAnimator;
    }
}
