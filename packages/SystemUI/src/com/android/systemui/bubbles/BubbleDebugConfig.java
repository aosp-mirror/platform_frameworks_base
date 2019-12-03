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

package com.android.systemui.bubbles;

/**
 * Common class for the various debug {@link android.util.Log} output configuration in the Bubbles
 * package.
 */
public class BubbleDebugConfig {

    // All output logs in the Bubbles package use the {@link #TAG_BUBBLES} string for tagging their
    // log output. This makes it easy to identify the origin of the log message when sifting
    // through a large amount of log output from multiple sources. However, it also makes trying
    // to figure-out the origin of a log message while debugging the Bubbles a little painful. By
    // setting this constant to true, log messages from the Bubbles package will be tagged with
    // their class names instead fot the generic tag.
    static final boolean TAG_WITH_CLASS_NAME = false;

    // Default log tag for the Bubbles package.
    static final String TAG_BUBBLES = "Bubbles";

    static final boolean DEBUG_BUBBLE_CONTROLLER = false;
    static final boolean DEBUG_BUBBLE_DATA = false;
    static final boolean DEBUG_BUBBLE_STACK_VIEW = false;
    static final boolean DEBUG_BUBBLE_EXPANDED_VIEW = false;

}
