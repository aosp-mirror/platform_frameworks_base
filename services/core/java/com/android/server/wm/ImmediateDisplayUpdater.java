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

package com.android.server.wm;

import android.annotation.NonNull;
import android.view.DisplayInfo;
import android.window.DisplayAreaInfo;

/**
 * DisplayUpdater that immediately applies new DisplayInfo properties
 */
public class ImmediateDisplayUpdater implements DisplayUpdater {

    private final DisplayContent mDisplayContent;
    private final DisplayInfo mDisplayInfo = new DisplayInfo();

    public ImmediateDisplayUpdater(@NonNull DisplayContent displayContent) {
        mDisplayContent = displayContent;
    }

    @Override
    public void updateDisplayInfo(Runnable callback) {
        mDisplayContent.mWmService.mDisplayManagerInternal.getNonOverrideDisplayInfo(
                mDisplayContent.mDisplayId, mDisplayInfo);
        mDisplayContent.onDisplayInfoUpdated(mDisplayInfo);
        callback.run();
    }

    @Override
    public void onDisplayContentDisplayPropertiesPreChanged(int displayId, int initialDisplayWidth,
            int initialDisplayHeight, int newWidth, int newHeight) {
        mDisplayContent.mDisplaySwitchTransitionLauncher.requestDisplaySwitchTransitionIfNeeded(
                displayId, initialDisplayWidth, initialDisplayHeight, newWidth, newHeight);
    }

    @Override
    public void onDisplayContentDisplayPropertiesPostChanged(int previousRotation, int newRotation,
            DisplayAreaInfo newDisplayAreaInfo) {
        mDisplayContent.mDisplaySwitchTransitionLauncher.onDisplayUpdated(previousRotation,
                newRotation,
                newDisplayAreaInfo);
    }
}
