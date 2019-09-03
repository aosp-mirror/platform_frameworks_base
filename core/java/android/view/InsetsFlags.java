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

import static android.view.WindowInsetsController.APPEARANCE_LIGHT_SIDE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_TOP_BAR;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_BARS;
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
                    mask = APPEARANCE_OPAQUE_BARS,
                    equals = APPEARANCE_OPAQUE_BARS,
                    name = "OPAQUE_BARS"),
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_LOW_PROFILE_BARS,
                    equals = APPEARANCE_LOW_PROFILE_BARS,
                    name = "LOW_PROFILE_BARS"),
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_LIGHT_TOP_BAR,
                    equals = APPEARANCE_LIGHT_TOP_BAR,
                    name = "LIGHT_TOP_BAR"),
            @ViewDebug.FlagToString(
                    mask = APPEARANCE_LIGHT_SIDE_BARS,
                    equals = APPEARANCE_LIGHT_SIDE_BARS,
                    name = "LIGHT_SIDE_BARS")
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
}
