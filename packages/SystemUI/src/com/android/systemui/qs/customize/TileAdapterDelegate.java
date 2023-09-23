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

import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.android.systemui.res.R;

import java.util.List;

/**
 * Accessibility delegate for {@link TileAdapter} views.
 *
 * This delegate will populate the accessibility info with the proper actions that can be taken for
 * the different tiles:
 * <ul>
 *   <li>Add to end if the tile is not a current tile (by double tap).</li>
 *   <li>Add to a given position (by context menu). This will let the user select a position.</li>
 *   <li>Remove, if the tile is a current tile (by double tap).</li>
 *   <li>Move to a given position (by context menu). This will let the user select a position.</li>
 * </ul>
 *
 * This only handles generating the associated actions. The logic for selecting positions is handled
 * by {@link TileAdapter}.
 *
 * In order for the delegate to work properly, the asociated {@link TileAdapter.Holder} should be
 * passed along with the view using {@link View#setTag}.
 */
class TileAdapterDelegate extends AccessibilityDelegateCompat {

    private static final int MOVE_TO_POSITION_ID = R.id.accessibility_action_qs_move_to_position;
    private static final int ADD_TO_POSITION_ID = R.id.accessibility_action_qs_add_to_position;

    private TileAdapter.Holder getHolder(View view) {
        return (TileAdapter.Holder) view.getTag();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        TileAdapter.Holder holder = getHolder(host);
        info.setCollectionItemInfo(null);
        info.setStateDescription("");
        if (holder == null || !holder.canTakeAccessibleAction()) {
            // If there's not a holder (not a regular Tile) or an action cannot be taken
            // because we are in the middle of an accessibility action, don't create a special node.
            return;
        }

        addClickAction(host, info, holder);
        maybeAddActionAddToPosition(host, info, holder);
        maybeAddActionMoveToPosition(host, info, holder);

        if (holder.isCurrentTile()) {
            info.setStateDescription(host.getContext().getString(
                    R.string.accessibility_qs_edit_position, holder.getLayoutPosition()));
        }
    }

    @Override
    public boolean performAccessibilityAction(View host, int action, Bundle args) {
        TileAdapter.Holder holder = getHolder(host);

        if (holder == null || !holder.canTakeAccessibleAction()) {
            // If there's not a holder (not a regular Tile) or an action cannot be taken
            // because we are in the middle of an accessibility action, perform the default action.
            return super.performAccessibilityAction(host, action, args);
        }
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            holder.toggleState();
            return true;
        } else if (action == MOVE_TO_POSITION_ID) {
            holder.startAccessibleMove();
            return true;
        } else if (action == ADD_TO_POSITION_ID) {
            holder.startAccessibleAdd();
            return true;
        } else {
            return super.performAccessibilityAction(host, action, args);
        }
    }

    private void addClickAction(
            View host, AccessibilityNodeInfoCompat info, TileAdapter.Holder holder) {
        String clickActionString;
        if (holder.canAdd()) {
            clickActionString = host.getContext().getString(
                    R.string.accessibility_qs_edit_tile_add_action);
        } else if (holder.canRemove()) {
            clickActionString = host.getContext().getString(
                    R.string.accessibility_qs_edit_remove_tile_action);
        } else {
            // Remove the default click action if tile can't either be added or removed (for example
            // if there's the minimum number of tiles)
            List<AccessibilityNodeInfoCompat.AccessibilityActionCompat> listOfActions =
                    info.getActionList(); // This is a copy
            int numActions = listOfActions.size();
            for (int i = 0; i < numActions; i++) {
                if (listOfActions.get(i).getId() == AccessibilityNodeInfo.ACTION_CLICK) {
                    info.removeAction(listOfActions.get(i));
                }
            }
            return;
        }

        AccessibilityNodeInfoCompat.AccessibilityActionCompat action =
                new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfo.ACTION_CLICK, clickActionString);
        info.addAction(action);
    }

    private void maybeAddActionMoveToPosition(
            View host, AccessibilityNodeInfoCompat info, TileAdapter.Holder holder) {
        if (holder.isCurrentTile()) {
            AccessibilityNodeInfoCompat.AccessibilityActionCompat action =
                    new AccessibilityNodeInfoCompat.AccessibilityActionCompat(MOVE_TO_POSITION_ID,
                            host.getContext().getString(
                                    R.string.accessibility_qs_edit_tile_start_move));
            info.addAction(action);
        }
    }

    private void maybeAddActionAddToPosition(
            View host, AccessibilityNodeInfoCompat info, TileAdapter.Holder holder) {
        if (holder.canAdd()) {
            AccessibilityNodeInfoCompat.AccessibilityActionCompat action =
                    new AccessibilityNodeInfoCompat.AccessibilityActionCompat(ADD_TO_POSITION_ID,
                            host.getContext().getString(
                                    R.string.accessibility_qs_edit_tile_start_add));
            info.addAction(action);
        }
    }
}
