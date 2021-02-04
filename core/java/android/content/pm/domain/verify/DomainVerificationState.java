/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.content.pm.domain.verify;

import android.annotation.IntDef;

/**
 * @hide
 */
public interface DomainVerificationState {

    /**
     * @hide
     */
    @IntDef({
            STATE_NO_RESPONSE,
            STATE_SUCCESS,
            STATE_MIGRATED,
            STATE_RESTORED,
            STATE_APPROVED,
            STATE_DENIED,
            STATE_LEGACY_FAILURE,
            STATE_FIRST_VERIFIER_DEFINED
    })
    @interface State {
    }

    // TODO(b/159952358): Document all the places that states need to be updated when one is added
    /**
     * @see DomainVerificationManager#STATE_NO_RESPONSE
     */
    int STATE_NO_RESPONSE = 0;

    /**
     * @see DomainVerificationManager#STATE_SUCCESS
     */
    int STATE_SUCCESS = 1;

    /**
     * The system has chosen to ignore the verification agent's opinion on whether the domain should
     * be verified. This will treat the domain as verified.
     * <p>
     * TODO: This currently combines SysConfig and instant app. Is it worth separating those?
     */
    int STATE_APPROVED = 2;

    /**
     * The system has chosen to ignore the verification agent's opinion on whether the domain should
     * be verified. This will treat the domain as unverified.
     */
    int STATE_DENIED = 3;

    /**
     * The state was migrated from the previous intent filter verification API. This will treat the
     * domain as verified, but it should be updated by the verification agent. The older API's
     * collection and handling of verifying domains may lead to improperly migrated state.
     */
    int STATE_MIGRATED = 4;

    /**
     * The state was restored from a user backup or by the system. This is treated as if the domain
     * was verified, but the verification agent may choose to re-verify this domain to be certain
     * nothing has changed since the snapshot.
     */
    int STATE_RESTORED = 5;

    /**
     * The domain was failed by a legacy intent filter verification agent from v1 of the API. This
     * is made distinct from {@link #STATE_FIRST_VERIFIER_DEFINED} to prevent any v2 verification
     * agent from misinterpreting the result, since {@link #STATE_FIRST_VERIFIER_DEFINED} is agent
     * specific and can be defined as a special error code.
     */
    int STATE_LEGACY_FAILURE = 6;

    /**
     * @see DomainVerificationManager#STATE_FIRST_VERIFIER_DEFINED
     */
    int STATE_FIRST_VERIFIER_DEFINED = 0b10000000000;
}
