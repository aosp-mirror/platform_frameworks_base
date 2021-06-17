/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenshot;

@SuppressWarnings("PointlessBooleanExpression")
class LogConfig {

    /** Log ALL the things... */
    private static final boolean DEBUG_ALL = false;

    /** Default log logTag for screenshot code */
    private static final String TAG_SS = "Screenshot";

    /** Use class name as Log tag instead of the default */
    private static final boolean TAG_WITH_CLASS_NAME = false;

    /** Action creation and user selection: Share, Save, Edit, Delete, Smart action, etc */
    static final boolean DEBUG_ACTIONS = DEBUG_ALL || false;

    /** Debug info about animations such as start, complete and cancel */
    static final boolean DEBUG_ANIM = DEBUG_ALL || false;

    /** Whenever Uri is supplied to consumer, or onComplete runnable is run() */
    static final boolean DEBUG_CALLBACK = DEBUG_ALL || false;

    /** Logs information about dismissing the screenshot tool */
    static final boolean DEBUG_DISMISS = DEBUG_ALL || false;

    /** Touch or key event driven action or side effects */
    static final boolean DEBUG_INPUT = DEBUG_ALL || false;

    /** Scroll capture usage */
    static final boolean DEBUG_SCROLL = DEBUG_ALL || false;

    /** Service lifecycle events and callbacks */
    static final boolean DEBUG_SERVICE = DEBUG_ALL || false;

    /** Storage related actions, Bitmap.compress, ContentManager, etc */
    static final boolean DEBUG_STORAGE = DEBUG_ALL || false;

    /** High level logical UI actions: timeout, onConfigChanged, insets, show actions, reset  */
    static final boolean DEBUG_UI = DEBUG_ALL || false;

    /** Interactions with Window and WindowManager */
    static final boolean DEBUG_WINDOW = DEBUG_ALL || false;

    static String logTag(Class<?> cls) {
        return TAG_WITH_CLASS_NAME ? cls.getSimpleName() : TAG_SS;
    }
}
