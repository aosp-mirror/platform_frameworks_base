/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.config.appcloning;

import android.content.Context;
import android.provider.DeviceConfig;

import com.android.internal.annotations.GuardedBy;

/**
 * Helper class that holds the flags related to the app_cloning namespace in {@link DeviceConfig}.
 *
 * @hide
 */
public class AppCloningDeviceConfigHelper {

    @GuardedBy("sLock")
    private static AppCloningDeviceConfigHelper sInstance;

    private static final Object sLock = new Object();

    private DeviceConfig.OnPropertiesChangedListener mDeviceConfigChangeListener;

    /**
     * This flag is defined inside {@link DeviceConfig#NAMESPACE_APP_CLONING}. Please check
     * {@link #mEnableAppCloningBuildingBlocks} for details.
     */
    public static final String ENABLE_APP_CLONING_BUILDING_BLOCKS =
            "enable_app_cloning_building_blocks";

    /**
     * Checks whether the support for app-cloning building blocks (like contacts
     * sharing/intent redirection), which are available starting from the U release, is turned on.
     * The default value is true to ensure the features are always enabled going forward.
     *
     * TODO:(b/253449368) Add information about the app-cloning config and mention that the devices
     * that do not support app-cloning should use the app-cloning config to disable all app-cloning
     * features.
     */
    private volatile boolean mEnableAppCloningBuildingBlocks = true;

    private AppCloningDeviceConfigHelper() {}

    /**
     * @hide
     */
    public static AppCloningDeviceConfigHelper getInstance(Context context) {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new AppCloningDeviceConfigHelper();
                sInstance.init(context);
            }
            return sInstance;
        }
    }

    private void init(Context context) {
        initializeDeviceConfigChangeListener();
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_APP_CLONING,
                context.getMainExecutor(),
                mDeviceConfigChangeListener);
    }

    private void initializeDeviceConfigChangeListener() {
        mDeviceConfigChangeListener = properties -> {
            if (!DeviceConfig.NAMESPACE_APP_CLONING.equals(properties.getNamespace())) {
                return;
            }
            for (String name : properties.getKeyset()) {
                if (name == null) {
                    return;
                }
                if (ENABLE_APP_CLONING_BUILDING_BLOCKS.equals(name)) {
                    updateEnableAppCloningBuildingBlocks();
                }
            }
        };
    }

    private void updateEnableAppCloningBuildingBlocks() {
        mEnableAppCloningBuildingBlocks = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_APP_CLONING, ENABLE_APP_CLONING_BUILDING_BLOCKS, true);
    }

    /**
     * Fetch the feature flag to check whether the support for the app-cloning building blocks
     * (like contacts sharing/intent redirection) is enabled on the device.
     * @hide
     */
    public boolean getEnableAppCloningBuildingBlocks() {
        return mEnableAppCloningBuildingBlocks;
    }
}
