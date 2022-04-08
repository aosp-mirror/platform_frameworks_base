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
    Intent getSettingsIntent();
    void setToggleState(boolean state);
    int getMetricsCategory();

    /**
     * Indicates whether the detail view wants to have its header (back button, title and
     * toggle) shown.
     */
    default boolean hasHeader() {
        return true;
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
