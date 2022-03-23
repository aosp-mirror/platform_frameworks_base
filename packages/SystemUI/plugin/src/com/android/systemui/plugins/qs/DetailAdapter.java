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

package com.android.systemui.plugins.qs;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.plugins.annotations.ProvidesInterface;

@ProvidesInterface(version = DetailAdapter.VERSION)
public interface DetailAdapter {
    public static final int VERSION = 1;

    CharSequence getTitle();
    Boolean getToggleState();

    default boolean getToggleEnabled() {
        return true;
    }

    View createDetailView(Context context, View convertView, ViewGroup parent);

    /**
     * @return intent for opening more settings related to this detail panel. If null, the more
     * settings button will not be shown
     */
    Intent getSettingsIntent();

    /**
     * @return resource id of the string to use for opening the settings intent. If
     * {@code Resources.ID_NULL}, then use the default string:
     * {@code com.android.systemui.R.string.quick_settings_more_settings}
     */
    default int getSettingsText() {
        return Resources.ID_NULL;
    }

    /**
     * @return resource id of the string to use for closing the detail panel. If
     * {@code Resources.ID_NULL}, then use the default string:
     * {@code com.android.systemui.R.string.quick_settings_done}
     */
    default int getDoneText() {
        return Resources.ID_NULL;
    }

    void setToggleState(boolean state);
    int getMetricsCategory();

    /**
     * Indicates whether the detail view wants to have its header (back button, title and
     * toggle) shown.
     */
    default boolean hasHeader() {
        return true;
    }

    /**
     * Indicates whether the detail view wants to animate when shown. This has no affect over the
     * closing animation. Detail panels will always animate when closed.
     */
    default boolean shouldAnimate() {
        return true;
    }

    /**
     * @return true if the callback handled the event and wants to keep the detail panel open, false
     * otherwise. Returning false will close the panel.
     */
    default boolean onDoneButtonClicked() {
        return false;
    }

    default UiEventLogger.UiEventEnum openDetailEvent() {
        return INVALID;
    }

    default UiEventLogger.UiEventEnum closeDetailEvent() {
        return INVALID;
    }

    default UiEventLogger.UiEventEnum moreSettingsEvent() {
        return INVALID;
    }

    UiEventLogger.UiEventEnum INVALID = () -> 0;
}
