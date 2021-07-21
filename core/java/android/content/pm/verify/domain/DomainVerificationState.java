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

package android.content.pm.verify.domain;

import android.annotation.IntDef;
import android.annotation.NonNull;

/**
 * @hide
 */
public interface DomainVerificationState {

    @IntDef({
            STATE_NO_RESPONSE,
            STATE_SUCCESS,
            STATE_MIGRATED,
            STATE_RESTORED,
            STATE_APPROVED,
            STATE_DENIED,
            STATE_LEGACY_FAILURE,
            STATE_SYS_CONFIG,
            STATE_FIRST_VERIFIER_DEFINED
    })
    @interface State {
    }

    // TODO(b/159952358): Document all the places that states need to be updated when one is added
    /**
     * @see DomainVerificationInfo#STATE_NO_RESPONSE
     */
    int STATE_NO_RESPONSE = 0;

    /**
     * @see DomainVerificationInfo#STATE_SUCCESS
     */
    int STATE_SUCCESS = 1;

    /**
     * The system has chosen to ignore the verification agent's opinion on whether the domain should
     * be verified. This will treat the domain as verified.
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
     * The application has been granted auto verification for all domains by configuration on the
     * system image.
     *
     * TODO: Can be stored per-package rather than for all domains for a package to save memory.
     */
    int STATE_SYS_CONFIG = 7;

    /**
     * @see DomainVerificationInfo#STATE_FIRST_VERIFIER_DEFINED
     */
    int STATE_FIRST_VERIFIER_DEFINED = 0b10000000000;

    @NonNull
    static String stateToDebugString(@DomainVerificationState.State int state) {
        switch (state) {
            case DomainVerificationState.STATE_NO_RESPONSE:
                return "none";
            case DomainVerificationState.STATE_SUCCESS:
                return "verified";
            case DomainVerificationState.STATE_APPROVED:
                return "approved";
            case DomainVerificationState.STATE_DENIED:
                return "denied";
            case DomainVerificationState.STATE_MIGRATED:
                return "migrated";
            case DomainVerificationState.STATE_RESTORED:
                return "restored";
            case DomainVerificationState.STATE_LEGACY_FAILURE:
                return "legacy_failure";
            case DomainVerificationState.STATE_SYS_CONFIG:
                return "system_configured";
            default:
                return String.valueOf(state);
        }
    }

    /**
     * For determining re-verify policy. This is hidden from the domain verification agent so that
     * no behavior is made based on the result.
     */
    static boolean isDefault(@State int state) {
        switch (state) {
            case STATE_NO_RESPONSE:
            case STATE_MIGRATED:
            case STATE_RESTORED:
                return true;
            case STATE_SUCCESS:
            case STATE_APPROVED:
            case STATE_DENIED:
            case STATE_LEGACY_FAILURE:
            case STATE_SYS_CONFIG:
            default:
                return false;
        }
    }

    /**
     * Checks if a state considers the corresponding domain to be successfully verified. The domain
     * verification agent may use this to determine whether or not to re-verify a domain.
     */
    static boolean isVerified(@DomainVerificationState.State int state) {
        switch (state) {
            case DomainVerificationState.STATE_SUCCESS:
            case DomainVerificationState.STATE_APPROVED:
            case DomainVerificationState.STATE_MIGRATED:
            case DomainVerificationState.STATE_RESTORED:
            case DomainVerificationState.STATE_SYS_CONFIG:
                return true;
            case DomainVerificationState.STATE_NO_RESPONSE:
            case DomainVerificationState.STATE_DENIED:
            case DomainVerificationState.STATE_LEGACY_FAILURE:
            default:
                return false;
        }
    }

    /**
     * Checks if a state is modifiable by the domain verification agent. This is useful as the
     * platform may add new state codes in newer versions, and older verification agents can use
     * this method to determine if a state can be changed without having to be aware of what the new
     * state means.
     */
    static boolean isModifiable(@DomainVerificationState.State int state) {
        switch (state) {
            case DomainVerificationState.STATE_NO_RESPONSE:
            case DomainVerificationState.STATE_SUCCESS:
            case DomainVerificationState.STATE_MIGRATED:
            case DomainVerificationState.STATE_RESTORED:
            case DomainVerificationState.STATE_LEGACY_FAILURE:
                return true;
            case DomainVerificationState.STATE_APPROVED:
            case DomainVerificationState.STATE_DENIED:
            case DomainVerificationState.STATE_SYS_CONFIG:
                return false;
            default:
                return state >= DomainVerificationState.STATE_FIRST_VERIFIER_DEFINED;
        }
    }

    /**
     * Whether the state is migrated when updating a package. Generally this is only for states
     * that maintain verification state or were set by an explicit user or developer action.
     */
    static boolean shouldMigrate(@State int state) {
        switch (state) {
            case STATE_SUCCESS:
            case STATE_MIGRATED:
            case STATE_RESTORED:
            case STATE_APPROVED:
            case STATE_DENIED:
                return true;
            case STATE_NO_RESPONSE:
            case STATE_LEGACY_FAILURE:
            case STATE_SYS_CONFIG:
            case STATE_FIRST_VERIFIER_DEFINED:
            default:
                return false;
        }
    }

    @DomainVerificationInfo.State
    static int convertToInfoState(@State int internalState) {
        if (internalState >= STATE_FIRST_VERIFIER_DEFINED) {
            return internalState;
        } else if (internalState == STATE_NO_RESPONSE) {
            return DomainVerificationInfo.STATE_NO_RESPONSE;
        } else if (internalState == STATE_SUCCESS) {
            return DomainVerificationInfo.STATE_SUCCESS;
        } else if (!isModifiable(internalState)) {
            return DomainVerificationInfo.STATE_UNMODIFIABLE;
        } else if (isVerified(internalState)) {
            return DomainVerificationInfo.STATE_MODIFIABLE_VERIFIED;
        } else {
            return DomainVerificationInfo.STATE_MODIFIABLE_UNVERIFIED;
        }
    }
}
