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

package android.content.pm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.ComponentInfoFlags;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.pm.PackageManager.ResolveInfoFlags;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.SparseArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Package manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class PackageManagerInternal {
    public static final int PACKAGE_SYSTEM = 0;
    public static final int PACKAGE_SETUP_WIZARD = 1;
    public static final int PACKAGE_INSTALLER = 2;
    public static final int PACKAGE_VERIFIER = 3;
    public static final int PACKAGE_BROWSER = 4;
    public static final int PACKAGE_SYSTEM_TEXT_CLASSIFIER = 5;
    @IntDef(value = {
        PACKAGE_SYSTEM,
        PACKAGE_SETUP_WIZARD,
        PACKAGE_INSTALLER,
        PACKAGE_VERIFIER,
        PACKAGE_BROWSER,
        PACKAGE_SYSTEM_TEXT_CLASSIFIER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface KnownPackage {}

    /** Observer called whenever the list of packages changes */
    public interface PackageListObserver {
        /** A package was added to the system. */
        void onPackageAdded(@NonNull String packageName);
        /** A package was removed from the system. */
        void onPackageRemoved(@NonNull String packageName);
    }

    /**
     * Provider for package names.
     */
    public interface PackagesProvider {

        /**
         * Gets the packages for a given user.
         * @param userId The user id.
         * @return The package names.
         */
        public String[] getPackages(int userId);
    }

    /**
     * Provider for package names.
     */
    public interface SyncAdapterPackagesProvider {

        /**
         * Gets the sync adapter packages for given authority and user.
         * @param authority The authority.
         * @param userId The user id.
         * @return The package names.
         */
        public String[] getPackages(String authority, int userId);
    }

    /**
     * Sets the location provider packages provider.
     * @param provider The packages provider.
     */
    public abstract void setLocationPackagesProvider(PackagesProvider provider);

    /**
     * Sets the voice interaction packages provider.
     * @param provider The packages provider.
     */
    public abstract void setVoiceInteractionPackagesProvider(PackagesProvider provider);

    /**
     * Sets the SMS packages provider.
     * @param provider The packages provider.
     */
    public abstract void setSmsAppPackagesProvider(PackagesProvider provider);

    /**
     * Sets the dialer packages provider.
     * @param provider The packages provider.
     */
    public abstract void setDialerAppPackagesProvider(PackagesProvider provider);

    /**
     * Sets the sim call manager packages provider.
     * @param provider The packages provider.
     */
    public abstract void setSimCallManagerPackagesProvider(PackagesProvider provider);

    /**
     * Sets the Use Open Wifi packages provider.
     * @param provider The packages provider.
     */
    public abstract void setUseOpenWifiAppPackagesProvider(PackagesProvider provider);

    /**
     * Sets the sync adapter packages provider.
     * @param provider The provider.
     */
    public abstract void setSyncAdapterPackagesprovider(SyncAdapterPackagesProvider provider);

    /**
     * Requests granting of the default permissions to the current default SMS app.
     * @param packageName The default SMS package name.
     * @param userId The user for which to grant the permissions.
     */
    public abstract void grantDefaultPermissionsToDefaultSmsApp(String packageName, int userId);

    /**
     * Requests granting of the default permissions to the current default dialer app.
     * @param packageName The default dialer package name.
     * @param userId The user for which to grant the permissions.
     */
    public abstract void grantDefaultPermissionsToDefaultDialerApp(String packageName, int userId);

    /**
     * Requests granting of the default permissions to the current default sim call manager.
     * @param packageName The default sim call manager package name.
     * @param userId The user for which to grant the permissions.
     */
    public abstract void grantDefaultPermissionsToDefaultSimCallManager(String packageName,
            int userId);

    /**
     * Requests granting of the default permissions to the current default Use Open Wifi app.
     * @param packageName The default use open wifi package name.
     * @param userId The user for which to grant the permissions.
     */
    public abstract void grantDefaultPermissionsToDefaultUseOpenWifiApp(String packageName,
            int userId);

    /**
     * Sets a list of apps to keep in PM's internal data structures and as APKs even if no user has
     * currently installed it. The apps are not preloaded.
     * @param packageList List of package names to keep cached.
     */
    public abstract void setKeepUninstalledPackages(List<String> packageList);

    /**
     * Gets whether some of the permissions used by this package require a user
     * review before any of the app components can run.
     * @param packageName The package name for which to check.
     * @param userId The user under which to check.
     * @return True a permissions review is required.
     */
    public abstract boolean isPermissionsReviewRequired(String packageName, int userId);

    /**
     * Retrieve all of the information we know about a particular package/application.
     * @param filterCallingUid The results will be filtered in the context of this UID instead
     * of the calling UID.
     * @see PackageManager#getPackageInfo(String, int)
     */
    public abstract PackageInfo getPackageInfo(String packageName,
            @PackageInfoFlags int flags, int filterCallingUid, int userId);

    /**
     * Retrieve launcher extras for a suspended package provided to the system in
     * {@link PackageManager#setPackagesSuspended(String[], boolean, PersistableBundle,
     * PersistableBundle, String)}.
     *
     * @param packageName The package for which to return launcher extras.
     * @param userId The user for which to check.
     * @return The launcher extras.
     *
     * @see PackageManager#setPackagesSuspended(String[], boolean, PersistableBundle,
     * PersistableBundle, String)
     * @see PackageManager#isPackageSuspended()
     */
    public abstract Bundle getSuspendedPackageLauncherExtras(String packageName,
            int userId);

    /**
     * Internal api to query the suspended state of a package.
     * @param packageName The package to check.
     * @param userId The user id to check for.
     * @return {@code true} if the package is suspended, {@code false} otherwise.
     * @see PackageManager#isPackageSuspended(String)
     */
    public abstract boolean isPackageSuspended(String packageName, int userId);

    /**
     * Get the name of the package that suspended the given package. Packages can be suspended by
     * device administrators or apps holding {@link android.Manifest.permission#MANAGE_USERS} or
     * {@link android.Manifest.permission#SUSPEND_APPS}.
     *
     * @param suspendedPackage The package that has been suspended.
     * @param userId The user for which to check.
     * @return Name of the package that suspended the given package. Returns {@code null} if the
     * given package is not currently suspended and the platform package name - i.e.
     * {@code "android"} - if the package was suspended by a device admin.
     */
    public abstract String getSuspendingPackage(String suspendedPackage, int userId);

    /**
     * Get the dialog message to be shown to the user when they try to launch a suspended
     * application.
     *
     * @param suspendedPackage The package that has been suspended.
     * @param userId The user for which to check.
     * @return The dialog message to be shown to the user.
     */
    public abstract String getSuspendedDialogMessage(String suspendedPackage, int userId);

    /**
     * Do a straight uid lookup for the given package/application in the given user.
     * @see PackageManager#getPackageUidAsUser(String, int, int)
     * @return The app's uid, or < 0 if the package was not found in that user
     */
    public abstract int getPackageUid(String packageName,
            @PackageInfoFlags int flags, int userId);

    /**
     * Retrieve all of the information we know about a particular package/application.
     * @param filterCallingUid The results will be filtered in the context of this UID instead
     * of the calling UID.
     * @see PackageManager#getApplicationInfo(String, int)
     */
    public abstract ApplicationInfo getApplicationInfo(String packageName,
            @ApplicationInfoFlags int flags, int filterCallingUid, int userId);

    /**
     * Retrieve all of the information we know about a particular activity class.
     * @param filterCallingUid The results will be filtered in the context of this UID instead
     * of the calling UID.
     * @see PackageManager#getActivityInfo(ComponentName, int)
     */
    public abstract ActivityInfo getActivityInfo(ComponentName component,
            @ComponentInfoFlags int flags, int filterCallingUid, int userId);

    /**
     * Retrieve all activities that can be performed for the given intent.
     * @param filterCallingUid The results will be filtered in the context of this UID instead
     * of the calling UID.
     * @see PackageManager#queryIntentActivities(Intent, int)
     */
    public abstract List<ResolveInfo> queryIntentActivities(Intent intent,
            @ResolveInfoFlags int flags, int filterCallingUid, int userId);

    /**
     * Retrieve all services that can be performed for the given intent.
     * @see PackageManager#queryIntentServices(Intent, int)
     */
    public abstract List<ResolveInfo> queryIntentServices(
            Intent intent, int flags, int callingUid, int userId);

    /**
     * Interface to {@link com.android.server.pm.PackageManagerService#getHomeActivitiesAsUser}.
     */
    public abstract ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates,
            int userId);

    /**
     * @return The default home activity component name.
     */
    public abstract ComponentName getDefaultHomeActivity(int userId);

    /**
     * Called by DeviceOwnerManagerService to set the package names of device owner and profile
     * owners.
     */
    public abstract void setDeviceAndProfileOwnerPackages(
            int deviceOwnerUserId, String deviceOwner, SparseArray<String> profileOwners);

    /**
     * Returns {@code true} if a given package can't be wiped. Otherwise, returns {@code false}.
     */
    public abstract boolean isPackageDataProtected(int userId, String packageName);

    /**
     * Returns {@code true} if a given package's state is protected, e.g. it cannot be force
     * stopped, suspended, disabled or hidden. Otherwise, returns {@code false}.
     */
    public abstract boolean isPackageStateProtected(String packageName, int userId);

    /**
     * Returns {@code true} if a given package is installed as ephemeral. Otherwise, returns
     * {@code false}.
     */
    public abstract boolean isPackageEphemeral(int userId, String packageName);

    /**
     * Gets whether the package was ever launched.
     * @param packageName The package name.
     * @param userId The user for which to check.
     * @return Whether was launched.
     * @throws IllegalArgumentException if the package is not found
     */
    public abstract boolean wasPackageEverLaunched(String packageName, int userId);

    /**
     * Grants a runtime permission
     * @param packageName The package name.
     * @param name The name of the permission.
     * @param userId The userId for which to grant the permission.
     * @param overridePolicy If true, grant this permission even if it is fixed by policy.
     */
    public abstract void grantRuntimePermission(String packageName, String name, int userId,
            boolean overridePolicy);

    /**
     * Revokes a runtime permission
     * @param packageName The package name.
     * @param name The name of the permission.
     * @param userId The userId for which to revoke the permission.
     * @param overridePolicy If true, revoke this permission even if it is fixed by policy.
     */
    public abstract void revokeRuntimePermission(String packageName, String name, int userId,
            boolean overridePolicy);

    /**
     * Retrieve the official name associated with a uid. This name is
     * guaranteed to never change, though it is possible for the underlying
     * uid to be changed. That is, if you are storing information about
     * uids in persistent storage, you should use the string returned
     * by this function instead of the raw uid.
     *
     * @param uid The uid for which you would like to retrieve a name.
     * @return Returns a unique name for the given uid, or null if the
     * uid is not currently assigned.
     */
    public abstract String getNameForUid(int uid);

    /**
     * Request to perform the second phase of ephemeral resolution.
     * @param responseObj The response of the first phase of ephemeral resolution
     * @param origIntent The original intent that triggered ephemeral resolution
     * @param resolvedType The resolved type of the intent
     * @param callingPackage The name of the package requesting the ephemeral application
     * @param verificationBundle Optional bundle to pass to the installer for additional
     * verification
     * @param userId The ID of the user that triggered ephemeral resolution
     */
    public abstract void requestInstantAppResolutionPhaseTwo(AuxiliaryResolveInfo responseObj,
            Intent origIntent, String resolvedType, String callingPackage,
            Bundle verificationBundle, int userId);

    /**
     * Grants access to the package metadata for an ephemeral application.
     * <p>
     * When an ephemeral application explicitly tries to interact with a full
     * install application [via an activity, service or provider that has been
     * exposed using the {@code visibleToInstantApp} attribute], the normal
     * application must be able to see metadata about the connecting ephemeral
     * app. If the ephemeral application uses an implicit intent [ie action VIEW,
     * category BROWSABLE], it remains hidden from the launched activity.
     * <p>
     * If the {@code sourceUid} is not for an ephemeral app or {@code targetUid}
     * is not for a fully installed app, this method will be a no-op.
     *
     * @param userId the user
     * @param intent the intent that triggered the grant
     * @param targetAppId The app ID of the fully installed application
     * @param ephemeralAppId The app ID of the ephemeral application
     */
    public abstract void grantEphemeralAccess(int userId, Intent intent,
            int targetAppId, int ephemeralAppId);

    public abstract boolean isInstantAppInstallerComponent(ComponentName component);
    /**
     * Prunes instant apps and state associated with uninstalled
     * instant apps according to the current platform policy.
     */
    public abstract void pruneInstantApps();

    /**
     * @return The SetupWizard package name.
     */
    public abstract String getSetupWizardPackageName();

    public interface ExternalSourcesPolicy {

        int USER_TRUSTED = 0;   // User has trusted the package to install apps
        int USER_BLOCKED = 1;   // User has blocked the package to install apps
        int USER_DEFAULT = 2;   // Default code to use when user response is unavailable

        /**
         * Checks the user preference for whether a package is trusted to request installs through
         * package installer
         *
         * @param packageName The package to check for
         * @param uid the uid in which the package is running
         * @return {@link USER_TRUSTED} if the user has trusted the package, {@link USER_BLOCKED}
         * if user has blocked requests from the package, {@link USER_DEFAULT} if the user response
         * is not yet available
         */
        int getPackageTrustedToInstallApps(String packageName, int uid);
    }

    public abstract void setExternalSourcesPolicy(ExternalSourcesPolicy policy);

    /**
     * Return true if the given package is a persistent app process.
     */
    public abstract boolean isPackagePersistent(String packageName);

    /**
     * Returns whether or not the given package represents a legacy system application released
     * prior to runtime permissions.
     */
    public abstract boolean isLegacySystemApp(PackageParser.Package pkg);

    /**
     * Get all overlay packages for a user.
     * @param userId The user for which to get the overlays.
     * @return A list of overlay packages. An empty list is returned if the
     *         user has no installed overlay packages.
     */
    public abstract List<PackageInfo> getOverlayPackages(int userId);

    /**
     * Get the names of all target packages for a user.
     * @param userId The user for which to get the package names.
     * @return A list of target package names. This list includes the "android" package.
     */
    public abstract List<String> getTargetPackageNames(int userId);

    /**
     * Set which overlay to use for a package.
     * @param userId The user for which to update the overlays.
     * @param targetPackageName The package name of the package for which to update the overlays.
     * @param overlayPackageNames The complete list of overlay packages that should be enabled for
     *                            the target. Previously enabled overlays not specified in the list
     *                            will be disabled. Pass in null or an empty list to disable
     *                            all overlays. The order of the items is significant if several
     *                            overlays modify the same resource.
     * @return true if all packages names were known by the package manager, false otherwise
     */
    public abstract boolean setEnabledOverlayPackages(int userId, String targetPackageName,
            List<String> overlayPackageNames);

    /**
     * Resolves an activity intent, allowing instant apps to be resolved.
     */
    public abstract ResolveInfo resolveIntent(Intent intent, String resolvedType,
            int flags, int userId, boolean resolveForStart, int filterCallingUid);

    /**
    * Resolves a service intent, allowing instant apps to be resolved.
    */
    public abstract ResolveInfo resolveService(Intent intent, String resolvedType,
           int flags, int userId, int callingUid);

   /**
    * Resolves a content provider intent.
    */
    public abstract ProviderInfo resolveContentProvider(String name, int flags, int userId);

    /**
     * Track the creator of a new isolated uid.
     * @param isolatedUid The newly created isolated uid.
     * @param ownerUid The uid of the app that created the isolated process.
     */
    public abstract void addIsolatedUid(int isolatedUid, int ownerUid);

    /**
     * Track removal of an isolated uid.
     * @param isolatedUid isolated uid that is no longer being used.
     */
    public abstract void removeIsolatedUid(int isolatedUid);

    /**
     * Return the taget SDK version for the app with the given UID.
     */
    public abstract int getUidTargetSdkVersion(int uid);

    /**
     * Return the taget SDK version for the app with the given package name.
     */
    public abstract int getPackageTargetSdkVersion(String packageName);

    /** Whether the binder caller can access instant apps. */
    public abstract boolean canAccessInstantApps(int callingUid, int userId);

    /** Whether the binder caller can access the given component. */
    public abstract boolean canAccessComponent(int callingUid, ComponentName component, int userId);

    /**
     * Returns {@code true} if a given package has instant application meta-data.
     * Otherwise, returns {@code false}. Meta-data is state (eg. cookie, app icon, etc)
     * associated with an instant app. It may be kept after the instant app has been uninstalled.
     */
    public abstract boolean hasInstantApplicationMetadata(String packageName, int userId);

    /**
     * Updates a package last used time.
     */
    public abstract void notifyPackageUse(String packageName, int reason);

    /**
     * Returns a package object for the given package name.
     */
    public abstract @Nullable PackageParser.Package getPackage(@NonNull String packageName);

    /**
     * Returns a list without a change observer.
     *
     * {@see #getPackageList(PackageListObserver)}
     */
    public @NonNull PackageList getPackageList() {
        return getPackageList(null);
    }

    /**
     * Returns the list of packages installed at the time of the method call.
     * <p>The given observer is notified when the list of installed packages
     * changes [eg. a package was installed or uninstalled]. It will not be
     * notified if a package is updated.
     * <p>The package list will not be updated automatically as packages are
     * installed / uninstalled. Any changes must be handled within the observer.
     */
    public abstract @NonNull PackageList getPackageList(@Nullable PackageListObserver observer);

    /**
     * Removes the observer.
     * <p>Generally not needed. {@link #getPackageList(PackageListObserver)} will automatically
     * remove the observer.
     * <p>Does nothing if the observer isn't currently registered.
     * <p>Observers are notified asynchronously and it's possible for an observer to be
     * invoked after its been removed.
     */
    public abstract void removePackageListObserver(@NonNull PackageListObserver observer);

    /**
     * Returns a package object for the disabled system package name.
     */
    public abstract @Nullable PackageParser.Package getDisabledPackage(@NonNull String packageName);

    /**
     * Returns whether or not the component is the resolver activity.
     */
    public abstract boolean isResolveActivityComponent(@NonNull ComponentInfo component);

    /**
     * Returns the package name for a known package.
     */
    public abstract @Nullable String getKnownPackageName(
            @KnownPackage int knownPackage, int userId);

    /**
     * Returns whether the package is an instant app.
     */
    public abstract boolean isInstantApp(String packageName, int userId);

    /**
     * Returns whether the package is an instant app.
     */
    public abstract @Nullable String getInstantAppPackageName(int uid);

    /**
     * Returns whether or not access to the application should be filtered.
     * <p>
     * Access may be limited based upon whether the calling or target applications
     * are instant applications.
     *
     * @see #canAccessInstantApps(int)
     */
    public abstract boolean filterAppAccess(
            @Nullable PackageParser.Package pkg, int callingUid, int userId);

    /*
     * NOTE: The following methods are temporary until permissions are extracted from
     * the package manager into a component specifically for handling permissions.
     */
    /** Returns the flags for the given permission. */
    public abstract @Nullable int getPermissionFlagsTEMP(@NonNull String permName,
            @NonNull String packageName, int userId);
    /** Updates the flags for the given permission. */
    public abstract void updatePermissionFlagsTEMP(@NonNull String permName,
            @NonNull String packageName, int flagMask, int flagValues, int userId);

    /**
     * Returns true if it's still safe to restore data backed up from this app's version
     * that was signed with restoringFromSigHash.
     */
    public abstract boolean isDataRestoreSafe(@NonNull byte[] restoringFromSigHash,
            @NonNull String packageName);

    /**
     * Returns true if it's still safe to restore data backed up from this app's version
     * that was signed with restoringFromSig.
     */
    public abstract boolean isDataRestoreSafe(@NonNull Signature restoringFromSig,
            @NonNull String packageName);
}
