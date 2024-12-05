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

package android.hardware.contexthub;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.chre.flags.Flags;

/**
 * Return type of {@link IHubEndpointLifecycleCallback#onSessionOpenRequest}. The value determines
 * whether a open session request from the remote is accepted or not.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public class HubEndpointSessionResult {
    private final boolean mAccepted;

    @Nullable private final String mReason;

    private HubEndpointSessionResult(boolean accepted, @Nullable String reason) {
        mAccepted = accepted;
        mReason = reason;
    }

    /**
     * Retrieve the decision of the session request.
     *
     * @return Whether a session request was accepted or not, previously set with {@link #accept()}
     *     or {@link #reject(String)}.
     */
    public boolean isAccepted() {
        return mAccepted;
    }

    /**
     * Retrieve the decision of the session request.
     *
     * @return The reason previously set in {@link #reject(String)}. If the result was {@link
     *     #accept()}, the reason will be null.
     */
    @Nullable
    public String getReason() {
        return mReason;
    }

    /** Accept the request. */
    @NonNull
    public static HubEndpointSessionResult accept() {
        return new HubEndpointSessionResult(true, null);
    }

    /**
     * Reject the request with a reason.
     *
     * @param reason Reason why the request was rejected, for diagnostic purposes.
     */
    @NonNull
    public static HubEndpointSessionResult reject(@NonNull String reason) {
        return new HubEndpointSessionResult(false, reason);
    }
}
