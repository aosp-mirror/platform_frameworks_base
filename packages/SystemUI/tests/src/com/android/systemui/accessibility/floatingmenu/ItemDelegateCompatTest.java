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

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate;
import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link ItemDelegateCompat}. */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ItemDelegateCompatTest extends SysuiTestCase {
    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private WindowManager mWindowManager;

    private RecyclerView mListView;
    private AccessibilityFloatingMenuView mMenuView;
    private ItemDelegateCompat mItemDelegateCompat;
    private final Rect mAvailableBounds = new Rect(100, 200, 300, 400);
    private final Position mPlaceholderPosition = new Position(0.0f, 0.0f);

    @Before
    public void setUp() {
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        doAnswer(invocation -> wm.getMaximumWindowMetrics()).when(
                mWindowManager).getMaximumWindowMetrics();
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);

        mListView = new RecyclerView(mContext);
        mMenuView =
                spy(new AccessibilityFloatingMenuView(mContext, mPlaceholderPosition, mListView));
        mItemDelegateCompat =
                new ItemDelegateCompat(new RecyclerViewAccessibilityDelegate(mListView), mMenuView);
    }

    @Test
    public void getAccessibilityActionList_matchResult() {
        final AccessibilityNodeInfoCompat info =
                new AccessibilityNodeInfoCompat(new AccessibilityNodeInfo());

        mItemDelegateCompat.onInitializeAccessibilityNodeInfo(mListView, info);

        assertThat(info.getActionList().size()).isEqualTo(5);
    }

    @Test
    public void performAccessibilityMoveTopLeftAction_halfOval_success() {
        doReturn(mAvailableBounds).when(mMenuView).getAvailableBounds();
        mMenuView.setShapeType(/* halfOvalShape */ 1);

        final boolean moveTopLeftAction =
                mItemDelegateCompat.performAccessibilityAction(mListView, R.id.action_move_top_left,
                        null);

        assertThat(moveTopLeftAction).isTrue();
        assertThat(mMenuView.mShapeType).isEqualTo(/* ovalShape */ 0);
        verify(mMenuView).snapToLocation(mAvailableBounds.left, mAvailableBounds.top);
    }

    @Test
    public void performAccessibilityMoveTopRightAction_halfOval_success() {
        doReturn(mAvailableBounds).when(mMenuView).getAvailableBounds();
        mMenuView.setShapeType(/* halfOvalShape */ 1);

        final boolean moveTopRightAction =
                mItemDelegateCompat.performAccessibilityAction(mListView,
                        R.id.action_move_top_right, null);

        assertThat(moveTopRightAction).isTrue();
        assertThat(mMenuView.mShapeType).isEqualTo(/* ovalShape */ 0);
        verify(mMenuView).snapToLocation(mAvailableBounds.right, mAvailableBounds.top);
    }

    @Test
    public void performAccessibilityMoveBottomLeftAction_halfOval_success() {
        doReturn(mAvailableBounds).when(mMenuView).getAvailableBounds();
        mMenuView.setShapeType(/* halfOvalShape */ 1);

        final boolean moveBottomLeftAction =
                mItemDelegateCompat.performAccessibilityAction(mListView,
                        R.id.action_move_bottom_left, null);

        assertThat(moveBottomLeftAction).isTrue();
        assertThat(mMenuView.mShapeType).isEqualTo(/* ovalShape */ 0);
        verify(mMenuView).snapToLocation(mAvailableBounds.left, mAvailableBounds.bottom);
    }

    @Test
    public void performAccessibilityMoveBottomRightAction_halfOval_success() {
        doReturn(mAvailableBounds).when(mMenuView).getAvailableBounds();
        mMenuView.setShapeType(/* halfOvalShape */ 1);

        final boolean moveBottomRightAction =
                mItemDelegateCompat.performAccessibilityAction(mListView,
                        R.id.action_move_bottom_right, null);

        assertThat(moveBottomRightAction).isTrue();
        assertThat(mMenuView.mShapeType).isEqualTo(/* ovalShape */ 0);
        verify(mMenuView).snapToLocation(mAvailableBounds.right, mAvailableBounds.bottom);
    }

    @Test
    public void performAccessibilityMoveOutEdgeAction_halfOval_success() {
        doReturn(mAvailableBounds).when(mMenuView).getAvailableBounds();
        mMenuView.setShapeType(/* halfOvalShape */ 1);

        final boolean moveOutEdgeAndShowAction =
                mItemDelegateCompat.performAccessibilityAction(mListView,
                        R.id.action_move_out_edge_and_show, null);

        assertThat(moveOutEdgeAndShowAction).isTrue();
        assertThat(mMenuView.mShapeType).isEqualTo(/* ovalShape */ 0);
    }

    @Test
    public void setupAccessibilityActions_oval_hasActionMoveToEdgeAndHide() {
        final AccessibilityNodeInfoCompat info =
                new AccessibilityNodeInfoCompat(new AccessibilityNodeInfo());
        mMenuView.setShapeType(/* ovalShape */ 0);

        mItemDelegateCompat.onInitializeAccessibilityNodeInfo(mListView, info);

        assertThat(info.getActionList().stream().anyMatch(
                action -> action.getId() == R.id.action_move_to_edge_and_hide)).isTrue();
    }
}
