/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.permission;

import static android.provider.DeviceConfig.NAMESPACE_PRIVACY;

import android.content.Context;
import android.provider.DeviceConfig;

import com.android.server.appop.AppOpsCheckingServiceInterface;
import com.android.server.appop.AppOpsServiceTestingShim;

import java.util.function.Supplier;

/**
 * A factory which will select one or both implementations of a PermissionManagerServiceInterface or
 * AppOpsCheckingServiceInterface, based upon either a DeviceConfig value, or a hard coded config.
 */
public class AccessTestingShimFactory {

    private static final int RUN_OLD_SUBSYSTEM = 0;
    private static final int RUN_NEW_SUBSYSTEM = 1;
    private static final int RUN_BOTH_SUBSYSTEMS = 2;
    public static final String DEVICE_CONFIG_SETTING = "selected_access_subsystem";

    /**
     * Get the PermissionManagerServiceInterface, based upon the current config state.
     */
    public static PermissionManagerServiceInterface getPms(Context context,
            Supplier<PermissionManagerServiceInterface> oldImpl,
            Supplier<PermissionManagerServiceInterface> newImpl) {
        int selectedSystem = DeviceConfig.getInt(NAMESPACE_PRIVACY,
                DEVICE_CONFIG_SETTING, RUN_OLD_SUBSYSTEM);
        switch (selectedSystem) {
            case RUN_BOTH_SUBSYSTEMS:
                return new PermissionManagerServiceTestingShim(oldImpl.get(), newImpl.get());
            case RUN_NEW_SUBSYSTEM:
                return newImpl.get();
            default:
                return oldImpl.get();
        }
    }

    /**
     * Get the AppOpsCheckingServiceInterface, based upon the current config state.
     */
    public static AppOpsCheckingServiceInterface getAos(Context context,
            Supplier<AppOpsCheckingServiceInterface> oldImpl,
            Supplier<AppOpsCheckingServiceInterface> newImpl) {
        int selectedSystem = DeviceConfig.getInt(NAMESPACE_PRIVACY,
                DEVICE_CONFIG_SETTING, RUN_OLD_SUBSYSTEM);
        switch (selectedSystem) {
            case RUN_BOTH_SUBSYSTEMS:
                return new AppOpsServiceTestingShim(oldImpl.get(), newImpl.get());
            case RUN_NEW_SUBSYSTEM:
                return newImpl.get();
            default:
                return oldImpl.get();
        }
    }
}
