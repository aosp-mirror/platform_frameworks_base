/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.devicepolicy;

import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE;
import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE;
import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_USER;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.ArraySet;
import android.view.inputmethod.InputMethodInfo;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Class that provides the apps that are not required on a managed device / profile according to the
 * overlays provided via (vendor_|)required_apps_managed_(profile|device).xml.
 */
public class OverlayPackagesProvider {

    protected static final String TAG = "OverlayPackagesProvider";

    private final PackageManager mPm;
    private final Context mContext;
    private final Injector mInjector;

    public OverlayPackagesProvider(Context context) {
        this(context, new DefaultInjector());
    }

    @VisibleForTesting
    interface Injector {
        @NonNull
        List<InputMethodInfo> getInputMethodListAsUser(@UserIdInt int userId);
    }

    private static final class DefaultInjector implements Injector {
        @NonNull
        @Override
        public List<InputMethodInfo> getInputMethodListAsUser(@UserIdInt int userId) {
            return InputMethodManagerInternal.get().getInputMethodListAsUser(userId);
        }
    }

    @VisibleForTesting
    OverlayPackagesProvider(Context context, Injector injector) {
        mContext = context;
        mPm = checkNotNull(context.getPackageManager());
        mInjector = checkNotNull(injector);
    }

    /**
     * Computes non-required apps. All the system apps with a launcher that are not in
     * the required set of packages will be considered as non-required apps.
     *
     * Note: If an app is mistakenly listed as both required and disallowed, it will be treated as
     * disallowed.
     *
     * @param admin              Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userId             The userId for which the non-required apps needs to be computed.
     * @param provisioningAction action indicating type of provisioning, should be one of
     *                           {@link ACTION_PROVISION_MANAGED_DEVICE}, {@link
     *                           ACTION_PROVISION_MANAGED_PROFILE} or
     *                           {@link ACTION_PROVISION_MANAGED_USER}.
     * @return the set of non-required apps.
     */
    @NonNull
    public Set<String> getNonRequiredApps(@NonNull ComponentName admin, int userId,
            @NonNull String provisioningAction) {
        final Set<String> nonRequiredApps = getLaunchableApps(userId);
        // Newly installed system apps are uninstalled when they are not required and are either
        // disallowed or have a launcher icon.
        nonRequiredApps.removeAll(getRequiredApps(provisioningAction, admin.getPackageName()));
        nonRequiredApps.removeAll(getSystemInputMethods(userId));
        nonRequiredApps.addAll(getDisallowedApps(provisioningAction));
        return nonRequiredApps;
    }

    private Set<String> getLaunchableApps(int userId) {
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> resolveInfos = mPm.queryIntentActivitiesAsUser(launcherIntent,
                PackageManager.MATCH_UNINSTALLED_PACKAGES
                        | PackageManager.MATCH_DISABLED_COMPONENTS
                        | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                userId);
        final Set<String> apps = new ArraySet<>();
        for (ResolveInfo resolveInfo : resolveInfos) {
            apps.add(resolveInfo.activityInfo.packageName);
        }
        return apps;
    }

    private Set<String> getSystemInputMethods(int userId) {
        final List<InputMethodInfo> inputMethods = mInjector.getInputMethodListAsUser(userId);
        final Set<String> systemInputMethods = new ArraySet<>();
        for (InputMethodInfo inputMethodInfo : inputMethods) {
            ApplicationInfo applicationInfo = inputMethodInfo.getServiceInfo().applicationInfo;
            if (applicationInfo.isSystemApp()) {
                systemInputMethods.add(inputMethodInfo.getPackageName());
            }
        }
        return systemInputMethods;
    }

    private Set<String> getRequiredApps(String provisioningAction, String dpcPackageName) {
        final Set<String> requiredApps = new ArraySet<>();
        requiredApps.addAll(getRequiredAppsSet(provisioningAction));
        requiredApps.addAll(getVendorRequiredAppsSet(provisioningAction));
        requiredApps.add(dpcPackageName);
        return requiredApps;
    }

    private Set<String> getDisallowedApps(String provisioningAction) {
        final Set<String> disallowedApps = new ArraySet<>();
        disallowedApps.addAll(getDisallowedAppsSet(provisioningAction));
        disallowedApps.addAll(getVendorDisallowedAppsSet(provisioningAction));
        return disallowedApps;
    }

    private Set<String> getRequiredAppsSet(String provisioningAction) {
        final int resId;
        switch (provisioningAction) {
            case ACTION_PROVISION_MANAGED_USER:
                resId = R.array.required_apps_managed_user;
                break;
            case ACTION_PROVISION_MANAGED_PROFILE:
                resId = R.array.required_apps_managed_profile;
                break;
            case ACTION_PROVISION_MANAGED_DEVICE:
                resId = R.array.required_apps_managed_device;
                break;
            default:
                throw new IllegalArgumentException("Provisioning type "
                        + provisioningAction + " not supported.");
        }
        return new ArraySet<>(Arrays.asList(mContext.getResources().getStringArray(resId)));
    }

    private Set<String> getDisallowedAppsSet(String provisioningAction) {
        final int resId;
        switch (provisioningAction) {
            case ACTION_PROVISION_MANAGED_USER:
                resId = R.array.disallowed_apps_managed_user;
                break;
            case ACTION_PROVISION_MANAGED_PROFILE:
                resId = R.array.disallowed_apps_managed_profile;
                break;
            case ACTION_PROVISION_MANAGED_DEVICE:
                resId = R.array.disallowed_apps_managed_device;
                break;
            default:
                throw new IllegalArgumentException("Provisioning type "
                        + provisioningAction + " not supported.");
        }
        return new ArraySet<>(Arrays.asList(mContext.getResources().getStringArray(resId)));
    }

    private Set<String> getVendorRequiredAppsSet(String provisioningAction) {
        final int resId;
        switch (provisioningAction) {
            case ACTION_PROVISION_MANAGED_USER:
                resId = R.array.vendor_required_apps_managed_user;
                break;
            case ACTION_PROVISION_MANAGED_PROFILE:
                resId = R.array.vendor_required_apps_managed_profile;
                break;
            case ACTION_PROVISION_MANAGED_DEVICE:
                resId = R.array.vendor_required_apps_managed_device;
                break;
            default:
                throw new IllegalArgumentException("Provisioning type "
                        + provisioningAction + " not supported.");
        }
        return new ArraySet<>(Arrays.asList(mContext.getResources().getStringArray(resId)));
    }

    private Set<String> getVendorDisallowedAppsSet(String provisioningAction) {
        final int resId;
        switch (provisioningAction) {
            case ACTION_PROVISION_MANAGED_USER:
                resId = R.array.vendor_disallowed_apps_managed_user;
                break;
            case ACTION_PROVISION_MANAGED_PROFILE:
                resId = R.array.vendor_disallowed_apps_managed_profile;
                break;
            case ACTION_PROVISION_MANAGED_DEVICE:
                resId = R.array.vendor_disallowed_apps_managed_device;
                break;
            default:
                throw new IllegalArgumentException("Provisioning type "
                        + provisioningAction + " not supported.");
        }
        return new ArraySet<>(Arrays.asList(mContext.getResources().getStringArray(resId)));
    }
}
