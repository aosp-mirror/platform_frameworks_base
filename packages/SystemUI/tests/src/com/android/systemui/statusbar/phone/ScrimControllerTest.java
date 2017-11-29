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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.util.wakelock.WakeLock;
import com.android.systemui.utils.os.FakeHandler;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class ScrimControllerTest extends SysuiTestCase {

    private SynchronousScrimController mScrimController;
    private ScrimView mScrimBehind;
    private ScrimView mScrimInFront;
    private View mHeadsUpScrim;
    private Consumer<Boolean> mScrimVisibilityCallback;
    private Boolean mScrimVisibile;
    private LightBarController mLightBarController;
    private DozeParameters mDozeParamenters;
    private WakeLock mWakeLock;
    private boolean mAlwaysOnEnabled;

    @Before
    public void setup() {
        mLightBarController = mock(LightBarController.class);
        mScrimBehind = new ScrimView(getContext());
        mScrimInFront = new ScrimView(getContext());
        mHeadsUpScrim = mock(View.class);
        mWakeLock = mock(WakeLock.class);
        mAlwaysOnEnabled = true;
        mScrimVisibilityCallback = (Boolean visible) -> mScrimVisibile = visible;
        mDozeParamenters = mock(DozeParameters.class);
        when(mDozeParamenters.getAlwaysOn()).thenAnswer(invocation -> mAlwaysOnEnabled);
        mScrimController = new SynchronousScrimController(mLightBarController, mScrimBehind,
                mScrimInFront, mHeadsUpScrim, mScrimVisibilityCallback, mDozeParamenters);
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
        assertScrimVisibility(false /* front */, true /* behind */);
        assertScrimTint(mScrimBehind, false /* tinted */);
    }

    @Test
    public void transitionToAod() {
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible with tint
        assertScrimVisibility(false /* front */, true /* behind */);
        assertScrimTint(mScrimBehind, true /* tinted */);
        assertScrimTint(mScrimInFront, true /* tinted */);
    }

    @Test
    public void transitionToPulsing() {
        mScrimController.transitionTo(ScrimState.PULSING);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible with tint
        // Pulse callback should have been invoked
        assertScrimVisibility(false /* front */, true /* behind */);
        assertScrimTint(mScrimBehind, true /* tinted */);
    }

    @Test
    public void transitionToBouncer() {
        mScrimController.transitionTo(ScrimState.BOUNCER);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible without tint
        assertScrimVisibility(true /* front */, true /* behind */);
        assertScrimTint(mScrimBehind, false /* tinted */);
    }

    @Test
    public void transitionToUnlocked() {
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be transparent
        assertScrimVisibility(false /* front */, false /* behind */);
        assertScrimTint(mScrimBehind, false /* tinted */);
        assertScrimTint(mScrimInFront, false /* tinted */);

        // Back scrim should be visible after start dragging
        mScrimController.setPanelExpansion(0.5f);
        assertScrimVisibility(false /* front */, true /* behind */);
    }

    @Test
    public void transitionToUnlockedFromAod() {
        // Simulate unlock with fingerprint
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        // Immediately tinted after the transition starts
        assertScrimTint(mScrimInFront, true /* tinted */);
        assertScrimTint(mScrimBehind, true /* tinted */);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be transparent
        // Neither scrims should be tinted anymore after the animation.
        assertScrimVisibility(false /* front */, false /* behind */);
        assertScrimTint(mScrimInFront, false /* tinted */);
        assertScrimTint(mScrimBehind, false /* tinted */);
    }

    @Test
    public void scrimBlanksBeforeLeavingAoD() {
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
                        Assert.assertTrue("Scrim should be visible during transition.",
                                mScrimVisibile);
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
    public void testHoldsWakeLock() {
        mScrimController.transitionTo(ScrimState.AOD);
        verify(mWakeLock, times(1)).acquire();
        verify(mWakeLock, never()).release();
        mScrimController.finishAnimationsImmediately();
        verify(mWakeLock, times(1)).release();
    }

    private void assertScrimTint(ScrimView scrimView, boolean tinted) {
        final boolean viewIsTinted = scrimView.getTint() != Color.TRANSPARENT;
        final String name = scrimView == mScrimInFront ? "front" : "back";
        Assert.assertEquals("Tint test failed at state " + mScrimController.getState()
                +" with scrim: " + name + " and tint: " + Integer.toHexString(scrimView.getTint()),
                tinted, viewIsTinted);
    }

    private void assertScrimVisibility(boolean inFront, boolean behind) {
        Assert.assertEquals("Unexpected front scrim visibility. Alpha is "
                + mScrimInFront.getViewAlpha(), inFront, mScrimInFront.getViewAlpha() > 0);
        Assert.assertEquals("Unexpected back scrim visibility. Alpha is "
                + mScrimBehind.getViewAlpha(), behind, mScrimBehind.getViewAlpha() > 0);
        Assert.assertEquals("Invalid visibility.", inFront || behind, mScrimVisibile);
    }

    /**
     * Special version of ScrimController where animations have 0 duration for test purposes.
     */
    private class SynchronousScrimController extends ScrimController {

        private FakeHandler mHandler;

        public SynchronousScrimController(LightBarController lightBarController,
                ScrimView scrimBehind, ScrimView scrimInFront, View headsUpScrim,
                Consumer<Boolean> scrimVisibleListener, DozeParameters dozeParameters) {
            super(lightBarController, scrimBehind, scrimInFront, headsUpScrim,
                    scrimVisibleListener, dozeParameters);
            mHandler = new FakeHandler(Looper.myLooper());
        }

        public void finishAnimationsImmediately() {
            boolean[] animationFinished = {false};
            setOnAnimationFinished(()-> animationFinished[0] = true);

            // Execute code that will trigger animations.
            onPreDraw();

            // Force finish screen blanking.
            endAnimation(mScrimInFront, TAG_KEY_ANIM_BLANK);
            mHandler.dispatchQueuedMessages();
            // Force finish all animations.
            endAnimation(mScrimBehind, TAG_KEY_ANIM);
            endAnimation(mScrimInFront, TAG_KEY_ANIM);

            if (!animationFinished[0]) {
                throw new IllegalStateException("Animation never finished");
            }
        }

        private void endAnimation(ScrimView scrimView, int tag) {
            Animator animator = (Animator) scrimView.getTag(tag);
            if (animator != null) {
                animator.end();
            }
        }

        @Override
        protected Handler getHandler() {
            return mHandler;
        }

        @Override
        protected WakeLock createWakeLock() {
            return mWakeLock;
        }
    }

}
