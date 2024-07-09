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

import static androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS;
import static androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.graphics.Rect;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.utils.TestUtils;
import com.android.systemui.res.R;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.atomic.AtomicBoolean;

/** Tests for {@link MenuItemAccessibilityDelegate}. */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4.class)
public class MenuItemAccessibilityDelegateTest extends SysuiTestCase {
    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private AccessibilityManager mAccessibilityManager;
    private final SecureSettings mSecureSettings = TestUtils.mockSecureSettings();
    private RecyclerView mStubListView;
    private MenuView mMenuView;
    private MenuViewLayer mMenuViewLayer;
    private MenuItemAccessibilityDelegate mMenuItemAccessibilityDelegate;
    private MenuAnimationController mMenuAnimationController;
    private final Rect mDraggableBounds = new Rect(100, 200, 300, 400);

    private final AtomicBoolean mEditReceived = new AtomicBoolean(false);

    @Before
    public void setUp() {
        final WindowManager stubWindowManager = mContext.getSystemService(WindowManager.class);
        final MenuViewAppearance stubMenuViewAppearance = new MenuViewAppearance(mContext,
                stubWindowManager);
        final MenuViewModel stubMenuViewModel = new MenuViewModel(mContext, mAccessibilityManager,
                mSecureSettings);

        final int halfScreenHeight =
                stubWindowManager.getCurrentWindowMetrics().getBounds().height() / 2;
        mMenuView = spy(new MenuView(mContext, stubMenuViewModel, stubMenuViewAppearance,
                mSecureSettings));
        mMenuView.setTranslationY(halfScreenHeight);

        mMenuViewLayer = spy(new MenuViewLayer(
                mContext, stubWindowManager, mAccessibilityManager,
                stubMenuViewModel, stubMenuViewAppearance, mMenuView,
                mock(IAccessibilityFloatingMenu.class), mSecureSettings));
        doNothing().when(mMenuViewLayer).gotoEditScreen();

        doReturn(mDraggableBounds).when(mMenuView).getMenuDraggableBounds();
        mStubListView = new RecyclerView(mContext);
        mMenuAnimationController = spy(new MenuAnimationController(mMenuView,
                stubMenuViewAppearance));
        mMenuItemAccessibilityDelegate =
                new MenuItemAccessibilityDelegate(new RecyclerViewAccessibilityDelegate(
                        mStubListView), mMenuAnimationController, mMenuViewLayer);
        mEditReceived.set(false);
    }

    @Test
    @DisableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_EDIT)
    public void getAccessibilityActionList_matchSize_withoutEdit() {
        final AccessibilityNodeInfoCompat info =
                new AccessibilityNodeInfoCompat(new AccessibilityNodeInfo());

        mMenuItemAccessibilityDelegate.onInitializeAccessibilityNodeInfo(mStubListView, info);

        assertThat(info.getActionList().size()).isEqualTo(6);
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_EDIT)
    public void getAccessibilityActionList_matchSize() {
        final AccessibilityNodeInfoCompat info =
                new AccessibilityNodeInfoCompat(new AccessibilityNodeInfo());

        mMenuItemAccessibilityDelegate.onInitializeAccessibilityNodeInfo(mStubListView, info);

        assertThat(info.getActionList().size()).isEqualTo(7);
    }

    @Test
    public void performMoveTopLeftAction_matchPosition() {
        final boolean moveTopLeftAction =
                mMenuItemAccessibilityDelegate.performAccessibilityAction(mStubListView,
                        R.id.action_move_top_left,
                        null);

        assertThat(moveTopLeftAction).isTrue();
        assertThat(mMenuView.getTranslationX()).isEqualTo(mDraggableBounds.left);
        assertThat(mMenuView.getTranslationY()).isEqualTo(mDraggableBounds.top);
    }

    @Test
    public void performMoveTopRightAction_matchPosition() {
        final boolean moveTopRightAction =
                mMenuItemAccessibilityDelegate.performAccessibilityAction(mStubListView,
                        R.id.action_move_top_right, null);

        assertThat(moveTopRightAction).isTrue();
        assertThat(mMenuView.getTranslationX()).isEqualTo(mDraggableBounds.right);
        assertThat(mMenuView.getTranslationY()).isEqualTo(mDraggableBounds.top);
    }

    @Test
    public void performMoveBottomLeftAction_matchPosition() {
        final boolean moveBottomLeftAction =
                mMenuItemAccessibilityDelegate.performAccessibilityAction(mStubListView,
                        R.id.action_move_bottom_left, null);

        assertThat(moveBottomLeftAction).isTrue();
        assertThat(mMenuView.getTranslationX()).isEqualTo(mDraggableBounds.left);
        assertThat(mMenuView.getTranslationY()).isEqualTo(mDraggableBounds.bottom);
    }

    @Test
    public void performMoveBottomRightAction_matchPosition() {
        final boolean moveBottomRightAction =
                mMenuItemAccessibilityDelegate.performAccessibilityAction(mStubListView,
                        R.id.action_move_bottom_right, null);

        assertThat(moveBottomRightAction).isTrue();
        assertThat(mMenuView.getTranslationX()).isEqualTo(mDraggableBounds.right);
        assertThat(mMenuView.getTranslationY()).isEqualTo(mDraggableBounds.bottom);
    }

    @Test
    public void performMoveToEdgeAndHideAction_success() {
        final boolean moveToEdgeAndHideAction =
                mMenuItemAccessibilityDelegate.performAccessibilityAction(mStubListView,
                        R.id.action_move_to_edge_and_hide, null);

        assertThat(moveToEdgeAndHideAction).isTrue();
        verify(mMenuAnimationController).moveToEdgeAndHide();
    }

    @Test
    public void performMoveOutFromEdgeAction_success() {
        final boolean moveOutEdgeAndShowAction =
                mMenuItemAccessibilityDelegate.performAccessibilityAction(mStubListView,
                        R.id.action_move_out_edge_and_show, null);

        assertThat(moveOutEdgeAndShowAction).isTrue();
        verify(mMenuAnimationController).moveOutEdgeAndShow();
    }

    @Test
    public void performRemoveMenuAction_success() {
        final boolean removeMenuAction =
                mMenuItemAccessibilityDelegate.performAccessibilityAction(mStubListView,
                        R.id.action_remove_menu, null);

        assertThat(removeMenuAction).isTrue();
        verify(mMenuViewLayer).dispatchAccessibilityAction(R.id.action_remove_menu);
    }

    @Test
    public void performEditAction_success() {
        final boolean editAction =
                mMenuItemAccessibilityDelegate.performAccessibilityAction(mStubListView,
                        R.id.action_edit, null);

        assertThat(editAction).isTrue();
        verify(mMenuViewLayer).dispatchAccessibilityAction(R.id.action_edit);
    }

    @Test
    public void performFocusAction_fadeIn() {
        mMenuItemAccessibilityDelegate.performAccessibilityAction(mStubListView,
                ACTION_ACCESSIBILITY_FOCUS, null);

        verify(mMenuAnimationController).fadeInNowIfEnabled();
    }

    @Test
    public void performClearFocusAction_fadeOut() {
        mMenuItemAccessibilityDelegate.performAccessibilityAction(mStubListView,
                ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);

        verify(mMenuAnimationController).fadeOutIfEnabled();
    }
}
