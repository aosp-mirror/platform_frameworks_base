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

import static com.android.systemui.statusbar.phone.ScrimController.OPAQUE;
import static com.android.systemui.statusbar.phone.ScrimController.SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.ScrimController.TRANSPARENT;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.app.AlarmManager;
import android.graphics.Color;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.DejankUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.statusbar.BlurUtils;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.wakelock.DelayedWakeLock;
import com.android.systemui.utils.os.FakeHandler;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class ScrimControllerTest extends SysuiTestCase {

    private ScrimController mScrimController;
    private ScrimView mScrimBehind;
    private ScrimView mScrimInFront;
    private ScrimView mScrimForBubble;
    private ScrimState mScrimState;
    private float mScrimBehindAlpha;
    private GradientColors mScrimInFrontColor;
    private int mScrimVisibility;
    private boolean mAlwaysOnEnabled;
    private TestableLooper mLooper;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private DozeParameters mDozeParamenters;
    @Mock
    LightBarController mLightBarController;
    @Mock
    DelayedWakeLock.Builder mDelayedWakeLockBuilder;
    @Mock
    private DelayedWakeLock mWakeLock;
    @Mock
    KeyguardStateController mKeyguardStateController;
    @Mock
    KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private SysuiColorExtractor mSysuiColorExtractor;
    @Mock
    private DockManager mDockManager;
    @Mock
    private BlurUtils mBlurUtils;


    private static class AnimatorListener implements Animator.AnimatorListener {
        private int mNumStarts;
        private int mNumEnds;
        private int mNumCancels;

        @Override
        public void onAnimationStart(Animator animation) {
            mNumStarts++;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            mNumEnds++;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mNumCancels++;
        }

        @Override
        public void onAnimationRepeat(Animator animation) {

        }

        public int getNumStarts() {
            return mNumStarts;
        }

        public int getNumEnds() {
            return mNumEnds;
        }

        public int getNumCancels() {
            return mNumCancels;
        }

        public void reset() {
            mNumStarts = 0;
            mNumEnds = 0;
            mNumCancels = 0;
        }
    };

    private AnimatorListener mAnimatorListener = new AnimatorListener();


    private void finishAnimationsImmediately() {
        // Execute code that will trigger animations.
        mScrimController.onPreDraw();
        // Force finish all animations.
        mLooper.processAllMessages();
        endAnimation(mScrimBehind);
        endAnimation(mScrimInFront);
        endAnimation(mScrimForBubble);

        Assert.assertEquals("Animators did not finish",
                mAnimatorListener.getNumStarts(), mAnimatorListener.getNumEnds());
    }

    private void endAnimation(View scrimView) {
        Animator animator = getAnimator(scrimView);
        if (animator != null) {
            animator.end();
        }
    }

    private Animator getAnimator(View scrimView) {
        return (Animator) scrimView.getTag(ScrimController.TAG_KEY_ANIM);
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mScrimBehind = spy(new ScrimView(getContext()));
        mScrimInFront = new ScrimView(getContext());
        mScrimForBubble = new ScrimView(getContext());
        mAlwaysOnEnabled = true;
        mLooper = TestableLooper.get(this);
        DejankUtils.setImmediate(true);

        // ScrimController uses mScrimBehind to delay some callbacks that we should run immediately.
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mScrimBehind).postOnAnimationDelayed(any(Runnable.class), anyLong());

        when(mDozeParamenters.getAlwaysOn()).thenAnswer(invocation -> mAlwaysOnEnabled);
        when(mDozeParamenters.getDisplayNeedsBlanking()).thenReturn(true);

        doAnswer((Answer<Void>) invocation -> {
            mScrimState = invocation.getArgument(0);
            mScrimBehindAlpha = invocation.getArgument(1);
            mScrimInFrontColor = invocation.getArgument(2);
            return null;
        }).when(mLightBarController).setScrimState(
                any(ScrimState.class), anyFloat(), any(GradientColors.class));

        when(mDelayedWakeLockBuilder.setHandler(any(Handler.class)))
                .thenReturn(mDelayedWakeLockBuilder);
        when(mDelayedWakeLockBuilder.setTag(any(String.class)))
                .thenReturn(mDelayedWakeLockBuilder);
        when(mDelayedWakeLockBuilder.build()).thenReturn(mWakeLock);

        when(mSysuiColorExtractor.getNeutralColors()).thenReturn(new GradientColors());

        when(mDockManager.isDocked()).thenReturn(false);

        mScrimController = new ScrimController(mLightBarController,
                mDozeParamenters, mAlarmManager, mKeyguardStateController, mDelayedWakeLockBuilder,
                new FakeHandler(mLooper.getLooper()), mKeyguardUpdateMonitor, mSysuiColorExtractor,
                mDockManager, mBlurUtils);
        mScrimController.setScrimVisibleListener(visible -> mScrimVisibility = visible);
        mScrimController.attachViews(mScrimBehind, mScrimInFront, mScrimForBubble);
        mScrimController.setAnimatorListener(mAnimatorListener);

        mScrimController.setHasBackdrop(false);
        mScrimController.setWallpaperSupportsAmbientMode(false);
        mScrimController.transitionTo(ScrimState.KEYGUARD);
        finishAnimationsImmediately();
    }

    @After
    public void tearDown() {
        finishAnimationsImmediately();
        DejankUtils.setImmediate(false);
    }

    @Test
    public void transitionToKeyguard() {
        mScrimController.transitionTo(ScrimState.KEYGUARD);
        finishAnimationsImmediately();

        assertScrimAlpha(TRANSPARENT /* front */,
                SEMI_TRANSPARENT /* back */,
                TRANSPARENT /* bubble */);

        assertScrimTint(true /* front */,
                true /* behind */,
                false /* bubble */);
    }

    @Test
    public void transitionToOff() {
        mScrimController.transitionTo(ScrimState.OFF);
        finishAnimationsImmediately();

        assertScrimAlpha(OPAQUE /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        assertScrimTint(true /* front */,
                true /* behind */,
                false /* bubble */);
    }

    @Test
    public void transitionToAod_withRegularWallpaper() {
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();

        assertScrimAlpha(TRANSPARENT /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        assertScrimTint(true /* front */,
                true /* behind */,
                false /* bubble */);
    }

    @Test
    public void transitionToAod_withAodWallpaper() {
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();

        assertScrimAlpha(TRANSPARENT /* front */,
                TRANSPARENT /* back */,
                TRANSPARENT /* bubble */);

        // Pulsing notification should conserve AOD wallpaper.
        mScrimController.transitionTo(ScrimState.PULSING);
        finishAnimationsImmediately();

        assertScrimAlpha(TRANSPARENT /* front */,
                TRANSPARENT /* back */,
                TRANSPARENT /* bubble */);
    }

    @Test
    public void transitionToAod_withAodWallpaperAndLockScreenWallpaper() {
        mScrimController.setHasBackdrop(true);
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();

        assertScrimAlpha(TRANSPARENT /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        assertScrimTint(true /* front */,
                true /* behind */,
                false /* bubble */);
    }

    @Test
    public void setHasBackdrop_withAodWallpaperAndAlbumArt() {
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        mScrimController.setHasBackdrop(true);
        finishAnimationsImmediately();

        assertScrimAlpha(TRANSPARENT /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        assertScrimTint(true /* front */,
                true /* behind */,
                false /* bubble */);
    }

    @Test
    public void transitionToAod_withFrontAlphaUpdates() {
        // Assert that setting the AOD front scrim alpha doesn't take effect in a non-AOD state.
        mScrimController.transitionTo(ScrimState.KEYGUARD);
        mScrimController.setAodFrontScrimAlpha(0.5f);
        finishAnimationsImmediately();

        assertScrimAlpha(TRANSPARENT /* front */,
                SEMI_TRANSPARENT /* back */,
                TRANSPARENT /* bubble */);

        // ... but that it does take effect once we enter the AOD state.
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        assertScrimAlpha(SEMI_TRANSPARENT /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        // ... and that if we set it while we're in AOD, it does take immediate effect.
        mScrimController.setAodFrontScrimAlpha(1f);
        assertScrimAlpha(OPAQUE /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        // ... and make sure we recall the previous front scrim alpha even if we transition away
        // for a bit.
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        assertScrimAlpha(OPAQUE /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        // ... and alpha updates should be completely ignored if always_on is off.
        // Passing it forward would mess up the wake-up transition.
        mAlwaysOnEnabled = false;
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        mScrimController.setAodFrontScrimAlpha(0.3f);
        Assert.assertEquals(ScrimState.AOD.getFrontAlpha(), mScrimInFront.getViewAlpha(), 0.001f);
        Assert.assertNotEquals(0.3f, mScrimInFront.getViewAlpha(), 0.001f);

        // Reset value since enums are static.
        mScrimController.setAodFrontScrimAlpha(0f);
    }

    @Test
    public void transitionToAod_afterDocked_ignoresAlwaysOnAndUpdatesFrontAlpha() {
        // Assert that setting the AOD front scrim alpha doesn't take effect in a non-AOD state.
        mScrimController.transitionTo(ScrimState.KEYGUARD);
        mScrimController.setAodFrontScrimAlpha(0.5f);
        finishAnimationsImmediately();

        assertScrimAlpha(TRANSPARENT /* front */,
                SEMI_TRANSPARENT /* back */,
                TRANSPARENT /* bubble */);

        // ... and doesn't take effect when disabled always_on
        mAlwaysOnEnabled = false;
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        assertScrimAlpha(OPAQUE /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        // ... but will take effect after docked
        when(mDockManager.isDocked()).thenReturn(true);
        mScrimController.transitionTo(ScrimState.KEYGUARD);
        mScrimController.setAodFrontScrimAlpha(0.5f);
        mScrimController.transitionTo(ScrimState.AOD);

        assertScrimAlpha(SEMI_TRANSPARENT /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        // ... and that if we set it while we're in AOD, it does take immediate effect after docked.
        mScrimController.setAodFrontScrimAlpha(1f);
        assertScrimAlpha(OPAQUE /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        // Reset value since enums are static.
        mScrimController.setAodFrontScrimAlpha(0f);
    }

    @Test
    public void transitionToPulsing_withFrontAlphaUpdates() {
        // Pre-condition
        // Need to go to AoD first because PULSING doesn't change
        // the back scrim opacity - otherwise it would hide AoD wallpapers.
        mScrimController.setWallpaperSupportsAmbientMode(false);
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        assertScrimAlpha(TRANSPARENT /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        mScrimController.transitionTo(ScrimState.PULSING);
        finishAnimationsImmediately();
        // Front scrim should be transparent, but tinted
        // Back scrim should be semi-transparent so the user can see the wallpaper
        // Pulse callback should have been invoked
        assertScrimAlpha(TRANSPARENT /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        assertScrimTint(true /* front */,
                true /* behind */,
                false /* bubble */);

        // ... and when ambient goes dark, front scrim should be semi-transparent
        mScrimController.setAodFrontScrimAlpha(0.5f);
        finishAnimationsImmediately();
        // Front scrim should be semi-transparent
        assertScrimAlpha(SEMI_TRANSPARENT /* front */,
                OPAQUE /* back */,
                TRANSPARENT /* bubble */);

        mScrimController.setWakeLockScreenSensorActive(true);
        finishAnimationsImmediately();
        assertScrimAlpha(SEMI_TRANSPARENT /* front */,
                SEMI_TRANSPARENT /* back */,
                TRANSPARENT /* bubble */);

        // Reset value since enums are static.
        mScrimController.setAodFrontScrimAlpha(0f);
    }

    @Test
    public void transitionToKeyguardBouncer() {
        mScrimController.transitionTo(ScrimState.BOUNCER);
        finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible without tint
        assertScrimAlpha(TRANSPARENT /* front */,
                SEMI_TRANSPARENT /* back */,
                TRANSPARENT /* bubble */);

        assertScrimTint(false /* front */,
                false /* behind */,
                false /* bubble */);
    }

    @Test
    public void transitionToBouncer() {
        mScrimController.transitionTo(ScrimState.BOUNCER_SCRIMMED);
        finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible without tint
        assertScrimAlpha(SEMI_TRANSPARENT /* front */,
                TRANSPARENT /* back */,
                TRANSPARENT /* bubble */);
        assertScrimTint(false /* front */,
                false /* behind */,
                false /* bubble */);
    }

    @Test
    public void transitionToUnlocked() {
        mScrimController.setPanelExpansion(0f);
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();
        assertScrimAlpha(TRANSPARENT /* front */,
                TRANSPARENT /* back */,
                TRANSPARENT /* bubble */);

        assertScrimTint(false /* front */,
                false /* behind */,
                false /* bubble */);

        // Back scrim should be visible after start dragging
        mScrimController.setPanelExpansion(0.5f);
        assertScrimAlpha(TRANSPARENT /* front */,
                SEMI_TRANSPARENT /* back */,
                TRANSPARENT /* bubble */);
    }

    @Test
    public void transitionToBubbleExpanded() {
        mScrimController.transitionTo(ScrimState.BUBBLE_EXPANDED);
        finishAnimationsImmediately();

        assertScrimTint(false /* front */,
                false /* behind */,
                false /* bubble */);

        // Front scrim should be transparent
        Assert.assertEquals(ScrimController.TRANSPARENT,
                mScrimInFront.getViewAlpha(), 0.0f);
        // Back scrim should be visible
        Assert.assertEquals(ScrimController.BUSY_SCRIM_ALPHA,
                mScrimBehind.getViewAlpha(), 0.0f);
        // Bubble scrim should be visible
        Assert.assertEquals(ScrimController.BUSY_SCRIM_ALPHA,
                mScrimBehind.getViewAlpha(), 0.0f);
    }

    @Test
    public void scrimStateCallback() {
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();
        Assert.assertEquals(mScrimState, ScrimState.UNLOCKED);

        mScrimController.transitionTo(ScrimState.BOUNCER);
        finishAnimationsImmediately();
        Assert.assertEquals(mScrimState, ScrimState.BOUNCER);

        mScrimController.transitionTo(ScrimState.BOUNCER_SCRIMMED);
        finishAnimationsImmediately();
        Assert.assertEquals(mScrimState, ScrimState.BOUNCER_SCRIMMED);
    }

    @Test
    public void panelExpansion() {
        mScrimController.setPanelExpansion(0f);
        mScrimController.setPanelExpansion(0.5f);
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();

        reset(mScrimBehind);
        mScrimController.setPanelExpansion(0f);
        mScrimController.setPanelExpansion(1.0f);
        finishAnimationsImmediately();

        Assert.assertEquals("Scrim alpha should change after setPanelExpansion",
                mScrimBehindAlpha, mScrimBehind.getViewAlpha(), 0.01f);

        mScrimController.setPanelExpansion(0f);
        finishAnimationsImmediately();

        Assert.assertEquals("Scrim alpha should change after setPanelExpansion",
                mScrimBehindAlpha, mScrimBehind.getViewAlpha(), 0.01f);
    }

    @Test
    public void panelExpansionAffectsAlpha() {
        mScrimController.setPanelExpansion(0f);
        mScrimController.setPanelExpansion(0.5f);
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();

        final float scrimAlpha = mScrimBehind.getViewAlpha();
        reset(mScrimBehind);
        mScrimController.setExpansionAffectsAlpha(false);
        mScrimController.setPanelExpansion(0.8f);
        verifyZeroInteractions(mScrimBehind);
        Assert.assertEquals("Scrim opacity shouldn't change when setExpansionAffectsAlpha "
                + "is false", scrimAlpha, mScrimBehind.getViewAlpha(), 0.01f);

        mScrimController.setExpansionAffectsAlpha(true);
        mScrimController.setPanelExpansion(0.1f);
        finishAnimationsImmediately();
        Assert.assertNotEquals("Scrim opacity should change when setExpansionAffectsAlpha "
                + "is true", scrimAlpha, mScrimBehind.getViewAlpha(), 0.01f);
    }

    @Test
    public void transitionToUnlockedFromAod() {
        // Simulate unlock with fingerprint
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.setPanelExpansion(0f);
        finishAnimationsImmediately();
        mScrimController.transitionTo(ScrimState.UNLOCKED);

        // Immediately tinted black after the transition starts
        assertScrimTint(true /* front */,
                true /* behind */,
                true  /* bubble */);

        finishAnimationsImmediately();

        // All scrims should be transparent at the end of fade transition.
        assertScrimAlpha(TRANSPARENT /* front */,
                TRANSPARENT /* behind */,
                TRANSPARENT /* bubble */);

        // Make sure at the very end of the animation, we're reset to transparent
        assertScrimTint(false /* front */,
                false /* behind */,
                false  /* bubble */);
    }

    @Test
    public void scrimBlanksBeforeLeavingAod() {
        // Simulate unlock with fingerprint
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        mScrimController.transitionTo(ScrimState.UNLOCKED,
                new ScrimController.Callback() {
                    @Override
                    public void onDisplayBlanked() {
                        // Front scrim should be black in the middle of the transition
                        Assert.assertTrue("Scrim should be visible during transition. Alpha: "
                                + mScrimInFront.getViewAlpha(), mScrimInFront.getViewAlpha() > 0);
                        assertScrimTint(true /* front */,
                                true /* behind */,
                                true /* bubble */);
                        Assert.assertSame("Scrim should be visible during transition.",
                                mScrimVisibility, OPAQUE);
                    }
                });
        finishAnimationsImmediately();
    }

    @Test
    public void scrimBlanksWhenUnlockingFromPulse() {
        boolean[] blanked = {false};
        // Simulate unlock with fingerprint
        mScrimController.transitionTo(ScrimState.PULSING);
        finishAnimationsImmediately();
        mScrimController.transitionTo(ScrimState.UNLOCKED,
                new ScrimController.Callback() {
                    @Override
                    public void onDisplayBlanked() {
                        blanked[0] = true;
                    }
                });
        finishAnimationsImmediately();
        Assert.assertTrue("Scrim should blank when unlocking from pulse.", blanked[0]);
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
        finishAnimationsImmediately();
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
        verify(mWakeLock).acquire(anyString());
        verify(mWakeLock, never()).release(anyString());
        finishAnimationsImmediately();
        verify(mWakeLock).release(anyString());
    }

    @Test
    public void testDoesNotHoldWakeLock_whenUnlocking() {
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();
        verifyZeroInteractions(mWakeLock);
    }

    @Test
    public void testCallbackInvokedOnSameStateTransition() {
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();
        ScrimController.Callback callback = mock(ScrimController.Callback.class);
        mScrimController.transitionTo(ScrimState.UNLOCKED, callback);
        verify(callback).onFinished();
    }

    @Test
    public void testHoldsAodWallpaperAnimationLock() {
        // Pre-conditions
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        reset(mWakeLock);

        mScrimController.onHideWallpaperTimeout();
        verify(mWakeLock).acquire(anyString());
        verify(mWakeLock, never()).release(anyString());
        finishAnimationsImmediately();
        verify(mWakeLock).release(anyString());
    }

    @Test
    public void testHoldsPulsingWallpaperAnimationLock() {
        // Pre-conditions
        mScrimController.transitionTo(ScrimState.PULSING);
        finishAnimationsImmediately();
        reset(mWakeLock);

        mScrimController.onHideWallpaperTimeout();
        verify(mWakeLock).acquire(anyString());
        verify(mWakeLock, never()).release(anyString());
        finishAnimationsImmediately();
        verify(mWakeLock).release(anyString());
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
    public void testWillHideDockedWallpaper() {
        mAlwaysOnEnabled = false;
        when(mDockManager.isDocked()).thenReturn(true);
        mScrimController.setWallpaperSupportsAmbientMode(true);

        mScrimController.transitionTo(ScrimState.AOD);

        verify(mAlarmManager).setExact(anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    public void testConservesExpansionOpacityAfterTransition() {
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.setPanelExpansion(0.5f);
        finishAnimationsImmediately();

        final float expandedAlpha = mScrimBehind.getViewAlpha();

        mScrimController.transitionTo(ScrimState.BRIGHTNESS_MIRROR);
        finishAnimationsImmediately();
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();

        Assert.assertEquals("Scrim expansion opacity wasn't conserved when transitioning back",
                expandedAlpha, mScrimBehind.getViewAlpha(), 0.01f);
    }

    @Test
    public void testCancelsOldAnimationBeforeBlanking() {
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        // Consume whatever value we had before
        mAnimatorListener.reset();

        mScrimController.transitionTo(ScrimState.KEYGUARD);
        finishAnimationsImmediately();
        Assert.assertTrue("Animators not canceled", mAnimatorListener.getNumCancels() != 0);
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
        finishAnimationsImmediately();
        assertScrimAlpha(TRANSPARENT /* front */,
                OPAQUE /* behind */,
                TRANSPARENT /* bubble */);

        mScrimController.transitionTo(ScrimState.PULSING);
        finishAnimationsImmediately();
        assertScrimAlpha(TRANSPARENT /* front */,
                OPAQUE /* behind */,
                TRANSPARENT /* bubble */);
    }

    @Test
    public void testHidesShowWhenLockedActivity_whenAlreadyInAod() {
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.transitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        assertScrimAlpha(TRANSPARENT /* front */,
                TRANSPARENT /* behind */,
                TRANSPARENT /* bubble */);

        mScrimController.setKeyguardOccluded(true);
        finishAnimationsImmediately();
        assertScrimAlpha(TRANSPARENT /* front */,
                OPAQUE /* behind */,
                TRANSPARENT /* bubble */);
    }

    @Test
    public void testEatsTouchEvent() {
        HashSet<ScrimState> eatsTouches =
                new HashSet<>(Collections.singletonList(ScrimState.AOD));
        for (ScrimState state : ScrimState.values()) {
            if (state == ScrimState.UNINITIALIZED) {
                continue;
            }
            mScrimController.transitionTo(state);
            finishAnimationsImmediately();
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

    @Test
    public void testViewsDontHaveFocusHighlight() {
        Assert.assertFalse("Scrim shouldn't have focus highlight",
                mScrimInFront.getDefaultFocusHighlightEnabled());
        Assert.assertFalse("Scrim shouldn't have focus highlight",
                mScrimBehind.getDefaultFocusHighlightEnabled());
        Assert.assertFalse("Scrim shouldn't have focus highlight",
                mScrimForBubble.getDefaultFocusHighlightEnabled());
    }

    @Test
    public void testIsLowPowerMode() {
        HashSet<ScrimState> lowPowerModeStates = new HashSet<>(Arrays.asList(
                ScrimState.OFF, ScrimState.AOD, ScrimState.PULSING));
        HashSet<ScrimState> regularStates = new HashSet<>(Arrays.asList(
                ScrimState.UNINITIALIZED, ScrimState.KEYGUARD, ScrimState.BOUNCER,
                ScrimState.BOUNCER_SCRIMMED, ScrimState.BRIGHTNESS_MIRROR, ScrimState.UNLOCKED,
                ScrimState.BUBBLE_EXPANDED));

        for (ScrimState state : ScrimState.values()) {
            if (!lowPowerModeStates.contains(state) && !regularStates.contains(state)) {
                Assert.fail("Scrim state not whitelisted nor blacklisted as low power mode");
            }
        }
    }

    private void assertScrimTint(boolean front, boolean behind, boolean bubble) {
        Assert.assertEquals("Tint test failed at state " + mScrimController.getState()
                        + " with scrim: " + getScrimName(mScrimInFront) + " and tint: "
                        + Integer.toHexString(mScrimInFront.getTint()),
                front, mScrimInFront.getTint() != Color.TRANSPARENT);

        Assert.assertEquals("Tint test failed at state " + mScrimController.getState()
                        + " with scrim: " + getScrimName(mScrimBehind) + " and tint: "
                        + Integer.toHexString(mScrimBehind.getTint()),
                behind, mScrimBehind.getTint() != Color.TRANSPARENT);

        Assert.assertEquals("Tint test failed at state " + mScrimController.getState()
                        + " with scrim: " + getScrimName(mScrimForBubble) + " and tint: "
                        + Integer.toHexString(mScrimForBubble.getTint()),
                bubble, mScrimForBubble.getTint() != Color.TRANSPARENT);
    }

    private String getScrimName(ScrimView scrim) {
        if (scrim == mScrimInFront) {
            return "front";
        } else if (scrim == mScrimBehind) {
            return "back";
        } else if (scrim == mScrimForBubble) {
            return "bubble";
        }
        return "unknown_scrim";
    }

    private void assertScrimAlpha(int front, int behind, int bubble) {
        // Check single scrim visibility.
        Assert.assertEquals("Unexpected front scrim alpha: "
                        + mScrimInFront.getViewAlpha(),
                front != TRANSPARENT /* expected */,
                mScrimInFront.getViewAlpha() > TRANSPARENT /* actual */);

        Assert.assertEquals("Unexpected back scrim alpha: "
                        + mScrimBehind.getViewAlpha(),
                behind != TRANSPARENT /* expected */,
                mScrimBehind.getViewAlpha() > TRANSPARENT /* actual */);

        Assert.assertEquals(
                "Unexpected bubble scrim alpha: "
                        + mScrimForBubble.getViewAlpha(), /* message */
                bubble != TRANSPARENT /* expected */,
                mScrimForBubble.getViewAlpha() > TRANSPARENT /* actual */);

        // Check combined scrim visibility.
        final int visibility;
        if (front == OPAQUE || behind == OPAQUE || bubble == OPAQUE) {
            visibility = OPAQUE;
        } else if (front > TRANSPARENT || behind > TRANSPARENT || bubble > TRANSPARENT) {
            visibility = SEMI_TRANSPARENT;
        } else {
            visibility = TRANSPARENT;
        }
        Assert.assertEquals("Invalid visibility.",
                visibility /* expected */,
                mScrimVisibility);
    }
}
