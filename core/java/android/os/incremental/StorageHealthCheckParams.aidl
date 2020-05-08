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

package android.os.incremental;

/**
 * @hide
 */
parcelable StorageHealthCheckParams {
    /** Timeouts of the oldest pending read.
    *   Valid values 0ms < blockedTimeoutMs < unhealthyTimeoutMs < storage page read timeout.
    *   Invalid values will disable health checking. */

    /** To consider storage "blocked". */
    int blockedTimeoutMs;
    /** To consider storage "unhealthy". */
    int unhealthyTimeoutMs;

    /** After storage is marked "unhealthy", how often to check if it recovered.
    *   Valid value 1000ms < unhealthyMonitoringMs. */
    int unhealthyMonitoringMs;
}
