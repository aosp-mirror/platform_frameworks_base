/**
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.biometrics.face;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.face.Face;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.biometrics.BiometricUtils;

import java.util.List;

/**
 * Utility class for dealing with faces and face settings.
 */
public class FaceUtils implements BiometricUtils {

    private static final Object sInstanceLock = new Object();
    private static FaceUtils sInstance;

    @GuardedBy("this")
    private final SparseArray<FaceUserState> mUsers = new SparseArray<>();

    public static FaceUtils getInstance() {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                sInstance = new FaceUtils();
            }
        }
        return sInstance;
    }

    private FaceUtils() {
    }

    @Override
    public List<Face> getBiometricsForUser(Context ctx, int userId) {
        return getStateForUser(ctx, userId).getBiometrics();
    }

    @Override
    public void addBiometricForUser(Context ctx, int userId,
            BiometricAuthenticator.Identifier identifier) {
        getStateForUser(ctx, userId).addBiometric(identifier);
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

    private FaceUserState getStateForUser(Context ctx, int userId) {
        synchronized (this) {
            FaceUserState state = mUsers.get(userId);
            if (state == null) {
                state = new FaceUserState(ctx, userId);
                mUsers.put(userId, state);
            }
            return state;
        }
    }
}

