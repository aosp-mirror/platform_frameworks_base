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

package android.hardware.fingerprint;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Provides the sensor props for fingerprint sensor, if available.
 * @hide
 */

public class FingerprintSensorConfigurations implements Parcelable {
    private static final String TAG = "FingerprintSensorConfigurations";

    private final Map<String, SensorProps[]> mSensorPropsMap;
    private final boolean mResetLockoutRequiresHardwareAuthToken;

    public static final Creator<FingerprintSensorConfigurations> CREATOR =
            new Creator<>() {
                @Override
                public FingerprintSensorConfigurations createFromParcel(Parcel in) {
                    return new FingerprintSensorConfigurations(in);
                }

                @Override
                public FingerprintSensorConfigurations[] newArray(int size) {
                    return new FingerprintSensorConfigurations[size];
                }
            };

    public FingerprintSensorConfigurations(boolean resetLockoutRequiresHardwareAuthToken) {
        mResetLockoutRequiresHardwareAuthToken = resetLockoutRequiresHardwareAuthToken;
        mSensorPropsMap = new HashMap<>();
    }

    /**
     * Process AIDL instances to extract sensor props and add it to the sensor map.
     * @param aidlInstances available face AIDL instances
     * @param getIFingerprint function that provides the daemon for the specific instance
     */
    public void addAidlSensors(@NonNull String[] aidlInstances,
            @NonNull Function<String, IFingerprint> getIFingerprint) {
        for (String aidlInstance : aidlInstances) {
            try {
                final String fqName = IFingerprint.DESCRIPTOR + "/" + aidlInstance;
                final IFingerprint fp = getIFingerprint.apply(fqName);
                if (fp != null) {
                    SensorProps[] props = fp.getSensorProps();
                    mSensorPropsMap.put(aidlInstance, props);
                } else {
                    Log.d(TAG, "IFingerprint null for instance " + aidlInstance);
                }
            } catch (RemoteException e) {
                Log.d(TAG, "Unable to get sensor properties!");
            }
        }
    }

    /**
     * Parse through HIDL configuration and add it to the sensor map.
     */
    public void addHidlSensors(@NonNull String[] hidlConfigStrings,
            @NonNull Context context) {
        final List<HidlFingerprintSensorConfig> hidlFingerprintSensorConfigs = new ArrayList<>();
        for (String hidlConfigString : hidlConfigStrings) {
            final HidlFingerprintSensorConfig hidlFingerprintSensorConfig =
                    new HidlFingerprintSensorConfig();
            try {
                hidlFingerprintSensorConfig.parse(hidlConfigString, context);
            } catch (Exception e) {
                Log.e(TAG, "HIDL sensor configuration format is incorrect.");
                continue;
            }
            if (hidlFingerprintSensorConfig.getModality() == TYPE_FINGERPRINT) {
                hidlFingerprintSensorConfigs.add(hidlFingerprintSensorConfig);
            }
        }
        final String hidlHalInstanceName = "defaultHIDL";
        mSensorPropsMap.put(hidlHalInstanceName,
                hidlFingerprintSensorConfigs.toArray(
                        new HidlFingerprintSensorConfig[hidlFingerprintSensorConfigs.size()]));
    }

    protected FingerprintSensorConfigurations(Parcel in) {
        mResetLockoutRequiresHardwareAuthToken = in.readByte() != 0;
        mSensorPropsMap = in.readHashMap(null /* loader */, String.class, SensorProps[].class);
    }

    /**
     * Returns true if any fingerprint sensors have been added.
     */
    public boolean hasSensorConfigurations() {
        return mSensorPropsMap.size() > 0;
    }

    /**
     * Returns true if there is only a single fingerprint sensor configuration available.
     */
    public boolean isSingleSensorConfigurationPresent() {
        return mSensorPropsMap.size() == 1;
    }

    /**
     * Return sensor props for the given instance. If instance is not available,
     * then null is returned.
     */
    @Nullable
    public Pair<String, SensorProps[]> getSensorPairForInstance(String instance) {
        if (mSensorPropsMap.containsKey(instance)) {
            return new Pair<>(instance, mSensorPropsMap.get(instance));
        }

        return null;
    }

    /**
     * Return the first pair of instance and sensor props, which does not correspond to the given
     * If instance is not available, then null is returned.
     */
    @Nullable
    public Pair<String, SensorProps[]> getSensorPairNotForInstance(String instance) {
        Optional<String> notAVirtualInstance = mSensorPropsMap.keySet().stream().filter(
                (instanceName) -> !instanceName.equals(instance)).findFirst();
        return notAVirtualInstance.map(this::getSensorPairForInstance).orElseGet(
                this::getSensorPair);
    }

    /**
     * Returns the first pair of instance and sensor props that has been added to the map.
     */
    @Nullable
    public Pair<String, SensorProps[]> getSensorPair() {
        Optional<String> optionalInstance = mSensorPropsMap.keySet().stream().findFirst();
        return optionalInstance.map(this::getSensorPairForInstance).orElse(null);

    }

    public boolean getResetLockoutRequiresHardwareAuthToken() {
        return mResetLockoutRequiresHardwareAuthToken;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeByte((byte) (mResetLockoutRequiresHardwareAuthToken ? 1 : 0));
        dest.writeMap(mSensorPropsMap);
    }
}
