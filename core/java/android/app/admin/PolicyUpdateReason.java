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
 * Class containing the reason a policy (set from {@link DevicePolicyManager}) hasn't been enforced
 * (passed in to {@link PolicyUpdatesReceiver#onPolicySetResult}) or has changed (passed in to
 * {@link PolicyUpdatesReceiver#onPolicyChanged}).
 */
public final class PolicyUpdateReason {

    /**
     * Reason code to indicate that the policy has not been enforced or has changed for an unknown
     * reason.
     */
    public static final int REASON_UNKNOWN = -1;

    /**
     * Reason code to indicate that the policy has not been enforced or has changed because another
     * admin has set a conflicting policy on the device.
     */
    public static final int REASON_CONFLICTING_ADMIN_POLICY = 0;

    /**
     * Reason codes for {@link #getReasonCode()}.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "REASON_" }, value = {
            REASON_UNKNOWN,
            REASON_CONFLICTING_ADMIN_POLICY,
    })
    public @interface ReasonCode {}

    private final int mReasonCode;

    /**
     * Constructor for {@code PolicyUpdateReason} that takes in a reason code describing why the
     * policy has changed.
     *
     * @param reasonCode Describes why the policy has changed.
     */
    public PolicyUpdateReason(@ReasonCode int reasonCode) {
        this.mReasonCode = reasonCode;
    }

    /**
     * Returns reason code for why a policy hasn't been applied or has changed.
     */
    @ReasonCode
    public int getReasonCode() {
        return mReasonCode;
    }
}
