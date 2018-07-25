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

package com.android.packageinstaller.permission.ui.auto;

import android.app.Activity;
import android.view.WindowManager;

import com.android.packageinstaller.permission.ui.handheld.GrantPermissionsViewHandlerImpl;

/**
 * A {@link com.android.packageinstaller.permission.ui.GrantPermissionsViewHandler} that is
 * specific for the auto use-case. In this case, the permissions dialog needs to be larger to make
 * clicking and reading safer in the car. Otherwise, the UI remains the same.
 *
 * <p>The reason this class extends {@link GrantPermissionsViewHandlerImpl} is so that it can
 * change the window params to allow the dialog's width to be larger.
 */
public class GrantPermissionsAutoViewHandler extends GrantPermissionsViewHandlerImpl {
    public GrantPermissionsAutoViewHandler(Activity activity, String appPackageName) {
        super(activity, appPackageName);
    }

    /**
     * Update the given {@link android.view.WindowManager.LayoutParams} to allow the dialog to take
     * up the entirety of the width.
     */
    @Override
    public void updateWindowAttributes(WindowManager.LayoutParams outLayoutParams) {
        outLayoutParams.width = WindowManager.LayoutParams.MATCH_PARENT;
        outLayoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
    }
}
