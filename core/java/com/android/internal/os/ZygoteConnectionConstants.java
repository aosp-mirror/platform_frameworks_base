/*
 * Copyright (C) 2007 The Android Open Source Project
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
 * Sharable zygote constants.
 *
 * @hide
 */
public class ZygoteConnectionConstants {
    /**
     * {@link android.net.LocalSocket#setSoTimeout} value for connections.
     * Effectively, the amount of time a requestor has between the start of
     * the request and the completed request. The select-loop mode Zygote
     * doesn't have the logic to return to the select loop in the middle of
     * a request, so we need to time out here to avoid being denial-of-serviced.
     */
    public static final int CONNECTION_TIMEOUT_MILLIS = 1000;

    /**
     * Wait time for a wrapped app to report back its pid.
     *
     * We'll wait up to thirty seconds. This should give enough time for the fork
     * to go through, but not to trigger the watchdog in the system server (by default
     * sixty seconds).
     *
     * WARNING: This may trigger the watchdog in debug mode. However, to support
     *          wrapping on lower-end devices we do not have much choice.
     */
    public static final int WRAPPED_PID_TIMEOUT_MILLIS = 30000;
}
