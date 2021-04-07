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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.filters.SmallTest;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.MotionEventHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
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

    private MotionEvent mInterceptMotionEvent;

    private RecyclerView mListView;

    private Rect mAvailableBounds = new Rect(100, 200, 300, 400);

    private int mMenuHalfWidth;
    private int mMenuHalfHeight;
    private int mScreenHalfWidth;
    private int mScreenHalfHeight;
    private int mMaxWindowX;

    private final MotionEventHelper mMotionEventHelper = new MotionEventHelper();
    private final List<AccessibilityTarget> mTargets = new ArrayList<>();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        doAnswer(invocation -> wm.getMaximumWindowMetrics()).when(
                mWindowManager).getMaximumWindowMetrics();
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);

        mTargets.add(mock(AccessibilityTarget.class));
        mListView = new RecyclerView(mContext);
        mMenuView = new AccessibilityFloatingMenuView(mContext, mListView);

        final Resources res = mContext.getResources();
        final int margin =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_margin);
        final int padding =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_padding);
        final int iconWidthHeight =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_small_width_height);
        final int menuWidth = padding * 2 + iconWidthHeight;
        final int menuHeight = (padding + iconWidthHeight) * mTargets.size() + padding;
        final int screenWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        final int screenHeight = mContext.getResources().getDisplayMetrics().heightPixels;
        mMenuHalfWidth = menuWidth / 2;
        mMenuHalfHeight = menuHeight / 2;
        mScreenHalfWidth = screenWidth / 2;
        mScreenHalfHeight = screenHeight / 2;
        mMaxWindowX = screenWidth - margin - menuWidth;
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
        final RecyclerView listView = spy(new RecyclerView(mContext));
        final AccessibilityFloatingMenuView menuView =
                new AccessibilityFloatingMenuView(mContext, listView);
        final int shapeType = 2;
        doReturn(mAnimator).when(listView).animate();

        menuView.setShapeType(shapeType);

        verify(mAnimator).translationX(anyFloat());
    }

    @Test
    public void onTargetsChanged_fadeInOut() {
        final AccessibilityFloatingMenuView menuView = spy(mMenuView);
        final InOrder inOrderMenuView = inOrder(menuView);

        menuView.onTargetsChanged(mTargets);

        inOrderMenuView.verify(menuView).fadeIn();
        inOrderMenuView.verify(menuView).fadeOut();
    }

    @Test
    public void setSizeType_fadeInOut() {
        final AccessibilityFloatingMenuView menuView = spy(mMenuView);
        final InOrder inOrderMenuView = inOrder(menuView);
        final int smallSize = 0;
        menuView.setSizeType(smallSize);

        inOrderMenuView.verify(menuView).fadeIn();
        inOrderMenuView.verify(menuView).fadeOut();
    }

    @Test
    public void tapOnAndDragMenu_interceptUpEvent() {
        final RecyclerView listView = new RecyclerView(mContext);
        final TestAccessibilityFloatingMenu menuView =
                new TestAccessibilityFloatingMenu(mContext, listView);

        menuView.show();
        menuView.onTargetsChanged(mTargets);
        menuView.setSizeType(0);
        menuView.setShapeType(0);
        final int currentWindowX = mMenuView.mCurrentLayoutParams.x;
        final int currentWindowY = mMenuView.mCurrentLayoutParams.y;
        final MotionEvent downEvent =
                mMotionEventHelper.obtainMotionEvent(0, 1,
                        MotionEvent.ACTION_DOWN,
                        currentWindowX + /* offsetXToMenuCenterX */ mMenuHalfWidth,
                        currentWindowY + /* offsetYToMenuCenterY */ mMenuHalfHeight);
        final MotionEvent moveEvent =
                mMotionEventHelper.obtainMotionEvent(2, 3,
                        MotionEvent.ACTION_MOVE,
                        /* screenCenterX */mScreenHalfWidth
                                - /* offsetXToScreenLeftHalfRegion */ 10,
                        /* screenCenterY */ mScreenHalfHeight);
        final MotionEvent upEvent =
                mMotionEventHelper.obtainMotionEvent(4, 5,
                        MotionEvent.ACTION_UP,
                        /* screenCenterX */ mScreenHalfWidth
                                - /* offsetXToScreenLeftHalfRegion */ 10,
                        /* screenCenterY */ mScreenHalfHeight);
        listView.dispatchTouchEvent(downEvent);
        listView.dispatchTouchEvent(moveEvent);
        listView.dispatchTouchEvent(upEvent);

        assertThat(mInterceptMotionEvent.getAction()).isEqualTo(MotionEvent.ACTION_UP);
    }

    @Test
    public void tapOnAndDragMenu_matchLocation() {
        mMenuView.show();
        mMenuView.onTargetsChanged(mTargets);
        mMenuView.setSizeType(0);
        mMenuView.setShapeType(0);
        final int currentWindowX = mMenuView.mCurrentLayoutParams.x;
        final int currentWindowY = mMenuView.mCurrentLayoutParams.y;
        final MotionEvent downEvent =
                mMotionEventHelper.obtainMotionEvent(0, 1,
                        MotionEvent.ACTION_DOWN,
                        currentWindowX + /* offsetXToMenuCenterX */ mMenuHalfWidth,
                        currentWindowY + /* offsetYToMenuCenterY */ mMenuHalfHeight);
        final MotionEvent moveEvent =
                mMotionEventHelper.obtainMotionEvent(2, 3,
                        MotionEvent.ACTION_MOVE,
                        /* screenCenterX */mScreenHalfWidth
                                + /* offsetXToScreenRightHalfRegion */ 10,
                        /* screenCenterY */ mScreenHalfHeight);
        final MotionEvent upEvent =
                mMotionEventHelper.obtainMotionEvent(4, 5,
                        MotionEvent.ACTION_UP,
                        /* screenCenterX */ mScreenHalfWidth
                                + /* offsetXToScreenRightHalfRegion */ 10,
                        /* screenCenterY */ mScreenHalfHeight);
        mListView.dispatchTouchEvent(downEvent);
        mListView.dispatchTouchEvent(moveEvent);
        mListView.dispatchTouchEvent(upEvent);
        mMenuView.mDragAnimator.end();

        assertThat(mMenuView.mCurrentLayoutParams.x).isEqualTo(mMaxWindowX);
        assertThat(mMenuView.mCurrentLayoutParams.y).isEqualTo(
                /* newWindowY = screenCenterY - offsetY */ mScreenHalfHeight - mMenuHalfHeight);
    }


    @Test
    public void tapOnAndDragMenuToScreenSide_transformShapeHalfOval() {
        mMenuView.show();
        mMenuView.onTargetsChanged(mTargets);
        mMenuView.setSizeType(0);
        mMenuView.setShapeType(/* oval */ 0);
        final int currentWindowX = mMenuView.mCurrentLayoutParams.x;
        final int currentWindowY = mMenuView.mCurrentLayoutParams.y;
        final MotionEvent downEvent =
                mMotionEventHelper.obtainMotionEvent(0, 1,
                        MotionEvent.ACTION_DOWN,
                        currentWindowX + /* offsetXToMenuCenterX */ mMenuHalfWidth,
                        currentWindowY + /* offsetYToMenuCenterY */ mMenuHalfHeight);
        final MotionEvent moveEvent =
                mMotionEventHelper.obtainMotionEvent(2, 3,
                        MotionEvent.ACTION_MOVE,
                        /* downX */(currentWindowX + mMenuHalfWidth)
                                + /* offsetXToScreenRightSide */ mMenuHalfWidth,
                        /* downY */ (currentWindowY +  mMenuHalfHeight));
        final MotionEvent upEvent =
                mMotionEventHelper.obtainMotionEvent(4, 5,
                        MotionEvent.ACTION_UP,
                        /* downX */(currentWindowX + mMenuHalfWidth)
                                + /* offsetXToScreenRightSide */ mMenuHalfWidth,
                        /* downY */ (currentWindowY +  mMenuHalfHeight));
        mListView.dispatchTouchEvent(downEvent);
        mListView.dispatchTouchEvent(moveEvent);
        mListView.dispatchTouchEvent(upEvent);

        assertThat(mMenuView.mShapeType).isEqualTo(/* halfOval */ 1);
    }

    @Test
    public void getAccessibilityActionList_matchResult() {
        final AccessibilityNodeInfo infos = new AccessibilityNodeInfo();
        mMenuView.onInitializeAccessibilityNodeInfo(infos);

        assertThat(infos.getActionList().size()).isEqualTo(4);
    }

    @Test
    public void accessibilityActionMove_moveTopLeft_success() {
        final AccessibilityFloatingMenuView menuView =
                spy(new AccessibilityFloatingMenuView(mContext));
        doReturn(mAvailableBounds).when(menuView).getAvailableBounds();

        final boolean isActionPerformed =
                menuView.performAccessibilityAction(R.id.action_move_top_left, null);

        assertThat(isActionPerformed).isTrue();
        verify(menuView).snapToLocation(mAvailableBounds.left, mAvailableBounds.top);
    }

    @Test
    public void accessibilityActionMove_moveTopRight_success() {
        final AccessibilityFloatingMenuView menuView =
                spy(new AccessibilityFloatingMenuView(mContext));
        doReturn(mAvailableBounds).when(menuView).getAvailableBounds();

        final boolean isActionPerformed =
                menuView.performAccessibilityAction(R.id.action_move_top_right, null);

        assertThat(isActionPerformed).isTrue();
        verify(menuView).snapToLocation(mAvailableBounds.right, mAvailableBounds.top);
    }

    @Test
    public void accessibilityActionMove_moveBottomLeft_success() {
        final AccessibilityFloatingMenuView menuView =
                spy(new AccessibilityFloatingMenuView(mContext));
        doReturn(mAvailableBounds).when(menuView).getAvailableBounds();

        final boolean isActionPerformed =
                menuView.performAccessibilityAction(R.id.action_move_bottom_left, null);

        assertThat(isActionPerformed).isTrue();
        verify(menuView).snapToLocation(mAvailableBounds.left, mAvailableBounds.bottom);
    }

    @Test
    public void accessibilityActionMove_moveBottomRight_success() {
        final AccessibilityFloatingMenuView menuView =
                spy(new AccessibilityFloatingMenuView(mContext));
        doReturn(mAvailableBounds).when(menuView).getAvailableBounds();

        final boolean isActionPerformed =
                menuView.performAccessibilityAction(R.id.action_move_bottom_right, null);

        assertThat(isActionPerformed).isTrue();
        verify(menuView).snapToLocation(mAvailableBounds.right, mAvailableBounds.bottom);
    }

    @After
    public void tearDown() {
        mInterceptMotionEvent = null;
        mMotionEventHelper.recycleEvents();
    }

    private class TestAccessibilityFloatingMenu extends AccessibilityFloatingMenuView {
        TestAccessibilityFloatingMenu(Context context, RecyclerView listView) {
            super(context, listView);
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
