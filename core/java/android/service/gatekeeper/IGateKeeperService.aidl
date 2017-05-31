/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.service.gatekeeper;

import android.service.gatekeeper.GateKeeperResponse;

/**
 * Interface for communication with GateKeeper, the
 * secure password storage daemon.
 *
 * This must be kept manually in sync with system/core/gatekeeperd
 * until AIDL can generate both C++ and Java bindings.
 *
 * @hide
 */
interface IGateKeeperService {
    /**
     * Enrolls a password, returning the handle to the enrollment to be stored locally.
     * @param uid The Android user ID associated to this enrollment
     * @param currentPasswordHandle The previously enrolled handle, or null if none
     * @param currentPassword The previously enrolled plaintext password, or null if none.
     *                        If provided, must verify against the currentPasswordHandle.
     * @param desiredPassword The new desired password, for which a handle will be returned
     *                        upon success.
     * @return an EnrollResponse or null on failure
     */
    GateKeeperResponse enroll(int uid, in byte[] currentPasswordHandle, in byte[] currentPassword,
            in byte[] desiredPassword);

    /**
     * Verifies an enrolled handle against a provided, plaintext blob.
     * @param uid The Android user ID associated to this enrollment
     * @param enrolledPasswordHandle The handle against which the provided password will be
     *                               verified.
     * @param The plaintext blob to verify against enrolledPassword.
     * @return a VerifyResponse, or null on failure.
     */
    GateKeeperResponse verify(int uid, in byte[] enrolledPasswordHandle, in byte[] providedPassword);

    /**
     * Verifies an enrolled handle against a provided, plaintext blob.
     * @param uid The Android user ID associated to this enrollment
     * @param challenge a challenge to authenticate agaisnt the device credential. If successful
     *                  authentication occurs, this value will be written to the returned
     *                  authentication attestation.
     * @param enrolledPasswordHandle The handle against which the provided password will be
     *                               verified.
     * @param The plaintext blob to verify against enrolledPassword.
     * @return a VerifyResponse with an attestation, or null on failure.
     */
    GateKeeperResponse verifyChallenge(int uid, long challenge, in byte[] enrolledPasswordHandle,
            in byte[] providedPassword);

    /**
     * Retrieves the secure identifier for the user with the provided Android ID,
     * or 0 if none is found.
     * @param uid the Android user id
     */
    long getSecureUserId(int uid);

    /**
     * Clears secure user id associated with the provided Android ID.
     * Must be called when password is set to NONE.
     * @param uid the Android user id.
     */
    void clearSecureUserId(int uid);

    /**
     * Notifies gatekeeper that device setup has been completed and any potentially still existing
     * state from before a factory reset can be cleaned up (if it has not been already).
     */
    void reportDeviceSetupComplete();
}
