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

import android.annotation.NonNull;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.R;
import com.android.internal.accessibility.common.ShortcutConstants.ShortcutMenuMode;

import java.util.List;

/**
 * Extension for {@link TargetAdapter} and used for AccessibilityShortcutChooserActivity.
 */
class ShortcutTargetAdapter extends TargetAdapter {
    @ShortcutMenuMode
    private int mShortcutMenuMode = ShortcutMenuMode.LAUNCH;
    private final List<AccessibilityTarget> mTargets;

    ShortcutTargetAdapter(@NonNull List<AccessibilityTarget> targets) {
        mTargets = targets;
    }

    @Override
    public int getCount() {
        return mTargets.size();
    }

    @Override
    public Object getItem(int position) {
        return mTargets.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final Context context = parent.getContext();
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(
                    R.layout.accessibility_shortcut_chooser_item, parent, /* attachToRoot= */
                    false);
            holder = new ViewHolder();
            holder.mCheckBoxView = convertView.findViewById(
                    R.id.accessibility_shortcut_target_checkbox);
            holder.mIconView = convertView.findViewById(R.id.accessibility_shortcut_target_icon);
            holder.mLabelView = convertView.findViewById(
                    R.id.accessibility_shortcut_target_label);
            holder.mSwitchItem = convertView.findViewById(
                    R.id.accessibility_shortcut_target_switch_item);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final AccessibilityTarget target = mTargets.get(position);
        target.updateActionItem(holder, mShortcutMenuMode);

        return convertView;
    }

    void setShortcutMenuMode(@ShortcutMenuMode int shortcutMenuMode) {
        mShortcutMenuMode = shortcutMenuMode;
    }

    @ShortcutMenuMode
    int getShortcutMenuMode() {
        return mShortcutMenuMode;
    }
}
