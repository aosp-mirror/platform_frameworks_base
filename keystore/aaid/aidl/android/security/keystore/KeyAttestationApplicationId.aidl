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

import android.security.keystore.KeyAttestationPackageInfo;

/**
 * @hide
 * The information aggregated by this parcelable is used by keystore to identify a caller of the
 * keystore API toward a remote party. It aggregates multiple PackageInfos because keystore
 * can only determine a caller by uid granularity, and a uid can be shared by multiple packages.
 * The remote party must decide if it trusts all of the packages enough to consider the
 * confidentiality of the key material in question intact.
 */
parcelable KeyAttestationApplicationId {
    KeyAttestationPackageInfo[] packageInfos;
}
