/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_CREDENTIAL;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_IRIS;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_NONE;

import android.annotation.IntDef;
import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Pair;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Class representing the calling client's request. Additionally, derives/calculates
 * preliminary info that would be useful in helping serve this request. Note that generating
 * the PreAuthInfo should not change any sensor state.
 */
class PreAuthInfo {
    private static final String TAG = "BiometricService/PreAuthInfo";

    static final int AUTHENTICATOR_OK = 1;
    static final int BIOMETRIC_NO_HARDWARE = 2;
    static final int BIOMETRIC_DISABLED_BY_DEVICE_POLICY = 3;
    static final int BIOMETRIC_INSUFFICIENT_STRENGTH = 4;
    static final int BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE = 5;
    static final int BIOMETRIC_HARDWARE_NOT_DETECTED = 6;
    static final int BIOMETRIC_NOT_ENROLLED = 7;
    static final int BIOMETRIC_NOT_ENABLED_FOR_APPS = 8;
    static final int CREDENTIAL_NOT_ENROLLED = 9;
    @IntDef({AUTHENTICATOR_OK,
            BIOMETRIC_NO_HARDWARE,
            BIOMETRIC_DISABLED_BY_DEVICE_POLICY,
            BIOMETRIC_INSUFFICIENT_STRENGTH,
            BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE,
            BIOMETRIC_HARDWARE_NOT_DETECTED,
            BIOMETRIC_NOT_ENROLLED,
            BIOMETRIC_NOT_ENABLED_FOR_APPS,
            CREDENTIAL_NOT_ENROLLED})
    @Retention(RetentionPolicy.SOURCE)
    @interface AuthenticatorStatus {}

    private final boolean mBiometricRequested;
    private final int mBiometricStrengthRequested;

    final boolean credentialRequested;
    // Sensors that can be used for this request (e.g. strong enough, enrolled, enabled).
    final List<BiometricSensor> eligibleSensors;
    // Sensors that cannot be used for this request. Pair<BiometricSensor, AuthenticatorStatus>
    final List<Pair<BiometricSensor, Integer>> ineligibleSensors;
    final boolean credentialAvailable;

    static PreAuthInfo create(ITrustManager trustManager,
            DevicePolicyManager devicePolicyManager,
            BiometricService.SettingObserver settingObserver,
            List<BiometricSensor> sensors,
            int userId, Bundle bundle, String opPackageName, boolean checkDevicePolicyManager)
            throws RemoteException {
        final boolean biometricRequested = Utils.isBiometricRequested(bundle);
        final int requestedStrength = Utils.getPublicBiometricStrength(bundle);
        final boolean credentialRequested = Utils.isCredentialRequested(bundle);

        final boolean credentialAvailable = trustManager.isDeviceSecure(userId);

        // Assuming that biometric authenticators are listed in priority-order, the rest of this
        // function will attempt to find the first authenticator that's as strong or stronger than
        // the requested strength, available, enrolled, and enabled. The tricky part is returning
        // the correct error. Error strings that are modality-specific should also respect the
        // priority-order.

        final List<BiometricSensor> eligibleSensors = new ArrayList<>();
        final List<Pair<BiometricSensor, Integer>> ineligibleSensors = new ArrayList<>();

        if (biometricRequested) {
            for (BiometricSensor sensor : sensors) {

                @AuthenticatorStatus int status = getStatusForBiometricAuthenticator(
                        devicePolicyManager, settingObserver, sensor, userId, opPackageName,
                        checkDevicePolicyManager, requestedStrength);

                Slog.d(TAG, "Package: " + opPackageName
                        + " Sensor ID: " + sensor.id
                        + " Modality: " + sensor.modality
                        + " Status: " + status);

                if (status == AUTHENTICATOR_OK) {
                    eligibleSensors.add(sensor);
                } else {
                    ineligibleSensors.add(new Pair<>(sensor, status));
                }
            }
        }

        return new PreAuthInfo(biometricRequested, requestedStrength, credentialRequested,
                eligibleSensors, ineligibleSensors, credentialAvailable);
    }

