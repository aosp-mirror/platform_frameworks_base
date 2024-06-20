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

package com.android.systemui.accessibility.floatingmenu;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.graphics.PointF;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FlingAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.test.filters.SmallTest;

import com.android.systemui.Prefs;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.utils.TestUtils;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Optional;

/** Tests for {@link MenuAnimationController}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class MenuAnimationControllerTest extends SysuiTestCase {

    private boolean mLastIsMoveToTucked;
    private ArgumentCaptor<DynamicAnimation.OnAnimationEndListener> mEndListenerCaptor;
    private ViewPropertyAnimator mViewPropertyAnimator;
    private MenuView mMenuView;
    private TestMenuAnimationController mMenuAnimationController;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private AccessibilityManager mAccessibilityManager;

    @Before
    public void setUp() throws Exception {
        final WindowManager stubWindowManager = mContext.getSystemService(WindowManager.class);
        final MenuViewAppearance stubMenuViewAppearance = new MenuViewAppearance(mContext,
                stubWindowManager);
        final SecureSettings secureSettings = TestUtils.mockSecureSettings();
        final MenuViewModel stubMenuViewModel = new MenuViewModel(mContext, mAccessibilityManager,
                secureSettings);

        mMenuView = spy(new MenuView(mContext, stubMenuViewModel, stubMenuViewAppearance,
                secureSettings));
        mViewPropertyAnimator = spy(mMenuView.animate());
        doReturn(mViewPropertyAnimator).when(mMenuView).animate();

        mMenuAnimationController = new TestMenuAnimationController(
                mMenuView, stubMenuViewAppearance);
        mLastIsMoveToTucked = Prefs.getBoolean(mContext,
                Prefs.Key.HAS_ACCESSIBILITY_FLOATING_MENU_TUCKED, /* defaultValue= */ false);
        mEndListenerCaptor = ArgumentCaptor.forClass(DynamicAnimation.OnAnimationEndListener.class);
    }

    @After
    public void tearDown() throws Exception {
        Prefs.putBoolean(mContext, Prefs.Key.HAS_ACCESSIBILITY_FLOATING_MENU_TUCKED,
                mLastIsMoveToTucked);
        mEndListenerCaptor.getAllValues().clear();
        mMenuAnimationController.mPositionAnimations.values().forEach(DynamicAnimation::cancel);
    }

    @Test
    public void moveToPosition_matchPosition() {
        final PointF destination = new PointF(50, 60);

        mMenuAnimationController.moveToPosition(destination);

        assertThat(mMenuView.getTranslationX()).isEqualTo(50);
        assertThat(mMenuView.getTranslationY()).isEqualTo(60);
    }

    @Test
    public void startShrinkAnimation_verifyAnimationEndAction() {
        mMenuAnimationController.startShrinkAnimation(() -> mMenuView.setVisibility(View.VISIBLE));

        verify(mViewPropertyAnimator).withEndAction(any(Runnable.class));
    }

    @Test
    public void startGrowAnimation_menuCompletelyOpaque() {
        mMenuAnimationController.startShrinkAnimation(/* endAction= */ null);

        mMenuAnimationController.startGrowAnimation();

        assertThat(mMenuView.getAlpha()).isEqualTo(/* completelyOpaque */ 1.0f);
    }

    @Test
    public void moveToEdgeAndHide_untucked_expectedSharedPreferenceValue() {
        Prefs.putBoolean(mContext, Prefs.Key.HAS_ACCESSIBILITY_FLOATING_MENU_TUCKED, /* value= */
                false);

        mMenuAnimationController.moveToEdgeAndHide();
        final boolean isMoveToTucked = Prefs.getBoolean(mContext,
                Prefs.Key.HAS_ACCESSIBILITY_FLOATING_MENU_TUCKED, /* defaultValue= */ false);

        assertThat(isMoveToTucked).isTrue();
    }

    @Test
    public void moveOutEdgeAndShow_tucked_expectedSharedPreferenceValue() {
        Prefs.putBoolean(mContext, Prefs.Key.HAS_ACCESSIBILITY_FLOATING_MENU_TUCKED, /* value= */
                true);

        mMenuAnimationController.moveOutEdgeAndShow();
        final boolean isMoveToTucked = Prefs.getBoolean(mContext,
                Prefs.Key.HAS_ACCESSIBILITY_FLOATING_MENU_TUCKED, /* defaultValue= */ true);

        assertThat(isMoveToTucked).isFalse();
    }

    @Test
    public void startTuckedAnimationPreview_hasAnimation() {
        mMenuView.clearAnimation();

        mMenuAnimationController.startTuckedAnimationPreview();

        assertThat(mMenuView.getAnimation()).isNotNull();
    }

    @Test
    public void startSpringAnimationsAndEndOneAnimation_notTriggerEndAction() {
        final Runnable onSpringAnimationsEndCallback = mock(Runnable.class);
        mMenuAnimationController.setSpringAnimationsEndAction(onSpringAnimationsEndCallback);

        setupAndRunSpringAnimations();
        final Optional<DynamicAnimation> anyAnimation =
                mMenuAnimationController.mPositionAnimations.values().stream().findAny();
        anyAnimation.ifPresent(this::skipAnimationToEnd);

        verifyZeroInteractions(onSpringAnimationsEndCallback);
    }

    @Test
    public void startAndEndSpringAnimations_triggerEndAction() {
        final Runnable onSpringAnimationsEndCallback = mock(Runnable.class);
        mMenuAnimationController.setSpringAnimationsEndAction(onSpringAnimationsEndCallback);

        setupAndRunSpringAnimations();
        mMenuAnimationController.mPositionAnimations.values().forEach(this::skipAnimationToEnd);

        verify(onSpringAnimationsEndCallback).run();
    }

    @Test
    public void flingThenSpringAnimationsAreEnded_triggerEndAction() {
        final Runnable onSpringAnimationsEndCallback = mock(Runnable.class);
        mMenuAnimationController.setSpringAnimationsEndAction(onSpringAnimationsEndCallback);

        mMenuAnimationController.flingMenuThenSpringToEdge(/* x= */ 0, /* velocityX= */
                100, /* velocityY= */ 100);
        mMenuAnimationController.mPositionAnimations.values()
                .forEach(animation -> verify((FlingAnimation) animation).addEndListener(
                        mEndListenerCaptor.capture()));
        mEndListenerCaptor.getAllValues()
                .forEach(listener -> listener.onAnimationEnd(mock(DynamicAnimation.class),
                        /* canceled */ false, /* endValue */ 0, /* endVelocity */ 0));
        mMenuAnimationController.mPositionAnimations.values().forEach(this::skipAnimationToEnd);

        verify(onSpringAnimationsEndCallback).run();
    }

    @Test
    public void existFlingIsRunningAndTheOtherAreEnd_notTriggerEndAction() {
        final Runnable onSpringAnimationsEndCallback = mock(Runnable.class);
        mMenuAnimationController.setSpringAnimationsEndAction(onSpringAnimationsEndCallback);

        mMenuAnimationController.flingMenuThenSpringToEdge(/* x= */ 0, /* velocityX= */
                200, /* velocityY= */ 200);
        mMenuAnimationController.mPositionAnimations.values()
                .forEach(animation -> verify((FlingAnimation) animation).addEndListener(
                        mEndListenerCaptor.capture()));
        final Optional<DynamicAnimation.OnAnimationEndListener> anyAnimation =
                mEndListenerCaptor.getAllValues().stream().findAny();
        anyAnimation.ifPresent(
                listener -> listener.onAnimationEnd(mock(DynamicAnimation.class), /* canceled */
                        false, /* endValue */ 0, /* endVelocity */ 0));
        mMenuAnimationController.mPositionAnimations.values()
                .stream()
                .filter(animation -> animation instanceof SpringAnimation)
                .forEach(this::skipAnimationToEnd);

        verifyZeroInteractions(onSpringAnimationsEndCallback);
    }

    @Test
    public void tuck_animates() {
        mMenuAnimationController.cancelAnimations();
        mMenuAnimationController.moveToEdgeAndHide();
        assertThat(mMenuAnimationController.getAnimation(
                DynamicAnimation.TRANSLATION_X).isRunning()).isTrue();
    }

    @Test
    public void untuck_animates() {
        mMenuAnimationController.cancelAnimations();
        mMenuAnimationController.moveOutEdgeAndShow();
        assertThat(mMenuAnimationController.getAnimation(
                DynamicAnimation.TRANSLATION_X).isRunning()).isTrue();
    }

    private void setupAndRunSpringAnimations() {
        final float stiffness = 700f;
        final float dampingRatio = 0.85f;
        final float velocity = 100f;
        final float finalPosition = 300f;

        mMenuAnimationController.springMenuWith(DynamicAnimation.TRANSLATION_X, new SpringForce()
                .setStiffness(stiffness)
                .setDampingRatio(dampingRatio), velocity, finalPosition,
                /* writeToPosition = */ true);
        mMenuAnimationController.springMenuWith(DynamicAnimation.TRANSLATION_Y, new SpringForce()
                .setStiffness(stiffness)
                .setDampingRatio(dampingRatio), velocity, finalPosition,
                /* writeToPosition = */ true);
    }

    private void skipAnimationToEnd(DynamicAnimation animation) {
        final SpringAnimation springAnimation = ((SpringAnimation) animation);
        // The doAnimationFrame function is used for skipping animation to the end.
        springAnimation.doAnimationFrame(100);
        springAnimation.skipToEnd();
        springAnimation.doAnimationFrame(200);
    }

    /**
     * Wrapper class for testing.
     */
    private static class TestMenuAnimationController extends MenuAnimationController {
        TestMenuAnimationController(MenuView menuView, MenuViewAppearance menuViewAppearance) {
            super(menuView, menuViewAppearance);
        }

        @Override
        FlingAnimation createFlingAnimation(MenuView menuView,
                MenuPositionProperty menuPositionProperty) {
            return spy(super.createFlingAnimation(menuView, menuPositionProperty));
        }
    }
}
