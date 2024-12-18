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

import static android.view.WindowInsetsController.APPEARANCE_FORCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND;
import static android.view.WindowInsetsController.BEHAVIOR_DEFAULT;
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
                    name = "LIGHT_NAVIGATION_BARS"),
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS,
                    equals = APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS,
                    name = "SEMI_TRANSPARENT_STATUS_BARS"),
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_SEMI_TRANSPARENT_NAVIGATION_BARS,
                    equals = APPEARANCE_SEMI_TRANSPARENT_NAVIGATION_BARS,
                    name = "SEMI_TRANSPARENT_NAVIGATION_BARS"),
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_FORCE_LIGHT_NAVIGATION_BARS,
                    equals = APPEARANCE_FORCE_LIGHT_NAVIGATION_BARS,
                    name = "FORCE_LIGHT_NAVIGATION_BARS"),
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                    equals = APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                    name = "APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND"),
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_LIGHT_CAPTION_BARS,
                    equals = APPEARANCE_LIGHT_CAPTION_BARS,
                    name = "APPEARANCE_LIGHT_CAPTION_BARS")
    })
    public @Appearance int appearance;

    @ViewDebug.ExportedProperty(flagMapping = {
            @ViewDebug.FlagToString(
                    mask = BEHAVIOR_DEFAULT,
                    equals = BEHAVIOR_DEFAULT,
                    name = "DEFAULT"),
            @ViewDebug.FlagToString(
                    mask = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    equals = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    name = "SHOW_TRANSIENT_BARS_BY_SWIPE")
    })
    public @Behavior int behavior = BEHAVIOR_DEFAULT;
}
