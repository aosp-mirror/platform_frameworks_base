/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;

import com.android.server.policy.WindowManagerPolicy.StartingSurface;

/**
 * Represents starting data for splash screens, i.e. "traditional" starting windows.
 */
class SplashScreenStartingData extends StartingData {

    private final String mPkg;
    private final int mTheme;
    private final CompatibilityInfo mCompatInfo;
    private final CharSequence mNonLocalizedLabel;
    private final int mLabelRes;
    private final int mIcon;
    private final int mLogo;
    private final int mWindowFlags;
    private final Configuration mMergedOverrideConfiguration;

    SplashScreenStartingData(WindowManagerService service, String pkg, int theme,
            CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes, int icon,
            int logo, int windowFlags, Configuration mergedOverrideConfiguration) {
        super(service);
        mPkg = pkg;
        mTheme = theme;
        mCompatInfo = compatInfo;
        mNonLocalizedLabel = nonLocalizedLabel;
        mLabelRes = labelRes;
        mIcon = icon;
        mLogo = logo;
        mWindowFlags = windowFlags;
        mMergedOverrideConfiguration = mergedOverrideConfiguration;
    }

    @Override
    StartingSurface createStartingSurface(ActivityRecord activity) {
        return mService.mPolicy.addSplashScreen(activity.token, activity.mUserId, mPkg, mTheme,
                mCompatInfo, mNonLocalizedLabel, mLabelRes, mIcon, mLogo, mWindowFlags,
                mMergedOverrideConfiguration, activity.getDisplayContent().getDisplayId());
    }
}
