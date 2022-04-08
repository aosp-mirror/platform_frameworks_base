/**
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.biometrics.fingerprint;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.fingerprint.Fingerprint;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.biometrics.BiometricUtils;

import java.util.List;

/**
 * Utility class for dealing with fingerprints and fingerprint settings.
 */
public class FingerprintUtils implements BiometricUtils {

    private static final Object sInstanceLock = new Object();
    private static FingerprintUtils sInstance;

    @GuardedBy("this")
    private final SparseArray<FingerprintUserState> mUsers = new SparseArray<>();

    public static FingerprintUtils getInstance() {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                sInstance = new FingerprintUtils();
            }
        }
        return sInstance;
    }

    private FingerprintUtils() {
    }

    @Override
    public List<Fingerprint> getBiometricsForUser(Context ctx, int userId) {
        return getStateForUser(ctx, userId).getBiometrics();
    }

    @Override
    public void addBiometricForUser(Context context, int userId,
            BiometricAuthenticator.Identifier identifier) {
        getStateForUser(context, userId).addBiometric(identifier);
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
            FingerprintUserState state = mUsers.get(userId);
            if (state == null) {
                state = new FingerprintUserState(ctx, userId);
                mUsers.put(userId, state);
            }
            return state;
        }
    }
}

