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

package com.android.internal.accessibility.dialog;

import static com.android.internal.accessibility.util.ShortcutUtils.convertToKey;
import static com.android.internal.accessibility.util.ShortcutUtils.isShortcutContained;

import android.accessibilityservice.AccessibilityShortcutInfo;
import android.annotation.NonNull;
import android.content.Context;

import com.android.internal.accessibility.common.ShortcutConstants;
import com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType;
import com.android.internal.accessibility.common.ShortcutConstants.ShortcutMenuMode;

/**
 * Base class for creating accessibility activity target.
 */
class AccessibilityActivityTarget extends AccessibilityTarget {

    AccessibilityActivityTarget(Context context,
            @ShortcutConstants.UserShortcutType int shortcutType,
            @NonNull AccessibilityShortcutInfo shortcutInfo) {
        super(context,
                shortcutType,
                AccessibilityFragmentType.LAUNCH_ACTIVITY,
                isShortcutContained(context, shortcutType,
                        shortcutInfo.getComponentName().flattenToString()),
                shortcutInfo.getComponentName().flattenToString(),
                shortcutInfo.getActivityInfo().applicationInfo.uid,
                shortcutInfo.getActivityInfo().loadLabel(context.getPackageManager()),
                shortcutInfo.getActivityInfo().loadIcon(context.getPackageManager()),
                convertToKey(shortcutType));
    }

    @Override
    public void updateActionItem(@NonNull TargetAdapter.ViewHolder holder,
            @ShortcutMenuMode int shortcutMenuMode) {
        super.updateActionItem(holder, shortcutMenuMode);

        final boolean isAllowed = AccessibilityTargetHelper.isAccessibilityTargetAllowed(
                getContext(), getComponentName().getPackageName(), getUid());
        final boolean isEditMenuMode =
                shortcutMenuMode == ShortcutMenuMode.EDIT;
        final boolean enabled = isAllowed || (isEditMenuMode && isShortcutEnabled());
        holder.mCheckBoxView.setEnabled(enabled);
        holder.mIconView.setEnabled(enabled);
        holder.mLabelView.setEnabled(enabled);
        holder.mStatusView.setEnabled(enabled);
    }
}
