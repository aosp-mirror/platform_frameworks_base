/**
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

import android.security.keystore.KeyAttestationApplicationId;

/** @hide */
interface IKeyAttestationApplicationIdProvider {
    const int ERROR_GET_ATTESTATION_APPLICATION_ID_FAILED = 1;

    /**
     * Provides information describing the possible applications identified by a UID.
     *
     * In case of not getting package ids from uid return
     * {@link #ERROR_GET_ATTESTATION_APPLICATION_ID_FAILED} to the caller.
     *
     * @hide
     */
    KeyAttestationApplicationId getKeyAttestationApplicationId(int uid);
}
