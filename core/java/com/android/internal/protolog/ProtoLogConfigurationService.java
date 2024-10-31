/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.protolog;

import android.annotation.NonNull;

public interface ProtoLogConfigurationService extends IProtoLogConfigurationService {
    /**
     * Get the list of groups clients have registered to the protolog service.
     * @return The list of ProtoLog groups registered with this service.
     */
    @NonNull
    String[] getGroups();

    /**
     * Check if a group is logging to logcat
     * @param group The group we want to check for
     * @return True iff we are logging this group to logcat.
     */
    boolean isLoggingToLogcat(@NonNull String group);

    /**
     * Enable logging target groups to logcat.
     * @param groups we want to enable logging them to logcat for.
     */
    void enableProtoLogToLogcat(@NonNull String... groups);

    /**
     * Disable logging target groups to logcat.
     * @param groups we want to disable from being logged to logcat.
     */
    void disableProtoLogToLogcat(@NonNull String... groups);
}
