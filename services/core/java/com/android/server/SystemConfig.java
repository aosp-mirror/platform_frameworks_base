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

package com.android.server;

import static com.android.internal.util.ArrayUtils.appendInt;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SignedPackage;
import android.os.Build;
import android.os.CarrierAssociatedAppEntry;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.VintfRuntimeInfo;
import android.os.incremental.IncrementalManager;
import android.os.storage.StorageManager;
import android.permission.PermissionManager.SplitPermissionInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimingsTraceLog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.pm.RoSystemFeatures;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.build.UnboundedSdkLevel;
import com.android.server.pm.permission.PermissionAllowlist;

import libcore.io.IoUtils;
import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads global system configuration info.
 * Note: Initializing this class hits the disk and is slow.  This class should generally only be
 * accessed by the system_server process.
 *
 * @hide
 */
public class SystemConfig {
    static final String TAG = "SystemConfig";

    static SystemConfig sInstance;

    // permission flag, determines which types of configuration are allowed to be read
    private static final int ALLOW_FEATURES = 0x001;
    private static final int ALLOW_LIBS = 0x002;
    private static final int ALLOW_PERMISSIONS = 0x004;
    private static final int ALLOW_APP_CONFIGS = 0x008;
    private static final int ALLOW_PRIVAPP_PERMISSIONS = 0x010;
    private static final int ALLOW_OEM_PERMISSIONS = 0x020;
    private static final int ALLOW_HIDDENAPI_WHITELISTING = 0x040;
    private static final int ALLOW_ASSOCIATIONS = 0x080;
    // ALLOW_OVERRIDE_APP_RESTRICTIONS allows to use "allow-in-power-save-except-idle",
    // "allow-in-power-save", "allow-in-data-usage-save","allow-unthrottled-location",
    // "allow-ignore-location-settings" and "allow-adas-location-settings".
    private static final int ALLOW_OVERRIDE_APP_RESTRICTIONS = 0x100;
    private static final int ALLOW_IMPLICIT_BROADCASTS = 0x200;
    private static final int ALLOW_VENDOR_APEX = 0x400;
    private static final int ALLOW_SIGNATURE_PERMISSIONS = 0x800;
    private static final int ALLOW_ALL = ~0;

    // property for runtime configuration differentiation
    private static final String SKU_PROPERTY = "ro.boot.product.hardware.sku";

    // property for runtime configuration differentiation in vendor
    private static final String VENDOR_SKU_PROPERTY = "ro.boot.product.vendor.sku";

    // property for runtime configuration differentation in product
    private static final String PRODUCT_SKU_PROPERTY = "ro.boot.hardware.sku";

    private static final ArrayMap<String, ArraySet<String>> EMPTY_PERMISSIONS =
            new ArrayMap<>();

    // Group-ids that are given to all packages as read from etc/permissions/*.xml.
    int[] mGlobalGids = EmptyArray.INT;

    // These are the built-in uid -> permission mappings that were read from the
    // system configuration files.
    final SparseArray<ArraySet<String>> mSystemPermissions = new SparseArray<>();

    final ArrayList<SplitPermissionInfo> mSplitPermissions = new ArrayList<>();

    private static boolean isAtLeastSdkLevel(String version) {
        try {
            return UnboundedSdkLevel.isAtLeast(version);
        } catch (IllegalArgumentException e) {
            // UnboundedSdkLevel throws when it sees a known old codename
            return false;
        }
    }

    private static boolean isAtMostSdkLevel(String version) {
        try {
            return UnboundedSdkLevel.isAtMost(version);
        } catch (IllegalArgumentException e) {
            // UnboundedSdkLevel throws when it sees a known old codename
            return true;
        }
    }

    public static final class SharedLibraryEntry {
        public final String name;
        public final String filename;
        public final String[] dependencies;

        /**
         * SDK version this library was added to the BOOTCLASSPATH.
         *
         * <p>At the SDK level specified in this field and higher, the apps' uses-library tags for
         * this library will be ignored, since the library is always available on BOOTCLASSPATH.
         *
         * <p>0 means not specified.
         */
        public final String onBootclasspathSince;

        /**
         * SDK version this library was removed from the BOOTCLASSPATH.
         *
         * <p>At the SDK level specified in this field and higher, this library needs to be
         * explicitly added by apps. For compatibility reasons, when an app
         * targets an SDK less than the value of this attribute, this library is automatically
         * added.
         *
         * <p>0 means not specified.
         */
        public final String onBootclasspathBefore;

        /**
         * Declares whether this library can be safely ignored from <uses-library> tags.
         *
         * <p> This can happen if the library initially had to be explicitly depended-on using that
         * tag but has since been moved to the BOOTCLASSPATH which means now is always available
         * and the tag is no longer required.
         */
        public final boolean canBeSafelyIgnored;

        public final boolean isNative;


        @VisibleForTesting
        public SharedLibraryEntry(String name, String filename, String[] dependencies,
                boolean isNative) {
            this(name, filename, dependencies, null /* onBootclasspathSince */,
                    null /* onBootclasspathBefore */, isNative);
        }

        @VisibleForTesting
        public SharedLibraryEntry(String name, String filename, String[] dependencies,
                String onBootclasspathSince, String onBootclasspathBefore) {
            this(name, filename, dependencies, onBootclasspathSince, onBootclasspathBefore,
                    false /* isNative */);
        }

        SharedLibraryEntry(String name, String filename, String[] dependencies,
                String onBootclasspathSince, String onBootclasspathBefore, boolean isNative) {
            this.name = name;
            this.filename = filename;
            this.dependencies = dependencies;
            this.onBootclasspathSince = onBootclasspathSince;
            this.onBootclasspathBefore = onBootclasspathBefore;
            this.isNative = isNative;

            // this entry can be ignored if either:
            // - onBootclasspathSince is set and we are at or past that SDK
            // - onBootclasspathBefore is set and we are before that SDK
            canBeSafelyIgnored =
                    (this.onBootclasspathSince != null
                            && isAtLeastSdkLevel(this.onBootclasspathSince))
                            || (this.onBootclasspathBefore != null
                            && !isAtLeastSdkLevel(this.onBootclasspathBefore));
        }
    }

    /**
     * Utility class for testing interaction with compile-time defined system features.
     * @hide
    */
    @VisibleForTesting
    public static class Injector {
        /** Whether a system feature is defined as enabled and available at compile-time. */
        public boolean isReadOnlySystemEnabledFeature(String featureName, int version) {
            return Boolean.TRUE.equals(RoSystemFeatures.maybeHasFeature(featureName, version));
        }

        /** Whether a system feature is defined as disabled and unavailable at compile-time. */
        public boolean isReadOnlySystemDisabledFeature(String featureName, int version) {
            return Boolean.FALSE.equals(RoSystemFeatures.maybeHasFeature(featureName, version));
        }

        /** The full set of system features defined as compile-time enabled and available. */
        public ArrayMap<String, FeatureInfo> getReadOnlySystemEnabledFeatures() {
            return RoSystemFeatures.getReadOnlySystemEnabledFeatures();
        }
    }

    private final Injector mInjector;

    // These are the built-in shared libraries that were read from the
    // system configuration files. Keys are the library names; values are
    // the individual entries that contain information such as filename
    // and dependencies.
    final ArrayMap<String, SharedLibraryEntry> mSharedLibraries = new ArrayMap<>();

    // These are the features this devices supports that were read from the
    // system configuration files.
    final ArrayMap<String, FeatureInfo> mAvailableFeatures;

    // These are the features which this device doesn't support; the OEM
    // partition uses these to opt-out of features from the system image.
    final ArraySet<String> mUnavailableFeatures = new ArraySet<>();

    public static final class PermissionEntry {
        public final String name;
        public int[] gids;
        public boolean perUser;

        PermissionEntry(String name, boolean perUser) {
            this.name = name;
            this.perUser = perUser;
        }
    }

    // These are the permission -> gid mappings that were read from the
    // system configuration files.
    final ArrayMap<String, PermissionEntry> mPermissions = new ArrayMap<>();

    // These are the packages that are white-listed to be able to run in the
    // background while in power save mode (but not whitelisted from device idle modes),
    // as read from the configuration files.
    final ArraySet<String> mAllowInPowerSaveExceptIdle = new ArraySet<>();

    // These are the packages that are white-listed to be able to run in the
    // background while in power save mode, as read from the configuration files.
    final ArraySet<String> mAllowInPowerSave = new ArraySet<>();

    // These are the packages that are white-listed to be able to run in the
    // background while in data-usage save mode, as read from the configuration files.
    final ArraySet<String> mAllowInDataUsageSave = new ArraySet<>();

    // These are the packages that are white-listed to be able to run background location
    // without throttling, as read from the configuration files.
    final ArraySet<String> mAllowUnthrottledLocation = new ArraySet<>();

    // These are the packages that are allow-listed to be able to retrieve location when
    // the location state is driver assistance only.
    final ArrayMap<String, ArraySet<String>> mAllowAdasSettings = new ArrayMap<>();

    // These are the packages that are white-listed to be able to retrieve location even when user
    // location settings are off, for emergency purposes, as read from the configuration files.
    final ArrayMap<String, ArraySet<String>> mAllowIgnoreLocationSettings = new ArrayMap<>();

    // These are the packages that are allow-listed to be able to access camera when
    // the camera privacy state is enabled.
    final ArraySet<String> mAllowlistCameraPrivacy = new ArraySet<>();

    // These are the action strings of broadcasts which are whitelisted to
    // be delivered anonymously even to apps which target O+.
    final ArraySet<String> mAllowImplicitBroadcasts = new ArraySet<>();

