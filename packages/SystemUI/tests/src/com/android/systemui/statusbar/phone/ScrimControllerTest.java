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
import static com.android.systemui.statusbar.phone.ScrimState.BOUNCER;
import static com.android.systemui.statusbar.phone.ScrimState.SHADE_LOCKED;

import static com.google.common.truth.Truth.assertThat;

import static kotlinx.coroutines.flow.FlowKt.emptyFlow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.testing.ViewUtils;
import android.util.MathUtils;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.DejankUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.ShadeInterpolation;
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants;
import com.android.systemui.dock.DockManager;
import com.android.systemui.flags.EnableSceneContainer;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.KeyguardState;
import com.android.systemui.keyguard.shared.model.TransitionState;
import com.android.systemui.keyguard.shared.model.TransitionStep;
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToGoneTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.scrim.ScrimView;
import com.android.systemui.shade.shared.flag.DualShade;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.shade.transition.LinearLargeScreenShadeInterpolator;
import com.android.systemui.statusbar.policy.FakeConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.wakelock.DelayedWakeLock;
import com.android.systemui.utils.os.FakeHandler;
import com.android.systemui.wallpapers.data.repository.FakeWallpaperRepository;

import com.google.common.truth.Expect;

import kotlinx.coroutines.test.TestScope;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class ScrimControllerTest extends SysuiTestCase {

    @Rule public Expect mExpect = Expect.create();
    private final KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);

    private final FakeConfigurationController mConfigurationController =
            new FakeConfigurationController();
    private final LargeScreenShadeInterpolator
            mLinearLargeScreenShadeInterpolator = new LinearLargeScreenShadeInterpolator();

    private final TestScope mTestScope = mKosmos.getTestScope();
    private final JavaAdapter mJavaAdapter = new JavaAdapter(mTestScope.getBackgroundScope());

    private ScrimController mScrimController;
    private ScrimView mScrimBehind;
    private ScrimView mNotificationsScrim;
    private ScrimView mScrimInFront;
    private ScrimState mScrimState;
    private float mScrimBehindAlpha;
    private GradientColors mScrimInFrontColor;
    private int mScrimVisibility;
    private boolean mAlwaysOnEnabled;
    private TestableLooper mLooper;
    private Context mContext;
    @Mock private AlarmManager mAlarmManager;
    @Mock private DozeParameters mDozeParameters;
    @Mock private LightBarController mLightBarController;
    @Mock private DelayedWakeLock.Factory mDelayedWakeLockFactory;
    @Mock private DelayedWakeLock mWakeLock;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private DockManager mDockManager;
    @Mock private ScreenOffAnimationController mScreenOffAnimationController;
    @Mock private KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    @Mock private PrimaryBouncerToGoneTransitionViewModel mPrimaryBouncerToGoneTransitionViewModel;
    @Mock private AlternateBouncerToGoneTransitionViewModel
            mAlternateBouncerToGoneTransitionViewModel;
    private final KeyguardTransitionInteractor mKeyguardTransitionInteractor =
            mKosmos.getKeyguardTransitionInteractor();
    private final FakeKeyguardTransitionRepository mKeyguardTransitionRepository =
            mKosmos.getKeyguardTransitionRepository();
    @Mock private KeyguardInteractor mKeyguardInteractor;
    private final FakeWallpaperRepository mWallpaperRepository = new FakeWallpaperRepository();
    @Mock private TypedArray mMockTypedArray;

    // TODO(b/204991468): Use a real PanelExpansionStateManager object once this bug is fixed. (The
    //   event-dispatch-on-registration pattern caused some of these unit tests to fail.)
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

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
    }

    private AnimatorListener mAnimatorListener = new AnimatorListener();

    private int mSurfaceColor = 0x112233;

    private void finishAnimationsImmediately() {
        // Execute code that will trigger animations.
        mScrimController.onPreDraw();
        // Force finish all animations.
        mLooper.processAllMessages();
        endAnimation(mNotificationsScrim);
        endAnimation(mScrimBehind);
        endAnimation(mScrimInFront);

        assertEquals("Animators did not finish",
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
        mContext = spy(getContext());
        when(mContext.obtainStyledAttributes(
                new int[]{com.android.internal.R.attr.materialColorSurface}))
                .thenReturn(mMockTypedArray);

        when(mMockTypedArray.getColorStateList(anyInt()))
                .thenAnswer((invocation) -> ColorStateList.valueOf(mSurfaceColor));

        mScrimBehind = spy(new ScrimView(mContext));
        mScrimInFront = new ScrimView(mContext);
        mNotificationsScrim = new ScrimView(mContext);
        mAlwaysOnEnabled = true;
        mLooper = TestableLooper.get(this);
        DejankUtils.setImmediate(true);

        // ScrimController uses mScrimBehind to delay some callbacks that we should run immediately.
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mScrimBehind).postOnAnimationDelayed(any(Runnable.class), anyLong());

        when(mDozeParameters.getAlwaysOn()).thenAnswer(invocation -> mAlwaysOnEnabled);
        when(mDozeParameters.getDisplayNeedsBlanking()).thenReturn(true);

        doAnswer((Answer<Void>) invocation -> {
            mScrimState = invocation.getArgument(0);
            mScrimBehindAlpha = invocation.getArgument(1);
            mScrimInFrontColor = invocation.getArgument(2);
            return null;
        }).when(mLightBarController).setScrimState(
                any(ScrimState.class), anyFloat(), any(GradientColors.class));

        when(mDelayedWakeLockFactory.create(any(String.class))).thenReturn(mWakeLock);
        when(mDockManager.isDocked()).thenReturn(false);

        when(mPrimaryBouncerToGoneTransitionViewModel.getScrimAlpha())
                .thenReturn(emptyFlow());
        when(mAlternateBouncerToGoneTransitionViewModel.getScrimAlpha())
                .thenReturn(emptyFlow());

        mScrimController = new ScrimController(
                mLightBarController,
                mDozeParameters,
                mAlarmManager,
                mKeyguardStateController,
                mDelayedWakeLockFactory,
                new FakeHandler(mLooper.getLooper()),
                mKeyguardUpdateMonitor,
                mDockManager,
                mConfigurationController,
                new FakeExecutor(new FakeSystemClock()),
                mJavaAdapter,
                mScreenOffAnimationController,
                mKeyguardUnlockAnimationController,
                mStatusBarKeyguardViewManager,
                mPrimaryBouncerToGoneTransitionViewModel,
                mAlternateBouncerToGoneTransitionViewModel,
                mKeyguardTransitionInteractor,
                mKeyguardInteractor,
                mWallpaperRepository,
                mKosmos.getTestDispatcher(),
                mLinearLargeScreenShadeInterpolator);
        mScrimController.start();
        mScrimController.setScrimVisibleListener(visible -> mScrimVisibility = visible);
        mScrimController.attachViews(mScrimBehind, mNotificationsScrim, mScrimInFront);
        mScrimController.setAnimatorListener(mAnimatorListener);

        // Attach behind scrim so flows that are collecting on it start running.
        ViewUtils.attachView(mScrimBehind);

        mScrimController.setHasBackdrop(false);

        mWallpaperRepository.getWallpaperSupportsAmbientMode().setValue(false);
        mTestScope.getTestScheduler().runCurrent();

        if (SceneContainerFlag.isEnabled()) {
            mScrimController.transitionTo(ScrimState.KEYGUARD);
        } else {
            mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        }
        finishAnimationsImmediately();
    }

    @After
    public void tearDown() {
        // Detaching view stops flow collection and prevents memory leak.
        ViewUtils.detachView(mScrimBehind);
        finishAnimationsImmediately();
        Arrays.stream(ScrimState.values()).forEach((scrim) -> {
            scrim.setAodFrontScrimAlpha(0f);
            scrim.setClipQsScrim(false);
        });
        DejankUtils.setImmediate(false);
    }

    @Test
    public void transitionToKeyguard() {
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, SEMI_TRANSPARENT));

        assertScrimTinted(Map.of(
                mScrimInFront, true,
                mScrimBehind, true
        ));
    }

    @Test
    public void transitionToShadeLocked() {
        mScrimController.legacyTransitionTo(SHADE_LOCKED);
        mScrimController.setQsPosition(1f, 0);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mNotificationsScrim, OPAQUE,
                mScrimInFront, TRANSPARENT,
                mScrimBehind, OPAQUE));

        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mScrimBehind, true
        ));
    }

    @Test
    @EnableSceneContainer
    @DisableFlags(DualShade.FLAG_NAME)
    public void transitionToShadeLocked_sceneContainer_dualShadeOff() {
        mScrimController.transitionTo(SHADE_LOCKED);
        mScrimController.setQsPosition(1f, 0);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mNotificationsScrim, OPAQUE,
                mScrimInFront, TRANSPARENT,
                mScrimBehind, OPAQUE
        ));

        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mScrimBehind, true
        ));
    }

    @Test
    @EnableSceneContainer
    @EnableFlags(DualShade.FLAG_NAME)
    public void transitionToShadeLocked_sceneContainer_dualShadeOn() {
        mScrimController.transitionTo(SHADE_LOCKED);
        mScrimController.setQsPosition(1f, 0);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mNotificationsScrim, TRANSPARENT,
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT
        ));

        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mNotificationsScrim, false,
                mScrimBehind, false
        ));
    }

    @Test
    public void transitionToShadeLocked_clippingQs() {
        mScrimController.setClipsQsScrim(true);
        mScrimController.legacyTransitionTo(SHADE_LOCKED);
        mScrimController.setQsPosition(1f, 0);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mNotificationsScrim, OPAQUE,
                mScrimInFront, TRANSPARENT,
                mScrimBehind, OPAQUE));

        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mScrimBehind, true
        ));
    }

    @Test
    public void transitionToOff() {
        mScrimController.legacyTransitionTo(ScrimState.OFF);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, OPAQUE,
                mScrimBehind, OPAQUE));

        assertScrimTinted(Map.of(
                mScrimInFront, true,
                mScrimBehind, true
        ));

        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);
    }

    @Test
    public void transitionToAod_withRegularWallpaper() {
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT));
        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        assertScrimTinted(Map.of(
                mScrimInFront, true,
                mScrimBehind, true
        ));
    }

    @Test
    public void transitionToAod_withAodWallpaper() {
        mWallpaperRepository.getWallpaperSupportsAmbientMode().setValue(true);
        mTestScope.getTestScheduler().runCurrent();

        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT));
        assertEquals(0f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        // Pulsing notification should conserve AOD wallpaper.
        mScrimController.legacyTransitionTo(ScrimState.PULSING);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT));
        assertEquals(0f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);
    }

    @Test
    public void transitionToAod_withAodWallpaperAndLockScreenWallpaper() {
        mScrimController.setHasBackdrop(true);
        mWallpaperRepository.getWallpaperSupportsAmbientMode().setValue(true);
        mTestScope.getTestScheduler().runCurrent();

        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT));
        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        assertScrimTinted(Map.of(
                mScrimInFront, true,
                mScrimBehind, true
        ));
    }

    @Test
    public void setHasBackdrop_withAodWallpaperAndAlbumArt() {
        mWallpaperRepository.getWallpaperSupportsAmbientMode().setValue(true);
        mTestScope.getTestScheduler().runCurrent();

        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        mScrimController.setHasBackdrop(true);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT));
        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        assertScrimTinted(Map.of(
                mScrimInFront, true,
                mScrimBehind, true
        ));
    }

    @Test
    public void transitionToAod_withFrontAlphaUpdates() {
        // Assert that setting the AOD front scrim alpha doesn't take effect in a non-AOD state.
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        mScrimController.setAodFrontScrimAlpha(0.5f);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, SEMI_TRANSPARENT));

        // ... but that it does take effect once we enter the AOD state.
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, SEMI_TRANSPARENT,
                mScrimBehind, TRANSPARENT));
        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        // ... and that if we set it while we're in AOD, it does take immediate effect.
        mScrimController.setAodFrontScrimAlpha(1f);
        assertScrimAlpha(Map.of(
                mScrimInFront, OPAQUE,
                mScrimBehind, TRANSPARENT));
        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        // ... and make sure we recall the previous front scrim alpha even if we transition away
        // for a bit.
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, OPAQUE,
                mScrimBehind, TRANSPARENT));
        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        // ... and alpha updates should be completely ignored if always_on is off.
        // Passing it forward would mess up the wake-up transition.
        mAlwaysOnEnabled = false;
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        mScrimController.setAodFrontScrimAlpha(0.3f);
        assertEquals(ScrimState.AOD.getFrontAlpha(), mScrimInFront.getViewAlpha(), 0.001f);
        Assert.assertNotEquals(0.3f, mScrimInFront.getViewAlpha(), 0.001f);
    }

    @Test
    public void transitionToAod_afterDocked_ignoresAlwaysOnAndUpdatesFrontAlpha() {
        // Assert that setting the AOD front scrim alpha doesn't take effect in a non-AOD state.
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        mScrimController.setAodFrontScrimAlpha(0.5f);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, SEMI_TRANSPARENT));

        // ... and doesn't take effect when disabled always_on
        mAlwaysOnEnabled = false;
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, OPAQUE,
                mScrimBehind, TRANSPARENT));
        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        // ... but will take effect after docked
        when(mDockManager.isDocked()).thenReturn(true);
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        mScrimController.setAodFrontScrimAlpha(0.5f);
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, SEMI_TRANSPARENT,
                mScrimBehind, TRANSPARENT));
        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        // ... and that if we set it while we're in AOD, it does take immediate effect after docked.
        mScrimController.setAodFrontScrimAlpha(1f);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, OPAQUE,
                mScrimBehind, TRANSPARENT));
        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        // Reset value since enums are static.
        mScrimController.setAodFrontScrimAlpha(0f);
    }

    @Test
    public void transitionToPulsing_withFrontAlphaUpdates() {
        // Pre-condition
        // Need to go to AoD first because PULSING doesn't change
        // the back scrim opacity - otherwise it would hide AoD wallpapers.
        mWallpaperRepository.getWallpaperSupportsAmbientMode().setValue(false);
        mTestScope.getTestScheduler().runCurrent();

        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT));
        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        mScrimController.legacyTransitionTo(ScrimState.PULSING);
        finishAnimationsImmediately();
        // Front scrim should be transparent, but tinted
        // Back scrim should be semi-transparent so the user can see the wallpaper
        // Pulse callback should have been invoked
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT));
        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        assertScrimTinted(Map.of(
                mScrimInFront, true,
                mScrimBehind, true
        ));

        // ... and when ambient goes dark, front scrim should be semi-transparent
        mScrimController.setAodFrontScrimAlpha(0.5f);
        finishAnimationsImmediately();
        // Front scrim should be semi-transparent
        assertScrimAlpha(Map.of(
                mScrimInFront, SEMI_TRANSPARENT,
                mScrimBehind, TRANSPARENT));
        assertEquals(1f, mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        mScrimController.setWakeLockScreenSensorActive(true);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, SEMI_TRANSPARENT,
                mScrimBehind, TRANSPARENT));
        assertEquals(ScrimController.WAKE_SENSOR_SCRIM_ALPHA,
                mScrimController.getState().getMaxLightRevealScrimAlpha(), 0f);

        // Reset value since enums are static.
        mScrimController.setAodFrontScrimAlpha(0f);
    }

    @Test
    public void transitionToKeyguardBouncer() {
        mScrimController.legacyTransitionTo(BOUNCER);
        finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible and tinted to the surface color
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, OPAQUE));

        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mScrimBehind, true,
                mNotificationsScrim, false
        ));

        assertScrimTint(mScrimBehind, mSurfaceColor);
    }

    @Test
    public void lockscreenToHubTransition_setsBehindScrimAlpha() {
        // Start on lockscreen.
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        finishAnimationsImmediately();

        // Behind scrim starts at default alpha.
        final float transitionProgress = 0f;
        float expectedAlpha = ScrimState.KEYGUARD.getBehindAlpha();
        mKeyguardTransitionRepository.sendTransitionStepJava(mKosmos.getTestScope(),
                new TransitionStep(
                        KeyguardState.LOCKSCREEN,
                        KeyguardState.GLANCEABLE_HUB,
                        transitionProgress,
                        TransitionState.STARTED
                ), true);
        mTestScope.getTestScheduler().runCurrent();
        assertThat(mScrimBehind.getViewAlpha()).isEqualTo(expectedAlpha);

        // Scrim fades out as transition runs.
        final float runningProgress = 0.2f;
        expectedAlpha = (1 - runningProgress) * ScrimState.KEYGUARD.getBehindAlpha();
        mKeyguardTransitionRepository.sendTransitionStepJava(mKosmos.getTestScope(),
                new TransitionStep(
                        KeyguardState.LOCKSCREEN,
                        KeyguardState.GLANCEABLE_HUB,
                        runningProgress,
                        TransitionState.RUNNING
                ), true);
        mTestScope.getTestScheduler().runCurrent();
        assertThat(mScrimBehind.getViewAlpha()).isEqualTo(expectedAlpha);

        // Scrim invisible at end of transition.
        final float finishedProgress = 1f;
        expectedAlpha = 0f;
        mKeyguardTransitionRepository.sendTransitionStepJava(mKosmos.getTestScope(),
                new TransitionStep(
                        KeyguardState.LOCKSCREEN,
                        KeyguardState.GLANCEABLE_HUB,
                        finishedProgress,
                        TransitionState.FINISHED
                ), true);
        mTestScope.getTestScheduler().runCurrent();
        assertThat(mScrimBehind.getViewAlpha()).isEqualTo(expectedAlpha);
    }

    @Test
    public void hubToLockscreenTransition_setsViewAlpha() {
        // Start on glanceable hub.
        mScrimController.legacyTransitionTo(ScrimState.GLANCEABLE_HUB);
        finishAnimationsImmediately();

        // Behind scrim starts at 0 alpha.
        final float transitionProgress = 0f;
        float expectedAlpha = 0f;
        mKeyguardTransitionRepository.sendTransitionStepJava(mKosmos.getTestScope(),
                new TransitionStep(
                        KeyguardState.GLANCEABLE_HUB,
                        KeyguardState.LOCKSCREEN,
                        transitionProgress,
                        TransitionState.STARTED
                ), true);
        mTestScope.getTestScheduler().runCurrent();
        assertThat(mScrimBehind.getViewAlpha()).isEqualTo(expectedAlpha);

        // Scrim fades in as transition runs.
        final float runningProgress = 0.2f;
        expectedAlpha = runningProgress * ScrimState.KEYGUARD.getBehindAlpha();
        mKeyguardTransitionRepository.sendTransitionStepJava(mKosmos.getTestScope(),
                new TransitionStep(
                        KeyguardState.GLANCEABLE_HUB,
                        KeyguardState.LOCKSCREEN,
                        runningProgress,
                        TransitionState.RUNNING
                ), true);
        mTestScope.getTestScheduler().runCurrent();
        assertThat(mScrimBehind.getViewAlpha()).isEqualTo(expectedAlpha);

        // Scrim at default visibility at end of transition.
        final float finishedProgress = 1f;
        expectedAlpha = finishedProgress * ScrimState.KEYGUARD.getBehindAlpha();
        mKeyguardTransitionRepository.sendTransitionStepJava(mKosmos.getTestScope(),
                new TransitionStep(
                        KeyguardState.GLANCEABLE_HUB,
                        KeyguardState.LOCKSCREEN,
                        finishedProgress,
                        TransitionState.FINISHED
                ), true);
        mTestScope.getTestScheduler().runCurrent();
        assertThat(mScrimBehind.getViewAlpha()).isEqualTo(expectedAlpha);
    }

    @Test
    public void transitionToHub() {
        mScrimController.setRawPanelExpansionFraction(0f);
        mScrimController.setBouncerHiddenFraction(KeyguardBouncerConstants.EXPANSION_HIDDEN);
        mScrimController.legacyTransitionTo(ScrimState.GLANCEABLE_HUB);
        finishAnimationsImmediately();

        // All scrims transparent on the hub.
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, TRANSPARENT));
    }

    @Test
    public void openBouncerOnHub() {
        mScrimController.legacyTransitionTo(ScrimState.GLANCEABLE_HUB);

        // Open the bouncer.
        mScrimController.setRawPanelExpansionFraction(0f);
        when(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).thenReturn(true);
        mScrimController.setBouncerHiddenFraction(KeyguardBouncerConstants.EXPANSION_VISIBLE);
        finishAnimationsImmediately();

        // Only behind scrim is visible.
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, OPAQUE));
        assertScrimTint(mScrimBehind, mSurfaceColor);

        // Bouncer is closed.
        mScrimController.setBouncerHiddenFraction(KeyguardBouncerConstants.EXPANSION_HIDDEN);
        mScrimController.legacyTransitionTo(ScrimState.GLANCEABLE_HUB);
        finishAnimationsImmediately();

        // All scrims are transparent.
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, TRANSPARENT));
    }

    @Test
    public void openShadeOnHub() {
        mScrimController.legacyTransitionTo(ScrimState.GLANCEABLE_HUB);

        // Open the shade.
        mScrimController.setQsPosition(1f, 0);
        mScrimController.setRawPanelExpansionFraction(1);
        mScrimController.setTransitionToFullShadeProgress(1, 0);
        finishAnimationsImmediately();

        // Shade scrims are visible.
        assertScrimAlpha(Map.of(
                mNotificationsScrim, OPAQUE,
                mScrimInFront, TRANSPARENT,
                mScrimBehind, OPAQUE));
        assertScrimTint(mScrimBehind, Color.BLACK);
        assertScrimTint(mNotificationsScrim, Color.TRANSPARENT);

        mScrimController.setTransitionToFullShadeProgress(0, 0);
        finishAnimationsImmediately();

        // All scrims are transparent.
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, TRANSPARENT));
    }

    @Test
    public void transitionToHubOverDream() {
        mScrimController.setRawPanelExpansionFraction(0f);
        mScrimController.setBouncerHiddenFraction(KeyguardBouncerConstants.EXPANSION_HIDDEN);
        mScrimController.legacyTransitionTo(ScrimState.GLANCEABLE_HUB_OVER_DREAM);
        finishAnimationsImmediately();

        // All scrims transparent on the hub.
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, TRANSPARENT));
    }

    @Test
    public void openBouncerOnHubOverDream() {
        mScrimController.legacyTransitionTo(ScrimState.GLANCEABLE_HUB_OVER_DREAM);

        // Open the bouncer.
        mScrimController.setRawPanelExpansionFraction(0f);
        when(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).thenReturn(true);
        mScrimController.setBouncerHiddenFraction(KeyguardBouncerConstants.EXPANSION_VISIBLE);
        finishAnimationsImmediately();

        // Only behind scrim is visible.
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, OPAQUE));
        assertScrimTint(mScrimBehind, mSurfaceColor);

        // Bouncer is closed.
        mScrimController.setBouncerHiddenFraction(KeyguardBouncerConstants.EXPANSION_HIDDEN);
        mScrimController.legacyTransitionTo(ScrimState.GLANCEABLE_HUB_OVER_DREAM);
        finishAnimationsImmediately();

        // All scrims are transparent.
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, TRANSPARENT));
    }

    @Test
    public void openShadeOnHubOverDream() {
        mScrimController.legacyTransitionTo(ScrimState.GLANCEABLE_HUB_OVER_DREAM);

        // Open the shade.
        mScrimController.setQsPosition(1f, 0);
        mScrimController.setRawPanelExpansionFraction(1f);
        finishAnimationsImmediately();

        // Shade scrims are visible.
        assertScrimAlpha(Map.of(
                mNotificationsScrim, OPAQUE,
                mScrimInFront, TRANSPARENT,
                mScrimBehind, OPAQUE));
        assertScrimTint(mScrimBehind, Color.BLACK);
        assertScrimTint(mNotificationsScrim, Color.TRANSPARENT);

        mScrimController.setQsPosition(0f, 0);
        mScrimController.setRawPanelExpansionFraction(0f);
        finishAnimationsImmediately();

        // All scrims are transparent.
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, TRANSPARENT));
    }

    @Test
    public void onThemeChange_bouncerBehindTint_isUpdatedToSurfaceColor() {
        assertEquals(BOUNCER.getBehindTint(), 0x112233);
        mSurfaceColor = 0x223344;
        mConfigurationController.notifyThemeChanged();
        assertEquals(BOUNCER.getBehindTint(), 0x223344);
    }

    @Test
    public void onThemeChangeWhileClipQsScrim_bouncerBehindTint_remainsBlack() {
        mScrimController.setClipsQsScrim(true);
        mScrimController.legacyTransitionTo(BOUNCER);
        finishAnimationsImmediately();

        assertEquals(BOUNCER.getBehindTint(), Color.BLACK);
        mSurfaceColor = 0x223344;
        mConfigurationController.notifyThemeChanged();
        assertEquals(BOUNCER.getBehindTint(), Color.BLACK);
    }

    @Test
    public void transitionToKeyguardBouncer_clippingQs() {
        mScrimController.setClipsQsScrim(true);
        mScrimController.legacyTransitionTo(BOUNCER);
        finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be clipping QS
        // Notif scrim should be visible without tint
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, OPAQUE,
                mScrimBehind, OPAQUE));

        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mScrimBehind, true,
                mNotificationsScrim, false
        ));
    }

    @Test
    public void disableClipQsScrimWithoutStateTransition_updatesTintAndAlpha() {
        mScrimController.setClipsQsScrim(true);
        mScrimController.legacyTransitionTo(BOUNCER);

        mScrimController.setClipsQsScrim(false);

        finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible and has a tint of surfaceColor
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, OPAQUE));
        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mScrimBehind, true,
                mNotificationsScrim, false
        ));
        assertScrimTint(mScrimBehind, mSurfaceColor);
    }

    @Test
    public void enableClipQsScrimWithoutStateTransition_updatesTintAndAlpha() {
        mScrimController.setClipsQsScrim(false);
        mScrimController.legacyTransitionTo(BOUNCER);

        mScrimController.setClipsQsScrim(true);

        finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be clipping QS
        // Notif scrim should be visible without tint
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, OPAQUE,
                mScrimBehind, OPAQUE));
        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mScrimBehind, true,
                mNotificationsScrim, false
        ));
    }

    @Test
    public void transitionToBouncer() {
        mScrimController.legacyTransitionTo(ScrimState.BOUNCER_SCRIMMED);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, OPAQUE,
                mScrimBehind, TRANSPARENT));
        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mScrimBehind, false
        ));
    }

    @Test
    public void transitionToUnlocked_clippedQs() {
        mScrimController.setClipsQsScrim(true);
        mScrimController.setRawPanelExpansionFraction(0f);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();

        assertScrimTinted(Map.of(
                mNotificationsScrim, false,
                mScrimInFront, false,
                mScrimBehind, true
        ));
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, OPAQUE));

        mScrimController.setRawPanelExpansionFraction(0.25f);
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, SEMI_TRANSPARENT,
                mScrimBehind, OPAQUE));

        mScrimController.setRawPanelExpansionFraction(0.5f);
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, OPAQUE,
                mScrimBehind, OPAQUE));
    }

    @Test
    @EnableSceneContainer
    @DisableFlags(DualShade.FLAG_NAME)
    public void transitionToUnlocked_sceneContainer_dualShadeOff() {
        mScrimController.setRawPanelExpansionFraction(0f);
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, TRANSPARENT
        ));

        mScrimController.setRawPanelExpansionFraction(0.5f);
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, SEMI_TRANSPARENT,
                mScrimBehind, SEMI_TRANSPARENT
        ));

        mScrimController.setRawPanelExpansionFraction(1f);
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, OPAQUE,
                mScrimBehind, OPAQUE
        ));
    }

    @Test
    @EnableSceneContainer
    @EnableFlags(DualShade.FLAG_NAME)
    public void transitionToUnlocked_sceneContainer_dualShadeOn() {
        mScrimController.setRawPanelExpansionFraction(0f);
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, TRANSPARENT
        ));

        mScrimController.setRawPanelExpansionFraction(0.5f);
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, TRANSPARENT
        ));

        mScrimController.setRawPanelExpansionFraction(1f);
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, TRANSPARENT
        ));
    }

    @Test
    public void transitionToUnlocked_nonClippedQs_followsLargeScreensInterpolator() {
        mScrimController.setClipsQsScrim(false);
        mScrimController.setRawPanelExpansionFraction(0f);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();

        assertScrimTinted(Map.of(
                mNotificationsScrim, false,
                mScrimInFront, false,
                mScrimBehind, true
        ));
        // The large screens interpolator used in this test is a linear one, just for tests.
        // Assertions below are based on this assumption, and that the code uses that interpolator
        // when on a large screen (QS not clipped).
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, TRANSPARENT));

        mScrimController.setRawPanelExpansionFraction(0.5f);
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, SEMI_TRANSPARENT,
                mScrimBehind, SEMI_TRANSPARENT));

        mScrimController.setRawPanelExpansionFraction(0.99f);
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, SEMI_TRANSPARENT,
                mScrimBehind, SEMI_TRANSPARENT));

        mScrimController.setRawPanelExpansionFraction(1f);
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, OPAQUE,
                mScrimBehind, OPAQUE));
    }

    @Test
    public void scrimStateCallback() {
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();
        assertEquals(mScrimState, ScrimState.UNLOCKED);

        mScrimController.legacyTransitionTo(BOUNCER);
        finishAnimationsImmediately();
        assertEquals(mScrimState, BOUNCER);

        mScrimController.legacyTransitionTo(ScrimState.BOUNCER_SCRIMMED);
        finishAnimationsImmediately();
        assertEquals(mScrimState, ScrimState.BOUNCER_SCRIMMED);
    }

    @Test
    public void panelExpansion() {
        mScrimController.setRawPanelExpansionFraction(0f);
        mScrimController.setRawPanelExpansionFraction(0.5f);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();

        reset(mScrimBehind);
        mScrimController.setRawPanelExpansionFraction(0f);
        mScrimController.setRawPanelExpansionFraction(1.0f);
        finishAnimationsImmediately();

        assertEquals("Scrim alpha should change after setPanelExpansion",
                mScrimBehindAlpha, mScrimBehind.getViewAlpha(), 0.01f);

        mScrimController.setRawPanelExpansionFraction(0f);
        finishAnimationsImmediately();

        assertEquals("Scrim alpha should change after setPanelExpansion",
                mScrimBehindAlpha, mScrimBehind.getViewAlpha(), 0.01f);
    }

    @Test
    public void qsExpansion() {
        reset(mScrimBehind);
        mScrimController.setQsPosition(1f, 999 /* value doesn't matter */);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, OPAQUE,
                mNotificationsScrim, OPAQUE));
    }

    @Test
    public void qsExpansion_clippingQs() {
        reset(mScrimBehind);
        mScrimController.setClipsQsScrim(true);
        mScrimController.setQsPosition(1f, 999 /* value doesn't matter */);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, OPAQUE,
                mNotificationsScrim, OPAQUE));
    }

    @Test
    public void qsExpansion_half_clippingQs() {
        reset(mScrimBehind);
        mScrimController.setClipsQsScrim(true);
        mScrimController.setQsPosition(0.25f, 999 /* value doesn't matter */);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, OPAQUE,
                mNotificationsScrim, SEMI_TRANSPARENT));
    }

    @Test
    public void panelExpansionAffectsAlpha() {
        mScrimController.setRawPanelExpansionFraction(0f);
        mScrimController.setRawPanelExpansionFraction(0.5f);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();

        final float scrimAlpha = mScrimBehind.getViewAlpha();
        reset(mScrimBehind);
        mScrimController.setExpansionAffectsAlpha(false);
        mScrimController.setRawPanelExpansionFraction(0.8f);
        verifyZeroInteractions(mScrimBehind);
        assertEquals("Scrim opacity shouldn't change when setExpansionAffectsAlpha "
                + "is false", scrimAlpha, mScrimBehind.getViewAlpha(), 0.01f);

        mScrimController.setExpansionAffectsAlpha(true);
        mScrimController.setRawPanelExpansionFraction(0.1f);
        finishAnimationsImmediately();
        Assert.assertNotEquals("Scrim opacity should change when setExpansionAffectsAlpha "
                + "is true", scrimAlpha, mScrimBehind.getViewAlpha(), 0.01f);
    }

    @Test
    public void transitionToUnlockedFromOff() {
        // Simulate unlock with fingerprint without AOD
        mScrimController.legacyTransitionTo(ScrimState.OFF);
        mScrimController.setRawPanelExpansionFraction(0f);
        finishAnimationsImmediately();
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);

        finishAnimationsImmediately();

        // All scrims should be transparent at the end of fade transition.
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT));

        // Make sure at the very end of the animation, we're reset to transparent
        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mScrimBehind, true
        ));
    }

    @Test
    public void transitionToUnlockedFromAod() {
        // Simulate unlock with fingerprint
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        mScrimController.setRawPanelExpansionFraction(0f);
        finishAnimationsImmediately();
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);

        finishAnimationsImmediately();

        // All scrims should be transparent at the end of fade transition.
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT));

        // Make sure at the very end of the animation, we're reset to transparent
        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mScrimBehind, true
        ));
    }

    @Test
    public void scrimBlanksBeforeLeavingAod() {
        // Simulate unlock with fingerprint
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED,
                new ScrimController.Callback() {
                    @Override
                    public void onDisplayBlanked() {
                        // Front scrim should be black in the middle of the transition
                        Assert.assertTrue("Scrim should be visible during transition. Alpha: "
                                + mScrimInFront.getViewAlpha(), mScrimInFront.getViewAlpha() > 0);
                        assertScrimTinted(Map.of(
                                mScrimInFront, true,
                                mScrimBehind, true
                        ));
                        Assert.assertSame("Scrim should be visible during transition.",
                                mScrimVisibility, OPAQUE);
                    }
                });
        finishAnimationsImmediately();
    }

    @Test
    public void scrimBlankCallbackWhenUnlockingFromPulse() {
        boolean[] blanked = {false};
        // Simulate unlock with fingerprint
        mScrimController.legacyTransitionTo(ScrimState.PULSING);
        finishAnimationsImmediately();
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED,
                new ScrimController.Callback() {
                    @Override
                    public void onDisplayBlanked() {
                        blanked[0] = true;
                    }
                });
        finishAnimationsImmediately();
        Assert.assertTrue("Scrim should send display blanked callback when unlocking "
                + "from pulse.", blanked[0]);
    }

    @Test
    public void blankingNotRequired_leavingAoD() {
        // GIVEN display does NOT need blanking
        when(mDozeParameters.getDisplayNeedsBlanking()).thenReturn(false);

        mScrimController = new ScrimController(
                mLightBarController,
                mDozeParameters,
                mAlarmManager,
                mKeyguardStateController,
                mDelayedWakeLockFactory,
                new FakeHandler(mLooper.getLooper()),
                mKeyguardUpdateMonitor,
                mDockManager,
                mConfigurationController,
                new FakeExecutor(new FakeSystemClock()),
                mJavaAdapter,
                mScreenOffAnimationController,
                mKeyguardUnlockAnimationController,
                mStatusBarKeyguardViewManager,
                mPrimaryBouncerToGoneTransitionViewModel,
                mAlternateBouncerToGoneTransitionViewModel,
                mKeyguardTransitionInteractor,
                mKeyguardInteractor,
                mWallpaperRepository,
                mKosmos.getTestDispatcher(),
                mLinearLargeScreenShadeInterpolator);
        mScrimController.start();
        mScrimController.setScrimVisibleListener(visible -> mScrimVisibility = visible);
        mScrimController.attachViews(mScrimBehind, mNotificationsScrim, mScrimInFront);
        mScrimController.setAnimatorListener(mAnimatorListener);
        mScrimController.setHasBackdrop(false);
        mWallpaperRepository.getWallpaperSupportsAmbientMode().setValue(false);
        mTestScope.getTestScheduler().runCurrent();
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        finishAnimationsImmediately();

        // WHEN Simulate unlock with fingerprint
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();

        // WHEN transitioning to UNLOCKED, onDisplayCallbackBlanked callback called to continue
        // the transition but the scrim was not actually blanked
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED,
                new ScrimController.Callback() {
                    @Override
                    public void onDisplayBlanked() {
                        // Front scrim should not be black nor opaque
                        Assert.assertTrue("Scrim should NOT be visible during transition."
                                + " Alpha: " + mScrimInFront.getViewAlpha(),
                                mScrimInFront.getViewAlpha() == 0f);
                        Assert.assertSame("Scrim should not be visible during transition.",
                                mScrimVisibility, TRANSPARENT);
                    }
                });
        finishAnimationsImmediately();
    }

    @Test
    public void testScrimCallback() {
        int[] callOrder = {0, 0, 0};
        int[] currentCall = {0};
        mScrimController.legacyTransitionTo(ScrimState.AOD, new ScrimController.Callback() {
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
        assertEquals("onStart called in wrong order", 1, callOrder[0]);
        assertEquals("onDisplayBlanked called in wrong order", 2, callOrder[1]);
        assertEquals("onFinished called in wrong order", 3, callOrder[2]);
    }

    @Test
    public void testScrimCallbacksWithoutAmbientDisplay() {
        mAlwaysOnEnabled = false;
        testScrimCallback();
    }

    @Test
    public void testScrimCallbackCancelled() {
        boolean[] cancelledCalled = {false};
        mScrimController.legacyTransitionTo(ScrimState.AOD, new ScrimController.Callback() {
            @Override
            public void onCancelled() {
                cancelledCalled[0] = true;
            }
        });
        mScrimController.legacyTransitionTo(ScrimState.PULSING);
        Assert.assertTrue("onCancelled should have been called", cancelledCalled[0]);
    }

    @Test
    public void testHoldsWakeLock_whenAOD() {
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        verify(mWakeLock).acquire(anyString());
        verify(mWakeLock, never()).release(anyString());
        finishAnimationsImmediately();
        verify(mWakeLock).release(anyString());
    }

    @Test
    public void testDoesNotHoldWakeLock_whenUnlocking() {
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();
        verifyZeroInteractions(mWakeLock);
    }

    @Test
    public void testCallbackInvokedOnSameStateTransition() {
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();
        ScrimController.Callback callback = mock(ScrimController.Callback.class);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED, callback);
        verify(callback).onFinished();
    }

    @Test
    public void testHoldsAodWallpaperAnimationLock() {
        // Pre-conditions
        mScrimController.legacyTransitionTo(ScrimState.AOD);
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
        mScrimController.legacyTransitionTo(ScrimState.PULSING);
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
        mWallpaperRepository.getWallpaperSupportsAmbientMode().setValue(true);
        mTestScope.getTestScheduler().runCurrent();

        mScrimController.legacyTransitionTo(ScrimState.AOD);
        verify(mAlarmManager).setExact(anyInt(), anyLong(), any(), any(), any());
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        verify(mAlarmManager).cancel(any(AlarmManager.OnAlarmListener.class));
    }

    @Test
    public void testWillHideDockedWallpaper() {
        mAlwaysOnEnabled = false;
        when(mDockManager.isDocked()).thenReturn(true);
        mWallpaperRepository.getWallpaperSupportsAmbientMode().setValue(true);
        mTestScope.getTestScheduler().runCurrent();

        mScrimController.legacyTransitionTo(ScrimState.AOD);

        verify(mAlarmManager).setExact(anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    public void testConservesExpansionOpacityAfterTransition() {
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        mScrimController.setRawPanelExpansionFraction(0.5f);
        finishAnimationsImmediately();

        final float expandedAlpha = mScrimBehind.getViewAlpha();

        mScrimController.legacyTransitionTo(ScrimState.BRIGHTNESS_MIRROR);
        finishAnimationsImmediately();
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        finishAnimationsImmediately();

        assertEquals("Scrim expansion opacity wasn't conserved when transitioning back",
                expandedAlpha, mScrimBehind.getViewAlpha(), 0.01f);
    }

    @Test
    public void testCancelsOldAnimationBeforeBlanking() {
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        // Consume whatever value we had before
        mAnimatorListener.reset();

        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        finishAnimationsImmediately();
        Assert.assertTrue("Animators not canceled", mAnimatorListener.getNumCancels() != 0);
    }

    @Test
    public void testScrimsAreNotFocusable() {
        assertFalse("Behind scrim should not be focusable", mScrimBehind.isFocusable());
        assertFalse("Front scrim should not be focusable", mScrimInFront.isFocusable());
        assertFalse("Notifications scrim should not be focusable",
                mNotificationsScrim.isFocusable());
    }

    @Test
    public void testHidesShowWhenLockedActivity() {
        mWallpaperRepository.getWallpaperSupportsAmbientMode().setValue(true);
        mTestScope.getTestScheduler().runCurrent();

        mScrimController.setKeyguardOccluded(true);
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, OPAQUE));

        mScrimController.legacyTransitionTo(ScrimState.PULSING);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, OPAQUE));
    }

    @Test
    public void testHidesShowWhenLockedActivity_whenAlreadyInAod() {
        mWallpaperRepository.getWallpaperSupportsAmbientMode().setValue(true);
        mTestScope.getTestScheduler().runCurrent();

        mScrimController.legacyTransitionTo(ScrimState.AOD);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT));

        mScrimController.setKeyguardOccluded(true);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, OPAQUE));
    }

    @Test
    public void testEatsTouchEvent() {
        HashSet<ScrimState> eatsTouches =
                new HashSet<>(Collections.singletonList(ScrimState.AOD));
        for (ScrimState state : ScrimState.values()) {
            if (state == ScrimState.UNINITIALIZED) {
                continue;
            }
            mScrimController.legacyTransitionTo(state);
            finishAnimationsImmediately();
            assertEquals("Should be clickable unless AOD or PULSING, was: " + state,
                    mScrimBehind.getViewAlpha() != 0 && !eatsTouches.contains(state),
                    mScrimBehind.isClickable());
        }
    }

    @Test
    public void testAnimatesTransitionToAod() {
        when(mDozeParameters.shouldControlScreenOff()).thenReturn(false);
        ScrimState.AOD.prepare(ScrimState.KEYGUARD);
        assertFalse("No animation when ColorFade kicks in",
                ScrimState.AOD.getAnimateChange());

        reset(mDozeParameters);
        when(mDozeParameters.shouldControlScreenOff()).thenReturn(true);
        ScrimState.AOD.prepare(ScrimState.KEYGUARD);
        Assert.assertTrue("Animate scrims when ColorFade won't be triggered",
                ScrimState.AOD.getAnimateChange());
    }

    @Test
    public void testIsLowPowerMode() {
        HashSet<ScrimState> lowPowerModeStates = new HashSet<>(Arrays.asList(
                ScrimState.OFF, ScrimState.AOD, ScrimState.PULSING));
        HashSet<ScrimState> regularStates = new HashSet<>(Arrays.asList(
                ScrimState.UNINITIALIZED, ScrimState.KEYGUARD, BOUNCER,
                ScrimState.DREAMING, ScrimState.BOUNCER_SCRIMMED, ScrimState.BRIGHTNESS_MIRROR,
                ScrimState.UNLOCKED, SHADE_LOCKED, ScrimState.AUTH_SCRIMMED,
                ScrimState.AUTH_SCRIMMED_SHADE, ScrimState.GLANCEABLE_HUB,
                ScrimState.GLANCEABLE_HUB_OVER_DREAM));

        for (ScrimState state : ScrimState.values()) {
            if (!lowPowerModeStates.contains(state) && !regularStates.contains(state)) {
                Assert.fail("Scrim state isn't categorized as a low power or regular state.");
            }
        }
    }

    @Test
    public void testScrimsOpaque_whenShadeFullyExpanded() {
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        mScrimController.setRawPanelExpansionFraction(1);
        // notifications scrim alpha change require calling setQsPosition
        mScrimController.setQsPosition(0, 300);
        finishAnimationsImmediately();

        assertEquals("Behind scrim should be opaque",
                mScrimBehind.getViewAlpha(), 1, 0.0);
        assertEquals("Notifications scrim should be opaque",
                mNotificationsScrim.getViewAlpha(), 1, 0.0);
    }

    @Test
    public void testAuthScrim_setClipQSScrimTrue_notifScrimOpaque_whenShadeFullyExpanded() {
        // GIVEN device has an activity showing ('UNLOCKED' state can occur on the lock screen
        // with the camera app occluding the keyguard)
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        mScrimController.setClipsQsScrim(true);
        mScrimController.setRawPanelExpansionFraction(1);
        // notifications scrim alpha change require calling setQsPosition
        mScrimController.setQsPosition(0, 300);
        finishAnimationsImmediately();

        // WHEN the user triggers the auth bouncer
        mScrimController.legacyTransitionTo(ScrimState.AUTH_SCRIMMED_SHADE);
        finishAnimationsImmediately();

        assertEquals("Behind scrim should be opaque",
                mScrimBehind.getViewAlpha(), 1, 0.0);
        assertEquals("Notifications scrim should be opaque",
                mNotificationsScrim.getViewAlpha(), 1, 0.0);

        assertScrimTinted(Map.of(
                mScrimInFront, true,
                mScrimBehind, true,
                mNotificationsScrim, false
        ));
    }


    @Test
    public void testAuthScrim_setClipQSScrimFalse_notifScrimOpaque_whenShadeFullyExpanded() {
        // GIVEN device has an activity showing ('UNLOCKED' state can occur on the lock screen
        // with the camera app occluding the keyguard)
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        mScrimController.setClipsQsScrim(false);
        mScrimController.setRawPanelExpansionFraction(1);
        // notifications scrim alpha change require calling setQsPosition
        mScrimController.setQsPosition(0, 300);
        finishAnimationsImmediately();

        // WHEN the user triggers the auth bouncer
        mScrimController.legacyTransitionTo(ScrimState.AUTH_SCRIMMED_SHADE);
        finishAnimationsImmediately();

        assertEquals("Behind scrim should be opaque",
                mScrimBehind.getViewAlpha(), 1, 0.0);
        assertEquals("Notifications scrim should be opaque",
                mNotificationsScrim.getViewAlpha(), 1, 0.0);

        assertScrimTinted(Map.of(
                mScrimInFront, true,
                mScrimBehind, true,
                mNotificationsScrim, false
        ));
    }

    @Test
    public void testAuthScrimKeyguard() {
        // GIVEN device is on the keyguard
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        finishAnimationsImmediately();

        // WHEN the user triggers the auth bouncer
        mScrimController.legacyTransitionTo(ScrimState.AUTH_SCRIMMED);
        finishAnimationsImmediately();

        // THEN the front scrim is updated and the KEYGUARD scrims are the same as the
        // KEYGUARD scrim state
        assertScrimAlpha(Map.of(
                mScrimInFront, SEMI_TRANSPARENT,
                mScrimBehind, SEMI_TRANSPARENT,
                mNotificationsScrim, TRANSPARENT));
    }

    @Test
    public void testScrimsVisible_whenShadeVisible() {
        mScrimController.setClipsQsScrim(true);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        mScrimController.setRawPanelExpansionFraction(0.3f);
        // notifications scrim alpha change require calling setQsPosition
        mScrimController.setQsPosition(0, 300);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimBehind, SEMI_TRANSPARENT,
                mNotificationsScrim, SEMI_TRANSPARENT,
                mScrimInFront, TRANSPARENT));
    }

    @Test
    public void testDoesntAnimate_whenUnlocking() {
        // LightRevealScrim will animate the transition, we should only hide the keyguard scrims.
        ScrimState.UNLOCKED.prepare(ScrimState.KEYGUARD);
        assertThat(ScrimState.UNLOCKED.getAnimateChange()).isTrue();
        ScrimState.UNLOCKED.prepare(ScrimState.PULSING);
        assertThat(ScrimState.UNLOCKED.getAnimateChange()).isFalse();

        ScrimState.UNLOCKED.prepare(ScrimState.KEYGUARD);
        assertThat(ScrimState.UNLOCKED.getAnimateChange()).isTrue();
        ScrimState.UNLOCKED.prepare(ScrimState.AOD);
        assertThat(ScrimState.UNLOCKED.getAnimateChange()).isFalse();

        // LightRevealScrim doesn't animate when AOD is disabled. We need to use the legacy anim.
        ScrimState.UNLOCKED.prepare(ScrimState.KEYGUARD);
        assertThat(ScrimState.UNLOCKED.getAnimateChange()).isTrue();
        ScrimState.UNLOCKED.prepare(ScrimState.OFF);
        assertThat(ScrimState.UNLOCKED.getAnimateChange()).isTrue();
    }

    @Test
    public void testScrimsVisible_whenShadeVisible_clippingQs() {
        mScrimController.setClipsQsScrim(true);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        mScrimController.setRawPanelExpansionFraction(0.3f);
        // notifications scrim alpha change require calling setQsPosition
        mScrimController.setQsPosition(0.5f, 300);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimBehind, OPAQUE,
                mNotificationsScrim, SEMI_TRANSPARENT,
                mScrimInFront, TRANSPARENT));
    }

    @Test
    public void testScrimsVisible_whenShadeVisibleOnLockscreen() {
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        mScrimController.setQsPosition(0.25f, 300);

        assertScrimAlpha(Map.of(
                mScrimBehind, SEMI_TRANSPARENT,
                mNotificationsScrim, SEMI_TRANSPARENT,
                mScrimInFront, TRANSPARENT));
    }

    @Test
    public void testNotificationScrimTransparent_whenOnLockscreen() {
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        // even if shade is not pulled down, panel has expansion of 1 on the lockscreen
        mScrimController.setRawPanelExpansionFraction(1);
        mScrimController.setQsPosition(0f, /*qs panel bottom*/ 0);

        assertScrimAlpha(Map.of(
                mScrimBehind, SEMI_TRANSPARENT,
                mNotificationsScrim, TRANSPARENT));
    }

    @Test
    public void testNotificationScrimVisible_afterOpeningShadeFromLockscreen() {
        mScrimController.setRawPanelExpansionFraction(1);
        mScrimController.legacyTransitionTo(SHADE_LOCKED);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimBehind, OPAQUE,
                mNotificationsScrim, OPAQUE));
    }

    @Test
    public void qsExpansion_BehindTint_shadeLocked_bouncerActive_usesBouncerProgress() {
        when(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).thenReturn(true);
        // clipping doesn't change tested logic but allows to assert scrims more in line with
        // their expected large screen behaviour
        mScrimController.setClipsQsScrim(false);
        mScrimController.legacyTransitionTo(SHADE_LOCKED);

        mScrimController.setQsPosition(1f, 100 /* value doesn't matter */);
        assertTintAfterExpansion(mScrimBehind, SHADE_LOCKED.getBehindTint(), /* expansion= */ 1f);

        mScrimController.setQsPosition(0.8f, 100 /* value doesn't matter */);
        // panel expansion of 0.6 means its fully transitioned with bouncer progress interpolation
        assertTintAfterExpansion(mScrimBehind, BOUNCER.getBehindTint(), /* expansion= */ 0.6f);
    }

    @Test
    public void expansionNotificationAlpha_shadeLocked_bouncerActive_usesBouncerInterpolator() {
        when(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).thenReturn(true);

        mScrimController.legacyTransitionTo(SHADE_LOCKED);

        float expansion = 0.8f;
        float expectedAlpha =
                BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(expansion);
        assertAlphaAfterExpansion(mNotificationsScrim, expectedAlpha, expansion);

        expansion = 0.2f;
        expectedAlpha = BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(expansion);
        assertAlphaAfterExpansion(mNotificationsScrim, expectedAlpha, expansion);
    }

    @Test
    public void expansionNotificationAlpha_shadeLocked_bouncerNotActive_usesShadeInterpolator() {
        when(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).thenReturn(false);

        mScrimController.legacyTransitionTo(SHADE_LOCKED);

        float expansion = 0.8f;
        float expectedAlpha = ShadeInterpolation.getNotificationScrimAlpha(expansion);
        assertAlphaAfterExpansion(mNotificationsScrim, expectedAlpha, expansion);

        expansion = 0.2f;
        expectedAlpha = ShadeInterpolation.getNotificationScrimAlpha(expansion);
        assertAlphaAfterExpansion(mNotificationsScrim, expectedAlpha, expansion);
    }

    @Test
    public void notificationAlpha_unnocclusionAnimating_bouncerNotActive_usesKeyguardNotifAlpha() {
        when(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).thenReturn(false);

        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);

        assertAlphaAfterExpansion(
                mNotificationsScrim, ScrimState.KEYGUARD.getNotifAlpha(), /* expansion */ 0f);
        assertAlphaAfterExpansion(
                mNotificationsScrim, ScrimState.KEYGUARD.getNotifAlpha(), /* expansion */ 0.4f);
        assertAlphaAfterExpansion(
                mNotificationsScrim, ScrimState.KEYGUARD.getNotifAlpha(), /* expansion */ 1.0f);

        // Verify normal behavior after
        float expansion = 0.4f;
        float alpha = 1 - ShadeInterpolation.getNotificationScrimAlpha(expansion);
        assertAlphaAfterExpansion(mNotificationsScrim, alpha, expansion);
    }

    @Test
    public void notificationAlpha_inKeyguardState_bouncerActive_usesInvertedBouncerInterpolator() {
        when(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).thenReturn(true);
        mScrimController.setClipsQsScrim(true);

        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);

        float expansion = 0.8f;
        float alpha = 1 - BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(expansion);
        assertAlphaAfterExpansion(mNotificationsScrim, alpha, expansion);

        expansion = 0.4f;
        alpha = 1 - BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(expansion);
        assertAlphaAfterExpansion(mNotificationsScrim, alpha, expansion);

        expansion = 0.2f;
        alpha = 1 - BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(expansion);
        assertAlphaAfterExpansion(mNotificationsScrim, alpha, expansion);
    }

    @Test
    public void notificationAlpha_inKeyguardState_bouncerNotActive_usesInvertedShadeInterpolator() {
        when(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).thenReturn(false);
        mScrimController.setClipsQsScrim(true);

        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);

        float expansion = 0.8f;
        float alpha = 1 - ShadeInterpolation.getNotificationScrimAlpha(expansion);
        assertAlphaAfterExpansion(mNotificationsScrim, alpha, expansion);

        expansion = 0.4f;
        alpha = 1 - ShadeInterpolation.getNotificationScrimAlpha(expansion);
        assertAlphaAfterExpansion(mNotificationsScrim, alpha, expansion);

        expansion = 0.2f;
        alpha = 1 - ShadeInterpolation.getNotificationScrimAlpha(expansion);
        assertAlphaAfterExpansion(mNotificationsScrim, alpha, expansion);
    }

    @Test
    public void behindTint_inKeyguardState_bouncerNotActive_usesKeyguardBehindTint() {
        when(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).thenReturn(false);
        mScrimController.setClipsQsScrim(false);

        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        finishAnimationsImmediately();
        assertThat(mScrimBehind.getTint())
                .isEqualTo(ScrimState.KEYGUARD.getBehindTint());
    }

    @Test
    public void testNotificationTransparency_followsTransitionToFullShade() {
        mScrimController.setClipsQsScrim(true);

        mScrimController.legacyTransitionTo(SHADE_LOCKED);
        mScrimController.setRawPanelExpansionFraction(1.0f);
        finishAnimationsImmediately();

        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mScrimBehind, true,
                mNotificationsScrim, false
        ));

        float shadeLockedAlpha = mNotificationsScrim.getViewAlpha();
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        mScrimController.setRawPanelExpansionFraction(1.0f);
        finishAnimationsImmediately();
        float keyguardAlpha = mNotificationsScrim.getViewAlpha();

        assertScrimTinted(Map.of(
                mScrimInFront, true,
                mScrimBehind, true,
                mNotificationsScrim, true
        ));

        float progress = 0.5f;
        float lsNotifProgress = 0.3f;
        mScrimController.setTransitionToFullShadeProgress(progress, lsNotifProgress);
        assertEquals(MathUtils.lerp(keyguardAlpha, shadeLockedAlpha, progress),
                mNotificationsScrim.getViewAlpha(), 0.2);
        progress = 0.0f;
        mScrimController.setTransitionToFullShadeProgress(progress, lsNotifProgress);
        assertEquals(MathUtils.lerp(keyguardAlpha, shadeLockedAlpha, progress),
                mNotificationsScrim.getViewAlpha(), 0.2);
        progress = 1.0f;
        mScrimController.setTransitionToFullShadeProgress(progress, lsNotifProgress);
        assertEquals(MathUtils.lerp(keyguardAlpha, shadeLockedAlpha, progress),
                mNotificationsScrim.getViewAlpha(), 0.2);
    }

    @Test
    public void notificationTransparency_followsNotificationScrimProgress() {
        mScrimController.legacyTransitionTo(SHADE_LOCKED);
        mScrimController.setRawPanelExpansionFraction(1.0f);
        finishAnimationsImmediately();
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        mScrimController.setRawPanelExpansionFraction(1.0f);
        finishAnimationsImmediately();

        float progress = 0.5f;
        float notifProgress = 0.3f;
        mScrimController.setTransitionToFullShadeProgress(progress, notifProgress);

        assertThat(mNotificationsScrim.getViewAlpha()).isEqualTo(notifProgress);
    }

    @Test
    public void notificationAlpha_qsNotClipped_alphaMatchesNotificationExpansionProgress() {
        mScrimController.setClipsQsScrim(false);
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        // RawPanelExpansion and QsExpansion are usually used for the notification alpha
        // calculation.
        // Here we set them to non-zero values explicitly to make sure that in not clipped mode,
        // they are not being used even when set.
        mScrimController.setRawPanelExpansionFraction(0.5f);
        mScrimController.setQsPosition(/* expansionFraction= */ 0.5f, /* qsPanelBottomY= */ 500);
        finishAnimationsImmediately();

        float progress = 0.5f;

        float notificationExpansionProgress = 0f;
        mScrimController.setTransitionToFullShadeProgress(progress, notificationExpansionProgress);
        mExpect.that(mNotificationsScrim.getViewAlpha()).isEqualTo(notificationExpansionProgress);

        notificationExpansionProgress = 0.25f;
        mScrimController.setTransitionToFullShadeProgress(progress, notificationExpansionProgress);
        mExpect.that(mNotificationsScrim.getViewAlpha()).isEqualTo(notificationExpansionProgress);

        notificationExpansionProgress = 0.5f;
        mScrimController.setTransitionToFullShadeProgress(progress, notificationExpansionProgress);
        mExpect.that(mNotificationsScrim.getViewAlpha()).isEqualTo(notificationExpansionProgress);

        notificationExpansionProgress = 0.75f;
        mScrimController.setTransitionToFullShadeProgress(progress, notificationExpansionProgress);
        mExpect.that(mNotificationsScrim.getViewAlpha()).isEqualTo(notificationExpansionProgress);

        notificationExpansionProgress = 1f;
        mScrimController.setTransitionToFullShadeProgress(progress, notificationExpansionProgress);
        mExpect.that(mNotificationsScrim.getViewAlpha()).isEqualTo(notificationExpansionProgress);
    }

    @Test
    public void setNotificationsOverScrollAmount_setsTranslationYOnNotificationsScrim() {
        int overScrollAmount = 10;

        mScrimController.setNotificationsOverScrollAmount(overScrollAmount);

        assertThat(mNotificationsScrim.getTranslationY()).isEqualTo(overScrollAmount);
    }

    @Test
    public void setNotificationsOverScrollAmount_doesNotSetTranslationYOnBehindScrim() {
        int overScrollAmount = 10;

        mScrimController.setNotificationsOverScrollAmount(overScrollAmount);

        assertThat(mScrimBehind.getTranslationY()).isEqualTo(0);
    }

    @Test
    public void setNotificationsOverScrollAmount_doesNotSetTranslationYOnFrontScrim() {
        int overScrollAmount = 10;

        mScrimController.setNotificationsOverScrollAmount(overScrollAmount);

        assertThat(mScrimInFront.getTranslationY()).isEqualTo(0);
    }

    @Test
    public void notificationBoundsTopGetsPassedToKeyguard() {
        mScrimController.legacyTransitionTo(SHADE_LOCKED);
        mScrimController.setQsPosition(1f, 0);
        finishAnimationsImmediately();

        mScrimController.setNotificationsBounds(0f, 100f, 0f, 0f);
        verify(mKeyguardInteractor).setTopClippingBounds(eq(100));
    }

    @Test
    public void notificationBoundsTopDoesNotGetPassedToKeyguardWhenNotifScrimIsNotVisible() {
        mScrimController.setKeyguardOccluded(true);
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        finishAnimationsImmediately();

        mScrimController.setNotificationsBounds(0f, 100f, 0f, 0f);
        verify(mKeyguardInteractor).setTopClippingBounds(eq(null));
    }

    @Test
    public void transitionToDreaming() {
        mScrimController.setRawPanelExpansionFraction(0f);
        mScrimController.setBouncerHiddenFraction(KeyguardBouncerConstants.EXPANSION_HIDDEN);
        mScrimController.legacyTransitionTo(ScrimState.DREAMING);
        finishAnimationsImmediately();

        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT,
                mScrimBehind, TRANSPARENT));

        assertScrimTinted(Map.of(
                mScrimInFront, false,
                mScrimBehind, true,
                mNotificationsScrim, false
        ));
    }

    @Test
    public void keyguardGoingAwayUpdateScrims() {
        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(true);
        mScrimController.updateScrims();
        finishAnimationsImmediately();
        assertThat(mNotificationsScrim.getViewAlpha()).isEqualTo(TRANSPARENT);
    }


    @Test
    public void setUnOccludingAnimationKeyguard() {
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        finishAnimationsImmediately();
        assertThat(mNotificationsScrim.getViewAlpha())
                .isWithin(0.01f).of(ScrimState.KEYGUARD.getNotifAlpha());
        assertThat(mNotificationsScrim.getTint())
                .isEqualTo(ScrimState.KEYGUARD.getNotifTint());
        assertThat(mScrimBehind.getViewAlpha())
                .isWithin(0.01f).of(ScrimState.KEYGUARD.getBehindAlpha());
        assertThat(mScrimBehind.getTint())
                .isEqualTo(ScrimState.KEYGUARD.getBehindTint());
    }

    @Test
    public void testHidesScrimFlickerInActivity() {
        mScrimController.setKeyguardOccluded(true);
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT));

        mScrimController.legacyTransitionTo(SHADE_LOCKED);
        finishAnimationsImmediately();
        assertScrimAlpha(Map.of(
                mScrimInFront, TRANSPARENT,
                mScrimBehind, TRANSPARENT,
                mNotificationsScrim, TRANSPARENT));
    }

    @Test
    public void notificationAlpha_inKeyguardState_bouncerNotActive_clipsQsScrimFalse() {
        mScrimController.setClipsQsScrim(false);
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);

        float expansion = 0.8f;
        assertAlphaAfterExpansion(mNotificationsScrim, 0f, expansion);
    }

    @Test
    public void aodStateSetsFrontScrimToNotBlend() {
        mScrimController.legacyTransitionTo(ScrimState.AOD);
        assertFalse("Front scrim should not blend with main color",
                mScrimInFront.shouldBlendWithMainColor());
    }

    @Test
    public void applyState_unlocked_bouncerShowing() {
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        mScrimController.setBouncerHiddenFraction(0.99f);
        mScrimController.setRawPanelExpansionFraction(0f);
        finishAnimationsImmediately();
        assertScrimAlpha(mScrimBehind, 0);
    }

    @Test
    public void ignoreTransitionRequestWhileKeyguardTransitionRunning() {
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        mScrimController.mBouncerToGoneTransition.accept(
                new TransitionStep(KeyguardState.PRIMARY_BOUNCER, KeyguardState.GONE, 0f,
                        TransitionState.RUNNING, "ScrimControllerTest"));

        // This request should not happen
        mScrimController.legacyTransitionTo(ScrimState.BOUNCER);
        assertThat(mScrimController.getState()).isEqualTo(ScrimState.UNLOCKED);
    }

    @Test
    public void primaryBouncerToGoneOnFinishCallsKeyguardFadedAway() {
        when(mKeyguardStateController.isKeyguardFadingAway()).thenReturn(true);
        mScrimController.mBouncerToGoneTransition.accept(
                new TransitionStep(KeyguardState.PRIMARY_BOUNCER, KeyguardState.GONE, 0f,
                        TransitionState.FINISHED, "ScrimControllerTest"));

        verify(mStatusBarKeyguardViewManager).onKeyguardFadedAway();
    }

    @Test
    public void testDoNotAnimateChangeIfOccludeAnimationPlaying() {
        mScrimController.setOccludeAnimationPlaying(true);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);

        assertFalse(ScrimState.UNLOCKED.mAnimateChange);
    }

    @Test
    public void testNotifScrimAlpha_1f_afterUnlockFinishedAndExpanded() {
        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        when(mKeyguardUnlockAnimationController.isPlayingCannedUnlockAnimation()).thenReturn(true);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);
        mScrimController.onUnlockAnimationFinished();
        assertAlphaAfterExpansion(mNotificationsScrim, 1f, 1f);
    }

    private void assertAlphaAfterExpansion(ScrimView scrim, float expectedAlpha, float expansion) {
        mScrimController.setRawPanelExpansionFraction(expansion);
        finishAnimationsImmediately();
        // alpha is not changing linearly thus 0.2 of leeway when asserting
        assertEquals(expectedAlpha, scrim.getViewAlpha(), 0.2);
    }

    private void assertTintAfterExpansion(ScrimView scrim, int expectedTint, float expansion) {
        String message = "Tint test failed with expected scrim tint: "
                + Integer.toHexString(expectedTint) + " and actual tint: "
                + Integer.toHexString(scrim.getTint()) + " for scrim: " + getScrimName(scrim);
        mScrimController.setRawPanelExpansionFraction(expansion);
        finishAnimationsImmediately();
        assertEquals(message, expectedTint, scrim.getTint(), 0.1);
    }

    private void assertScrimTinted(Map<ScrimView, Boolean> scrimToTint) {
        scrimToTint.forEach(this::assertScrimTint);
    }

    private void assertScrimTint(ScrimView scrim, boolean hasTint) {
        String message = "Tint test failed at state " + mScrimController.getState()
                + " with scrim: " + getScrimName(scrim) + " and tint: "
                + Integer.toHexString(scrim.getTint());
        assertEquals(message, hasTint, scrim.getTint() != Color.TRANSPARENT);
    }

    private void assertScrimTint(ScrimView scrim, int expectedTint) {
        String message = "Tint test failed with expected scrim tint: "
                + Integer.toHexString(expectedTint) + " and actual tint: "
                + Integer.toHexString(scrim.getTint()) + " for scrim: " + getScrimName(scrim);
        assertEquals(message, expectedTint, scrim.getTint(), 0.1);
    }

    private String getScrimName(ScrimView scrim) {
        if (scrim == mScrimInFront) {
            return "front";
        } else if (scrim == mScrimBehind) {
            return "behind";
        } else if (scrim == mNotificationsScrim) {
            return "notifications";
        }
        return "unknown_scrim";
    }

    /**
     * If {@link #mNotificationsScrim} is not passed in the map
     * we assume it must be transparent
     */
    private void assertScrimAlpha(Map<ScrimView, Integer> scrimToAlpha) {
        // Check single scrim visibility.
        if (!scrimToAlpha.containsKey(mNotificationsScrim)) {
            assertScrimAlpha(mNotificationsScrim, TRANSPARENT);
        }
        scrimToAlpha.forEach(this::assertScrimAlpha);

        // When clipping, QS scrim should not affect combined visibility.
        if (mScrimController.getClipQsScrim() && scrimToAlpha.get(mScrimBehind) == OPAQUE) {
            scrimToAlpha = new HashMap<>(scrimToAlpha);
            scrimToAlpha.remove(mScrimBehind);
        }

        // Check combined scrim visibility.
        final int visibility;
        if (scrimToAlpha.values().contains(OPAQUE)) {
            visibility = OPAQUE;
        } else if (scrimToAlpha.values().contains(SEMI_TRANSPARENT)) {
            visibility = SEMI_TRANSPARENT;
        } else {
            visibility = TRANSPARENT;
        }
        assertEquals("Invalid visibility.",
                visibility /* expected */,
                mScrimVisibility);
    }

    private void assertScrimAlpha(ScrimView scrim, int expectedAlpha) {
        assertEquals("Unexpected " + getScrimName(scrim) + " scrim alpha: "
                        + scrim.getViewAlpha(),
                expectedAlpha != TRANSPARENT /* expected */,
                scrim.getViewAlpha() > TRANSPARENT /* actual */);
    }
}
