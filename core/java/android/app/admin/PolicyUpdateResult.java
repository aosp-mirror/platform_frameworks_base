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
 * {@link PolicyUpdateReceiver#onPolicySetResult}) and
 * {@link PolicyUpdateReceiver#onPolicyChanged}).
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
    public static final int RESULT_POLICY_SET = 0;

    /**
     * Result code to indicate that the policy has not been enforced or has changed because another
     * admin has set a conflicting policy on the device.
     *
     * <p>The system will automatically try to enforce the policy when it can without additional
     * calls from the admin.
     */
    public static final int RESULT_FAILURE_CONFLICTING_ADMIN_POLICY = 1;

    /**
     * Result code to indicate that the policy set by the admin has been successfully cleared,
     * admins will no longer receive policy updates for this policy after this point.
     *
     * <p>Note that the policy can still be enforced by some other admin.
     */
    public static final int RESULT_POLICY_CLEARED = 2;

    /**
     * Result code to indicate that the policy set by the admin has not been enforced because the
     * local storage has reached its max limit.
     *
     * <p>The system will NOT try to automatically store and enforce this policy again.
     */
    public static final int RESULT_FAILURE_STORAGE_LIMIT_REACHED = 3;

    /**
     * Result code to indicate that the policy set by the admin has not been enforced because of a
     * permanent hardware limitation/issue.
     *
     * <p>The system will NOT try to automatically store and enforce this policy again.
     */
    public static final int RESULT_FAILURE_HARDWARE_LIMITATION = 4;

    /**
     * Reason codes for {@link #getResultCode()}.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "RESULT_" }, value = {
            RESULT_FAILURE_UNKNOWN,
            RESULT_POLICY_SET,
            RESULT_FAILURE_CONFLICTING_ADMIN_POLICY,
            RESULT_POLICY_CLEARED,
            RESULT_FAILURE_STORAGE_LIMIT_REACHED,
            RESULT_FAILURE_HARDWARE_LIMITATION
    })
    public @interface ResultCode {}

    private final int mResultCode;

    /**
     * Constructor for {@code PolicyUpdateResult} that takes in a result code describing why the
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
