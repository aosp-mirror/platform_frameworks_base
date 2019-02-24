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
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Debug;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.telephony.SmsApplication;
import com.android.internal.util.CollectionUtils;
import com.android.server.LocalServices;
import com.android.server.role.RoleManagerService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

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
                String legacyAssistant = Settings.Secure.getStringForUser(
                        mContext.getContentResolver(), Settings.Secure.ASSISTANT, userId);
                if (legacyAssistant == null || legacyAssistant.isEmpty()) {
                    return Collections.emptyList();
                } else {
                    return Collections.singletonList(
                            ComponentName.unflattenFromString(legacyAssistant).getPackageName());
                }
            }
            case RoleManager.ROLE_BROWSER: {
                PackageManagerInternal packageManagerInternal = LocalServices.getService(
                        PackageManagerInternal.class);
                String packageName = packageManagerInternal.removeLegacyDefaultBrowserPackageName(
                        userId);
                return CollectionUtils.singletonOrEmpty(packageName);
            }
            case RoleManager.ROLE_DIALER: {
                String setting = Settings.Secure.getStringForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.DIALER_DEFAULT_APPLICATION, userId);
                return CollectionUtils.singletonOrEmpty(!TextUtils.isEmpty(setting)
                        ? setting
                        : mContext.getSystemService(TelecomManager.class).getSystemDialerPackage());
            }
            case RoleManager.ROLE_SMS: {
                // Moved over from SmsApplication#getApplication
                String result = Settings.Secure.getStringForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.SMS_DEFAULT_APPLICATION, userId);
                // TODO: STOPSHIP: Remove the following code once we read the value of
                //  config_defaultSms in RoleControllerService.
                if (result == null) {
                    Collection<SmsApplication.SmsApplicationData> applications =
                            SmsApplication.getApplicationCollectionAsUser(mContext, userId);
                    SmsApplication.SmsApplicationData applicationData;
                    String defaultPackage = mContext.getResources()
                            .getString(com.android.internal.R.string.default_sms_application);
                    applicationData =
                            SmsApplication.getApplicationForPackage(applications, defaultPackage);

                    if (applicationData == null) {
                        // Are there any applications?
                        if (applications.size() != 0) {
                            applicationData =
                                    (SmsApplication.SmsApplicationData) applications.toArray()[0];
                        }
                    }
                    if (DEBUG) {
                        Log.i(LOG_TAG, "Found default sms app: " + applicationData
                                + " among: " + applications + " from " + Debug.getCallers(4));
                    }
                    SmsApplication.SmsApplicationData app = applicationData;
                    result = app == null ? null : app.mPackageName;
                }
                return CollectionUtils.singletonOrEmpty(result);
            }
            case RoleManager.ROLE_HOME: {
                PackageManager packageManager = mContext.getPackageManager();
                List<ResolveInfo> resolveInfos = new ArrayList<>();
                ComponentName componentName = packageManager.getHomeActivities(resolveInfos);
                String packageName = componentName != null ? componentName.getPackageName() : null;
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
}
