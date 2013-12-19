/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.am;

import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Switch;

public final class CompatModeDialog extends Dialog {
    final ActivityManagerService mService;
    final ApplicationInfo mAppInfo;

    final Switch mCompatEnabled;
    final CheckBox mAlwaysShow;
    final View mHint;

    public CompatModeDialog(ActivityManagerService service, Context context,
            ApplicationInfo appInfo) {
        super(context, com.android.internal.R.style.Theme_Holo_Dialog_MinWidth);
        setCancelable(true);
        setCanceledOnTouchOutside(true);
        getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
        getWindow().setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        mService = service;
        mAppInfo = appInfo;

        setContentView(com.android.internal.R.layout.am_compat_mode_dialog);
        mCompatEnabled = (Switch)findViewById(com.android.internal.R.id.compat_checkbox);
        mCompatEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                synchronized (mService) {
                    mService.mCompatModePackages.setPackageScreenCompatModeLocked(
                            mAppInfo.packageName,
                            mCompatEnabled.isChecked() ? ActivityManager.COMPAT_MODE_ENABLED
                                    : ActivityManager.COMPAT_MODE_DISABLED);
                    updateControls();
                }
            }
        });
        mAlwaysShow = (CheckBox)findViewById(com.android.internal.R.id.ask_checkbox);
        mAlwaysShow.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                synchronized (mService) {
                    mService.mCompatModePackages.setPackageAskCompatModeLocked(
                            mAppInfo.packageName, mAlwaysShow.isChecked());
                    updateControls();
                }
            }
        });
        mHint = findViewById(com.android.internal.R.id.reask_hint);

        updateControls();
    }

    void updateControls() {
        synchronized (mService) {
            int mode = mService.mCompatModePackages.computeCompatModeLocked(mAppInfo);
            mCompatEnabled.setChecked(mode == ActivityManager.COMPAT_MODE_ENABLED);
            boolean ask = mService.mCompatModePackages.getPackageAskCompatModeLocked(
                    mAppInfo.packageName);
            mAlwaysShow.setChecked(ask);
            mHint.setVisibility(ask ? View.INVISIBLE : View.VISIBLE);
        }
    }
}
