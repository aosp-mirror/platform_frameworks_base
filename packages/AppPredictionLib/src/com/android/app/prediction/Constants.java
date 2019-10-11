/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.app.prediction;

/**
 * Constants to be used with {@link android.app.prediction.AppPredictor}.
 */
public class Constants {

    /**
     * UI surface for predictions displayed on the user's home screen
     */
    public static final String UI_SURFACE_HOME = "home";

    /**
     * UI surface for predictions displayed on the recents/task switcher view
     */
    public static final String UI_SURFACE_RECENTS = "recents";

    /**
     * UI surface for predictions displayed on the share sheet.
     */
    public static final String UI_SURFACE_SHARE = "share";

    /**
     * Location constant when an app target or shortcut is started from the apps list
     */
    public static final String LAUNCH_LOCATION_APPS_LIST = "apps_list";

    /**
     * Location constant when an app target or shortcut is started from the user's home screen
     */
    public static final String LAUNCH_LOCATION_APPS_HOME = "home";

    /**
     * Location constant when an app target or shortcut is started from task switcher
     */
    public static final String LAUNCH_LOCATION_APPS_RECENTS = "recents";

    /**
     * Location constant when an app target or shortcut is started in the share sheet while it is
     * in collapsed state (showing a limited set of result).
     */
    public static final String LAUNCH_LOCATION_APPS_SHARE_COLLAPSED = "share_collapsed";

    /**
     * Location constant when an app target or shortcut is started in the share sheet while it is
     * in expended state and showing all the results.
     */
    public static final String LAUNCH_LOCATION_APPS_SHARE_EXPANDED = "shared_expanded";

    /**
     * Location constant when an app target or shortcut is started in the share sheet when the
     * target is displayed as a placeholder for an deprecated object.
     */
    public static final String LAUNCH_LOCATION_APPS_SHARE_LEGACY = "share_legacy";
}
