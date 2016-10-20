/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * @hide
 */
public final class ApplicationThreadConstants {
    public static final int BACKUP_MODE_INCREMENTAL = 0;
    public static final int BACKUP_MODE_FULL = 1;
    public static final int BACKUP_MODE_RESTORE = 2;
    public static final int BACKUP_MODE_RESTORE_FULL = 3;

    public static final int DEBUG_OFF = 0;
    public static final int DEBUG_ON = 1;
    public static final int DEBUG_WAIT = 2;

    // the package has been removed, clean up internal references
    public static final int PACKAGE_REMOVED = 0;
    public static final int EXTERNAL_STORAGE_UNAVAILABLE = 1;
    // the package is being modified in-place, don't kill it and retain references to it
    public static final int PACKAGE_REMOVED_DONT_KILL = 2;
    // a previously removed package was replaced with a new version [eg. upgrade, split added, ...]
    public static final int PACKAGE_REPLACED = 3;
}