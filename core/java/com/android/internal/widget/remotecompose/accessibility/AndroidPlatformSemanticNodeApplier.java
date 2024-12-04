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
import android.view.accessibility.AccessibilityNodeInfo;

public class AndroidPlatformSemanticNodeApplier
        extends BaseSemanticNodeApplier<AccessibilityNodeInfo> {

    private static final String ROLE_DESCRIPTION_KEY = "AccessibilityNodeInfo.roleDescription";

    @Override
    protected void setClickable(AccessibilityNodeInfo nodeInfo, boolean clickable) {
        nodeInfo.setClickable(clickable);
        if (clickable) {
            nodeInfo.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        } else {
            nodeInfo.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
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
}
