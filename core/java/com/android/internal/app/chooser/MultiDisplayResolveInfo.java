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

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a "stack" of chooser targets for various activities within the same component.
 */
public class MultiDisplayResolveInfo extends DisplayResolveInfo {

    List<DisplayResolveInfo> mTargetInfos = new ArrayList<>();
    String mPackageName;
    // We'll use this DRI for basic presentation info - eg icon, name.
    final DisplayResolveInfo mBaseInfo;

    /**
     * @param firstInfo A representative DRI to use for the main icon, title, etc for this Info.
     */
    public MultiDisplayResolveInfo(String packageName, DisplayResolveInfo firstInfo) {
        super(firstInfo);
        mBaseInfo = firstInfo;
        mTargetInfos.add(firstInfo);
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

}
