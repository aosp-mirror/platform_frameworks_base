/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.v2.ui;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

public class UninstallLaunch extends FragmentActivity{

    public static final String EXTRA_CALLING_PKG_UID =
        UninstallLaunch.class.getPackageName() + ".callingPkgUid";
    public static final String EXTRA_CALLING_ACTIVITY_NAME =
        UninstallLaunch.class.getPackageName() + ".callingActivityName";
    public static final String TAG = UninstallLaunch.class.getSimpleName();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        // Never restore any state, esp. never create any fragments. The data in the fragment might
        // be stale, if e.g. the app was uninstalled while the activity was destroyed.
        super.onCreate(null);
    }
}