    // These are the packages that are exempted from the background restriction applied
    // by the system automatically, i.e., due to high background current drain.
    final ArraySet<String> mBgRestrictionExemption = new ArraySet<>();

    // These are the package names of apps which should be automatically granted domain verification
    // for all of their domains. The only way these apps can be overridden by the user is by
    // explicitly disabling overall link handling support in app info.
    final ArraySet<String> mLinkedApps = new ArraySet<>();

    // These are the components that are enabled by default as VR mode listener services.
    final ArraySet<ComponentName> mDefaultVrComponents = new ArraySet<>();

    // These are the permitted backup transport service components
    final ArraySet<ComponentName> mBackupTransportWhitelist = new ArraySet<>();

    // These are packages mapped to maps of component class name to default enabled state.
    final ArrayMap<String, ArrayMap<String, Boolean>> mPackageComponentEnabledState =
            new ArrayMap<>();

    // Package names that are exempted from private API blacklisting
    final ArraySet<String> mHiddenApiPackageWhitelist = new ArraySet<>();

    // The list of carrier applications which should be disabled until used.
    // This function suppresses update notifications for these pre-installed apps.
    // In SubscriptionInfoUpdater, the listed applications are disabled until used when all of the
    // following conditions are met.
    // 1. Not currently carrier-privileged according to the inserted SIM
    // 2. Pre-installed
    // 3. In the default state (enabled but not explicitly)
    // And SubscriptionInfoUpdater undoes this and marks the app enabled when a SIM is inserted
    // that marks the app as carrier privileged. It also grants the app default permissions
    // for Phone and Location. As such, apps MUST only ever be added to this list if they
    // obtain user consent to access their location through other means.
    final ArraySet<String> mDisabledUntilUsedPreinstalledCarrierApps = new ArraySet<>();

    // These are the packages of carrier-associated apps which should be disabled until used until
    // a SIM is inserted which grants carrier privileges to that carrier app.
    final ArrayMap<String, List<CarrierAssociatedAppEntry>>
            mDisabledUntilUsedPreinstalledCarrierAssociatedApps = new ArrayMap<>();

    private final PermissionAllowlist mPermissionAllowlist = new PermissionAllowlist();

    // Allowed associations between applications.  If there are any entries
    // for an app, those are the only associations allowed; otherwise, all associations
    // are allowed.  Allowing an association from app A to app B means app A can not
    // associate with any other apps, but does not limit what apps B can associate with.
    final ArrayMap<String, ArraySet<String>> mAllowedAssociations = new ArrayMap<>();

    private final ArraySet<String> mBugreportWhitelistedPackages = new ArraySet<>();
    private final ArraySet<String> mAppDataIsolationWhitelistedApps = new ArraySet<>();

    // These packages will be set as 'prevent disable', where they are no longer possible
    // for the end user to disable via settings. This flag should only be used for packages
    // which meet the 'force or keep enabled apps' policy.
    private final ArrayList<String> mPreventUserDisablePackages = new ArrayList<>();

    // Map of packagesNames to userTypes. Stored temporarily until cleared by UserManagerService().
    private ArrayMap<String, Set<String>> mPackageToUserTypeWhitelist = new ArrayMap<>();
    private ArrayMap<String, Set<String>> mPackageToUserTypeBlacklist = new ArrayMap<>();

    private final ArraySet<String> mRollbackWhitelistedPackages = new ArraySet<>();
    private final ArraySet<String> mWhitelistedStagedInstallers = new ArraySet<>();
    // A map from package name of vendor APEXes that can be updated to an installer package name
    // allowed to install updates for it.
    private final ArrayMap<String, String> mAllowedVendorApexes = new ArrayMap<>();
    // A set of package names that are allowed to use <install-constraints> manifest tag.
    private final Set<String> mInstallConstraintsAllowlist = new ArraySet<>();

    private String mModulesInstallerPackageName;
    // Update ownership for system applications and the installers eligible to update them.
    private final ArrayMap<String, String> mUpdateOwnersForSystemApps = new ArrayMap<>();

    // Set of package names that should not be marked as "stopped" during initial device boot
    // or when adding a new user. A new package not contained in this set will be
    // marked as stopped by the system
    @NonNull private final Set<String> mInitialNonStoppedSystemPackages = new ArraySet<>();

    // Which packages (key) are allowed to join particular SharedUid (value).
    @NonNull private final ArrayMap<String, String> mPackageToSharedUidAllowList = new ArrayMap<>();

    // A map of preloaded package names and the path to its app metadata file path.
    private final ArrayMap<String, String> mAppMetadataFilePaths = new ArrayMap<>();

    // A set of pre-installed package names that requires strict signature verification once
    // updated to avoid cached/potentially tampered results.
    private final Set<String> mPreinstallPackagesWithStrictSignatureCheck = new ArraySet<>();

    // A set of packages that should be considered "trusted packages" by ECM (Enhanced
    // Confirmation Mode). "Trusted packages" are exempt from ECM (i.e., they will never be
    // considered "restricted").
    private final ArraySet<SignedPackage> mEnhancedConfirmationTrustedPackages = new ArraySet<>();

    // A set of packages that should be considered "trusted installers" by ECM (Enhanced
    // Confirmation Mode). "Trusted installers", and all apps installed by a trusted installer, are
    // exempt from ECM (i.e., they will never be considered "restricted").
    private final ArraySet<SignedPackage> mEnhancedConfirmationTrustedInstallers = new ArraySet<>();

    // A map of UIDs defined by OEMs, mapping from name to value. The UIDs will be registered at the
    // start of the system which allows OEMs to create and register their system services.
    @NonNull private final ArrayMap<String, Integer> mOemDefinedUids = new ArrayMap<>();

    /**
     * Map of system pre-defined, uniquely named actors; keys are namespace,
     * value maps actor name to package name.
     */
    private Map<String, Map<String, String>> mNamedActors = null;

    // Package name of the package pre-installed on a read-only
    // partition that is used to verify if an overlay package fulfills
    // the 'config_signature' policy by comparing their signatures:
    // if the overlay package is signed with the same certificate as
    // the package declared in 'overlay-config-signature' tag, then the
    // overlay package fulfills the 'config_signature' policy.
    private String mOverlayConfigSignaturePackage;

    public static SystemConfig getInstance() {
        if (!isSystemProcess()) {
            Slog.wtf(TAG, "SystemConfig is being accessed by a process other than "
                    + "system_server.");
        }

        synchronized (SystemConfig.class) {
            if (sInstance == null) {
                sInstance = new SystemConfig();
            }
            return sInstance;
        }
    }

    public int[] getGlobalGids() {
        return mGlobalGids;
    }

    public SparseArray<ArraySet<String>> getSystemPermissions() {
        return mSystemPermissions;
    }

    public ArrayList<SplitPermissionInfo> getSplitPermissions() {
        return mSplitPermissions;
    }

    public ArrayMap<String, SharedLibraryEntry> getSharedLibraries() {
        return mSharedLibraries;
    }

    public ArrayMap<String, FeatureInfo> getAvailableFeatures() {
        return mAvailableFeatures;
    }

    public ArrayMap<String, PermissionEntry> getPermissions() {
        return mPermissions;
    }

    public ArraySet<String> getAllowImplicitBroadcasts() {
        return mAllowImplicitBroadcasts;
    }

    public ArraySet<String> getAllowInPowerSaveExceptIdle() {
        return mAllowInPowerSaveExceptIdle;
    }

    public ArraySet<String> getAllowInPowerSave() {
        return mAllowInPowerSave;
    }

    public ArraySet<String> getAllowInDataUsageSave() {
        return mAllowInDataUsageSave;
    }

    public ArraySet<String> getAllowUnthrottledLocation() {
        return mAllowUnthrottledLocation;
    }

    public ArrayMap<String, ArraySet<String>> getAllowAdasLocationSettings() {
        return mAllowAdasSettings;
    }

    public ArrayMap<String, ArraySet<String>> getAllowIgnoreLocationSettings() {
        return mAllowIgnoreLocationSettings;
    }

    public ArraySet<String> getBgRestrictionExemption() {
        return mBgRestrictionExemption;
    }

    public ArraySet<String> getLinkedApps() {
        return mLinkedApps;
    }

    public ArraySet<String> getHiddenApiWhitelistedApps() {
        return mHiddenApiPackageWhitelist;
    }

    public ArraySet<ComponentName> getDefaultVrComponents() {
        return mDefaultVrComponents;
    }

    public ArraySet<ComponentName> getBackupTransportWhitelist() {
        return mBackupTransportWhitelist;
    }

    public ArrayMap<String, Boolean> getComponentsEnabledStates(String packageName) {
        return mPackageComponentEnabledState.get(packageName);
    }

    public ArraySet<String> getDisabledUntilUsedPreinstalledCarrierApps() {
        return mDisabledUntilUsedPreinstalledCarrierApps;
    }

    public ArrayMap<String, List<CarrierAssociatedAppEntry>>
            getDisabledUntilUsedPreinstalledCarrierAssociatedApps() {
        return mDisabledUntilUsedPreinstalledCarrierAssociatedApps;
    }

    public PermissionAllowlist getPermissionAllowlist() {
        return mPermissionAllowlist;
    }

    public ArrayMap<String, ArraySet<String>> getAllowedAssociations() {
        return mAllowedAssociations;
    }

    public ArraySet<String> getCameraPrivacyAllowlist() {
        return mAllowlistCameraPrivacy;
    }

    public ArraySet<String> getBugreportWhitelistedPackages() {
        return mBugreportWhitelistedPackages;
    }

