/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.annotation.XmlRes;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.ComponentInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstallSourceInfo;
import android.content.pm.InstantAppInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.KeySet;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.ArtManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.permission.IOnPermissionsChangeListener;
import android.permission.IPermissionManager;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.LauncherIcons;
import android.util.Log;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.UserIcons;

import dalvik.system.VMRuntime;

import libcore.util.EmptyArray;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** @hide */
public class ApplicationPackageManager extends PackageManager {
    private static final String TAG = "ApplicationPackageManager";
    private static final boolean DEBUG_ICONS = false;
    /**
     * Note: Changing this won't do anything on it's own - you should also change the filtering in
     * {@link #shouldTraceGrant}
     *
     * @hide
     */
    public static final boolean DEBUG_TRACE_GRANTS = false;
    public static final boolean DEBUG_TRACE_PERMISSION_UPDATES = false;

    private static final int DEFAULT_EPHEMERAL_COOKIE_MAX_SIZE_BYTES = 16384; // 16KB

    // Default flags to use with PackageManager when no flags are given.
    private final static int sDefaultFlags = PackageManager.GET_SHARED_LIBRARY_FILES;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private UserManager mUserManager;
    @GuardedBy("mLock")
    private PackageInstaller mInstaller;
    @GuardedBy("mLock")
    private ArtManager mArtManager;

    @GuardedBy("mDelegates")
    private final ArrayList<MoveCallbackDelegate> mDelegates = new ArrayList<>();

    @GuardedBy("mLock")
    private String mPermissionsControllerPackageName;

    UserManager getUserManager() {
        synchronized (mLock) {
            if (mUserManager == null) {
                mUserManager = UserManager.get(mContext);
            }
            return mUserManager;
        }
    }

    @Override
    public int getUserId() {
        return mContext.getUserId();
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags)
            throws NameNotFoundException {
        return getPackageInfoAsUser(packageName, flags, getUserId());
    }