    /**
     * Returns the status of the authenticator, with errors returned in a specific priority order.
     * For example, {@link #BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE} is only returned
     * if it has enrollments, and is enabled for apps.
     *
     * @return @AuthenticatorStatus
     */
    private static @AuthenticatorStatus int getStatusForBiometricAuthenticator(
            DevicePolicyManager devicePolicyManager,
            BiometricService.SettingObserver settingObserver,
            BiometricSensor sensor, int userId, String opPackageName,
            boolean checkDevicePolicyManager, int requestedStrength) {

        final boolean wasStrongEnough =
                Utils.isAtLeastStrength(sensor.oemStrength, requestedStrength);
        final boolean isStrongEnough =
                Utils.isAtLeastStrength(sensor.getCurrentStrength(), requestedStrength);

        if (wasStrongEnough && !isStrongEnough) {
            return BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE;
        } else if (!wasStrongEnough) {
            return BIOMETRIC_INSUFFICIENT_STRENGTH;
        }

        try {
            if (!sensor.impl.isHardwareDetected(opPackageName)) {
                return BIOMETRIC_HARDWARE_NOT_DETECTED;
            }

            if (!sensor.impl.hasEnrolledTemplates(userId, opPackageName)) {
                return BIOMETRIC_NOT_ENROLLED;
            }
        } catch (RemoteException e) {
            return BIOMETRIC_HARDWARE_NOT_DETECTED;
        }

        if (!isEnabledForApp(settingObserver, sensor.modality, userId)) {
            return BIOMETRIC_NOT_ENABLED_FOR_APPS;
        }

        if (checkDevicePolicyManager) {
            if (isBiometricDisabledByDevicePolicy(devicePolicyManager, sensor.modality, userId)) {
                return BIOMETRIC_DISABLED_BY_DEVICE_POLICY;
            }
        }

        return AUTHENTICATOR_OK;
    }

    private static boolean isEnabledForApp(BiometricService.SettingObserver settingObserver,
            @BiometricAuthenticator.Modality int modality, int userId) {
        switch (modality) {
            case TYPE_FINGERPRINT:
                return true;
            case TYPE_IRIS:
                return true;
            case TYPE_FACE:
                return settingObserver.getFaceEnabledForApps(userId);
            default:
                Slog.w(TAG, "Unsupported modality: " + modality);
                return false;
        }
    }

    private static boolean isBiometricDisabledByDevicePolicy(
            DevicePolicyManager devicePolicyManager, @BiometricAuthenticator.Modality int modality,
            int effectiveUserId) {
        final int biometricToCheck = mapModalityToDevicePolicyType(modality);
        if (biometricToCheck == DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE) {
            throw new IllegalStateException("Modality unknown to devicePolicyManager: " + modality);
        }
        final int devicePolicyDisabledFeatures =
                devicePolicyManager.getKeyguardDisabledFeatures(null, effectiveUserId);
        final boolean isBiometricDisabled =
                (biometricToCheck & devicePolicyDisabledFeatures) != 0;
        Slog.w(TAG, "isBiometricDisabledByDevicePolicy(" + modality + "," + effectiveUserId
                + ")=" + isBiometricDisabled);
        return isBiometricDisabled;
    }

    /**
     * @param modality one of {@link BiometricAuthenticator#TYPE_FINGERPRINT},
     * {@link BiometricAuthenticator#TYPE_IRIS} or {@link BiometricAuthenticator#TYPE_FACE}
     * @return
     */
    private static int mapModalityToDevicePolicyType(int modality) {
        switch (modality) {
            case TYPE_FINGERPRINT:
                return DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT;
            case TYPE_IRIS:
                return DevicePolicyManager.KEYGUARD_DISABLE_IRIS;
            case TYPE_FACE:
                return DevicePolicyManager.KEYGUARD_DISABLE_FACE;
            default:
                Slog.e(TAG, "Error modality=" + modality);
                return DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE;
        }
    }

    private PreAuthInfo(boolean biometricRequested, int biometricStrengthRequested,
            boolean credentialRequested, List<BiometricSensor> eligibleSensors,
            List<Pair<BiometricSensor, Integer>> ineligibleSensors, boolean credentialAvailable) {
        mBiometricRequested = biometricRequested;
        mBiometricStrengthRequested = biometricStrengthRequested;
        this.credentialRequested = credentialRequested;

        this.eligibleSensors = eligibleSensors;
        this.ineligibleSensors = ineligibleSensors;
        this.credentialAvailable = credentialAvailable;
    }

