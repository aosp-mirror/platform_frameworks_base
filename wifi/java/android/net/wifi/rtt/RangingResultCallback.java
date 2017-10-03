/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.wifi.rtt;

import android.os.Handler;

import java.util.List;

/**
 * Base class for ranging result callbacks. Should be extended by applications and set when calling
 * {@link WifiRttManager#startRanging(RangingRequest, RangingResultCallback, Handler)}. A single
 * result from a range request will be called in this object.
 *
 * @hide RTT_API
 */
public abstract class RangingResultCallback {
    /**
     * Individual range request status, {@link RangingResult#getStatus()}. Indicates ranging
     * operation was successful and distance value is valid.
     */
    public static final int STATUS_SUCCESS = 0;

    /**
     * Individual range request status, {@link RangingResult#getStatus()}. Indicates ranging
     * operation failed and the distance value is invalid.
     */
    public static final int STATUS_FAIL = 1;

    /**
     * Called when a ranging operation failed in whole - i.e. no ranging operation to any of the
     * devices specified in the request was attempted.
     */
    public abstract void onRangingFailure();

    /**
     * Called when a ranging operation was executed. The list of results corresponds to devices
     * specified in the ranging request.
     *
     * @param results List of range measurements, one per requested device.
     */
    public abstract void onRangingResults(List<RangingResult> results);
}
