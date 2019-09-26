/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

/**
 * Responsible for un/installing system packages based on user type.
 *
 * <p>Uses the SystemConfig's install-in-user-type whitelist;
 * see {@link SystemConfig#getAndClearPackageToUserTypeWhitelist} and
 * {@link SystemConfig#getAndClearPackageToUserTypeBlacklist}.
 *
 * <p>If {@link #isEnforceMode()} is false, then all system packages are always installed for all
 * users. The following applies when it is true.
 *
 * Any package can be in one of three states in the SystemConfig whitelist
 * <ol>
 *     <li>Explicitly blacklisted for a particular user type</li>
 *     <li>Explicitly whitelisted for a particular user type</li>
 *     <li>Not mentioned at all, for any user type (neither whitelisted nor blacklisted)</li>
 * </ol>
 * Blacklisting always takes precedence - if a package is blacklisted for a particular user,
 * it won't be installed on that type of user (even if it is also whitelisted for that user).
 * Next comes whitelisting - if it is whitelisted for a particular user, it will be installed on
 * that type of user (as long as it isn't blacklisted).
 * Finally, if the package is not mentioned at all (i.e. neither whitelisted nor blacklisted for
 * any user types) in the SystemConfig 'install-in-user-type' lists
 * then:
 * <ul>
 *     <li>If {@link #isImplicitWhitelistMode()}, the package is implicitly treated as whitelisted
 *          for all users</li>
 *     <li>Otherwise, the package is implicitly treated as blacklisted for all non-SYSTEM users</li>
 *     <li>Either way, for {@link UserHandle#USER_SYSTEM}, the package will be implicitly
 *          whitelisted so that it can be used for local development purposes.</li>
 * </ul>
 */
class UserSystemPackageInstaller {
    private static final String TAG = "UserManagerService";

    /**
     * System Property whether to only install system packages on a user if they're whitelisted for
     * that user type. These are flags and can be freely combined.
     * <ul>
     * <li> 0 (0b000) - disable whitelist (install all system packages; no logging)</li>
     * <li> 1 (0b001) - enforce (only install system packages if they are whitelisted)</li>
     * <li> 2 (0b010) - log (log when a non-whitelisted package is run)</li>
     * <li> 4 (0b100) - implicitly whitelist any package not mentioned in the whitelist</li>
     * <li>-1         - use device default (as defined in res/res/values/config.xml)</li>
     * </ul>
     * Note: This list must be kept current with config_userTypePackageWhitelistMode in
     * frameworks/base/core/res/res/values/config.xml
     */
    static final String PACKAGE_WHITELIST_MODE_PROP = "persist.debug.user.package_whitelist_mode";
    static final int USER_TYPE_PACKAGE_WHITELIST_MODE_DISABLE = 0;
    static final int USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE = 0b001;
    static final int USER_TYPE_PACKAGE_WHITELIST_MODE_LOG = 0b010;
    static final int USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST = 0b100;
    static final int USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT = -1;

