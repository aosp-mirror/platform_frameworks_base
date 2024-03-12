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

package com.android.systemui.theme;

import android.content.res.Resources;

import com.android.systemui.res.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.concurrency.SysUIConcurrencyModule;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

/** */
@Module(includes = {SysUIConcurrencyModule.class})
public class ThemeModule {
    static final String LAUNCHER_PACKAGE = "theme_launcher_package";
    static final String THEME_PICKER_PACKAGE = "theme_picker_package";

    /** */
    @Provides
    @Named(LAUNCHER_PACKAGE)
    static String provideLauncherPackage(@Main Resources resources) {
        return resources.getString(R.string.launcher_overlayable_package);
    }

    /** */
    @Provides
    @Named(THEME_PICKER_PACKAGE)
    static String provideThemePickerPackage(@Main Resources resources) {
        return resources.getString(R.string.themepicker_overlayable_package);
    }
}