    public Set<String> getRollbackWhitelistedPackages() {
        return mRollbackWhitelistedPackages;
    }

    public Set<String> getWhitelistedStagedInstallers() {
        return mWhitelistedStagedInstallers;
    }

    public Map<String, String> getAllowedVendorApexes() {
        return mAllowedVendorApexes;
    }

    public Set<String> getInstallConstraintsAllowlist() {
        return mInstallConstraintsAllowlist;
    }

    public String getModulesInstallerPackageName() {
        return mModulesInstallerPackageName;
    }

    /**
     * Gets the update owner of the given package from "update-ownership" tags in sysconfig.
     */
    public @Nullable String getSystemAppUpdateOwnerPackageName(@NonNull String packageName) {
        return mUpdateOwnersForSystemApps.get(packageName);
    }

    public ArraySet<String> getAppDataIsolationWhitelistedApps() {
        return mAppDataIsolationWhitelistedApps;
    }

    public @NonNull ArrayList<String> getPreventUserDisablePackages() {
        return mPreventUserDisablePackages;
    }

    /**
     * Gets map of packagesNames to userTypes, dictating on which user types each package should be
     * initially installed, and then removes this map from SystemConfig.
     * Called by UserManagerService when it is constructed.
     */
    public ArrayMap<String, Set<String>> getAndClearPackageToUserTypeWhitelist() {
        ArrayMap<String, Set<String>> r = mPackageToUserTypeWhitelist;
        mPackageToUserTypeWhitelist = new ArrayMap<>(0);
        return r;
    }

    /**
     * Gets map of packagesNames to userTypes, dictating on which user types each package should NOT
     * be initially installed, even if they are whitelisted, and then removes this map from
     * SystemConfig.
     * Called by UserManagerService when it is constructed.
     */
    public ArrayMap<String, Set<String>> getAndClearPackageToUserTypeBlacklist() {
        ArrayMap<String, Set<String>> r = mPackageToUserTypeBlacklist;
        mPackageToUserTypeBlacklist = new ArrayMap<>(0);
        return r;
    }

    @NonNull
    public Map<String, Map<String, String>> getNamedActors() {
        return mNamedActors != null ? mNamedActors : Collections.emptyMap();
    }

    @Nullable
    public String getOverlayConfigSignaturePackage() {
        return TextUtils.isEmpty(mOverlayConfigSignaturePackage)
                ? null : mOverlayConfigSignaturePackage;
    }

    public Set<String> getInitialNonStoppedSystemPackages() {
        return mInitialNonStoppedSystemPackages;
    }

    @NonNull
    public ArrayMap<String, String> getPackageToSharedUidAllowList() {
        return mPackageToSharedUidAllowList;
    }

    public ArrayMap<String, String> getAppMetadataFilePaths() {
        return mAppMetadataFilePaths;
    }

    public Set<String> getPreinstallPackagesWithStrictSignatureCheck() {
        return mPreinstallPackagesWithStrictSignatureCheck;
    }

    public ArraySet<SignedPackage> getEnhancedConfirmationTrustedPackages() {
        return mEnhancedConfirmationTrustedPackages;
    }

    public ArraySet<SignedPackage> getEnhancedConfirmationTrustedInstallers() {
        return mEnhancedConfirmationTrustedInstallers;
    }

    @NonNull
    public ArrayMap<String, Integer> getOemDefinedUids() {
        return mOemDefinedUids;
    }

    /**
     * Only use for testing. Do NOT use in production code.
     * @param readPermissions false to create an empty SystemConfig; true to read the permissions.
     */
    @VisibleForTesting
    public SystemConfig(boolean readPermissions) {
        this(readPermissions, new Injector());
    }

    /**
     * Only use for testing. Do NOT use in production code.
     * @param readPermissions false to create an empty SystemConfig; true to read the permissions.
     * @param injector Additional dependency injection for testing.
     */
    @VisibleForTesting
    public SystemConfig(boolean readPermissions, Injector injector) {
        mInjector = injector;
        mAvailableFeatures = mInjector.getReadOnlySystemEnabledFeatures();

        if (readPermissions) {
            Slog.w(TAG, "Constructing a test SystemConfig");
            readAllPermissions();
        } else {
            Slog.w(TAG, "Constructing an empty test SystemConfig");
        }
    }

    SystemConfig() {
        mInjector = new Injector();
        mAvailableFeatures = mInjector.getReadOnlySystemEnabledFeatures();

        TimingsTraceLog log = new TimingsTraceLog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
        log.traceBegin("readAllPermissions");
        try {
            readAllPermissions();
            readPublicNativeLibrariesList();
        } finally {
            log.traceEnd();
        }
    }

    private void readAllPermissions() {
        final XmlPullParser parser = Xml.newPullParser();

        // Read configuration from system
        readPermissions(parser, Environment.buildPath(
                Environment.getRootDirectory(), "etc", "sysconfig"), ALLOW_ALL);

        // Read configuration from the old permissions dir
        readPermissions(parser, Environment.buildPath(
                Environment.getRootDirectory(), "etc", "permissions"), ALLOW_ALL);

        // Vendors are only allowed to customize these
        int vendorPermissionFlag = ALLOW_LIBS | ALLOW_FEATURES | ALLOW_PRIVAPP_PERMISSIONS
                | ALLOW_SIGNATURE_PERMISSIONS | ALLOW_ASSOCIATIONS | ALLOW_VENDOR_APEX;
        if (Build.VERSION.DEVICE_INITIAL_SDK_INT <= Build.VERSION_CODES.O_MR1) {
            // For backward compatibility
            vendorPermissionFlag |= (ALLOW_PERMISSIONS | ALLOW_APP_CONFIGS);
        }
        readPermissions(parser, Environment.buildPath(
                Environment.getVendorDirectory(), "etc", "sysconfig"), vendorPermissionFlag);
        readPermissions(parser, Environment.buildPath(
                Environment.getVendorDirectory(), "etc", "permissions"), vendorPermissionFlag);

        String vendorSkuProperty = SystemProperties.get(VENDOR_SKU_PROPERTY, "");
        if (!vendorSkuProperty.isEmpty()) {
            String vendorSkuDir = "sku_" + vendorSkuProperty;
            readPermissions(parser, Environment.buildPath(
                    Environment.getVendorDirectory(), "etc", "sysconfig", vendorSkuDir),
                    vendorPermissionFlag);
            readPermissions(parser, Environment.buildPath(
                    Environment.getVendorDirectory(), "etc", "permissions", vendorSkuDir),
                    vendorPermissionFlag);
        }

        // Allow ODM to customize system configs as much as Vendor, because /odm is another
        // vendor partition other than /vendor.
        int odmPermissionFlag = vendorPermissionFlag;
        readPermissions(parser, Environment.buildPath(
                Environment.getOdmDirectory(), "etc", "sysconfig"), odmPermissionFlag);
        readPermissions(parser, Environment.buildPath(
                Environment.getOdmDirectory(), "etc", "permissions"), odmPermissionFlag);

        String skuProperty = SystemProperties.get(SKU_PROPERTY, "");
        if (!skuProperty.isEmpty()) {
            String skuDir = "sku_" + skuProperty;

            readPermissions(parser, Environment.buildPath(
                    Environment.getOdmDirectory(), "etc", "sysconfig", skuDir), odmPermissionFlag);
            readPermissions(parser, Environment.buildPath(
                    Environment.getOdmDirectory(), "etc", "permissions", skuDir),
                    odmPermissionFlag);
        }

        // Allow OEM to customize these
        int oemPermissionFlag = ALLOW_FEATURES | ALLOW_OEM_PERMISSIONS | ALLOW_ASSOCIATIONS
                | ALLOW_VENDOR_APEX;
        readPermissions(parser, Environment.buildPath(
                Environment.getOemDirectory(), "etc", "sysconfig"), oemPermissionFlag);
        readPermissions(parser, Environment.buildPath(
                Environment.getOemDirectory(), "etc", "permissions"), oemPermissionFlag);

        // Allow Product to customize these configs
        // TODO(b/157203468): ALLOW_HIDDENAPI_WHITELISTING must be removed because we prohibited
        // the use of hidden APIs from the product partition.
        int productPermissionFlag = ALLOW_FEATURES | ALLOW_LIBS | ALLOW_PERMISSIONS
                | ALLOW_APP_CONFIGS | ALLOW_PRIVAPP_PERMISSIONS | ALLOW_SIGNATURE_PERMISSIONS
                | ALLOW_HIDDENAPI_WHITELISTING | ALLOW_ASSOCIATIONS
                | ALLOW_OVERRIDE_APP_RESTRICTIONS | ALLOW_IMPLICIT_BROADCASTS | ALLOW_VENDOR_APEX;
        if (Build.VERSION.DEVICE_INITIAL_SDK_INT <= Build.VERSION_CODES.R) {
            // TODO(b/157393157): This must check product interface enforcement instead of
            // DEVICE_INITIAL_SDK_INT for the devices without product interface enforcement.
            productPermissionFlag = ALLOW_ALL;
        }
        readPermissions(parser, Environment.buildPath(
                Environment.getProductDirectory(), "etc", "sysconfig"), productPermissionFlag);
        readPermissions(parser, Environment.buildPath(
                Environment.getProductDirectory(), "etc", "permissions"), productPermissionFlag);

        String productSkuProperty = SystemProperties.get(PRODUCT_SKU_PROPERTY, "");
        if (!productSkuProperty.isEmpty()) {
            String productSkuDir = "sku_" + productSkuProperty;
            readPermissions(parser, Environment.buildPath(
                    Environment.getProductDirectory(), "etc", "sysconfig", productSkuDir),
                    productPermissionFlag);
            readPermissions(parser, Environment.buildPath(
                    Environment.getProductDirectory(), "etc", "permissions", productSkuDir),
                    productPermissionFlag);
        }

        // Allow /system_ext to customize all system configs
        readPermissions(parser, Environment.buildPath(
                Environment.getSystemExtDirectory(), "etc", "sysconfig"), ALLOW_ALL);
        readPermissions(parser, Environment.buildPath(
                Environment.getSystemExtDirectory(), "etc", "permissions"), ALLOW_ALL);

        // Skip loading configuration from apex if it is not a system process.
        if (!isSystemProcess()) {
            return;
        }
        // Read configuration of features, libs and priv-app permissions from apex module.
        int apexPermissionFlag = ALLOW_LIBS | ALLOW_FEATURES | ALLOW_PRIVAPP_PERMISSIONS
                | ALLOW_SIGNATURE_PERMISSIONS;
        // TODO: Use a solid way to filter apex module folders?
        for (File f: FileUtils.listFilesOrEmpty(Environment.getApexDirectory())) {
            if (f.isFile() || f.getPath().contains("@")) {
                continue;
            }
            readPermissions(parser, Environment.buildPath(f, "etc", "permissions"),
                    apexPermissionFlag);
        }
    }

