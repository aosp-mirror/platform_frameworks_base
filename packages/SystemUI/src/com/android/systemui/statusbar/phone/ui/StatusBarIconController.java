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

package com.android.systemui.statusbar.phone.ui;

import android.annotation.Nullable;
import android.content.Context;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.CallIndicatorIconState;

import java.util.List;

/** Interface controlling the icons shown in the status bar. */
public interface StatusBarIconController {

    /**
     * When an icon is added with TAG_PRIMARY, it will be treated as the primary icon
     * in that slot and not added as a sub slot.
     */
    int TAG_PRIMARY = 0;

    /** */
    void addIconGroup(IconManager iconManager);
    /** */
    void removeIconGroup(IconManager iconManager);

    /** Refresh the state of an IconManager by recreating the views */
    void refreshIconGroup(IconManager iconManager);

    /**
     * Adds or updates an icon that comes from an active tile service.
     *
     * If the icon is null, the icon will be removed.
     */
    void setIconFromTile(String slot, @Nullable StatusBarIcon icon);

    /** Removes an icon that had come from an active tile service. */
    void removeIconForTile(String slot);

    /** Adds or updates an icon for the given slot for **internal system icons**. */
    void setIcon(String slot, int resourceId, CharSequence contentDescription);

    /**
     * Sets up a wifi icon using the new data pipeline. No effect if the wifi icon has already been
     * set up (inflated and added to the view hierarchy).
     */
    void setNewWifiIcon();

    /**
     * Notify this class that there is a new set of mobile icons to display, keyed off of this list
     * of subIds. The icons will be added and bound to the mobile data pipeline via
     * {@link com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder}.
     */
    void setNewMobileIconSubIds(List<Integer> subIds);
    /**
     * Display the no calling & SMS icons.
     */
    void setCallStrengthIcons(String slot, List<CallIndicatorIconState> states);

    /**
     * Display the no calling & SMS icons.
     */
    void setNoCallingIcons(String slot, List<CallIndicatorIconState> states);

    /** Sets whether the icon in the given slot should be visible or not. */
    void setIconVisibility(String slot, boolean b);

    /**
     * Sets the live region mode for the icon
     *
     * @param slot                    Icon slot to set region for
     * @param accessibilityLiveRegion live region mode for the icon
     * @see android.view.View#setAccessibilityLiveRegion(int)
     */
    void setIconAccessibilityLiveRegion(String slot, int accessibilityLiveRegion);

    /**
     * If you don't know what to pass for `tag`, either remove all icons for slot, or use
     * TAG_PRIMARY to refer to the first icon at a given slot.
     */
    void removeIcon(String slot, int tag);

    // TODO: See if we can rename this tunable name.
    String ICON_HIDE_LIST = "icon_blacklist";

    /** Reads the default hide list from config value unless hideListStr is provided. */
    static ArraySet<String> getIconHideList(Context context, String hideListStr) {
        ArraySet<String> ret = new ArraySet<>();
        String[] hideList = hideListStr == null
                ? context.getResources().getStringArray(R.array.config_statusBarIconsToExclude)
                : hideListStr.split(",");
        for (String slot : hideList) {
            if (!TextUtils.isEmpty(slot)) {
                ret.add(slot);
            }
        }
        return ret;
    }

}
