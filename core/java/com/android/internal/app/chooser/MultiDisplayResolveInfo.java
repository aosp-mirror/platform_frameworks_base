/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.app.chooser;

import android.app.Activity;
import android.os.Bundle;
import android.os.UserHandle;

import com.android.internal.app.ResolverActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a "stack" of chooser targets for various activities within the same component.
 */
public class MultiDisplayResolveInfo extends DisplayResolveInfo {

    List<DisplayResolveInfo> mTargetInfos = new ArrayList<>();
    // We'll use this DRI for basic presentation info - eg icon, name.
    final DisplayResolveInfo mBaseInfo;
    // Index of selected target
    private int mSelected = -1;

    /**
     * @param firstInfo A representative DRI to use for the main icon, title, etc for this Info.
     */
    public MultiDisplayResolveInfo(String packageName, DisplayResolveInfo firstInfo) {
        super(firstInfo);
        mBaseInfo = firstInfo;
        mTargetInfos.add(firstInfo);
    }

    @Override
    public CharSequence getExtendedInfo() {
        // Never show subtitle for stacked apps
        return null;
    }

    /**
     * Add another DisplayResolveInfo to the list included for this target.
     */
    public void addTarget(DisplayResolveInfo target) {
        mTargetInfos.add(target);
    }

    /**
     * List of all DisplayResolveInfos included in this target.
     */
    public List<DisplayResolveInfo> getTargets() {
        return mTargetInfos;
    }

    public void setSelected(int selected) {
        mSelected = selected;
    }

    /**
     * Return selected target.
     */
    public DisplayResolveInfo getSelectedTarget() {
        return hasSelected() ? mTargetInfos.get(mSelected) : null;
    }

    /**
     * Whether or not the user has selected a specific target for this MultiInfo.
     */
    public boolean hasSelected() {
        return mSelected >= 0;
    }

    @Override
    public boolean start(Activity activity, Bundle options) {
        return mTargetInfos.get(mSelected).start(activity, options);
    }

    @Override
    public boolean startAsCaller(ResolverActivity activity, Bundle options, int userId) {
        return mTargetInfos.get(mSelected).startAsCaller(activity, options, userId);
    }

    @Override
    public boolean startAsUser(Activity activity, Bundle options, UserHandle user) {
        return mTargetInfos.get(mSelected).startAsUser(activity, options, user);
    }

}
