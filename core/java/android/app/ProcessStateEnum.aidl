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
package android.app;

/**
 * Defines the PROCESS_STATE_* values used by ActivityManager.
 * These values are shared by Java and native side.
 * {@hide}
 */
@Backing(type="int")
enum ProcessStateEnum {
    /** @hide Not a real process state. */
    UNKNOWN = -1,

    /** @hide Process is a persistent system process. */
    PERSISTENT = 0,

    /** @hide Process is a persistent system process and is doing UI. */
    PERSISTENT_UI = 1,

    /** @hide Process is hosting the current top activities.  Note that this covers
     * all activities that are visible to the user. */
    TOP = 2,

    /** @hide Process is bound to a TOP app. */
    BOUND_TOP = 3,

    /** @hide Process is hosting a foreground service. */
    FOREGROUND_SERVICE = 4,

    /** @hide Process is hosting a foreground service due to a system binding. */
    BOUND_FOREGROUND_SERVICE = 5,

    /** @hide Process is important to the user, and something they are aware of. */
    IMPORTANT_FOREGROUND = 6,

    /** @hide Process is important to the user, but not something they are aware of. */
    IMPORTANT_BACKGROUND = 7,

    /** @hide Process is in the background transient so we will try to keep running. */
    TRANSIENT_BACKGROUND = 8,

    /** @hide Process is in the background running a backup/restore operation. */
    BACKUP = 9,

    /** @hide Process is in the background running a service.  Unlike oom_adj, this level
     * is used for both the normal running in background state and the executing
     * operations state. */
    SERVICE = 10,

    /** @hide Process is in the background running a receiver.   Note that from the
     * perspective of oom_adj, receivers run at a higher foreground level, but for our
     * prioritization here that is not necessary and putting them below services means
     * many fewer changes in some process states as they receive broadcasts. */
    RECEIVER = 11,

    /** @hide Same as {@link #PROCESS_STATE_TOP} but while device is sleeping. */
    TOP_SLEEPING = 12,

    /** @hide Process is in the background, but it can't restore its state so we want
     * to try to avoid killing it. */
    HEAVY_WEIGHT = 13,

    /** @hide Process is in the background but hosts the home activity. */
    HOME = 14,

    /** @hide Process is in the background but hosts the last shown activity. */
    LAST_ACTIVITY = 15,

    /** @hide Process is being cached for later use and contains activities. */
    CACHED_ACTIVITY = 16,

    /** @hide Process is being cached for later use and is a client of another cached
     * process that contains activities. */
    CACHED_ACTIVITY_CLIENT = 17,

    /** @hide Process is being cached for later use and has an activity that corresponds
     * to an existing recent task. */
    CACHED_RECENT = 18,

    /** @hide Process is being cached for later use and is empty. */
    CACHED_EMPTY = 19,

    /** @hide Process does not exist. */
    NONEXISTENT = 20,

}
