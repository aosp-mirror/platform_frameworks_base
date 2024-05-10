/*
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.keystore;

/**
 * @hide
 * Represents a signature data read from the package file. Extracted from from the PackageManager's
 * PackageInfo for the purpose of key attestation. It is part of the KeyAttestationPackageInfo,
 * which is used by keystore to identify the caller of the keystore API towards a remote party.
 */
parcelable Signature {
    /**
     * Represents signing certificate data associated with application package, signatures are
     * expected to be a hex-encoded ASCII string representing valid X509 certificate.
     */
    byte[] data;
}
