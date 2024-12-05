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

package android.security;

/**
 * @hide This class is necessary to allow the version of the AIDL interface for Keystore and
* KeyMint used in KeyStore2.java to differ by BUILD flag `RELEASE_ATTEST_MODULES`. When
* `RELEASE_ATTEST_MODULES` is set, this file is included, and the latest HALs for Keystore (V5)
* and KeyMint (V4) are used.
*/
class KeyStore2HalVersion {
    public static byte[] getSupplementaryAttestationInfoHelper(int tag, KeyStore2 ks)
            throws KeyStoreException {
        return ks.handleRemoteExceptionWithRetry(
            (service) -> service.getSupplementaryAttestationInfo(tag));
    }
}
