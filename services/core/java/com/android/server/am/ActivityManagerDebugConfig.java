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

    // While debugging it is sometimes useful to have the category name of the log appended to the
    // base log tag to make sifting through logs with the same base tag easier. By setting this
    // constant to true, the category name of the log point will be appended to the log tag.
    static final boolean APPEND_CATEGORY_NAME = false;

    // Default log tag for the activity manager package.
    static final String TAG_AM = "ActivityManager";

    // Enable all debug log categories.
    static final boolean DEBUG_ALL = false;

    // Available log categories in the activity manager package.
    static final boolean DEBUG_ANR = false;
    static final boolean DEBUG_BACKGROUND_CHECK = DEBUG_ALL || false;
    static final boolean DEBUG_BACKUP = DEBUG_ALL || false;
    static final boolean DEBUG_BROADCAST = DEBUG_ALL || false;
    static final boolean DEBUG_BROADCAST_BACKGROUND = DEBUG_BROADCAST || false;
    static final boolean DEBUG_BROADCAST_LIGHT = DEBUG_BROADCAST || false;
    static final boolean DEBUG_BROADCAST_DEFERRAL = DEBUG_BROADCAST || false;
    static final boolean DEBUG_COMPACTION = DEBUG_ALL || false;
    static final boolean DEBUG_LRU = DEBUG_ALL || false;
    static final boolean DEBUG_MU = DEBUG_ALL || false;
    static final boolean DEBUG_NETWORK = DEBUG_ALL || false;
    static final boolean DEBUG_OOM_ADJ = DEBUG_ALL || false;
    static final boolean DEBUG_OOM_ADJ_REASON = DEBUG_ALL || false;
    static final boolean DEBUG_POWER = DEBUG_ALL || false;
    static final boolean DEBUG_POWER_QUICK = DEBUG_POWER || false;
    static final boolean DEBUG_PROCESS_OBSERVERS = DEBUG_ALL || false;
    static final boolean DEBUG_PROCESSES = DEBUG_ALL || false;
    static final boolean DEBUG_PROVIDER = DEBUG_ALL || false;
    static final boolean DEBUG_PSS = DEBUG_ALL || false;
    static final boolean DEBUG_SERVICE = DEBUG_ALL || false;
    static final boolean DEBUG_FOREGROUND_SERVICE = DEBUG_ALL || false;
    static final boolean DEBUG_SERVICE_EXECUTING = DEBUG_ALL || false;
    static final boolean DEBUG_UID_OBSERVERS = DEBUG_ALL || false;
    static final boolean DEBUG_USAGE_STATS = DEBUG_ALL || false;
    static final boolean DEBUG_PERMISSIONS_REVIEW = DEBUG_ALL || false;
    static final boolean DEBUG_WHITELISTS = DEBUG_ALL || false;

    static final String POSTFIX_BACKUP = (APPEND_CATEGORY_NAME) ? "_Backup" : "";
    static final String POSTFIX_BROADCAST = (APPEND_CATEGORY_NAME) ? "_Broadcast" : "";
    static final String POSTFIX_CLEANUP = (APPEND_CATEGORY_NAME) ? "_Cleanup" : "";
    static final String POSTFIX_LRU = (APPEND_CATEGORY_NAME) ? "_LRU" : "";
    static final String POSTFIX_MU = "_MU";
    static final String POSTFIX_NETWORK = "_Network";
    static final String POSTFIX_OOM_ADJ = (APPEND_CATEGORY_NAME) ? "_OomAdj" : "";
    static final String POSTFIX_POWER = (APPEND_CATEGORY_NAME) ? "_Power" : "";
    static final String POSTFIX_PROCESS_OBSERVERS = (APPEND_CATEGORY_NAME)
            ? "_ProcessObservers" : "";
    static final String POSTFIX_PROCESSES = (APPEND_CATEGORY_NAME) ? "_Processes" : "";
    static final String POSTFIX_PROVIDER = (APPEND_CATEGORY_NAME) ? "_Provider" : "";
    static final String POSTFIX_PSS = (APPEND_CATEGORY_NAME) ? "_Pss" : "";
    static final String POSTFIX_SERVICE = (APPEND_CATEGORY_NAME) ? "_Service" : "";
    static final String POSTFIX_SERVICE_EXECUTING =
            (APPEND_CATEGORY_NAME) ? "_ServiceExecuting" : "";
    static final String POSTFIX_UID_OBSERVERS = (APPEND_CATEGORY_NAME)
            ? "_UidObservers" : "";
}