    /**
     * With {@link PreAuthInfo} generated with the requested authenticators from the public API
     * surface, combined with the actual sensor/credential and user/system settings, calculate the
     * internal {@link AuthenticatorStatus} that should be returned to the client. Note that this
     * will need to be converted into the public API constant.
     * @return Pair<Modality, Error> with error being the internal {@link AuthenticatorStatus} code
     */
    private Pair<Integer, Integer> getInternalStatus() {
        @AuthenticatorStatus final int status;
        @BiometricAuthenticator.Modality int modality = TYPE_NONE;
        if (mBiometricRequested && credentialRequested) {
            if (credentialAvailable || !eligibleSensors.isEmpty()) {
                status = AUTHENTICATOR_OK;
                if (credentialAvailable) {
                    modality |= TYPE_CREDENTIAL;
                }
                for (BiometricSensor sensor : eligibleSensors) {
                    modality |= sensor.modality;
                }
            } else {
                // Pick the first sensor error if it exists
                if (!ineligibleSensors.isEmpty()) {
                    modality |= ineligibleSensors.get(0).first.modality;
                    status = ineligibleSensors.get(0).second;
                } else {
                    modality |= TYPE_CREDENTIAL;
                    status = CREDENTIAL_NOT_ENROLLED;
                }
            }
        } else if (mBiometricRequested) {
            if (!eligibleSensors.isEmpty()) {
                 status = AUTHENTICATOR_OK;
                 for (BiometricSensor sensor : eligibleSensors) {
                     modality |= sensor.modality;
                 }
            } else {
                // Pick the first sensor error if it exists
                if (!ineligibleSensors.isEmpty()) {
                    modality |= ineligibleSensors.get(0).first.modality;
                    status = ineligibleSensors.get(0).second;
                } else {
                    modality |= TYPE_NONE;
                    status = BIOMETRIC_NO_HARDWARE;
                }
            }
        } else if (credentialRequested) {
            modality |= TYPE_CREDENTIAL;
            status = credentialAvailable ? AUTHENTICATOR_OK : CREDENTIAL_NOT_ENROLLED;
        } else {
            // This should not be possible via the public API surface and is here mainly for
            // "correctness". An exception should have been thrown before getting here.
            Slog.e(TAG, "No authenticators requested");
            status = BIOMETRIC_NO_HARDWARE;
        }

        Slog.d(TAG, "getCanAuthenticateInternal Modality: " + modality
                + " AuthenticatorSatus: " + status);
        return new Pair<>(modality, status);
    }

    /**
     * @return public BiometricManager result for the current request.
     */
    @BiometricManager.BiometricError int getCanAuthenticateResult() {
        // TODO: Convert this directly
        return Utils.biometricConstantsToBiometricManager(
                Utils.authenticatorStatusToBiometricConstant(
                        getInternalStatus().second));
    }

    /**
     * For the given request, generate the appropriate reason why authentication cannot be started.
     * Note that for some errors, modality is intentionally cleared.
     * @return Pair<Modality, Error> with modality being filtered if necessary, and error
     * being one of the public {@link android.hardware.biometrics.BiometricConstants} codes.
     */
    Pair<Integer, Integer> getPreAuthenticateStatus() {
        final Pair<Integer, Integer> internalStatus = getInternalStatus();

        final int publicError = Utils.authenticatorStatusToBiometricConstant(internalStatus.second);
        int modality = internalStatus.first;
        switch (internalStatus.second) {
            case AUTHENTICATOR_OK:
            case BIOMETRIC_NO_HARDWARE:
            case BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE:
            case BIOMETRIC_HARDWARE_NOT_DETECTED:
            case BIOMETRIC_NOT_ENROLLED:
            case CREDENTIAL_NOT_ENROLLED:
                break;

            case BIOMETRIC_DISABLED_BY_DEVICE_POLICY:
            case BIOMETRIC_INSUFFICIENT_STRENGTH:
            case BIOMETRIC_NOT_ENABLED_FOR_APPS:
            default:
                modality = TYPE_NONE;
                break;
        }

        return new Pair<>(modality, publicError);
    }

    /**
     * @return bitmask representing the modalities that are running or could be running for the
     * current session.
     */
    @BiometricAuthenticator.Modality int getEligibleModalities() {
        @BiometricAuthenticator.Modality int modalities = 0;
        for (BiometricSensor sensor : eligibleSensors) {
            modalities |= sensor.modality;
        }

        if (credentialRequested && credentialAvailable) {
            modalities |= TYPE_CREDENTIAL;
        }
        return modalities;
    }

    int numSensorsWaitingForCookie() {
        int numWaiting = 0;
        for (BiometricSensor sensor : eligibleSensors) {
            if (sensor.getSensorState() == BiometricSensor.STATE_WAITING_FOR_COOKIE) {
                Slog.d(TAG, "Sensor ID: " + sensor.id
                        + " Waiting for cookie: " + sensor.getCookie());
                numWaiting++;
            }
        }
        return numWaiting;
    }

    @Override
    public String toString() {
        StringBuilder string = new StringBuilder(
                "BiometricRequested: " + mBiometricRequested
                        + "\nStrengthRequested: " + mBiometricStrengthRequested
                        + "\nCredentialRequested: " + credentialRequested);
        string.append("\nEligible:{");
        for (BiometricSensor sensor: eligibleSensors) {
            string.append(sensor.id).append(" ");
        }
        string.append("}");

        string.append("\nIneligible:{");
        for (Pair<BiometricSensor, Integer> ineligible : ineligibleSensors) {
            string.append(ineligible.first).append(":").append(ineligible.second).append(" ");
        }
        string.append("}");

        string.append("\nCredentialAvailable: ").append(credentialAvailable);
        string.append("\n");
        return string.toString();
    }
}
