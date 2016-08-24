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
 * limitations under the License.
 */

package com.android.systemui.tv.pip;

import android.content.pm.PackageManager;
import android.content.res.Configuration;

import com.android.systemui.SystemUI;

import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;

/**
 * Controls the picture-in-picture window for TV devices.
 */
public class PipUI extends SystemUI {
    private boolean mSupportPip;

    @Override
    public void start() {
        PackageManager pm = mContext.getPackageManager();
        mSupportPip = pm.hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)
                && pm.hasSystemFeature(FEATURE_LEANBACK);
        if (!mSupportPip) {
            return;
        }
        PipManager pipManager = PipManager.getInstance();
        pipManager.initialize(mContext);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!mSupportPip) {
            return;
        }
        PipManager.getInstance().onConfigurationChanged();
    }
}
