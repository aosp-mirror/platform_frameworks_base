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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;

import java.util.List;

/**
 * Extension for {@link TargetAdapter} and used for AccessibilityButtonChooserActivity.
 */
class ButtonTargetAdapter extends TargetAdapter {
    private List<AccessibilityTarget> mTargets;

    ButtonTargetAdapter(List<AccessibilityTarget> targets) {
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
        final View root = LayoutInflater.from(context).inflate(
                R.layout.accessibility_button_chooser_item, parent, /* attachToRoot= */
                false);
        final AccessibilityTarget target = mTargets.get(position);
        final ImageView iconView = root.findViewById(R.id.accessibility_button_target_icon);
        final TextView labelView = root.findViewById(R.id.accessibility_button_target_label);
        iconView.setImageDrawable(target.getIcon());
        labelView.setText(target.getLabel());
        return root;
    }
}
