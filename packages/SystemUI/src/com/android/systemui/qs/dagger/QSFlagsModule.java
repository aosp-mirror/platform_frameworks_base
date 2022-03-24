/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.dagger;

import android.content.Context;
import android.hardware.display.ColorDisplayManager;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.util.settings.GlobalSettings;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

@Module
public interface QSFlagsModule {

    String RBC_AVAILABLE = "rbc_available";
    String PM_LITE_ENABLED = "pm_lite";
    String PM_LITE_SETTING = "sysui_pm_lite";
    int PM_LITE_SETTING_DEFAULT = 1;

    /** */
    @Provides
    @SysUISingleton
    @Named(RBC_AVAILABLE)
    static boolean isReduceBrightColorsAvailable(Context context) {
        return ColorDisplayManager.isReduceBrightColorsAvailable(context);
    }

    @Provides
    @SysUISingleton
    @Named(PM_LITE_ENABLED)
    static boolean isPMLiteEnabled(FeatureFlags featureFlags, GlobalSettings globalSettings) {
        return featureFlags.isEnabled(Flags.POWER_MENU_LITE)
                && globalSettings.getInt(PM_LITE_SETTING, PM_LITE_SETTING_DEFAULT) != 0;
    }
}
