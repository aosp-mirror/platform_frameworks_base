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

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.CheckResult;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Bundle;
import android.os.ParcelDuration;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.infra.AndroidFuture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Provides methods for verifying that attestations from remote compute environments meet minimum
 * security requirements specified by attestation profiles.
 *
 * @hide
 */
@SystemService(Context.ATTESTATION_VERIFICATION_SERVICE)
public class AttestationVerificationManager {

    private static final String TAG = "AVF";
    private static final Duration MAX_TOKEN_AGE = Duration.ofHours(1);

    private final Context mContext;
    private final IAttestationVerificationManagerService mService;

    /**
     * Verifies that {@code attestation} describes a computing environment that meets the
     * requirements of {@code profile}, {@code localBindingType}, and {@code requirements}.
     *
     * <p>This method verifies that at least one system-registered {@linkplain
     * AttestationVerificationService attestation verifier} associated with {@code profile} and
     * {@code localBindingType} has verified that {@code attestation} attests that the remote
     * environment matching the local binding data (determined by {@code localBindingType}) in
     * {@code requirements} meets the requirements of the profile.
     *
     * <p>For successful verification, the {@code requirements} bundle must contain locally-known
     * data which must match {@code attestation}. The required data in the bundle is defined by the
     * {@code localBindingType} (see documentation for the type). Verifiers will fail to verify the
     * attestation if the bundle contains unsupported data.
     *
     * <p>The {@code localBindingType} specifies how {@code attestation} is bound to a local
     * secure channel endpoint or similar connection with the target remote environment described by
     * the attestation. The binding is expected to be related to a cryptographic protocol, and each
     * binding type requires specific arguments to be present in the {@code requirements} bundle. It
     * is this binding to something known locally that ensures an attestation is not only valid, but
     * is also associated with a particular connection.
     *
     * <p>The {@code callback} is called with a result and {@link VerificationToken} (which may be
     * null). The result is an integer (see constants in this class with the prefix {@code RESULT_}.
     * The result is {@link #RESULT_SUCCESS} when at least one verifier has passed its checks. The
     * token may be used in calls to other parts of the system.
     *
     * <p>It's expected that a verifier will be able to decode and understand the passed values,
     * otherwise fail to verify. {@code attestation} should contain some type data to prevent parse
     * errors.
     *
     * <p>The values put into the {@code requirements} Bundle depend on the {@code
     * localBindingType} used.
     *
     * @param profile          the attestation profile which defines the security requirements which
     *                         must be met by the environment described by {@code attestation}
     * @param localBindingType the type of the local binding data; see constants in this class with
     *                         the prefix {@code TYPE_}
     * @param requirements     a {@link Bundle} containing locally-known data which must match
     *                         {@code attestation}
     * @param attestation      attestation data which describes a remote computing environment
     * @param executor         {@code callback} will be executed on this executor
     * @param callback         will be called with the results of the verification
     * @see AttestationVerificationService
     */
    @RequiresPermission(Manifest.permission.USE_ATTESTATION_VERIFICATION_SERVICE)
    public void verifyAttestation(
            @NonNull AttestationProfile profile,
            @LocalBindingType int localBindingType,
            @NonNull Bundle requirements,
            @NonNull byte[] attestation,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BiConsumer<@VerificationResult Integer, VerificationToken> callback) {
        try {
            AndroidFuture<IVerificationResult> resultCallback = new AndroidFuture<>();
            resultCallback.thenAccept(result -> {
                Log.d(TAG, "verifyAttestation result: " + result.resultCode + " / " + result.token);
                executor.execute(() -> {
                    callback.accept(result.resultCode, result.token);
                });
            });

            mService.verifyAttestation(profile, localBindingType, requirements, attestation,
                    resultCallback);

        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Verifies that {@code token} is a valid token, returning the result contained in valid
     * tokens.
     *
     * <p>This verifies that the token was issued by the platform and thus the system verified
     * attestation data against the specified {@code profile}, {@code localBindingType}, and {@code
     * requirements}. The value returned by this method is the same as the one originally returned
     * when the token was generated. Callers of this method should not trust the provider of the
     * token to also specify the profile, local binding type, or requirements, but instead have
     * their own security requirements about these arguments.
     *
     * <p>This method, in contrast to {@code verifyAttestation}, executes synchronously and only
     * checks that a previous verification succeeded. This allows callers to pass the token to
     * others, including system APIs, without those components needing to re-verify the attestation
     * data, an operation which can take several seconds.
     *
     * <p>When {@code maximumAge} is not specified (null), this method verifies the token was
     * generated in the past hour. Otherwise, it verifies the token was generated between now and
     * {@code maximumAge} ago. The maximum value of {@code maximumAge} is one hour; specifying a
     * duration greater than one hour will result in an {@link IllegalArgumentException}.
     *
     * @param profile          the attestation profile which must be in the token
     * @param localBindingType the local binding type which must be in the token
     * @param requirements     the requirements which must be in the token
     * @param token            the token to be verified
     * @param maximumAge       the maximum age to accept for the token
     */
    @RequiresPermission(Manifest.permission.USE_ATTESTATION_VERIFICATION_SERVICE)
    @CheckResult
    @VerificationResult
    public int verifyToken(
            @NonNull AttestationProfile profile,
            @LocalBindingType int localBindingType,
            @NonNull Bundle requirements,
            @NonNull VerificationToken token,
            @Nullable Duration maximumAge) {
        Duration usedMaximumAge;
        if (maximumAge == null) {
            usedMaximumAge = MAX_TOKEN_AGE;
        } else {
            if (maximumAge.compareTo(MAX_TOKEN_AGE) > 0) {
                throw new IllegalArgumentException(
                        "maximumAge cannot be greater than " + MAX_TOKEN_AGE + "; was "
                                + maximumAge);
            }
            usedMaximumAge = maximumAge;
        }

        try {
            AndroidFuture<Integer> resultCallback = new AndroidFuture<>();
            resultCallback.orTimeout(5, TimeUnit.SECONDS);

            mService.verifyToken(token, new ParcelDuration(usedMaximumAge), resultCallback);
            return resultCallback.get(); // block on result callback
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (Throwable t) {
            throw new RuntimeException("Error verifying token.", t);
        }
    }

    /** @hide */
    public AttestationVerificationManager(
            @NonNull Context context,
            @NonNull IAttestationVerificationManagerService service) {
        this.mContext = context;
        this.mService = service;
    }

    /** @hide */
    @IntDef(
            prefix = {"PROFILE_"},
            value = {
                    PROFILE_UNKNOWN,
                    PROFILE_APP_DEFINED,
                    PROFILE_SELF_TRUSTED,
                    PROFILE_PEER_DEVICE,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AttestationProfileId {
    }

    /**
     * The profile is unknown because it is a profile unknown to this version of the SDK.
     */
    public static final int PROFILE_UNKNOWN = 0;

    /** The profile is defined by an app. */
    public static final int PROFILE_APP_DEFINED = 1;

    /**
     * A system-defined profile which verifies that the attesting environment can create an
     * attestation with the same root certificate as the verifying device with a matching
     * attestation challenge.
     *
     * This profile is intended to be used only for testing.
     */
    public static final int PROFILE_SELF_TRUSTED = 2;

    /**
     * A system-defined profile which verifies that the attesting environment is similar to the
     * current device in terms of security model and security configuration. This category is fairly
     * broad and most securely configured Android devices should qualify, along with a variety of
     * non-Android devices.
     */
    public static final int PROFILE_PEER_DEVICE = 3;

    /** @hide */
    @IntDef(
            prefix = {"TYPE_"},
            value = {
                    TYPE_UNKNOWN,
                    TYPE_APP_DEFINED,
                    TYPE_PUBLIC_KEY,
                    TYPE_CHALLENGE,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LocalBindingType {
    }

    /**
     * The type of the local binding data is unknown because it is a type unknown to this version of
     * the SDK.
     */
    public static final int TYPE_UNKNOWN = 0;

    /**
     * A local binding type for app-defined profiles which use local binding data which does not
     * match any of the existing system-defined types.
     */
    public static final int TYPE_APP_DEFINED = 1;

    /**
     * A local binding type where the attestation is bound to a public key negotiated and
     * authenticated to a public key.
     *
     * <p>When using this type, the {@code requirements} bundle contains values for:
     * <ul>
     *   <li>{@link #PARAM_PUBLIC_KEY}
     *   <li>{@link #PARAM_ID}: identifying the remote environment, optional
     * </ul>
     */
    public static final int TYPE_PUBLIC_KEY = 2;

    /**
     * A local binding type where the attestation is bound to a challenge.
     *
     * <p>When using this type, the {@code requirements} bundle contains values for:
     * <ul>
     *   <li>{@link #PARAM_CHALLENGE}: containing the challenge
     * </ul>
     */
    public static final int TYPE_CHALLENGE = 3;

    /** @hide */
    @IntDef(
            prefix = {"RESULT_"},
            value = {
                    RESULT_UNKNOWN,
                    RESULT_SUCCESS,
                    RESULT_FAILURE,
            })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface VerificationResult {
    }

    /** The result of the verification is unknown because it has a value unknown to this SDK. */
    public static final int RESULT_UNKNOWN = 0;

    /** The result of the verification was successful. */
    public static final int RESULT_SUCCESS = 1;

    /**
     * The result of the attestation verification was failure. The attestation could not be
     * verified.
     */
    public static final int RESULT_FAILURE = 2;

    /**
     * Requirements bundle parameter key for a public key, a byte array.
     *
     * <p>This should contain the encoded key bytes according to the ASN.1 type
     * {@code SubjectPublicKeyInfo} defined in the X.509 standard, the same as a call to {@link
     * java.security.spec.X509EncodedKeySpec#getEncoded()} would produce.
     *
     * @see Bundle#putByteArray(String, byte[])
     */
    public static final String PARAM_PUBLIC_KEY = "localbinding.public_key";

    /** Requirements bundle parameter key for an ID, String. */
    public static final String PARAM_ID = "localbinding.id";

    /** Requirements bundle parameter for a challenge. */
    public static final String PARAM_CHALLENGE = "localbinding.challenge";

    /** Requirements bundle parameter for max patch level diff (int) for a peer device. **/
    public static final String PARAM_MAX_PATCH_LEVEL_DIFF_MONTHS =
            "param_max_patch_level_diff_months";

    /** @hide */
    public static String localBindingTypeToString(@LocalBindingType int localBindingType) {
        final String text;
        switch (localBindingType) {
            case TYPE_UNKNOWN:
                text = "UNKNOWN";
                break;

            case TYPE_APP_DEFINED:
                text = "APP_DEFINED";
                break;

            case TYPE_PUBLIC_KEY:
                text = "PUBLIC_KEY";
                break;

            case TYPE_CHALLENGE:
                text = "CHALLENGE";
                break;

            default:
                return Integer.toString(localBindingType);
        }
        return text + "(" + localBindingType + ")";
    }

    /** @hide */
    public static String verificationResultCodeToString(@VerificationResult int resultCode) {
        final String text;
        switch (resultCode) {
            case RESULT_UNKNOWN:
                text = "UNKNOWN";
                break;

            case RESULT_SUCCESS:
                text = "SUCCESS";
                break;

            case RESULT_FAILURE:
                text = "FAILURE";
                break;

            default:
                return Integer.toString(resultCode);
        }
        return text + "(" + resultCode + ")";
    }
}
