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
 * limitations under the License
 */

package com.android.server.am;

/**
 * Common class for the various debug {@link android.util.Log} output configuration in the activity
 * manager package.
 */
class ActivityManagerDebugConfig {

    // All output logs in the activity manager package use the {@link #TAG_AM} string for tagging
    // their log output. This makes it easy to identify the origin of the log message when sifting
    // through a large amount of log output from multiple sources. However, it also makes trying
    // to figure-out the origin of a log message while debugging the activity manager a little
    // painful. By setting this constant to true, log messages from the activity manager package
    // will be tagged with their class names instead fot the generic tag.
    static final boolean TAG_WITH_CLASS_NAME = false;

    // While debugging it is sometimes useful to have the category name of the log prepended to the
    // base log tag to make sifting through logs with the same base tag easier. By setting this
    // constant to true, the category name of the log point will be prepended to the log tag.
    static final boolean PREPEND_CATEGORY_NAME = false;

    // Default log tag for the activity manager package.
    static final String TAG_AM = "ActivityManager";

    // Enable all debug log categories.
    static final boolean DEBUG_ALL = false;

    // Available log categories in the activity manager package.
    static final boolean DEBUG_BACKUP = DEBUG_ALL || false;
    static final boolean DEBUG_BROADCAST = DEBUG_ALL || false;
    static final boolean DEBUG_BROADCAST_BACKGROUND = DEBUG_BROADCAST || false;
    static final boolean DEBUG_BROADCAST_LIGHT = DEBUG_BROADCAST || false;
    static final boolean DEBUG_CLEANUP = DEBUG_ALL || false;
    static final boolean DEBUG_CONFIGURATION = DEBUG_ALL || false;
    static final boolean DEBUG_FOCUS = false;
    static final boolean DEBUG_IMMERSIVE = DEBUG_ALL || false;
    static final boolean DEBUG_LOCKSCREEN = DEBUG_ALL || false;
    static final boolean DEBUG_LRU = DEBUG_ALL || false;
    static final boolean DEBUG_MU = DEBUG_ALL || false;
    static final boolean DEBUG_OOM_ADJ = DEBUG_ALL || false;
    static final boolean DEBUG_PAUSE = DEBUG_ALL || false;
    static final boolean DEBUG_POWER = DEBUG_ALL || false;
    static final boolean DEBUG_POWER_QUICK = DEBUG_POWER || false;
    static final boolean DEBUG_PROCESS_OBSERVERS = DEBUG_ALL || false;
    static final boolean DEBUG_PROCESSES = DEBUG_ALL || false;
    static final boolean DEBUG_PROVIDER = DEBUG_ALL || false;
    static final boolean DEBUG_PSS = DEBUG_ALL || false;
    static final boolean DEBUG_RECENTS = DEBUG_ALL || false;
    static final boolean DEBUG_RESULTS = DEBUG_ALL || false;
    static final boolean DEBUG_SERVICE = DEBUG_ALL || false;
    static final boolean DEBUG_SERVICE_EXECUTING = DEBUG_ALL || false;
    static final boolean DEBUG_STACK = DEBUG_ALL || false;
    static final boolean DEBUG_SWITCH = DEBUG_ALL || false;
    static final boolean DEBUG_TASKS = DEBUG_ALL || false;
    static final boolean DEBUG_THUMBNAILS = DEBUG_ALL || false;
    static final boolean DEBUG_TRANSITION = DEBUG_ALL || false;
    static final boolean DEBUG_URI_PERMISSION = DEBUG_ALL || false;
    static final boolean DEBUG_USER_LEAVING = DEBUG_ALL || false;
    static final boolean DEBUG_VISBILITY = DEBUG_ALL || false;

    static final String POSTFIX_BACKUP = (PREPEND_CATEGORY_NAME) ? "_Backup" : "";
    static final String POSTFIX_BROADCAST = (PREPEND_CATEGORY_NAME) ? "_Broadcast" : "";
    static final String POSTFIX_CLEANUP = (PREPEND_CATEGORY_NAME) ? "_Cleanup" : "";
    static final String POSTFIX_MU = "_MU";
    static final String POSTFIX_SERVICE = (PREPEND_CATEGORY_NAME) ? "_Service" : "";
    static final String POSTFIX_SERVICE_EXECUTING =
            (PREPEND_CATEGORY_NAME) ? "_ServiceExecuting" : "";

}
