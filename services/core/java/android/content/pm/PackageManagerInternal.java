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
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager.SignatureResult;
import android.content.pm.SigningDetails.CertCapabilities;
import android.content.pm.overlay.OverlayPaths;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.storage.StorageManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.pm.pkg.component.ParsedMainComponent;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.pm.KnownPackages;
import com.android.server.pm.PackageArchiver;
import com.android.server.pm.PackageList;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.dex.DynamicCodeLogger;
import com.android.server.pm.permission.LegacyPermissionSettings;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.pm.pkg.mutate.PackageStateMutator;
import com.android.server.pm.snapshot.PackageDataSnapshot;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Package manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class PackageManagerInternal {
    @LongDef(flag = true, prefix = "RESOLVE_", value = {
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

    @Deprecated
    @IntDef(value = {
            INTEGRITY_VERIFICATION_ALLOW,
            INTEGRITY_VERIFICATION_REJECT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface IntegrityVerificationResult {}

    @Deprecated
    public static final int INTEGRITY_VERIFICATION_ALLOW = 1;

    @Deprecated
    public static final int INTEGRITY_VERIFICATION_REJECT = 0;

    /**
     * Observer called whenever the list of packages changes.
     *
     * @deprecated please use {@link com.android.internal.content.PackageMonitor} instead.
     * PackageMonitor covers more installation and uninstallation corner cases than
     * PackageListObserver.
     */
    @Deprecated
    public interface PackageListObserver {
        /** A package was added to the system. */
        default void onPackageAdded(@NonNull String packageName, int uid) {}
        /** A package was changed - either installed for a specific user or updated. */
        default void onPackageChanged(@NonNull String packageName, int uid) {}
        /** A package was removed from the system. */
        default void onPackageRemoved(@NonNull String packageName, int uid) {}
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
     * Variant of {@link #isSameApp(String, long, int, int)} with no flags.
     * @see #isSameApp(String, long, int, int)
     */
    public abstract boolean isSameApp(String packageName, int callingUid, int userId);

    /**
     * Gets whether a given package name belongs to the calling uid. If the calling uid is an
     * {@link Process#isSdkSandboxUid(int) sdk sandbox uid}, checks whether the package name is
     * equal to {@link PackageManager#getSdkSandboxPackageName()}.
     *
     * @param packageName The package name to check.
     * @param flags The PackageInfoFlagsBits flags to use during uid lookup.
     * @param callingUid The calling uid.
     * @param userId The user under which to check.
     * @return True if the package name belongs to the calling uid.
     */
    public abstract boolean isSameApp(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int callingUid, int userId);

    /**
     * Retrieve all of the information we know about a particular package/application.
     * @param filterCallingUid The results will be filtered in the context of this UID instead
     * of the calling UID.
     * @see PackageManager#getPackageInfo(String, int)
     */
    public abstract PackageInfo getPackageInfo(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int filterCallingUid, int userId);

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
            @PackageManager.ApplicationInfoFlagsBits long flags, @UserIdInt int userId,
            int callingUid);

    /**
     * Like {@link #getInstalledApplications}, but allows the fetching of apps
     * cross user.
     */
    public abstract List<ApplicationInfo> getInstalledApplicationsCrossUser(
            @PackageManager.ApplicationInfoFlagsBits long flags, @UserIdInt int userId,
            int callingUid);

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
     * @return User id and package name of the package that suspended the given package. Returns
     * {@code null} if the given package is not currently suspended and the platform package name
     * - i.e. {@code "android"} - if the package was suspended by a device admin.
     */
    public abstract UserPackage getSuspendingPackage(String suspendedPackage, int userId);

    /**
     * Suspend or unsuspend packages upon admin request.
     *
     * @param userId The target user.
     * @param packageNames The names of the packages to set the suspended status.
     * @param suspended Whether the packages should be suspended or unsuspended.
     * @return an array of package names for which the suspended status could not be set as
     *   requested in this method.
     */
    public abstract String[] setPackagesSuspendedByAdmin(
            @UserIdInt int userId, @NonNull String[] packageNames, boolean suspended);

    /**
     * Get the information describing the dialog to be shown to the user when they try to launch a
     * suspended application.
     *
     * @param suspendedPackage The package that has been suspended.
     * @param suspendingPackage The package responsible for suspension.
     * @param userId The user for which to check.
     * @return A {@link SuspendDialogInfo} object describing the dialog to be shown.
     */
    @Nullable
    public abstract SuspendDialogInfo getSuspendedDialogInfo(String suspendedPackage,
            UserPackage suspendingPackage, int userId);

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
     * Do a straight uid lookup for the given package/application in the given user.
     * @see PackageManager#getPackageUidAsUser(String, int, int)
     * @return The app's uid, or < 0 if the package was not found in that user
     */
    public abstract int getPackageUid(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int userId);

    /**
     * Retrieve all of the information we know about a particular package/application.
     * @param filterCallingUid The results will be filtered in the context of this UID instead
     * of the calling UID.
     * @see PackageManager#getApplicationInfo(String, int)
     */
    public abstract ApplicationInfo getApplicationInfo(String packageName,
            @PackageManager.ApplicationInfoFlagsBits long flags, int filterCallingUid, int userId);

    /**
     * Retrieve all of the information we know about a particular activity class.
     * @param filterCallingUid The results will be filtered in the context of this UID instead
     * of the calling UID.
     * @see PackageManager#getActivityInfo(ComponentName, int)
     */
    public abstract ActivityInfo getActivityInfo(ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, int filterCallingUid, int userId);

    /**
     * Retrieve all activities that can be performed for the given intent.
     * @param resolvedType the resolved type of the intent, which should be resolved via
     * {@link Intent#resolveTypeIfNeeded(ContentResolver)} before passing to {@link PackageManager}
     * @param filterCallingUid The results will be filtered in the context of this UID instead
     * of the calling UID.
     * @see PackageManager#queryIntentActivities(Intent, int)
     */
    public abstract List<ResolveInfo> queryIntentActivities(
            Intent intent, @Nullable String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags, int filterCallingUid, int userId);

    /**
     * Retrieve all receivers that can handle a broadcast of the given intent.
     *
     * @param filterCallingUid The results will be filtered in the context of this UID instead
     *                         of the calling UID.
     * @param forSend          true if the invocation is intended for sending broadcasts. The value
     *                         of this parameter affects how packages are filtered.
     */
    public abstract List<ResolveInfo> queryIntentReceivers(
            Intent intent, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            int filterCallingUid, int callingPid, int userId, boolean forSend);

    /**
     * Retrieve all services that can be performed for the given intent.
     * @see PackageManager#queryIntentServices(Intent, int)
     */
    public abstract List<ResolveInfo> queryIntentServices(
            Intent intent, @PackageManager.ResolveInfoFlagsBits long flags, int callingUid,
            int userId);

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
     * Marks packages as protected for a given user or all users in case of USER_ALL. Setting
     * {@code packageNames} to {@code null} means unset all existing protected packages for the
     * given user.
     *
     * <p> Note that setting it if set for a specific user, it takes precedence over the packages
     * set globally using USER_ALL.
     */
    public abstract void setOwnerProtectedPackages(
            @UserIdInt int userId, @Nullable List<String> packageNames);

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
     * @param retainOnUpdate true if the implicit access is retained across package update.
     */
    public abstract void grantImplicitAccess(
            @UserIdInt int userId, Intent intent,
            @AppIdInt int recipientAppId, int visibleUid,
            boolean direct, boolean retainOnUpdate);

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
     * @param pendingChanges is a map to describe all overlay targets and their related overlay
     *                      paths. Its key is the overlay target package and its value is the
     *                      complete list of overlay paths that should be enabled for
     *                      the target. Previously enabled overlays not specified in the list
     *                      will be disabled. Pass in null or empty paths to disable all overlays.
     *                      The order of the items is significant if several overlays modify the
     *                      same resource. To pass the concrete ArrayMap type is to reduce the
     *                      overheads of system server.
     * @param outUpdatedPackageNames An output list that contains the package names of packages
     *                               affected by the update of enabled overlays.
     * @param outInvalidPackageNames An output list that contains the package names of packages
     *                               are not valid.
     */
    public abstract void setEnabledOverlayPackages(int userId,
            @NonNull ArrayMap<String, OverlayPaths> pendingChanges,
            @NonNull Set<String> outUpdatedPackageNames,
            @NonNull Set<String> outInvalidPackageNames);

    /**
     * Resolves an exported activity intent, allowing instant apps to be resolved.
     */
    public abstract ResolveInfo resolveIntent(Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags,
            @PrivateResolveFlags long privateResolveFlags, int userId, boolean resolveForStart,
            int filterCallingUid, int callingPid);

    /**
    * Resolves a service intent, allowing instant apps to be resolved.
    */
    public abstract ResolveInfo resolveService(Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId, int callingUid);


    /**
     * Resolves a service intent for start.
     */
    public abstract ResolveInfo resolveService(
            Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId,
            int callingUid, int callingPid);

    /**
    * Resolves a content provider intent.
    */
    public abstract ProviderInfo resolveContentProvider(String name,
            @PackageManager.ComponentInfoFlagsBits long flags, int userId, int callingUid);

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
     * Notify the package is force stopped.
     */
    public abstract void onPackageProcessKilledForUninstall(String packageName);

    /**
     * Returns a package object for the given package name.
     */
    public abstract @Nullable AndroidPackage getPackage(@NonNull String packageName);

    /**
     * Returns the {@link SystemApi} variant of a package for use with mainline.
     */
    @Nullable
    public abstract AndroidPackage getAndroidPackage(@NonNull String packageName);

    @Nullable
    public abstract PackageStateInternal getPackageStateInternal(@NonNull String packageName);

    @NonNull
    public abstract ArrayMap<String, ? extends PackageStateInternal> getPackageStates();

    /**
     * Returns a package for the given UID. If the UID is part of a shared user ID, one
     * of the packages will be chosen to be returned.
     */
    public abstract @Nullable AndroidPackage getPackage(int uid);


    /**
     * Returns all packages for the given app ID.
     */
    public abstract @NonNull List<AndroidPackage> getPackagesForAppId(int appId);

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
     *
     * @deprecated please use {@link com.android.internal.content.PackageMonitor} instead.
     * PackageMonitor covers more installation and uninstallation corner cases than
     * PackageListObserver.
     */
    @Deprecated
    public abstract @NonNull PackageList getPackageList(@Nullable PackageListObserver observer);

    /**
     * Removes the observer.
     * <p>Generally not needed. {@link #getPackageList(PackageListObserver)} will automatically
     * remove the observer.
     * <p>Does nothing if the observer isn't currently registered.
     * <p>Observers are notified asynchronously and it's possible for an observer to be
     * invoked after its been removed.
     *
     * @deprecated please use {@link com.android.internal.content.PackageMonitor} instead.
     * PackageMonitor covers more installation and uninstallation corner cases than
     * PackageListObserver.
     */
    @Deprecated
    public abstract void removePackageListObserver(@NonNull PackageListObserver observer);

    /**
     * Returns a package object for the disabled system package name.
     */
    public abstract @Nullable PackageStateInternal getDisabledSystemPackage(
            @NonNull String packageName);

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
            @KnownPackages.KnownPackage int knownPackage, int userId);

    /**
     * Returns whether the package is an instant app.
     */
    public abstract boolean isInstantApp(String packageName, int userId);

    /**
     * Returns whether the package is an instant app.
     */
    public abstract @Nullable String getInstantAppPackageName(int uid);

    /**
     * Returns whether or not access to the application should be filtered. The access is not
     * allowed if the application is not installed under the given user.
     * <p>
     * Access may be limited based upon whether the calling or target applications
     * are instant applications.
     *
     * @see #canAccessInstantApps
     *
     * @param pkg The package to be accessed.
     * @param callingUid The uid that attempts to access the package.
     * @param userId The user id where the package resides.
     */
    public abstract boolean filterAppAccess(
            @NonNull AndroidPackage pkg, int callingUid, int userId);

    /**
     * Returns whether or not access to the application should be filtered. The access is not
     * allowed if the application is not installed under the given user.
     *
     * @see #filterAppAccess(AndroidPackage, int, int)
     */
    public boolean filterAppAccess(@NonNull String packageName, int callingUid, int userId) {
        return filterAppAccess(packageName, callingUid, userId, true /* filterUninstalled */);
    }

    /**
     * Returns whether or not access to the application should be filtered.
     *
     * @param packageName The package to be accessed.
     * @param callingUid The uid that attempts to access the package.
     * @param userId The user id where the package resides.
     * @param filterUninstalled Set to true to filter the access if the package is not installed
     *                        under the given user.
     * @see #filterAppAccess(AndroidPackage, int, int)
     */
    public abstract boolean filterAppAccess(
            @NonNull String packageName, int callingUid, int userId, boolean filterUninstalled);

    /**
     * Returns whether or not access to the application which belongs to the given UID should be
     * filtered. If the UID is part of a shared user ID, return {@code true} if all applications
     * belong to the shared user ID should be filtered. The access is not allowed if the uid does
     * not exist in the device.
     *
     * @see #filterAppAccess(AndroidPackage, int, int)
     */
    public abstract boolean filterAppAccess(int uid, int callingUid);

    /**
     * Fetches all app Ids that a given application is currently visible to the provided user.
     *
     * <p>
     * <strong>Note: </strong>This only includes UIDs >= {@link Process#FIRST_APPLICATION_UID}
     * as all other UIDs can already see all applications.
     * </p>
     *
     * If the app is visible to all UIDs, null is returned. If the app is not visible to any
     * applications, the int array will be empty.
     */
    @Nullable
    public abstract int[] getVisibilityAllowList(@NonNull String packageName, int userId);

    /**
     * Returns whether the given UID either declares &lt;queries&gt; element with the given package
     * name in its app's manifest, has {@link android.Manifest.permission.QUERY_ALL_PACKAGES}, or
     * package visibility filtering is enabled on it. If the UID is part of a shared user ID,
     * return {@code true} if any one application belongs to the shared user ID meets the criteria.
     */
    public abstract boolean canQueryPackage(int callingUid, @Nullable String packageName);

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
     * Returns {@code true} if the signing information for {@code clientUid} is sufficient
     * to gain access gated by {@code capability}.  This can happen if the two UIDs have the
     * same signing information, if the signing information {@code clientUid} indicates that
     * it has the signing certificate for {@code serverUid} in its signing history (if it was
     * previously signed by it), or if the signing certificate for {@code clientUid} is in the
     * signing history for {@code serverUid} and with the {@code capability} specified.
     */
    public abstract boolean hasSignatureCapability(int serverUid, int clientUid,
            @CertCapabilities int capability);

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
     * Make a best-effort attempt to provide the requested free disk space by
     * deleting cached files.
     *
     * @throws IOException if the request was unable to be fulfilled.
     */
    public abstract void freeStorage(String volumeUuid, long bytes,
            @StorageManager.AllocateFlags int flags) throws IOException;

    /**
     * Blocking call to clear all cached app data above quota.
     */
    public abstract void freeAllAppCacheAboveQuota(@NonNull String volumeUuid) throws IOException;

    /** Returns {@code true} if the specified component is enabled and matches the given flags. */
    public abstract boolean isEnabledAndMatches(@NonNull ParsedMainComponent component,
            @PackageManager.ComponentInfoFlagsBits long flags, int userId);

    /** Returns {@code true} if the given user requires extra badging for icons. */
    public abstract boolean userNeedsBadging(int userId);

    /**
     * Perform the given action for each {@link PackageSetting}.
     * Note that packages lock will be held while performing the actions.
     *
     * If the caller does not need all packages, prefer the potentially non-locking
     * {@link #withPackageSettingsSnapshot(Consumer)}.
     *
     * @param actionLocked action to be performed
     */
    public abstract void forEachPackageSetting(Consumer<PackageSetting> actionLocked);

    /**
     * Perform the given action for each package.
     * @param action action to be performed
     */
    public abstract void forEachPackageState(Consumer<PackageStateInternal> action);

    /**
     * {@link #forEachPackageState(Consumer)} but filtered to only states with packages
     * on device where {@link PackageStateInternal#getPkg()} is not null.
     */
    public abstract void forEachPackage(Consumer<AndroidPackage> action);

    /**
     * Perform the given action for each installed package for a user.
     */
    public abstract void forEachInstalledPackage(
            @NonNull Consumer<AndroidPackage> action, @UserIdInt int userId);

    /** Returns the list of enabled components */
    public abstract ArraySet<String> getEnabledComponents(String packageName, int userId);

    /** Returns the list of disabled components */
    public abstract ArraySet<String> getDisabledComponents(String packageName, int userId);

    /** Returns whether the given package is enabled for the given user */
    public abstract @PackageManager.EnabledState int getApplicationEnabledState(
            String packageName, int userId);

    /**
     * Return the enabled setting for a package component (activity, receiver, service, provider).
     */
    public abstract @PackageManager.EnabledState int getComponentEnabledSetting(
            @NonNull ComponentName componentName, int callingUid, int userId);

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
            IntentSender intentSender, @PackageManager.InstallFlags int installFlags);

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
     * Read legacy permission definitions for permissions migration to new permission subsystem.
     * Note that this api is supposed to be used for permissions migration only.
     */
    public abstract LegacyPermissionSettings getLegacyPermissions();

    /**
     * Read legacy permission states for permissions migration to new permission subsystem.
     * Note that this api is supposed to be used for permissions state migration only.
     */
    // TODO: restore to com.android.permission.persistence.RuntimePermissionsState
    // once Ravenwood includes Mainline stubs
    public abstract Object getLegacyPermissionsState(@UserIdInt int userId);

    /**
     * @return permissions file version for the given user.
     */
    public abstract int getLegacyPermissionsVersion(@UserIdInt int userId);

    /**
     * Returns {@code true} if the caller is the installer of record for the given package.
     * Otherwise, {@code false}.
     */
    public abstract boolean isCallerInstallerOfRecord(
            @NonNull AndroidPackage pkg, int callingUid);

    /** Returns whether or not permissions need to be upgraded for the given user */
    public abstract boolean isPermissionUpgradeNeeded(@UserIdInt int userId);

    /**
     * Used to allow the integrity component to respond to the
     * ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION package verification
     * broadcast to respond to the package manager.
     *
     * Deprecated.
     */
    @Deprecated
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

    /**
     * Returns if a package name is a valid system package.
     */
    public abstract boolean isSystemPackage(@NonNull String packageName);

    /**
     * Unblocks uninstall for all packages for the user.
     */
    public abstract void clearBlockUninstallForUser(@UserIdInt int userId);

    /**
     * Unsuspends all packages suspended by an admin for the user.
     */
    public abstract void unsuspendAdminSuspendedPackages(int userId);

    /**
     * Returns {@code true} if an admin is suspending any packages for the user.
     */
    public abstract boolean isAdminSuspendingAnyPackages(int userId);

    /**
     * Register to listen for loading progress of an installed package.
     * The listener is automatically unregistered when the app is fully loaded.
     * @param packageName The name of the installed package
     * @param callback To loading reporting progress
     * @param userId The user under which to check.
     * @return Whether the registration was successful. It can fail if the package has not been
     *          installed yet.
     */
    public abstract boolean registerInstalledLoadingProgressCallback(@NonNull String packageName,
            @NonNull InstalledLoadingProgressCallback callback, int userId);

    /**
     * Callback to listen for loading progress of a package installed on Incremental File System.
     */
    public abstract static class InstalledLoadingProgressCallback {
        final LoadingProgressCallbackBinder mBinder = new LoadingProgressCallbackBinder();
        final Executor mExecutor;
        /**
         * Default constructor that should always be called on subclass instantiation
         * @param handler To dispatch callback events through. If null, the main thread
         *                handler will be used.
         */
        public InstalledLoadingProgressCallback(@Nullable Handler handler) {
            if (handler == null) {
                handler = new Handler(Looper.getMainLooper());
            }
            mExecutor = new HandlerExecutor(handler);
        }

        /**
         * Binder used by Package Manager Service to register as a callback
         * @return the binder object of IPackageLoadingProgressCallback
         */
        public final @NonNull IBinder getBinder() {
            return mBinder;
        }

        /**
         * Report loading progress of an installed package.
         *
         * @param progress    Loading progress between [0, 1] for the registered package.
         */
        public abstract void onLoadingProgressChanged(float progress);

        private class LoadingProgressCallbackBinder extends
                android.content.pm.IPackageLoadingProgressCallback.Stub {
            @Override
            public void onPackageLoadingProgressChanged(float progress) {
                mExecutor.execute(PooledLambda.obtainRunnable(
                        InstalledLoadingProgressCallback::onLoadingProgressChanged,
                        InstalledLoadingProgressCallback.this,
                        progress).recycleOnUse());
            }
        }
    }

    /**
     * Retrieve all of the information we know about a particular activity class including its
     * package states.
     *
     * @param packageName a specific package
     * @param filterCallingUid The results will be filtered in the context of this UID instead
     *                         of the calling UID.
     * @param userId The user for whom the package is installed
     * @return IncrementalStatesInfo that contains information about package states.
     */
    public abstract IncrementalStatesInfo getIncrementalStatesInfo(String packageName,
            int filterCallingUid, int userId);

    /**
     * Requesting the checksums for APKs within a package.
     * See {@link PackageManager#requestChecksums} for details.
     *
     * @param executor to use for digest calculations.
     * @param handler to use for postponed calculations.
     */
    public abstract void requestChecksums(@NonNull String packageName, boolean includeSplits,
            @Checksum.TypeMask int optional, @Checksum.TypeMask int required,
            @Nullable List trustedInstallers,
            @NonNull IOnChecksumsReadyListener onChecksumsReadyListener, int userId,
            @NonNull Executor executor, @NonNull Handler handler);

    /**
     * Returns true if the given {@code packageName} and {@code userId} is frozen.
     *
     * @param packageName a specific package
     * @param callingUid The uid of the caller
     * @param userId The user for whom the package is installed
     * @return {@code true} If the package is current frozen (due to install/update etc.)
     */
    public abstract boolean isPackageFrozen(
            @NonNull String packageName, int callingUid, int userId);

    /**
     * Deletes the OAT artifacts of a package.
     * @param packageName a specific package
     * @return the number of freed bytes or -1 if there was an error in the process.
     */
    public abstract long deleteOatArtifactsOfPackage(String packageName);

    /**
     * Reconcile all app data for the given user.
     */
    public abstract void reconcileAppsData(int userId, @StorageManager.StorageFlags int flags,
            boolean migrateAppsData);

    /**
     * Returns an array of PackageStateInternal that are all part of a shared user setting which is
     * denoted by the app ID. Returns an empty set if the shared user setting doesn't exist or does
     * not contain any package.
     */
    @NonNull
    public abstract ArraySet<PackageStateInternal> getSharedUserPackages(int sharedUserAppId);

    /**
     * Returns the SharedUserApi denoted by the app ID of the shared user setting. Returns null if
     * the corresponding shared user setting doesn't exist.
     */
    @Nullable
    public abstract SharedUserApi getSharedUserApi(int sharedUserAppId);

    /**
     * Returns if the given uid is privileged or not.
     */
    public abstract boolean isUidPrivileged(int uid);

    /**
     * Initiates a package state mutation request, returning the current state as known by
     * PackageManager. This allows the later commit request to compare the initial values and
     * determine if any state was changed or any packages were updated since the whole request
     * was initiated.
     *
     * As a concrete example, consider the following steps:
     * <ol>
     *     <li>Read a package state without taking a lock</li>
     *     <li>Check some values in that state, determine that a mutation needs to occur</li>
     *     <li>Call to commit the change with the new value, takes lock</li>
     * </ol>
     *
     * Between steps 1 and 3, because the lock was not taken for the entire flow, it's possible
     * a package state was changed by another consumer or a package was updated/installed.
     *
     * If anything has changed,
     * {@link #commitPackageStateMutation(PackageStateMutator.InitialState, Consumer)} will return
     * a {@link PackageStateMutator.Result} indicating so. If the caller has not indicated it can
     * ignore changes, it can opt to re-run the commit logic from the top with a true write lock
     * around all of its read-logic-commit loop.
     *
     * Note that if the caller does not care about potential race conditions or package/state
     * changes between steps 1 and 3, it can simply opt to not call this method and pass in null
     * for the initial state. This is useful to avoid long running data structure locks when the
     * caller is changing a value as part of a one-off request. Perhaps from an app side API which
     * mutates only a single package, where it doesn't care what the state of that package is or
     * any other packages on the devices.
     *
     * Important to note is that if no locking is enforced, callers themselves will not be
     * synchronized with themselves. The caller may be relying on the PackageManager lock to
     * enforce ordering within their own code path, and that has to be adjusted if migrated off
     * the lock.
     */
    @NonNull
    public abstract PackageStateMutator.InitialState recordInitialState();

    /**
     * Some questions to ask when designing a mutation:
     * <ol>
     *     <li>What external system state is required and is it synchronized properly?</li>
     *     <li>Are there any package/state changes that could happen to the target (or another)
     *     package that could result in the commit being invalid?</li>
     *     <li>Is the caller synchronized with itself and can handle multiple mutations being
     *     requested from different threads?</li>
     *     <li>What should be done in case of a conflict and the commit can't be finished?</li>
     * </ol>
     *
     * @param state See {@link #recordInitialState()}. If null, no result is returned.
     * @param consumer Lean wrapper around just the logic that changes state values
     * @return result if anything changed since initial state, or null if nothing changed and
     * commit was successful
     */
    @Nullable
    public abstract PackageStateMutator.Result commitPackageStateMutation(
            @Nullable PackageStateMutator.InitialState state,
            @NonNull Consumer<PackageStateMutator> consumer);

    /**
     * @return package data snapshot for use with other PackageManager infrastructure. This should
     * only be used as a parameter passed to another PM related class. Do not call methods on this
     * directly.
     */
    @NonNull
    public abstract PackageDataSnapshot snapshot();

    public abstract void shutdown();

    public abstract DynamicCodeLogger getDynamicCodeLogger();

    /**
     * Compare the signatures of two packages that are installed in different users.
     *
     * @param uid1 First UID whose signature will be compared.
     * @param uid2 Second UID whose signature will be compared.
     * @return {@link PackageManager#SIGNATURE_MATCH} if signatures are matched.
     * @throws SecurityException if the caller does not hold the
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS}.
     */
    public abstract @SignatureResult int checkUidSignaturesForAllUsers(int uid1, int uid2);

    public abstract void setPackageStoppedState(@NonNull String packageName, boolean stopped,
            @UserIdInt int userId);

    /**
     * Tells PackageManager when a component of the package is used
     * and the package should get out of stopped state and be enabled.
     */
    public abstract void notifyComponentUsed(@NonNull String packageName,
            @UserIdInt int userId, @Nullable String recentCallingPackage,
            @NonNull String debugInfo);

    /**
     * Gets {@link PackageManager.DistractionRestriction restrictions} of the given
     * packages of the given user.
     *
     * The corresponding element of the resulting array will be -1 if a given package doesn't exist.
     *
     * @param packageNames The packages under which to check.
     * @param userId The user under which to check.
     * @return an array of distracting restriction state in order of the given packages
     */
    public abstract int[] getDistractingPackageRestrictionsAsUser(
            @NonNull String[] packageNames, int userId);

    /**
     * Checks if package is quarantined for a specific user.
     *
     * @throws PackageManager.NameNotFoundException if the package is not found
     */
    public abstract boolean isPackageQuarantined(@NonNull String packageName, @UserIdInt int userId)
            throws PackageManager.NameNotFoundException;

    /**
     * Checks if package is stopped for a specific user.
     *
     * @throws PackageManager.NameNotFoundException if the package is not found
     */
    public abstract boolean isPackageStopped(@NonNull String packageName, @UserIdInt int userId)
            throws PackageManager.NameNotFoundException;

    /**
     * Sends the PACKAGE_RESTARTED broadcast.
     */
    public abstract void sendPackageRestartedBroadcast(@NonNull String packageName,
            int uid, @Intent.Flags int flags);

    /**
     * Return a list of all historical install sessions for the given user.
     */
    public abstract ParceledListSlice<PackageInstaller.SessionInfo> getHistoricalSessions(
            int userId);

    /**
     * Sends the ACTION_PACKAGE_DATA_CLEARED broadcast.
     */
    public abstract void sendPackageDataClearedBroadcast(@NonNull String packageName,
            int uid, int userId, boolean isRestore, boolean isInstantApp);

    /**
     * Returns an instance of {@link PackageArchiver} to be used for archiving related operations.
     */
    @NonNull
    public abstract PackageArchiver getPackageArchiver();

    /**
     * Returns true if the device is upgrading from an SDK version lower than the one specified.
     */
    public abstract boolean isUpgradingFromLowerThan(int sdkVersion);
}
