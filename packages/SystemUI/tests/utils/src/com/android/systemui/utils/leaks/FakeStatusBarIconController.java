/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils.leaks;

import android.graphics.drawable.Drawable;
import android.testing.LeakCheck;

import androidx.annotation.Nullable;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.CallIndicatorIconState;
import com.android.systemui.statusbar.phone.ui.IconManager;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;

import java.util.List;

public class FakeStatusBarIconController extends BaseLeakChecker<IconManager>
        implements StatusBarIconController {

    public FakeStatusBarIconController(LeakCheck test) {
        super(test, "StatusBarGroup");
    }

    @Override
    public void addIconGroup(IconManager iconManager) {
        addCallback(iconManager);
    }

    @Override
    public void removeIconGroup(IconManager iconManager) {
        removeCallback(iconManager);
    }

    @Override
    public void refreshIconGroup(IconManager iconManager) {
    }

    @Override
    public void setIconFromTile(String slot, StatusBarIcon icon) {

    }

    @Override
    public void removeIconForTile(String slot) {

    }

    @Override
    public void setIcon(String slot, int resourceId, CharSequence contentDescription) {

    }

    @Override
    public void setResourceIcon(String slot, @Nullable String resPackage, int iconResId,
            @Nullable Drawable preloadedIcon, CharSequence contentDescription,
            StatusBarIcon.Shape shape) {
    }

    @Override
    public void setNewWifiIcon() {
    }

    @Override
    public void setNewMobileIconSubIds(List<Integer> subIds) {
    }

    @Override
    public void setCallStrengthIcons(String slot, List<CallIndicatorIconState> states) {
    }

    @Override
    public void setNoCallingIcons(String slot, List<CallIndicatorIconState> states) {
    }

    @Override
    public void setIconVisibility(String slotTty, boolean b) {
    }

    @Override
    public void removeIcon(String slot, int tag) {
    }

    @Override
    public void setIconAccessibilityLiveRegion(String slot, int mode) {
    }

}
