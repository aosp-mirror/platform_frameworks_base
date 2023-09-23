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

import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate;

import com.android.systemui.res.R;

/**
 * An accessibility item delegate for the individual items of the list view in the
 * {@link MenuView}.
 */
class MenuItemAccessibilityDelegate extends RecyclerViewAccessibilityDelegate.ItemDelegate {
    private final MenuAnimationController mAnimationController;

    MenuItemAccessibilityDelegate(@NonNull RecyclerViewAccessibilityDelegate recyclerViewDelegate,
            MenuAnimationController animationController) {
        super(recyclerViewDelegate);
        mAnimationController = animationController;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
        super.onInitializeAccessibilityNodeInfo(host, info);

        final Resources res = host.getResources();
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

        final int moveEdgeId = mAnimationController.isMoveToTucked()
                ? R.id.action_move_out_edge_and_show
                : R.id.action_move_to_edge_and_hide;
        final int moveEdgeTextResId = mAnimationController.isMoveToTucked()
                ? R.string.accessibility_floating_button_action_move_out_edge_and_show
                : R.string.accessibility_floating_button_action_move_to_edge_and_hide_to_half;
        final AccessibilityNodeInfoCompat.AccessibilityActionCompat moveToOrOutEdge =
                new AccessibilityNodeInfoCompat.AccessibilityActionCompat(moveEdgeId,
                        res.getString(moveEdgeTextResId));
        info.addAction(moveToOrOutEdge);

        final AccessibilityNodeInfoCompat.AccessibilityActionCompat removeMenu =
                new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.action_remove_menu,
                        res.getString(R.string.accessibility_floating_button_action_remove_menu));
        info.addAction(removeMenu);
    }

    @Override
    public boolean performAccessibilityAction(View host, int action, Bundle args) {
        if (action == ACTION_ACCESSIBILITY_FOCUS) {
            mAnimationController.fadeInNowIfEnabled();
        }

        if (action == ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
            mAnimationController.fadeOutIfEnabled();
        }

        if (action == R.id.action_move_top_left) {
            mAnimationController.moveToTopLeftPosition();
            return true;
        }

        if (action == R.id.action_move_top_right) {
            mAnimationController.moveToTopRightPosition();
            return true;
        }

        if (action == R.id.action_move_bottom_left) {
            mAnimationController.moveToBottomLeftPosition();
            return true;
        }

        if (action == R.id.action_move_bottom_right) {
            mAnimationController.moveToBottomRightPosition();
            return true;
        }

        if (action == R.id.action_move_to_edge_and_hide) {
            mAnimationController.moveToEdgeAndHide();
            return true;
        }

        if (action == R.id.action_move_out_edge_and_show) {
            mAnimationController.moveOutEdgeAndShow();
            return true;
        }

        if (action == R.id.action_remove_menu) {
            mAnimationController.removeMenu();
            return true;
        }

        return super.performAccessibilityAction(host, action, args);
    }
}
