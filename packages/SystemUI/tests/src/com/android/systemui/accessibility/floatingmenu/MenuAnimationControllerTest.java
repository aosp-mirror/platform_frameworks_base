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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.graphics.PointF;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.Prefs;
import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MenuAnimationController}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class MenuAnimationControllerTest extends SysuiTestCase {

    private boolean mLastIsMoveToTucked;
    private ViewPropertyAnimator mViewPropertyAnimator;
    private MenuView mMenuView;
    private MenuAnimationController mMenuAnimationController;

    @Before
    public void setUp() throws Exception {
        final WindowManager stubWindowManager = mContext.getSystemService(WindowManager.class);
        final MenuViewAppearance stubMenuViewAppearance = new MenuViewAppearance(mContext,
                stubWindowManager);
        final MenuViewModel stubMenuViewModel = new MenuViewModel(mContext);

        mMenuView = spy(new MenuView(mContext, stubMenuViewModel, stubMenuViewAppearance));
        mViewPropertyAnimator = spy(mMenuView.animate());
        doReturn(mViewPropertyAnimator).when(mMenuView).animate();

        mMenuAnimationController = new MenuAnimationController(mMenuView);
        mLastIsMoveToTucked = Prefs.getBoolean(mContext,
                Prefs.Key.HAS_ACCESSIBILITY_FLOATING_MENU_TUCKED, /* defaultValue= */ false);
    }

    @After
    public void tearDown() throws Exception {
        Prefs.putBoolean(mContext, Prefs.Key.HAS_ACCESSIBILITY_FLOATING_MENU_TUCKED,
                mLastIsMoveToTucked);
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
}
