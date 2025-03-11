/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.security.advancedprotection.features;

import static android.security.advancedprotection.AdvancedProtectionManager.ADVANCED_PROTECTION_SYSTEM_ENTITY;
import static android.security.advancedprotection.AdvancedProtectionManager.FEATURE_ID_ENABLE_MTE;

import android.annotation.NonNull;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.SystemProperties;
import android.security.advancedprotection.AdvancedProtectionFeature;
import android.util.Slog;

/** @hide */
public final class MemoryTaggingExtensionHook
        extends AdvancedProtectionHook {
    private static final String TAG = "AdvancedProtectionMTE";
    private static final String MTE_DPM_SYSTEM_PROPERTY =
            "ro.arm64.memtag.bootctl_device_policy_manager";
    private static final String MTE_SETTINGS_SYSTEM_PROPERTY =
            "ro.arm64.memtag.bootctl_settings_toggle";

    private final AdvancedProtectionFeature mFeature = new AdvancedProtectionFeature(
            FEATURE_ID_ENABLE_MTE);
    private final DevicePolicyManager mDevicePolicyManager;

    public MemoryTaggingExtensionHook(@NonNull Context context,
            boolean enabled) {
        super(context, enabled);
        mDevicePolicyManager = context.getSystemService(DevicePolicyManager.class);
        onAdvancedProtectionChanged(enabled);
    }

    @NonNull
    @Override
    public AdvancedProtectionFeature getFeature() {
        return mFeature;
    }

    @Override
    public boolean isAvailable() {
        return SystemProperties.getBoolean(MTE_DPM_SYSTEM_PROPERTY,
                SystemProperties.getBoolean(MTE_SETTINGS_SYSTEM_PROPERTY, false));
    }

    @Override
    public void onAdvancedProtectionChanged(boolean enabled) {
        if (!isAvailable()) {
            Slog.i(TAG, "MTE unavailable on device, skipping.");
            return;
        }
        final int mtePolicy;
        if (enabled) {
            mtePolicy = DevicePolicyManager.MTE_ENABLED;
        } else {
            mtePolicy = DevicePolicyManager.MTE_NOT_CONTROLLED_BY_POLICY;
        }

        Slog.d(TAG, "Setting MTE state to " + mtePolicy);
        try {
            mDevicePolicyManager.setMtePolicy(ADVANCED_PROTECTION_SYSTEM_ENTITY, mtePolicy);
        } catch (UnsupportedOperationException e) {
            Slog.i(TAG, "Setting MTE policy unsupported", e);
        }
    }
}
