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

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.View.OVER_SCROLL_ALWAYS;
import static android.view.View.OVER_SCROLL_NEVER;
import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.systemBars;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.filters.SmallTest;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.MotionEventHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tests for {@link AccessibilityFloatingMenuView}. */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AccessibilityFloatingMenuViewTest extends SysuiTestCase {

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    private final MotionEventHelper mMotionEventHelper = new MotionEventHelper();
    private final List<AccessibilityTarget> mTargets = new ArrayList<>(
            Collections.singletonList(mock(AccessibilityTarget.class)));

    private final Position mPlaceholderPosition = new Position(0.0f, 0.0f);

    @Mock
    private WindowManager mWindowManager;
    @Mock
    private ViewPropertyAnimator mAnimator;
    @Mock
    private WindowMetrics mWindowMetrics;
    private MotionEvent mInterceptMotionEvent;
    private AccessibilityFloatingMenuView mMenuView;
    private RecyclerView mListView = new RecyclerView(mContext);

    private int mMenuWindowHeight;
    private int mMenuHalfWidth;
    private int mMenuHalfHeight;
    private int mDisplayHalfWidth;
    private int mDisplayHalfHeight;
    private int mMaxWindowX;
    private int mMaxWindowY;
    private final int mDisplayWindowWidth = 1080;
    private final int mDisplayWindowHeight = 2340;

    @Before
    public void initMenuView() {
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        doAnswer(invocation -> wm.getMaximumWindowMetrics()).when(
                mWindowManager).getMaximumWindowMetrics();
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);
        when(mWindowManager.getCurrentWindowMetrics()).thenReturn(mWindowMetrics);
        when(mWindowMetrics.getBounds()).thenReturn(new Rect(0, 0, mDisplayWindowWidth,
                mDisplayWindowHeight));
        when(mWindowMetrics.getWindowInsets()).thenReturn(fakeDisplayInsets());
        mMenuView = spy(
                new AccessibilityFloatingMenuView(mContext, mPlaceholderPosition, mListView));
    }

    @Before
    public void setUpMatrices() {
        final Resources res = mContext.getResources();
        final int margin =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_margin);
        final int padding =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_small_padding);
        final int iconWidthHeight =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_small_width_height);
        final int menuWidth = padding * 2 + iconWidthHeight;
        final int menuHeight = (padding + iconWidthHeight) * mTargets.size() + padding;
        mMenuHalfWidth = menuWidth / 2;
        mMenuHalfHeight = menuHeight / 2;
        mDisplayHalfWidth = mDisplayWindowWidth / 2;
        mDisplayHalfHeight = mDisplayWindowHeight / 2;
        int marginStartEnd =
                mContext.getResources().getConfiguration().orientation == ORIENTATION_PORTRAIT
                        ? margin : 0;
        mMaxWindowX = mDisplayWindowWidth - marginStartEnd - menuWidth;
        mMenuWindowHeight = menuHeight + margin * 2;
        mMaxWindowY = mDisplayWindowHeight - mMenuWindowHeight;
    }

    @Test
    public void initListView_success() {
        assertThat(mListView.getCompatAccessibilityDelegate()).isNotNull();
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
    public void onTargetsChanged_singleTarget_expectedRadii() {
        final Position alignRightPosition = new Position(1.0f, 0.0f);
        final AccessibilityFloatingMenuView menuView = new AccessibilityFloatingMenuView(mContext,
                alignRightPosition);
        setupBasicMenuView(menuView);

        menuView.onTargetsChanged(mTargets);

        final View view = menuView.getChildAt(0);
        final LayerDrawable layerDrawable = (LayerDrawable) view.getBackground();
        final GradientDrawable gradientDrawable =
                (GradientDrawable) layerDrawable.getDrawable(0);
        final float smallRadius =
                getContext().getResources().getDimensionPixelSize(
                        R.dimen.accessibility_floating_menu_small_single_radius);
        final float[] expectedRadii =
                new float[]{smallRadius, smallRadius, 0.0f, 0.0f, 0.0f, 0.0f, smallRadius,
                        smallRadius};
        assertThat(gradientDrawable.getCornerRadii()).isEqualTo(expectedRadii);
    }

    @Test
    public void setSizeType_alignRightAndLargeSize_expectedRadii() {
        final RecyclerView listView = spy(new RecyclerView(mContext));
        final Position alignRightPosition = new Position(1.0f, 0.0f);
        final AccessibilityFloatingMenuView menuView = new AccessibilityFloatingMenuView(mContext,
                alignRightPosition, listView);
        setupBasicMenuView(menuView);

        menuView.setSizeType(/* largeSize */ 1);

        final LayerDrawable layerDrawable =
                (LayerDrawable) listView.getBackground();
        final GradientDrawable gradientDrawable =
                (GradientDrawable) layerDrawable.getDrawable(0);
        final float largeRadius = getContext().getResources().getDimensionPixelSize(
                R.dimen.accessibility_floating_menu_large_single_radius);
        final float[] expectedRadii =
                new float[] {largeRadius, largeRadius, 0.0f, 0.0f, 0.0f, 0.0f, largeRadius,
                        largeRadius};
        assertThat(gradientDrawable.getCornerRadii()).isEqualTo(expectedRadii);
    }

    @Test
    public void setShapeType_halfCircle_translationX() {
        final RecyclerView listView = spy(new RecyclerView(mContext));
        final AccessibilityFloatingMenuView menuView =
                new AccessibilityFloatingMenuView(mContext, mPlaceholderPosition, listView);
        setupBasicMenuView(menuView);
        doReturn(mAnimator).when(listView).animate();

        menuView.setShapeType(/* halfOvalShape */ 1);

        verify(mAnimator).translationX(anyFloat());
    }

    @Test
    public void onTargetsChanged_fadeInOut() {
        final InOrder inOrderMenuView = inOrder(mMenuView);

        mMenuView.onTargetsChanged(mTargets);

        inOrderMenuView.verify(mMenuView).fadeIn();
        inOrderMenuView.verify(mMenuView).fadeOut();
    }

    @Test
    public void setSizeType_fadeInOut() {
        final InOrder inOrderMenuView = inOrder(mMenuView);

        mMenuView.setSizeType(/* smallSize */ 0);

        inOrderMenuView.verify(mMenuView).fadeIn();
        inOrderMenuView.verify(mMenuView).fadeOut();
    }

    @Test
    public void tapOnAndDragMenu_interceptUpEvent() {
        final RecyclerView listView = new RecyclerView(mContext);
        final TestAccessibilityFloatingMenu menuView =
                new TestAccessibilityFloatingMenu(mContext, mPlaceholderPosition, listView);
        setupBasicMenuView(menuView);
        final int currentWindowX = menuView.mCurrentLayoutParams.x;
        final int currentWindowY = menuView.mCurrentLayoutParams.y;
        final MotionEvent downEvent =
                mMotionEventHelper.obtainMotionEvent(0, 1,
                        MotionEvent.ACTION_DOWN,
                        currentWindowX + /* offsetXToMenuCenterX */ mMenuHalfWidth,
                        currentWindowY + /* offsetYToMenuCenterY */ mMenuHalfHeight);
        final MotionEvent moveEvent =
                mMotionEventHelper.obtainMotionEvent(2, 3,
                        MotionEvent.ACTION_MOVE,
                        /* displayCenterX */mDisplayHalfWidth
                                - /* offsetXToDisplayLeftHalfRegion */ 10,
                        /* displayCenterY */ mDisplayHalfHeight);
        final MotionEvent upEvent =
                mMotionEventHelper.obtainMotionEvent(4, 5,
                        MotionEvent.ACTION_UP,
                        /* displayCenterX */ mDisplayHalfWidth
                                - /* offsetXToDisplayLeftHalfRegion */ 10,
                        /* displayCenterY */ mDisplayHalfHeight);

        listView.dispatchTouchEvent(downEvent);
        listView.dispatchTouchEvent(moveEvent);
        listView.dispatchTouchEvent(upEvent);

        assertThat(mInterceptMotionEvent.getAction()).isEqualTo(MotionEvent.ACTION_UP);
    }

    @Test
    public void tapOnAndDragMenu_matchLocation() {
        final float expectedX = 1.0f;
        final float expectedY = 0.7f;
        final Position position = new Position(expectedX, expectedY);
        final RecyclerView listView = new RecyclerView(mContext);
        final AccessibilityFloatingMenuView menuView = new AccessibilityFloatingMenuView(mContext,
                position, listView);
        setupBasicMenuView(menuView);
        final int currentWindowX = menuView.mCurrentLayoutParams.x;
        final int currentWindowY = menuView.mCurrentLayoutParams.y;
        final MotionEvent downEvent =
                mMotionEventHelper.obtainMotionEvent(0, 1,
                        MotionEvent.ACTION_DOWN,
                        currentWindowX + /* offsetXToMenuCenterX */ mMenuHalfWidth,
                        currentWindowY + /* offsetYToMenuCenterY */ mMenuHalfHeight);
        final MotionEvent moveEvent =
                mMotionEventHelper.obtainMotionEvent(2, 3,
                        MotionEvent.ACTION_MOVE,
                        /* displayCenterX */mDisplayHalfWidth
                                + /* offsetXToDisplayRightHalfRegion */ 10,
                        /* displayCenterY */ mDisplayHalfHeight);
        final MotionEvent upEvent =
                mMotionEventHelper.obtainMotionEvent(4, 5,
                        MotionEvent.ACTION_UP,
                        /* displayCenterX */ mDisplayHalfWidth
                                + /* offsetXToDisplayRightHalfRegion */ 10,
                        /* displayCenterY */ mDisplayHalfHeight);

        listView.dispatchTouchEvent(downEvent);
        listView.dispatchTouchEvent(moveEvent);
        listView.dispatchTouchEvent(upEvent);
        menuView.mDragAnimator.end();

        assertThat((float) menuView.mCurrentLayoutParams.x).isWithin(1.0f).of(mMaxWindowX);
        assertThat((float) menuView.mCurrentLayoutParams.y).isWithin(1.0f).of(
                /* newWindowY = displayCenterY - offsetY */ mDisplayHalfHeight - mMenuHalfHeight);
    }


    @Test
    public void tapOnAndDragMenuToDisplaySide_transformShapeHalfOval() {
        final Position alignRightPosition = new Position(1.0f, 0.8f);
        final RecyclerView listView = new RecyclerView(mContext);
        final AccessibilityFloatingMenuView menuView = new AccessibilityFloatingMenuView(mContext,
                alignRightPosition, listView);
        setupBasicMenuView(menuView);

        final int currentWindowX = menuView.mCurrentLayoutParams.x;
        final int currentWindowY = menuView.mCurrentLayoutParams.y;
        final MotionEvent downEvent =
                mMotionEventHelper.obtainMotionEvent(0, 1,
                        MotionEvent.ACTION_DOWN,
                        currentWindowX + /* offsetXToMenuCenterX */ mMenuHalfWidth,
                        currentWindowY + /* offsetYToMenuCenterY */ mMenuHalfHeight);
        final MotionEvent moveEvent =
                mMotionEventHelper.obtainMotionEvent(2, 3,
                        MotionEvent.ACTION_MOVE,
                        /* downX */(currentWindowX + mMenuHalfWidth)
                                + /* offsetXToDisplayRightSide */ mMenuHalfWidth,
                        /* downY */ (currentWindowY +  mMenuHalfHeight));
        final MotionEvent upEvent =
                mMotionEventHelper.obtainMotionEvent(4, 5,
                        MotionEvent.ACTION_UP,
                        /* downX */(currentWindowX + mMenuHalfWidth)
                                + /* offsetXToDisplayRightSide */ mMenuHalfWidth,
                        /* downY */ (currentWindowY +  mMenuHalfHeight));

        listView.dispatchTouchEvent(downEvent);
        listView.dispatchTouchEvent(moveEvent);
        listView.dispatchTouchEvent(upEvent);

        assertThat(menuView.mShapeType).isEqualTo(/* halfOval */ 1);
    }

    @Test
    public void onTargetsChanged_exceedAvailableHeight_overScrollAlways() {
        doReturn(true).when(mMenuView).hasExceededMaxLayoutHeight();

        mMenuView.onTargetsChanged(mTargets);

        assertThat(mListView.getOverScrollMode()).isEqualTo(OVER_SCROLL_ALWAYS);
    }

    @Test
    public void onTargetsChanged_notExceedAvailableHeight_overScrollNever() {
        doReturn(false).when(mMenuView).hasExceededMaxLayoutHeight();

        mMenuView.onTargetsChanged(mTargets);

        assertThat(mListView.getOverScrollMode()).isEqualTo(OVER_SCROLL_NEVER);
    }

    @Test
    public void showMenuView_insetsListener_overlapWithIme_menuViewShifted() {
        final int offset = 200;

        final Position alignRightPosition = new Position(1.0f, 0.8f);
        final AccessibilityFloatingMenuView menuView = new AccessibilityFloatingMenuView(mContext,
                alignRightPosition);
        setupBasicMenuView(menuView);
        final WindowInsets imeInset = fakeImeInsetWith(menuView, offset);
        when(mWindowManager.getCurrentWindowMetrics()).thenReturn(mWindowMetrics);
        when(mWindowMetrics.getWindowInsets()).thenReturn(imeInset);
        final int expectedLayoutY = menuView.mCurrentLayoutParams.y - offset;
        menuView.dispatchApplyWindowInsets(imeInset);

        assertThat(menuView.mCurrentLayoutParams.y).isEqualTo(expectedLayoutY);
    }

    @Test
    public void hideIme_onMenuViewShifted_menuViewMovedBack() {
        final int offset = 200;
        setupBasicMenuView(mMenuView);
        final WindowInsets imeInset = fakeImeInsetWith(mMenuView, offset);
        when(mWindowManager.getCurrentWindowMetrics()).thenReturn(mWindowMetrics);
        when(mWindowMetrics.getWindowInsets()).thenReturn(imeInset);
        final int expectedLayoutY = mMenuView.mCurrentLayoutParams.y;
        mMenuView.dispatchApplyWindowInsets(imeInset);

        mMenuView.dispatchApplyWindowInsets(
                new WindowInsets.Builder().setVisible(ime(), false).build());

        assertThat(mMenuView.mCurrentLayoutParams.y).isEqualTo(expectedLayoutY);
    }

    @Test
    public void showMenuAndIme_withHigherIme_alignDisplayTopEdge() {
        final int offset = 99999;

        setupBasicMenuView(mMenuView);
        final WindowInsets imeInset = fakeImeInsetWith(mMenuView, offset);
        when(mWindowManager.getCurrentWindowMetrics()).thenReturn(mWindowMetrics);
        when(mWindowMetrics.getWindowInsets()).thenReturn(imeInset);
        mMenuView.dispatchApplyWindowInsets(imeInset);

        assertThat(mMenuView.mCurrentLayoutParams.y).isEqualTo(0);
    }

    @Test
    public void testConstructor_withPosition_expectedPosition() {
        final float expectedX = 1.0f;
        final float expectedY = 0.7f;
        final Position position = new Position(expectedX, expectedY);

        final AccessibilityFloatingMenuView menuView = new AccessibilityFloatingMenuView(mContext,
                position);
        setupBasicMenuView(menuView);

        assertThat((float) menuView.mCurrentLayoutParams.x).isWithin(1.0f).of(mMaxWindowX);
        assertThat((float) menuView.mCurrentLayoutParams.y).isWithin(1.0f).of(
                expectedY * mMaxWindowY);
    }

    @After
    public void tearDown() {
        mInterceptMotionEvent = null;
        mMotionEventHelper.recycleEvents();
        mListView = null;
    }

    private void setupBasicMenuView(AccessibilityFloatingMenuView menuView) {
        menuView.show();
        menuView.onTargetsChanged(mTargets);
        menuView.setSizeType(0);
        menuView.setShapeType(0);
    }

    /**
     * Based on the current menu status, fake the ime inset component {@link WindowInsets} used
     * for testing.
     *
     * @param menuView {@link AccessibilityFloatingMenuView} that needs to be changed
     * @param offset is used for the y-axis position of ime higher than the y-axis position of menu
     * @return the ime inset
     */
    private WindowInsets fakeImeInsetWith(AccessibilityFloatingMenuView menuView, int offset) {
        // Ensure the keyboard has overlapped on the menu view.
        final int fakeImeHeight =
                mDisplayWindowHeight - (menuView.mCurrentLayoutParams.y + mMenuWindowHeight)
                        + offset;
        return new WindowInsets.Builder()
                .setVisible(ime(), true)
                .setInsets(ime(), Insets.of(0, 0, 0, fakeImeHeight))
                .build();
    }

    private WindowInsets fakeDisplayInsets() {
        final int fakeStatusBarHeight = 75;
        final int fakeNavigationBarHeight = 125;
        return new WindowInsets.Builder()
                .setVisible(systemBars() | displayCutout(), true)
                .setInsets(systemBars() | displayCutout(),
                        Insets.of(0, fakeStatusBarHeight, 0, fakeNavigationBarHeight))
                .build();
    }

    private class TestAccessibilityFloatingMenu extends AccessibilityFloatingMenuView {
        TestAccessibilityFloatingMenu(Context context, Position position, RecyclerView listView) {
            super(context, position, listView);
        }

        @Override
        public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView,
                @NonNull MotionEvent event) {
            final boolean intercept = super.onInterceptTouchEvent(recyclerView, event);

            if (intercept) {
                mInterceptMotionEvent = event;
            }

            return intercept;
        }
    }
}
