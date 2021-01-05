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

package com.android.systemui.statusbar.policy;

import static android.service.SensorPrivacyIndividualEnabledSensorProto.CAMERA;

import android.content.Context;
import android.hardware.SensorPrivacyManager;
import android.util.ArraySet;

import androidx.annotation.NonNull;

import java.util.Set;

import javax.inject.Inject;

public class CameraToggleControllerImpl implements CameraToggleController {

    private final @NonNull Context mContext;
    private final @NonNull SensorPrivacyManager mSensorPrivacyManager;
    private boolean mState;
    private Set<Callback> mCallbacks = new ArraySet<>();

    @Inject
    public CameraToggleControllerImpl(@NonNull Context context) {
        mContext = context;
        mSensorPrivacyManager = context.getSystemService(SensorPrivacyManager.class);
        mSensorPrivacyManager.addSensorPrivacyListener(CAMERA, this::onCameraPrivacyChanged);

        mState = mSensorPrivacyManager.isIndividualSensorPrivacyEnabled(CAMERA);
    }

    @Override
    public boolean isCameraBlocked() {
        return mState;
    }

    @Override
    public void setCameraBlocked(boolean blocked) {
        mSensorPrivacyManager.setIndividualSensorPrivacyForProfileGroup(CAMERA, blocked);
    }

    @Override
    public void addCallback(@NonNull Callback listener) {
        mCallbacks.add(listener);
    }

    @Override
    public void removeCallback(@NonNull Callback listener) {
        mCallbacks.remove(listener);
    }

    private void onCameraPrivacyChanged(boolean state) {
        mState = state;
        for (Callback callback : mCallbacks) {
            callback.onCameraBlockedChanged(mState);
        }
    }
}