    @VisibleForTesting
    public void readPermissions(final XmlPullParser parser, File libraryDir, int permissionFlag) {
        // Read permissions from given directory.
        if (!libraryDir.exists() || !libraryDir.isDirectory()) {
            if (permissionFlag == ALLOW_ALL) {
                Slog.w(TAG, "No directory " + libraryDir + ", skipping");
            }
            return;
        }
        if (!libraryDir.canRead()) {
            Slog.w(TAG, "Directory " + libraryDir + " cannot be read");
            return;
        }

        // Iterate over the files in the directory and scan .xml files
        File platformFile = null;
        for (File f : libraryDir.listFiles()) {
            if (!f.isFile()) {
                continue;
            }

            // We'll read platform.xml last
            if (f.getPath().endsWith("etc/permissions/platform.xml")) {
                platformFile = f;
                continue;
            }

            if (!f.getPath().endsWith(".xml")) {
                Slog.i(TAG, "Non-xml file " + f + " in " + libraryDir + " directory, ignoring");
                continue;
            }
            if (!f.canRead()) {
                Slog.w(TAG, "Permissions library file " + f + " cannot be read");
                continue;
            }

            readPermissionsFromXml(parser, f, permissionFlag);
        }

        // Read platform permissions last so it will take precedence
        if (platformFile != null) {
            readPermissionsFromXml(parser, platformFile, permissionFlag);
        }
    }

    private void logNotAllowedInPartition(String name, File permFile, XmlPullParser parser) {
        Slog.w(TAG, "<" + name + "> not allowed in partition of "
                + permFile + " at " + parser.getPositionDescription());
    }

