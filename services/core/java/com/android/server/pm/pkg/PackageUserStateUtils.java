/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.pkg;

import static android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS;
import static android.content.pm.PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
import static android.content.pm.PackageManager.MATCH_QUARANTINED_COMPONENTS;

import android.annotation.NonNull;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.os.Debug;
import android.util.DebugUtils;
import android.util.Slog;

import com.android.internal.pm.pkg.component.ParsedMainComponent;

/** @hide */
public class PackageUserStateUtils {

    private static final boolean DEBUG = false;
    private static final String TAG = "PackageUserStateUtils";

    public static boolean isMatch(@NonNull PackageUserState state,
            ComponentInfo componentInfo, long flags) {
        return isMatch(state, componentInfo.applicationInfo.isSystemApp(),
                componentInfo.applicationInfo.enabled, componentInfo.enabled,
                componentInfo.directBootAware, componentInfo.name, flags);
    }

    public static boolean isMatch(@NonNull PackageUserState state, boolean isSystem,
            boolean isPackageEnabled, ParsedMainComponent component, long flags) {
        return isMatch(state, isSystem, isPackageEnabled, component.isEnabled(),
                component.isDirectBootAware(), component.getName(), flags);
    }

    /**
     * Test if the given component is considered installed, enabled and a match for the given
     * flags.
     *
     * <p>
     * Expects at least one of {@link PackageManager#MATCH_DIRECT_BOOT_AWARE} and {@link
     * PackageManager#MATCH_DIRECT_BOOT_UNAWARE} are specified in {@code flags}.
     * </p>
     */
    public static boolean isMatch(@NonNull PackageUserState state, boolean isSystem,
            boolean isPackageEnabled, boolean isComponentEnabled,
            boolean isComponentDirectBootAware, String componentName, long flags) {
        final boolean matchUninstalled = (flags & PackageManager.MATCH_KNOWN_PACKAGES) != 0;
        if (!isAvailable(state, flags) && !(isSystem && matchUninstalled)) {
            return reportIfDebug(false, flags);
        }

        if (!isEnabled(state, isPackageEnabled, isComponentEnabled, componentName, flags)) {
            return reportIfDebug(false, flags);
        }

        if ((flags & PackageManager.MATCH_SYSTEM_ONLY) != 0) {
            if (!isSystem) {
                return reportIfDebug(false, flags);
            }
        }

        final boolean matchesUnaware = ((flags & PackageManager.MATCH_DIRECT_BOOT_UNAWARE) != 0)
                && !isComponentDirectBootAware;
        final boolean matchesAware = ((flags & PackageManager.MATCH_DIRECT_BOOT_AWARE) != 0)
                && isComponentDirectBootAware;
        return reportIfDebug(matchesUnaware || matchesAware, flags);
    }

    /**
     * @return true if any of the following conditions is met:
     * <p><ul>
     * <li> If it is installed and not hidden for this user;
     * <li> If it is installed but hidden for this user, still return true if
     * {@link PackageManager#MATCH_UNINSTALLED_PACKAGES} or
     * {@link PackageManager#MATCH_ARCHIVED_PACKAGES} is requested;
     * <li> If MATCH_ANY_USER is requested, always return true, because the fact that
     * this object exists means that the package must be installed or has data on at least one user;
     * <li> If it is not installed but still has data (i.e., it was previously uninstalled with
     * {@link PackageManager#DELETE_KEEP_DATA}), return true if the caller requested
     * {@link PackageManager#MATCH_UNINSTALLED_PACKAGES} or
     * {@link PackageManager#MATCH_ARCHIVED_PACKAGES};
     * </ul><p>
     */
    public static boolean isAvailable(@NonNull PackageUserState state, long flags) {
        final boolean matchAnyUser = (flags & PackageManager.MATCH_ANY_USER) != 0;
        final boolean matchUninstalled = (flags & PackageManager.MATCH_UNINSTALLED_PACKAGES) != 0;
        final boolean matchArchived = (flags & PackageManager.MATCH_ARCHIVED_PACKAGES) != 0;
        final boolean matchDataExists = matchUninstalled || matchArchived;

        if (matchAnyUser) {
            return true;
        }
        if (state.isInstalled()) {
            if (!state.isHidden()) {
                return true;
            } else return matchDataExists;
        } else {
            // not installed
            return matchDataExists && state.dataExists();
        }
    }

    public static boolean reportIfDebug(boolean result, long flags) {
        if (DEBUG && !result) {
            Slog.i(TAG, "No match!; flags: "
                    + DebugUtils.flagsToString(PackageManager.class, "MATCH_", flags) + " "
                    + Debug.getCaller());
        }
        return result;
    }

    public static boolean isEnabled(@NonNull PackageUserState state, ComponentInfo componentInfo,
            long flags) {
        return isEnabled(state, componentInfo.applicationInfo.enabled, componentInfo.enabled,
                componentInfo.name, flags);
    }

    public static boolean isEnabled(@NonNull PackageUserState state, boolean isPackageEnabled,
            ParsedMainComponent parsedComponent, long flags) {
        return isEnabled(state, isPackageEnabled, parsedComponent.isEnabled(),
                parsedComponent.getName(), flags);
    }

    /**
     * Test if the given component is considered enabled.
     */
    public static boolean isEnabled(@NonNull PackageUserState state,
            boolean isPackageEnabled, boolean isComponentEnabled, String componentName,
            long flags) {
        if ((flags & MATCH_DISABLED_COMPONENTS) != 0) {
            return true;
        }

        if ((flags & MATCH_QUARANTINED_COMPONENTS) == 0 && state.isQuarantined()) {
            return false;
        }

        // First check if the overall package is disabled; if the package is
        // enabled then fall through to check specific component
        switch (state.getEnabledState()) {
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                return false;
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                if ((flags & MATCH_DISABLED_UNTIL_USED_COMPONENTS) == 0) {
                    return false;
                }
                // fallthrough
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                if (!isPackageEnabled) {
                    return false;
                }
                // fallthrough
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                break;
        }

        // Check if component has explicit state before falling through to
        // the manifest default
        if (state.isComponentEnabled(componentName)) {
            return true;
        } else if (state.isComponentDisabled(componentName)) {
            return false;
        }

        return isComponentEnabled;
    }

    public static boolean isPackageEnabled(@NonNull PackageUserState state,
            @NonNull AndroidPackage pkg) {
        switch (state.getEnabledState()) {
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                return true;
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return false;
            default:
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                return pkg.isEnabled();
        }
    }
}
