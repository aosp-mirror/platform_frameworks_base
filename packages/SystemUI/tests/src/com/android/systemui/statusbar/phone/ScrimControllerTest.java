/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static com.android.systemui.statusbar.phone.ScrimController.VISIBILITY_FULLY_OPAQUE;
import static com.android.systemui.statusbar.phone.ScrimController.VISIBILITY_FULLY_TRANSPARENT;
import static com.android.systemui.statusbar.phone.ScrimController.VISIBILITY_SEMI_TRANSPARENT;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.app.AlarmManager;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.util.wakelock.WakeLock;
import com.android.systemui.utils.os.FakeHandler;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashSet;
import java.util.function.Consumer;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class ScrimControllerTest extends SysuiTestCase {

    private SynchronousScrimController mScrimController;
    private ScrimView mScrimBehind;
    private ScrimView mScrimInFront;
    private Consumer<Float> mScrimBehindAlphaCallback;
    private Consumer<GradientColors> mScrimInFrontColorCallback;
    private Consumer<Integer> mScrimVisibilityCallback;
    private float mScrimBehindAlpha;
    private GradientColors mScrimInFrontColor;
    private int mScrimVisibility;
    private DozeParameters mDozeParamenters;
    private WakeLock mWakeLock;
    private boolean mAlwaysOnEnabled;
    private AlarmManager mAlarmManager;

    @Before
    public void setup() {
        mScrimBehind = spy(new ScrimView(getContext()));
        mScrimInFront = new ScrimView(getContext());
        mWakeLock = mock(WakeLock.class);
        mAlarmManager = mock(AlarmManager.class);
        mAlwaysOnEnabled = true;
        mScrimBehindAlphaCallback = (Float alpha) -> mScrimBehindAlpha = alpha;
        mScrimInFrontColorCallback = (GradientColors color) -> mScrimInFrontColor = color;
        mScrimVisibilityCallback = (Integer visible) -> mScrimVisibility = visible;
        mDozeParamenters = mock(DozeParameters.class);
        when(mDozeParamenters.getAlwaysOn()).thenAnswer(invocation -> mAlwaysOnEnabled);
        when(mDozeParamenters.getDisplayNeedsBlanking()).thenReturn(true);
        mScrimController = new SynchronousScrimController(mScrimBehind, mScrimInFront,
                mScrimBehindAlphaCallback, mScrimInFrontColorCallback, mScrimVisibilityCallback,
                mDozeParamenters, mAlarmManager);
    }

    @Test
    public void initialState() {
        Assert.assertEquals("ScrimController should start initialized",
                mScrimController.getState(), ScrimState.UNINITIALIZED);
    }

    @Test
    public void transitionToKeyguard() {
        mScrimController.transitionTo(ScrimState.KEYGUARD);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible without tint
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_SEMI_TRANSPARENT);
        assertScrimTint(mScrimBehind, false /* tinted */);
    }

    @Test
    public void transitionToAod_withRegularWallpaper() {
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible with tint
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);
        assertScrimTint(mScrimBehind, true /* tinted */);
        assertScrimTint(mScrimInFront, true /* tinted */);
    }

    @Test
    public void transitionToAod_withAodWallpaper() {
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be transparent
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_TRANSPARENT);

        // Move on to PULSING and check if the back scrim is still transparent
        mScrimController.transitionTo(ScrimState.PULSING);
        mScrimController.finishAnimationsImmediately();
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_TRANSPARENT);
    }

    @Test
    public void transitionToAod_withAodWallpaperAndLockScreenWallpaper() {
        ScrimState.AOD.mKeyguardUpdateMonitor = new KeyguardUpdateMonitor(getContext()) {
            @Override
            public boolean hasLockscreenWallpaper() {
                return true;
            }
        };
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible with tint
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);
        assertScrimTint(mScrimBehind, true /* tinted */);
        assertScrimTint(mScrimInFront, true /* tinted */);
    }

    @Test
    public void transitionToPulsing() {
        // Pre-condition
        // Need to go to AoD first because PULSING doesn't change
        // the back scrim opacity - otherwise it would hide AoD wallpapers.
        mScrimController.setWallpaperSupportsAmbientMode(false);
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);

        mScrimController.transitionTo(ScrimState.PULSING);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible with tint
        // Pulse callback should have been invoked
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);
        assertScrimTint(mScrimBehind, true /* tinted */);
    }

    @Test
    public void transitionToKeyguardBouncer() {
        mScrimController.transitionTo(ScrimState.BOUNCER);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible without tint
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_SEMI_TRANSPARENT);
        assertScrimTint(mScrimBehind, false /* tinted */);
    }

    @Test
    public void transitionToBouncer() {
        mScrimController.transitionTo(ScrimState.BOUNCER_SCRIMMED);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible without tint
        assertScrimVisibility(VISIBILITY_SEMI_TRANSPARENT, VISIBILITY_FULLY_TRANSPARENT);
        assertScrimTint(mScrimBehind, false /* tinted */);
    }

    @Test
    public void transitionToUnlocked() {
        mScrimController.setPanelExpansion(0f);
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be transparent
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_TRANSPARENT);
        assertScrimTint(mScrimBehind, false /* tinted */);
        assertScrimTint(mScrimInFront, false /* tinted */);

        // Back scrim should be visible after start dragging
        mScrimController.setPanelExpansion(0.5f);
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_SEMI_TRANSPARENT);
    }

    @Test
    public void panelExpansion() {
        mScrimController.setPanelExpansion(0f);
        mScrimController.setPanelExpansion(0.5f);
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();

        reset(mScrimBehind);
        mScrimController.setPanelExpansion(0f);
        mScrimController.setPanelExpansion(1.0f);
        mScrimController.onPreDraw();

        Assert.assertEquals("Scrim alpha should change after setPanelExpansion",
                mScrimBehindAlpha, mScrimBehind.getViewAlpha(), 0.01f);

        mScrimController.setPanelExpansion(0f);
        mScrimController.onPreDraw();

        Assert.assertEquals("Scrim alpha should change after setPanelExpansion",
                mScrimBehindAlpha, mScrimBehind.getViewAlpha(), 0.01f);
    }

    @Test
    public void panelExpansionAffectsAlpha() {
        mScrimController.setPanelExpansion(0f);
        mScrimController.setPanelExpansion(0.5f);
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();

        final float scrimAlpha = mScrimBehind.getViewAlpha();
        reset(mScrimBehind);
        mScrimController.setExpansionAffectsAlpha(false);
        mScrimController.setPanelExpansion(0.8f);
        verifyZeroInteractions(mScrimBehind);
        Assert.assertEquals("Scrim opacity shouldn't change when setExpansionAffectsAlpha "
                + "is false", scrimAlpha, mScrimBehind.getViewAlpha(), 0.01f);

        mScrimController.setExpansionAffectsAlpha(true);
        mScrimController.setPanelExpansion(0.1f);
        Assert.assertNotEquals("Scrim opacity should change when setExpansionAffectsAlpha "
                + "is true", scrimAlpha, mScrimBehind.getViewAlpha(), 0.01f);
    }

    @Test
    public void transitionToUnlockedFromAod() {
        // Simulate unlock with fingerprint
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.setPanelExpansion(0f);
        mScrimController.finishAnimationsImmediately();
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        // Immediately tinted after the transition starts
        assertScrimTint(mScrimInFront, true /* tinted */);
        assertScrimTint(mScrimBehind, true /* tinted */);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be transparent
        // Neither scrims should be tinted anymore after the animation.
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_TRANSPARENT);
        assertScrimTint(mScrimInFront, false /* tinted */);
        assertScrimTint(mScrimBehind, false /* tinted */);
    }

    @Test
    public void scrimBlanksBeforeLeavingAod() {
        // Simulate unlock with fingerprint
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        mScrimController.transitionTo(ScrimState.UNLOCKED,
                new ScrimController.Callback() {
                    @Override
                    public void onDisplayBlanked() {
                        // Front scrim should be black in the middle of the transition
                        Assert.assertTrue("Scrim should be visible during transition. Alpha: "
                                + mScrimInFront.getViewAlpha(), mScrimInFront.getViewAlpha() > 0);
                        assertScrimTint(mScrimInFront, true /* tinted */);
                        Assert.assertSame("Scrim should be visible during transition.",
                                mScrimVisibility, VISIBILITY_FULLY_OPAQUE);
                    }
                });
        mScrimController.finishAnimationsImmediately();
    }

    @Test
    public void testScrimCallback() {
        int[] callOrder = {0, 0, 0};
        int[] currentCall = {0};
        mScrimController.transitionTo(ScrimState.AOD, new ScrimController.Callback() {
            @Override
            public void onStart() {
                callOrder[0] = ++currentCall[0];
            }

            @Override
            public void onDisplayBlanked() {
                callOrder[1] = ++currentCall[0];
            }

            @Override
            public void onFinished() {
                callOrder[2] = ++currentCall[0];
            }
        });
        mScrimController.finishAnimationsImmediately();
        Assert.assertEquals("onStart called in wrong order", 1, callOrder[0]);
        Assert.assertEquals("onDisplayBlanked called in wrong order", 2, callOrder[1]);
        Assert.assertEquals("onFinished called in wrong order", 3, callOrder[2]);
    }

    @Test
    public void testScrimCallbacksWithoutAmbientDisplay() {
        mAlwaysOnEnabled = false;
        testScrimCallback();
    }

    @Test
    public void testScrimCallbackCancelled() {
        boolean[] cancelledCalled = {false};
        mScrimController.transitionTo(ScrimState.AOD, new ScrimController.Callback() {
            @Override
            public void onCancelled() {
                cancelledCalled[0] = true;
            }
        });
        mScrimController.transitionTo(ScrimState.PULSING);
        Assert.assertTrue("onCancelled should have been called", cancelledCalled[0]);
    }

    @Test
    public void testHoldsWakeLock_whenAOD() {
        mScrimController.transitionTo(ScrimState.AOD);
        verify(mWakeLock).acquire();
        verify(mWakeLock, never()).release();
        mScrimController.finishAnimationsImmediately();
        verify(mWakeLock).release();
    }

    @Test
    public void testDoesNotHoldWakeLock_whenUnlocking() {
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();
        verifyZeroInteractions(mWakeLock);
    }

    @Test
    public void testCallbackInvokedOnSameStateTransition() {
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();
        ScrimController.Callback callback = mock(ScrimController.Callback.class);
        mScrimController.transitionTo(ScrimState.UNLOCKED, callback);
        verify(callback).onFinished();
    }

    @Test
    public void testHoldsAodWallpaperAnimationLock() {
        // Pre-conditions
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        reset(mWakeLock);

        mScrimController.onHideWallpaperTimeout();
        verify(mWakeLock).acquire();
        verify(mWakeLock, never()).release();
        mScrimController.finishAnimationsImmediately();
        verify(mWakeLock).release();
    }

    @Test
    public void testWillHideAodWallpaper() {
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.transitionTo(ScrimState.AOD);
        verify(mAlarmManager).setExact(anyInt(), anyLong(), any(), any(), any());
        mScrimController.transitionTo(ScrimState.KEYGUARD);
        verify(mAlarmManager).cancel(any(AlarmManager.OnAlarmListener.class));
    }

    @Test
    public void testConservesExpansionOpacityAfterTransition() {
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.setPanelExpansion(0.5f);
        mScrimController.finishAnimationsImmediately();

        final float expandedAlpha = mScrimBehind.getViewAlpha();

        mScrimController.transitionTo(ScrimState.BRIGHTNESS_MIRROR);
        mScrimController.finishAnimationsImmediately();
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();

        Assert.assertEquals("Scrim expansion opacity wasn't conserved when transitioning back",
                expandedAlpha, mScrimBehind.getViewAlpha(), 0.01f);
    }

    @Test
    public void cancelsOldAnimationBeforeBlanking() {
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        // Consume whatever value we had before
        mScrimController.wasAnimationJustCancelled();

        mScrimController.transitionTo(ScrimState.KEYGUARD);
        mScrimController.finishAnimationsImmediately();
        Assert.assertTrue(mScrimController.wasAnimationJustCancelled());
    }

    /**
     * Number of visible notifications affects scrim opacity.
     */
    @Test
    public void testNotificationDensity() {
        mScrimController.transitionTo(ScrimState.KEYGUARD);
        mScrimController.finishAnimationsImmediately();

        mScrimController.setNotificationCount(0);
        mScrimController.finishAnimationsImmediately();
        Assert.assertEquals("lower density when no notifications",
                ScrimController.GRADIENT_SCRIM_ALPHA,  mScrimBehind.getViewAlpha(), 0.01f);

        mScrimController.setNotificationCount(3);
        mScrimController.finishAnimationsImmediately();
        Assert.assertEquals("stronger density when notifications are visible",
                ScrimController.GRADIENT_SCRIM_ALPHA_BUSY,  mScrimBehind.getViewAlpha(), 0.01f);
    }

    /**
     * Moving from/to states conserves old notification density.
     */
    @Test
    public void testConservesNotificationDensity() {
        testConservesNotificationDensity(0 /* count */, ScrimController.GRADIENT_SCRIM_ALPHA);
        testConservesNotificationDensity(3 /* count */, ScrimController.GRADIENT_SCRIM_ALPHA_BUSY);
    }

    @Test
    public void testScrimFocus() {
        mScrimController.transitionTo(ScrimState.AOD);
        Assert.assertFalse("Should not be focusable on AOD", mScrimBehind.isFocusable());
        Assert.assertFalse("Should not be focusable on AOD", mScrimInFront.isFocusable());

        mScrimController.transitionTo(ScrimState.KEYGUARD);
        Assert.assertTrue("Should be focusable on keyguard", mScrimBehind.isFocusable());
        Assert.assertTrue("Should be focusable on keyguard", mScrimInFront.isFocusable());
    }

    @Test
    public void testHidesShowWhenLockedActivity() {
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.setKeyguardOccluded(true);
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);

        mScrimController.transitionTo(ScrimState.PULSING);
        mScrimController.finishAnimationsImmediately();
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);
    }

    @Test
    public void testEatsTouchEvent() {
        HashSet<ScrimState> eatsTouches =
                new HashSet<>(Arrays.asList(ScrimState.AOD, ScrimState.PULSING));
        for (ScrimState state : ScrimState.values()) {
            if (state == ScrimState.UNINITIALIZED) {
                continue;
            }
            mScrimController.transitionTo(state);
            mScrimController.finishAnimationsImmediately();
            Assert.assertEquals("Should be clickable unless AOD or PULSING, was: " + state,
                    mScrimBehind.getViewAlpha() != 0 && !eatsTouches.contains(state),
                    mScrimBehind.isClickable());
        }
    }

    @Test
    public void testAnimatesTransitionToAod() {
        when(mDozeParamenters.shouldControlScreenOff()).thenReturn(false);
        ScrimState.AOD.prepare(ScrimState.KEYGUARD);
        Assert.assertFalse("No animation when ColorFade kicks in",
                ScrimState.AOD.getAnimateChange());

        reset(mDozeParamenters);
        when(mDozeParamenters.shouldControlScreenOff()).thenReturn(true);
        ScrimState.AOD.prepare(ScrimState.KEYGUARD);
        Assert.assertTrue("Animate scrims when ColorFade won't be triggered",
                ScrimState.AOD.getAnimateChange());
    }

    /**
     * Conserves old notification density after leaving state and coming back.
     *
     * @param count How many notification.
     * @param expectedAlpha Expected alpha.
     */
    private void testConservesNotificationDensity(int count, float expectedAlpha) {
        mScrimController.setNotificationCount(count);
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();

        mScrimController.transitionTo(ScrimState.KEYGUARD);
        mScrimController.finishAnimationsImmediately();

        Assert.assertEquals("Doesn't respect notification busyness after transition",
                expectedAlpha,  mScrimBehind.getViewAlpha(), 0.01f);
    }

    private void assertScrimTint(ScrimView scrimView, boolean tinted) {
        final boolean viewIsTinted = scrimView.getTint() != Color.TRANSPARENT;
        final String name = scrimView == mScrimInFront ? "front" : "back";
        Assert.assertEquals("Tint test failed at state " + mScrimController.getState()
                +" with scrim: " + name + " and tint: " + Integer.toHexString(scrimView.getTint()),
                tinted, viewIsTinted);
    }

    private void assertScrimVisibility(int inFront, int behind) {
        boolean inFrontVisible = inFront != ScrimController.VISIBILITY_FULLY_TRANSPARENT;
        boolean behindVisible = behind != ScrimController.VISIBILITY_FULLY_TRANSPARENT;
        Assert.assertEquals("Unexpected front scrim visibility. Alpha is "
                + mScrimInFront.getViewAlpha(), inFrontVisible, mScrimInFront.getViewAlpha() > 0);
        Assert.assertEquals("Unexpected back scrim visibility. Alpha is "
                + mScrimBehind.getViewAlpha(), behindVisible, mScrimBehind.getViewAlpha() > 0);

        final int visibility;
        if (inFront == VISIBILITY_FULLY_OPAQUE || behind == VISIBILITY_FULLY_OPAQUE) {
            visibility = VISIBILITY_FULLY_OPAQUE;
        } else if (inFront > VISIBILITY_FULLY_TRANSPARENT || behind > VISIBILITY_FULLY_TRANSPARENT) {
            visibility = VISIBILITY_SEMI_TRANSPARENT;
        } else {
            visibility = VISIBILITY_FULLY_TRANSPARENT;
        }
        Assert.assertEquals("Invalid visibility.", visibility, mScrimVisibility);
    }

    /**
     * Special version of ScrimController where animations have 0 duration for test purposes.
     */
    private class SynchronousScrimController extends ScrimController {

        private FakeHandler mHandler;
        private boolean mAnimationCancelled;
        boolean mOnPreDrawCalled;

        SynchronousScrimController(ScrimView scrimBehind, ScrimView scrimInFront,
                Consumer<Float> scrimBehindAlphaListener,
                Consumer<GradientColors> scrimInFrontColorListener,
                Consumer<Integer> scrimVisibleListener, DozeParameters dozeParameters,
                AlarmManager alarmManager) {
            super(scrimBehind, scrimInFront, scrimBehindAlphaListener, scrimInFrontColorListener,
                    scrimVisibleListener, dozeParameters, alarmManager);
            mHandler = new FakeHandler(Looper.myLooper());
        }

        @Override
        public boolean onPreDraw() {
            mOnPreDrawCalled = true;
            return super.onPreDraw();
        }

        void finishAnimationsImmediately() {
            boolean[] animationFinished = {false};
            setOnAnimationFinished(()-> animationFinished[0] = true);

            // Execute code that will trigger animations.
            onPreDraw();

            // Force finish screen blanking.
            mHandler.dispatchQueuedMessages();
            // Force finish all animations.
            endAnimation(mScrimBehind, TAG_KEY_ANIM);
            endAnimation(mScrimInFront, TAG_KEY_ANIM);

            if (!animationFinished[0]) {
                throw new IllegalStateException("Animation never finished");
            }
        }

        boolean wasAnimationJustCancelled() {
            final boolean wasCancelled = mAnimationCancelled;
            mAnimationCancelled = false;
            return wasCancelled;
        }

        private void endAnimation(View scrimView, int tag) {
            Animator animator = (Animator) scrimView.getTag(tag);
            if (animator != null) {
                animator.end();
            }
        }

        @Override
        protected void cancelAnimator(ValueAnimator previousAnimator) {
            super.cancelAnimator(previousAnimator);
            mAnimationCancelled = true;
        }

        @Override
        protected Handler getHandler() {
            return mHandler;
        }

        @Override
        protected WakeLock createWakeLock() {
            return mWakeLock;
        }

        /**
         * Do not wait for a frame since we're in a test environment.
         * @param callback What to execute.
         */
        @Override
        protected void doOnTheNextFrame(Runnable callback) {
            callback.run();
        }
    }

}
