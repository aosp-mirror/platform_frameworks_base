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

package com.android.server.wm;

import static android.provider.DeviceConfig.NAMESPACE_WINDOW_MANAGER;

import static com.android.server.wm.ActivityStarter.ASM_RESTRICTIONS;

import android.annotation.NonNull;
import android.app.compat.CompatChanges;
import android.provider.DeviceConfig;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.Executor;

/**
 * Contains utility methods to query whether or not go/activity-security should be enabled
 * asm_start_rules_enabled - Enable rule enforcement in ActivityStarter.java
 * asm_start_rules_toasts_enabled - Show toasts when rules would block from ActivityStarter.java
 * asm_start_rules_exception_list - Comma separated list of packages to exclude from the above
 * 2 rules.
 * TODO(b/258792202) Cleanup once ASM is ready to launch
 */
class ActivitySecurityModelFeatureFlags {
    // TODO(b/230590090): Replace with public documentation once ready
    static final String DOC_LINK = "go/android-asm";

    /** Used to determine which version of the ASM logic was used in logs while we iterate */
    static final int ASM_VERSION = 10;

    private static final String NAMESPACE = NAMESPACE_WINDOW_MANAGER;
    private static final String KEY_ASM_PREFIX = "ActivitySecurity__";
    private static final String KEY_ASM_RESTRICTIONS_ENABLED = KEY_ASM_PREFIX
            + "asm_restrictions_enabled";
    private static final String KEY_ASM_TOASTS_ENABLED = KEY_ASM_PREFIX + "asm_toasts_enabled";

    private static final int VALUE_DISABLE = 0;
    private static final int VALUE_ENABLE_FOR_V = 1;
    private static final int VALUE_ENABLE_FOR_ALL = 2;

    private static final int DEFAULT_VALUE = VALUE_DISABLE;

    private static int sAsmToastsEnabled;
    private static int sAsmRestrictionsEnabled;

    @GuardedBy("ActivityTaskManagerService.mGlobalLock")
    static void initialize(@NonNull Executor executor) {
        updateFromDeviceConfig();
        DeviceConfig.addOnPropertiesChangedListener(NAMESPACE, executor,
                properties -> updateFromDeviceConfig());
    }

    @GuardedBy("ActivityTaskManagerService.mGlobalLock")
    static boolean shouldShowToast(int uid) {
        return sAsmToastsEnabled == VALUE_ENABLE_FOR_ALL
                || (sAsmToastsEnabled == VALUE_ENABLE_FOR_V
                        && CompatChanges.isChangeEnabled(ASM_RESTRICTIONS, uid));
    }

    @GuardedBy("ActivityTaskManagerService.mGlobalLock")
    static boolean shouldRestrictActivitySwitch(int uid) {
        if (android.security.Flags.asmRestrictionsEnabled()) {
            return CompatChanges.isChangeEnabled(ASM_RESTRICTIONS, uid)
                    || asmRestrictionsEnabledForAll();
        }

        return false;
    }

    @GuardedBy("ActivityTaskManagerService.mGlobalLock")
    static boolean asmRestrictionsEnabledForAll() {
        return sAsmRestrictionsEnabled == VALUE_ENABLE_FOR_ALL;
    }

    private static void updateFromDeviceConfig() {
        sAsmToastsEnabled = DeviceConfig.getInt(NAMESPACE, KEY_ASM_TOASTS_ENABLED,
                DEFAULT_VALUE);
        sAsmRestrictionsEnabled = DeviceConfig.getInt(NAMESPACE, KEY_ASM_RESTRICTIONS_ENABLED,
                DEFAULT_VALUE);
    }
}
