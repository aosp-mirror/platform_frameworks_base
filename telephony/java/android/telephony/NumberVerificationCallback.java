/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;

/**
 * A callback for number verification. After a request for number verification is received,
 * the system will call {@link #onCallReceived(String)} if a phone call was received from a number
 * matching the provided {@link PhoneNumberRange} or it will call {@link #onVerificationFailed(int)}
 * if an error occurs.
 * @hide
 */
@SystemApi
public interface NumberVerificationCallback {
    /** @hide */
    @IntDef(value = {REASON_UNSPECIFIED, REASON_TIMED_OUT, REASON_NETWORK_NOT_AVAILABLE,
            REASON_TOO_MANY_CALLS, REASON_CONCURRENT_REQUESTS, REASON_IN_ECBM,
            REASON_IN_EMERGENCY_CALL},
            prefix = {"REASON_"})
    @interface NumberVerificationFailureReason {}

    /**
     * Verification failed for an unspecified reason.
     */
    int REASON_UNSPECIFIED = 0;

    /**
     * Verification failed because no phone call was received from a matching number within the
     * provided timeout.
     */
    int REASON_TIMED_OUT = 1;

    /**
     * Verification failed because no cellular voice network is available.
     */
    int REASON_NETWORK_NOT_AVAILABLE = 2;

    /**
     * Verification failed because there are currently too many ongoing phone calls for a new
     * incoming phone call to be received.
     */
    int REASON_TOO_MANY_CALLS = 3;

    /**
     * Verification failed because a previous request for verification has not yet completed.
     */
    int REASON_CONCURRENT_REQUESTS = 4;

    /**
     * Verification failed because the phone is in emergency callback mode.
     */
    int REASON_IN_ECBM = 5;

    /**
     * Verification failed because the phone is currently in an emergency call.
     */
    int REASON_IN_EMERGENCY_CALL = 6;

    /**
     * Called when the device receives a phone call from the provided {@link PhoneNumberRange}.
     * @param phoneNumber The phone number within the range that called. May or may not contain the
     *                    country code, but will be entirely numeric.
     */
    default void onCallReceived(@NonNull String phoneNumber) { }

    /**
     * Called when verification fails for some reason.
     * @param reason The reason for failure.
     */
    default void onVerificationFailed(@NumberVerificationFailureReason int reason) { }
}
