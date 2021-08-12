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

import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate;

import com.android.systemui.R;
import com.android.systemui.accessibility.floatingmenu.AccessibilityFloatingMenuView.ShapeType;

import java.lang.ref.WeakReference;

/**
 * An accessibility item delegate for the individual items of the list view
 * {@link AccessibilityFloatingMenuView}.
 */
final class ItemDelegateCompat extends RecyclerViewAccessibilityDelegate.ItemDelegate {
    private final WeakReference<AccessibilityFloatingMenuView> mMenuViewRef;

    ItemDelegateCompat(@NonNull RecyclerViewAccessibilityDelegate recyclerViewDelegate,
            AccessibilityFloatingMenuView menuView) {
        super(recyclerViewDelegate);
        this.mMenuViewRef = new WeakReference<>(menuView);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
        super.onInitializeAccessibilityNodeInfo(host, info);

        if (mMenuViewRef.get() == null) {
            return;
        }
        final AccessibilityFloatingMenuView menuView = mMenuViewRef.get();

        final Resources res = menuView.getResources();
        final AccessibilityNodeInfoCompat.AccessibilityActionCompat moveTopLeft =
                new AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.action_move_top_left,
                        res.getString(
                                R.string.accessibility_floating_button_action_move_top_left));
        info.addAction(moveTopLeft);

        final AccessibilityNodeInfoCompat.AccessibilityActionCompat moveTopRight =
                new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.action_move_top_right,
                        res.getString(
                                R.string.accessibility_floating_button_action_move_top_right));
        info.addAction(moveTopRight);

        final AccessibilityNodeInfoCompat.AccessibilityActionCompat moveBottomLeft =
                new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.action_move_bottom_left,
                        res.getString(
                                R.string.accessibility_floating_button_action_move_bottom_left));
        info.addAction(moveBottomLeft);

        final AccessibilityNodeInfoCompat.AccessibilityActionCompat moveBottomRight =
                new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.action_move_bottom_right,
                        res.getString(
                                R.string.accessibility_floating_button_action_move_bottom_right));
        info.addAction(moveBottomRight);

        final int moveEdgeId = menuView.isOvalShape()
                ? R.id.action_move_to_edge_and_hide
                : R.id.action_move_out_edge_and_show;
        final int moveEdgeTextResId = menuView.isOvalShape()
                ? R.string.accessibility_floating_button_action_move_to_edge_and_hide_to_half
                : R.string.accessibility_floating_button_action_move_out_edge_and_show;
        final AccessibilityNodeInfoCompat.AccessibilityActionCompat moveToOrOutEdge =
                new AccessibilityNodeInfoCompat.AccessibilityActionCompat(moveEdgeId,
                        res.getString(moveEdgeTextResId));
        info.addAction(moveToOrOutEdge);
    }

    @Override
    public boolean performAccessibilityAction(View host, int action, Bundle args) {
        if (mMenuViewRef.get() == null) {
            return super.performAccessibilityAction(host, action, args);
        }
        final AccessibilityFloatingMenuView menuView = mMenuViewRef.get();

        menuView.fadeIn();

        final Rect bounds = menuView.getAvailableBounds();
        if (action == R.id.action_move_top_left) {
            menuView.setShapeType(ShapeType.OVAL);
            menuView.snapToLocation(bounds.left, bounds.top);
            return true;
        }

        if (action == R.id.action_move_top_right) {
            menuView.setShapeType(ShapeType.OVAL);
            menuView.snapToLocation(bounds.right, bounds.top);
            return true;
        }

        if (action == R.id.action_move_bottom_left) {
            menuView.setShapeType(ShapeType.OVAL);
            menuView.snapToLocation(bounds.left, bounds.bottom);
            return true;
        }

        if (action == R.id.action_move_bottom_right) {
            menuView.setShapeType(ShapeType.OVAL);
            menuView.snapToLocation(bounds.right, bounds.bottom);
            return true;
        }

        if (action == R.id.action_move_to_edge_and_hide) {
            menuView.setShapeType(ShapeType.HALF_OVAL);
            return true;
        }

        if (action == R.id.action_move_out_edge_and_show) {
            menuView.setShapeType(ShapeType.OVAL);
            return true;
        }

        return super.performAccessibilityAction(host, action, args);
    }
}
