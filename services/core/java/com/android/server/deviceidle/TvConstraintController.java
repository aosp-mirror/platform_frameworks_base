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

package com.android.server.deviceidle;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;

import com.android.server.DeviceIdleController;
import com.android.server.LocalServices;

/**
 * Device idle constraints for television devices.
 *
 * <p>Televisions are devices with {@code FEATURE_LEANBACK_ONLY}. Other devices might support
 * some kind of leanback mode but they should not follow the same rules for idle state.
 */
public class TvConstraintController implements ConstraintController {
    private final Context mContext;
    private final Handler mHandler;
    private final DeviceIdleController.LocalService mDeviceIdleService;

    @Nullable
    private final BluetoothConstraint mBluetoothConstraint;

    public TvConstraintController(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mDeviceIdleService = LocalServices.getService(DeviceIdleController.LocalService.class);

        final PackageManager pm = context.getPackageManager();
        mBluetoothConstraint = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
                ? new BluetoothConstraint(mContext, mHandler, mDeviceIdleService)
                : null;
    }

    @Override
    public void start() {
        if (mBluetoothConstraint != null) {
            mDeviceIdleService.registerDeviceIdleConstraint(
                    mBluetoothConstraint, "bluetooth", IDeviceIdleConstraint.SENSING_OR_ABOVE);
        }
    }

    @Override
    public void stop() {
        if (mBluetoothConstraint != null) {
            mDeviceIdleService.unregisterDeviceIdleConstraint(mBluetoothConstraint);
        }
    }
}
