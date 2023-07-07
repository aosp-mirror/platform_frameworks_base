/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.text.TextUtils;

import androidx.recyclerview.widget.DiffUtil;

import com.android.internal.accessibility.dialog.AccessibilityTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link DiffUtil.Callback} to calculate the difference between old and new menu target List.
 */
class MenuTargetsCallback extends DiffUtil.Callback {
    private final List<AccessibilityTarget> mOldTargets = new ArrayList<>();
    private final List<AccessibilityTarget> mNewTargets = new ArrayList<>();

    MenuTargetsCallback(List<AccessibilityTarget> oldTargets,
            List<AccessibilityTarget> newTargets) {
        mOldTargets.addAll(oldTargets);
        mNewTargets.addAll(newTargets);
    }

    @Override
    public int getOldListSize() {
        return mOldTargets.size();
    }

    @Override
    public int getNewListSize() {
        return mNewTargets.size();
    }

    @Override
    public boolean areItemsTheSame(int oldIndex, int newIndex) {
        return mOldTargets.get(oldIndex).getId().equals(mNewTargets.get(newIndex).getId());
    }

    @Override
    public boolean areContentsTheSame(int oldIndex, int newIndex) {
        if (!TextUtils.equals(mOldTargets.get(oldIndex).getLabel(),
                mNewTargets.get(newIndex).getLabel())) {
            return false;
        }

        if (!TextUtils.equals(mOldTargets.get(oldIndex).getStateDescription(),
                mNewTargets.get(newIndex).getStateDescription())) {
            return false;
        }

        return true;
    }
}
