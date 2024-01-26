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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.animation.AccelerateInterpolator;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.LargeTest;

import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.AnimatorTestRule;
import com.android.systemui.model.SysUiState;
import com.android.systemui.res.R;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.atomic.AtomicReference;

@LargeTest
@RunWith(AndroidTestingRunner.class)
@FlakyTest(bugId = 308501761)
public class WindowMagnificationAnimationControllerTest extends SysuiTestCase {

    @Rule
    public final AnimatorTestRule mAnimatorTestRule = new AnimatorTestRule();
    private static final float DEFAULT_SCALE = 4.0f;
    private static final float DEFAULT_CENTER_X = 400.0f;
    private static final float DEFAULT_CENTER_Y = 500.0f;

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
    @Mock
    SecureSettings mSecureSettings;
    private SpyWindowMagnificationController mController;
    private WindowMagnificationController mSpyController;
    private WindowMagnificationAnimationController mWindowMagnificationAnimationController;

    private long mWaitAnimationDuration;
    private long mWaitPartialAnimationDuration;

    private TestableWindowManager mWindowManager;
    private ValueAnimator mValueAnimator;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        mWindowManager = spy(new TestableWindowManager(wm));
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);

        // Using the animation duration in WindowMagnificationAnimationController for testing.
        mWaitAnimationDuration = mContext.getResources()
                .getInteger(com.android.internal.R.integer.config_longAnimTime);
        mWaitPartialAnimationDuration = mWaitAnimationDuration / 2;

        mValueAnimator = newValueAnimator();
        mWindowMagnificationAnimationController = new WindowMagnificationAnimationController(
                mContext, mValueAnimator);
        mController = new SpyWindowMagnificationController(mContext, mHandler,
                mWindowMagnificationAnimationController,
                mSfVsyncFrameProvider, null, new SurfaceControl.Transaction(),
                mWindowMagnifierCallback, mSysUiState, mSecureSettings);
        mSpyController = mController.getSpyController();
    }

    @After
    public void tearDown() throws Exception {
        getInstrumentation().runOnMainSync(() -> {
            mController.deleteWindowMagnification();
        });
    }

    @Test
    public void enableWindowMagnification_disabled_expectedValuesAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitAnimationDuration, mAnimationCallback);

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
        enableWindowMagnificationAndWaitAnimating(mWaitAnimationDuration, mAnimationCallback);
        final float targetScale = DEFAULT_SCALE + 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        enableWindowMagnificationWithoutAnimation(targetScale, targetCenterX, targetCenterY);

        verifyFinalSpec(targetScale, targetCenterX, targetCenterY);
    }

    @Test
    public void enableWindowMagnificationWithScaleOne_disabled_NoAnimationAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(
                mWaitAnimationDuration, /* targetScale= */ 1.0f,
                DEFAULT_CENTER_X, DEFAULT_CENTER_Y, mAnimationCallback);

        verify(mSpyController).enableWindowMagnificationInternal(1.0f, DEFAULT_CENTER_X,
                DEFAULT_CENTER_Y, 0f, 0f);
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void enableWindowMagnification_enabling_expectedValuesAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);
        final float targetScale = DEFAULT_SCALE + 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        resetMockObjects();
        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                    targetCenterX, targetCenterY, mAnimationCallback2);
            mCurrentScale.set(mController.getScale());
            mCurrentCenterX.set(mController.getCenterX());
            mCurrentCenterY.set(mController.getCenterY());
            advanceTimeBy(mWaitAnimationDuration);
        });

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
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);

        enableWindowMagnificationAndWaitAnimating(mWaitAnimationDuration, Float.NaN,
                Float.NaN, Float.NaN, mAnimationCallback2);

        // The callback in 2nd enableWindowMagnification will return true
        verify(mAnimationCallback2).onResult(true);
        // The callback in 1st enableWindowMagnification will return false
        verify(mAnimationCallback).onResult(false);
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X, DEFAULT_CENTER_Y);
    }

    @Test
    public void enableWindowMagnificationWithScaleOne_enabled_AnimationAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationWithoutAnimation();

        // Wait for Rects updated.
        waitForIdleSync();
        View mirrorView = mWindowManager.getAttachedView();
        final float targetScale = 1.0f;
        // Move the magnifier to the top left corner, within the boundary
        final float targetCenterX = mirrorView.getWidth() / 2.0f;
        final float targetCenterY = mirrorView.getHeight() / 2.0f;

        Mockito.reset(mSpyController);
        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                    targetCenterX, targetCenterY, mAnimationCallback);
            mCurrentScale.set(mController.getScale());
            mCurrentCenterX.set(mController.getCenterX());
            mCurrentCenterY.set(mController.getCenterY());
            advanceTimeBy(mWaitAnimationDuration);
        });

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

        verify(mAnimationCallback).onResult(true);
        assertEquals(WindowMagnificationAnimationController.STATE_ENABLED,
                mWindowMagnificationAnimationController.getState());
    }

    @Test
    public void enableWindowMagnificationWithScaleLessThanOne_enabled_AnimationAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationWithoutAnimation();

        final float targetScale = 0.99f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        Mockito.reset(mSpyController);
        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                    targetCenterX, targetCenterY, mAnimationCallback);
            mCurrentScale.set(mController.getScale());
            mCurrentCenterX.set(mController.getCenterX());
            mCurrentCenterY.set(mController.getCenterY());
            advanceTimeBy(mWaitAnimationDuration);
        });

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
        assertEquals(WindowMagnificationAnimationController.STATE_DISABLED,
                mWindowMagnificationAnimationController.getState());
    }

    @Test
    public void
            enableWindowMagnificationWithScaleLessThanOneAndWithoutCallBack_enabled_expectedValues()
            throws RemoteException {
        enableWindowMagnificationWithoutAnimation();

        final float targetScale = 0.99f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        Mockito.reset(mSpyController);
        enableWindowMagnificationWithoutAnimation(targetScale, targetCenterX, targetCenterY);

        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);
        assertEquals(WindowMagnificationAnimationController.STATE_DISABLED,
                mWindowMagnificationAnimationController.getState());
    }

    @Test
    public void
            enableMagnificationWithoutCallback_enabling_expectedValuesAndInvokeFormerCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);
        final float targetScale = DEFAULT_SCALE - 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        Mockito.reset(mSpyController);
        enableWindowMagnificationWithoutAnimation(targetScale, targetCenterX, targetCenterY);

        verifyFinalSpec(targetScale, targetCenterX, targetCenterY);
        verify(mAnimationCallback).onResult(false);
    }

    @Test
    public void enableWindowMagnificationWithSameSpec_enabling_NoAnimationAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);

        Mockito.reset(mSpyController);
        enableWindowMagnificationAndWaitAnimating(
                mWaitAnimationDuration, Float.NaN, Float.NaN, Float.NaN, mAnimationCallback2);

        verify(mSpyController, never()).enableWindowMagnificationInternal(anyFloat(), anyFloat(),
                anyFloat());
        verify(mAnimationCallback).onResult(false);
        verify(mAnimationCallback2).onResult(true);
    }

    @Test
    public void enableWindowMagnification_disabling_expectedValuesAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationWithoutAnimation();
        deleteWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);
        final float targetScale = DEFAULT_SCALE + 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        Mockito.reset(mSpyController);
        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                    targetCenterX, targetCenterY, mAnimationCallback2);
            mCurrentScale.set(mController.getScale());
            mCurrentCenterX.set(mController.getCenterX());
            mCurrentCenterY.set(mController.getCenterY());
        });

        // Current spec shouldn't match given spec.
        verify(mAnimationCallback2, never()).onResult(anyBoolean());
        verify(mAnimationCallback).onResult(false);

        getInstrumentation().runOnMainSync(() -> {
            // ValueAnimator.reverse() could not work correctly with the AnimatorTestRule since it
            // is using SystemClock in reverse() (b/305731398). Therefore, we call end() on the
            // animator directly to verify the result of animation is correct instead of querying
            // the animation frame at a specific timing.
            mValueAnimator.end();
        });

        verify(mSpyController).enableWindowMagnificationInternal(
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
        deleteWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);
        final float targetScale = DEFAULT_SCALE + 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        Mockito.reset(mSpyController);
        enableWindowMagnificationWithoutAnimation(targetScale, targetCenterX, targetCenterY);

        verify(mAnimationCallback).onResult(false);
        verifyFinalSpec(targetScale, targetCenterX, targetCenterY);
    }

    @Test
    public void enableWindowMagnificationWithSameSpec_disabling_NoAnimationAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationWithoutAnimation();
        deleteWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);

        Mockito.reset(mSpyController);
        enableWindowMagnificationAndWaitAnimating(mWaitAnimationDuration, Float.NaN,
                Float.NaN, Float.NaN, mAnimationCallback2);

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

        Mockito.reset(mSpyController);
        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                    targetCenterX, targetCenterY, mAnimationCallback2);
            mCurrentScale.set(mController.getScale());
            mCurrentCenterX.set(mController.getCenterX());
            mCurrentCenterY.set(mController.getCenterY());
            advanceTimeBy(mWaitAnimationDuration);
        });

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

        Mockito.reset(mSpyController);
        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.enableWindowMagnification(DEFAULT_SCALE,
                    windowBounds.exactCenterX(), windowBounds.exactCenterY(),
                    offsetRatio, offsetRatio, mAnimationCallback);
            advanceTimeBy(mWaitAnimationDuration);
        });

        // Wait for Rects update
        waitForIdleSync();
        final View attachedView = mWindowManager.getAttachedView();
        assertNotNull(attachedView);
        final Rect mirrorViewBound = new Rect();
        final View mirrorView = attachedView.findViewById(R.id.surface_view);
        assertNotNull(mirrorView);
        mirrorView.getBoundsOnScreen(mirrorViewBound);

        assertEquals((int) (offsetRatio * mirrorViewBound.width() / 2),
                (int) (mirrorViewBound.exactCenterX() - windowBounds.exactCenterX()));
        assertEquals((int) (offsetRatio * mirrorViewBound.height() / 2),
                (int) (mirrorViewBound.exactCenterY() - windowBounds.exactCenterY()));
    }

    @Test
    public void moveWindowMagnifierToPosition_enabled_expectedValues() throws RemoteException {
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;
        enableWindowMagnificationWithoutAnimation();

        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                    targetCenterX, targetCenterY, mAnimationCallback);
            advanceTimeBy(mWaitAnimationDuration);
        });

        verify(mAnimationCallback).onResult(true);
        verify(mAnimationCallback, never()).onResult(false);
        verifyFinalSpec(DEFAULT_SCALE, targetCenterX, targetCenterY);
    }

    @Test
    public void moveWindowMagnifierToPositionMultipleTimes_enabled_expectedValuesToLastOne()
            throws RemoteException {
        enableWindowMagnificationWithoutAnimation();

        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                    DEFAULT_CENTER_X + 10, DEFAULT_CENTER_Y + 10, mAnimationCallback);
            mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                    DEFAULT_CENTER_X + 20, DEFAULT_CENTER_Y + 20, mAnimationCallback);
            mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                    DEFAULT_CENTER_X + 30, DEFAULT_CENTER_Y + 30, mAnimationCallback);
            mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                    DEFAULT_CENTER_X + 40, DEFAULT_CENTER_Y + 40, mAnimationCallback2);
            advanceTimeBy(mWaitAnimationDuration);
        });

        // only the last one callback will return true
        verify(mAnimationCallback2).onResult(true);
        // the others will return false
        verify(mAnimationCallback, times(3)).onResult(false);
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X + 40, DEFAULT_CENTER_Y + 40);
    }

    @Test
    public void moveWindowMagnifierToPosition_enabling_expectedValuesToLastOne()
            throws RemoteException {
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        enableWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);

        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                    targetCenterX, targetCenterY, mAnimationCallback2);
            advanceTimeBy(mWaitAnimationDuration);
        });

        // The callback in moveWindowMagnifierToPosition will return true
        verify(mAnimationCallback2).onResult(true);
        // The callback in enableWindowMagnification will return false
        verify(mAnimationCallback).onResult(false);
        verifyFinalSpec(DEFAULT_SCALE, targetCenterX, targetCenterY);
    }

    @Test
    public void moveWindowMagnifierToPositionWithCenterUnchanged_enabling_expectedValuesToDefault()
            throws RemoteException {

        enableWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);

        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.moveWindowMagnifierToPosition(
                    Float.NaN, Float.NaN, mAnimationCallback2);
            advanceTimeBy(mWaitAnimationDuration);
        });

        // The callback in moveWindowMagnifierToPosition will return true
        verify(mAnimationCallback2).onResult(true);
        // The callback in enableWindowMagnification will return false
        verify(mAnimationCallback).onResult(false);
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X, DEFAULT_CENTER_Y);
    }

    @Test
    public void enableWindowMagnificationWithSameScale_enabled_doNothingButInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationWithoutAnimation();

        enableWindowMagnificationAndWaitAnimating(mWaitAnimationDuration, mAnimationCallback);

        verify(mSpyController, never()).enableWindowMagnificationInternal(anyFloat(), anyFloat(),
                anyFloat());
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void deleteWindowMagnification_enabled_expectedValuesAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationWithoutAnimation();

        resetMockObjects();
        deleteWindowMagnificationAndWaitAnimating(mWaitAnimationDuration, mAnimationCallback);

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
        deleteWindowMagnificationAndWaitAnimating(mWaitAnimationDuration, mAnimationCallback);

        Mockito.verifyNoMoreInteractions(mSpyController);
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void deleteWindowMagnification_enabling_expectedValuesAndInvokeCallback()
            throws RemoteException {
        enableWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);

        resetMockObjects();
        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.deleteWindowMagnification(
                    mAnimationCallback2);
            mCurrentScale.set(mController.getScale());
            mCurrentCenterX.set(mController.getCenterX());
            mCurrentCenterY.set(mController.getCenterY());
            // ValueAnimator.reverse() could not work correctly with the AnimatorTestRule since it
            // is using SystemClock in reverse() (b/305731398). Therefore, we call end() on the
            // animator directly to verify the result of animation is correct instead of querying
            // the animation frame at a specific timing.
            mValueAnimator.end();
        });

        // wait for animation returns
        waitForIdleSync();

        // {@link ValueAnimator.AnimatorUpdateListener#onAnimationUpdate(ValueAnimator)} will only
        // be triggered once in {@link ValueAnimator#end()}
        verify(mSpyController).enableWindowMagnificationInternal(
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
        enableWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);

        Mockito.reset(mSpyController);
        deleteWindowMagnificationWithoutAnimation();

        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);
        verify(mAnimationCallback).onResult(false);
    }

    @Test
    public void deleteWindowMagnification_disabling_checkStartAndValues() throws RemoteException {
        enableWindowMagnificationWithoutAnimation();
        deleteWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);

        resetMockObjects();
        deleteWindowMagnificationAndWaitAnimating(mWaitAnimationDuration, mAnimationCallback2);

        verify(mSpyController).enableWindowMagnificationInternal(
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
        enableWindowMagnificationWithoutAnimation();
        deleteWindowMagnificationAndWaitAnimating(mWaitPartialAnimationDuration,
                mAnimationCallback);

        // Verifying that WindowMagnificationController#deleteWindowMagnification is never called
        // in previous steps
        verify(mSpyController, never()).deleteWindowMagnification();

        deleteWindowMagnificationWithoutAnimation();

        verify(mSpyController).deleteWindowMagnification();
        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);
        verify(mAnimationCallback).onResult(false);
    }

    @Test
    public void moveWindowMagnifierWithYXRatioLargerThanBase_enabled_movedOnlyVertically() {
        enableWindowMagnificationWithoutAnimation();

        // should move vertically since offsetY/offsetX > HORIZONTAL_LOCK_BASE
        final float offsetX = 50.0f;
        final float offsetY =
                (float) Math.ceil(offsetX * WindowMagnificationController.HORIZONTAL_LOCK_BASE)
                        + 1.0f;
        getInstrumentation().runOnMainSync(()-> mController.moveWindowMagnifier(offsetX, offsetY));

        verify(mSpyController).moveWindowMagnifier(offsetX, offsetY);
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X, DEFAULT_CENTER_Y + offsetY);
    }

    @Test
    public void moveWindowMagnifierWithYXRatioLessThanBase_enabled_movedOnlyHorizontally() {
        enableWindowMagnificationWithoutAnimation();

        // should move vertically since offsetY/offsetX <= HORIZONTAL_LOCK_BASE
        final float offsetX = 50.0f;
        final float offsetY =
                (float) Math.floor(offsetX * WindowMagnificationController.HORIZONTAL_LOCK_BASE)
                        - 1.0f;
        getInstrumentation().runOnMainSync(() ->
                mController.moveWindowMagnifier(offsetX, offsetY));

        verify(mSpyController).moveWindowMagnifier(offsetX, offsetY);
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X + offsetX, DEFAULT_CENTER_Y);
    }

    @Test
    public void moveWindowMagnifier_magnifierEnabledAndDiagonalEnabled_movedDiagonally() {
        enableWindowMagnificationWithoutAnimation();

        final float offsetX = 50.0f;
        final float offsetY =
                (float) Math.ceil(offsetX * WindowMagnificationController.HORIZONTAL_LOCK_BASE);
        // while diagonal scrolling enabled,
        //  should move with both offsetX and offsetY without regrading offsetY/offsetX
        getInstrumentation().runOnMainSync(() -> {
            mController.setDiagonalScrolling(true);
            mController.moveWindowMagnifier(offsetX, offsetY);
        });

        verify(mSpyController).moveWindowMagnifier(offsetX, offsetY);
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X + offsetX, DEFAULT_CENTER_Y + offsetY);
    }

    @Test
    public void moveWindowMagnifierToPosition_enabled() {
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;
        enableWindowMagnificationWithoutAnimation();

        getInstrumentation().runOnMainSync(() -> {
            mController.moveWindowMagnifierToPosition(targetCenterX, targetCenterY,
                    mAnimationCallback);
            advanceTimeBy(mWaitAnimationDuration);
        });

        verifyFinalSpec(DEFAULT_SCALE, targetCenterX, targetCenterY);
    }

    private void advanceTimeBy(long timeDelta) {
        mAnimatorTestRule.advanceTimeBy(timeDelta);
    }

    private void verifyFinalSpec(float expectedScale, float expectedCenterX,
            float expectedCenterY) {
        assertEquals(expectedScale, mController.getScale(), 0f);
        assertEquals(expectedCenterX, mController.getCenterX(), 0f);
        assertEquals(expectedCenterY, mController.getCenterY(), 0f);
    }

    private void enableWindowMagnificationWithoutAnimation() {
        enableWindowMagnificationWithoutAnimation(
                DEFAULT_SCALE, DEFAULT_CENTER_X, DEFAULT_CENTER_Y);
    }

    private void enableWindowMagnificationWithoutAnimation(
            float targetScale, float targetCenterX, float targetCenterY) {
        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.enableWindowMagnification(
                    targetScale, targetCenterX, targetCenterY, null);
        });
        // wait for animation returns
        waitForIdleSync();
    }

    private void enableWindowMagnificationAndWaitAnimating(long duration,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        enableWindowMagnificationAndWaitAnimating(
                duration, DEFAULT_SCALE, DEFAULT_CENTER_X, DEFAULT_CENTER_Y, callback);
    }

    private void enableWindowMagnificationAndWaitAnimating(
            long duration,
            float targetScale,
            float targetCenterX,
            float targetCenterY,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.enableWindowMagnification(
                    targetScale, targetCenterX, targetCenterY, callback);
            advanceTimeBy(duration);
        });
        // wait for animation returns
        waitForIdleSync();
    }

    private void deleteWindowMagnificationWithoutAnimation() {
        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.deleteWindowMagnification(null);
        });
        // wait for animation returns
        waitForIdleSync();
    }

    private void deleteWindowMagnificationAndWaitAnimating(long duration,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationAnimationController.deleteWindowMagnification(callback);
            advanceTimeBy(duration);
        });
        // wait for animation returns
        waitForIdleSync();
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
                WindowMagnifierCallback callback, SysUiState sysUiState,
                SecureSettings secureSettings) {
            super(
                    context,
                    handler,
                    animationController,
                    sfVsyncFrameProvider,
                    mirrorWindowControl,
                    transaction,
                    callback,
                    sysUiState,
                    WindowManagerGlobal::getWindowSession,
                    secureSettings);
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
            super.moveWindowMagnifier(offsetX, offsetY);
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

    private ValueAnimator newValueAnimator() {
        final ValueAnimator valueAnimator = new ValueAnimator();
        valueAnimator.setDuration(mWaitAnimationDuration);
        valueAnimator.setInterpolator(new AccelerateInterpolator(2.5f));
        valueAnimator.setFloatValues(0.0f, 1.0f);
        return valueAnimator;
    }
}
