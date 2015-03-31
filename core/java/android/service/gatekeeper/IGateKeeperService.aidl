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
     * @return the handle corresponding to desiredPassword, or null
     */
    byte[] enroll(int uid, in byte[] currentPasswordHandle, in byte[] currentPassword,
            in byte[] desiredPassword);

    /**
     * Verifies an enrolled handle against a provided, plaintext blob.
     * @param uid The Android user ID associated to this enrollment
     * @param enrolledPasswordHandle The handle against which the provided password will be
     *                               verified.
     * @param The plaintext blob to verify against enrolledPassword.
     * @return true if success, false if failure
     */
    boolean verify(int uid, in byte[] enrolledPasswordHandle, in byte[] providedPassword);
}
