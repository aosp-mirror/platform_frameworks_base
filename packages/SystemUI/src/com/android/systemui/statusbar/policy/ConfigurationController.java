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

package com.android.systemui.statusbar.policy;

import android.content.res.Configuration;

import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

/**
 * Common listener for configuration or subsets of configuration changes (like density or
 * font scaling), providing easy static dependence on these events.
 */
public interface ConfigurationController extends CallbackController<ConfigurationListener> {

    /** Alert controller of a change in the configuration. */
    void onConfigurationChanged(Configuration newConfiguration);

    /** Alert controller of a change in between light and dark themes. */
    void notifyThemeChanged();

    /** Query the current configuration's layout direction */
    boolean isLayoutRtl();

    /** Logging only; Query the current configuration's night mode name */
    String getNightModeName();

    interface ConfigurationListener {
        default void onConfigChanged(Configuration newConfig) {}
        default void onDensityOrFontScaleChanged() {}
        default void onSmallestScreenWidthChanged() {}
        default void onMaxBoundsChanged() {}
        default void onUiModeChanged() {}
        default void onThemeChanged() {}
        default void onLocaleListChanged() {}
        default void onLayoutDirectionChanged(boolean isLayoutRtl) {}
        default void onOrientationChanged(int orientation) {}
    }
}
