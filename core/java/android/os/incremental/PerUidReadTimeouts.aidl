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
 * Max value is ~1hr = 3600s = 3600000ms = 3600000000us
 * @hide
 */
parcelable PerUidReadTimeouts {
    /** UID to apply these timeouts to */
    int uid;

    /**
    * Min time to read any block. Note that this doesn't apply to reads
    * which are satisfied from the page cache.
    */
    long minTimeUs;

    /**
    * Min time to satisfy a pending read. Must be >= min_time_us. Any
    * pending read which is filled before this time will be delayed so
    * that the total read time >= this value.
    */
    long minPendingTimeUs;

    /**
    * Max time to satisfy a pending read before the read times out.
    * Must be >= min_pending_time_us
    */
    long maxPendingTimeUs;
}