    private void readPermissionsFromXml(final XmlPullParser parser, File permFile,
            int permissionFlag) {
        final FileReader permReader;
        try {
            permReader = new FileReader(permFile);
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Couldn't find or open permissions file " + permFile);
            return;
        }
        Slog.i(TAG, "Reading permissions from " + permFile);

        final boolean lowRam = ActivityManager.isLowRamDeviceStatic();

        try {
            parser.setInput(permReader);

            int type;
            while ((type=parser.next()) != parser.START_TAG
                       && type != parser.END_DOCUMENT) {
                ;
            }

            if (type != parser.START_TAG) {
                throw new XmlPullParserException("No start tag found");
            }

            if (!parser.getName().equals("permissions") && !parser.getName().equals("config")) {
                throw new XmlPullParserException("Unexpected start tag in " + permFile
                        + ": found " + parser.getName() + ", expected 'permissions' or 'config'");
            }

            final boolean allowAll = permissionFlag == ALLOW_ALL;
            final boolean allowLibs = (permissionFlag & ALLOW_LIBS) != 0;
            final boolean allowFeatures = (permissionFlag & ALLOW_FEATURES) != 0;
            final boolean allowPermissions = (permissionFlag & ALLOW_PERMISSIONS) != 0;
            final boolean allowAppConfigs = (permissionFlag & ALLOW_APP_CONFIGS) != 0;
            final boolean allowPrivappPermissions = (permissionFlag & ALLOW_PRIVAPP_PERMISSIONS)
                    != 0;
            final boolean allowSignaturePermissions = (permissionFlag & ALLOW_SIGNATURE_PERMISSIONS)
                    != 0;
            final boolean allowOemPermissions = (permissionFlag & ALLOW_OEM_PERMISSIONS) != 0;
            final boolean allowApiWhitelisting = (permissionFlag & ALLOW_HIDDENAPI_WHITELISTING)
                    != 0;
            final boolean allowAssociations = (permissionFlag & ALLOW_ASSOCIATIONS) != 0;
            final boolean allowOverrideAppRestrictions =
                    (permissionFlag & ALLOW_OVERRIDE_APP_RESTRICTIONS) != 0;
            final boolean allowImplicitBroadcasts = (permissionFlag & ALLOW_IMPLICIT_BROADCASTS)
                    != 0;
            final boolean allowVendorApex = (permissionFlag & ALLOW_VENDOR_APEX) != 0;
            while (true) {
                XmlUtils.nextElement(parser);
                if (parser.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                String name = parser.getName();
                if (name == null) {
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                }
                switch (name) {
                    case "group": {
                        if (allowAll) {
                            String gidStr = parser.getAttributeValue(null, "gid");
                            if (gidStr != null) {
                                int gid = android.os.Process.getGidForName(gidStr);
                                mGlobalGids = appendInt(mGlobalGids, gid);
                            } else {
                                Slog.w(TAG, "<" + name + "> without gid in " + permFile + " at "
                                        + parser.getPositionDescription());
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "permission": {
                        if (allowPermissions) {
                            String perm = parser.getAttributeValue(null, "name");
                            if (perm == null) {
                                Slog.w(TAG, "<" + name + "> without name in " + permFile + " at "
                                        + parser.getPositionDescription());
                                XmlUtils.skipCurrentTag(parser);
                                break;
                            }
                            perm = perm.intern();
                            readPermission(parser, perm);
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                            XmlUtils.skipCurrentTag(parser);
                        }
                    } break;
                    case "assign-permission": {
                        if (allowPermissions) {
                            String perm = parser.getAttributeValue(null, "name");
                            if (perm == null) {
                                Slog.w(TAG, "<" + name + "> without name in " + permFile
                                        + " at " + parser.getPositionDescription());
                                XmlUtils.skipCurrentTag(parser);
                                break;
                            }
                            String uidStr = parser.getAttributeValue(null, "uid");
                            if (uidStr == null) {
                                Slog.w(TAG, "<" + name + "> without uid in " + permFile
                                        + " at " + parser.getPositionDescription());
                                XmlUtils.skipCurrentTag(parser);
                                break;
                            }
                            int uid = Process.getUidForName(uidStr);
                            if (uid < 0) {
                                Slog.w(TAG, "<" + name + "> with unknown uid \""
                                        + uidStr + "  in " + permFile + " at "
                                        + parser.getPositionDescription());
                                XmlUtils.skipCurrentTag(parser);
                                break;
                            }
                            perm = perm.intern();
                            ArraySet<String> perms = mSystemPermissions.get(uid);
                            if (perms == null) {
                                perms = new ArraySet<String>();
                                mSystemPermissions.put(uid, perms);
                            }
                            perms.add(perm);
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "split-permission": {
                        if (allowPermissions) {
                            readSplitPermission(parser, permFile);
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                            XmlUtils.skipCurrentTag(parser);
                        }
                    } break;
                    case "apex-library":
                        // "apex-library" is meant to behave exactly like "library"
                    case "library": {
                        if (allowLibs) {
                            String lname = parser.getAttributeValue(null, "name");
                            String lfile = parser.getAttributeValue(null, "file");
                            String ldependency = parser.getAttributeValue(null, "dependency");
                            String minDeviceSdk = parser.getAttributeValue(null, "min-device-sdk");
                            String maxDeviceSdk = parser.getAttributeValue(null, "max-device-sdk");
                            if (lname == null) {
                                Slog.w(TAG, "<" + name + "> without name in " + permFile + " at "
                                        + parser.getPositionDescription());
                            } else if (lfile == null) {
                                Slog.w(TAG, "<" + name + "> without file in " + permFile + " at "
                                        + parser.getPositionDescription());
                            } else {
                                boolean allowedMinSdk =
                                        minDeviceSdk == null || isAtLeastSdkLevel(minDeviceSdk);
                                boolean allowedMaxSdk =
                                        maxDeviceSdk == null || isAtMostSdkLevel(maxDeviceSdk);
                                final boolean exists = new File(lfile).exists();
                                if (allowedMinSdk && allowedMaxSdk && exists) {
                                    String bcpSince = parser.getAttributeValue(null,
                                            "on-bootclasspath-since");
                                    String bcpBefore = parser.getAttributeValue(null,
                                            "on-bootclasspath-before");
                                    SharedLibraryEntry entry = new SharedLibraryEntry(lname, lfile,
                                            ldependency == null
                                                    ? new String[0] : ldependency.split(":"),
                                            bcpSince, bcpBefore);
                                    mSharedLibraries.put(lname, entry);
                                } else {
                                    final StringBuilder msg = new StringBuilder(
                                            "Ignore shared library ").append(lname).append(":");
                                    if (!allowedMinSdk) {
                                        msg.append(" min-device-sdk=").append(minDeviceSdk);
                                    }
                                    if (!allowedMaxSdk) {
                                        msg.append(" max-device-sdk=").append(maxDeviceSdk);
                                    }
                                    if (!exists) {
                                        msg.append(" ").append(lfile).append(" does not exist");
                                    }
                                    Slog.i(TAG, msg.toString());
                                }
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "feature": {
                        if (allowFeatures) {
                            String fname = parser.getAttributeValue(null, "name");
                            int fversion = XmlUtils.readIntAttribute(parser, "version", 0);
                            boolean allowed;
                            if (!lowRam) {
                                allowed = true;
                            } else {
                                String notLowRam = parser.getAttributeValue(null, "notLowRam");
                                allowed = !"true".equals(notLowRam);
                            }
                            if (fname == null) {
                                Slog.w(TAG, "<" + name + "> without name in " + permFile + " at "
                                        + parser.getPositionDescription());
                            } else if (allowed) {
                                addFeature(fname, fversion);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "unavailable-feature": {
                        if (allowFeatures) {
                            String fname = parser.getAttributeValue(null, "name");
                            if (fname == null) {
                                Slog.w(TAG, "<" + name + "> without name in " + permFile
                                        + " at " + parser.getPositionDescription());
                            } else {
                                mUnavailableFeatures.add(fname);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "allow-in-power-save-except-idle": {
                        if (allowOverrideAppRestrictions) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            if (pkgname == null) {
                                Slog.w(TAG, "<" + name + "> without package in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else {
                                mAllowInPowerSaveExceptIdle.add(pkgname);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "allow-in-power-save": {
                        if (allowOverrideAppRestrictions) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            if (pkgname == null) {
                                Slog.w(TAG, "<" + name + "> without package in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else {
                                mAllowInPowerSave.add(pkgname);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "allow-in-data-usage-save": {
                        if (allowOverrideAppRestrictions) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            if (pkgname == null) {
                                Slog.w(TAG, "<" + name + "> without package in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else {
                                mAllowInDataUsageSave.add(pkgname);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "allow-unthrottled-location": {
                        if (allowOverrideAppRestrictions) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            if (pkgname == null) {
                                Slog.w(TAG, "<" + name + "> without package in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else {
                                mAllowUnthrottledLocation.add(pkgname);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "allow-adas-location-settings" : {
                        if (allowOverrideAppRestrictions) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            String attributionTag = parser.getAttributeValue(null,
                                    "attributionTag");
                            if (pkgname == null) {
                                Slog.w(TAG, "<" + name + "> without package in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else {
                                ArraySet<String> tags = mAllowAdasSettings.get(pkgname);
                                if (tags == null || !tags.isEmpty()) {
                                    if (tags == null) {
                                        tags = new ArraySet<>(1);
                                        mAllowAdasSettings.put(pkgname, tags);
                                    }
                                    if (!"*".equals(attributionTag)) {
                                        if ("null".equals(attributionTag)) {
                                            attributionTag = null;
                                        }
                                        tags.add(attributionTag);
                                    }
                                }
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "camera-privacy-allowlisted-app" : {
                        if (allowOverrideAppRestrictions) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            if (pkgname == null) {
                                Slog.w(TAG, "<" + name + "> without package in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else {
                                mAllowlistCameraPrivacy.add(pkgname);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "allow-ignore-location-settings": {
                        if (allowOverrideAppRestrictions) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            String attributionTag = parser.getAttributeValue(null,
                                    "attributionTag");
                            if (pkgname == null) {
                                Slog.w(TAG, "<" + name + "> without package in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else {
                                ArraySet<String> tags = mAllowIgnoreLocationSettings.get(pkgname);
                                if (tags == null || !tags.isEmpty()) {
                                    if (tags == null) {
                                        tags = new ArraySet<>(1);
                                        mAllowIgnoreLocationSettings.put(pkgname, tags);
                                    }
                                    if (!"*".equals(attributionTag)) {
                                        if ("null".equals(attributionTag)) {
                                            attributionTag = null;
                                        }
                                        tags.add(attributionTag);
                                    }
                                }
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "allow-implicit-broadcast": {
                        if (allowImplicitBroadcasts) {
                            String action = parser.getAttributeValue(null, "action");
                            if (action == null) {
                                Slog.w(TAG, "<" + name + "> without action in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else {
                                mAllowImplicitBroadcasts.add(action);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "app-link": {
                        if (allowAppConfigs) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            if (pkgname == null) {
                                Slog.w(TAG, "<" + name + "> without package in " + permFile
                                        + " at " + parser.getPositionDescription());
                            } else {
                                mLinkedApps.add(pkgname);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "bg-restriction-exemption": {
                        if (allowOverrideAppRestrictions) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            if (pkgname == null) {
                                Slog.w(TAG, "<" + name + "> without package in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else {
                                mBgRestrictionExemption.add(pkgname);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "default-enabled-vr-app": {
                        if (allowAppConfigs) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            String clsname = parser.getAttributeValue(null, "class");
                            if (pkgname == null) {
                                Slog.w(TAG, "<" + name + "> without package in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else if (clsname == null) {
                                Slog.w(TAG, "<" + name + "> without class in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else {
                                mDefaultVrComponents.add(new ComponentName(pkgname, clsname));
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "component-override": {
                        readComponentOverrides(parser, permFile);
                    } break;
                    case "backup-transport-whitelisted-service": {
                        if (allowFeatures) {
                            String serviceName = parser.getAttributeValue(null, "service");
                            if (serviceName == null) {
                                Slog.w(TAG, "<" + name + "> without service in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else {
                                ComponentName cn = ComponentName.unflattenFromString(serviceName);
                                if (cn == null) {
                                    Slog.w(TAG, "<" + name + "> with invalid service name "
                                            + serviceName + " in " + permFile
                                            + " at " + parser.getPositionDescription());
                                } else {
                                    mBackupTransportWhitelist.add(cn);
                                }
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "disabled-until-used-preinstalled-carrier-associated-app": {
                        if (allowAppConfigs) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            String carrierPkgname = parser.getAttributeValue(null,
                                    "carrierAppPackage");
                            if (pkgname == null || carrierPkgname == null) {
                                Slog.w(TAG, "<" + name
                                        + "> without package or carrierAppPackage in " + permFile
                                        + " at " + parser.getPositionDescription());
                            } else {
                                // APKs added to system images via OTA should specify the addedInSdk
                                // attribute, otherwise they may be enabled-by-default in too many
                                // cases. See CarrierAppUtils for more info.
                                int addedInSdk = CarrierAssociatedAppEntry.SDK_UNSPECIFIED;
                                String addedInSdkStr = parser.getAttributeValue(null, "addedInSdk");
                                if (!TextUtils.isEmpty(addedInSdkStr)) {
                                    try {
                                        addedInSdk = Integer.parseInt(addedInSdkStr);
                                    } catch (NumberFormatException e) {
                                        Slog.w(TAG, "<" + name + "> addedInSdk not an integer in "
                                                + permFile + " at "
                                                + parser.getPositionDescription());
                                        XmlUtils.skipCurrentTag(parser);
                                        break;
                                    }
                                }
                                List<CarrierAssociatedAppEntry> associatedPkgs =
                                        mDisabledUntilUsedPreinstalledCarrierAssociatedApps.get(
                                                carrierPkgname);
                                if (associatedPkgs == null) {
                                    associatedPkgs = new ArrayList<>();
                                    mDisabledUntilUsedPreinstalledCarrierAssociatedApps.put(
                                            carrierPkgname, associatedPkgs);
                                }
                                associatedPkgs.add(
                                        new CarrierAssociatedAppEntry(pkgname, addedInSdk));
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "disabled-until-used-preinstalled-carrier-app": {
                        if (allowAppConfigs) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            if (pkgname == null) {
                                Slog.w(TAG,
                                        "<" + name + "> without "
                                                + "package in " + permFile + " at "
                                                + parser.getPositionDescription());
                            } else {
                                mDisabledUntilUsedPreinstalledCarrierApps.add(pkgname);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "privapp-permissions": {
                        if (allowPrivappPermissions) {
                            // privapp permissions from system, apex, vendor, product and
                            // system_ext partitions are stored separately. This is to
                            // prevent xml files in the vendor partition from granting
                            // permissions to priv apps in the system partition and vice versa.
                            boolean vendor = permFile.toPath().startsWith(
                                    Environment.getVendorDirectory().toPath() + "/")
                                    || permFile.toPath().startsWith(
                                    Environment.getOdmDirectory().toPath() + "/");
                            boolean product = permFile.toPath().startsWith(
                                    Environment.getProductDirectory().toPath() + "/");
                            boolean systemExt = permFile.toPath().startsWith(
                                    Environment.getSystemExtDirectory().toPath() + "/");
                            boolean apex = permFile.toPath().startsWith(
                                    Environment.getApexDirectory().toPath() + "/");
                            if (vendor) {
                                readPrivAppPermissions(parser,
                                        mPermissionAllowlist.getVendorPrivilegedAppAllowlist());
                            } else if (product) {
                                readPrivAppPermissions(parser,
                                        mPermissionAllowlist.getProductPrivilegedAppAllowlist());
                            } else if (systemExt) {
                                readPrivAppPermissions(parser,
                                        mPermissionAllowlist.getSystemExtPrivilegedAppAllowlist());
                            } else if (apex) {
                                readApexPrivAppPermissions(parser, permFile,
                                        Environment.getApexDirectory().toPath());
                            } else {
                                readPrivAppPermissions(parser,
                                        mPermissionAllowlist.getPrivilegedAppAllowlist());
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                            XmlUtils.skipCurrentTag(parser);
                        }
                    } break;
                    case "signature-permissions": {
                        if (allowSignaturePermissions) {
                            // signature permissions from system, apex, vendor, product and
                            // system_ext partitions are stored separately. This is to
                            // prevent xml files in the vendor partition from granting
                            // permissions to signature apps in the system partition and vice versa.
                            boolean vendor = permFile.toPath().startsWith(
                                    Environment.getVendorDirectory().toPath() + "/")
                                    || permFile.toPath().startsWith(
                                    Environment.getOdmDirectory().toPath() + "/");
                            boolean product = permFile.toPath().startsWith(
                                    Environment.getProductDirectory().toPath() + "/");
                            boolean systemExt = permFile.toPath().startsWith(
                                    Environment.getSystemExtDirectory().toPath() + "/");
                            boolean apex = permFile.toPath().startsWith(
                                    Environment.getApexDirectory().toPath() + "/");
                            if (vendor) {
                                readSignatureAppPermissions(parser,
                                        mPermissionAllowlist.getVendorSignatureAppAllowlist());
                            } else if (product) {
                                readSignatureAppPermissions(parser,
                                        mPermissionAllowlist.getProductSignatureAppAllowlist());
                            } else if (systemExt) {
                                readSignatureAppPermissions(parser,
                                        mPermissionAllowlist.getSystemExtSignatureAppAllowlist());
                            } else if (apex) {
                                readSignatureAppPermissions(parser,
                                        mPermissionAllowlist.getApexSignatureAppAllowlist());
                            } else {
                                readSignatureAppPermissions(parser,
                                        mPermissionAllowlist.getSignatureAppAllowlist());
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                            XmlUtils.skipCurrentTag(parser);
                        }
                    } break;
                    case "oem-permissions": {
                        if (allowOemPermissions) {
                            readOemPermissions(parser);
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                            XmlUtils.skipCurrentTag(parser);
                        }
                    } break;
                    case "hidden-api-whitelisted-app": {
                        if (allowApiWhitelisting) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            if (pkgname == null) {
                                Slog.w(TAG, "<" + name + "> without package in "
                                        + permFile + " at " + parser.getPositionDescription());
                            } else {
                                mHiddenApiPackageWhitelist.add(pkgname);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "allow-association": {
                        if (allowAssociations) {
                            String target = parser.getAttributeValue(null, "target");
                            if (target == null) {
                                Slog.w(TAG, "<" + name + "> without target in " + permFile
                                        + " at " + parser.getPositionDescription());
                                XmlUtils.skipCurrentTag(parser);
                                break;
                            }
                            String allowed = parser.getAttributeValue(null, "allowed");
                            if (allowed == null) {
                                Slog.w(TAG, "<" + name + "> without allowed in " + permFile
                                        + " at " + parser.getPositionDescription());
                                XmlUtils.skipCurrentTag(parser);
                                break;
                            }
                            target = target.intern();
                            allowed = allowed.intern();
                            ArraySet<String> associations = mAllowedAssociations.get(target);
                            if (associations == null) {
                                associations = new ArraySet<>();
                                mAllowedAssociations.put(target, associations);
                            }
                            Slog.i(TAG, "Adding association: " + target + " <- " + allowed);
                            associations.add(allowed);
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "app-data-isolation-whitelisted-app": {
                        String pkgname = parser.getAttributeValue(null, "package");
                        if (pkgname == null) {
                            Slog.w(TAG, "<" + name + "> without package in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else {
                            mAppDataIsolationWhitelistedApps.add(pkgname);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "bugreport-whitelisted": {
                        String pkgname = parser.getAttributeValue(null, "package");
                        if (pkgname == null) {
                            Slog.w(TAG, "<" + name + "> without package in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else {
                            mBugreportWhitelistedPackages.add(pkgname);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "prevent-disable": {
                        String pkgname = parser.getAttributeValue(null, "package");
                        if (pkgname == null) {
                            Slog.w(TAG, "<" + name + "> without package in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else {
                            mPreventUserDisablePackages.add(pkgname);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "install-in-user-type": {
                        // NB: We allow any directory permission to declare install-in-user-type.
                        readInstallInUserType(parser,
                                mPackageToUserTypeWhitelist, mPackageToUserTypeBlacklist);
                    } break;
                    case "named-actor": {
                        String namespace = TextUtils.safeIntern(
                                parser.getAttributeValue(null, "namespace"));
                        String actorName = parser.getAttributeValue(null, "name");
                        String pkgName = TextUtils.safeIntern(
                                parser.getAttributeValue(null, "package"));
                        if (TextUtils.isEmpty(namespace)) {
                            Slog.wtf(TAG, "<" + name + "> without namespace in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else if (TextUtils.isEmpty(actorName)) {
                            Slog.wtf(TAG, "<" + name + "> without actor name in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else if (TextUtils.isEmpty(pkgName)) {
                            Slog.wtf(TAG, "<" + name + "> without package name in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else if ("android".equalsIgnoreCase(namespace)) {
                            throw new IllegalStateException("Defining " + actorName + " as "
                                    + pkgName + " for the android namespace is not allowed");
                        } else {
                            if (mNamedActors == null) {
                                mNamedActors = new ArrayMap<>();
                            }

                            Map<String, String> nameToPkgMap = mNamedActors.get(namespace);
                            if (nameToPkgMap == null) {
                                nameToPkgMap = new ArrayMap<>();
                                mNamedActors.put(namespace, nameToPkgMap);
                            } else if (nameToPkgMap.containsKey(actorName)) {
                                String existing = nameToPkgMap.get(actorName);
                                throw new IllegalStateException("Duplicate actor definition for "
                                        + namespace + "/" + actorName
                                        + "; defined as both " + existing + " and " + pkgName);
                            }

                            nameToPkgMap.put(actorName, pkgName);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "overlay-config-signature": {
                        if (allowAll) {
                            String pkgName = parser.getAttributeValue(null, "package");
                            if (pkgName == null) {
                                Slog.w(TAG, "<" + name + "> without package in " + permFile
                                        + " at " + parser.getPositionDescription());
                            } else {
                                if (TextUtils.isEmpty(mOverlayConfigSignaturePackage)) {
                                    mOverlayConfigSignaturePackage = pkgName.intern();
                                } else {
                                    throw new IllegalStateException("Reference signature package "
                                                  + "defined as both "
                                                  + mOverlayConfigSignaturePackage
                                                  + " and " + pkgName);
                                }
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "rollback-whitelisted-app": {
                        String pkgname = parser.getAttributeValue(null, "package");
                        if (pkgname == null) {
                            Slog.w(TAG, "<" + name + "> without package in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else {
                            mRollbackWhitelistedPackages.add(pkgname);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "whitelisted-staged-installer": {
                        if (allowAppConfigs) {
                            String pkgname = parser.getAttributeValue(null, "package");
                            boolean isModulesInstaller = XmlUtils.readBooleanAttribute(
                                    parser, "isModulesInstaller", false);
                            if (pkgname == null) {
                                Slog.w(TAG, "<" + name + "> without package in " + permFile
                                        + " at " + parser.getPositionDescription());
                            } else {
                                mWhitelistedStagedInstallers.add(pkgname);
                            }
                            if (isModulesInstaller) {
                                if (mModulesInstallerPackageName != null) {
                                    throw new IllegalStateException(
                                            "Multiple modules installers");
                                }
                                mModulesInstallerPackageName = pkgname;
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "allowed-vendor-apex": {
                        if (allowVendorApex) {
                            String pkgName = parser.getAttributeValue(null, "package");
                            String installerPkgName = parser.getAttributeValue(
                                    null, "installerPackage");
                            if (pkgName == null) {
                                Slog.w(TAG, "<" + name + "> without package in " + permFile
                                        + " at " + parser.getPositionDescription());
                            }
                            if (installerPkgName == null) {
                                Slog.w(TAG, "<" + name + "> without installerPackage in " + permFile
                                        + " at " + parser.getPositionDescription());
                            }
                            if (pkgName != null && installerPkgName != null) {
                                mAllowedVendorApexes.put(pkgName, installerPkgName);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "install-constraints-allowed": {
                        if (allowAppConfigs) {
                            String packageName = parser.getAttributeValue(null, "package");
                            if (packageName == null) {
                                Slog.w(TAG, "<" + name + "> without package in " + permFile
                                        + " at " + parser.getPositionDescription());
                            } else {
                                mInstallConstraintsAllowlist.add(packageName);
                            }
                        } else {
                            logNotAllowedInPartition(name, permFile, parser);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "update-ownership": {
                        final String packageName = parser.getAttributeValue(null /* namespace */,
                                "package");
                        final String installerName = parser.getAttributeValue(null /* namespace */,
                                "installer");
                        if (TextUtils.isEmpty(packageName)) {
                            Slog.w(TAG, "<" + name + "> without valid package in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else if (TextUtils.isEmpty(installerName)) {
                            Slog.w(TAG, "<" + name + "> without valid installer in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else {
                            mUpdateOwnersForSystemApps.put(packageName, installerName);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                    case "initial-package-state": {
                        String pkgName = parser.getAttributeValue(null, "package");
                        String stopped = parser.getAttributeValue(null, "stopped");
                        if (TextUtils.isEmpty(pkgName)) {
                            Slog.w(TAG, "<" + name + "> without package in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else if (TextUtils.isEmpty(stopped)) {
                            Slog.w(TAG, "<" + name + "> without stopped in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else if (!Boolean.parseBoolean(stopped)) {
                            mInitialNonStoppedSystemPackages.add(pkgName);
                        }
                    } break;
                    case "allow-package-shareduid": {
                        String pkgName = parser.getAttributeValue(null, "package");
                        String sharedUid = parser.getAttributeValue(null, "shareduid");
                        if (TextUtils.isEmpty(pkgName)) {
                            Slog.w(TAG, "<" + name + "> without package in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else if (TextUtils.isEmpty(sharedUid)) {
                            Slog.w(TAG, "<" + name + "> without shareduid in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else {
                            mPackageToSharedUidAllowList.put(pkgName, sharedUid);
                        }
                    } break;
                    case "asl-file": {
                        String packageName = parser.getAttributeValue(null, "package");
                        String path = parser.getAttributeValue(null, "path");
                        if (TextUtils.isEmpty(packageName)) {
                            Slog.w(TAG, "<" + name + "> without valid package in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else if (TextUtils.isEmpty(path)) {
                            Slog.w(TAG, "<" + name + "> without valid path in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else {
                            mAppMetadataFilePaths.put(packageName, path);
                        }
                    } break;
                    case "require-strict-signature": {
                        if (android.security.Flags.extendVbChainToUpdatedApk()) {
                            String packageName = parser.getAttributeValue(null, "package");
                            if (TextUtils.isEmpty(packageName)) {
                                Slog.w(TAG, "<" + name + "> without valid package in " + permFile
                                        + " at " + parser.getPositionDescription());
                            } else {
                                mPreinstallPackagesWithStrictSignatureCheck.add(packageName);
                            }
                        }
                    } break;
                    case "oem-defined-uid": {
                        final String uidName = parser.getAttributeValue(null, "name");
                        final String uidValue = parser.getAttributeValue(null, "uid");
                        if (TextUtils.isEmpty(uidName)) {
                            Slog.w(TAG, "<" + name + "> without valid uid name in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else if (TextUtils.isEmpty(uidValue)) {
                            Slog.w(TAG, "<" + name + "> without valid uid value in " + permFile
                                    + " at " + parser.getPositionDescription());
                        } else {
                            try {
                                final int oemDefinedUid = Integer.parseInt(uidValue);
                                mOemDefinedUids.put(uidName, oemDefinedUid);
                            } catch (NumberFormatException e) {
                                Slog.w(TAG, "<" + name + "> with invalid uid value: "
                                        + uidValue + " in " + permFile
                                        + " at " + parser.getPositionDescription());
                            }
                        }
                    } break;
                    case "enhanced-confirmation-trusted-package": {
                        if (android.permission.flags.Flags.enhancedConfirmationModeApisEnabled()) {
                            SignedPackage signedPackage = parseEnhancedConfirmationTrustedPackage(
                                    parser, permFile, name);
                            if (signedPackage != null) {
                                mEnhancedConfirmationTrustedPackages.add(signedPackage);
                            }
                            break;
                        }
                    } // fall through if flag is not enabled
                    case "enhanced-confirmation-trusted-installer": {
                        if (android.permission.flags.Flags.enhancedConfirmationModeApisEnabled()) {
                            SignedPackage signedPackage = parseEnhancedConfirmationTrustedPackage(
                                    parser, permFile, name);
                            if (signedPackage != null) {
                                mEnhancedConfirmationTrustedInstallers.add(signedPackage);
                            }
                            break;
                        }
                    } // fall through if flag is not enabled
                    default: {
                        Slog.w(TAG, "Tag " + name + " is unknown in "
                                + permFile + " at " + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                    } break;
                }
            }
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Got exception parsing permissions.", e);
        } catch (IOException e) {
            Slog.w(TAG, "Got exception parsing permissions.", e);
        } finally {
            IoUtils.closeQuietly(permReader);
        }

        // Some devices can be field-converted to FBE, so offer to splice in
        // those features if not already defined by the static config
        if (StorageManager.isFileEncrypted()) {
            addFeature(PackageManager.FEATURE_FILE_BASED_ENCRYPTION, 0);
            addFeature(PackageManager.FEATURE_SECURELY_REMOVES_USERS, 0);
        }

        // Help legacy devices that may not have updated their static config
        if (StorageManager.hasAdoptable()) {
            addFeature(PackageManager.FEATURE_ADOPTABLE_STORAGE, 0);
        }

        if (ActivityManager.isLowRamDeviceStatic()) {
            addFeature(PackageManager.FEATURE_RAM_LOW, 0);
        } else {
            addFeature(PackageManager.FEATURE_RAM_NORMAL, 0);
        }

        final int incrementalVersion = IncrementalManager.getVersion();
        if (incrementalVersion > 0) {
            addFeature(PackageManager.FEATURE_INCREMENTAL_DELIVERY, incrementalVersion);
        }

        if (PackageManager.APP_ENUMERATION_ENABLED_BY_DEFAULT) {
            addFeature(PackageManager.FEATURE_APP_ENUMERATION, 0);
        }

        if (Build.VERSION.DEVICE_INITIAL_SDK_INT >= Build.VERSION_CODES.Q) {
            addFeature(PackageManager.FEATURE_IPSEC_TUNNELS, 0);
        }

        enableIpSecTunnelMigrationOnVsrUAndAbove();

        if (isErofsSupported()) {
            if (isKernelVersionAtLeast(5, 10)) {
                addFeature(PackageManager.FEATURE_EROFS, 0);
            } else if (isKernelVersionAtLeast(4, 19)) {
                addFeature(PackageManager.FEATURE_EROFS_LEGACY, 0);
            }
        }

        for (String featureName : mUnavailableFeatures) {
            removeFeature(featureName);
        }
    }

    private @Nullable SignedPackage parseEnhancedConfirmationTrustedPackage(XmlPullParser parser,
            File permFile, String elementName) {
        String pkgName = parser.getAttributeValue(null, "package");
        if (TextUtils.isEmpty(pkgName)) {
            Slog.w(TAG, "<" + elementName + "> without package " + permFile + " at "
                    + parser.getPositionDescription());
            return null;
        }

        String certificateDigestStr = parser.getAttributeValue(null, "sha256-cert-digest");
        if (TextUtils.isEmpty(certificateDigestStr)) {
            Slog.w(TAG, "<" + elementName + "> without sha256-cert-digest in " + permFile
                    + " at " + parser.getPositionDescription());
            return null;
        }
        byte[] certificateDigest = null;
        try {
            certificateDigest = new Signature(certificateDigestStr.replace(":", "")).toByteArray();
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "<" + elementName + "> with invalid sha256-cert-digest in "
                    + permFile + " at " + parser.getPositionDescription());
            return null;
        }

        return new SignedPackage(pkgName, certificateDigest);
    }

    // This method only enables a new Android feature added in U and will not have impact on app
    // compatibility
    @SuppressWarnings("AndroidFrameworkCompatChange")
    private void enableIpSecTunnelMigrationOnVsrUAndAbove() {
        final int vsrApi =
                SystemProperties.getInt(
                        "ro.vendor.api_level", Build.VERSION.DEVICE_INITIAL_SDK_INT);
        if (vsrApi > Build.VERSION_CODES.TIRAMISU) {
            addFeature(PackageManager.FEATURE_IPSEC_TUNNEL_MIGRATION, 0);
        }
    }

    private void addFeature(String name, int version) {
        if (mInjector.isReadOnlySystemDisabledFeature(name, version)) {
            Slog.w(TAG, "Skipping feature addition for compile-time disabled feature: " + name);
            return;
        }
        FeatureInfo fi = mAvailableFeatures.get(name);
        if (fi == null) {
            fi = new FeatureInfo();
            fi.name = name;
            fi.version = version;
            mAvailableFeatures.put(name, fi);
        } else {
            fi.version = Math.max(fi.version, version);
        }
    }

    private void removeFeature(String name) {
        if (mInjector.isReadOnlySystemEnabledFeature(name, /*version=*/0)) {
            Slog.w(TAG, "Skipping feature removal for compile-time enabled feature: " + name);
            return;
        }
        if (mAvailableFeatures.remove(name) != null) {
            Slog.d(TAG, "Removed unavailable feature " + name);
        }
    }

    void readPermission(XmlPullParser parser, String name)
            throws IOException, XmlPullParserException {
        if (mPermissions.containsKey(name)) {
            throw new IllegalStateException("Duplicate permission definition for " + name);
        }

        final boolean perUser = XmlUtils.readBooleanAttribute(parser, "perUser", false);
        final PermissionEntry perm = new PermissionEntry(name, perUser);
        mPermissions.put(name, perm);

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if ("group".equals(tagName)) {
                String gidStr = parser.getAttributeValue(null, "gid");
                if (gidStr != null) {
                    int gid = Process.getGidForName(gidStr);
                    if (gid != -1) {
                        perm.gids = appendInt(perm.gids, gid);
                    } else {
                        Slog.w(TAG, "<group> with unknown gid \""
                                + gidStr + " for permission " + name + " in "
                                + parser.getPositionDescription());
                    }
                } else {
                    Slog.w(TAG, "<group> without gid at "
                            + parser.getPositionDescription());
                }
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    private void readPrivAppPermissions(@NonNull XmlPullParser parser,
            @NonNull ArrayMap<String, ArrayMap<String, Boolean>> allowlist)
            throws IOException, XmlPullParserException {
        readPermissionAllowlist(parser, allowlist, "privapp-permissions");
    }

    private void readSignatureAppPermissions(@NonNull XmlPullParser parser,
            @NonNull ArrayMap<String, ArrayMap<String, Boolean>> allowlist)
            throws IOException, XmlPullParserException {
        readPermissionAllowlist(parser, allowlist, "signature-permissions");
    }

    private void readInstallInUserType(XmlPullParser parser,
            Map<String, Set<String>> doInstallMap,
            Map<String, Set<String>> nonInstallMap)
            throws IOException, XmlPullParserException {
        final String packageName = parser.getAttributeValue(null, "package");
        if (TextUtils.isEmpty(packageName)) {
            Slog.w(TAG, "package is required for <install-in-user-type> in "
                    + parser.getPositionDescription());
            return;
        }

        Set<String> userTypesYes = doInstallMap.get(packageName);
        Set<String> userTypesNo = nonInstallMap.get(packageName);
        final int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            final String name = parser.getName();
            if ("install-in".equals(name)) {
                final String userType = parser.getAttributeValue(null, "user-type");
                if (TextUtils.isEmpty(userType)) {
                    Slog.w(TAG, "user-type is required for <install-in-user-type> in "
                            + parser.getPositionDescription());
                    continue;
                }
                if (userTypesYes == null) {
                    userTypesYes = new ArraySet<>();
                    doInstallMap.put(packageName, userTypesYes);
                }
                userTypesYes.add(userType);
            } else if ("do-not-install-in".equals(name)) {
                final String userType = parser.getAttributeValue(null, "user-type");
                if (TextUtils.isEmpty(userType)) {
                    Slog.w(TAG, "user-type is required for <install-in-user-type> in "
                            + parser.getPositionDescription());
                    continue;
                }
                if (userTypesNo == null) {
                    userTypesNo = new ArraySet<>();
                    nonInstallMap.put(packageName, userTypesNo);
                }
                userTypesNo.add(userType);
            } else {
                Slog.w(TAG, "unrecognized tag in <install-in-user-type> in "
                        + parser.getPositionDescription());
            }
        }
    }

    void readOemPermissions(XmlPullParser parser) throws IOException, XmlPullParserException {
        readPermissionAllowlist(parser, mPermissionAllowlist.getOemAppAllowlist(),
                "oem-permissions");
    }

    private static void readPermissionAllowlist(@NonNull XmlPullParser parser,
            @NonNull ArrayMap<String, ArrayMap<String, Boolean>> allowlist, @NonNull String tagName)
            throws IOException, XmlPullParserException {
        final String packageName = parser.getAttributeValue(null, "package");
        if (TextUtils.isEmpty(packageName)) {
            Slog.w(TAG, "package is required for <" + tagName + "> in "
                    + parser.getPositionDescription());
            return;
        }

        ArrayMap<String, Boolean> permissions = allowlist.get(packageName);
        if (permissions == null) {
            permissions = new ArrayMap<>();
        }
        final int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            final String name = parser.getName();
            if ("permission".equals(name)) {
                final String permissionName = parser.getAttributeValue(null, "name");
                if (TextUtils.isEmpty(permissionName)) {
                    Slog.w(TAG, "name is required for <permission> in "
                            + parser.getPositionDescription());
                    continue;
                }
                permissions.put(permissionName, Boolean.TRUE);
            } else if ("deny-permission".equals(name)) {
                String permissionName = parser.getAttributeValue(null, "name");
                if (TextUtils.isEmpty(permissionName)) {
                    Slog.w(TAG, "name is required for <deny-permission> in "
                            + parser.getPositionDescription());
                    continue;
                }
                permissions.put(permissionName, Boolean.FALSE);
            }
        }
        allowlist.put(packageName, permissions);
    }

    private void readSplitPermission(XmlPullParser parser, File permFile)
            throws IOException, XmlPullParserException {
        String splitPerm = parser.getAttributeValue(null, "name");
        if (splitPerm == null) {
            Slog.w(TAG, "<split-permission> without name in " + permFile + " at "
                    + parser.getPositionDescription());
            XmlUtils.skipCurrentTag(parser);
            return;
        }
        String targetSdkStr = parser.getAttributeValue(null, "targetSdk");
        int targetSdk = Build.VERSION_CODES.CUR_DEVELOPMENT + 1;
        if (!TextUtils.isEmpty(targetSdkStr)) {
            try {
                targetSdk = Integer.parseInt(targetSdkStr);
            } catch (NumberFormatException e) {
                Slog.w(TAG, "<split-permission> targetSdk not an integer in " + permFile + " at "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
                return;
            }
        }
        final int depth = parser.getDepth();
        List<String> newPermissions = new ArrayList<>();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            String name = parser.getName();
            if ("new-permission".equals(name)) {
                final String newName = parser.getAttributeValue(null, "name");
                if (TextUtils.isEmpty(newName)) {
                    Slog.w(TAG, "name is required for <new-permission> in "
                            + parser.getPositionDescription());
                    continue;
                }
                newPermissions.add(newName);
            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }
        if (!newPermissions.isEmpty()) {
            mSplitPermissions.add(new SplitPermissionInfo(splitPerm, newPermissions, targetSdk));
        }
    }

    private void readComponentOverrides(XmlPullParser parser, File permFile)
            throws IOException, XmlPullParserException {
        String pkgname = parser.getAttributeValue(null, "package");
        if (pkgname == null) {
            Slog.w(TAG, "<component-override> without package in "
                    + permFile + " at " + parser.getPositionDescription());
            return;
        }

        pkgname = pkgname.intern();

        final int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            if ("component".equals(parser.getName())) {
                String clsname = parser.getAttributeValue(null, "class");
                String enabled = parser.getAttributeValue(null, "enabled");
                if (clsname == null) {
                    Slog.w(TAG, "<component> without class in "
                            + permFile + " at " + parser.getPositionDescription());
                    return;
                } else if (enabled == null) {
                    Slog.w(TAG, "<component> without enabled in "
                            + permFile + " at " + parser.getPositionDescription());
                    return;
                }

                if (clsname.startsWith(".")) {
                    clsname = pkgname + clsname;
                }

                clsname = clsname.intern();

                ArrayMap<String, Boolean> componentEnabledStates =
                        mPackageComponentEnabledState.get(pkgname);
                if (componentEnabledStates == null) {
                    componentEnabledStates = new ArrayMap<>();
                    mPackageComponentEnabledState.put(pkgname,
                            componentEnabledStates);
                }

                componentEnabledStates.put(clsname, !"false".equals(enabled));
            }
        }
    }

    private void readPublicNativeLibrariesList() {
        readPublicLibrariesListFile(new File("/vendor/etc/public.libraries.txt"));
        String[] dirs = {"/system/etc", "/system_ext/etc", "/product/etc"};
        for (String dir : dirs) {
            File[] files = new File(dir).listFiles();
            if (files == null) {
                Slog.w(TAG, "Public libraries file folder missing: " + dir);
                continue;
            }
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith("public.libraries-") && name.endsWith(".txt")) {
                    readPublicLibrariesListFile(f);
                }
            }
        }
    }

    private void readPublicLibrariesListFile(File listFile) {
        try (BufferedReader br = new BufferedReader(new FileReader(listFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                // Line format is <soname> [abi]. We take the soname part.
                String soname = line.trim().split(" ")[0];
                SharedLibraryEntry entry = new SharedLibraryEntry(
                        soname, soname, new String[0], true);
                mSharedLibraries.put(entry.name, entry);
            }
        } catch (FileNotFoundException e) {
            // Expected for /vendor/etc/public.libraries.txt on some devices
            Slog.d(TAG, listFile + " does not exist");
        } catch (IOException e) {
            Slog.w(TAG, "Failed to read public libraries file " + listFile, e);
        }
    }


    /**
     * Returns the module name for a file in the apex module's partition.
     */
    private String getApexModuleNameFromFilePath(Path path, Path apexDirectoryPath) {
        if (!path.startsWith(apexDirectoryPath)) {
            throw new IllegalArgumentException("File " + path + " is not part of an APEX.");
        }
        // File must be in <apex_directory>/<module_name>/[extra_paths/]<xml_file>
        if (path.getNameCount() <= (apexDirectoryPath.getNameCount() + 1)) {
            throw new IllegalArgumentException("File " + path + " is in the APEX partition,"
                                                + " but not inside a module.");
        }
        return path.getName(apexDirectoryPath.getNameCount()).toString();
    }

    /**
     * Reads the contents of the privileged permission allowlist stored inside an APEX.
     */
    @VisibleForTesting
    public void readApexPrivAppPermissions(XmlPullParser parser, File permFile,
            Path apexDirectoryPath) throws IOException, XmlPullParserException {
        final String moduleName =
                getApexModuleNameFromFilePath(permFile.toPath(), apexDirectoryPath);
        final ArrayMap<String, ArrayMap<String, ArrayMap<String, Boolean>>> allowlists =
                mPermissionAllowlist.getApexPrivilegedAppAllowlists();
        ArrayMap<String, ArrayMap<String, Boolean>> allowlist = allowlists.get(moduleName);
        if (allowlist == null) {
            allowlist = new ArrayMap<>();
            allowlists.put(moduleName, allowlist);
        }
        readPrivAppPermissions(parser, allowlist);
    }

    private static boolean isSystemProcess() {
        return Process.myUid() == Process.SYSTEM_UID;
    }

    private static boolean isErofsSupported() {
        try {
            final Path path = Paths.get("/sys/fs/erofs");
            return Files.exists(path);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isKernelVersionAtLeast(int major, int minor) {
        final String kernelVersion = VintfRuntimeInfo.getKernelVersion();
        final String[] parts = kernelVersion.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            final int majorVersion = Integer.parseInt(parts[0]);
            final int minorVersion = Integer.parseInt(parts[1]);
            return majorVersion > major || (majorVersion == major && minorVersion >= minor);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
