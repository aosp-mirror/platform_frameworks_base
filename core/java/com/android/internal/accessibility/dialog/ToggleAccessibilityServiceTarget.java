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

import static com.android.internal.accessibility.util.AccessibilityUtils.isAccessibilityServiceEnabled;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityManager.ShortcutType;

import com.android.internal.R;
import com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType;
import com.android.internal.accessibility.common.ShortcutConstants.ShortcutMenuMode;
import com.android.internal.accessibility.dialog.TargetAdapter.ViewHolder;

/**
 * Extension for {@link AccessibilityServiceTarget} with {@link AccessibilityFragmentType#TOGGLE}
 * type.
 */
class ToggleAccessibilityServiceTarget extends AccessibilityServiceTarget {

    ToggleAccessibilityServiceTarget(Context context, @ShortcutType int shortcutType,
            @NonNull AccessibilityServiceInfo serviceInfo) {
        super(context,
                shortcutType,
                AccessibilityFragmentType.TOGGLE,
                serviceInfo);
    }

    @Override
    public void updateActionItem(@NonNull ViewHolder holder,
            @ShortcutMenuMode int shortcutMenuMode) {
        super.updateActionItem(holder, shortcutMenuMode);

        final boolean isEditMenuMode =
                shortcutMenuMode == ShortcutMenuMode.EDIT;
        holder.mStatusView.setVisibility(isEditMenuMode ? View.GONE : View.VISIBLE);
        holder.mStatusView.setText(getStateDescription());
    }

    @Override
    public CharSequence getStateDescription() {
        final int statusResId = isAccessibilityServiceEnabled(getContext(), getId())
                ? R.string.accessibility_shortcut_menu_item_status_on
                : R.string.accessibility_shortcut_menu_item_status_off;
        return getContext().getString(statusResId);
    }
}
