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

package com.android.server.biometrics.sensors.face;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.face.Face;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.biometrics.sensors.BiometricUtils;

import java.util.List;

/**
 * Utility class for dealing with faces and face settings.
 */
public class FaceUtils implements BiometricUtils<Face> {

    private static final Object sInstanceLock = new Object();
    // Map<SensorId, FaceUtils>
    private static SparseArray<FaceUtils> sInstances;
    private static final String LEGACY_FACE_FILE = "settings_face.xml";

    @GuardedBy("this")
    private final SparseArray<FaceUserState> mUserStates;
    private final String mFileName;

    public static FaceUtils getInstance(int sensorId) {
        // Specify a null fileName to use an auto-generated sensorId-specific filename.
        return getInstance(sensorId, null /* fileName */);
    }

    /**
     * Retrieves an instance for the specified sensorId. If the fileName is null, a default
     * filename (e.g. settings_face_<sensorId>.xml will be generated.
     *
     * Specifying an explicit fileName allows for backward compatibility with legacy devices,
     * where everything is stored in settings_face.xml.
     */
    private static FaceUtils getInstance(int sensorId, @Nullable String fileName) {
        final FaceUtils utils;
        synchronized (sInstanceLock) {
            if (sInstances == null) {
                sInstances = new SparseArray<>();
            }
            if (sInstances.get(sensorId) == null) {
                if (fileName == null) {
                    fileName = "settings_face_" + sensorId + ".xml";
                }
                sInstances.put(sensorId, new FaceUtils(fileName));
            }
            utils = sInstances.get(sensorId);
        }
        return utils;
    }

    /**
     * Legacy getter for {@link android.hardware.biometrics.face.V1_0} and its extended subclasses.
     * Framework-side cache is always stored in the same file, regardless of sensorId.
     */
    public static FaceUtils getLegacyInstance(int sensorId) {
        // Note that sensorId for legacy services can be hard-coded to 0 since it's only used
        // to index into the sensor states map.
        return getInstance(sensorId, LEGACY_FACE_FILE);
    }

    private FaceUtils(String fileName) {
        mUserStates = new SparseArray<>();
        mFileName = fileName;
    }

    @Override
    public List<Face> getBiometricsForUser(Context ctx, int userId) {
        return getStateForUser(ctx, userId).getBiometrics();
    }

    @Override
    public void addBiometricForUser(Context ctx, int userId, Face face) {
        getStateForUser(ctx, userId).addBiometric(face);
    }

    @Override
    public void removeBiometricForUser(Context ctx, int userId, int faceId) {
        getStateForUser(ctx, userId).removeBiometric(faceId);
    }

    @Override
    public void renameBiometricForUser(Context ctx, int userId, int faceId, CharSequence name) {
        if (TextUtils.isEmpty(name)) {
            // Don't do the rename if it's empty
            return;
        }
        getStateForUser(ctx, userId).renameBiometric(faceId, name);
    }

    @Override
    public CharSequence getUniqueName(Context context, int userId) {
        return getStateForUser(context, userId).getUniqueName();
    }

    @Override
    public void setInvalidationInProgress(Context context, int userId, boolean inProgress) {
        getStateForUser(context, userId).setInvalidationInProgress(inProgress);
    }

    @Override
    public boolean isInvalidationInProgress(Context context, int userId) {
        return getStateForUser(context, userId).isInvalidationInProgress();
    }

    private FaceUserState getStateForUser(Context ctx, int userId) {
        synchronized (this) {
            FaceUserState state = mUserStates.get(userId);
            if (state == null) {
                state = new FaceUserState(ctx, userId, mFileName);
                mUserStates.put(userId, state);
            }
            return state;
        }
    }
}

