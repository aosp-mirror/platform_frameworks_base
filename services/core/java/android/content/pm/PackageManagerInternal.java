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

import android.annotation.AppIdInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.ComponentInfoFlags;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.pm.PackageManager.ResolveInfoFlags;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.server.pm.PackageList;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

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
    public static final int PACKAGE_PERMISSION_CONTROLLER = 6;
    public static final int PACKAGE_WELLBEING = 7;
    public static final int PACKAGE_DOCUMENTER = 8;
    public static final int PACKAGE_CONFIGURATOR = 9;
    public static final int PACKAGE_INCIDENT_REPORT_APPROVER = 10;
    public static final int PACKAGE_APP_PREDICTOR = 11;
    public static final int PACKAGE_TELEPHONY = 12;
    public static final int PACKAGE_WIFI = 13;
    public static final int PACKAGE_COMPANION = 14;
    public static final int PACKAGE_RETAIL_DEMO = 15;

    @IntDef(flag = true, prefix = "RESOLVE_", value = {
            RESOLVE_NON_BROWSER_ONLY,
            RESOLVE_NON_RESOLVER_ONLY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PrivateResolveFlags {}

    /**
     * Internal {@link #resolveIntent(Intent, String, int, int, int, boolean, int)} flag:
     * only match components that contain a generic web intent filter.
     */
    public static final int RESOLVE_NON_BROWSER_ONLY = 0x00000001;

    /**
     * Internal {@link #resolveIntent(Intent, String, int, int, int, boolean, int)} flag: do not
     * match to the resolver.
     */
    public static final int RESOLVE_NON_RESOLVER_ONLY = 0x00000002;

    @IntDef(value = {
            INTEGRITY_VERIFICATION_ALLOW,
            INTEGRITY_VERIFICATION_REJECT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface IntegrityVerificationResult {}

    /**
     * Used as the {@code verificationCode} argument for
     * {@link PackageManagerInternal#setIntegrityVerificationResult(int, int)} to indicate that the
     * integrity component allows the install to proceed.
     */
    public static final int INTEGRITY_VERIFICATION_ALLOW = 1;

    /**
     * Used as the {@code verificationCode} argument for
     * {@link PackageManagerInternal#setIntegrityVerificationResult(int, int)} to indicate that the
     * integrity component does not allow install to proceed.
     */
    public static final int INTEGRITY_VERIFICATION_REJECT = 0;

    @IntDef(value = {
        PACKAGE_SYSTEM,
        PACKAGE_SETUP_WIZARD,
        PACKAGE_INSTALLER,
        PACKAGE_VERIFIER,
        PACKAGE_BROWSER,
        PACKAGE_SYSTEM_TEXT_CLASSIFIER,
        PACKAGE_PERMISSION_CONTROLLER,
        PACKAGE_WELLBEING,
        PACKAGE_DOCUMENTER,
        PACKAGE_CONFIGURATOR,
        PACKAGE_INCIDENT_REPORT_APPROVER,
        PACKAGE_APP_PREDICTOR,
        PACKAGE_TELEPHONY,
        PACKAGE_WIFI,
        PACKAGE_COMPANION,
        PACKAGE_RETAIL_DEMO,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface KnownPackage {}

    /** Observer called whenever the list of packages changes */
    public interface PackageListObserver {
        /** A package was added to the system. */
        void onPackageAdded(@NonNull String packageName, int uid);
        /** A package was changed - either installed for a specific user or updated. */
        default void onPackageChanged(@NonNull String packageName, int uid) {}
        /** A package was removed from the system. */
        void onPackageRemoved(@NonNull String packageName, int uid);
    }

    /**
     * Called when the package for the default SMS handler changed
     *
     * @param packageName the new sms package
     * @param userId user for which the change was made
     */
    public void onDefaultSmsAppChanged(String packageName, int userId) {}

    /**
     * Called when the package for the default sim call manager changed
     *
     * @param packageName the new sms package
     * @param userId user for which the change was made
     */
    public void onDefaultSimCallManagerAppChanged(String packageName, int userId) {}

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
     * Retrieve CE data directory inode number of an application.
     * Return 0 if there's error.
     */
    public abstract long getCeDataInode(String packageName, int userId);

    /**
     * Return a List of all application packages that are installed on the
     * device, for a specific user. If flag GET_UNINSTALLED_PACKAGES has been
     * set, a list of all applications including those deleted with
     * {@code DELETE_KEEP_DATA} (partially installed apps with data directory)
     * will be returned.
     *
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user for whom the installed applications are to be
     *            listed
     * @param callingUid The uid of the original caller app
     * @return A List of ApplicationInfo objects, one for each installed
     *         application. In the unlikely case there are no installed
     *         packages, an empty list is returned. If flag
     *         {@code MATCH_UNINSTALLED_PACKAGES} is set, the application
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DELETE_KEEP_DATA} flag set).
     */
    public abstract List<ApplicationInfo> getInstalledApplications(
            @ApplicationInfoFlags int flags, @UserIdInt int userId, int callingUid);

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
     * Removes all package suspensions imposed by any non-system packages.
     */
    public abstract void removeAllNonSystemPackageSuspensions(int userId);

    /**
     * Removes all suspensions imposed on the given package by non-system packages.
     */
    public abstract void removeNonSystemPackageSuspensions(String packageName, int userId);

    /**
     * Removes all {@link PackageManager.DistractionRestriction restrictions} set on the given
     * package
     */
    public abstract void removeDistractingPackageRestrictions(String packageName, int userId);

    /**
     * Removes all {@link PackageManager.DistractionRestriction restrictions} set on all the
     * packages.
     */
    public abstract void removeAllDistractingPackageRestrictions(int userId);

    /**
     * Flushes package restrictions for the given user immediately to disk.
     */
    @WorkerThread
    public abstract void flushPackageRestrictions(int userId);

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
     * Get the information describing the dialog to be shown to the user when they try to launch a
     * suspended application.
     *
     * @param suspendedPackage The package that has been suspended.
     * @param suspendingPackage
     * @param userId The user for which to check.
     * @return A {@link SuspendDialogInfo} object describing the dialog to be shown.
     */
    @Nullable
    public abstract SuspendDialogInfo getSuspendedDialogInfo(String suspendedPackage,
            String suspendingPackage, int userId);

    /**
     * Gets any distraction flags set via
     * {@link PackageManager#setDistractingPackageRestrictions(String[], int)}
     *
     * @param packageName
     * @param userId
     * @return A bitwise OR of any of the {@link PackageManager.DistractionRestriction}
     */
    public abstract @PackageManager.DistractionRestriction int getDistractingPackageRestrictions(
            String packageName, int userId);

    /**
     * Do a straight uid lookup for the given package/application in the given user. This enforces
     * app visibility rules and permissions. Call {@link #getPackageUidInternal} for the internal
     * implementation.
     * @deprecated Use {@link PackageManager#getPackageUid(String, int)}
     * @return The app's uid, or < 0 if the package was not found in that user
     */
    @Deprecated
    public abstract int getPackageUid(String packageName,
            @PackageInfoFlags int flags, int userId);

    /**
     * Do a straight uid lookup for the given package/application in the given user.
     * @see PackageManager#getPackageUidAsUser(String, int, int)
     * @return The app's uid, or < 0 if the package was not found in that user
     * TODO(b/148235092): rename this to getPackageUid
     */
    public abstract int getPackageUidInternal(String packageName,
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
     * @param resolvedType the resolved type of the intent, which should be resolved via
     * {@link Intent#resolveTypeIfNeeded(ContentResolver)} before passing to {@link PackageManager}
     * @param filterCallingUid The results will be filtered in the context of this UID instead
     * of the calling UID.
     * @see PackageManager#queryIntentActivities(Intent, int)
     */
    public abstract List<ResolveInfo> queryIntentActivities(
            Intent intent, @Nullable String resolvedType, @ResolveInfoFlags int flags,
            int filterCallingUid, int userId);


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
     * @return The SystemUI service component name.
     */
    public abstract ComponentName getSystemUiServiceComponent();

    /**
     * Called by DeviceOwnerManagerService to set the package names of device owner and profile
     * owners.
     */
    public abstract void setDeviceAndProfileOwnerPackages(
            int deviceOwnerUserId, String deviceOwner, SparseArray<String> profileOwners);

    /**
     * Called by DevicePolicyManagerService to set the package names protected by the device
     * owner.
     */
    public abstract void setDeviceOwnerProtectedPackages(List<String> packageNames);

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
     * Marks a package as installed (or not installed) for a given user.
     *
     * @param pkg the package whose installation is to be set
     * @param userId the user for whom to set it
     * @param installed the new installed state
     * @return true if the installed state changed as a result
     */
    public abstract boolean setInstalled(AndroidPackage pkg,
            @UserIdInt int userId, boolean installed);

    /**
     * Request to perform the second phase of ephemeral resolution.
     * @param responseObj The response of the first phase of ephemeral resolution
     * @param origIntent The original intent that triggered ephemeral resolution
     * @param resolvedType The resolved type of the intent
     * @param callingPkg The app requesting the ephemeral application
     * @param callingFeatureId The feature in the package
     * @param isRequesterInstantApp Whether or not the app requesting the ephemeral application
     *                              is an instant app
     * @param verificationBundle Optional bundle to pass to the installer for additional
     * verification
     * @param userId The ID of the user that triggered ephemeral resolution
     */
    public abstract void requestInstantAppResolutionPhaseTwo(AuxiliaryResolveInfo responseObj,
            Intent origIntent, String resolvedType, String callingPkg,
            @Nullable String callingFeatureId, boolean isRequesterInstantApp,
            Bundle verificationBundle, int userId);

    /**
     * Grants implicit access based on an interaction between two apps. This grants access to the
     * from one application to the other's package metadata.
     * <p>
     * When an application explicitly tries to interact with another application [via an
     * activity, service or provider that is either declared in the caller's
     * manifest via the {@code <queries>} tag or has been exposed via the target apps manifest using
     * the {@code visibleToInstantApp} attribute], the target application must be able to see
     * metadata about the calling app. If the calling application uses an implicit intent [ie
     * action VIEW, category BROWSABLE], it remains hidden from the launched app.
     * <p>
     * If an interaction is not explicit, the {@code direct} argument should be set to false as
     * visibility should not be granted in some cases. This method handles that logic.
     * <p>
     * @param userId the user
     * @param intent the intent that triggered the grant
     * @param recipientAppId The app ID of the application that is being given access to {@code
     *                       visibleUid}
     * @param visibleUid The uid of the application that is becoming accessible to {@code
     *                   recipientAppId}
     * @param direct true if the access is being made due to direct interaction between visibleUid
     *               and recipientAppId.
     */
    public abstract void grantImplicitAccess(
            @UserIdInt int userId, Intent intent,
            @AppIdInt int recipientAppId, int visibleUid,
            boolean direct);

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
         * @return {@link #USER_TRUSTED} if the user has trusted the package, {@link #USER_BLOCKED}
         * if user has blocked requests from the package, {@link #USER_DEFAULT} if the user response
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
    public abstract boolean isLegacySystemApp(AndroidPackage pkg);

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
     * @param outUpdatedPackageNames An output list that contains the package names of packages
     *                               affected by the update of enabled overlays.
     * @return true if all packages names were known by the package manager, false otherwise
     */
    public abstract boolean setEnabledOverlayPackages(int userId, String targetPackageName,
            List<String> overlayPackageNames, Collection<String> outUpdatedPackageNames);

    /**
     * Resolves an activity intent, allowing instant apps to be resolved.
     */
    public abstract ResolveInfo resolveIntent(Intent intent, String resolvedType,
            int flags, @PrivateResolveFlags int privateResolveFlags, int userId,
            boolean resolveForStart, int filterCallingUid);

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
    public abstract @Nullable AndroidPackage getPackage(@NonNull String packageName);

    public abstract @Nullable PackageSetting getPackageSetting(String packageName);

    /**
     * Returns a package for the given UID. If the UID is part of a shared user ID, one
     * of the packages will be chosen to be returned.
     */
    public abstract @Nullable AndroidPackage getPackage(int uid);

    /**
     * Returns a list without a change observer.
     *
     * @see #getPackageList(PackageListObserver)
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
    public abstract @Nullable PackageSetting getDisabledSystemPackage(@NonNull String packageName);

    /**
     * Returns the package name for the disabled system package.
     *
     * This is equivalent to
     * {@link #getDisabledSystemPackage(String)}
     *     .{@link PackageSetting#pkg}
     *     .{@link AndroidPackage#getPackageName()}
     */
    public abstract @Nullable String getDisabledSystemPackageName(@NonNull String packageName);

    /**
     * Returns whether or not the component is the resolver activity.
     */
    public abstract boolean isResolveActivityComponent(@NonNull ComponentInfo component);


    /**
     * Returns a list of package names for a known package
     */
    public abstract @NonNull String[] getKnownPackageNames(
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
     * @see #canAccessInstantApps
     */
    public abstract boolean filterAppAccess(
            @NonNull AndroidPackage pkg, int callingUid, int userId);

    /**
     * Returns whether or not access to the application should be filtered.
     *
     * @see #filterAppAccess(AndroidPackage, int, int)
     */
    public abstract boolean filterAppAccess(
            @NonNull String packageName, int callingUid, int userId);

    /** Returns whether the given package was signed by the platform */
    public abstract boolean isPlatformSigned(String pkg);

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

    /**
     * Returns {@code true} if the the signing information for {@code clientUid} is sufficient
     * to gain access gated by {@code capability}.  This can happen if the two UIDs have the
     * same signing information, if the signing information {@code clientUid} indicates that
     * it has the signing certificate for {@code serverUid} in its signing history (if it was
     * previously signed by it), or if the signing certificate for {@code clientUid} is in the
     * signing history for {@code serverUid} and with the {@code capability} specified.
     */
    public abstract boolean hasSignatureCapability(int serverUid, int clientUid,
            @PackageParser.SigningDetails.CertCapabilities int capability);

    /**
     * Get appIds of all available apps which specified android:sharedUserId in the manifest.
     *
     * @return a SparseArray mapping from appId to it's sharedUserId.
     */
    public abstract SparseArray<String> getAppsWithSharedUserIds();

    /**
     * Get all packages which share the same userId as the specified package, or an empty array
     * if the package does not have a shared userId.
     */
    @NonNull
    public abstract String[] getSharedUserPackagesForPackage(@NonNull String packageName,
            int userId);

    /**
     * Return the processes that have been declared for a uid.
     *
     * @param uid The uid to query.
     *
     * @return Returns null if there are no declared processes for the uid; otherwise,
     * returns the set of processes it declared.
     */
    public abstract ArrayMap<String, ProcessInfo> getProcessesForUid(int uid);

    /**
     * Return the gids associated with a particular permission.
     *
     * @param permissionName The name of the permission to query.
     * @param userId The user id the gids will be associated with.
     *
     * @return Returns null if there are no gids associated with the permission, otherwise an
     * array if the gid ints.
     */
    public abstract int[] getPermissionGids(String permissionName, int userId);

    /**
     * Return if device is currently in a "core" boot environment, typically
     * used to support full-disk encryption. Only apps marked with
     * {@code coreApp} attribute are available.
     */
    public abstract boolean isOnlyCoreApps();

    /**
     * Make a best-effort attempt to provide the requested free disk space by
     * deleting cached files.
     *
     * @throws IOException if the request was unable to be fulfilled.
     */
    public abstract void freeStorage(String volumeUuid, long bytes, int storageFlags)
            throws IOException;

    /** Returns {@code true} if the specified component is enabled and matches the given flags. */
    public abstract boolean isEnabledAndMatches(@NonNull ParsedMainComponent component, int flags,
            int userId);

    /** Returns {@code true} if the given user requires extra badging for icons. */
    public abstract boolean userNeedsBadging(int userId);

    /**
     * Perform the given action for each package.
     * Note that packages lock will be held while performing the actions.
     *
     * @param actionLocked action to be performed
     */
    public abstract void forEachPackage(Consumer<AndroidPackage> actionLocked);

    /**
     * Perform the given action for each {@link PackageSetting}.
     * Note that packages lock will be held while performing the actions.
     *
     * @param actionLocked action to be performed
     */
    public abstract void forEachPackageSetting(Consumer<PackageSetting> actionLocked);

    /**
     * Perform the given action for each installed package for a user.
     * Note that packages lock will be held while performin the actions.
     */
    public abstract void forEachInstalledPackage(
            @NonNull Consumer<AndroidPackage> actionLocked, @UserIdInt int userId);

    /** Returns the list of enabled components */
    public abstract ArraySet<String> getEnabledComponents(String packageName, int userId);

    /** Returns the list of disabled components */
    public abstract ArraySet<String> getDisabledComponents(String packageName, int userId);

    /** Returns whether the given package is enabled for the given user */
    public abstract @PackageManager.EnabledState int getApplicationEnabledState(
            String packageName, int userId);

    /**
     * Extra field name for the token of a request to enable rollback for a
     * package.
     */
    public static final String EXTRA_ENABLE_ROLLBACK_TOKEN =
            "android.content.pm.extra.ENABLE_ROLLBACK_TOKEN";

    /**
     * Extra field name for the session id of a request to enable rollback
     * for a package.
     */
    public static final String EXTRA_ENABLE_ROLLBACK_SESSION_ID =
            "android.content.pm.extra.ENABLE_ROLLBACK_SESSION_ID";

    /**
     * Used as the {@code enableRollbackCode} argument for
     * {@link PackageManagerInternal#setEnableRollbackCode} to indicate that
     * enabling rollback succeeded.
     */
    public static final int ENABLE_ROLLBACK_SUCCEEDED = 1;

    /**
     * Used as the {@code enableRollbackCode} argument for
     * {@link PackageManagerInternal#setEnableRollbackCode} to indicate that
     * enabling rollback failed.
     */
    public static final int ENABLE_ROLLBACK_FAILED = -1;

    /**
     * Allows the rollback manager listening to the
     * {@link Intent#ACTION_PACKAGE_ENABLE_ROLLBACK enable rollback broadcast}
     * to respond to the package manager. The response must include the
     * {@code enableRollbackCode} which is one of
     * {@link PackageManager#ENABLE_ROLLBACK_SUCCEEDED} or
     * {@link PackageManager#ENABLE_ROLLBACK_FAILED}.
     *
     * @param token pending package identifier as passed via the
     *            {@link PackageManager#EXTRA_ENABLE_ROLLBACK_TOKEN} Intent extra.
     * @param enableRollbackCode the status code result of enabling rollback
     * @throws SecurityException if the caller does not have the
     *            PACKAGE_ROLLBACK_AGENT permission.
     */
    public abstract void setEnableRollbackCode(int token, int enableRollbackCode);

    /**
     * Ask the package manager to compile layouts in the given package.
     */
    public abstract boolean compileLayouts(String packageName);

    /*
     * Inform the package manager that the pending package install identified by
     * {@code token} can be completed.
     */
    public abstract void finishPackageInstall(int token, boolean didLaunch);

    /**
     * Remove the default browser stored in the legacy package settings.
     *
     * @param userId the user id
     *
     * @return the package name of the default browser, or {@code null} if none
     */
    @Nullable
    public abstract String removeLegacyDefaultBrowserPackageName(int userId);

    /**
     * Returns {@code true} if given {@code packageName} is an apex package.
     */
    public abstract boolean isApexPackage(String packageName);

    /**
     * Returns list of {@code packageName} of apks inside the given apex.
     * @param apexPackageName Package name of the apk container of apex
     */
    public abstract List<String> getApksInApex(String apexPackageName);

    /**
     * Uninstalls given {@code packageName}.
     *
     * @param packageName apex package to uninstall.
     * @param versionCode version of a package to uninstall.
     * @param userId user to uninstall apex package for. Must be
     *               {@link android.os.UserHandle#USER_ALL}, otherwise failure will be reported.
     * @param intentSender a {@link IntentSender} to send result of an uninstall to.
     * @param flags flags about the uninstall.
     */
    public abstract void uninstallApex(String packageName, long versionCode, int userId,
            IntentSender intentSender, int flags);

    /**
     * Update fingerprint of build that updated the runtime permissions for a user.
     *
     * @param userId The user to update
     */
    public abstract void updateRuntimePermissionsFingerprint(@UserIdInt int userId);

    /**
     * Migrates legacy obb data to its new location.
     */
    public abstract void migrateLegacyObbData();

    /**
     * Writes all package manager settings to disk. If {@code async} is {@code true}, the
     * settings are written at some point in the future. Otherwise, the call blocks until
     * the settings have been written.
     */
    public abstract void writeSettings(boolean async);

    /**
     * Writes all permission settings for the given set of users to disk. If {@code async}
     * is {@code true}, the settings are written at some point in the future. Otherwise,
     * the call blocks until the settings have been written.
     */
    public abstract void writePermissionSettings(@NonNull @UserIdInt int[] userIds, boolean async);

    /**
     * Returns {@code true} if the caller is the installer of record for the given package.
     * Otherwise, {@code false}.
     */
    public abstract boolean isCallerInstallerOfRecord(
            @NonNull AndroidPackage pkg, int callingUid);

    /** Returns whether or not permissions need to be upgraded for the given user */
    public abstract boolean isPermissionUpgradeNeeded(@UserIdInt int userId);

    /** Sets the enforcement of reading external storage */
    public abstract void setReadExternalStorageEnforced(boolean enforced);

    /**
     * Allows the integrity component to respond to the
     * {@link Intent#ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION package verification
     * broadcast} to respond to the package manager. The response must include
     * the {@code verificationCode} which is one of
     * {@link #INTEGRITY_VERIFICATION_ALLOW} and {@link #INTEGRITY_VERIFICATION_REJECT}.
     *
     * @param verificationId pending package identifier as passed via the
     *            {@link PackageManager#EXTRA_VERIFICATION_ID} Intent extra.
     * @param verificationResult either {@link #INTEGRITY_VERIFICATION_ALLOW}
     *            or {@link #INTEGRITY_VERIFICATION_REJECT}.
     */
    public abstract void setIntegrityVerificationResult(int verificationId,
            @IntegrityVerificationResult int verificationResult);

    /**
     * Returns MIME types contained in {@code mimeGroup} from {@code packageName} package
     */
    public abstract List<String> getMimeGroup(String packageName, String mimeGroup);

    /**
     * Toggles visibility logging to help in debugging the app enumeration feature.
     * @param packageName the package name that should begin logging
     * @param enabled true if visibility blocks should be logged
     */
    public abstract void setVisibilityLogging(String packageName, boolean enabled);
}
