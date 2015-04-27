/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package android.telecom;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Class for managing the default dialer application that will receive incoming calls, and be
 * allowed to make emergency outgoing calls.
 *
 * @hide
 */
public class DefaultDialerManager {
    private static final String TAG = "DefaultDialerManager";

    /**
     * Sets the specified package name as the default dialer application. The caller of this method
     * needs to have permission to write to secure settings.
     *
     * @hide
     * */
    public static void setDefaultDialerApplication(Context context, String packageName) {
        // Get old package name
        String oldPackageName = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.DIALER_DEFAULT_APPLICATION);

        if (packageName != null && oldPackageName != null && packageName.equals(oldPackageName)) {
            // No change
            return;
        }

        // Only make the change if the new package belongs to a valid phone application
        List<ComponentName> componentNames = getInstalledDialerApplications(context);
        final ComponentName foundComponent = getComponentName(componentNames, packageName);

        if (foundComponent != null) {
            // Update the secure setting.
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.DIALER_DEFAULT_APPLICATION, foundComponent.getPackageName());
        }
    }

    /**
     * Returns the installed dialer application that will be used to receive incoming calls, and is
     * allowed to make emergency calls.
     *
     * The application will be returned in order of preference:
     * 1) User selected phone application (if still installed)
     * 2) Pre-installed system dialer (if not disabled)
     * 3) Null
     *
     * @hide
     * */
    public static ComponentName getDefaultDialerApplication(Context context) {
        String defaultPackageName = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.DIALER_DEFAULT_APPLICATION);

        final List<ComponentName> componentNames = getInstalledDialerApplications(context);
        if (!TextUtils.isEmpty(defaultPackageName)) {
            final ComponentName defaultDialer =
                    getComponentName(componentNames, defaultPackageName);
            if (defaultDialer != null) {
                return defaultDialer;
            }
        }

        // No user-set dialer found, fallback to system dialer
        String systemDialer = getTelecomManager(context).getSystemDialerPackage();

        if (TextUtils.isEmpty(systemDialer)) {
            // No system dialer configured at build time
            return null;
        }

        // Verify that the system dialer has not been disabled.
        return getComponentName(componentNames, systemDialer);
    }

    /**
     * Returns a list of installed and available dialer applications.
     *
     * In order to appear in the list, a dialer application must implement an intent-filter with
     * the DIAL intent for the following schemes:
     *
     * 1) Empty scheme
     * 2) tel Uri scheme
     *
     * @hide
     **/
    public static List<ComponentName> getInstalledDialerApplications(Context context) {
        PackageManager packageManager = context.getPackageManager();

        // Get the list of apps registered for the DIAL intent with empty scheme
        Intent intent = new Intent(Intent.ACTION_DIAL);
        List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(intent, 0);

        List<ComponentName> componentNames = new ArrayList<ComponentName> ();

        for (ResolveInfo resolveInfo : resolveInfoList) {
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            if (activityInfo == null) {
                continue;
            }
            final ComponentName componentName =
                    new ComponentName(activityInfo.packageName, activityInfo.name);
            componentNames.add(componentName);
        }

        // TODO: Filter for apps that don't handle DIAL intent with tel scheme
        return componentNames;
    }

    /**
     * Returns the {@link ComponentName} for the installed dialer application for a given package
     * name.
     *
     * @param context A valid context.
     * @param packageName to retrieve the {@link ComponentName} for.
     *
     * @return The {@link ComponentName} for the installed dialer application corresponding to the
     * package name, or null if none is found.
     *
     * @hide
     */
    public static ComponentName getDialerApplicationForPackageName(Context context,
            String packageName) {
        return getComponentName(getInstalledDialerApplications(context), packageName);
    }

    /**
     * Determines if the package name belongs to the user-selected default dialer or the preloaded
     * system dialer, and thus should be allowed to perform certain privileged operations.
     *
     * @param context A valid context.
     * @param packageName of the package to check for.
     *
     * @return {@code true} if the provided package name corresponds to the user-selected default
     *         dialer or the preloaded system dialer, {@code false} otherwise.
     *
     * @hide
     */
    public static boolean isDefaultOrSystemDialer(Context context, String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        final TelecomManager tm = getTelecomManager(context);
        return packageName.equals(tm.getDefaultDialerPackage())
                || packageName.equals(tm.getSystemDialerPackage());
    }

    /**
     * Returns the component from a list of application components that corresponds to the package
     * name.
     *
     * @param componentNames A list of component names
     * @param packageName The package name to look for
     * @return The {@link ComponentName} that matches the provided packageName, or null if not
     *         found.
     */
    private static ComponentName getComponentName(List<ComponentName> componentNames,
            String packageName) {
        for (ComponentName componentName : componentNames) {
            if (TextUtils.equals(packageName, componentName.getPackageName())) {
                return componentName;
            }
        }
        return null;
    }

    private static TelecomManager getTelecomManager(Context context) {
        return (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
    }
}
