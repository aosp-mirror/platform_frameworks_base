/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.CheckResult;
import android.annotation.NonNull;
import android.app.Service;
import android.os.Bundle;
import android.security.attestationverification.AttestationVerificationManager.VerificationResult;

/**
 * A verifier which can be implemented by apps to verify an attestation (as described in {@link
 * AttestationVerificationManager}).
 *
 * In the manifest for this service, specify the profile and local binding type this verifier
 * supports. Create a new service for each combination of profile & local binding type that your app
 * supports. Each service must declare an {@code intent-filter} action of {@link #SERVICE_INTERFACE}
 * and permission of {@link android.Manifest.permission#BIND_ATTESTATION_VERIFICATION_SERVICE}.
 *
 * <p>Example:
 * {@code
 * <pre>
 * <service android:name=".MyAttestationVerificationService"
 *          android:permission="android.permission.BIND_ATTESTATION_VERIFICATION_SERVICE"
 *          android:exported="true">
 *   <intent-filter>
 *     <action
 *         android:name="android.security.attestationverification.AttestationVerificationService" />
 *   </intent-filter>
 *   <meta-data android:name="android.security.attestationverification.PROFILE_ID"
 *              android:value="PROFILE_PLACEHOLDER_0" />
 *   <meta-data android:name="android.security.attestationverification.LOCAL_BINDING_TYPE"
 *              android:value="TYPE_PLACEHOLDER_0" />
 * </service>
 * </pre>
 * }
 *
 * <p>For app-defined profiles, an example of the {@code <meta-data>}:
 * {@code
 * <pre>
 *   <meta-data android:name="android.security.attestation.PROFILE_PACKAGE_NAME"
 *              android:value="com.example" />
 *   <meta-data android:name="android.security.attestation.PROFILE_NAME"
 *              android:value="com.example.profile.PROFILE_FOO" />
 * </pre>
 * }
 *
 * @hide
 */
public abstract class AttestationVerificationService extends Service {

    /**
     * An intent action for a service to be bound and act as an attestation verifier.
     *
     * <p>The app will be kept alive for a short duration between verification calls after which
     * the system will unbind from this service making the app eligible for cleanup.
     *
     * <p>The service must also require permission
     * {@link android.Manifest.permission#BIND_ATTESTATION_VERIFICATION_SERVICE}.
     */
    public static final String SERVICE_INTERFACE =
            "android.security.attestationverification.AttestationVerificationService";

    /**
     * Verifies that {@code attestation} attests that the device identified by the local binding
     * data in {@code requirements} meets the minimum requirements of this verifier for this
     * verifier's profile.
     *
     * <p>Called by the system to verify an attestation.
     *
     * <p>The data passed into this method comes directly from apps and should be treated as
     * potentially dangerous user input.
     *
     * @param requirements a {@link Bundle} containing locally-known data which must match {@code
     *                     attestation}
     * @param attestation  the attestation to verify
     * @return whether the verification passed
     * @see AttestationVerificationManager#verifyAttestation(AttestationProfile, int, Bundle,
     * byte[], java.util.concurrent.Executor, java.util.function.BiConsumer)
     */
    @CheckResult
    @VerificationResult
    public abstract int onVerifyPeerDeviceAttestation(
            @NonNull Bundle requirements,
            @NonNull byte[] attestation);
}
