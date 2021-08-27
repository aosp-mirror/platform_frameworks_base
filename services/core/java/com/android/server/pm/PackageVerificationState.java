/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.pm;

import android.content.pm.PackageManager;
import android.util.SparseBooleanArray;

import com.android.server.pm.PackageManagerService.VerificationParams;

/**
 * Tracks the package verification state for a particular package. Each package verification has a
 * required verifier and zero or more sufficient verifiers. Only one of the sufficient verifier list
 * must return affirmative to allow the package to be considered verified. If there are zero
 * sufficient verifiers, then package verification is considered complete.
 */
class PackageVerificationState {
    private final VerificationParams mParams;

    private final SparseBooleanArray mSufficientVerifierUids;

    private int mRequiredVerifierUid;

    private boolean mSufficientVerificationComplete;

    private boolean mSufficientVerificationPassed;

    private boolean mRequiredVerificationComplete;

    private boolean mRequiredVerificationPassed;

    private boolean mExtendedTimeout;

    private boolean mIntegrityVerificationComplete;

    /**
     * Create a new package verification state where {@code requiredVerifierUid} is the user ID for
     * the package that must reply affirmative before things can continue.
     */
    PackageVerificationState(VerificationParams params) {
        mParams = params;
        mSufficientVerifierUids = new SparseBooleanArray();
        mExtendedTimeout = false;
    }

    VerificationParams getVerificationParams() {
        return mParams;
    }

    /** Sets the user ID of the required package verifier. */
    void setRequiredVerifierUid(int uid) {
        mRequiredVerifierUid = uid;
    }

    /**
     * Add a verifier which is added to our sufficient list.
     *
     * @param uid user ID of sufficient verifier
     */
    void addSufficientVerifier(int uid) {
        mSufficientVerifierUids.put(uid, true);
    }

    /**
     * Should be called when a verification is received from an agent so the state of the package
     * verification can be tracked.
     *
     * @param uid user ID of the verifying agent
     * @return {@code true} if the verifying agent actually exists in our list
     */
    boolean setVerifierResponse(int uid, int code) {
        if (uid == mRequiredVerifierUid) {
            mRequiredVerificationComplete = true;
            switch (code) {
                case PackageManager.VERIFICATION_ALLOW_WITHOUT_SUFFICIENT:
                    mSufficientVerifierUids.clear();
                    // fall through
                case PackageManager.VERIFICATION_ALLOW:
                    mRequiredVerificationPassed = true;
                    break;
                default:
                    mRequiredVerificationPassed = false;
            }
            return true;
        } else {
            if (mSufficientVerifierUids.get(uid)) {
                if (code == PackageManager.VERIFICATION_ALLOW) {
                    mSufficientVerificationComplete = true;
                    mSufficientVerificationPassed = true;
                }

                mSufficientVerifierUids.delete(uid);
                if (mSufficientVerifierUids.size() == 0) {
                    mSufficientVerificationComplete = true;
                }

                return true;
            }
        }

        return false;
    }

    /**
     * Returns whether verification is considered complete. This means that the required verifier
     * and at least one of the sufficient verifiers has returned a positive verification.
     *
     * @return {@code true} when verification is considered complete
     */
    boolean isVerificationComplete() {
        if (!mRequiredVerificationComplete) {
            return false;
        }

        if (mSufficientVerifierUids.size() == 0) {
            return true;
        }

        return mSufficientVerificationComplete;
    }

    /**
     * Returns whether installation should be allowed. This should only be called after {@link
     * #isVerificationComplete()} returns {@code true}.
     *
     * @return {@code true} if installation should be allowed
     */
    boolean isInstallAllowed() {
        if (!mRequiredVerificationPassed) {
            return false;
        }

        if (mSufficientVerificationComplete) {
            return mSufficientVerificationPassed;
        }

        return true;
    }

    /** Extend the timeout for this Package to be verified. */
    void extendTimeout() {
        if (!mExtendedTimeout) {
            mExtendedTimeout = true;
        }
    }

    /**
     * Returns whether the timeout was extended for verification.
     *
     * @return {@code true} if a timeout was already extended.
     */
    boolean timeoutExtended() {
        return mExtendedTimeout;
    }

    void setIntegrityVerificationResult(int code) {
        mIntegrityVerificationComplete = true;
    }

    boolean isIntegrityVerificationComplete() {
        return mIntegrityVerificationComplete;
    }

    boolean areAllVerificationsComplete() {
        return mIntegrityVerificationComplete && isVerificationComplete();
    }
}
