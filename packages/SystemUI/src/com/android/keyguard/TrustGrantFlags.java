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

package com.android.keyguard;

import android.service.trust.TrustAgentService;

import java.util.Objects;

/**
 * Translating {@link android.service.trust.TrustAgentService.GrantTrustFlags} to a more
 * parsable object. These flags are requested by a TrustAgent.
 */
public class TrustGrantFlags {
    final int mFlags;

    public TrustGrantFlags(int flags) {
        this.mFlags = flags;
    }

    /** {@link TrustAgentService.FLAG_GRANT_TRUST_INITIATED_BY_USER} */
    public boolean isInitiatedByUser() {
        return (mFlags & TrustAgentService.FLAG_GRANT_TRUST_INITIATED_BY_USER) != 0;
    }

    /**
     * Trust agent is requesting to dismiss the keyguard.
     * See {@link TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD}.
     *
     * This does not guarantee that the keyguard is dismissed.
     * KeyguardUpdateMonitor makes the final determination whether the keyguard should be dismissed.
     * {@link KeyguardUpdateMonitorCallback#onTrustGrantedForCurrentUser(
     *      boolean, TrustGrantFlags, String).
     */
    public boolean dismissKeyguardRequested() {
        return (mFlags & TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD) != 0;
    }

    /** {@link TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE} */
    public boolean temporaryAndRenewable() {
        return (mFlags & TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) != 0;
    }

    /** {@link TrustAgentService.FLAG_GRANT_TRUST_DISPLAY_MESSAGE} */
    public boolean displayMessage() {
        return (mFlags & TrustAgentService.FLAG_GRANT_TRUST_DISPLAY_MESSAGE) != 0;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TrustGrantFlags)) {
            return false;
        }

        return ((TrustGrantFlags) o).mFlags == this.mFlags;
    }

    @Override
    public int hashCode() {
      return Objects.hash(mFlags);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("[");
        sb.append(mFlags);
        sb.append("]=");

        if (isInitiatedByUser()) {
            sb.append("initiatedByUser|");
        }
        if (dismissKeyguardRequested()) {
            sb.append("dismissKeyguard|");
        }
        if (temporaryAndRenewable()) {
            sb.append("temporaryAndRenewable|");
        }
        if (displayMessage()) {
            sb.append("displayMessage|");
        }

        return sb.toString();
    }
}
