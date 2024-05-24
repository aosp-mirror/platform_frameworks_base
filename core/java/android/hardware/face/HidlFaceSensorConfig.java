/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.face;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.common.CommonProps;
import android.hardware.biometrics.common.SensorStrength;
import android.hardware.biometrics.face.SensorProps;

import com.android.internal.R;

/**
 * Parse HIDL face sensor config and map it to SensorProps.aidl to match AIDL.
 * See core/res/res/values/config.xml config_biometric_sensors
 * @hide
 */
public final class HidlFaceSensorConfig extends SensorProps {
    private int mSensorId;
    private int mModality;
    private int mStrength;

    /**
     * Parse through the config string and map it to SensorProps.aidl.
     * @throws IllegalArgumentException when config string has unexpected format
     */
    public void parse(@NonNull String config, @NonNull Context context)
            throws IllegalArgumentException {
        final String[] elems = config.split(":");
        if (elems.length < 3) {
            throw new IllegalArgumentException();
        }
        mSensorId = Integer.parseInt(elems[0]);
        mModality = Integer.parseInt(elems[1]);
        mStrength = Integer.parseInt(elems[2]);
        mapHidlToAidlFaceSensorConfigurations(context);
    }

    @BiometricAuthenticator.Modality
    public int getModality() {
        return mModality;
    }

    private void mapHidlToAidlFaceSensorConfigurations(@NonNull Context context) {
        commonProps = new CommonProps();
        commonProps.sensorId = mSensorId;
        commonProps.sensorStrength = authenticatorStrengthToPropertyStrength(mStrength);
        halControlsPreview = context.getResources().getBoolean(
                R.bool.config_faceAuthSupportsSelfIllumination);
        commonProps.maxEnrollmentsPerUser = context.getResources().getInteger(
                R.integer.config_faceMaxTemplatesPerUser);
        commonProps.componentInfo = null;
        supportsDetectInteraction = false;
    }

    private byte authenticatorStrengthToPropertyStrength(
            @BiometricManager.Authenticators.Types int strength) {
        switch (strength) {
            case BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE:
                return SensorStrength.CONVENIENCE;
            case BiometricManager.Authenticators.BIOMETRIC_WEAK:
                return SensorStrength.WEAK;
            case BiometricManager.Authenticators.BIOMETRIC_STRONG:
                return SensorStrength.STRONG;
            default:
                throw new IllegalArgumentException("Unknown strength: " + strength);
        }
    }
}
