/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static com.android.systemui.statusbar.policy.dagger.StatusBarPolicyModule.DEVICE_STATE_ROTATION_LOCK_DEFAULTS;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;

import androidx.annotation.NonNull;

import com.android.internal.view.RotationPolicy.RotationPolicyListener;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.util.wrapper.RotationPolicyWrapper;

import java.util.concurrent.CopyOnWriteArrayList;

import javax.inject.Inject;
import javax.inject.Named;

/** Platform implementation of the rotation lock controller. **/
@SysUISingleton
public final class RotationLockControllerImpl implements RotationLockController {

    private final CopyOnWriteArrayList<RotationLockControllerCallback> mCallbacks =
            new CopyOnWriteArrayList<>();

    private final RotationPolicyListener mRotationPolicyListener =
            new RotationPolicyListener() {
        @Override
        public void onChange() {
            notifyChanged();
        }
    };

    private final RotationPolicyWrapper mRotationPolicy;
    private final DeviceStateRotationLockSettingController
            mDeviceStateRotationLockSettingController;
    private final boolean mIsPerDeviceStateRotationLockEnabled;

    @Inject
    public RotationLockControllerImpl(
            RotationPolicyWrapper rotationPolicyWrapper,
            DeviceStateRotationLockSettingController deviceStateRotationLockSettingController,
            @Named(DEVICE_STATE_ROTATION_LOCK_DEFAULTS) String[] deviceStateRotationLockDefaults
    ) {
        mRotationPolicy = rotationPolicyWrapper;
        mDeviceStateRotationLockSettingController = deviceStateRotationLockSettingController;
        mIsPerDeviceStateRotationLockEnabled = deviceStateRotationLockDefaults.length > 0;
        if (mIsPerDeviceStateRotationLockEnabled) {
            mCallbacks.add(mDeviceStateRotationLockSettingController);
        }

        setListening(true);
    }

    @Override
    public void addCallback(@NonNull RotationLockControllerCallback callback) {
        mCallbacks.add(callback);
        notifyChanged(callback);
    }

    @Override
    public void removeCallback(@NonNull RotationLockControllerCallback callback) {
        mCallbacks.remove(callback);
    }

    public int getRotationLockOrientation() {
        return mRotationPolicy.getRotationLockOrientation();
    }

    public boolean isRotationLocked() {
        return mRotationPolicy.isRotationLocked();
    }

    public boolean isCameraRotationEnabled() {
        return mRotationPolicy.isCameraRotationEnabled();
    }

    public void setRotationLocked(boolean locked, String caller) {
        mRotationPolicy.setRotationLock(locked, caller);
    }

    public void setRotationLockedAtAngle(boolean locked, int rotation, String caller) {
        mRotationPolicy.setRotationLockAtAngle(locked, rotation, caller);
    }

    public boolean isRotationLockAffordanceVisible() {
        return mRotationPolicy.isRotationLockToggleVisible();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mRotationPolicy.registerRotationPolicyListener(mRotationPolicyListener,
                    UserHandle.USER_ALL);
        } else {
            mRotationPolicy.unregisterRotationPolicyListener(mRotationPolicyListener);
        }
        if (mIsPerDeviceStateRotationLockEnabled) {
            mDeviceStateRotationLockSettingController.setListening(listening);
        }
    }

    private void notifyChanged() {
        for (RotationLockControllerCallback callback : mCallbacks) {
            notifyChanged(callback);
        }
    }

    private void notifyChanged(RotationLockControllerCallback callback) {
        callback.onRotationLockStateChanged(mRotationPolicy.isRotationLocked(),
                mRotationPolicy.isRotationLockToggleVisible());
    }

    public static boolean hasSufficientPermission(Context context) {
        final PackageManager packageManager = context.getPackageManager();
        final String rotationPackage = packageManager.getRotationResolverPackageName();
        return rotationPackage != null && packageManager.checkPermission(
                Manifest.permission.CAMERA, rotationPackage) == PackageManager.PERMISSION_GRANTED;
    }
}
