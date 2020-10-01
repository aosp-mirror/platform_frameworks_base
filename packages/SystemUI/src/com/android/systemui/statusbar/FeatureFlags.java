/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.annotation.NonNull;
import android.provider.DeviceConfig;
import android.util.ArrayMap;

import com.android.systemui.dagger.qualifiers.Background;

import java.util.Map;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Class to manage simple DeviceConfig-based feature flags.
 *
 * To enable or disable a flag, run:
 *
 * {@code
 *  $ adb shell device_config put systemui <key> <true|false>
*  }
 *
 * You will probably need to restart systemui for the changes to be picked up:
 *
 * {@code
 *  $ adb shell am restart com.android.systemui
 * }
 */
@Singleton
public class FeatureFlags {
    private final Map<String, Boolean> mCachedDeviceConfigFlags = new ArrayMap<>();

    @Inject
    public FeatureFlags(@Background Executor executor) {
        DeviceConfig.addOnPropertiesChangedListener(
                "systemui",
                executor,
                this::onPropertiesChanged);
    }

    public boolean isNewNotifPipelineEnabled() {
        return getDeviceConfigFlag("notification.newpipeline.enabled", true);
    }

    public boolean isNewNotifPipelineRenderingEnabled() {
        return isNewNotifPipelineEnabled()
                && getDeviceConfigFlag("notification.newpipeline.rendering", false);
    }

    private void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
        synchronized (mCachedDeviceConfigFlags) {
            for (String key : properties.getKeyset()) {
                mCachedDeviceConfigFlags.remove(key);
            }
        }
    }

    private boolean getDeviceConfigFlag(String key, boolean defaultValue) {
        synchronized (mCachedDeviceConfigFlags) {
            Boolean flag = mCachedDeviceConfigFlags.get(key);
            if (flag == null) {
                flag = DeviceConfig.getBoolean("systemui", key, defaultValue);
                mCachedDeviceConfigFlags.put(key, flag);
            }
            return flag;
        }
    }
}
