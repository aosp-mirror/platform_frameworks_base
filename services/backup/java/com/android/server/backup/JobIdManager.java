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

package com.android.server.backup;

/**
 * Allocates job IDs for {@link FullBackupJob} and {@link KeyValueBackupJob}
 */
public class JobIdManager {
    public static int getJobIdForUserId(int minJobId, int maxJobId, int userId) {
        if (minJobId + userId > maxJobId) {
            throw new RuntimeException("No job IDs available in the given range");
        }

        return minJobId + userId;
    }
}
