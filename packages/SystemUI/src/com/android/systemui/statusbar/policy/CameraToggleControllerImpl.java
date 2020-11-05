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

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraToggleManager;
import android.os.Looper;
import android.util.ArraySet;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Set;

import javax.inject.Inject;

public class CameraToggleControllerImpl implements CameraToggleController {

    private final Context mContext;
    private final DumpManager mDumpManager;
    private final CameraToggleManager mCameraToggleManager;

    private boolean mState = true;

    Set<Callback> mCallbacks = new ArraySet<>();

    Set<String> mUsedCameras = new ArraySet<>();

    /**
     */
    @Inject
    public CameraToggleControllerImpl(
            Context context,
            DumpManager dumpManager,
            @Background Looper bgLooper,
            @Main Looper mainLooper) {
        mContext = context;
        mDumpManager = dumpManager;
        mCameraToggleManager = context.getSystemService(CameraToggleManager.class);
        mCameraToggleManager.addCameraToggleChangeListener(this::onCameraChanged);
        mState = mCameraToggleManager.isCameraEnabled();
        mContext.getSystemService(CameraManager.class).registerAvailabilityCallback(
                context.getMainExecutor(), new CameraManager.AvailabilityCallback() {
                    @Override
                    public void onCameraAvailable(@NonNull String cameraId) {
                        mUsedCameras.remove(cameraId);
                        onCameraChanged(mState);
                    }

                    @Override
                    public void onCameraUnavailable(@NonNull String cameraId) {
                        mUsedCameras.add(cameraId);
                        onCameraChanged(mState);
                    }
                });
    }

    @Override
    public boolean isCameraEnabled() {
        return mState;
    }

    @Override
    public void setCameraEnabled(boolean enabled) {
        if (!/*mCameraToggleManager.setCameraEnabled(enabled)*/true) {
            Toast.makeText(mContext, "Can't disable camera while in use", Toast.LENGTH_LONG);
        }
    }

    @Override
    public boolean isCameraAvailable() {
        return false;/*mUsedCameras.isEmpty();*/
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {

    }

    @Override
    public void addCallback(@NonNull Callback listener) {
        mCallbacks.add(listener);
    }

    @Override
    public void removeCallback(@NonNull Callback listener) {
        mCallbacks.remove(listener);
    }

    private void onCameraChanged(boolean state) {
        mState = state;
        for (Callback callback : mCallbacks) {
            callback.onCameraEnabledChanged(state);
        }
    }
}
