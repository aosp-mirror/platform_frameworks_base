/*
 * Copyright (c) 2023, The Android Open Source Project
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

import android.security.keystore.Signature;

/**
 * @hide
 * This parcelable constitutes and excerpt from the PackageManager's PackageInfo for the purpose of
 * key attestation. It is part of the KeyAttestationApplicationId, which is used by
 * keystore to identify the caller of the keystore API towards a remote party.
 */
parcelable KeyAttestationPackageInfo {
    String packageName;

    long versionCode;

    Signature[] signatures;
}
