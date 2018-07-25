/*
* Copyright (C) 2015 The Android Open Source Project
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

package com.android.packageinstaller.permission.ui;

import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;

import com.android.packageinstaller.DeviceUtils;
import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.ui.handheld.ManageStandardPermissionsFragment;
import com.android.packageinstaller.permission.ui.wear.AppPermissionsFragmentWear;

public final class ManagePermissionsActivity extends OverlayTouchActivity {
    private static final String LOG_TAG = "ManagePermissionsActivity";

    public static final String EXTRA_ALL_PERMISSIONS =
            "com.android.packageinstaller.extra.ALL_PERMISSIONS";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (DeviceUtils.isAuto(this)) {
            setTheme(R.style.CarSettingTheme);
        }
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            return;
        }

        Fragment fragment;
        String action = getIntent().getAction();

        switch (action) {
            case Intent.ACTION_MANAGE_PERMISSIONS: {
                if (DeviceUtils.isTelevision(this)) {
                    fragment = com.android.packageinstaller.permission.ui.television
                            .ManagePermissionsFragment.newInstance();
                } else {
                    fragment = ManageStandardPermissionsFragment.newInstance();
                }
            } break;

            case Intent.ACTION_MANAGE_APP_PERMISSIONS: {
                String packageName = getIntent().getStringExtra(Intent.EXTRA_PACKAGE_NAME);
                if (packageName == null) {
                    Log.i(LOG_TAG, "Missing mandatory argument EXTRA_PACKAGE_NAME");
                    finish();
                    return;
                }
                if (DeviceUtils.isAuto(this)) {
                    fragment = com.android.packageinstaller.permission.ui.auto
                            .AppPermissionsFragment.newInstance(packageName);
                } else if (DeviceUtils.isWear(this)) {
                    fragment = AppPermissionsFragmentWear.newInstance(packageName);
                } else if (DeviceUtils.isTelevision(this)) {
                    fragment = com.android.packageinstaller.permission.ui.television
                            .AppPermissionsFragment.newInstance(packageName);
                } else {
                    final boolean allPermissions = getIntent().getBooleanExtra(
                            EXTRA_ALL_PERMISSIONS, false);
                    if (allPermissions) {
                        fragment = com.android.packageinstaller.permission.ui.handheld
                                .AllAppPermissionsFragment.newInstance(packageName);
                    } else {
                        fragment = com.android.packageinstaller.permission.ui.handheld
                                .AppPermissionsFragment.newInstance(packageName);
                    }
                }
            } break;

            case Intent.ACTION_MANAGE_PERMISSION_APPS: {
                String permissionName = getIntent().getStringExtra(Intent.EXTRA_PERMISSION_NAME);
                if (permissionName == null) {
                    Log.i(LOG_TAG, "Missing mandatory argument EXTRA_PERMISSION_NAME");
                    finish();
                    return;
                }
                if (DeviceUtils.isTelevision(this)) {
                    fragment = com.android.packageinstaller.permission.ui.television
                            .PermissionAppsFragment.newInstance(permissionName);
                } else {
                    fragment = com.android.packageinstaller.permission.ui.handheld
                            .PermissionAppsFragment.newInstance(permissionName);
                }
            } break;

            default: {
                Log.w(LOG_TAG, "Unrecognized action " + action);
                finish();
                return;
            }
        }

        getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // in automotive mode, there's no system wide back button, so need to add that
        if (DeviceUtils.isAuto(this)) {
            switch (item.getItemId()) {
                case android.R.id.home:
                    onBackPressed();
                    return true;
                default:
                    return super.onOptionsItemSelected(item);
            }
        }
        return super.onOptionsItemSelected(item);
    }
}
