/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Telephony;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.view.inputmethod.InputMethodInfo;

import com.android.internal.R;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Utility class to find what personal apps should be suspended to limit personal device use.
 */
public final class PersonalAppsSuspensionHelper {
    private static final String LOG_TAG = DevicePolicyManagerService.LOG_TAG;

    // Flags to get all packages even if the user is still locked.
    private static final int PACKAGE_QUERY_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

    private final Context mContext;
    private final PackageManager mPackageManager;

    /**
     * Factory method
     */
    public static PersonalAppsSuspensionHelper forUser(Context context, @UserIdInt int userId) {
        return new PersonalAppsSuspensionHelper(context.createContextAsUser(UserHandle.of(userId),
                /* flags= */ 0));
    }

    /**
     * @param context Context for the user whose apps should to be suspended.
     */
    private PersonalAppsSuspensionHelper(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    /**
     * @return List of packages that should be suspended to limit personal use.
     */
    String[] getPersonalAppsForSuspension() {
        final List<PackageInfo> installedPackageInfos =
                mPackageManager.getInstalledPackages(PACKAGE_QUERY_FLAGS);
        final Set<String> result = new ArraySet<>();
        for (final PackageInfo packageInfo : installedPackageInfos) {
            final ApplicationInfo info = packageInfo.applicationInfo;
            if ((!info.isSystemApp() && !info.isUpdatedSystemApp())
                    || hasLauncherIntent(packageInfo.packageName)) {
                result.add(packageInfo.packageName);
            }
        }
        result.removeAll(getCriticalPackages());
        result.removeAll(getSystemLauncherPackages());
        result.removeAll(getAccessibilityServices());
        result.removeAll(getInputMethodPackages());
        result.remove(Telephony.Sms.getDefaultSmsPackage(mContext));
        result.remove(getSettingsPackageName());

        final String[] unsuspendablePackages =
                mPackageManager.getUnsuspendablePackages(result.toArray(new String[0]));
        for (final String pkg : unsuspendablePackages) {
            result.remove(pkg);
        }

        Slog.i(LOG_TAG, "Packages subject to suspension: " + String.join(",", result));
        return result.toArray(new String[0]);
    }

    private List<String> getSystemLauncherPackages() {
        final List<String> result = new ArrayList<>();
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        final List<ResolveInfo> matchingActivities =
                mPackageManager.queryIntentActivities(intent, PACKAGE_QUERY_FLAGS);
        for (final ResolveInfo resolveInfo : matchingActivities) {
            if (resolveInfo.activityInfo == null
                    || TextUtils.isEmpty(resolveInfo.activityInfo.packageName)) {
                Slog.wtf(LOG_TAG, "Could not find package name for launcher app" + resolveInfo);
                continue;
            }
            final String packageName = resolveInfo.activityInfo.packageName;
            try {
                final ApplicationInfo applicationInfo =
                        mPackageManager.getApplicationInfo(packageName, PACKAGE_QUERY_FLAGS);
                if (applicationInfo.isSystemApp() || applicationInfo.isUpdatedSystemApp()) {
                    result.add(packageName);
                }
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(LOG_TAG, "Could not find application info for launcher app: " + packageName);
            }
        }
        return result;
    }

    private List<String> getAccessibilityServices() {
        final List<AccessibilityServiceInfo> accessibilityServiceInfos =
                getAccessibilityManagerForUser(mContext.getUserId())
                        .getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK);
        final List<String> result = new ArrayList<>();
        for (final AccessibilityServiceInfo serviceInfo : accessibilityServiceInfos) {
            final ComponentName componentName =
                    ComponentName.unflattenFromString(serviceInfo.getId());
            if (componentName != null) {
                result.add(componentName.getPackageName());
            }
        }
        return result;
    }

    private List<String> getInputMethodPackages() {
        final List<InputMethodInfo> enabledImes = InputMethodManagerInternal.get()
                .getEnabledInputMethodListAsUser(mContext.getUserId());
        final List<String> result = new ArrayList<>();
        for (final InputMethodInfo info : enabledImes) {
            result.add(info.getPackageName());
        }
        return result;
    }

    @Nullable
    private String getSettingsPackageName() {
        final Intent intent = new Intent(Settings.ACTION_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        final ResolveInfo resolveInfo =
                mPackageManager.resolveActivity(intent, PACKAGE_QUERY_FLAGS);
        if (resolveInfo != null) {
            return resolveInfo.activityInfo.packageName;
        }
        return null;
    }

    private List<String> getCriticalPackages() {
        return Arrays.asList(mContext.getResources()
                .getStringArray(R.array.config_packagesExemptFromSuspension));
    }

    private boolean hasLauncherIntent(String packageName) {
        final Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
        intentToResolve.setPackage(packageName);
        final List<ResolveInfo> resolveInfos =
                mPackageManager.queryIntentActivities(intentToResolve, PACKAGE_QUERY_FLAGS);
        return resolveInfos != null && !resolveInfos.isEmpty();
    }

    private AccessibilityManager getAccessibilityManagerForUser(int userId) {
        final IBinder iBinder = ServiceManager.getService(Context.ACCESSIBILITY_SERVICE);
        final IAccessibilityManager service =
                iBinder == null ? null : IAccessibilityManager.Stub.asInterface(iBinder);
        return new AccessibilityManager(mContext, service, userId);
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("PersonalAppsSuspensionHelper");
        pw.increaseIndent();

        DevicePolicyManagerService.dumpApps(pw, "critical packages", getCriticalPackages());
        DevicePolicyManagerService.dumpApps(pw, "launcher packages", getSystemLauncherPackages());
        DevicePolicyManagerService.dumpApps(pw, "accessibility services",
                getAccessibilityServices());
        DevicePolicyManagerService.dumpApps(pw, "input method packages", getInputMethodPackages());
        pw.printf("SMS package: %s\n", Telephony.Sms.getDefaultSmsPackage(mContext));
        pw.printf("Settings package: %s\n", getSettingsPackageName());
        DevicePolicyManagerService.dumpApps(pw, "Packages subject to suspension",
                getPersonalAppsForSuspension());

        pw.decreaseIndent();
    }
}
