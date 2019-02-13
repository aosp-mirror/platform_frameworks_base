/*
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

package com.android.server.devicepolicy;

import android.app.admin.DevicePolicyManager.InstallSystemUpdateCallback;
import android.app.admin.StartInstallingUpdateCallback;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RecoverySystem;
import android.util.Log;

import java.io.IOException;

/**
 * Used for installing an update for <a href="https://source.android.com/devices/tech/ota/nonab">non
 * AB</a> devices.
 */
class NonAbUpdateInstaller extends UpdateInstaller {
    NonAbUpdateInstaller(Context context,
            ParcelFileDescriptor updateFileDescriptor,
            StartInstallingUpdateCallback callback, DevicePolicyManagerService.Injector injector,
            DevicePolicyConstants constants) {
        super(context, updateFileDescriptor, callback, injector, constants);
    }

    @Override
    public void installUpdateInThread() {
        try {
            RecoverySystem.installPackage(mContext, mCopiedUpdateFile);
            notifyCallbackOnSuccess();
        } catch (IOException e) {
            Log.w(TAG, "IO error while trying to install non AB update.", e);
            notifyCallbackOnError(
                    InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN,
                    Log.getStackTraceString(e));
        }
    }
}