    @IntDef(flag = true, prefix = "USER_TYPE_PACKAGE_WHITELIST_MODE_", value = {
            USER_TYPE_PACKAGE_WHITELIST_MODE_DISABLE,
            USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE,
            USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE,
            USER_TYPE_PACKAGE_WHITELIST_MODE_LOG,
            USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PackageWhitelistMode {}

    /**
     * Maps system package manifest names to the user flags on which they should be initially
     * installed.
     * <p>Packages that are whitelisted, but then blacklisted so that they aren't to be installed on
     * any user, are purposefully still present in this list.
     */
    private final ArrayMap<String, Integer> mWhitelitsedPackagesForUserTypes;

    private final UserManagerService mUm;

    UserSystemPackageInstaller(UserManagerService ums) {
        mUm = ums;
        mWhitelitsedPackagesForUserTypes =
                determineWhitelistedPackagesForUserTypes(SystemConfig.getInstance());
    }

    /** Constructor for testing purposes. */
    @VisibleForTesting
    UserSystemPackageInstaller(UserManagerService ums, ArrayMap<String, Integer> whitelist) {
        mUm = ums;
        mWhitelitsedPackagesForUserTypes = whitelist;
    }

    /**
     * During OTAs and first boot, install/uninstall all system packages for all users based on the
     * user's UserInfo flags and the SystemConfig whitelist.
     * We do NOT uninstall packages during an OTA though.
     *
     * This is responsible for enforcing the whitelist for pre-existing users (i.e. USER_SYSTEM);
     * enforcement for new users is done when they are created in UserManagerService.createUser().
     */
    boolean installWhitelistedSystemPackages(boolean isFirstBoot, boolean isUpgrade) {
        final int mode = getWhitelistMode();
        checkWhitelistedSystemPackages(mode);
        if (!isUpgrade && !isFirstBoot) {
            return false;
        }
        Slog.i(TAG, "Reviewing whitelisted packages due to "
                + (isFirstBoot ? "[firstBoot]" : "") + (isUpgrade ? "[upgrade]" : ""));
        final PackageManagerInternal pmInt = LocalServices.getService(PackageManagerInternal.class);
        // Install/uninstall system packages per user.
        for (int userId : mUm.getUserIds()) {
            final Set<String> userWhitelist = getInstallablePackagesForUserId(userId);
            pmInt.forEachPackage(pkg -> {
                if (!pkg.isSystem()) {
                    return;
                }
                final boolean install =
                        (userWhitelist == null || userWhitelist.contains(pkg.packageName))
                        && !pkg.applicationInfo.hiddenUntilInstalled;
                if (isUpgrade && !isFirstBoot && !install) {
                    return; // To be careful, we don’t uninstall apps during OTAs
                }
                final boolean changed = pmInt.setInstalled(pkg, userId, install);
                if (changed) {
                    Slog.i(TAG, (install ? "Installed " : "Uninstalled ")
                            + pkg.packageName + " for user " + userId);
                }
            });
        }
        return true;
    }

    /**
     * Checks whether the system packages and the mWhitelistedPackagesForUserTypes whitelist are
     * in 1-to-1 correspondence.
     */
    private void checkWhitelistedSystemPackages(@PackageWhitelistMode int mode) {
        if (!isLogMode(mode) && !isEnforceMode(mode)) {
            return;
        }
        Slog.v(TAG,  "Checking that all system packages are whitelisted.");
        final Set<String> allWhitelistedPackages = getWhitelistedSystemPackages();
        PackageManagerInternal pmInt = LocalServices.getService(PackageManagerInternal.class);

        // Check whether all whitelisted packages are indeed on the system.
        for (String pkgName : allWhitelistedPackages) {
            PackageParser.Package pkg = pmInt.getPackage(pkgName);
            if (pkg == null) {
                Slog.w(TAG, pkgName + " is whitelisted but not present.");
            } else if (!pkg.isSystem()) {
                Slog.w(TAG, pkgName + " is whitelisted and present but not a system package.");
            }
        }

        // Check whether all system packages are indeed whitelisted.
        if (isImplicitWhitelistMode(mode) && !isLogMode(mode)) {
            return;
        }
        final boolean doWtf = isEnforceMode(mode);
        pmInt.forEachPackage(pkg -> {
            if (pkg.isSystem() && !allWhitelistedPackages.contains(pkg.manifestPackageName)) {
                final String msg = "System package " + pkg.manifestPackageName
                        + " is not whitelisted using 'install-in-user-type' in SystemConfig "
                        + "for any user types!";
                if (doWtf) {
                    Slog.wtf(TAG, msg);
                } else {
                    Slog.e(TAG, msg);
                }
            }
        });
    }

    /** Whether to only install system packages in new users for which they are whitelisted. */
    boolean isEnforceMode() {
        return isEnforceMode(getWhitelistMode());
    }

    /**
     * Whether to log a warning concerning potential problems with the user-type package whitelist.
     */
    boolean isLogMode() {
        return isLogMode(getWhitelistMode());
    }

    /**
     * Whether to treat all packages that are not mentioned at all in the whitelist to be implicitly
     * whitelisted for all users.
     */
    boolean isImplicitWhitelistMode() {
        return isImplicitWhitelistMode(getWhitelistMode());
    }

    /** See {@link #isEnforceMode()}. */
    private static boolean isEnforceMode(int whitelistMode) {
        return (whitelistMode & USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE) != 0;
    }

    /** See {@link #isLogMode()}. */
    private static boolean isLogMode(int whitelistMode) {
        return (whitelistMode & USER_TYPE_PACKAGE_WHITELIST_MODE_LOG) != 0;
    }

    /** See {@link #isImplicitWhitelistMode()}. */
    private static boolean isImplicitWhitelistMode(int whitelistMode) {
        return (whitelistMode & USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST) != 0;
    }

    /** Gets the PackageWhitelistMode for use of {@link #mWhitelitsedPackagesForUserTypes}. */
    private @PackageWhitelistMode int getWhitelistMode() {
        final int runtimeMode = SystemProperties.getInt(
                PACKAGE_WHITELIST_MODE_PROP, USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT);
        if (runtimeMode != USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT) {
            return runtimeMode;
        }
        return Resources.getSystem()
                .getInteger(com.android.internal.R.integer.config_userTypePackageWhitelistMode);
    }

    /**
     * Gets the system packages names that should be installed on the given user.
     * See {@link #getInstallablePackagesForUserType(int)}.
     */
    private @Nullable Set<String> getInstallablePackagesForUserId(@UserIdInt int userId) {
        return getInstallablePackagesForUserType(mUm.getUserInfo(userId).flags);
    }

    /**
     * Gets the system package names that should be installed on a user with the given flags, as
     * determined by SystemConfig, the whitelist mode, and the apps actually on the device.
     * Names are the {@link PackageParser.Package#packageName}, not necessarily the manifest names.
     *
     * Returns null if all system packages should be installed (due enforce-mode being off).
     */
    @Nullable Set<String> getInstallablePackagesForUserType(int flags) {
        final int mode = getWhitelistMode();
        if (!isEnforceMode(mode)) {
            return null;
        }
        final boolean isSystemUser = (flags & UserInfo.FLAG_SYSTEM) != 0;
        final boolean isImplicitWhitelistMode = isImplicitWhitelistMode(mode);
        final Set<String> whitelistedPackages = getWhitelistedPackagesForUserType(flags);

        final Set<String> installPackages = new ArraySet<>();
        final PackageManagerInternal pmInt = LocalServices.getService(PackageManagerInternal.class);
        pmInt.forEachPackage(pkg -> {
            if (!pkg.isSystem()) {
                return;
            }
            if (shouldInstallPackage(pkg, mWhitelitsedPackagesForUserTypes,
                    whitelistedPackages, isImplicitWhitelistMode, isSystemUser)) {
                // Although the whitelist uses manifest names, this function returns packageNames.
                installPackages.add(pkg.packageName);
            }
        });
        return installPackages;
    }

    /**
     * Returns whether the given system package should be installed on the given user, based on the
     * the given whitelist of system packages.
     *
     * @param sysPkg the system package. Must be a system package; no verification for this is done.
     * @param userTypeWhitelist map of package manifest names to user flags on which they should be
     *                          installed
     * @param userWhitelist set of package manifest names that should be installed on this
     *                      particular user. This must be consistent with userTypeWhitelist, but is
     *                      passed in separately to avoid repeatedly calculating it from
     *                      userTypeWhitelist.
     * @param isImplicitWhitelistMode whether non-mentioned packages are implicitly whitelisted.
     * @param isSystemUser whether the user is USER_SYSTEM (which gets special treatment).
     */
    @VisibleForTesting
    static boolean shouldInstallPackage(PackageParser.Package sysPkg,
            @NonNull ArrayMap<String, Integer> userTypeWhitelist,
            @NonNull Set<String> userWhitelist, boolean isImplicitWhitelistMode,
            boolean isSystemUser) {

        final String pkgName = sysPkg.manifestPackageName;
        boolean install = (isImplicitWhitelistMode && !userTypeWhitelist.containsKey(pkgName))
                || userWhitelist.contains(pkgName);

        // For the purposes of local development, any package that isn't even mentioned in the
        // whitelist at all is implicitly treated as whitelisted for the SYSTEM user.
        if (!install && isSystemUser && !userTypeWhitelist.containsKey(pkgName)) {
            install = true;
            Slog.e(TAG, "System package " + pkgName + " is not mentioned "
                    + "in SystemConfig's 'install-in-user-type' but we are "
                    + "implicitly treating it as whitelisted for the SYSTEM user.");
        }
        return install;
    }

    /**
     * Gets the package manifest names that are whitelisted for a user with the given flags,
     * as determined by SystemConfig.
     */
    @VisibleForTesting
    @NonNull Set<String> getWhitelistedPackagesForUserType(int flags) {
        Set<String> installablePkgs = new ArraySet<>(mWhitelitsedPackagesForUserTypes.size());
        for (int i = 0; i < mWhitelitsedPackagesForUserTypes.size(); i++) {
            String pkgName = mWhitelitsedPackagesForUserTypes.keyAt(i);
            int whitelistedUserTypes = mWhitelitsedPackagesForUserTypes.valueAt(i);
            if ((flags & whitelistedUserTypes) != 0) {
                installablePkgs.add(pkgName);
            }
        }
        return installablePkgs;
    }

    /**
     * Set of package manifest names that are included anywhere in the package-to-user-type
     * whitelist, as determined by SystemConfig.
     *
     * Packages that are whitelisted, but then blacklisted so that they aren't to be installed on
     * any user, are still present in this list, since that is a valid scenario (e.g. if an OEM
     * completely blacklists an AOSP app).
     */
    private Set<String> getWhitelistedSystemPackages() {
        return mWhitelitsedPackagesForUserTypes.keySet();
    }

    /**
     * Returns a map of package manifest names to the user flags on which it is to be installed.
     * Also, clears this data from SystemConfig where it was stored inefficiently (and therefore
     * should be called exactly once, even if the data isn't useful).
     *
     * Any system packages not present in this map should not even be on the device at all.
     * To enforce this:
     * <ul>
     *  <li>Illegal user types are ignored.</li>
     *  <li>Packages that never whitelisted at all (even if they are explicitly blacklisted) are
     *          ignored.</li>
     *  <li>Packages that are blacklisted whenever they are whitelisted will be stored with the
     *          flag 0 (since this is a valid scenario, e.g. if an OEM completely blacklists an AOSP
     *          app).</li>
     * </ul>
     */
    @VisibleForTesting
    static ArrayMap<String, Integer> determineWhitelistedPackagesForUserTypes(
            SystemConfig sysConfig) {

        final ArrayMap<String, Set<String>> whitelist =
                sysConfig.getAndClearPackageToUserTypeWhitelist();
        // result maps packageName -> userTypes on which the package should be installed.
        final ArrayMap<String, Integer> result = new ArrayMap<>(whitelist.size() + 1);
        // First, do the whitelisted user types.
        for (int i = 0; i < whitelist.size(); i++) {
            final String pkgName = whitelist.keyAt(i);
            final int flags = getFlagsFromUserTypes(whitelist.valueAt(i));
            if (flags != 0) {
                result.put(pkgName, flags);
            }
        }
        // Then, un-whitelist any blacklisted user types.
        // TODO(b/141370854): Right now, the blacklist is actually just an 'unwhitelist'. Which
        //                    direction we go depends on how we design user subtypes, which is still
        //                    being designed. For now, unwhitelisting works for current use-cases.
        final ArrayMap<String, Set<String>> blacklist =
                sysConfig.getAndClearPackageToUserTypeBlacklist();
        for (int i = 0; i < blacklist.size(); i++) {
            final String pkgName = blacklist.keyAt(i);
            final int nonFlags = getFlagsFromUserTypes(blacklist.valueAt(i));
            final Integer flags = result.get(pkgName);
            if (flags != null) {
                result.put(pkgName, flags & ~nonFlags);
            }
        }
        // Regardless of the whitelists/blacklists, ensure mandatory packages.
        result.put("android",
                UserInfo.FLAG_SYSTEM | UserInfo.FLAG_FULL | UserInfo.PROFILE_FLAGS_MASK);
        return result;
    }

    /** Converts a user types, as used in SystemConfig, to a UserInfo flag. */
    private static int getFlagsFromUserTypes(Iterable<String> userTypes) {
        int flags = 0;
        for (String type : userTypes) {
            switch (type) {
                case "GUEST":
                    flags |= UserInfo.FLAG_GUEST;
                    break;
                case "RESTRICTED":
                    flags |= UserInfo.FLAG_RESTRICTED;
                    break;
                case "MANAGED_PROFILE":
                    flags |= UserInfo.FLAG_MANAGED_PROFILE;
                    break;
                case "EPHEMERAL":
                    flags |= UserInfo.FLAG_EPHEMERAL;
                    break;
                case "DEMO":
                    flags |= UserInfo.FLAG_DEMO;
                    break;
                case "FULL":
                    flags |= UserInfo.FLAG_FULL;
                    break;
                case "SYSTEM":
                    flags |= UserInfo.FLAG_SYSTEM;
                    break;
                case "PROFILE":
                    flags |= UserInfo.PROFILE_FLAGS_MASK;
                    break;
                default:
                    Slog.w(TAG, "SystemConfig contained an invalid user type: " + type);
                    break;
                // Other UserInfo flags are forbidden.
                // In particular, FLAG_INITIALIZED, FLAG_DISABLED, FLAG_QUIET_MODE are inapplicable.
                // The following are invalid now, but are reconsiderable: FLAG_PRIMARY, FLAG_ADMIN.
            }
        }
        return flags;
    }

    void dump(PrintWriter pw) {
        for (int i = 0; i < mWhitelitsedPackagesForUserTypes.size(); i++) {
            final String pkgName = mWhitelitsedPackagesForUserTypes.keyAt(i);
            final String whitelistedUserTypes =
                    UserInfo.flagsToString(mWhitelitsedPackagesForUserTypes.valueAt(i));
            pw.println("    " + pkgName + ": " + whitelistedUserTypes);
        }
    }
}
