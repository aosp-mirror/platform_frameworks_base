/*
 * Copyright 2021 The Android Open Source Project
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

package android.security.attestationverification;

import android.os.Bundle;
import android.os.ParcelDuration;
import android.security.attestationverification.AttestationProfile;
import android.security.attestationverification.VerificationToken;
import com.android.internal.infra.AndroidFuture;


/**
 * Binder interface to communicate with AttestationVerificationManagerService.
 * @hide
 */
oneway interface IAttestationVerificationManagerService {

    void verifyAttestation(
            in AttestationProfile profile,
            in int localBindingType,
            in Bundle requirements,
            in byte[] attestation,
            in AndroidFuture resultCallback);

    void verifyToken(
            in VerificationToken token,
            in ParcelDuration maximumTokenAge,
            in AndroidFuture resultCallback);
}
