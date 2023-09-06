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

package com.android.systemui.accessibility.utils;

import static com.android.systemui.flags.SetFlagsRuleExtensionsKt.setFlagDefault;

import android.platform.test.flag.junit.SetFlagsRule;

import com.android.systemui.Flags;

public class FlagUtils {
    /**
     * Populates a setFlagsRule with every SystemUI a11y feature flag.
     * This function should be updated when new flags are added.
     *
     * @param setFlagsRule set flags rule from the test environment.
     */
    public static void setFlagDefaults(SetFlagsRule setFlagsRule) {
        setFlagDefault(setFlagsRule, Flags.FLAG_FLOATING_MENU_OVERLAPS_NAV_BARS_FLAG);
        setFlagDefault(setFlagsRule, Flags.FLAG_FLOATING_MENU_IME_DISPLACEMENT_ANIMATION);
    }
}
