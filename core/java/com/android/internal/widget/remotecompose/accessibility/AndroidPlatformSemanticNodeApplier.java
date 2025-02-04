/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.accessibility;

import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.core.semantics.ScrollableComponent;

import java.util.List;

public class AndroidPlatformSemanticNodeApplier
        extends BaseSemanticNodeApplier<AccessibilityNodeInfo> {

    private static final String ROLE_DESCRIPTION_KEY = "AccessibilityNodeInfo.roleDescription";

    private final View mPlayer;

    public AndroidPlatformSemanticNodeApplier(View player) {
        this.mPlayer = player;
    }

    @Override
    protected void setClickable(AccessibilityNodeInfo nodeInfo, boolean clickable) {
        nodeInfo.setClickable(clickable);
        if (clickable) {
            nodeInfo.addAction(AccessibilityAction.ACTION_CLICK);
        } else {
            nodeInfo.removeAction(AccessibilityAction.ACTION_CLICK);
        }
    }

    @Override
    protected void setEnabled(AccessibilityNodeInfo nodeInfo, boolean enabled) {
        nodeInfo.setEnabled(enabled);
    }

    @Override
    protected CharSequence getStateDescription(AccessibilityNodeInfo nodeInfo) {
        return nodeInfo.getStateDescription();
    }

    @Override
    protected void setStateDescription(AccessibilityNodeInfo nodeInfo, CharSequence description) {
        nodeInfo.setStateDescription(description);
    }

    @Override
    protected void setRoleDescription(AccessibilityNodeInfo nodeInfo, String description) {
        nodeInfo.getExtras().putCharSequence(ROLE_DESCRIPTION_KEY, description);
    }

    @Override
    protected CharSequence getText(AccessibilityNodeInfo nodeInfo) {
        return nodeInfo.getText();
    }

    @Override
    protected void setText(AccessibilityNodeInfo nodeInfo, CharSequence text) {
        nodeInfo.setText(text);
    }

    @Override
    protected CharSequence getContentDescription(AccessibilityNodeInfo nodeInfo) {
        return nodeInfo.getContentDescription();
    }

    @Override
    protected void setContentDescription(AccessibilityNodeInfo nodeInfo, CharSequence description) {
        nodeInfo.setContentDescription(description);
    }

    @Override
    protected void setBoundsInScreen(AccessibilityNodeInfo nodeInfo, Rect bounds) {
        nodeInfo.setBoundsInParent(bounds);
        nodeInfo.setBoundsInScreen(bounds);
    }

    @Override
    protected void setUniqueId(AccessibilityNodeInfo nodeInfo, String id) {
        nodeInfo.setUniqueId(id);
    }

    @Override
    protected void applyScrollable(
            AccessibilityNodeInfo nodeInfo,
            ScrollableComponent.ScrollAxisRange scrollAxis,
            int scrollDirection) {
        nodeInfo.setScrollable(true);
        nodeInfo.addAction(AccessibilityAction.ACTION_SCROLL_TO_POSITION);
        nodeInfo.addAction(AccessibilityAction.ACTION_SET_PROGRESS);

        nodeInfo.setGranularScrollingSupported(true);

        if (scrollAxis.canScrollForward()) {
            nodeInfo.addAction(AccessibilityAction.ACTION_SCROLL_FORWARD);
            if (scrollDirection == RootContentBehavior.SCROLL_VERTICAL) {
                nodeInfo.addAction(AccessibilityAction.ACTION_SCROLL_DOWN);
                nodeInfo.addAction(AccessibilityAction.ACTION_PAGE_DOWN);
            } else if (scrollDirection == RootContentBehavior.SCROLL_HORIZONTAL) {
                // TODO handle RTL
                nodeInfo.addAction(AccessibilityAction.ACTION_SCROLL_RIGHT);
                nodeInfo.addAction(AccessibilityAction.ACTION_PAGE_RIGHT);
            }
        }

        if (scrollAxis.canScrollBackwards()) {
            nodeInfo.addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD);
            if (scrollDirection == RootContentBehavior.SCROLL_VERTICAL) {
                nodeInfo.addAction(AccessibilityAction.ACTION_SCROLL_UP);
                nodeInfo.addAction(AccessibilityAction.ACTION_PAGE_UP);
            } else if (scrollDirection == RootContentBehavior.SCROLL_HORIZONTAL) {
                // TODO handle RTL
                nodeInfo.addAction(AccessibilityAction.ACTION_SCROLL_LEFT);
                nodeInfo.addAction(AccessibilityAction.ACTION_PAGE_LEFT);
            }
        }

        // TODO correct values
        nodeInfo.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(-1, 1, false));

        if (scrollDirection == RootContentBehavior.SCROLL_HORIZONTAL) {
            nodeInfo.setClassName("android.widget.HorizontalScrollView");
        } else {
            nodeInfo.setClassName("android.widget.ScrollView");
        }
    }

    @Override
    protected void applyListItem(AccessibilityNodeInfo nodeInfo, int parentId) {
        nodeInfo.addAction(AccessibilityAction.ACTION_SHOW_ON_SCREEN);
        nodeInfo.setScreenReaderFocusable(true);
        nodeInfo.setFocusable(true);
        nodeInfo.setParent(mPlayer, parentId);

        // TODO correct values
        nodeInfo.setCollectionItemInfo(
                AccessibilityNodeInfo.CollectionItemInfo.obtain(1, 1, 0, 1, false));
    }

    @Override
    public void addChildren(AccessibilityNodeInfo nodeInfo, List<Integer> childIds) {
        for (int id : childIds) {
            nodeInfo.addChild(mPlayer, id);
        }
    }
}
