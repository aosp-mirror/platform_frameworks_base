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

package com.android.server.biometrics.sensors.fingerprint;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.fingerprint.Fingerprint;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.biometrics.sensors.BiometricUtils;

import java.util.List;

/**
 * Utility class for dealing with fingerprints and fingerprint settings.
 */
public class FingerprintUtils implements BiometricUtils<Fingerprint> {

    private static final Object sInstanceLock = new Object();
    // Map<SensorId, FingerprintUtils>
    private static SparseArray<FingerprintUtils> sInstances;
    private static final String LEGACY_FINGERPRINT_FILE = "settings_fingerprint.xml";

    @GuardedBy("this")
    private final SparseArray<FingerprintUserState> mUserStates;
    private final String mFileName;

    /**
     * Retrieves an instance for the specified sensorId.
     */
    public static FingerprintUtils getInstance(int sensorId) {
        // Specify a null fileName to use an auto-generated sensorId-specific filename.
        return getInstance(sensorId, null /* fileName */);
    }

    /**
     * Retrieves an instance for the specified sensorId. If the fileName is null, a default
     * filename (e.g. settings_fingerprint_<sensorId>.xml will be generated.
     *
     * Specifying an explicit fileName allows for backward compatibility with legacy devices,
     * where everything is stored in settings_fingerprint.xml.
     */
    private static FingerprintUtils getInstance(int sensorId, @Nullable String fileName) {
        final FingerprintUtils utils;
        synchronized (sInstanceLock) {
            if (sInstances == null) {
                sInstances = new SparseArray<>();
            }
            if (sInstances.get(sensorId) == null) {
                if (fileName == null) {
                    fileName = "settings_fingerprint_" + sensorId + ".xml";
                }
                sInstances.put(sensorId, new FingerprintUtils(fileName));
            }
            utils = sInstances.get(sensorId);
        }
        return utils;
    }

    /**
     * Legacy getter for {@link android.hardware.biometrics.fingerprint.V2_1} ands its extended
     * subclasses, which do not support a well defined sensorId from the HAL.
     */
    public static FingerprintUtils getInstance() {
        // Note that sensorId for legacy services can be hard-coded to 0 since it's only used
        // to index into the sensor states map.
        return getInstance(0 /* sensorId */, LEGACY_FINGERPRINT_FILE);
    }

    private FingerprintUtils(String fileName) {
        mUserStates = new SparseArray<>();
        mFileName = fileName;
    }

    @Override
    public List<Fingerprint> getBiometricsForUser(Context ctx, int userId) {
        return getStateForUser(ctx, userId).getBiometrics();
    }

    @Override
    public void addBiometricForUser(Context context, int userId, Fingerprint fingerprint) {
        getStateForUser(context, userId).addBiometric(fingerprint);
    }

    @Override
    public void removeBiometricForUser(Context context, int userId, int fingerId) {
        getStateForUser(context, userId).removeBiometric(fingerId);
    }

    @Override
    public void renameBiometricForUser(Context context, int userId, int fingerId,
            CharSequence name) {
        if (TextUtils.isEmpty(name)) {
            // Don't do the rename if it's empty
            return;
        }
        getStateForUser(context, userId).renameBiometric(fingerId, name);
    }

    @Override
    public CharSequence getUniqueName(Context context, int userId) {
        return getStateForUser(context, userId).getUniqueName();
    }

    private FingerprintUserState getStateForUser(Context ctx, int userId) {
        synchronized (this) {
            FingerprintUserState state = mUserStates.get(userId);
            if (state == null) {
                state = new FingerprintUserState(ctx, userId, mFileName);
                mUserStates.put(userId, state);
            }
            return state;
        }
    }
}

