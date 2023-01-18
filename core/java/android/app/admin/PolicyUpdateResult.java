/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.admin;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Class containing the reason for the policy (set from {@link DevicePolicyManager}) update (e.g.
 * success, failure reasons, etc.). This is passed in to
 * {@link PolicyUpdatesReceiver#onPolicySetResult}) and
 * {@link PolicyUpdatesReceiver#onPolicyChanged}).
 */
public final class PolicyUpdateResult {

    /**
     * Result code to indicate that the policy has not been enforced or has changed for an unknown
     * reason.
     */
    public static final int RESULT_FAILURE_UNKNOWN = -1;

    /**
     * Result code to indicate that the policy has been changed to the desired value set by
     * the admin.
     */
    public static final int RESULT_SUCCESS = 0;

    /**
     * Result code to indicate that the policy has not been enforced or has changed because another
     * admin has set a conflicting policy on the device.
     */
    public static final int RESULT_FAILURE_CONFLICTING_ADMIN_POLICY = 1;

    /**
     * Reason codes for {@link #getResultCode()}.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "RESULT_" }, value = {
            RESULT_FAILURE_UNKNOWN,
            RESULT_SUCCESS,
            RESULT_FAILURE_CONFLICTING_ADMIN_POLICY
    })
    public @interface ResultCode {}

    private final int mResultCode;

    /**
     * Constructor for {@code PolicyUpdateReason} that takes in a result code describing why the
     * policy has changed.
     *
     * @param resultCode Describes why the policy has changed.
     */
    public PolicyUpdateResult(@ResultCode int resultCode) {
        this.mResultCode = resultCode;
    }

    /**
     * Returns result code describing why the policy has changed.
     */
    @ResultCode
    public int getResultCode() {
        return mResultCode;
    }
}
