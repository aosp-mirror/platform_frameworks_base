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

/** @hide */
oneway interface IStorageHealthListener {
    /** OK status, no pending reads. */
    const int HEALTH_STATUS_OK = 0;
    /* Statuses depend on timeouts defined in StorageHealthCheckParams. */
    /** Pending reads detected, waiting for params.blockedTimeoutMs to confirm blocked state. */
    const int HEALTH_STATUS_READS_PENDING = 1;
    /** There are reads pending for params.blockedTimeoutMs, waiting till
    *   params.unhealthyTimeoutMs to confirm unhealthy state. */
    const int HEALTH_STATUS_BLOCKED = 2;
    /** There are reads pending for params.unhealthyTimeoutMs,
    *   marking storage as unhealthy due to unknown issues. */
    const int HEALTH_STATUS_UNHEALTHY = 3;

    /** Health status callback. */
    void onHealthStatus(in int storageId, in int status);
}
