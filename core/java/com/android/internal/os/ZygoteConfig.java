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

package com.android.internal.os;

/**
 * Flag names for configuring the zygote.
 *
 * @hide
 */
public class ZygoteConfig {

    /** If {@code true}, enables the unspecialized app process (USAP) pool feature */
    public static final String USAP_POOL_ENABLED = "usap_pool_enabled";

    /** The threshold used to determine if the pool should be refilled */
    public static final String USAP_POOL_REFILL_THRESHOLD = "usap_refill_threshold";

    /** The maximum number of processes to keep in the USAP pool */
    public static final String USAP_POOL_SIZE_MAX = "usap_pool_size_max";

    /** The minimum number of processes to keep in the USAP pool */
    public static final String USAP_POOL_SIZE_MIN = "usap_pool_size_min";

    /** The number of milliseconds to delay before refilling the USAP pool */
    public static final String USAP_POOL_REFILL_DELAY_MS = "usap_pool_refill_delay_ms";
}