    @Override
    public PackageInfo getPackageInfo(VersionedPackage versionedPackage, int flags)
            throws NameNotFoundException {
        final int userId = getUserId();
        try {
            PackageInfo pi = mPM.getPackageInfoVersioned(versionedPackage,
                    updateFlagsForPackage(flags, userId), userId);
            if (pi != null) {
                return pi;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        throw new NameNotFoundException(versionedPackage.toString());
    }

    @Override
    public PackageInfo getPackageInfoAsUser(String packageName, int flags, int userId)
            throws NameNotFoundException {
        try {
            PackageInfo pi = mPM.getPackageInfo(packageName,
                    updateFlagsForPackage(flags, userId), userId);
            if (pi != null) {
                return pi;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        throw new NameNotFoundException(packageName);
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) {
        try {
            return mPM.currentToCanonicalPackageNames(names);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) {
        try {
            return mPM.canonicalToCurrentPackageNames(names);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public Intent getLaunchIntentForPackage(String packageName) {
        // First see if the package has an INFO activity; the existence of
        // such an activity is implied to be the desired front-door for the
        // overall package (such as if it has multiple launcher entries).
        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_INFO);
        intentToResolve.setPackage(packageName);
        List<ResolveInfo> ris = queryIntentActivities(intentToResolve, 0);

        // Otherwise, try to find a main launcher activity.
        if (ris == null || ris.size() <= 0) {
            // reuse the intent instance
            intentToResolve.removeCategory(Intent.CATEGORY_INFO);
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
            intentToResolve.setPackage(packageName);
            ris = queryIntentActivities(intentToResolve, 0);
        }
        if (ris == null || ris.size() <= 0) {
            return null;
        }
        Intent intent = new Intent(intentToResolve);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(ris.get(0).activityInfo.packageName,
                ris.get(0).activityInfo.name);
        return intent;
    }

    @Override
    public Intent getLeanbackLaunchIntentForPackage(String packageName) {
        return getLaunchIntentForPackageAndCategory(packageName, Intent.CATEGORY_LEANBACK_LAUNCHER);
    }

    @Override
    public Intent getCarLaunchIntentForPackage(String packageName) {
        return getLaunchIntentForPackageAndCategory(packageName, Intent.CATEGORY_CAR_LAUNCHER);
    }

    private Intent getLaunchIntentForPackageAndCategory(String packageName, String category) {
        // Try to find a main launcher activity for the given categories.
        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(category);
        intentToResolve.setPackage(packageName);
        List<ResolveInfo> ris = queryIntentActivities(intentToResolve, 0);

        if (ris == null || ris.size() <= 0) {
            return null;
        }
        Intent intent = new Intent(intentToResolve);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(ris.get(0).activityInfo.packageName,
                ris.get(0).activityInfo.name);
        return intent;
    }

    @Override
    public int[] getPackageGids(String packageName) throws NameNotFoundException {
        return getPackageGids(packageName, 0);
    }

    @Override
    public int[] getPackageGids(String packageName, int flags)
            throws NameNotFoundException {
        final int userId = getUserId();
        try {
            int[] gids = mPM.getPackageGids(packageName,
                    updateFlagsForPackage(flags, userId), userId);
            if (gids != null) {
                return gids;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        throw new NameNotFoundException(packageName);
    }

    @Override
    public int getPackageUid(String packageName, int flags) throws NameNotFoundException {
        return getPackageUidAsUser(packageName, flags, getUserId());
    }

    @Override
    public int getPackageUidAsUser(String packageName, int userId) throws NameNotFoundException {
        return getPackageUidAsUser(packageName, 0, userId);
    }

    @Override
    public int getPackageUidAsUser(String packageName, int flags, int userId)
            throws NameNotFoundException {
        try {
            int uid = mPM.getPackageUid(packageName,
                    updateFlagsForPackage(flags, userId), userId);
            if (uid >= 0) {
                return uid;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        throw new NameNotFoundException(packageName);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        try {
            final ParceledListSlice<PermissionGroupInfo> parceledList =
                    mPermissionManager.getAllPermissionGroups(flags);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags)
            throws NameNotFoundException {
        try {
            final PermissionGroupInfo pgi =
                    mPermissionManager.getPermissionGroupInfo(groupName, flags);
            if (pgi != null) {
                return pgi;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        throw new NameNotFoundException(groupName);
    }

    @Override
    public PermissionInfo getPermissionInfo(String permName, int flags)
            throws NameNotFoundException {
        try {
            final String packageName = mContext.getOpPackageName();
            final PermissionInfo pi =
                    mPermissionManager.getPermissionInfo(permName, packageName, flags);
            if (pi != null) {
                return pi;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        throw new NameNotFoundException(permName);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PermissionInfo> queryPermissionsByGroup(String groupName, int flags)
            throws NameNotFoundException {
        try {
            final ParceledListSlice<PermissionInfo> parceledList =
                    mPermissionManager.queryPermissionsByGroup(groupName, flags);
            if (parceledList != null) {
                final List<PermissionInfo> pi = parceledList.getList();
                if (pi != null) {
                    return pi;
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        throw new NameNotFoundException(groupName);
    }

    @Override
    public boolean arePermissionsIndividuallyControlled() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_permissionsIndividuallyControlled);
    }

    @Override
    public boolean isWirelessConsentModeEnabled() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wirelessConsentRequired);
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags)
            throws NameNotFoundException {
        return getApplicationInfoAsUser(packageName, flags, getUserId());
    }

    @Override
    public ApplicationInfo getApplicationInfoAsUser(String packageName, int flags, int userId)
            throws NameNotFoundException {
        try {
            ApplicationInfo ai = mPM.getApplicationInfo(packageName,
                    updateFlagsForApplication(flags, userId), userId);
            if (ai != null) {
                // This is a temporary hack. Callers must use
                // createPackageContext(packageName).getApplicationInfo() to
                // get the right paths.
                return maybeAdjustApplicationInfo(ai);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        throw new NameNotFoundException(packageName);
    }

    private static ApplicationInfo maybeAdjustApplicationInfo(ApplicationInfo info) {
        // If we're dealing with a multi-arch application that has both
        // 32 and 64 bit shared libraries, we might need to choose the secondary
        // depending on what the current runtime's instruction set is.
        if (info.primaryCpuAbi != null && info.secondaryCpuAbi != null) {
            final String runtimeIsa = VMRuntime.getRuntime().vmInstructionSet();

            // Get the instruction set that the libraries of secondary Abi is supported.
            // In presence of a native bridge this might be different than the one secondary Abi used.
            String secondaryIsa = VMRuntime.getInstructionSet(info.secondaryCpuAbi);
            final String secondaryDexCodeIsa = SystemProperties.get("ro.dalvik.vm.isa." + secondaryIsa);
            secondaryIsa = secondaryDexCodeIsa.isEmpty() ? secondaryIsa : secondaryDexCodeIsa;

            // If the runtimeIsa is the same as the primary isa, then we do nothing.
            // Everything will be set up correctly because info.nativeLibraryDir will
            // correspond to the right ISA.
            if (runtimeIsa.equals(secondaryIsa)) {
                ApplicationInfo modified = new ApplicationInfo(info);
                modified.nativeLibraryDir = info.secondaryNativeLibraryDir;
                return modified;
            }
        }
        return info;
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName className, int flags)
            throws NameNotFoundException {
        final int userId = getUserId();
        try {
            ActivityInfo ai = mPM.getActivityInfo(className,
                    updateFlagsForComponent(flags, userId, null), userId);
            if (ai != null) {
                return ai;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        throw new NameNotFoundException(className.toString());
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName className, int flags)
            throws NameNotFoundException {
        final int userId = getUserId();
        try {
            ActivityInfo ai = mPM.getReceiverInfo(className,
                    updateFlagsForComponent(flags, userId, null), userId);
            if (ai != null) {
                return ai;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        throw new NameNotFoundException(className.toString());
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName className, int flags)
            throws NameNotFoundException {
        final int userId = getUserId();
        try {
            ServiceInfo si = mPM.getServiceInfo(className,
                    updateFlagsForComponent(flags, userId, null), userId);
            if (si != null) {
                return si;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        throw new NameNotFoundException(className.toString());
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName className, int flags)
            throws NameNotFoundException {
        final int userId = getUserId();
        try {
            ProviderInfo pi = mPM.getProviderInfo(className,
                    updateFlagsForComponent(flags, userId, null), userId);
            if (pi != null) {
                return pi;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        throw new NameNotFoundException(className.toString());
    }

    @Override
    public String[] getSystemSharedLibraryNames() {
        try {
            return mPM.getSystemSharedLibraryNames();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Override
    public @NonNull List<SharedLibraryInfo> getSharedLibraries(int flags) {
        return getSharedLibrariesAsUser(flags, getUserId());
    }

    /** @hide */
    @Override
    @SuppressWarnings("unchecked")
    public @NonNull List<SharedLibraryInfo> getSharedLibrariesAsUser(int flags, int userId) {
        try {
            ParceledListSlice<SharedLibraryInfo> sharedLibs = mPM.getSharedLibraries(
                    mContext.getOpPackageName(), flags, userId);
            if (sharedLibs == null) {
                return Collections.emptyList();
            }
            return sharedLibs.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    @Override
    public List<SharedLibraryInfo> getDeclaredSharedLibraries(@NonNull String packageName,
            @InstallFlags int flags) {
        try {
            ParceledListSlice<SharedLibraryInfo> sharedLibraries = mPM.getDeclaredSharedLibraries(
                    packageName, flags, mContext.getUserId());
            return sharedLibraries != null ? sharedLibraries.getList() : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Override
    public @NonNull String getServicesSystemSharedLibraryPackageName() {
        try {
            return mPM.getServicesSystemSharedLibraryPackageName();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public @NonNull String getSharedSystemSharedLibraryPackageName() {
        try {
            return mPM.getSharedSystemSharedLibraryPackageName();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public ChangedPackages getChangedPackages(int sequenceNumber) {
        try {
            return mPM.getChangedPackages(sequenceNumber, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public FeatureInfo[] getSystemAvailableFeatures() {
        try {
            ParceledListSlice<FeatureInfo> parceledList =
                    mPM.getSystemAvailableFeatures();
            if (parceledList == null) {
                return new FeatureInfo[0];
            }
            final List<FeatureInfo> list = parceledList.getList();
            final FeatureInfo[] res = new FeatureInfo[list.size()];
            for (int i = 0; i < res.length; i++) {
                res[i] = list.get(i);
            }
            return res;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean hasSystemFeature(String name) {
        return hasSystemFeature(name, 0);
    }

    @Override
    public boolean hasSystemFeature(String name, int version) {
        try {
            return mPM.hasSystemFeature(name, version);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int checkPermission(String permName, String pkgName) {
        try {
            return mPermissionManager.checkPermission(permName, pkgName, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean isPermissionRevokedByPolicy(String permName, String pkgName) {
        try {
            return mPermissionManager.isPermissionRevokedByPolicy(permName, pkgName, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @Override
    public String getPermissionControllerPackageName() {
        synchronized (mLock) {
            if (mPermissionsControllerPackageName == null) {
                try {
                    mPermissionsControllerPackageName = mPM.getPermissionControllerPackageName();
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            return mPermissionsControllerPackageName;
        }
    }

    @Override
    public boolean addPermission(PermissionInfo info) {
        try {
            return mPermissionManager.addPermission(info, false);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean addPermissionAsync(PermissionInfo info) {
        try {
            return mPermissionManager.addPermission(info, true);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void removePermission(String name) {
        try {
            mPermissionManager.removePermission(name);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void grantRuntimePermission(String packageName, String permissionName,
            UserHandle user) {
        if (DEBUG_TRACE_GRANTS
                && shouldTraceGrant(packageName, permissionName, user.getIdentifier())) {
            Log.i(TAG, "App " + mContext.getPackageName() + " is granting " + packageName + " "
                    + permissionName + " for user " + user.getIdentifier(), new RuntimeException());
        }
        try {
            mPM.grantRuntimePermission(packageName, permissionName, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public static boolean shouldTraceGrant(String packageName, String permissionName, int userId) {
        // To be modified when debugging
        return false;
    }

    @Override
    public void revokeRuntimePermission(String packageName, String permName, UserHandle user) {
        if (DEBUG_TRACE_PERMISSION_UPDATES
                && shouldTraceGrant(packageName, permName, user.getIdentifier())) {
            Log.i(TAG, "App " + mContext.getPackageName() + " is revoking " + packageName + " "
                    + permName + " for user " + user.getIdentifier(), new RuntimeException());
        }
        try {
            mPermissionManager
                    .revokeRuntimePermission(packageName, permName, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int getPermissionFlags(String permName, String packageName, UserHandle user) {
        try {
            return mPermissionManager
                    .getPermissionFlags(permName, packageName, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void updatePermissionFlags(String permName, String packageName,
            int flagMask, int flagValues, UserHandle user) {
        if (DEBUG_TRACE_PERMISSION_UPDATES
                && shouldTraceGrant(packageName, permName, user.getIdentifier())) {
            Log.i(TAG, "App " + mContext.getPackageName() + " is updating flags for "
                    + packageName + " "
                    + permName + " for user " + user.getIdentifier() + ": "
                    + DebugUtils.flagsToString(PackageManager.class, "FLAG_PERMISSION_", flagMask)
                    + " := " + DebugUtils.flagsToString(
                            PackageManager.class, "FLAG_PERMISSION_", flagValues),
                    new RuntimeException());
        }
        try {
            final boolean checkAdjustPolicyFlagPermission =
                    mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.Q;
            mPermissionManager.updatePermissionFlags(permName, packageName, flagMask,
                    flagValues, checkAdjustPolicyFlagPermission, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public @NonNull Set<String> getWhitelistedRestrictedPermissions(
            @NonNull String packageName, @PermissionWhitelistFlags int flags) {
        try {
            final int userId = getUserId();
            final List<String> whitelist = mPermissionManager
                    .getWhitelistedRestrictedPermissions(packageName, flags, userId);
            if (whitelist != null) {
                return new ArraySet<>(whitelist);
            }
            return Collections.emptySet();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean addWhitelistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, @PermissionWhitelistFlags int flags) {
        try {
            final int userId = getUserId();
            return mPermissionManager
                    .addWhitelistedRestrictedPermission(packageName, permName, flags, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean removeWhitelistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, @PermissionWhitelistFlags int flags) {
        try {
            final int userId = getUserId();
            return mPermissionManager
                    .removeWhitelistedRestrictedPermission(packageName, permName, flags, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @UnsupportedAppUsage
    public boolean shouldShowRequestPermissionRationale(String permName) {
        try {
            final String packageName = mContext.getPackageName();
            return mPermissionManager
                    .shouldShowRequestPermissionRationale(permName, packageName, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int checkSignatures(String pkg1, String pkg2) {
        try {
            return mPM.checkSignatures(pkg1, pkg2);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int checkSignatures(int uid1, int uid2) {
        try {
            return mPM.checkUidSignatures(uid1, uid2);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean hasSigningCertificate(
            String packageName, byte[] certificate, @PackageManager.CertificateInputType int type) {
        try {
            return mPM.hasSigningCertificate(packageName, certificate, type);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean hasSigningCertificate(
            int uid, byte[] certificate, @PackageManager.CertificateInputType int type) {
        try {
            return mPM.hasUidSigningCertificate(uid, certificate, type);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String[] getPackagesForUid(int uid) {
        try {
            return mPM.getPackagesForUid(uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String getNameForUid(int uid) {
        try {
            return mPM.getNameForUid(uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String[] getNamesForUids(int[] uids) {
        try {
            return mPM.getNamesForUids(uids);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int getUidForSharedUser(String sharedUserName)
            throws NameNotFoundException {
        try {
            int uid = mPM.getUidForSharedUser(sharedUserName);
            if(uid != -1) {
                return uid;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        throw new NameNotFoundException("No shared userid for user:"+sharedUserName);
    }

    @Override
    public List<ModuleInfo> getInstalledModules(int flags) {
        try {
            return mPM.getInstalledModules(flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public ModuleInfo getModuleInfo(String packageName, int flags) throws NameNotFoundException {
        try {
            ModuleInfo mi = mPM.getModuleInfo(packageName, flags);
            if (mi != null) {
                return mi;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        throw new NameNotFoundException("No module info for package: " + packageName);
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<PackageInfo> getInstalledPackages(int flags) {
        return getInstalledPackagesAsUser(flags, getUserId());
    }

    /** @hide */
    @Override
    @SuppressWarnings("unchecked")
    public List<PackageInfo> getInstalledPackagesAsUser(int flags, int userId) {
        try {
            ParceledListSlice<PackageInfo> parceledList =
                    mPM.getInstalledPackages(updateFlagsForPackage(flags, userId), userId);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<PackageInfo> getPackagesHoldingPermissions(
            String[] permissions, int flags) {
        final int userId = getUserId();
        try {
            ParceledListSlice<PackageInfo> parceledList =
                    mPM.getPackagesHoldingPermissions(permissions,
                            updateFlagsForPackage(flags, userId), userId);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags) {
        return getInstalledApplicationsAsUser(flags, getUserId());
    }

    /** @hide */
    @SuppressWarnings("unchecked")
    @Override
    public List<ApplicationInfo> getInstalledApplicationsAsUser(int flags, int userId) {
        try {
            ParceledListSlice<ApplicationInfo> parceledList =
                    mPM.getInstalledApplications(updateFlagsForApplication(flags, userId), userId);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @SuppressWarnings("unchecked")
    @Override
    public List<InstantAppInfo> getInstantApps() {
        try {
            ParceledListSlice<InstantAppInfo> slice = mPM.getInstantApps(getUserId());
            if (slice != null) {
                return slice.getList();
            }
            return Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Override
    public Drawable getInstantAppIcon(String packageName) {
        try {
            Bitmap bitmap = mPM.getInstantAppIcon(packageName, getUserId());
            if (bitmap != null) {
                return new BitmapDrawable(null, bitmap);
            }
            return null;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean isInstantApp() {
        return isInstantApp(mContext.getPackageName());
    }

    @Override
    public boolean isInstantApp(String packageName) {
        try {
            return mPM.isInstantApp(packageName, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public int getInstantAppCookieMaxBytes() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.EPHEMERAL_COOKIE_MAX_SIZE_BYTES,
                DEFAULT_EPHEMERAL_COOKIE_MAX_SIZE_BYTES);
    }

    @Override
    public int getInstantAppCookieMaxSize() {
        return getInstantAppCookieMaxBytes();
    }

    @Override
    public @NonNull byte[] getInstantAppCookie() {
        try {
            final byte[] cookie = mPM.getInstantAppCookie(mContext.getPackageName(), getUserId());
            if (cookie != null) {
                return cookie;
            } else {
                return EmptyArray.BYTE;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void clearInstantAppCookie() {
        updateInstantAppCookie(null);
    }

    @Override
    public void updateInstantAppCookie(@NonNull byte[] cookie) {
        if (cookie != null && cookie.length > getInstantAppCookieMaxBytes()) {
            throw new IllegalArgumentException("instant cookie longer than "
                    + getInstantAppCookieMaxBytes());
        }
        try {
            mPM.setInstantAppCookie(mContext.getPackageName(), cookie, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @UnsupportedAppUsage
    @Override
    public boolean setInstantAppCookie(@NonNull byte[] cookie) {
        try {
            return mPM.setInstantAppCookie(mContext.getPackageName(), cookie, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public ResolveInfo resolveActivity(Intent intent, int flags) {
        return resolveActivityAsUser(intent, flags, getUserId());
    }

    @Override
    public ResolveInfo resolveActivityAsUser(Intent intent, int flags, int userId) {
        try {
            return mPM.resolveIntent(
                intent,
                intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                updateFlagsForComponent(flags, userId, intent),
                userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent,
                                                   int flags) {
        return queryIntentActivitiesAsUser(intent, flags, getUserId());
    }

    /** @hide Same as above but for a specific user */
    @Override
    @SuppressWarnings("unchecked")
    public List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent,
            int flags, int userId) {
        try {
            ParceledListSlice<ResolveInfo> parceledList = mPM.queryIntentActivities(
                    intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    updateFlagsForComponent(flags, userId, intent),
                    userId);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ResolveInfo> queryIntentActivityOptions(ComponentName caller, Intent[] specifics,
            Intent intent, int flags) {
        final int userId = getUserId();
        final ContentResolver resolver = mContext.getContentResolver();

        String[] specificTypes = null;
        if (specifics != null) {
            final int N = specifics.length;
            for (int i=0; i<N; i++) {
                Intent sp = specifics[i];
                if (sp != null) {
                    String t = sp.resolveTypeIfNeeded(resolver);
                    if (t != null) {
                        if (specificTypes == null) {
                            specificTypes = new String[N];
                        }
                        specificTypes[i] = t;
                    }
                }
            }
        }

        try {
            ParceledListSlice<ResolveInfo> parceledList = mPM.queryIntentActivityOptions(
                    caller,
                    specifics,
                    specificTypes,
                    intent,
                    intent.resolveTypeIfNeeded(resolver),
                    updateFlagsForComponent(flags, userId, intent),
                    userId);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<ResolveInfo> queryBroadcastReceiversAsUser(Intent intent, int flags, int userId) {
        try {
            ParceledListSlice<ResolveInfo> parceledList = mPM.queryIntentReceivers(
                    intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    updateFlagsForComponent(flags, userId, intent),
                    userId);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
        return queryBroadcastReceiversAsUser(intent, flags, getUserId());
    }

    @Override
    public ResolveInfo resolveServiceAsUser(Intent intent, @ResolveInfoFlags int flags,
            @UserIdInt int userId) {
        try {
            return mPM.resolveService(
                intent,
                intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                updateFlagsForComponent(flags, userId, intent),
                userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public ResolveInfo resolveService(Intent intent, int flags) {
        return resolveServiceAsUser(intent, flags, getUserId());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ResolveInfo> queryIntentServicesAsUser(Intent intent, int flags, int userId) {
        try {
            ParceledListSlice<ResolveInfo> parceledList = mPM.queryIntentServices(
                    intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    updateFlagsForComponent(flags, userId, intent),
                    userId);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
        return queryIntentServicesAsUser(intent, flags, getUserId());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ResolveInfo> queryIntentContentProvidersAsUser(
            Intent intent, int flags, int userId) {
        try {
            ParceledListSlice<ResolveInfo> parceledList = mPM.queryIntentContentProviders(
                    intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    updateFlagsForComponent(flags, userId, intent),
                    userId);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public List<ResolveInfo> queryIntentContentProviders(Intent intent, int flags) {
        return queryIntentContentProvidersAsUser(intent, flags, getUserId());
    }

    @Override
    public ProviderInfo resolveContentProvider(String name, int flags) {
        return resolveContentProviderAsUser(name, flags, getUserId());
    }

    /** @hide **/
    @Override
    public ProviderInfo resolveContentProviderAsUser(String name, int flags, int userId) {
        try {
            return mPM.resolveContentProvider(name,
                    updateFlagsForComponent(flags, userId, null), userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName,
            int uid, int flags) {
        return queryContentProviders(processName, uid, flags, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ProviderInfo> queryContentProviders(String processName,
            int uid, int flags, String metaDataKey) {
        try {
            ParceledListSlice<ProviderInfo> slice = mPM.queryContentProviders(processName, uid,
                    updateFlagsForComponent(flags, UserHandle.getUserId(uid), null), metaDataKey);
            return slice != null ? slice.getList() : Collections.<ProviderInfo>emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public InstrumentationInfo getInstrumentationInfo(
        ComponentName className, int flags)
            throws NameNotFoundException {
        try {
            InstrumentationInfo ii = mPM.getInstrumentationInfo(
                className, flags);
            if (ii != null) {
                return ii;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        throw new NameNotFoundException(className.toString());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<InstrumentationInfo> queryInstrumentation(
        String targetPackage, int flags) {
        try {
            ParceledListSlice<InstrumentationInfo> parceledList =
                    mPM.queryInstrumentation(targetPackage, flags);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Nullable
    @Override
    public Drawable getDrawable(String packageName, @DrawableRes int resId,
            @Nullable ApplicationInfo appInfo) {
        final ResourceName name = new ResourceName(packageName, resId);
        final Drawable cachedIcon = getCachedIcon(name);
        if (cachedIcon != null) {
            return cachedIcon;
        }

        if (appInfo == null) {
            try {
                appInfo = getApplicationInfo(packageName, sDefaultFlags);
            } catch (NameNotFoundException e) {
                return null;
            }
        }

        if (resId != 0) {
            try {
                final Resources r = getResourcesForApplication(appInfo);
                final Drawable dr = r.getDrawable(resId, null);
                if (dr != null) {
                    putCachedIcon(name, dr);
                }

                if (false) {
                    RuntimeException e = new RuntimeException("here");
                    e.fillInStackTrace();
                    Log.w(TAG, "Getting drawable 0x" + Integer.toHexString(resId)
                                    + " from package " + packageName
                                    + ": app scale=" + r.getCompatibilityInfo().applicationScale
                                    + ", caller scale=" + mContext.getResources()
                                    .getCompatibilityInfo().applicationScale,
                            e);
                }
                if (DEBUG_ICONS) {
                    Log.v(TAG, "Getting drawable 0x"
                            + Integer.toHexString(resId) + " from " + r
                            + ": " + dr);
                }
                return dr;
            } catch (NameNotFoundException e) {
                Log.w("PackageManager", "Failure retrieving resources for "
                        + appInfo.packageName);
            } catch (Resources.NotFoundException e) {
                Log.w("PackageManager", "Failure retrieving resources for "
                        + appInfo.packageName + ": " + e.getMessage());
            } catch (Exception e) {
                // If an exception was thrown, fall through to return
                // default icon.
                Log.w("PackageManager", "Failure retrieving icon 0x"
                        + Integer.toHexString(resId) + " in package "
                        + packageName, e);
            }
        }

        return null;
    }

    @Override public Drawable getActivityIcon(ComponentName activityName)
            throws NameNotFoundException {
        return getActivityInfo(activityName, sDefaultFlags).loadIcon(this);
    }

    @Override public Drawable getActivityIcon(Intent intent)
            throws NameNotFoundException {
        if (intent.getComponent() != null) {
            return getActivityIcon(intent.getComponent());
        }

        ResolveInfo info = resolveActivity(
            intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null) {
            return info.activityInfo.loadIcon(this);
        }

        throw new NameNotFoundException(intent.toUri(0));
    }

    @Override public Drawable getDefaultActivityIcon() {
        return mContext.getDrawable(com.android.internal.R.drawable.sym_def_app_icon);
    }

    @Override public Drawable getApplicationIcon(ApplicationInfo info) {
        return info.loadIcon(this);
    }

    @Override public Drawable getApplicationIcon(String packageName)
            throws NameNotFoundException {
        return getApplicationIcon(getApplicationInfo(packageName, sDefaultFlags));
    }

    @Override
    public Drawable getActivityBanner(ComponentName activityName)
            throws NameNotFoundException {
        return getActivityInfo(activityName, sDefaultFlags).loadBanner(this);
    }

    @Override
    public Drawable getActivityBanner(Intent intent)
            throws NameNotFoundException {
        if (intent.getComponent() != null) {
            return getActivityBanner(intent.getComponent());
        }

        ResolveInfo info = resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null) {
            return info.activityInfo.loadBanner(this);
        }

        throw new NameNotFoundException(intent.toUri(0));
    }

    @Override
    public Drawable getApplicationBanner(ApplicationInfo info) {
        return info.loadBanner(this);
    }

    @Override
    public Drawable getApplicationBanner(String packageName)
            throws NameNotFoundException {
        return getApplicationBanner(getApplicationInfo(packageName, sDefaultFlags));
    }

    @Override
    public Drawable getActivityLogo(ComponentName activityName)
            throws NameNotFoundException {
        return getActivityInfo(activityName, sDefaultFlags).loadLogo(this);
    }

    @Override
    public Drawable getActivityLogo(Intent intent)
            throws NameNotFoundException {
        if (intent.getComponent() != null) {
            return getActivityLogo(intent.getComponent());
        }

        ResolveInfo info = resolveActivity(
            intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null) {
            return info.activityInfo.loadLogo(this);
        }

        throw new NameNotFoundException(intent.toUri(0));
    }

    @Override
    public Drawable getApplicationLogo(ApplicationInfo info) {
        return info.loadLogo(this);
    }

    @Override
    public Drawable getApplicationLogo(String packageName)
            throws NameNotFoundException {
        return getApplicationLogo(getApplicationInfo(packageName, sDefaultFlags));
    }

    @Override
    public Drawable getUserBadgedIcon(Drawable icon, UserHandle user) {
        if (!hasUserBadge(user.getIdentifier())) {
            return icon;
        }
        Drawable badge = new LauncherIcons(mContext).getBadgeDrawable(
                getUserManager().getUserIconBadgeResId(user.getIdentifier()),
                getUserBadgeColor(user));
        return getBadgedDrawable(icon, badge, null, true);
    }

    @Override
    public Drawable getUserBadgedDrawableForDensity(Drawable drawable, UserHandle user,
            Rect badgeLocation, int badgeDensity) {
        Drawable badgeDrawable = getUserBadgeForDensity(user, badgeDensity);
        if (badgeDrawable == null) {
            return drawable;
        }
        return getBadgedDrawable(drawable, badgeDrawable, badgeLocation, true);
    }

    /** Returns the color of the user's actual badge (not the badge's shadow). */
    private int getUserBadgeColor(UserHandle user) {
        return getUserManager().getUserBadgeColor(user.getIdentifier());
    }

    @Override
    public Drawable getUserBadgeForDensity(UserHandle user, int density) {
        // This is part of the shadow, not the main color, and is not actually corp-specific.
        Drawable badgeColor = getProfileIconForDensity(user,
                com.android.internal.R.drawable.ic_corp_badge_color, density);
        if (badgeColor == null) {
            return null;
        }
        Drawable badgeForeground = getDrawableForDensity(
                getUserManager().getUserBadgeResId(user.getIdentifier()), density);
        badgeForeground.setTint(getUserBadgeColor(user));
        Drawable badge = new LayerDrawable(new Drawable[] {badgeColor, badgeForeground });
        return badge;
    }

    @Override
    public Drawable getUserBadgeForDensityNoBackground(UserHandle user, int density) {
        Drawable badge = getProfileIconForDensity(user,
                getUserManager().getUserBadgeNoBackgroundResId(user.getIdentifier()), density);
        if (badge != null) {
            badge.setTint(getUserBadgeColor(user));
        }
        return badge;
    }

    private Drawable getDrawableForDensity(int drawableId, int density) {
        if (density <= 0) {
            density = mContext.getResources().getDisplayMetrics().densityDpi;
        }
        return mContext.getResources().getDrawableForDensity(drawableId, density);
    }

    private Drawable getProfileIconForDensity(UserHandle user, int drawableId, int density) {
        if (hasUserBadge(user.getIdentifier())) {
            return getDrawableForDensity(drawableId, density);
        }
        return null;
    }

    @Override
    public CharSequence getUserBadgedLabel(CharSequence label, UserHandle user) {
        return getUserManager().getBadgedLabelForUser(label, user);
    }

    @Override
    public Resources getResourcesForActivity(ComponentName activityName)
            throws NameNotFoundException {
        return getResourcesForApplication(
            getActivityInfo(activityName, sDefaultFlags).applicationInfo);
    }

    @Override
    public Resources getResourcesForApplication(@NonNull ApplicationInfo app)
            throws NameNotFoundException {
        if (app.packageName.equals("system")) {
            return mContext.mMainThread.getSystemUiContext().getResources();
        }
        final boolean sameUid = (app.uid == Process.myUid());
        final Resources r = mContext.mMainThread.getTopLevelResources(
                    sameUid ? app.sourceDir : app.publicSourceDir,
                    sameUid ? app.splitSourceDirs : app.splitPublicSourceDirs,
                    app.resourceDirs, app.sharedLibraryFiles, Display.DEFAULT_DISPLAY,
                    mContext.mPackageInfo);
        if (r != null) {
            return r;
        }
        throw new NameNotFoundException("Unable to open " + app.publicSourceDir);

    }

    @Override
    public Resources getResourcesForApplication(String appPackageName)
            throws NameNotFoundException {
        return getResourcesForApplication(
            getApplicationInfo(appPackageName, sDefaultFlags));
    }

    /** @hide */
    @Override
    public Resources getResourcesForApplicationAsUser(String appPackageName, int userId)
            throws NameNotFoundException {
        if (userId < 0) {
            throw new IllegalArgumentException(
                    "Call does not support special user #" + userId);
        }
        if ("system".equals(appPackageName)) {
            return mContext.mMainThread.getSystemUiContext().getResources();
        }
        try {
            ApplicationInfo ai = mPM.getApplicationInfo(appPackageName, sDefaultFlags, userId);
            if (ai != null) {
                return getResourcesForApplication(ai);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        throw new NameNotFoundException("Package " + appPackageName + " doesn't exist");
    }

    volatile int mCachedSafeMode = -1;

    @Override
    public boolean isSafeMode() {
        try {
            if (mCachedSafeMode < 0) {
                mCachedSafeMode = mPM.isSafeMode() ? 1 : 0;
            }
            return mCachedSafeMode != 0;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void addOnPermissionsChangeListener(OnPermissionsChangedListener listener) {
        synchronized (mPermissionListeners) {
            if (mPermissionListeners.get(listener) != null) {
                return;
            }
            OnPermissionsChangeListenerDelegate delegate =
                    new OnPermissionsChangeListenerDelegate(listener, Looper.getMainLooper());
            try {
                mPermissionManager.addOnPermissionsChangeListener(delegate);
                mPermissionListeners.put(listener, delegate);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    @Override
    public void removeOnPermissionsChangeListener(OnPermissionsChangedListener listener) {
        synchronized (mPermissionListeners) {
            IOnPermissionsChangeListener delegate = mPermissionListeners.get(listener);
            if (delegate != null) {
                try {
                    mPermissionManager.removeOnPermissionsChangeListener(delegate);
                    mPermissionListeners.remove(listener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    @UnsupportedAppUsage
    static void configurationChanged() {
        synchronized (sSync) {
            sIconCache.clear();
            sStringCache.clear();
        }
    }

    protected ApplicationPackageManager(ContextImpl context, IPackageManager pm,
            IPermissionManager permissionManager) {
        mContext = context;
        mPM = pm;
        mPermissionManager = permissionManager;
    }

    /**
     * Update given flags when being used to request {@link PackageInfo}.
     */
    private int updateFlagsForPackage(int flags, int userId) {
        if ((flags & (GET_ACTIVITIES | GET_RECEIVERS | GET_SERVICES | GET_PROVIDERS)) != 0) {
            // Caller is asking for component details, so they'd better be
            // asking for specific Direct Boot matching behavior
            if ((flags & (MATCH_DIRECT_BOOT_UNAWARE
                    | MATCH_DIRECT_BOOT_AWARE
                    | MATCH_DIRECT_BOOT_AUTO)) == 0) {
                onImplicitDirectBoot(userId);
            }
        }
        return flags;
    }

    /**
     * Update given flags when being used to request {@link ApplicationInfo}.
     */
    private int updateFlagsForApplication(int flags, int userId) {
        return updateFlagsForPackage(flags, userId);
    }

    /**
     * Update given flags when being used to request {@link ComponentInfo}.
     */
    private int updateFlagsForComponent(int flags, int userId, Intent intent) {
        if (intent != null) {
            if ((intent.getFlags() & Intent.FLAG_DIRECT_BOOT_AUTO) != 0) {
                flags |= MATCH_DIRECT_BOOT_AUTO;
            }
        }

        // Caller is asking for component details, so they'd better be
        // asking for specific Direct Boot matching behavior
        if ((flags & (MATCH_DIRECT_BOOT_UNAWARE
                | MATCH_DIRECT_BOOT_AWARE
                | MATCH_DIRECT_BOOT_AUTO)) == 0) {
            onImplicitDirectBoot(userId);
        }
        return flags;
    }

    private void onImplicitDirectBoot(int userId) {
        // Only report if someone is relying on implicit behavior while the user
        // is locked; code running when unlocked is going to see both aware and
        // unaware components.
        if (StrictMode.vmImplicitDirectBootEnabled()) {
            // We can cache the unlocked state for the userId we're running as,
            // since any relocking of that user will always result in our
            // process being killed to release any CE FDs we're holding onto.
            if (userId == UserHandle.myUserId()) {
                if (mUserUnlocked) {
                    return;
                } else if (mContext.getSystemService(UserManager.class)
                        .isUserUnlockingOrUnlocked(userId)) {
                    mUserUnlocked = true;
                } else {
                    StrictMode.onImplicitDirectBoot();
                }
            } else if (!mContext.getSystemService(UserManager.class)
                    .isUserUnlockingOrUnlocked(userId)) {
                StrictMode.onImplicitDirectBoot();
            }
        }
    }

    @Nullable
    private Drawable getCachedIcon(@NonNull ResourceName name) {
        synchronized (sSync) {
            final WeakReference<Drawable.ConstantState> wr = sIconCache.get(name);
            if (DEBUG_ICONS) Log.v(TAG, "Get cached weak drawable ref for "
                                   + name + ": " + wr);
            if (wr != null) {   // we have the activity
                final Drawable.ConstantState state = wr.get();
                if (state != null) {
                    if (DEBUG_ICONS) {
                        Log.v(TAG, "Get cached drawable state for " + name + ": " + state);
                    }
                    // Note: It's okay here to not use the newDrawable(Resources) variant
                    //       of the API. The ConstantState comes from a drawable that was
                    //       originally created by passing the proper app Resources instance
                    //       which means the state should already contain the proper
                    //       resources specific information (like density.) See
                    //       BitmapDrawable.BitmapState for instance.
                    return state.newDrawable();
                }
                // our entry has been purged
                sIconCache.remove(name);
            }
        }
        return null;
    }

    private void putCachedIcon(@NonNull ResourceName name, @NonNull Drawable dr) {
        synchronized (sSync) {
            sIconCache.put(name, new WeakReference<>(dr.getConstantState()));
            if (DEBUG_ICONS) Log.v(TAG, "Added cached drawable state for " + name + ": " + dr);
        }
    }

    static void handlePackageBroadcast(int cmd, String[] pkgList, boolean hasPkgInfo) {
        boolean immediateGc = false;
        if (cmd == ApplicationThreadConstants.EXTERNAL_STORAGE_UNAVAILABLE) {
            immediateGc = true;
        }
        if (pkgList != null && (pkgList.length > 0)) {
            boolean needCleanup = false;
            for (String ssp : pkgList) {
                synchronized (sSync) {
                    for (int i=sIconCache.size()-1; i>=0; i--) {
                        ResourceName nm = sIconCache.keyAt(i);
                        if (nm.packageName.equals(ssp)) {
                            //Log.i(TAG, "Removing cached drawable for " + nm);
                            sIconCache.removeAt(i);
                            needCleanup = true;
                        }
                    }
                    for (int i=sStringCache.size()-1; i>=0; i--) {
                        ResourceName nm = sStringCache.keyAt(i);
                        if (nm.packageName.equals(ssp)) {
                            //Log.i(TAG, "Removing cached string for " + nm);
                            sStringCache.removeAt(i);
                            needCleanup = true;
                        }
                    }
                }
            }
            if (needCleanup || hasPkgInfo) {
                if (immediateGc) {
                    // Schedule an immediate gc.
                    Runtime.getRuntime().gc();
                } else {
                    ActivityThread.currentActivityThread().scheduleGcIdler();
                }
            }
        }
    }

    private static final class ResourceName {
        final String packageName;
        final int iconId;

        ResourceName(String _packageName, int _iconId) {
            packageName = _packageName;
            iconId = _iconId;
        }

        ResourceName(ApplicationInfo aInfo, int _iconId) {
            this(aInfo.packageName, _iconId);
        }

        ResourceName(ComponentInfo cInfo, int _iconId) {
            this(cInfo.applicationInfo.packageName, _iconId);
        }

        ResourceName(ResolveInfo rInfo, int _iconId) {
            this(rInfo.activityInfo.applicationInfo.packageName, _iconId);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ResourceName that = (ResourceName) o;

            if (iconId != that.iconId) return false;
            return !(packageName != null ?
                     !packageName.equals(that.packageName) : that.packageName != null);

        }

        @Override
        public int hashCode() {
            int result;
            result = packageName.hashCode();
            result = 31 * result + iconId;
            return result;
        }

        @Override
        public String toString() {
            return "{ResourceName " + packageName + " / " + iconId + "}";
        }
    }

    private CharSequence getCachedString(ResourceName name) {
        synchronized (sSync) {
            WeakReference<CharSequence> wr = sStringCache.get(name);
            if (wr != null) {   // we have the activity
                CharSequence cs = wr.get();
                if (cs != null) {
                    return cs;
                }
                // our entry has been purged
                sStringCache.remove(name);
            }
        }
        return null;
    }

    private void putCachedString(ResourceName name, CharSequence cs) {
        synchronized (sSync) {
            sStringCache.put(name, new WeakReference<CharSequence>(cs));
        }
    }

    @Override
    public CharSequence getText(String packageName, @StringRes int resid,
                                ApplicationInfo appInfo) {
        ResourceName name = new ResourceName(packageName, resid);
        CharSequence text = getCachedString(name);
        if (text != null) {
            return text;
        }
        if (appInfo == null) {
            try {
                appInfo = getApplicationInfo(packageName, sDefaultFlags);
            } catch (NameNotFoundException e) {
                return null;
            }
        }
        try {
            Resources r = getResourcesForApplication(appInfo);
            text = r.getText(resid);
            putCachedString(name, text);
            return text;
        } catch (NameNotFoundException e) {
            Log.w("PackageManager", "Failure retrieving resources for "
                  + appInfo.packageName);
        } catch (RuntimeException e) {
            // If an exception was thrown, fall through to return
            // default icon.
            Log.w("PackageManager", "Failure retrieving text 0x"
                  + Integer.toHexString(resid) + " in package "
                  + packageName, e);
        }
        return null;
    }

    @Override
    public XmlResourceParser getXml(String packageName, @XmlRes int resid,
                                    ApplicationInfo appInfo) {
        if (appInfo == null) {
            try {
                appInfo = getApplicationInfo(packageName, sDefaultFlags);
            } catch (NameNotFoundException e) {
                return null;
            }
        }
        try {
            Resources r = getResourcesForApplication(appInfo);
            return r.getXml(resid);
        } catch (RuntimeException e) {
            // If an exception was thrown, fall through to return
            // default icon.
            Log.w("PackageManager", "Failure retrieving xml 0x"
                  + Integer.toHexString(resid) + " in package "
                  + packageName, e);
        } catch (NameNotFoundException e) {
            Log.w("PackageManager", "Failure retrieving resources for "
                  + appInfo.packageName);
        }
        return null;
    }

    @Override
    public CharSequence getApplicationLabel(ApplicationInfo info) {
        return info.loadLabel(this);
    }

    @Override
    public int installExistingPackage(String packageName) throws NameNotFoundException {
        return installExistingPackage(packageName, PackageManager.INSTALL_REASON_UNKNOWN);
    }

    @Override
    public int installExistingPackage(String packageName, int installReason)
            throws NameNotFoundException {
        return installExistingPackageAsUser(packageName, installReason, getUserId());
    }

    @Override
    public int installExistingPackageAsUser(String packageName, int userId)
            throws NameNotFoundException {
        return installExistingPackageAsUser(packageName, PackageManager.INSTALL_REASON_UNKNOWN,
                userId);
    }

    private int installExistingPackageAsUser(String packageName, int installReason, int userId)
            throws NameNotFoundException {
        try {
            int res = mPM.installExistingPackageAsUser(packageName, userId,
                    INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS, installReason, null);
            if (res == INSTALL_FAILED_INVALID_URI) {
                throw new NameNotFoundException("Package " + packageName + " doesn't exist");
            }
            return res;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void verifyPendingInstall(int id, int response) {
        try {
            mPM.verifyPendingInstall(id, response);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void extendVerificationTimeout(int id, int verificationCodeAtTimeout,
            long millisecondsToDelay) {
        try {
            mPM.extendVerificationTimeout(id, verificationCodeAtTimeout, millisecondsToDelay);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void verifyIntentFilter(int id, int verificationCode, List<String> failedDomains) {
        try {
            mPM.verifyIntentFilter(id, verificationCode, failedDomains);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int getIntentVerificationStatusAsUser(String packageName, int userId) {
        try {
            return mPM.getIntentVerificationStatus(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean updateIntentVerificationStatusAsUser(String packageName, int status, int userId) {
        try {
            return mPM.updateIntentVerificationStatus(packageName, status, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<IntentFilterVerificationInfo> getIntentFilterVerifications(String packageName) {
        try {
            ParceledListSlice<IntentFilterVerificationInfo> parceledList =
                    mPM.getIntentFilterVerifications(packageName);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<IntentFilter> getAllIntentFilters(String packageName) {
        try {
            ParceledListSlice<IntentFilter> parceledList =
                    mPM.getAllIntentFilters(packageName);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String getDefaultBrowserPackageNameAsUser(int userId) {
        try {
            return mPermissionManager.getDefaultBrowser(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean setDefaultBrowserPackageNameAsUser(String packageName, int userId) {
        try {
            return mPermissionManager.setDefaultBrowser(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setInstallerPackageName(String targetPackage,
            String installerPackageName) {
        try {
            mPM.setInstallerPackageName(targetPackage, installerPackageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setUpdateAvailable(String packageName, boolean updateAvailable) {
        try {
            mPM.setUpdateAvailable(packageName, updateAvailable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String getInstallerPackageName(String packageName) {
        try {
            return mPM.getInstallerPackageName(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @NonNull
    public InstallSourceInfo getInstallSourceInfo(String packageName) throws NameNotFoundException {
        final InstallSourceInfo installSourceInfo;
        try {
            installSourceInfo = mPM.getInstallSourceInfo(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        if (installSourceInfo == null) {
            throw new NameNotFoundException(packageName);
        }
        return installSourceInfo;
    }

    @Override
    public int getMoveStatus(int moveId) {
        try {
            return mPM.getMoveStatus(moveId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void registerMoveCallback(MoveCallback callback, Handler handler) {
        synchronized (mDelegates) {
            final MoveCallbackDelegate delegate = new MoveCallbackDelegate(callback,
                    handler.getLooper());
            try {
                mPM.registerMoveCallback(delegate);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mDelegates.add(delegate);
        }
    }

    @Override
    public void unregisterMoveCallback(MoveCallback callback) {
        synchronized (mDelegates) {
            for (Iterator<MoveCallbackDelegate> i = mDelegates.iterator(); i.hasNext();) {
                final MoveCallbackDelegate delegate = i.next();
                if (delegate.mCallback == callback) {
                    try {
                        mPM.unregisterMoveCallback(delegate);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    i.remove();
                }
            }
        }
    }

    @Override
    public int movePackage(String packageName, VolumeInfo vol) {
        try {
            final String volumeUuid;
            if (VolumeInfo.ID_PRIVATE_INTERNAL.equals(vol.id)) {
                volumeUuid = StorageManager.UUID_PRIVATE_INTERNAL;
            } else if (vol.isPrimaryPhysical()) {
                volumeUuid = StorageManager.UUID_PRIMARY_PHYSICAL;
            } else {
                volumeUuid = Objects.requireNonNull(vol.fsUuid);
            }

            return mPM.movePackage(packageName, volumeUuid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @UnsupportedAppUsage
    public @Nullable VolumeInfo getPackageCurrentVolume(ApplicationInfo app) {
        final StorageManager storage = mContext.getSystemService(StorageManager.class);
        return getPackageCurrentVolume(app, storage);
    }

    @VisibleForTesting
    protected @Nullable VolumeInfo getPackageCurrentVolume(ApplicationInfo app,
            StorageManager storage) {
        if (app.isInternal()) {
            return storage.findVolumeById(VolumeInfo.ID_PRIVATE_INTERNAL);
        } else {
            return storage.findVolumeByUuid(app.volumeUuid);
        }
    }

    @Override
    public @NonNull List<VolumeInfo> getPackageCandidateVolumes(ApplicationInfo app) {
        final StorageManager storageManager = mContext.getSystemService(StorageManager.class);
        return getPackageCandidateVolumes(app, storageManager, mPM);
    }

    @VisibleForTesting
    protected @NonNull List<VolumeInfo> getPackageCandidateVolumes(ApplicationInfo app,
            StorageManager storageManager, IPackageManager pm) {
        final VolumeInfo currentVol = getPackageCurrentVolume(app, storageManager);
        final List<VolumeInfo> vols = storageManager.getVolumes();
        final List<VolumeInfo> candidates = new ArrayList<>();
        for (VolumeInfo vol : vols) {
            if (Objects.equals(vol, currentVol)
                    || isPackageCandidateVolume(mContext, app, vol, pm)) {
                candidates.add(vol);
            }
        }
        return candidates;
    }

    @VisibleForTesting
    protected boolean isForceAllowOnExternal(Context context) {
        return Settings.Global.getInt(
                context.getContentResolver(), Settings.Global.FORCE_ALLOW_ON_EXTERNAL, 0) != 0;
    }

    @VisibleForTesting
    protected boolean isAllow3rdPartyOnInternal(Context context) {
        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_allow3rdPartyAppOnInternal);
    }

    private boolean isPackageCandidateVolume(
            ContextImpl context, ApplicationInfo app, VolumeInfo vol, IPackageManager pm) {
        final boolean forceAllowOnExternal = isForceAllowOnExternal(context);

        if (VolumeInfo.ID_PRIVATE_INTERNAL.equals(vol.getId())) {
            return app.isSystemApp() || isAllow3rdPartyOnInternal(context);
        }

        // System apps and apps demanding internal storage can't be moved
        // anywhere else
        if (app.isSystemApp()) {
            return false;
        }
        if (!forceAllowOnExternal
                && (app.installLocation == PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY
                        || app.installLocation == PackageInfo.INSTALL_LOCATION_UNSPECIFIED)) {
            return false;
        }

        // Gotta be able to write there
        if (!vol.isMountedWritable()) {
            return false;
        }

        // Moving into an ASEC on public primary is only option internal
        if (vol.isPrimaryPhysical()) {
            return app.isInternal();
        }

        // Some apps can't be moved. (e.g. device admins)
        try {
            if (pm.isPackageDeviceAdminOnAnyUser(app.packageName)) {
                return false;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Otherwise we can move to any private volume
        return (vol.getType() == VolumeInfo.TYPE_PRIVATE);
    }

    @Override
    public int movePrimaryStorage(VolumeInfo vol) {
        try {
            final String volumeUuid;
            if (VolumeInfo.ID_PRIVATE_INTERNAL.equals(vol.id)) {
                volumeUuid = StorageManager.UUID_PRIVATE_INTERNAL;
            } else if (vol.isPrimaryPhysical()) {
                volumeUuid = StorageManager.UUID_PRIMARY_PHYSICAL;
            } else {
                volumeUuid = Objects.requireNonNull(vol.fsUuid);
            }

            return mPM.movePrimaryStorage(volumeUuid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public @Nullable VolumeInfo getPrimaryStorageCurrentVolume() {
        final StorageManager storage = mContext.getSystemService(StorageManager.class);
        final String volumeUuid = storage.getPrimaryStorageUuid();
        return storage.findVolumeByQualifiedUuid(volumeUuid);
    }

    @Override
    public @NonNull List<VolumeInfo> getPrimaryStorageCandidateVolumes() {
        final StorageManager storage = mContext.getSystemService(StorageManager.class);
        final VolumeInfo currentVol = getPrimaryStorageCurrentVolume();
        final List<VolumeInfo> vols = storage.getVolumes();
        final List<VolumeInfo> candidates = new ArrayList<>();
        if (Objects.equals(StorageManager.UUID_PRIMARY_PHYSICAL,
                storage.getPrimaryStorageUuid()) && currentVol != null) {
            // TODO: support moving primary physical to emulated volume
            candidates.add(currentVol);
        } else {
            for (VolumeInfo vol : vols) {
                if (Objects.equals(vol, currentVol) || isPrimaryStorageCandidateVolume(vol)) {
                    candidates.add(vol);
                }
            }
        }
        return candidates;
    }

    private static boolean isPrimaryStorageCandidateVolume(VolumeInfo vol) {
        // Private internal is always an option
        if (VolumeInfo.ID_PRIVATE_INTERNAL.equals(vol.getId())) {
            return true;
        }

        // Gotta be able to write there
        if (!vol.isMountedWritable()) {
            return false;
        }

        // We can move to any private volume
        return (vol.getType() == VolumeInfo.TYPE_PRIVATE);
    }

    @Override
    @UnsupportedAppUsage
    public void deletePackage(String packageName, IPackageDeleteObserver observer, int flags) {
        deletePackageAsUser(packageName, observer, flags, getUserId());
    }

    @Override
    public void deletePackageAsUser(String packageName, IPackageDeleteObserver observer,
            int flags, int userId) {
        try {
            mPM.deletePackageAsUser(packageName, PackageManager.VERSION_CODE_HIGHEST,
                    observer, userId, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void clearApplicationUserData(String packageName,
                                         IPackageDataObserver observer) {
        try {
            mPM.clearApplicationUserData(packageName, observer, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
    @Override
    public void deleteApplicationCacheFiles(String packageName,
                                            IPackageDataObserver observer) {
        try {
            mPM.deleteApplicationCacheFiles(packageName, observer);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void deleteApplicationCacheFilesAsUser(String packageName, int userId,
            IPackageDataObserver observer) {
        try {
            mPM.deleteApplicationCacheFilesAsUser(packageName, userId, observer);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void freeStorageAndNotify(String volumeUuid, long idealStorageSize,
            IPackageDataObserver observer) {
        try {
            mPM.freeStorageAndNotify(volumeUuid, idealStorageSize, 0, observer);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void freeStorage(String volumeUuid, long freeStorageSize, IntentSender pi) {
        try {
            mPM.freeStorage(volumeUuid, freeStorageSize, 0, pi);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String[] setDistractingPackageRestrictions(String[] packages, int distractionFlags) {
        try {
            return mPM.setDistractingPackageRestrictionsAsUser(packages, distractionFlags,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String[] setPackagesSuspended(String[] packageNames, boolean suspended,
            PersistableBundle appExtras, PersistableBundle launcherExtras,
            String dialogMessage) {
        final SuspendDialogInfo dialogInfo = !TextUtils.isEmpty(dialogMessage)
                ? new SuspendDialogInfo.Builder().setMessage(dialogMessage).build()
                : null;
        return setPackagesSuspended(packageNames, suspended, appExtras, launcherExtras, dialogInfo);
    }

    @Override
    public String[] setPackagesSuspended(String[] packageNames, boolean suspended,
            PersistableBundle appExtras, PersistableBundle launcherExtras,
            SuspendDialogInfo dialogInfo) {
        try {
            return mPM.setPackagesSuspendedAsUser(packageNames, suspended, appExtras,
                    launcherExtras, dialogInfo, mContext.getOpPackageName(),
                    getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String[] getUnsuspendablePackages(String[] packageNames) {
        try {
            return mPM.getUnsuspendablePackagesForUser(packageNames, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public Bundle getSuspendedPackageAppExtras() {
        try {
            return mPM.getSuspendedPackageAppExtras(mContext.getOpPackageName(), getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean isPackageSuspendedForUser(String packageName, int userId) {
        try {
            return mPM.isPackageSuspendedForUser(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Override
    public boolean isPackageSuspended(String packageName) throws NameNotFoundException {
        try {
            return isPackageSuspendedForUser(packageName, getUserId());
        } catch (IllegalArgumentException ie) {
            throw new NameNotFoundException(packageName);
        }
    }

    @Override
    public boolean isPackageSuspended() {
        return isPackageSuspendedForUser(mContext.getOpPackageName(), getUserId());
    }

    /** @hide */
    @Override
    public void setApplicationCategoryHint(String packageName, int categoryHint) {
        try {
            mPM.setApplicationCategoryHint(packageName, categoryHint,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @UnsupportedAppUsage
    public void getPackageSizeInfoAsUser(String packageName, int userHandle,
            IPackageStatsObserver observer) {
        final String msg = "Shame on you for calling the hidden API "
                + "getPackageSizeInfoAsUser(). Shame!";
        if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.O) {
            throw new UnsupportedOperationException(msg);
        } else if (observer != null) {
            Log.d(TAG, msg);
            try {
                observer.onGetStatsCompleted(null, false);
            } catch (RemoteException ignored) {
            }
        }
    }

    @Override
    public void addPackageToPreferred(String packageName) {
        Log.w(TAG, "addPackageToPreferred() is a no-op");
    }

    @Override
    public void removePackageFromPreferred(String packageName) {
        Log.w(TAG, "removePackageFromPreferred() is a no-op");
    }

    @Override
    public List<PackageInfo> getPreferredPackages(int flags) {
        Log.w(TAG, "getPreferredPackages() is a no-op");
        return Collections.emptyList();
    }

    @Override
    public void addPreferredActivity(IntentFilter filter,
                                     int match, ComponentName[] set, ComponentName activity) {
        try {
            mPM.addPreferredActivity(filter, match, set, activity, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void addPreferredActivityAsUser(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, int userId) {
        try {
            mPM.addPreferredActivity(filter, match, set, activity, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void replacePreferredActivity(IntentFilter filter,
                                         int match, ComponentName[] set, ComponentName activity) {
        try {
            mPM.replacePreferredActivity(filter, match, set, activity, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void replacePreferredActivityAsUser(IntentFilter filter,
                                         int match, ComponentName[] set, ComponentName activity,
                                         int userId) {
        try {
            mPM.replacePreferredActivity(filter, match, set, activity, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void clearPackagePreferredActivities(String packageName) {
        try {
            mPM.clearPackagePreferredActivities(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int getPreferredActivities(List<IntentFilter> outFilters,
                                      List<ComponentName> outActivities, String packageName) {
        try {
            return mPM.getPreferredActivities(outFilters, outActivities, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public ComponentName getHomeActivities(List<ResolveInfo> outActivities) {
        try {
            return mPM.getHomeActivities(outActivities);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setSyntheticAppDetailsActivityEnabled(String packageName, boolean enabled) {
        try {
            ComponentName componentName = new ComponentName(packageName,
                    PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME);
            mPM.setComponentEnabledSetting(componentName, enabled
                    ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean getSyntheticAppDetailsActivityEnabled(String packageName) {
        try {
            ComponentName componentName = new ComponentName(packageName,
                    PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME);
            int state = mPM.getComponentEnabledSetting(componentName, getUserId());
            return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setComponentEnabledSetting(ComponentName componentName,
                                           int newState, int flags) {
        try {
            mPM.setComponentEnabledSetting(componentName, newState, flags, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int getComponentEnabledSetting(ComponentName componentName) {
        try {
            return mPM.getComponentEnabledSetting(componentName, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setApplicationEnabledSetting(String packageName,
                                             int newState, int flags) {
        try {
            mPM.setApplicationEnabledSetting(packageName, newState, flags,
                    getUserId(), mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int getApplicationEnabledSetting(String packageName) {
        try {
            return mPM.getApplicationEnabledSetting(packageName, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void flushPackageRestrictionsAsUser(int userId) {
        try {
            mPM.flushPackageRestrictionsAsUser(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean setApplicationHiddenSettingAsUser(String packageName, boolean hidden,
            UserHandle user) {
        try {
            return mPM.setApplicationHiddenSettingAsUser(packageName, hidden,
                    user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean getApplicationHiddenSettingAsUser(String packageName, UserHandle user) {
        try {
            return mPM.getApplicationHiddenSettingAsUser(packageName, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Override
    public KeySet getKeySetByAlias(String packageName, String alias) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(alias);
        try {
            return mPM.getKeySetByAlias(packageName, alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Override
    public KeySet getSigningKeySet(String packageName) {
        Objects.requireNonNull(packageName);
        try {
            return mPM.getSigningKeySet(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Override
    public boolean isSignedBy(String packageName, KeySet ks) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(ks);
        try {
            return mPM.isPackageSignedByKeySet(packageName, ks);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Override
    public boolean isSignedByExactly(String packageName, KeySet ks) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(ks);
        try {
            return mPM.isPackageSignedByKeySetExactly(packageName, ks);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @Override
    public VerifierDeviceIdentity getVerifierDeviceIdentity() {
        try {
            return mPM.getVerifierDeviceIdentity();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean isUpgrade() {
        return isDeviceUpgrading();
    }

    @Override
    public boolean isDeviceUpgrading() {
        try {
            return mPM.isDeviceUpgrading();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public PackageInstaller getPackageInstaller() {
        synchronized (mLock) {
            if (mInstaller == null) {
                try {
                    mInstaller = new PackageInstaller(mPM.getPackageInstaller(),
                            mContext.getPackageName(), getUserId());
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            return mInstaller;
        }
    }

    @Override
    public boolean isPackageAvailable(String packageName) {
        try {
            return mPM.isPackageAvailable(packageName, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @Override
    public void addCrossProfileIntentFilter(IntentFilter filter, int sourceUserId, int targetUserId,
            int flags) {
        try {
            mPM.addCrossProfileIntentFilter(filter, mContext.getOpPackageName(),
                    sourceUserId, targetUserId, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @Override
    public void clearCrossProfileIntentFilters(int sourceUserId) {
        try {
            mPM.clearCrossProfileIntentFilters(sourceUserId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public Drawable loadItemIcon(PackageItemInfo itemInfo, ApplicationInfo appInfo) {
        Drawable dr = loadUnbadgedItemIcon(itemInfo, appInfo);
        if (itemInfo.showUserIcon != UserHandle.USER_NULL) {
            return dr;
        }
        return getUserBadgedIcon(dr, new UserHandle(getUserId()));
    }

    /**
     * @hide
     */
    public Drawable loadUnbadgedItemIcon(@NonNull PackageItemInfo itemInfo,
            @Nullable ApplicationInfo appInfo) {
        if (itemInfo.showUserIcon != UserHandle.USER_NULL) {
            // Indicates itemInfo is for a different user (e.g. a profile's parent), so use a
            // generic user icon (users generally lack permission to view each other's actual icons)
            int targetUserId = itemInfo.showUserIcon;
            return UserIcons.getDefaultUserIcon(
                    mContext.getResources(), targetUserId, /* light= */ false);
        }
        Drawable dr = null;
        if (itemInfo.packageName != null) {
            dr = getDrawable(itemInfo.packageName, itemInfo.icon, appInfo);
        }
        if (dr == null && itemInfo != appInfo && appInfo != null) {
            dr = loadUnbadgedItemIcon(appInfo, appInfo);
        }
        if (dr == null) {
            dr = itemInfo.loadDefaultIcon(this);
        }
        return dr;
    }

    private Drawable getBadgedDrawable(Drawable drawable, Drawable badgeDrawable,
            Rect badgeLocation, boolean tryBadgeInPlace) {
        final int badgedWidth = drawable.getIntrinsicWidth();
        final int badgedHeight = drawable.getIntrinsicHeight();
        final boolean canBadgeInPlace = tryBadgeInPlace
                && (drawable instanceof BitmapDrawable)
                && ((BitmapDrawable) drawable).getBitmap().isMutable();

        final Bitmap bitmap;
        if (canBadgeInPlace) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            bitmap = Bitmap.createBitmap(badgedWidth, badgedHeight, Bitmap.Config.ARGB_8888);
        }
        Canvas canvas = new Canvas(bitmap);

        if (!canBadgeInPlace) {
            drawable.setBounds(0, 0, badgedWidth, badgedHeight);
            drawable.draw(canvas);
        }

        if (badgeLocation != null) {
            if (badgeLocation.left < 0 || badgeLocation.top < 0
                    || badgeLocation.width() > badgedWidth || badgeLocation.height() > badgedHeight) {
                throw new IllegalArgumentException("Badge location " + badgeLocation
                        + " not in badged drawable bounds "
                        + new Rect(0, 0, badgedWidth, badgedHeight));
            }
            badgeDrawable.setBounds(0, 0, badgeLocation.width(), badgeLocation.height());

            canvas.save();
            canvas.translate(badgeLocation.left, badgeLocation.top);
            badgeDrawable.draw(canvas);
            canvas.restore();
        } else {
            badgeDrawable.setBounds(0, 0, badgedWidth, badgedHeight);
            badgeDrawable.draw(canvas);
        }

        if (!canBadgeInPlace) {
            BitmapDrawable mergedDrawable = new BitmapDrawable(mContext.getResources(), bitmap);

            if (drawable instanceof BitmapDrawable) {
                BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
                mergedDrawable.setTargetDensity(bitmapDrawable.getBitmap().getDensity());
            }

            return mergedDrawable;
        }

        return drawable;
    }

    private boolean hasUserBadge(int userId) {
        return getUserManager().hasBadge(userId);
    }

    /**
     * @hide
     */
    @Override
    public int getInstallReason(String packageName, UserHandle user) {
        try {
            return mPM.getInstallReason(packageName, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    private static class MoveCallbackDelegate extends IPackageMoveObserver.Stub implements
            Handler.Callback {
        private static final int MSG_CREATED = 1;
        private static final int MSG_STATUS_CHANGED = 2;

        final MoveCallback mCallback;
        final Handler mHandler;

        public MoveCallbackDelegate(MoveCallback callback, Looper looper) {
            mCallback = callback;
            mHandler = new Handler(looper, this);
        }

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CREATED: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    mCallback.onCreated(args.argi1, (Bundle) args.arg2);
                    args.recycle();
                    return true;
                }
                case MSG_STATUS_CHANGED: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    mCallback.onStatusChanged(args.argi1, args.argi2, (long) args.arg3);
                    args.recycle();
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onCreated(int moveId, Bundle extras) {
            final SomeArgs args = SomeArgs.obtain();
            args.argi1 = moveId;
            args.arg2 = extras;
            mHandler.obtainMessage(MSG_CREATED, args).sendToTarget();
        }

        @Override
        public void onStatusChanged(int moveId, int status, long estMillis) {
            final SomeArgs args = SomeArgs.obtain();
            args.argi1 = moveId;
            args.argi2 = status;
            args.arg3 = estMillis;
            mHandler.obtainMessage(MSG_STATUS_CHANGED, args).sendToTarget();
        }
    }

    private final ContextImpl mContext;
    @UnsupportedAppUsage
    private final IPackageManager mPM;
    private final IPermissionManager mPermissionManager;

    /** Assume locked until we hear otherwise */
    private volatile boolean mUserUnlocked = false;

    private static final Object sSync = new Object();
    private static ArrayMap<ResourceName, WeakReference<Drawable.ConstantState>> sIconCache
            = new ArrayMap<ResourceName, WeakReference<Drawable.ConstantState>>();
    private static ArrayMap<ResourceName, WeakReference<CharSequence>> sStringCache
            = new ArrayMap<ResourceName, WeakReference<CharSequence>>();

    private final Map<OnPermissionsChangedListener, IOnPermissionsChangeListener>
            mPermissionListeners = new ArrayMap<>();

    public class OnPermissionsChangeListenerDelegate extends IOnPermissionsChangeListener.Stub
            implements Handler.Callback{
        private static final int MSG_PERMISSIONS_CHANGED = 1;

        private final OnPermissionsChangedListener mListener;
        private final Handler mHandler;


        public OnPermissionsChangeListenerDelegate(OnPermissionsChangedListener listener,
                Looper looper) {
            mListener = listener;
            mHandler = new Handler(looper, this);
        }

        @Override
        public void onPermissionsChanged(int uid) {
            mHandler.obtainMessage(MSG_PERMISSIONS_CHANGED, uid, 0).sendToTarget();
        }

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PERMISSIONS_CHANGED: {
                    final int uid = msg.arg1;
                    mListener.onPermissionsChanged(uid);
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public boolean canRequestPackageInstalls() {
        try {
            return mPM.canRequestPackageInstalls(mContext.getPackageName(), getUserId());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public ComponentName getInstantAppResolverSettingsComponent() {
        try {
            return mPM.getInstantAppResolverSettingsComponent();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public ComponentName getInstantAppInstallerComponent() {
        try {
            return mPM.getInstantAppInstallerComponent();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public String getInstantAppAndroidId(String packageName, UserHandle user) {
        try {
            return mPM.getInstantAppAndroidId(packageName, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    private static class DexModuleRegisterResult {
        final String dexModulePath;
        final boolean success;
        final String message;

        private DexModuleRegisterResult(String dexModulePath, boolean success, String message) {
            this.dexModulePath = dexModulePath;
            this.success = success;
            this.message = message;
        }
    }

    private static class DexModuleRegisterCallbackDelegate
            extends android.content.pm.IDexModuleRegisterCallback.Stub
            implements Handler.Callback {
        private static final int MSG_DEX_MODULE_REGISTERED = 1;
        private final DexModuleRegisterCallback callback;
        private final Handler mHandler;

        DexModuleRegisterCallbackDelegate(@NonNull DexModuleRegisterCallback callback) {
            this.callback = callback;
            mHandler = new Handler(Looper.getMainLooper(), this);
        }

        @Override
        public void onDexModuleRegistered(@NonNull String dexModulePath, boolean success,
                @Nullable String message)throws RemoteException {
            mHandler.obtainMessage(MSG_DEX_MODULE_REGISTERED,
                    new DexModuleRegisterResult(dexModulePath, success, message)).sendToTarget();
        }

        @Override
        public boolean handleMessage(Message msg) {
            if (msg.what != MSG_DEX_MODULE_REGISTERED) {
                return false;
            }
            DexModuleRegisterResult result = (DexModuleRegisterResult)msg.obj;
            callback.onDexModuleRegistered(result.dexModulePath, result.success, result.message);
            return true;
        }
    }

    @Override
    public void registerDexModule(@NonNull String dexModule,
            @Nullable DexModuleRegisterCallback callback) {
        // Check if this is a shared module by looking if the others can read it.
        boolean isSharedModule = false;
        try {
            StructStat stat = Os.stat(dexModule);
            if ((OsConstants.S_IROTH & stat.st_mode) != 0) {
                isSharedModule = true;
            }
        } catch (ErrnoException e) {
            callback.onDexModuleRegistered(dexModule, false,
                    "Could not get stat the module file: " + e.getMessage());
            return;
        }

        // Module path is ok.
        // Create the callback delegate to be passed to package manager service.
        DexModuleRegisterCallbackDelegate callbackDelegate = null;
        if (callback != null) {
            callbackDelegate = new DexModuleRegisterCallbackDelegate(callback);
        }

        // Invoke the package manager service.
        try {
            mPM.registerDexModule(mContext.getPackageName(), dexModule,
                    isSharedModule, callbackDelegate);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public CharSequence getHarmfulAppWarning(String packageName) {
        try {
            return mPM.getHarmfulAppWarning(packageName, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void setHarmfulAppWarning(String packageName, CharSequence warning) {
        try {
            mPM.setHarmfulAppWarning(packageName, warning, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public ArtManager getArtManager() {
        synchronized (mLock) {
            if (mArtManager == null) {
                try {
                    mArtManager = new ArtManager(mContext, mPM.getArtManager());
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            return mArtManager;
        }
    }

    @Override
    public String getSystemTextClassifierPackageName() {
        try {
            return mPM.getSystemTextClassifierPackageName();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public String[] getSystemTextClassifierPackages() {
        try {
            return mPM.getSystemTextClassifierPackages();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public String getAttentionServicePackageName() {
        try {
            return mPM.getAttentionServicePackageName();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public String getWellbeingPackageName() {
        try {
            return mPM.getWellbeingPackageName();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public String getAppPredictionServicePackageName() {
        try {
            return mPM.getAppPredictionServicePackageName();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public String[] getTelephonyPackageNames() {
        try {
            return mPM.getTelephonyPackageNames();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public String getSystemCaptionsServicePackageName() {
        try {
            return mPM.getSystemCaptionsServicePackageName();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public String getSetupWizardPackageName() {
        try {
            return mPM.getSetupWizardPackageName();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public String getIncidentReportApproverPackageName() {
        try {
            return mPM.getIncidentReportApproverPackageName();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public String getContentCaptureServicePackageName() {
        try {
            return mPM.getContentCaptureServicePackageName();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public boolean isPackageStateProtected(String packageName, int userId) {
        try {
            return mPM.isPackageStateProtected(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public void sendDeviceCustomizationReadyBroadcast() {
        try {
            mPM.sendDeviceCustomizationReadyBroadcast();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }
}
