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

package android.view;

import static android.view.View.NAVIGATION_BAR_TRANSLUCENT;
import static android.view.View.NAVIGATION_BAR_TRANSPARENT;
import static android.view.View.STATUS_BAR_TRANSLUCENT;
import static android.view.View.STATUS_BAR_TRANSPARENT;
import static android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
import static android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
import static android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_SWIPE;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;

import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;

/**
 * Contains the information about {@link Appearance} and {@link Behavior} of system windows which
 * can produce insets. This is for carrying the request from a client to the system server.
 * @hide
 */
public class InsetsFlags {

    @ViewDebug.ExportedProperty(flagMapping = {
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_OPAQUE_STATUS_BARS,
                    equals = APPEARANCE_OPAQUE_STATUS_BARS,
                    name = "OPAQUE_STATUS_BARS"),
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_OPAQUE_NAVIGATION_BARS,
                    equals = APPEARANCE_OPAQUE_NAVIGATION_BARS,
                    name = "OPAQUE_NAVIGATION_BARS"),
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_LOW_PROFILE_BARS,
                    equals = APPEARANCE_LOW_PROFILE_BARS,
                    name = "LOW_PROFILE_BARS"),
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_LIGHT_STATUS_BARS,
                    equals = APPEARANCE_LIGHT_STATUS_BARS,
                    name = "LIGHT_STATUS_BARS"),
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_LIGHT_NAVIGATION_BARS,
                    equals = APPEARANCE_LIGHT_NAVIGATION_BARS,
                    name = "LIGHT_NAVIGATION_BARS")
    })
    public @Appearance int appearance;

    @ViewDebug.ExportedProperty(flagMapping = {
            @ViewDebug.FlagToString(
                    mask = BEHAVIOR_SHOW_BARS_BY_SWIPE,
                    equals = BEHAVIOR_SHOW_BARS_BY_SWIPE,
                    name = "SHOW_BARS_BY_SWIPE"),
            @ViewDebug.FlagToString(
                    mask = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    equals = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    name = "SHOW_TRANSIENT_BARS_BY_SWIPE")
    })
    public @Behavior int behavior;

    /**
     * Converts system UI visibility to appearance.
     *
     * @param systemUiVisibility the system UI visibility to be converted.
     * @return the outcome {@link Appearance}
     */
    public static @Appearance int getAppearance(int systemUiVisibility) {
        int appearance = 0;
        appearance |= convertFlag(systemUiVisibility, SYSTEM_UI_FLAG_LOW_PROFILE,
                APPEARANCE_LOW_PROFILE_BARS);
        appearance |= convertFlag(systemUiVisibility, SYSTEM_UI_FLAG_LIGHT_STATUS_BAR,
                APPEARANCE_LIGHT_STATUS_BARS);
        appearance |= convertFlag(systemUiVisibility, SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR,
                APPEARANCE_LIGHT_NAVIGATION_BARS);
        appearance |= convertNoFlag(systemUiVisibility,
                STATUS_BAR_TRANSLUCENT | STATUS_BAR_TRANSPARENT, APPEARANCE_OPAQUE_STATUS_BARS);
        appearance |= convertNoFlag(systemUiVisibility,
                NAVIGATION_BAR_TRANSLUCENT | NAVIGATION_BAR_TRANSPARENT,
                APPEARANCE_OPAQUE_NAVIGATION_BARS);
        return appearance;
    }

    /**
     * Converts the system UI visibility into an appearance flag if the given visibility contains
     * the given system UI flag.
     */
    private static @Appearance int convertFlag(int systemUiVisibility, int systemUiFlag,
            @Appearance int appearance) {
        return (systemUiVisibility & systemUiFlag) != 0 ? appearance : 0;
    }

    /**
     * Converts the system UI visibility into an appearance flag if the given visibility doesn't
     * contains the given system UI flag.
     */
    private static @Appearance int convertNoFlag(int systemUiVisibility, int systemUiFlag,
            @Appearance int appearance) {
        return (systemUiVisibility & systemUiFlag) == 0 ? appearance : 0;
    }
}
