/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.filters.SmallTest;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/** Tests for {@link AccessibilityFloatingMenuView}. */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AccessibilityFloatingMenuViewTest extends SysuiTestCase {
    private AccessibilityFloatingMenuView mMenuView;

    @Mock
    private WindowManager mWindowManager;

    @Mock
    private ViewPropertyAnimator mAnimator;

    private RecyclerView mListView;

    private final List<AccessibilityTarget> mTargets = new ArrayList<>();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        doAnswer(invocation -> wm.getMaximumWindowMetrics()).when(
                mWindowManager).getMaximumWindowMetrics();
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);

        mTargets.add(mock(AccessibilityTarget.class));
        mListView = spy(new RecyclerView(mContext));
        mMenuView = new AccessibilityFloatingMenuView(mContext);
    }

    @Test
    public void initListView_success() {
        assertThat(mMenuView.getChildCount()).isEqualTo(1);
    }

    @Test
    public void showMenuView_success() {
        mMenuView.show();

        assertThat(mMenuView.isShowing()).isTrue();
        verify(mWindowManager).addView(eq(mMenuView), any(WindowManager.LayoutParams.class));
    }

    @Test
    public void showMenuView_showTwice_addViewOnce() {
        mMenuView.show();
        mMenuView.show();

        assertThat(mMenuView.isShowing()).isTrue();
        verify(mWindowManager, times(1)).addView(eq(mMenuView),
                any(WindowManager.LayoutParams.class));
    }

    @Test
    public void hideMenuView_success() {
        mMenuView.show();
        mMenuView.hide();

        assertThat(mMenuView.isShowing()).isFalse();
        verify(mWindowManager).removeView(eq(mMenuView));
    }

    @Test
    public void hideMenuView_hideTwice_removeViewOnce() {
        mMenuView.show();
        mMenuView.hide();
        mMenuView.hide();

        assertThat(mMenuView.isShowing()).isFalse();
        verify(mWindowManager, times(1)).removeView(eq(mMenuView));
    }

    @Test
    public void updateListViewRadius_singleTarget_matchResult() {
        final float radius =
                getContext().getResources().getDimensionPixelSize(
                        R.dimen.accessibility_floating_menu_small_single_radius);
        final float[] expectedRadii =
                new float[]{radius, radius, 0.0f, 0.0f, 0.0f, 0.0f, radius, radius};

        mMenuView.onTargetsChanged(mTargets);
        final View view = mMenuView.getChildAt(0);
        final LayerDrawable layerDrawable = (LayerDrawable) view.getBackground();
        final GradientDrawable gradientDrawable =
                (GradientDrawable) layerDrawable.getDrawable(0);
        final float[] actualRadii = gradientDrawable.getCornerRadii();

        assertThat(actualRadii).isEqualTo(expectedRadii);
    }

    @Test
    public void setSizeType_largeSize_matchResult() {
        final int shapeType = 2;
        final float radius = getContext().getResources().getDimensionPixelSize(
                R.dimen.accessibility_floating_menu_large_single_radius);
        final float[] expectedRadii =
                new float[]{radius, radius, 0.0f, 0.0f, 0.0f, 0.0f, radius, radius};
        final Drawable listViewBackground =
                mContext.getDrawable(R.drawable.accessibility_floating_menu_background);
        mListView = spy(new RecyclerView(mContext));
        mListView.setBackground(listViewBackground);

        mMenuView = new AccessibilityFloatingMenuView(mContext, mListView);
        mMenuView.setSizeType(shapeType);
        final LayerDrawable layerDrawable =
                (LayerDrawable) mListView.getBackground();
        final GradientDrawable gradientDrawable =
                (GradientDrawable) layerDrawable.getDrawable(0);

        assertThat(gradientDrawable.getCornerRadii()).isEqualTo(expectedRadii);
    }

    @Test
    public void setShapeType_halfCircle_translationX() {
        final int shapeType = 2;
        doReturn(mAnimator).when(mListView).animate();

        mMenuView = new AccessibilityFloatingMenuView(mContext, mListView);
        mMenuView.setShapeType(shapeType);

        verify(mAnimator).translationX(anyFloat());
    }
}
