/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.policy.role;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.telephony.SmsApplication;
import com.android.internal.util.CollectionUtils;
import com.android.server.LocalServices;
import com.android.server.role.RoleManagerService;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Logic to retrieve the various legacy(pre-Q) equivalents of role holders.
 *
 * Unlike {@link RoleManagerService} this is meant to be pretty high-level to allow for depending
 * on all kinds of various systems that are historically involved in legacy role resolution,
 * e.g. {@link SmsApplication}
 *
 * @see RoleManagerService#migrateRoleIfNecessary
 */
public class LegacyRoleResolutionPolicy implements RoleManagerService.RoleHoldersResolver {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "LegacyRoleResolutionPol";

    @NonNull
    private final Context mContext;

    public LegacyRoleResolutionPolicy(@NonNull Context context) {
        mContext = context;
    }

    @NonNull
    @Override
    public List<String> getRoleHolders(@NonNull String roleName, @UserIdInt int userId) {
        switch (roleName) {
            case RoleManager.ROLE_ASSISTANT: {
                String packageName;
                String setting = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                        Settings.Secure.ASSISTANT, userId);
                // AssistUtils was using the default assistant app if Settings.Secure.ASSISTANT is
                // null, while only an empty string means user selected "None".
                if (setting != null) {
                    if (!setting.isEmpty()) {
                        ComponentName componentName = ComponentName.unflattenFromString(setting);
                        packageName = componentName != null ? componentName.getPackageName() : null;
                    } else {
                        packageName = null;
                    }
                } else if (mContext.getPackageManager().isDeviceUpgrading()) {
                    String defaultAssistant = mContext.getString(R.string.config_defaultAssistant);
                    packageName = !TextUtils.isEmpty(defaultAssistant) ? defaultAssistant : null;
                } else {
                    packageName = null;
                }
                return CollectionUtils.singletonOrEmpty(packageName);
            }
            case RoleManager.ROLE_BROWSER: {
                PackageManagerInternal packageManagerInternal = LocalServices.getService(
                        PackageManagerInternal.class);
                String packageName = packageManagerInternal.removeLegacyDefaultBrowserPackageName(
                        userId);
                return CollectionUtils.singletonOrEmpty(packageName);
            }
            case RoleManager.ROLE_DIALER: {
                String setting = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                        Settings.Secure.DIALER_DEFAULT_APPLICATION, userId);
                String packageName;
                if (!TextUtils.isEmpty(setting)) {
                    packageName = setting;
                } else if (mContext.getPackageManager().isDeviceUpgrading()) {
                    // DefaultDialerManager was using the default dialer app if
                    // Settings.Secure.DIALER_DEFAULT_APPLICATION is invalid.
                    // TelecomManager.getSystemDialerPackage() won't work because it might not
                    // be ready.
                    packageName = mContext.getString(R.string.config_defaultDialer);
                } else {
                    packageName = null;
                }
                return CollectionUtils.singletonOrEmpty(packageName);
            }
            case RoleManager.ROLE_SMS: {
                String setting = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                        Settings.Secure.SMS_DEFAULT_APPLICATION, userId);
                String packageName;
                if (!TextUtils.isEmpty(setting)) {
                    packageName = setting;
                } else if (mContext.getPackageManager().isDeviceUpgrading()) {
                    // SmsApplication was using the default SMS app if
                    // Settings.Secure.DIALER_DEFAULT_APPLICATION is invalid.
                    packageName = mContext.getString(R.string.config_defaultSms);
                } else {
                    packageName = null;
                }
                return CollectionUtils.singletonOrEmpty(packageName);
            }
            case RoleManager.ROLE_HOME: {
                PackageManager packageManager = mContext.getPackageManager();
                String packageName;
                if (packageManager.isDeviceUpgrading()) {
                    ResolveInfo resolveInfo = packageManager.resolveActivityAsUser(
                            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                            PackageManager.MATCH_DEFAULT_ONLY
                                    | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);
                    packageName = resolveInfo != null && resolveInfo.activityInfo != null
                            ? resolveInfo.activityInfo.packageName : null;
                    if (packageName != null && isSettingsApplication(packageName, userId)) {
                        packageName = null;
                    }
                } else {
                    packageName = null;
                }
                return CollectionUtils.singletonOrEmpty(packageName);
            }
            case RoleManager.ROLE_EMERGENCY: {
                String defaultEmergencyApp = Settings.Secure.getStringForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.EMERGENCY_ASSISTANCE_APPLICATION, userId);
                return CollectionUtils.singletonOrEmpty(defaultEmergencyApp);
            }
            default: {
                Slog.e(LOG_TAG, "Don't know how to find legacy role holders for " + roleName);
                return Collections.emptyList();
            }
        }
    }

    private boolean isSettingsApplication(@NonNull String packageName, @UserIdInt int userId) {
        PackageManager packageManager = mContext.getPackageManager();
        ResolveInfo resolveInfo = packageManager.resolveActivityAsUser(new Intent(
                Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);
        if (resolveInfo == null || resolveInfo.activityInfo == null) {
            return false;
        }
        return Objects.equals(packageName, resolveInfo.activityInfo.packageName);
    }
}
