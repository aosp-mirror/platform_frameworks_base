/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.customize;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TileAdapterDelegateTest extends SysuiTestCase {

    private static final int MOVE_TO_POSITION_ID = R.id.accessibility_action_qs_move_to_position;
    private static final int ADD_TO_POSITION_ID = R.id.accessibility_action_qs_add_to_position;
    private static final int POSITION_STRING_ID = R.string.accessibility_qs_edit_position;

    @Mock
    private TileAdapter.Holder mHolder;

    private AccessibilityNodeInfoCompat mInfo;
    private TileAdapterDelegate mDelegate;
    private View mView;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mView = new View(mContext);
        mDelegate = new TileAdapterDelegate();
        mInfo = AccessibilityNodeInfoCompat.obtain();
    }

    @Test
    public void testInfoNoSpecialActionsWhenNoHolder() {
        mDelegate.onInitializeAccessibilityNodeInfo(mView, mInfo);
        for (AccessibilityNodeInfoCompat.AccessibilityActionCompat action : mInfo.getActionList()) {
            if (action.getId() == MOVE_TO_POSITION_ID || action.getId() == ADD_TO_POSITION_ID
                    || action.getId() == AccessibilityNodeInfo.ACTION_CLICK) {
                fail("It should not have special action " + action.getId());
            }
        }
    }

    @Test
    public void testInfoNoSpecialActionsWhenCannotStartAccessibleAction() {
        mView.setTag(mHolder);
        when(mHolder.canTakeAccessibleAction()).thenReturn(false);
        mDelegate.onInitializeAccessibilityNodeInfo(mView, mInfo);
        for (AccessibilityNodeInfoCompat.AccessibilityActionCompat action : mInfo.getActionList()) {
            if (action.getId() == MOVE_TO_POSITION_ID || action.getId() == ADD_TO_POSITION_ID
                    || action.getId() == AccessibilityNodeInfo.ACTION_CLICK) {
                fail("It should not have special action " + action.getId());
            }
        }
    }

    @Test
    public void testNoCollectionItemInfo() {
        mInfo.setCollectionItemInfo(
                AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(0, 1, 0, 1, false));

        mDelegate.onInitializeAccessibilityNodeInfo(mView, mInfo);
        assertThat(mInfo.getCollectionItemInfo()).isNull();
    }

    @Test
    public void testStateDescriptionHasPositionForCurrentTile() {
        mView.setTag(mHolder);
        int position = 3;
        when(mHolder.getLayoutPosition()).thenReturn(position);
        when(mHolder.isCurrentTile()).thenReturn(true);
        when(mHolder.canTakeAccessibleAction()).thenReturn(true);

        String expectedString = mContext.getString(POSITION_STRING_ID, position);

        mDelegate.onInitializeAccessibilityNodeInfo(mView, mInfo);
        assertThat(mInfo.getStateDescription()).isEqualTo(expectedString);
    }

    @Test
    public void testStateDescriptionEmptyForNotCurrentTile() {
        mView.setTag(mHolder);
        int position = 3;
        when(mHolder.getLayoutPosition()).thenReturn(position);
        when(mHolder.isCurrentTile()).thenReturn(false);
        when(mHolder.canTakeAccessibleAction()).thenReturn(true);

        mDelegate.onInitializeAccessibilityNodeInfo(mView, mInfo);
        assertThat(mInfo.getStateDescription()).isEqualTo("");
    }

    @Test
    public void testClickAddAction() {
        mView.setTag(mHolder);
        when(mHolder.canTakeAccessibleAction()).thenReturn(true);
        when(mHolder.canAdd()).thenReturn(true);
        when(mHolder.canRemove()).thenReturn(false);

        mDelegate.onInitializeAccessibilityNodeInfo(mView, mInfo);

        String expectedString = mContext.getString(R.string.accessibility_qs_edit_tile_add_action);
        AccessibilityNodeInfoCompat.AccessibilityActionCompat action =
                getActionForId(mInfo, AccessibilityNodeInfo.ACTION_CLICK);
        assertThat(action.getLabel().toString()).contains(expectedString);
        assertThat(mInfo.isClickable()).isTrue();
    }

    @Test
    public void testClickRemoveAction() {
        mView.setTag(mHolder);
        when(mHolder.canTakeAccessibleAction()).thenReturn(true);
        when(mHolder.canAdd()).thenReturn(false);
        when(mHolder.canRemove()).thenReturn(true);

        mDelegate.onInitializeAccessibilityNodeInfo(mView, mInfo);

        String expectedString = mContext.getString(
                R.string.accessibility_qs_edit_remove_tile_action);
        AccessibilityNodeInfoCompat.AccessibilityActionCompat action =
                getActionForId(mInfo, AccessibilityNodeInfo.ACTION_CLICK);
        assertThat(action.getLabel().toString()).contains(expectedString);
        assertThat(mInfo.isClickable()).isTrue();
    }

    @Test
    public void testNoClickActionAndNotClickable() {
        mView.setTag(mHolder);
        when(mHolder.canTakeAccessibleAction()).thenReturn(true);
        when(mHolder.canAdd()).thenReturn(false);
        when(mHolder.canRemove()).thenReturn(false);
        mInfo.addAction(AccessibilityNodeInfo.ACTION_CLICK);

        mDelegate.onInitializeAccessibilityNodeInfo(mView, mInfo);

        AccessibilityNodeInfoCompat.AccessibilityActionCompat action =
                getActionForId(mInfo, AccessibilityNodeInfo.ACTION_CLICK);
        assertThat(action).isNull();
        assertThat(mInfo.isClickable()).isFalse();
    }

    @Test
    public void testAddToPositionAction() {
        mView.setTag(mHolder);
        when(mHolder.canTakeAccessibleAction()).thenReturn(true);
        when(mHolder.canAdd()).thenReturn(true);

        mDelegate.onInitializeAccessibilityNodeInfo(mView, mInfo);
        assertThat(getActionForId(mInfo, ADD_TO_POSITION_ID)).isNotNull();
    }

    @Test
    public void testNoAddToPositionAction() {
        mView.setTag(mHolder);
        when(mHolder.canTakeAccessibleAction()).thenReturn(true);
        when(mHolder.canAdd()).thenReturn(false);

        mDelegate.onInitializeAccessibilityNodeInfo(mView, mInfo);
        assertThat(getActionForId(mInfo, ADD_TO_POSITION_ID)).isNull();
    }

    @Test
    public void testMoveToPositionAction() {
        mView.setTag(mHolder);
        when(mHolder.canTakeAccessibleAction()).thenReturn(true);
        when(mHolder.isCurrentTile()).thenReturn(true);

        mDelegate.onInitializeAccessibilityNodeInfo(mView, mInfo);
        assertThat(getActionForId(mInfo, MOVE_TO_POSITION_ID)).isNotNull();
    }

    @Test
    public void testNoMoveToPositionAction() {
        mView.setTag(mHolder);
        when(mHolder.canTakeAccessibleAction()).thenReturn(true);
        when(mHolder.isCurrentTile()).thenReturn(false);

        mDelegate.onInitializeAccessibilityNodeInfo(mView, mInfo);
        assertThat(getActionForId(mInfo, MOVE_TO_POSITION_ID)).isNull();
    }

    @Test
    public void testNoInteractionsWhenCannotTakeAccessibleAction() {
        mView.setTag(mHolder);
        when(mHolder.canTakeAccessibleAction()).thenReturn(false);

        mDelegate.performAccessibilityAction(mView, AccessibilityNodeInfo.ACTION_CLICK, null);
        mDelegate.performAccessibilityAction(mView, MOVE_TO_POSITION_ID, new Bundle());
        mDelegate.performAccessibilityAction(mView, ADD_TO_POSITION_ID, new Bundle());

        verify(mHolder, never()).toggleState();
        verify(mHolder, never()).startAccessibleAdd();
        verify(mHolder, never()).startAccessibleMove();
    }

    @Test
    public void testClickActionTogglesState() {
        mView.setTag(mHolder);
        when(mHolder.canTakeAccessibleAction()).thenReturn(true);

        mDelegate.performAccessibilityAction(mView, AccessibilityNodeInfo.ACTION_CLICK, null);

        verify(mHolder).toggleState();
    }

    @Test
    public void testAddToPositionActionStartsAccessibleAdd() {
        mView.setTag(mHolder);
        when(mHolder.canTakeAccessibleAction()).thenReturn(true);

        mDelegate.performAccessibilityAction(mView, ADD_TO_POSITION_ID, null);

        verify(mHolder).startAccessibleAdd();
    }

    @Test
    public void testMoveToPositionActionStartsAccessibleMove() {
        mView.setTag(mHolder);
        when(mHolder.canTakeAccessibleAction()).thenReturn(true);

        mDelegate.performAccessibilityAction(mView, MOVE_TO_POSITION_ID, null);

        verify(mHolder).startAccessibleMove();
    }

    private AccessibilityNodeInfoCompat.AccessibilityActionCompat getActionForId(
            AccessibilityNodeInfoCompat info, int action) {
        for (AccessibilityNodeInfoCompat.AccessibilityActionCompat a : info.getActionList()) {
            if (a.getId() == action) {
                return a;
            }
        }
        return null;
    }
}
