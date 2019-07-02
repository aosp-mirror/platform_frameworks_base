/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

public class RecentsDebugFlags {

    public static class Static {
        // Enables debug drawing for the transition thumbnail
        public static final boolean EnableTransitionThumbnailDebugMode = false;

        // Disables enter and exit transitions for other tasks for low ram devices
        public static final boolean DisableRecentsLowRamEnterExitAnimation = false;

    }
}
