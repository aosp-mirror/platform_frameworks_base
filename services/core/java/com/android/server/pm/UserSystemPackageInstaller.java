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
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * <p>Any package can be in one of three states in the {@code SystemConfig} whitelist
 * <ol>
 *     <li>Explicitly blacklisted for a particular user type</li>
 *     <li>Explicitly whitelisted for a particular user type</li>
 *     <li>Not mentioned at all, for any user type (neither whitelisted nor blacklisted)</li>
 * </ol>
 *
 * <p>Blacklisting always takes precedence - if a package is blacklisted for a particular user,
 * it won't be installed on that type of user (even if it is also whitelisted for that user).
 * Next comes whitelisting - if it is whitelisted for a particular user, it will be installed on
 * that type of user (as long as it isn't blacklisted).
 * Finally, if the package is not mentioned at all (i.e. neither whitelisted nor blacklisted for
 * any user types) in the SystemConfig 'install-in-user-type' lists
 * then:
 * <ul>
 *     <li>If {@link #isImplicitWhitelistMode()}, the package is implicitly treated as whitelisted
 *          for <b>all</b> users</li>
 *     <li>Otherwise, if {@link #isImplicitWhitelistSystemMode()}, the package is implicitly treated
 *          as whitelisted for the <b>{@link UserHandle#USER_SYSTEM}</b> user (not other users),
 *          which is useful for local development purposes</li>
 *     <li>Otherwise, the package is implicitly treated as blacklisted for all users</li>
 * </ul>
 *
 * <p>Packages are only installed/uninstalled by this mechanism when a new user is created or during
 * an update. In the case of updates:<ul>
 *     <li>new packages are (un)installed per the whitelist/blacklist</li>
 *     <li>pre-existing installed blacklisted packages are never uninstalled</li>
 *     <li>pre-existing not-installed whitelisted packages are only installed if the reason why they
 *     had been previously uninstalled was due to UserSystemPackageInstaller</li>
 * </ul>
 *
 * <p><b>NOTE:</b> the {@code SystemConfig} state is only updated on first boot or after a system
 * update. So, to verify changes during development, you can emulate the latter by calling:
 * <pre><code>
 * adb shell setprop persist.pm.mock-upgrade true
 * </code></pre>
 */
class UserSystemPackageInstaller {
    private static final String TAG = "UserManagerService";

    private static final boolean DEBUG = false;

    /**
     * System Property whether to only install system packages on a user if they're whitelisted for
     * that user type. These are flags and can be freely combined.
     * <ul>
     * <li> 0  - disable whitelist (install all system packages; no logging)</li>
     * <li> 1  - enforce (only install system packages if they are whitelisted)</li>
     * <li> 2  - log (log non-whitelisted packages)</li>
     * <li> 4  - for all users: implicitly whitelist any package not mentioned in the whitelist</li>
     * <li> 8  - for SYSTEM: implicitly whitelist any package not mentioned in the whitelist</li>
     * <li> 16 - ignore OTAs (don't install system packages during OTAs)</li>
     * <li>-1  - use device default (as defined in res/res/values/config.xml)</li>
     * </ul>
     * Note: This list must be kept current with config_userTypePackageWhitelistMode in
     * frameworks/base/core/res/res/values/config.xml
     */
    static final String PACKAGE_WHITELIST_MODE_PROP = "persist.debug.user.package_whitelist_mode";

    // NOTE: flags below are public so they can used by DebugUtils.flagsToString. And this class
    // itself is package-protected, so it doesn't matter...
    public static final int USER_TYPE_PACKAGE_WHITELIST_MODE_DISABLE = 0x00;
    public static final int USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE = 0x01;
    public static final int USER_TYPE_PACKAGE_WHITELIST_MODE_LOG = 0x02;
    public static final int USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST = 0x04;
    public static final int USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST_SYSTEM = 0x08;
    public static final int USER_TYPE_PACKAGE_WHITELIST_MODE_IGNORE_OTA = 0x10;
    static final int USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT = -1;

    // Used by Shell command only
    static final int USER_TYPE_PACKAGE_WHITELIST_MODE_NONE = -1000;

    @IntDef(flag = true, prefix = "USER_TYPE_PACKAGE_WHITELIST_MODE_", value = {
            USER_TYPE_PACKAGE_WHITELIST_MODE_DISABLE,
            USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE,
            USER_TYPE_PACKAGE_WHITELIST_MODE_LOG,
            USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST,
            USER_TYPE_PACKAGE_WHITELIST_MODE_IGNORE_OTA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PackageWhitelistMode {}

    /**
     * Maps system package manifest names to a bitset representing (via {@link #getUserTypeMask})
     * the user types on which they should be initially installed.
     * <p>
     * E.g. if package "pkg1" should be installed on "usertype_d", which is the user type for which
     * {@link #getUserTypeMask}("usertype_d") returns (1 << 3)
     * then mWhitelistedPackagesForUserTypes.get("pkg1") will be a Long whose
     * bit in position 3 will equal 1.
     * <p>
     * Packages that are whitelisted, but then blacklisted so that they aren't to be installed on
     * any user, are purposefully still present in this list.
     */
    private final ArrayMap<String, Long> mWhitelistedPackagesForUserTypes;

    private final UserManagerService mUm;

    /**
     * Alphabetically sorted list of user types.
     * Throughout this class, a long (functioning as a bitset) has its ith bit representing
     * the user type stored in mUserTypes[i].
     * mUserTypes cannot exceed Long.SIZE (since we are using long for our bitset).
     */
    private final String[] mUserTypes;

    UserSystemPackageInstaller(UserManagerService um, ArrayMap<String, UserTypeDetails> userTypes) {
        mUm = um;
        mUserTypes = getAndSortKeysFromMap(userTypes);
        if (mUserTypes.length > Long.SIZE) {
            throw new IllegalArgumentException("Device contains " + userTypes.size()
                    + " user types. However, UserSystemPackageInstaller does not work if there are"
                    + " more than " + Long.SIZE + " user types.");
            // UserSystemPackageInstaller could use a BitSet instead of Long in this case.
            // But, currently, 64 user types is far beyond expectations, so we have not done so.
        }
        mWhitelistedPackagesForUserTypes =
                determineWhitelistedPackagesForUserTypes(SystemConfig.getInstance());
    }

    /** Constructor for testing purposes. */
    @VisibleForTesting
    UserSystemPackageInstaller(UserManagerService ums, ArrayMap<String, Long> whitelist,
            String[] sortedUserTypes) {
        mUm = ums;
        mUserTypes = sortedUserTypes;
        mWhitelistedPackagesForUserTypes = whitelist;
    }

    /**
     * During OTAs and first boot, install/uninstall all system packages for all users based on the
     * user's user type and the SystemConfig whitelist.
     * We do NOT uninstall packages during an OTA though.
     *
     * This is responsible for enforcing the whitelist for pre-existing users (i.e. USER_SYSTEM);
     * enforcement for new users is done when they are created in UserManagerService.createUser().
     *
     * @param preExistingPackages list of packages on the device prior to the upgrade. Cannot be
     *                            null if isUpgrade is true.
     */
    boolean installWhitelistedSystemPackages(boolean isFirstBoot, boolean isUpgrade,
            @Nullable ArraySet<String> preExistingPackages) {
        final int mode = getWhitelistMode();
        checkWhitelistedSystemPackages(mode);
        final boolean isConsideredUpgrade = isUpgrade && !isIgnoreOtaMode(mode);
        if (!isConsideredUpgrade && !isFirstBoot) {
            return false;
        }
        if (isFirstBoot && !isEnforceMode(mode)) {
            // Note that if !isEnforceMode, we nonetheless still install packages if isUpgrade
            // in order to undo any previous non-installing. isFirstBoot lacks this requirement.
            return false;
        }
        Slog.i(TAG, "Reviewing whitelisted packages due to "
                + (isFirstBoot ? "[firstBoot]" : "") + (isConsideredUpgrade ? "[upgrade]" : ""));
        final PackageManagerInternal pmInt = LocalServices.getService(PackageManagerInternal.class);
        // Install/uninstall system packages per user.
        for (int userId : mUm.getUserIds()) {
            final Set<String> userWhitelist = getInstallablePackagesForUserId(userId);
            pmInt.forEachPackageSetting(pkgSetting -> {
                AndroidPackage pkg = pkgSetting.pkg;
                if (pkg == null || !pkg.isSystem()) {
                    return;
                }
                final boolean install =
                        (userWhitelist == null || userWhitelist.contains(pkg.getPackageName()))
                                && !pkgSetting.getPkgState().isHiddenUntilInstalled();
                if (pkgSetting.getInstalled(userId) == install
                        || !shouldChangeInstallationState(pkgSetting, install, userId, isFirstBoot,
                                isConsideredUpgrade, preExistingPackages)) {
                    return;
                }
                pkgSetting.setInstalled(install, userId);
                pkgSetting.setUninstallReason(
                        install ? PackageManager.UNINSTALL_REASON_UNKNOWN :
                                PackageManager.UNINSTALL_REASON_USER_TYPE,
                        userId);
                Slog.i(TAG, (install ? "Installed " : "Uninstalled ")
                        + pkg.getPackageName() + " for user " + userId);
            });
        }
        return true;
    }

    /**
     * Returns whether to proceed with install/uninstall for the given package.
     * In particular, do not install a package unless it was only uninstalled due to the user type;
     * and do not uninstall a package if it previously was installed (prior to the OTA).
     *
     * Should be called only within PackageManagerInternal.forEachPackageSetting() since it
     * requires the LP lock.
     *
     * @param preOtaPkgs list of packages on the device prior to the upgrade.
     *                   Cannot be null if isUpgrade is true.
     */
    private static boolean shouldChangeInstallationState(PackageSetting pkgSetting,
                                                         boolean install,
                                                         @UserIdInt int userId,
                                                         boolean isFirstBoot,
                                                         boolean isUpgrade,
                                                         @Nullable ArraySet<String> preOtaPkgs) {
        if (install) {
            // Only proceed with install if we are the only reason why it had been uninstalled.
            return pkgSetting.getUninstallReason(userId)
                    == PackageManager.UNINSTALL_REASON_USER_TYPE;
        } else {
            // Only proceed with uninstall if the package is new to the device.
            return isFirstBoot || (isUpgrade && !preOtaPkgs.contains(pkgSetting.name));
        }
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

        // Check whether all whitelisted packages are indeed on the system.
        final List<String> warnings = getPackagesWhitelistWarnings();
        final int numberWarnings = warnings.size();
        if (numberWarnings == 0) {
            Slog.v(TAG, "checkWhitelistedSystemPackages(mode=" + modeToString(mode)
                    + ") has no warnings");
        } else {
            Slog.w(TAG, "checkWhitelistedSystemPackages(mode=" + modeToString(mode)
                    + ") has " + numberWarnings + " warnings:");
            for (int i = 0; i < numberWarnings; i++) {
                Slog.w(TAG, warnings.get(i));
            }
        }

        // Check whether all system packages are indeed whitelisted.
        if (isImplicitWhitelistMode(mode) && !isLogMode(mode)) {
            return;
        }

        final List<String> errors = getPackagesWhitelistErrors(mode);
        final int numberErrors = errors.size();

        if (numberErrors == 0) {
            Slog.v(TAG, "checkWhitelistedSystemPackages(mode=" + modeToString(mode)
                    + ") has no errors");
            return;
        }
        Slog.e(TAG, "checkWhitelistedSystemPackages(mode=" + modeToString(mode) + ") has "
                + numberErrors + " errors:");

        boolean doWtf = !isImplicitWhitelistMode(mode);
        for (int i = 0; i < numberErrors; i++) {
            final String msg = errors.get(i);
            if (doWtf) {
                Slog.wtf(TAG, msg);
            } else {
                Slog.e(TAG, msg);
            }
        }
    }

    /**
     * Gets packages that are listed in the whitelist XML but are not present on the system image.
     */
    @NonNull
    private List<String> getPackagesWhitelistWarnings() {
        final Set<String> allWhitelistedPackages = getWhitelistedSystemPackages();
        final List<String> warnings = new ArrayList<>();
        final PackageManagerInternal pmInt = LocalServices.getService(PackageManagerInternal.class);

        // Check whether all whitelisted packages are indeed on the system.
        final String notPresentFmt = "%s is whitelisted but not present.";
        final String notSystemFmt = "%s is whitelisted and present but not a system package.";
        final String overlayPackageFmt = "%s is whitelisted but it's auto-generated RRO package.";
        for (String pkgName : allWhitelistedPackages) {
            final AndroidPackage pkg = pmInt.getPackage(pkgName);
            if (pkg == null) {
                warnings.add(String.format(notPresentFmt, pkgName));
            } else if (!pkg.isSystem()) {
                warnings.add(String.format(notSystemFmt, pkgName));
            } else if (isAutoGeneratedRRO(pkg)) {
                warnings.add(String.format(overlayPackageFmt, pkgName));
            }
        }
        return warnings;
    }

    /**
     * Gets packages that are not listed in the whitelist XMLs when they should be.
     */
    @NonNull
    private List<String> getPackagesWhitelistErrors(@PackageWhitelistMode int mode) {
        if ((!isEnforceMode(mode) || isImplicitWhitelistMode(mode)) && !isLogMode(mode)) {
            return Collections.emptyList();
        }

        final List<String> errors = new ArrayList<>();
        final Set<String> allWhitelistedPackages = getWhitelistedSystemPackages();
        final PackageManagerInternal pmInt = LocalServices.getService(PackageManagerInternal.class);

        // Check whether all system packages are indeed whitelisted.
        final String logMessageFmt = "System package %s is not whitelisted using "
                + "'install-in-user-type' in SystemConfig for any user types!";
        pmInt.forEachPackage(pkg -> {
            if (!pkg.isSystem()) return;
            final String pkgName = pkg.getManifestPackageName();
            if (!allWhitelistedPackages.contains(pkgName)
                    && !isAutoGeneratedRRO(pmInt.getPackage(pkgName))) {
                errors.add(String.format(logMessageFmt, pkgName));
            }
        });

        return errors;
    }

    /** Whether to only install system packages in new users for which they are whitelisted. */
    boolean isEnforceMode() {
        return isEnforceMode(getWhitelistMode());
    }

    /**
     * Whether to ignore OTAs, and therefore not install missing system packages during OTAs.
     * <p>Note:
     * If in this mode, old system packages will not be installed on pre-existing users during OTAs.
     * Any system packages that had not been installed at the time of the user's creation,
     * due to {@link UserSystemPackageInstaller}'s previous actions, will therefore continue to
     * remain uninstalled, even if the whitelist (or enforcement mode) now declares that they should
     * be.
     */
    boolean isIgnoreOtaMode() {
        return isIgnoreOtaMode(getWhitelistMode());
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

    /**
     * Whether to treat all packages that are not mentioned at all in the whitelist to be implicitly
     * whitelisted for the SYSTEM user.
     */
    boolean isImplicitWhitelistSystemMode() {
        return isImplicitWhitelistSystemMode(getWhitelistMode());
    }

    /**
     * Whether package name has auto-generated RRO package name suffix.
     */
    @VisibleForTesting
    static boolean hasAutoGeneratedRROSuffix(String name) {
        return name.endsWith(".auto_generated_rro_product__")
                || name.endsWith(".auto_generated_rro_vendor__");
    }

    /**
     * Whether the package is auto-generated RRO package.
     */
    private static boolean isAutoGeneratedRRO(AndroidPackage pkg) {
        return pkg.isOverlay()
                && (hasAutoGeneratedRROSuffix(pkg.getManifestPackageName()));
    }

    /** See {@link #isEnforceMode()}. */
    private static boolean isEnforceMode(int whitelistMode) {
        return (whitelistMode & USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE) != 0;
    }

    /** See {@link #isIgnoreOtaMode()}. */
    private static boolean isIgnoreOtaMode(int whitelistMode) {
        return (whitelistMode & USER_TYPE_PACKAGE_WHITELIST_MODE_IGNORE_OTA) != 0;
    }

    /** See {@link #isLogMode()}. */
    private static boolean isLogMode(int whitelistMode) {
        return (whitelistMode & USER_TYPE_PACKAGE_WHITELIST_MODE_LOG) != 0;
    }

    /** See {@link #isImplicitWhitelistMode()}. */
    private static boolean isImplicitWhitelistMode(int whitelistMode) {
        return (whitelistMode & USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST) != 0;
    }

    /** See {@link #isImplicitWhitelistSystemMode()}. */
    private static boolean isImplicitWhitelistSystemMode(int whitelistMode) {
        return (whitelistMode & USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST_SYSTEM) != 0;
    }

    /** Gets the PackageWhitelistMode for use of {@link #mWhitelistedPackagesForUserTypes}. */
    private @PackageWhitelistMode int getWhitelistMode() {
        final int runtimeMode = SystemProperties.getInt(
                PACKAGE_WHITELIST_MODE_PROP, USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT);
        if (runtimeMode != USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT) {
            return runtimeMode;
        }
        return getDeviceDefaultWhitelistMode();
    }

    /** Gets the PackageWhitelistMode as defined by {@code config_userTypePackageWhitelistMode}. */
    private @PackageWhitelistMode int getDeviceDefaultWhitelistMode() {
        return Resources.getSystem()
                .getInteger(com.android.internal.R.integer.config_userTypePackageWhitelistMode);
    }

    static @NonNull String modeToString(@PackageWhitelistMode int mode) {
        // Must handle some types separately because they're not bitwise flags
        switch (mode) {
            case USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT:
                return "DEVICE_DEFAULT";
            case USER_TYPE_PACKAGE_WHITELIST_MODE_NONE:
                return "NONE";
            default:
                return DebugUtils.flagsToString(UserSystemPackageInstaller.class,
                        "USER_TYPE_PACKAGE_WHITELIST_MODE_", mode);
        }
    }

    /**
     * Gets the system packages names that should be installed on the given user.
     * See {@link #getInstallablePackagesForUserType(String)}.
     */
    private @Nullable Set<String> getInstallablePackagesForUserId(@UserIdInt int userId) {
        return getInstallablePackagesForUserType(mUm.getUserInfo(userId).userType);
    }

    /**
     * Gets the system package names that should be installed on users of the given user type, as
     * determined by SystemConfig, the whitelist mode, and the apps actually on the device.
     * Names are the {@link PackageParser.Package#packageName}, not necessarily the manifest names.
     *
     * Returns null if all system packages should be installed (due to enforce-mode being off).
     */
    @Nullable Set<String> getInstallablePackagesForUserType(String userType) {
        final int mode = getWhitelistMode();
        if (!isEnforceMode(mode)) {
            return null;
        }
        final boolean implicitlyWhitelist = isImplicitWhitelistMode(mode)
                || (isImplicitWhitelistSystemMode(mode) && mUm.isUserTypeSubtypeOfSystem(userType));
        final Set<String> whitelistedPackages = getWhitelistedPackagesForUserType(userType);

        final Set<String> installPackages = new ArraySet<>();
        final PackageManagerInternal pmInt = LocalServices.getService(PackageManagerInternal.class);
        pmInt.forEachPackage(pkg -> {
            if (!pkg.isSystem()) {
                return;
            }
            if (shouldInstallPackage(pkg, mWhitelistedPackagesForUserTypes,
                    whitelistedPackages, implicitlyWhitelist)) {
                // Although the whitelist uses manifest names, this function returns packageNames.
                installPackages.add(pkg.getPackageName());
            }
        });
        return installPackages;
    }

    /**
     * Returns whether the given system package should be installed on the given user, based on the
     * the given whitelist of system packages.
     *
     * @param sysPkg the system package. Must be a system package; no verification for this is done.
     * @param userTypeWhitelist map of package manifest names to user types on which they should be
     *                          installed. This is only used for overriding the userWhitelist in
     *                          certain situations (based on its keyset).
     * @param userWhitelist set of package manifest names that should be installed on this
     *                      <b>particular</b> user. This must be consistent with userTypeWhitelist,
     *                      but is passed in separately to avoid repeatedly calculating it from
     *                      userTypeWhitelist.
     * @param implicitlyWhitelist whether non-mentioned packages are implicitly whitelisted.
     */
    @VisibleForTesting
    static boolean shouldInstallPackage(AndroidPackage sysPkg,
            @NonNull ArrayMap<String, Long> userTypeWhitelist,
            @NonNull Set<String> userWhitelist, boolean implicitlyWhitelist) {
        final String pkgName;
        if (isAutoGeneratedRRO(sysPkg)) {
            pkgName = sysPkg.getOverlayTarget();
            if (DEBUG) {
                Slog.i(TAG, "shouldInstallPackage(): " + sysPkg.getManifestPackageName()
                        + " is auto-generated RRO package, will look for overlay system package: "
                        + pkgName);
            }
        } else {
            pkgName = sysPkg.getManifestPackageName();
        }

        return (implicitlyWhitelist && !userTypeWhitelist.containsKey(pkgName))
                || userWhitelist.contains(pkgName);
    }

    /**
     * Gets the package manifest names that are whitelisted for users of the given user type,
     * as determined by SystemConfig.
     */
    @VisibleForTesting
    @NonNull Set<String> getWhitelistedPackagesForUserType(String userType) {
        final long userTypeMask = getUserTypeMask(userType);
        final Set<String> installablePkgs = new ArraySet<>(mWhitelistedPackagesForUserTypes.size());
        for (int i = 0; i < mWhitelistedPackagesForUserTypes.size(); i++) {
            final String pkgName = mWhitelistedPackagesForUserTypes.keyAt(i);
            final long whitelistedUserTypes = mWhitelistedPackagesForUserTypes.valueAt(i);
            if ((userTypeMask & whitelistedUserTypes) != 0) {
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
        return mWhitelistedPackagesForUserTypes.keySet();
    }

    /**
     * Returns a map of package manifest names to the bit set representing (via
     * {@link #getUserTypeMask}) the user types on which they are to be installed.
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
     *          value 0 (since this is a valid scenario, e.g. if an OEM completely blacklists an
     *          AOSP app).</li>
     * </ul>
     *
     * @see #mWhitelistedPackagesForUserTypes
     */
    @VisibleForTesting
    ArrayMap<String, Long> determineWhitelistedPackagesForUserTypes(SystemConfig sysConfig) {
        // We first get the list of user types that correspond to FULL, SYSTEM, and PROFILE.
        final Map<String, Long> baseTypeBitSets = getBaseTypeBitSets();

        final ArrayMap<String, Set<String>> whitelist =
                sysConfig.getAndClearPackageToUserTypeWhitelist();
        // result maps packageName -> userTypes on which the package should be installed.
        final ArrayMap<String, Long> result = new ArrayMap<>(whitelist.size() + 1);
        // First, do the whitelisted user types.
        for (int i = 0; i < whitelist.size(); i++) {
            final String pkgName = whitelist.keyAt(i).intern();
            final long typesBitSet = getTypesBitSet(whitelist.valueAt(i), baseTypeBitSets);
            if (typesBitSet != 0) {
                result.put(pkgName, typesBitSet);
            }
        }
        // Then, un-whitelist any blacklisted user types.
        final ArrayMap<String, Set<String>> blacklist =
                sysConfig.getAndClearPackageToUserTypeBlacklist();
        for (int i = 0; i < blacklist.size(); i++) {
            final String pkgName = blacklist.keyAt(i).intern();
            final long nonTypesBitSet = getTypesBitSet(blacklist.valueAt(i), baseTypeBitSets);
            final Long typesBitSet = result.get(pkgName);
            if (typesBitSet != null) {
                result.put(pkgName, typesBitSet & ~nonTypesBitSet);
            } else if (nonTypesBitSet != 0) {
                // Package was never whitelisted but is validly blacklisted.
                result.put(pkgName, 0L);
            }
        }
        // Regardless of the whitelists/blacklists, ensure mandatory packages.
        result.put("android", ~0L);
        return result;
    }

    /**
     * Returns the bitmask (with exactly one 1) corresponding to the given userType.
     * Returns 0 if no such userType exists.
     */
    @VisibleForTesting
    long getUserTypeMask(String userType) {
        final int userTypeIndex = Arrays.binarySearch(mUserTypes, userType);
        final long userTypeMask = userTypeIndex >= 0 ? (1 << userTypeIndex) : 0;
        return userTypeMask;
    }

    /**
     * Returns the mapping from the name of each base type to the bitset (as defined by
     * {@link #getUserTypeMask}) of user types to which it corresponds (i.e. the base's subtypes).
     * <p>
     * E.g. if "android.type.ex" is a FULL user type for which getUserTypeMask() returns (1 << 3),
     * then getBaseTypeBitSets().get("FULL") will contain true (1) in position 3.
     */
    private Map<String, Long> getBaseTypeBitSets() {
        long typesBitSetFull = 0;
        long typesBitSetSystem = 0;
        long typesBitSetProfile = 0;
        for (int idx = 0; idx < mUserTypes.length; idx++) {
            if (mUm.isUserTypeSubtypeOfFull(mUserTypes[idx])) {
                typesBitSetFull |= (1 << idx);
            }
            if (mUm.isUserTypeSubtypeOfSystem(mUserTypes[idx])) {
                typesBitSetSystem |= (1 << idx);
            }
            if (mUm.isUserTypeSubtypeOfProfile(mUserTypes[idx])) {
                typesBitSetProfile |= (1 << idx);
            }
        }

        Map<String, Long> result = new ArrayMap<>(3);
        result.put("FULL", typesBitSetFull);
        result.put("SYSTEM", typesBitSetSystem);
        result.put("PROFILE", typesBitSetProfile);
        return result;
    }

    /**
     * Converts a list of user types and base types, as used in SystemConfig, to a bit set
     * representing (via {@link #getUserTypeMask}) user types.
     *
     * Returns 0 if userTypes does not contain any valid user or base types.
     *
     * @param baseTypeBitSets a map from the base types (FULL/SYSTEM/PROFILE) to their subtypes
     *                        (represented as a bitset, as defined by {@link #getUserTypeMask}).
     *                        (This can be created by {@link #getBaseTypeBitSets}.)
     */
    private long getTypesBitSet(Iterable<String> userTypes, Map<String, Long> baseTypeBitSets) {
        long resultBitSet = 0;
        for (String type : userTypes) {
            // See if userType is a base type, like FULL.
            final Long baseTypeBitSet = baseTypeBitSets.get(type);
            if (baseTypeBitSet != null) {
                resultBitSet |= baseTypeBitSet;
                continue;
            }
            // userType wasn't a base type, so it should be the name of a specific user type.
            final long userTypeBitSet = getUserTypeMask(type);
            if (userTypeBitSet != 0) {
                resultBitSet |= userTypeBitSet;
                continue;
            }
            Slog.w(TAG, "SystemConfig contained an invalid user type: " + type);
        }
        return resultBitSet;
    }

    /** Returns a sorted array consisting of the keyset of the provided map. */
    private static String[] getAndSortKeysFromMap(ArrayMap<String, ?> map) {
        final String[] userTypeList = new String[map.size()];
        for (int i = 0; i < map.size(); i++) {
            userTypeList[i] = map.keyAt(i);
        }
        Arrays.sort(userTypeList);
        return userTypeList;
    }

    void dump(PrintWriter pw) {
        try (IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ")) {
            dumpIndented(ipw);
        }
    }

    private void dumpIndented(IndentingPrintWriter pw) {
        final int mode = getWhitelistMode();
        pw.println("Whitelisted packages per user type");

        pw.increaseIndent();
        pw.print("Mode: ");
        pw.print(mode);
        pw.print(isEnforceMode(mode) ? " (enforced)" : "");
        pw.print(isLogMode(mode) ? " (logged)" : "");
        pw.print(isImplicitWhitelistMode(mode) ? " (implicit)" : "");
        pw.print(isIgnoreOtaMode(mode) ? " (ignore OTAs)" : "");
        pw.println();
        pw.decreaseIndent();

        pw.increaseIndent();
        pw.println("Legend");
        pw.increaseIndent();
        for (int idx = 0; idx < mUserTypes.length; idx++) {
            pw.println(idx + " -> " + mUserTypes[idx]);
        }
        pw.decreaseIndent(); pw.decreaseIndent();

        pw.increaseIndent();
        final int size = mWhitelistedPackagesForUserTypes.size();
        if (size == 0) {
            pw.println("No packages");
            pw.decreaseIndent();
            return;
        }
        pw.print(size); pw.println(" packages:");
        pw.increaseIndent();
        for (int pkgIdx = 0; pkgIdx < size; pkgIdx++) {
            final String pkgName = mWhitelistedPackagesForUserTypes.keyAt(pkgIdx);
            pw.print(pkgName); pw.print(": ");
            final long userTypesBitSet = mWhitelistedPackagesForUserTypes.valueAt(pkgIdx);
            for (int idx = 0; idx < mUserTypes.length; idx++) {
                if ((userTypesBitSet & (1 << idx)) != 0) {
                    pw.print(idx); pw.print(" ");
                }
            }
            pw.println();
        }
        pw.decreaseIndent(); pw.decreaseIndent();

        pw.increaseIndent();
        dumpPackageWhitelistProblems(pw, mode, /* verbose= */ true, /* criticalOnly= */ false);
        pw.decreaseIndent();
    }

    void dumpPackageWhitelistProblems(IndentingPrintWriter pw, @PackageWhitelistMode int mode,
            boolean verbose, boolean criticalOnly) {
        // Handle special cases first
        if (mode == USER_TYPE_PACKAGE_WHITELIST_MODE_NONE) {
            mode = getWhitelistMode();
        } else if (mode == USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT) {
            mode = getDeviceDefaultWhitelistMode();
        }
        if (criticalOnly) {
            // Ignore log mode (if set) since log-only issues are not critical.
            mode &= ~USER_TYPE_PACKAGE_WHITELIST_MODE_LOG;
        }
        Slog.v(TAG, "dumpPackageWhitelistProblems(): using mode " + modeToString(mode));

        final List<String> errors = getPackagesWhitelistErrors(mode);
        showIssues(pw, verbose, errors, "errors");

        if (criticalOnly) return;

        final List<String> warnings = getPackagesWhitelistWarnings();
        showIssues(pw, verbose, warnings, "warnings");
    }

    private static void showIssues(IndentingPrintWriter pw, boolean verbose, List<String> issues,
            String issueType) {
        final int size = issues.size();
        if (size == 0) {
            if (verbose) {
                pw.print("No "); pw.println(issueType);
            }
            return;
        }
        if (verbose) {
            pw.print(size); pw.print(' '); pw.println(issueType);
            pw.increaseIndent();
        }
        for (int i = 0; i < size; i++) {
            pw.println(issues.get(i));
        }
        if (verbose) {
            pw.decreaseIndent();
        }
    }
}
