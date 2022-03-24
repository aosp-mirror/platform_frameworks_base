/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.Manifest.permission.DELETE_PACKAGES;
import static android.Manifest.permission.INSTALL_PACKAGES;
import static android.Manifest.permission.REQUEST_DELETE_PACKAGES;
import static android.Manifest.permission.SET_HARMFUL_APP_WARNINGS;
import static android.content.ContentProvider.isAuthorityRedirectedForCloneProfile;
import static android.content.Intent.ACTION_MAIN;
import static android.content.Intent.CATEGORY_DEFAULT;
import static android.content.Intent.CATEGORY_HOME;
import static android.content.pm.PackageManager.CERT_INPUT_RAW_X509;
import static android.content.pm.PackageManager.CERT_INPUT_SHA256;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER;
import static android.content.pm.PackageManager.MATCH_ALL;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.content.pm.PackageManager.MATCH_APEX;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS;
import static android.content.pm.PackageManager.MATCH_FACTORY_ONLY;
import static android.content.pm.PackageManager.MATCH_KNOWN_PACKAGES;
import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.PackageManager.TYPE_ACTIVITY;
import static android.content.pm.PackageManager.TYPE_PROVIDER;
import static android.content.pm.PackageManager.TYPE_RECEIVER;
import static android.content.pm.PackageManager.TYPE_SERVICE;
import static android.content.pm.PackageManager.TYPE_UNKNOWN;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import static com.android.internal.app.IntentForwarderActivity.FORWARD_INTENT_TO_MANAGED_PROFILE;
import static com.android.internal.app.IntentForwarderActivity.FORWARD_INTENT_TO_PARENT;
import static com.android.server.pm.PackageManagerService.DEBUG_DOMAIN_VERIFICATION;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTANT;
import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_INFO;
import static com.android.server.pm.PackageManagerService.DEBUG_PREFERRED;
import static com.android.server.pm.PackageManagerService.EMPTY_INT_ARRAY;
import static com.android.server.pm.PackageManagerService.HIDE_EPHEMERAL_APIS;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.compareSignatures;
import static com.android.server.pm.resolution.ComponentResolver.RESOLVE_PRIORITY_SORTER;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PermissionChecker;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.InstantAppRequest;
import android.content.pm.InstantAppResolveInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.KeySet;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProcessInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelableException;
import android.os.PatternMatcher;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.LogPrinter;
import android.util.LongSparseLongArray;
import android.util.MathUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TypedXmlSerializer;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.PackageDexUsage;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageStateUtils;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.PackageUserStateUtils;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.pm.pkg.component.ParsedActivity;
import com.android.server.pm.pkg.component.ParsedInstrumentation;
import com.android.server.pm.pkg.component.ParsedIntentInfo;
import com.android.server.pm.pkg.component.ParsedMainComponent;
import com.android.server.pm.pkg.component.ParsedProvider;
import com.android.server.pm.pkg.component.ParsedService;
import com.android.server.pm.pkg.parsing.PackageInfoWithoutStateUtils;
import com.android.server.pm.resolution.ComponentResolverApi;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.pm.verify.domain.DomainVerificationUtils;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedLongSparseArray;
import com.android.server.utils.WatchedSparseBooleanArray;
import com.android.server.utils.WatchedSparseIntArray;
import com.android.server.wm.ActivityTaskManagerInternal;

import libcore.util.EmptyArray;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * This class contains the implementation of the Computer functions.  It
 * is entirely self-contained - it has no implicit access to
 * PackageManagerService.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
public class ComputerEngine implements Computer {

    // TODO: Move this to its own interface implemented by the pm.Settings implementation
    protected class Settings {

        @NonNull
        private final com.android.server.pm.Settings mSettings;

        public ArrayMap<String, ? extends PackageStateInternal> getPackages() {
            return mSettings.getPackagesLocked().untrackedStorage();
        }

        public Settings(@NonNull com.android.server.pm.Settings settings) {
            mSettings = settings;
        }

        @Nullable
        public PackageStateInternal getPackage(@NonNull String packageName) {
            return mSettings.getPackageLPr(packageName);
        }

        @Nullable
        public PackageStateInternal getDisabledSystemPkg(@NonNull String packageName) {
            return mSettings.getDisabledSystemPkgLPr(packageName);
        }

        public boolean isEnabledAndMatch(ComponentInfo componentInfo, int flags, int userId) {
            PackageStateInternal pkgState = getPackage(componentInfo.packageName);
            if (pkgState == null) {
                return false;
            }

            return PackageUserStateUtils.isMatch(pkgState.getUserStateOrDefault(userId),
                    componentInfo, flags);
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public boolean isEnabledAndMatch(AndroidPackage pkg, ParsedMainComponent component,
                long flags, int userId) {
            PackageStateInternal pkgState = getPackage(component.getPackageName());
            if (pkgState == null) {
                return false;
            }

            return PackageUserStateUtils.isMatch(pkgState.getUserStateOrDefault(userId),
                    pkg.isSystem(), pkg.isEnabled(), component, flags);
        }

        @Nullable
        public CrossProfileIntentResolver getCrossProfileIntentResolver(@UserIdInt int userId) {
            return mSettings.getCrossProfileIntentResolver(userId);
        }

        // TODO: Find replacement
        @Nullable
        public SettingBase getSettingBase(int appId) {
            return mSettings.getSettingLPr(appId);
        }

        @Nullable
        public String getRenamedPackageLPr(String packageName) {
            return mSettings.getRenamedPackageLPr(packageName);
        }

        @Nullable
        public PersistentPreferredIntentResolver getPersistentPreferredActivities(
                @UserIdInt int userId) {
            return mSettings.getPersistentPreferredActivities(userId);
        }

        public void dumpVersionLPr(@NonNull IndentingPrintWriter indentingPrintWriter) {
            mSettings.dumpVersionLPr(indentingPrintWriter);
        }

        public void dumpPreferred(PrintWriter pw, DumpState dumpState, String packageName) {
            mSettings.dumpPreferred(pw, dumpState, packageName);
        }

        // TODO: Move to separate utility
        public void writePreferredActivitiesLPr(@NonNull TypedXmlSerializer serializer, int userId,
                boolean full) throws IllegalArgumentException, IllegalStateException, IOException {
            mSettings.writePreferredActivitiesLPr(serializer, userId, full);
        }

        public PreferredIntentResolver getPreferredActivities(@UserIdInt int userId) {
            return mSettings.getPreferredActivities(userId);
        }

        @Nullable
        public SharedUserSetting getSharedUserFromId(String name) {
            try {
                return mSettings.getSharedUserLPw(name, 0, 0, false /*create*/);
            } catch (PackageManagerException ignored) {
                // This is impossible do to create being false
                throw new RuntimeException(ignored);
            }
        }

        public boolean getBlockUninstall(@UserIdInt int userId, @NonNull String packageName) {
            return mSettings.getBlockUninstallLPr(userId, packageName);
        }

        @PackageManager.EnabledState
        public int getApplicationEnabledSetting(@NonNull String packageName,
                @UserIdInt int userId) throws PackageManager.NameNotFoundException {
            return mSettings.getApplicationEnabledSettingLPr(packageName, userId);
        }

        @PackageManager.EnabledState
        public int getComponentEnabledSetting(@NonNull ComponentName component,
                @UserIdInt int userId) throws PackageManager.NameNotFoundException {
            return mSettings.getComponentEnabledSettingLPr(component, userId);
        }

        @NonNull
        public KeySetManagerService getKeySetManagerService() {
            return mSettings.getKeySetManagerService();
        }

        @NonNull
        public Collection<SharedUserSetting> getAllSharedUsers() {
            return mSettings.getAllSharedUsersLPw();
        }

        @Nullable
        public SharedUserApi getSharedUserFromPackageName(String packageName) {
            return mSettings.getSharedUserSettingLPr(packageName);
        }

        @Nullable
        public SharedUserApi getSharedUserFromAppId(int sharedUserAppId) {
            return (SharedUserSetting) mSettings.getSettingLPr(sharedUserAppId);
        }

        @NonNull
        public ArraySet<PackageStateInternal> getSharedUserPackages(int sharedUserAppId) {
            final ArraySet<PackageStateInternal> res = new ArraySet<>();
            final SharedUserSetting sharedUserSetting =
                    (SharedUserSetting) mSettings.getSettingLPr(sharedUserAppId);
            if (sharedUserSetting != null) {
                final ArraySet<? extends PackageStateInternal> sharedUserPackages =
                        sharedUserSetting.getPackageStates();
                for (PackageStateInternal ps : sharedUserPackages) {
                    res.add(ps);
                }
            }
            return res;
        }

        public void dumpPackagesProto(ProtoOutputStream proto) {
            mSettings.dumpPackagesProto(proto);
        }

        public void dumpPermissions(PrintWriter pw, String packageName,
                ArraySet<String> permissionNames, DumpState dumpState) {
            mSettings.dumpPermissions(pw, packageName, permissionNames, dumpState);
        }

        public void dumpPackages(PrintWriter pw, String packageName,
                ArraySet<String> permissionNames, DumpState dumpState, boolean checkin) {
            mSettings.dumpPackagesLPr(pw, packageName, permissionNames, dumpState, checkin);
        }

        public void dumpKeySet(PrintWriter pw, String packageName, DumpState dumpState) {
            mSettings.getKeySetManagerService().dumpLPr(pw, packageName, dumpState);
        }

        public void dumpSharedUsers(PrintWriter pw, String packageName,
                ArraySet<String> permissionNames, DumpState dumpState, boolean checkin) {
            mSettings.dumpSharedUsersLPr(pw, packageName, permissionNames, dumpState, checkin);
        }

        public void dumpReadMessages(PrintWriter pw, DumpState dumpState) {
            mSettings.dumpReadMessages(pw, dumpState);
        }

        public void dumpSharedUsersProto(ProtoOutputStream proto) {
            mSettings.dumpSharedUsersProto(proto);
        }
    }

    private static final Comparator<ProviderInfo> sProviderInitOrderSorter = (p1, p2) -> {
        final int v1 = p1.initOrder;
        final int v2 = p2.initOrder;
        return (v1 > v2) ? -1 : ((v1 < v2) ? 1 : 0);
    };

    // The administrative use counter.
    private int mUsed = 0;

    // Cached attributes.  The names in this class are the same as the
    // names in PackageManagerService; see that class for documentation.
    protected final Settings mSettings;
    private final WatchedSparseIntArray mIsolatedOwners;
    private final WatchedArrayMap<String, AndroidPackage> mPackages;
    private final WatchedArrayMap<ComponentName, ParsedInstrumentation>
            mInstrumentation;
    private final SharedLibrariesRead mSharedLibraries;
    private final ComponentName mLocalResolveComponentName;
    private final ActivityInfo mResolveActivity;
    private final WatchedSparseBooleanArray mWebInstantAppsDisabled;
    private final ActivityInfo mLocalInstantAppInstallerActivity;
    private final ResolveInfo mInstantAppInstallerInfo;
    private final InstantAppRegistry mInstantAppRegistry;
    private final ApplicationInfo mLocalAndroidApplication;
    private final AppsFilterSnapshot mAppsFilter;
    private final WatchedArrayMap<String, Integer> mFrozenPackages;

    // Immutable service attribute
    private final String mAppPredictionServicePackage;

    // The following are not cloned since changes to these have never
    // been guarded by the PMS lock.
    private final Context mContext;
    private final UserManagerService mUserManager;
    private final PermissionManagerServiceInternal mPermissionManager;
    private final ApexManager mApexManager;
    private final PackageManagerServiceInjector mInjector;
    private final ComponentResolverApi mComponentResolver;
    private final InstantAppResolverConnection mInstantAppResolverConnection;
    private final DefaultAppProvider mDefaultAppProvider;
    private final DomainVerificationManagerInternal mDomainVerificationManager;
    private final PackageDexOptimizer mPackageDexOptimizer;
    private final DexManager mDexManager;
    private final CompilerStats mCompilerStats;
    private final BackgroundDexOptService mBackgroundDexOptService;
    private final PackageManagerInternal.ExternalSourcesPolicy mExternalSourcesPolicy;

    // PackageManagerService attributes that are primitives are referenced through the
    // pms object directly.  Primitives are the only attributes so referenced.
    protected final PackageManagerService mService;
    private boolean safeMode() {
        return mService.getSafeMode();
    }
    protected ComponentName resolveComponentName() {
        return mLocalResolveComponentName;
    }
    protected ActivityInfo instantAppInstallerActivity() {
        return mLocalInstantAppInstallerActivity;
    }
    protected ApplicationInfo androidApplication() {
        return mLocalAndroidApplication;
    }

    ComputerEngine(PackageManagerService.Snapshot args) {
        mSettings = new Settings(args.settings);
        mIsolatedOwners = args.isolatedOwners;
        mPackages = args.packages;
        mSharedLibraries = args.sharedLibraries;
        mInstrumentation = args.instrumentation;
        mWebInstantAppsDisabled = args.webInstantAppsDisabled;
        mLocalResolveComponentName = args.resolveComponentName;
        mResolveActivity = args.resolveActivity;
        mLocalInstantAppInstallerActivity = args.instantAppInstallerActivity;
        mInstantAppInstallerInfo = args.instantAppInstallerInfo;
        mInstantAppRegistry = args.instantAppRegistry;
        mLocalAndroidApplication = args.androidApplication;
        mAppsFilter = args.appsFilter;
        mFrozenPackages = args.frozenPackages;
        mComponentResolver = args.componentResolver;

        mAppPredictionServicePackage = args.appPredictionServicePackage;

        // The following are not cached copies.  Instead they are
        // references to outside services.
        mPermissionManager = args.service.mPermissionManager;
        mUserManager = args.service.mUserManager;
        mContext = args.service.mContext;
        mInjector = args.service.mInjector;
        mApexManager = args.service.mApexManager;
        mInstantAppResolverConnection = args.service.mInstantAppResolverConnection;
        mDefaultAppProvider = args.service.getDefaultAppProvider();
        mDomainVerificationManager = args.service.mDomainVerificationManager;
        mPackageDexOptimizer = args.service.mPackageDexOptimizer;
        mDexManager = args.service.getDexManager();
        mCompilerStats = args.service.mCompilerStats;
        mBackgroundDexOptService = args.service.mBackgroundDexOptService;
        mExternalSourcesPolicy = args.service.mExternalSourcesPolicy;

        // Used to reference PMS attributes that are primitives and which are not
        // updated under control of the PMS lock.
        mService = args.service;
    }

    /**
     * Record that the snapshot was used.
     */
    public final void use() {
        mUsed++;
    }

    /**
     * Return the usage counter.
     */
    public final int getUsed() {
        return mUsed;
    }

    public final @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            @PackageManagerInternal.PrivateResolveFlags long privateResolveFlags,
            int filterCallingUid, int userId, boolean resolveForStart,
            boolean allowDynamicSplits) {
        if (!mUserManager.exists(userId)) return Collections.emptyList();
        final String instantAppPkgName = getInstantAppPackageName(filterCallingUid);
        enforceCrossUserPermission(Binder.getCallingUid(), userId,
                false /* requireFullPermission */, false /* checkShell */,
                "query intent activities");
        final String pkgName = intent.getPackage();
        Intent originalIntent = null;
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                originalIntent = intent;
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }

        flags = updateFlagsForResolve(flags, userId, filterCallingUid, resolveForStart,
                comp != null || pkgName != null /*onlyExposedExplicitly*/,
                isImplicitImageCaptureIntentAndNotSetByDpc(intent, userId, resolvedType,
                        flags));
        List<ResolveInfo> list = Collections.emptyList();
        boolean skipPostResolution = false;
        if (comp != null) {
            final ActivityInfo ai = getActivityInfo(comp, flags, userId);
            if (ai != null) {
                // When specifying an explicit component, we prevent the activity from being
                // used when either 1) the calling package is normal and the activity is within
                // an ephemeral application or 2) the calling package is ephemeral and the
                // activity is not visible to ephemeral applications.
                final boolean matchInstantApp =
                        (flags & PackageManager.MATCH_INSTANT) != 0;
                final boolean matchVisibleToInstantAppOnly =
                        (flags & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
                final boolean matchExplicitlyVisibleOnly =
                        (flags & PackageManager.MATCH_EXPLICITLY_VISIBLE_ONLY) != 0;
                final boolean isCallerInstantApp =
                        instantAppPkgName != null;
                final boolean isTargetSameInstantApp =
                        comp.getPackageName().equals(instantAppPkgName);
                final boolean isTargetInstantApp =
                        (ai.applicationInfo.privateFlags
                                & ApplicationInfo.PRIVATE_FLAG_INSTANT) != 0;
                final boolean isTargetVisibleToInstantApp =
                        (ai.flags & ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP) != 0;
                final boolean isTargetExplicitlyVisibleToInstantApp =
                        isTargetVisibleToInstantApp
                                && (ai.flags & ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP)
                                == 0;
                final boolean isTargetHiddenFromInstantApp =
                        !isTargetVisibleToInstantApp
                                || (matchExplicitlyVisibleOnly
                                && !isTargetExplicitlyVisibleToInstantApp);
                final boolean blockInstantResolution =
                        !isTargetSameInstantApp
                                && ((!matchInstantApp && !isCallerInstantApp && isTargetInstantApp)
                                || (matchVisibleToInstantAppOnly && isCallerInstantApp
                                && isTargetHiddenFromInstantApp));
                final boolean blockNormalResolution =
                        !resolveForStart && !isTargetInstantApp && !isCallerInstantApp
                                && shouldFilterApplication(
                                getPackageStateInternal(ai.applicationInfo.packageName,
                                        Process.SYSTEM_UID), filterCallingUid, userId);
                if (!blockInstantResolution && !blockNormalResolution) {
                    final ResolveInfo ri = new ResolveInfo();
                    ri.activityInfo = ai;
                    list = new ArrayList<>(1);
                    list.add(ri);
                    PackageManagerServiceUtils.applyEnforceIntentFilterMatching(
                            mInjector.getCompatibility(), mComponentResolver,
                            list, false, intent, resolvedType, filterCallingUid);
                }
            }
        } else {
            QueryIntentActivitiesResult lockedResult =
                    queryIntentActivitiesInternalBody(
                            intent, resolvedType, flags, filterCallingUid, userId,
                            resolveForStart, allowDynamicSplits, pkgName, instantAppPkgName);
            if (lockedResult.answer != null) {
                skipPostResolution = true;
                list = lockedResult.answer;
            } else {
                if (lockedResult.addInstant) {
                    String callingPkgName = getInstantAppPackageName(filterCallingUid);
                    boolean isRequesterInstantApp = isInstantApp(callingPkgName, userId);
                    lockedResult.result = maybeAddInstantAppInstaller(
                            lockedResult.result, intent, resolvedType, flags,
                            userId, resolveForStart, isRequesterInstantApp);
                }
                if (lockedResult.sortResult) {
                    lockedResult.result.sort(RESOLVE_PRIORITY_SORTER);
                }
                list = lockedResult.result;
            }
        }

        if (originalIntent != null) {
            // We also have to ensure all components match the original intent
            PackageManagerServiceUtils.applyEnforceIntentFilterMatching(
                    mInjector.getCompatibility(), mComponentResolver,
                    list, false, originalIntent, resolvedType, filterCallingUid);
        }

        return skipPostResolution ? list : applyPostResolutionFilter(
                list, instantAppPkgName, allowDynamicSplits, filterCallingUid,
                resolveForStart, userId, intent);
    }

    public final @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        return queryIntentActivitiesInternal(
                intent, resolvedType, flags, 0 /*privateResolveFlags*/, Binder.getCallingUid(),
                userId, false /*resolveForStart*/, true /*allowDynamicSplits*/);
    }

    public final @NonNull List<ResolveInfo> queryIntentServicesInternal(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId,
            int callingUid, boolean includeInstantApps) {
        if (!mUserManager.exists(userId)) return Collections.emptyList();
        enforceCrossUserOrProfilePermission(callingUid,
                userId,
                false /*requireFullPermission*/,
                false /*checkShell*/,
                "query intent receivers");
        final String instantAppPkgName = getInstantAppPackageName(callingUid);
        flags = updateFlagsForResolve(flags, userId, callingUid, includeInstantApps,
                false /* isImplicitImageCaptureIntentAndNotSetByDpc */);
        Intent originalIntent = null;
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                originalIntent = intent;
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        List<ResolveInfo> list = Collections.emptyList();
        if (comp != null) {
            final ServiceInfo si = getServiceInfo(comp, flags, userId);
            if (si != null) {
                // When specifying an explicit component, we prevent the service from being
                // used when either 1) the service is in an instant application and the
                // caller is not the same instant application or 2) the calling package is
                // ephemeral and the activity is not visible to ephemeral applications.
                final boolean matchInstantApp =
                        (flags & PackageManager.MATCH_INSTANT) != 0;
                final boolean matchVisibleToInstantAppOnly =
                        (flags & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
                final boolean isCallerInstantApp =
                        instantAppPkgName != null;
                final boolean isTargetSameInstantApp =
                        comp.getPackageName().equals(instantAppPkgName);
                final boolean isTargetInstantApp =
                        (si.applicationInfo.privateFlags
                                & ApplicationInfo.PRIVATE_FLAG_INSTANT) != 0;
                final boolean isTargetHiddenFromInstantApp =
                        (si.flags & ServiceInfo.FLAG_VISIBLE_TO_INSTANT_APP) == 0;
                final boolean blockInstantResolution =
                        !isTargetSameInstantApp
                                && ((!matchInstantApp && !isCallerInstantApp && isTargetInstantApp)
                                || (matchVisibleToInstantAppOnly && isCallerInstantApp
                                && isTargetHiddenFromInstantApp));

                final boolean blockNormalResolution = !isTargetInstantApp && !isCallerInstantApp
                        && shouldFilterApplication(
                        getPackageStateInternal(si.applicationInfo.packageName,
                                Process.SYSTEM_UID), callingUid, userId);
                if (!blockInstantResolution && !blockNormalResolution) {
                    final ResolveInfo ri = new ResolveInfo();
                    ri.serviceInfo = si;
                    list = new ArrayList<>(1);
                    list.add(ri);
                    PackageManagerServiceUtils.applyEnforceIntentFilterMatching(
                            mInjector.getCompatibility(), mComponentResolver,
                            list, false, intent, resolvedType, callingUid);
                }
            }
        } else {
            list = queryIntentServicesInternalBody(intent, resolvedType, flags,
                    userId, callingUid, instantAppPkgName);
        }

        if (originalIntent != null) {
            // We also have to ensure all components match the original intent
            PackageManagerServiceUtils.applyEnforceIntentFilterMatching(
                    mInjector.getCompatibility(), mComponentResolver,
                    list, false, originalIntent, resolvedType, callingUid);
        }

        return list;
    }

    protected @NonNull List<ResolveInfo> queryIntentServicesInternalBody(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId,
            int callingUid, String instantAppPkgName) {
        // reader
        String pkgName = intent.getPackage();
        if (pkgName == null) {
            final List<ResolveInfo> resolveInfos = mComponentResolver.queryServices(this, intent,
                    resolvedType, flags, userId);
            if (resolveInfos == null) {
                return Collections.emptyList();
            }
            return applyPostServiceResolutionFilter(
                    resolveInfos, instantAppPkgName, userId, callingUid);
        }
        final AndroidPackage pkg = mPackages.get(pkgName);
        if (pkg != null) {
            final List<ResolveInfo> resolveInfos = mComponentResolver.queryServices(this, intent,
                    resolvedType, flags, pkg.getServices(),
                    userId);
            if (resolveInfos == null) {
                return Collections.emptyList();
            }
            return applyPostServiceResolutionFilter(
                    resolveInfos, instantAppPkgName, userId, callingUid);
        }
        return Collections.emptyList();
    }

    public @NonNull QueryIntentActivitiesResult queryIntentActivitiesInternalBody(
            Intent intent, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            int filterCallingUid, int userId, boolean resolveForStart, boolean allowDynamicSplits,
            String pkgName, String instantAppPkgName) {
        // reader
        boolean sortResult = false;
        boolean addInstant = false;
        List<ResolveInfo> result = null;
        if (pkgName == null) {
            List<CrossProfileIntentFilter> matchingFilters =
                    getMatchingCrossProfileIntentFilters(intent, resolvedType, userId);
            // Check for results that need to skip the current profile.
            ResolveInfo skipProfileInfo  = querySkipCurrentProfileIntents(matchingFilters,
                    intent, resolvedType, flags, userId);
            if (skipProfileInfo != null) {
                List<ResolveInfo> xpResult = new ArrayList<>(1);
                xpResult.add(skipProfileInfo);
                return new QueryIntentActivitiesResult(
                        applyPostResolutionFilter(
                                filterIfNotSystemUser(xpResult, userId), instantAppPkgName,
                                allowDynamicSplits, filterCallingUid, resolveForStart, userId,
                                intent));
            }

            // Check for results in the current profile.
            result = filterIfNotSystemUser(mComponentResolver.queryActivities(this,
                    intent, resolvedType, flags, userId), userId);
            addInstant = isInstantAppResolutionAllowed(intent, result, userId,
                    false /*skipPackageCheck*/, flags);
            // Check for cross profile results.
            boolean hasNonNegativePriorityResult = hasNonNegativePriority(result);
            CrossProfileDomainInfo specificXpInfo = queryCrossProfileIntents(
                    matchingFilters, intent, resolvedType, flags, userId,
                    hasNonNegativePriorityResult);
            if (intent.hasWebURI()) {
                CrossProfileDomainInfo generalXpInfo = null;
                final UserInfo parent = getProfileParent(userId);
                if (parent != null) {
                    generalXpInfo = getCrossProfileDomainPreferredLpr(intent, resolvedType,
                            flags, userId, parent.id);
                }

                // Generalized cross profile intents take precedence over specific.
                // Note that this is the opposite of the intuitive order.
                CrossProfileDomainInfo prioritizedXpInfo =
                        generalXpInfo != null ? generalXpInfo : specificXpInfo;

                if (!addInstant) {
                    if (result.isEmpty() && prioritizedXpInfo != null) {
                        // No result in current profile, but found candidate in parent user.
                        // And we are not going to add ephemeral app, so we can return the
                        // result straight away.
                        result.add(prioritizedXpInfo.mResolveInfo);
                        return new QueryIntentActivitiesResult(
                                applyPostResolutionFilter(result, instantAppPkgName,
                                        allowDynamicSplits, filterCallingUid, resolveForStart,
                                        userId, intent));
                    } else if (result.size() <= 1 && prioritizedXpInfo == null) {
                        // No result in parent user and <= 1 result in current profile, and we
                        // are not going to add ephemeral app, so we can return the result
                        // without further processing.
                        return new QueryIntentActivitiesResult(
                                applyPostResolutionFilter(result, instantAppPkgName,
                                        allowDynamicSplits, filterCallingUid, resolveForStart,
                                        userId, intent));
                    }
                }

                // We have more than one candidate (combining results from current and parent
                // profile), so we need filtering and sorting.
                result = filterCandidatesWithDomainPreferredActivitiesLPr(
                        intent, flags, result, prioritizedXpInfo, userId);
                sortResult = true;
            } else {
                // If not web Intent, just add result to candidate set and let ResolverActivity
                // figure it out.
                if (specificXpInfo != null) {
                    result.add(specificXpInfo.mResolveInfo);
                    sortResult = true;
                }
            }
        } else {
            final PackageStateInternal setting =
                    getPackageStateInternal(pkgName, Process.SYSTEM_UID);
            result = null;
            if (setting != null && setting.getAndroidPackage() != null && (resolveForStart
                    || !shouldFilterApplication(setting, filterCallingUid, userId))) {
                result = filterIfNotSystemUser(mComponentResolver.queryActivities(this,
                        intent, resolvedType, flags, setting.getAndroidPackage().getActivities(),
                        userId), userId);
            }
            if (result == null || result.size() == 0) {
                // the caller wants to resolve for a particular package; however, there
                // were no installed results, so, try to find an ephemeral result
                addInstant = isInstantAppResolutionAllowed(intent, null /*result*/, userId,
                        true /*skipPackageCheck*/, flags);
                if (result == null) {
                    result = new ArrayList<>();
                }
            }
        }
        return new QueryIntentActivitiesResult(sortResult, addInstant, result);
    }

    /**
     * Returns the activity component that can handle install failures.
     * <p>By default, the instant application installer handles failures. However, an
     * application may want to handle failures on its own. Applications do this by
     * creating an activity with an intent filter that handles the action
     * {@link Intent#ACTION_INSTALL_FAILURE}.
     */
    private @Nullable ComponentName findInstallFailureActivity(
            String packageName, int filterCallingUid, int userId) {
        final Intent failureActivityIntent = new Intent(Intent.ACTION_INSTALL_FAILURE);
        failureActivityIntent.setPackage(packageName);
        // IMPORTANT: disallow dynamic splits to avoid an infinite loop
        final List<ResolveInfo> result = queryIntentActivitiesInternal(
                failureActivityIntent, null /*resolvedType*/, 0 /*flags*/,
                0 /*privateResolveFlags*/, filterCallingUid, userId, false /*resolveForStart*/,
                false /*allowDynamicSplits*/);
        final int numResults = result.size();
        if (numResults > 0) {
            for (int i = 0; i < numResults; i++) {
                final ResolveInfo info = result.get(i);
                if (info.activityInfo.splitName != null) {
                    continue;
                }
                return new ComponentName(packageName, info.activityInfo.name);
            }
        }
        return null;
    }

    public final ActivityInfo getActivityInfo(ComponentName component,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        return getActivityInfoInternal(component, flags, Binder.getCallingUid(), userId);
    }

    /**
     * Important: The provided filterCallingUid is used exclusively to filter out activities
     * that can be seen based on user state. It's typically the original caller uid prior
     * to clearing. Because it can only be provided by trusted code, its value can be
     * trusted and will be used as-is; unlike userId which will be validated by this method.
     */
    public final ActivityInfo getActivityInfoInternal(ComponentName component,
            @PackageManager.ResolveInfoFlagsBits long flags, int filterCallingUid, int userId) {
        if (!mUserManager.exists(userId)) return null;
        flags = updateFlagsForComponent(flags, userId);

        if (!isRecentsAccessingChildProfiles(Binder.getCallingUid(), userId)) {
            enforceCrossUserPermission(Binder.getCallingUid(), userId,
                    false /* requireFullPermission */, false /* checkShell */,
                    "get activity info");
        }

        return getActivityInfoInternalBody(component, flags, filterCallingUid, userId);
    }

    protected ActivityInfo getActivityInfoInternalBody(ComponentName component,
            @PackageManager.ResolveInfoFlagsBits long flags, int filterCallingUid, int userId) {
        ParsedActivity a = mComponentResolver.getActivity(component);

        if (DEBUG_PACKAGE_INFO) Log.v(TAG, "getActivityInfo " + component + ": " + a);

        AndroidPackage pkg = a == null ? null : mPackages.get(a.getPackageName());
        if (pkg != null && mSettings.isEnabledAndMatch(pkg, a, flags, userId)) {
            PackageStateInternal ps = mSettings.getPackage(component.getPackageName());
            if (ps == null) return null;
            if (shouldFilterApplication(
                    ps, filterCallingUid, component, TYPE_ACTIVITY, userId)) {
                return null;
            }
            return PackageInfoUtils.generateActivityInfo(pkg,
                    a, flags, ps.getUserStateOrDefault(userId), userId, ps);
        }
        if (resolveComponentName().equals(component)) {
            return PackageInfoWithoutStateUtils.generateDelegateActivityInfo(mResolveActivity,
                    flags, PackageUserStateInternal.DEFAULT, userId);
        }
        return null;
    }

    public AndroidPackage getPackage(String packageName) {
        packageName = resolveInternalPackageName(
                packageName, PackageManager.VERSION_CODE_HIGHEST);
        return mPackages.get(packageName);
    }

    public AndroidPackage getPackage(int uid) {
        final String[] packageNames = getPackagesForUidInternal(uid, Process.SYSTEM_UID);
        AndroidPackage pkg = null;
        final int numPackages = packageNames == null ? 0 : packageNames.length;
        for (int i = 0; pkg == null && i < numPackages; i++) {
            pkg = mPackages.get(packageNames[i]);
        }
        return pkg;
    }

    public final ApplicationInfo generateApplicationInfoFromSettings(String packageName,
            long flags, int filterCallingUid, int userId) {
        if (!mUserManager.exists(userId)) return null;
        PackageStateInternal ps = mSettings.getPackage(packageName);
        if (ps != null) {
            if (filterSharedLibPackage(ps, filterCallingUid, userId, flags)) {
                return null;
            }
            if (shouldFilterApplication(ps, filterCallingUid, userId)) {
                return null;
            }
            if (ps.getAndroidPackage() == null) {
                final PackageInfo pInfo = generatePackageInfo(ps, flags, userId);
                if (pInfo != null) {
                    return pInfo.applicationInfo;
                }
                return null;
            }
            ApplicationInfo ai = PackageInfoUtils.generateApplicationInfo(ps.getPkg(),
                    flags, ps.getUserStateOrDefault(userId), userId, ps);
            if (ai != null) {
                ai.packageName = resolveExternalPackageName(ps.getPkg());
            }
            return ai;
        }
        return null;
    }

    public final ApplicationInfo getApplicationInfo(String packageName,
            @PackageManager.ApplicationInfoFlagsBits long flags, int userId) {
        return getApplicationInfoInternal(packageName, flags, Binder.getCallingUid(), userId);
    }

    /**
     * Important: The provided filterCallingUid is used exclusively to filter out applications
     * that can be seen based on user state. It's typically the original caller uid prior
     * to clearing. Because it can only be provided by trusted code, its value can be
     * trusted and will be used as-is; unlike userId which will be validated by this method.
     */
    public final ApplicationInfo getApplicationInfoInternal(String packageName,
            @PackageManager.ApplicationInfoFlagsBits long flags,
            int filterCallingUid, int userId) {
        if (!mUserManager.exists(userId)) return null;
        flags = updateFlagsForApplication(flags, userId);

        if (!isRecentsAccessingChildProfiles(Binder.getCallingUid(), userId)) {
            enforceCrossUserPermission(Binder.getCallingUid(), userId,
                    false /* requireFullPermission */, false /* checkShell */,
                    "get application info");
        }

        return getApplicationInfoInternalBody(packageName, flags, filterCallingUid, userId);
    }

    protected ApplicationInfo getApplicationInfoInternalBody(String packageName,
            @PackageManager.ApplicationInfoFlagsBits long flags,
            int filterCallingUid, int userId) {
        // writer
        // Normalize package name to handle renamed packages and static libs
        packageName = resolveInternalPackageName(packageName,
                PackageManager.VERSION_CODE_HIGHEST);

        AndroidPackage p = mPackages.get(packageName);
        if (DEBUG_PACKAGE_INFO) {
            Log.v(
                    TAG, "getApplicationInfo " + packageName
                            + ": " + p);
        }
        if (p != null) {
            PackageStateInternal ps = mSettings.getPackage(packageName);
            if (ps == null) return null;
            if (filterSharedLibPackage(ps, filterCallingUid, userId, flags)) {
                return null;
            }
            if (shouldFilterApplication(ps, filterCallingUid, userId)) {
                return null;
            }
            // Note: isEnabledLP() does not apply here - always return info
            ApplicationInfo ai = PackageInfoUtils.generateApplicationInfo(
                    p, flags, ps.getUserStateOrDefault(userId), userId, ps);
            if (ai != null) {
                ai.packageName = resolveExternalPackageName(p);
            }
            return ai;
        }
        if ((flags & PackageManager.MATCH_APEX) != 0) {
            // For APKs, PackageInfo.applicationInfo is not exactly the same as ApplicationInfo
            // returned from getApplicationInfo, but for APEX packages difference shouldn't be
            // very big.
            // TODO(b/155328545): generate proper application info for APEXes as well.
            int apexFlags = ApexManager.MATCH_ACTIVE_PACKAGE;
            if ((flags & PackageManager.MATCH_SYSTEM_ONLY) != 0) {
                apexFlags = ApexManager.MATCH_FACTORY_PACKAGE;
            }
            final PackageInfo pi = mApexManager.getPackageInfo(packageName, apexFlags);
            if (pi == null) {
                return null;
            }
            return pi.applicationInfo;
        }
        if ("android".equals(packageName) || "system".equals(packageName)) {
            return androidApplication();
        }
        if ((flags & MATCH_KNOWN_PACKAGES) != 0) {
            // Already generates the external package name
            return generateApplicationInfoFromSettings(packageName,
                    flags, filterCallingUid, userId);
        }
        return null;
    }

    protected ArrayList<ResolveInfo> filterCandidatesWithDomainPreferredActivitiesLPrBody(
            Intent intent, long matchFlags, List<ResolveInfo> candidates,
            CrossProfileDomainInfo xpDomainInfo, int userId, boolean debug) {
        final ArrayList<ResolveInfo> result = new ArrayList<>();
        final ArrayList<ResolveInfo> matchAllList = new ArrayList<>();
        final ArrayList<ResolveInfo> undefinedList = new ArrayList<>();

        // Blocking instant apps is usually done in applyPostResolutionFilter, but since
        // domain verification can resolve to a single result, which can be an instant app,
        // it will then be filtered to an empty list in that method. Instead, do blocking
        // here so that instant apps can be ignored for approval filtering and a lower
        // priority result chosen instead.
        final boolean blockInstant = intent.isWebIntent() && areWebInstantAppsDisabled(userId);

        final int count = candidates.size();
        // First, try to use approved apps.
        for (int n = 0; n < count; n++) {
            ResolveInfo info = candidates.get(n);
            if (blockInstant && (info.isInstantAppAvailable
                    || isInstantAppInternal(info.activityInfo.packageName, userId,
                            Process.SYSTEM_UID))) {
                continue;
            }

            // Add to the special match all list (Browser use case)
            if (info.handleAllWebDataURI) {
                matchAllList.add(info);
            } else {
                undefinedList.add(info);
            }
        }

        // We'll want to include browser possibilities in a few cases
        boolean includeBrowser = false;

        if (!DomainVerificationUtils.isDomainVerificationIntent(intent, matchFlags)) {
            result.addAll(undefinedList);
            // Maybe add one for the other profile.
            if (xpDomainInfo != null && xpDomainInfo.mHighestApprovalLevel
                    > DomainVerificationManagerInternal.APPROVAL_LEVEL_NONE) {
                result.add(xpDomainInfo.mResolveInfo);
            }
            includeBrowser = true;
        } else {
            Pair<List<ResolveInfo>, Integer> infosAndLevel = mDomainVerificationManager
                    .filterToApprovedApp(intent, undefinedList, userId,
                            mSettings::getPackage);
            List<ResolveInfo> approvedInfos = infosAndLevel.first;
            Integer highestApproval = infosAndLevel.second;

            // If no apps are approved for the domain, resolve only to browsers
            if (approvedInfos.isEmpty()) {
                includeBrowser = true;
                if (xpDomainInfo != null && xpDomainInfo.mHighestApprovalLevel
                        > DomainVerificationManagerInternal.APPROVAL_LEVEL_NONE) {
                    result.add(xpDomainInfo.mResolveInfo);
                }
            } else {
                result.addAll(approvedInfos);

                // If the other profile has an app that's higher approval, add it
                if (xpDomainInfo != null
                        && xpDomainInfo.mHighestApprovalLevel > highestApproval) {
                    result.add(xpDomainInfo.mResolveInfo);
                }
            }
        }

        if (includeBrowser) {
            // Also add browsers (all of them or only the default one)
            if (DEBUG_DOMAIN_VERIFICATION) {
                Slog.v(TAG, "   ...including browsers in candidate set");
            }
            if ((matchFlags & MATCH_ALL) != 0) {
                result.addAll(matchAllList);
            } else {
                // Browser/generic handling case.  If there's a default browser, go straight
                // to that (but only if there is no other higher-priority match).
                final String defaultBrowserPackageName = mDefaultAppProvider.getDefaultBrowser(
                        userId);
                int maxMatchPrio = 0;
                ResolveInfo defaultBrowserMatch = null;
                final int numCandidates = matchAllList.size();
                for (int n = 0; n < numCandidates; n++) {
                    ResolveInfo info = matchAllList.get(n);
                    // track the highest overall match priority...
                    if (info.priority > maxMatchPrio) {
                        maxMatchPrio = info.priority;
                    }
                    // ...and the highest-priority default browser match
                    if (info.activityInfo.packageName.equals(defaultBrowserPackageName)) {
                        if (defaultBrowserMatch == null
                                || (defaultBrowserMatch.priority < info.priority)) {
                            if (debug) {
                                Slog.v(TAG, "Considering default browser match " + info);
                            }
                            defaultBrowserMatch = info;
                        }
                    }
                }
                if (defaultBrowserMatch != null
                        && defaultBrowserMatch.priority >= maxMatchPrio
                        && !TextUtils.isEmpty(defaultBrowserPackageName)) {
                    if (debug) {
                        Slog.v(TAG, "Default browser match " + defaultBrowserMatch);
                    }
                    result.add(defaultBrowserMatch);
                } else {
                    result.addAll(matchAllList);
                }
            }

            // If there is nothing selected, add all candidates
            if (result.size() == 0) {
                result.addAll(candidates);
            }
        }
        return result;
    }

    /**
     * Report the 'Home' activity which is currently set as "always use this one". If non is set
     * then reports the most likely home activity or null if there are more than one.
     */
    public final ComponentName getDefaultHomeActivity(int userId) {
        List<ResolveInfo> allHomeCandidates = new ArrayList<>();
        ComponentName cn = getHomeActivitiesAsUser(allHomeCandidates, userId);
        if (cn != null) {
            return cn;
        }
        // TODO: This should not happen since there should always be a default package set for
        //  ROLE_HOME in RoleManager. Continue with a warning log for now.
        Slog.w(TAG, "Default package for ROLE_HOME is not set in RoleManager");

        // Find the launcher with the highest priority and return that component if there are no
        // other home activity with the same priority.
        int lastPriority = Integer.MIN_VALUE;
        ComponentName lastComponent = null;
        final int size = allHomeCandidates.size();
        for (int i = 0; i < size; i++) {
            final ResolveInfo ri = allHomeCandidates.get(i);
            if (ri.priority > lastPriority) {
                lastComponent = ri.activityInfo.getComponentName();
                lastPriority = ri.priority;
            } else if (ri.priority == lastPriority) {
                // Two components found with same priority.
                lastComponent = null;
            }
        }
        return lastComponent;
    }

    public final ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates,
            int userId) {
        Intent intent  = getHomeIntent();
        List<ResolveInfo> resolveInfos = queryIntentActivitiesInternal(intent, null,
                PackageManager.GET_META_DATA, userId);
        allHomeCandidates.clear();
        if (resolveInfos == null) {
            return null;
        }
        allHomeCandidates.addAll(resolveInfos);

        String packageName = mDefaultAppProvider.getDefaultHome(userId);
        if (packageName == null) {
            // Role changes are not and cannot be atomic because its implementation lives inside
            // a system app, so when the home role changes, there is a window when the previous
            // role holder is removed and the new role holder is granted the preferred activity,
            // but hasn't become the role holder yet. However, this case may be easily hit
            // because the preferred activity change triggers a broadcast and receivers may try
            // to get the default home activity there. So we need to fix it for this time
            // window, and an easy workaround is to fallback to the current preferred activity.
            final int appId = UserHandle.getAppId(Binder.getCallingUid());
            final boolean filtered = appId >= Process.FIRST_APPLICATION_UID;
            PackageManagerService.FindPreferredActivityBodyResult result =
                    findPreferredActivityInternal(intent, null, 0, resolveInfos, true, false,
                            false, userId, filtered);
            ResolveInfo preferredResolveInfo =  result.mPreferredResolveInfo;
            if (preferredResolveInfo != null && preferredResolveInfo.activityInfo != null) {
                packageName = preferredResolveInfo.activityInfo.packageName;
            }
        }
        if (packageName == null) {
            return null;
        }

        int resolveInfosSize = resolveInfos.size();
        for (int i = 0; i < resolveInfosSize; i++) {
            ResolveInfo resolveInfo = resolveInfos.get(i);

            if (resolveInfo.activityInfo != null && TextUtils.equals(
                    resolveInfo.activityInfo.packageName, packageName)) {
                return new ComponentName(resolveInfo.activityInfo.packageName,
                        resolveInfo.activityInfo.name);
            }
        }
        return null;
    }

    public final CrossProfileDomainInfo getCrossProfileDomainPreferredLpr(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int sourceUserId,
            int parentUserId) {
        if (!mUserManager.hasUserRestriction(UserManager.ALLOW_PARENT_PROFILE_APP_LINKING,
                sourceUserId)) {
            return null;
        }
        List<ResolveInfo> resultTargetUser = mComponentResolver.queryActivities(this, intent,
                resolvedType, flags, parentUserId);

        if (resultTargetUser == null || resultTargetUser.isEmpty()) {
            return null;
        }
        CrossProfileDomainInfo result = null;
        int size = resultTargetUser.size();
        for (int i = 0; i < size; i++) {
            ResolveInfo riTargetUser = resultTargetUser.get(i);
            // Intent filter verification is only for filters that specify a host. So don't
            //return
            // those that handle all web uris.
            if (riTargetUser.handleAllWebDataURI) {
                continue;
            }
            String packageName = riTargetUser.activityInfo.packageName;
            PackageStateInternal ps = mSettings.getPackage(packageName);
            if (ps == null) {
                continue;
            }

            int approvalLevel = mDomainVerificationManager
                    .approvalLevelForDomain(ps, intent, flags, parentUserId);

            if (result == null) {
                result = new CrossProfileDomainInfo(createForwardingResolveInfoUnchecked(
                        new WatchedIntentFilter(), sourceUserId, parentUserId), approvalLevel);
            } else {
                result.mHighestApprovalLevel =
                        Math.max(approvalLevel, result.mHighestApprovalLevel);
            }
        }
        if (result != null && result.mHighestApprovalLevel
                <= DomainVerificationManagerInternal.APPROVAL_LEVEL_NONE) {
            return null;
        }
        return result;
    }

    public final Intent getHomeIntent() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        return intent;
    }

    public final List<CrossProfileIntentFilter> getMatchingCrossProfileIntentFilters(
            Intent intent, String resolvedType, int userId) {
        CrossProfileIntentResolver resolver = mSettings.getCrossProfileIntentResolver(userId);
        if (resolver != null) {
            return resolver.queryIntent(this, intent, resolvedType, false /*defaultOnly*/, userId);
        }
        return null;
    }

    /**
     * Filters out ephemeral activities.
     * <p>When resolving for an ephemeral app, only activities that 1) are defined in the
     * ephemeral app or 2) marked with {@code visibleToEphemeral} are returned.
     *
     * @param resolveInfos The pre-filtered list of resolved activities
     * @param ephemeralPkgName The ephemeral package name. If {@code null}, no filtering
     *          is performed.
     * @param intent
     * @return A filtered list of resolved activities.
     */
    public final List<ResolveInfo> applyPostResolutionFilter(
            @NonNull List<ResolveInfo> resolveInfos,
            String ephemeralPkgName, boolean allowDynamicSplits, int filterCallingUid,
            boolean resolveForStart, int userId, Intent intent) {
        final boolean blockInstant = intent.isWebIntent() && areWebInstantAppsDisabled(userId);
        for (int i = resolveInfos.size() - 1; i >= 0; i--) {
            final ResolveInfo info = resolveInfos.get(i);
            // remove locally resolved instant app web results when disabled
            if (info.isInstantAppAvailable && blockInstant) {
                resolveInfos.remove(i);
                continue;
            }
            // allow activities that are defined in the provided package
            if (allowDynamicSplits
                    && info.activityInfo != null
                    && info.activityInfo.splitName != null
                    && !ArrayUtils.contains(info.activityInfo.applicationInfo.splitNames,
                    info.activityInfo.splitName)) {
                if (instantAppInstallerActivity() == null) {
                    if (DEBUG_INSTALL) {
                        Slog.v(TAG, "No installer - not adding it to the ResolveInfo list");
                    }
                    resolveInfos.remove(i);
                    continue;
                }
                if (blockInstant && isInstantAppInternal(
                        info.activityInfo.packageName, userId, Process.SYSTEM_UID)) {
                    resolveInfos.remove(i);
                    continue;
                }
                // requested activity is defined in a split that hasn't been installed yet.
                // add the installer to the resolve list
                if (DEBUG_INSTALL) {
                    Slog.v(TAG, "Adding installer to the ResolveInfo list");
                }
                final ResolveInfo installerInfo = new ResolveInfo(
                        mInstantAppInstallerInfo);
                final ComponentName installFailureActivity = findInstallFailureActivity(
                        info.activityInfo.packageName,  filterCallingUid, userId);
                installerInfo.auxiliaryInfo = new AuxiliaryResolveInfo(
                        installFailureActivity,
                        info.activityInfo.packageName,
                        info.activityInfo.applicationInfo.longVersionCode,
                        info.activityInfo.splitName);
                // add a non-generic filter
                installerInfo.filter = new IntentFilter();

                // This resolve info may appear in the chooser UI, so let us make it
                // look as the one it replaces as far as the user is concerned which
                // requires loading the correct label and icon for the resolve info.
                installerInfo.resolvePackageName = info.getComponentInfo().packageName;
                installerInfo.labelRes = info.resolveLabelResId();
                installerInfo.icon = info.resolveIconResId();
                installerInfo.isInstantAppAvailable = true;
                resolveInfos.set(i, installerInfo);
                continue;
            }
            if (ephemeralPkgName == null) {
                // caller is a full app
                SettingBase callingSetting =
                        mSettings.getSettingBase(UserHandle.getAppId(filterCallingUid));
                PackageStateInternal resolvedSetting =
                        getPackageStateInternal(info.activityInfo.packageName, 0);
                if (resolveForStart
                        || !mAppsFilter.shouldFilterApplication(
                        filterCallingUid, callingSetting, resolvedSetting, userId)) {
                    continue;
                }
            } else if (ephemeralPkgName.equals(info.activityInfo.packageName)) {
                // caller is same app; don't need to apply any other filtering
                continue;
            } else if (resolveForStart
                    && (intent.isWebIntent()
                    || (intent.getFlags() & Intent.FLAG_ACTIVITY_MATCH_EXTERNAL) != 0)
                    && intent.getPackage() == null
                    && intent.getComponent() == null) {
                // ephemeral apps can launch other ephemeral apps indirectly
                continue;
            } else if (((info.activityInfo.flags & ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP)
                    != 0)
                    && !info.activityInfo.applicationInfo.isInstantApp()) {
                // allow activities that have been explicitly exposed to ephemeral apps
                continue;
            }
            resolveInfos.remove(i);
        }
        return resolveInfos;
    }

    private List<ResolveInfo> applyPostServiceResolutionFilter(List<ResolveInfo> resolveInfos,
            String instantAppPkgName, @UserIdInt int userId, int filterCallingUid) {
        for (int i = resolveInfos.size() - 1; i >= 0; i--) {
            final ResolveInfo info = resolveInfos.get(i);
            if (instantAppPkgName == null) {
                SettingBase callingSetting =
                        mSettings.getSettingBase(UserHandle.getAppId(filterCallingUid));
                PackageStateInternal resolvedSetting =
                        getPackageStateInternal(info.serviceInfo.packageName, 0);
                if (!mAppsFilter.shouldFilterApplication(
                        filterCallingUid, callingSetting, resolvedSetting, userId)) {
                    continue;
                }
            }
            final boolean isEphemeralApp = info.serviceInfo.applicationInfo.isInstantApp();
            // allow services that are defined in the provided package
            if (isEphemeralApp && instantAppPkgName.equals(info.serviceInfo.packageName)) {
                if (info.serviceInfo.splitName != null
                        && !ArrayUtils.contains(info.serviceInfo.applicationInfo.splitNames,
                        info.serviceInfo.splitName)) {
                    if (instantAppInstallerActivity() == null) {
                        if (DEBUG_INSTANT) {
                            Slog.v(TAG, "No installer - not adding it to the ResolveInfo"
                                    + "list");
                        }
                        resolveInfos.remove(i);
                        continue;
                    }
                    // requested service is defined in a split that hasn't been installed yet.
                    // add the installer to the resolve list
                    if (DEBUG_INSTANT) {
                        Slog.v(TAG, "Adding ephemeral installer to the ResolveInfo list");
                    }
                    final ResolveInfo installerInfo = new ResolveInfo(
                            mInstantAppInstallerInfo);
                    installerInfo.auxiliaryInfo = new AuxiliaryResolveInfo(
                            null /* installFailureActivity */,
                            info.serviceInfo.packageName,
                            info.serviceInfo.applicationInfo.longVersionCode,
                            info.serviceInfo.splitName);
                    // add a non-generic filter
                    installerInfo.filter = new IntentFilter();
                    // load resources from the correct package
                    installerInfo.resolvePackageName = info.getComponentInfo().packageName;
                    resolveInfos.set(i, installerInfo);
                }
                continue;
            }
            // allow services that have been explicitly exposed to ephemeral apps
            if (!isEphemeralApp
                    && ((info.serviceInfo.flags & ServiceInfo.FLAG_VISIBLE_TO_INSTANT_APP)
                    != 0)) {
                continue;
            }
            resolveInfos.remove(i);
        }
        return resolveInfos;
    }

    private List<ResolveInfo> filterCandidatesWithDomainPreferredActivitiesLPr(Intent intent,
            long matchFlags, List<ResolveInfo> candidates, CrossProfileDomainInfo xpDomainInfo,
            int userId) {
        final boolean debug = (intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0;

        if (DEBUG_PREFERRED || DEBUG_DOMAIN_VERIFICATION) {
            Slog.v(TAG, "Filtering results with preferred activities. Candidates count: "
                    + candidates.size());
        }

        final ArrayList<ResolveInfo> result =
                filterCandidatesWithDomainPreferredActivitiesLPrBody(
                        intent, matchFlags, candidates, xpDomainInfo, userId, debug);

        if (DEBUG_PREFERRED || DEBUG_DOMAIN_VERIFICATION) {
            Slog.v(TAG, "Filtered results with preferred activities. New candidates count: "
                    + result.size());
            for (ResolveInfo info : result) {
                Slog.v(TAG, "  + " + info.activityInfo);
            }
        }
        return result;
    }

    /**
     * Filter out activities with systemUserOnly flag set, when current user is not System.
     *
     * @return filtered list
     */
    private List<ResolveInfo> filterIfNotSystemUser(List<ResolveInfo> resolveInfos,
            int userId) {
        if (userId == UserHandle.USER_SYSTEM) {
            return resolveInfos;
        }

        for (int i = CollectionUtils.size(resolveInfos) - 1; i >= 0; i--) {
            ResolveInfo info = resolveInfos.get(i);
            if ((info.activityInfo.flags & ActivityInfo.FLAG_SYSTEM_USER_ONLY) != 0) {
                resolveInfos.remove(i);
            }
        }
        return resolveInfos;
    }

    private List<ResolveInfo> maybeAddInstantAppInstaller(List<ResolveInfo> result,
            Intent intent, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            int userId, boolean resolveForStart, boolean isRequesterInstantApp) {
        // first, check to see if we've got an instant app already installed
        final boolean alreadyResolvedLocally = (flags & PackageManager.MATCH_INSTANT) != 0;
        ResolveInfo localInstantApp = null;
        boolean blockResolution = false;
        if (!alreadyResolvedLocally) {
            final List<ResolveInfo> instantApps = mComponentResolver.queryActivities(this,
                    intent,
                    resolvedType,
                    flags
                            | PackageManager.GET_RESOLVED_FILTER
                            | PackageManager.MATCH_INSTANT
                            | PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY,
                    userId);
            for (int i = instantApps.size() - 1; i >= 0; --i) {
                final ResolveInfo info = instantApps.get(i);
                final String packageName = info.activityInfo.packageName;
                final PackageStateInternal ps = mSettings.getPackage(packageName);
                if (ps.getUserStateOrDefault(userId).isInstantApp()) {
                    if (PackageManagerServiceUtils.hasAnyDomainApproval(
                            mDomainVerificationManager, ps, intent, flags, userId)) {
                        if (DEBUG_INSTANT) {
                            Slog.v(TAG, "Instant app approved for intent; pkg: "
                                    + packageName);
                        }
                        localInstantApp = info;
                    } else {
                        if (DEBUG_INSTANT) {
                            Slog.v(TAG, "Instant app not approved for intent; pkg: "
                                    + packageName);
                        }
                        blockResolution = true;
                    }
                    break;
                }
            }
        }
        // no app installed, let's see if one's available
        AuxiliaryResolveInfo auxiliaryResponse = null;
        if (!blockResolution) {
            if (localInstantApp == null) {
                // we don't have an instant app locally, resolve externally
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "resolveEphemeral");
                String token = UUID.randomUUID().toString();
                InstantAppResolveInfo.InstantAppDigest digest =
                        InstantAppResolver.parseDigest(intent);
                final InstantAppRequest requestObject =
                        new InstantAppRequest(null /*responseObj*/,
                                intent /*origIntent*/, resolvedType, null /*callingPackage*/,
                                null /*callingFeatureId*/, isRequesterInstantApp, userId,
                                null /*verificationBundle*/, resolveForStart,
                                digest.getDigestPrefixSecure(), token);
                auxiliaryResponse = InstantAppResolver.doInstantAppResolutionPhaseOne(this,
                        mUserManager, mInstantAppResolverConnection, requestObject);
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            } else {
                // we have an instant application locally, but, we can't admit that since
                // callers shouldn't be able to determine prior browsing. create a placeholder
                // auxiliary response so the downstream code behaves as if there's an
                // instant application available externally. when it comes time to start
                // the instant application, we'll do the right thing.
                final ApplicationInfo ai = localInstantApp.activityInfo.applicationInfo;
                auxiliaryResponse = new AuxiliaryResolveInfo(null /* failureActivity */,
                        ai.packageName, ai.longVersionCode,
                        null /* splitName */);
            }
        }
        if (intent.isWebIntent() && auxiliaryResponse == null) {
            return result;
        }
        final PackageStateInternal ps =
                mSettings.getPackage(instantAppInstallerActivity().packageName);
        if (ps == null || !PackageUserStateUtils.isEnabled(ps.getUserStateOrDefault(userId),
                instantAppInstallerActivity(), 0)) {
            return result;
        }
        final ResolveInfo ephemeralInstaller = new ResolveInfo(mInstantAppInstallerInfo);
        ephemeralInstaller.activityInfo =
                PackageInfoWithoutStateUtils.generateDelegateActivityInfo(
                        instantAppInstallerActivity(), 0 /*flags*/,
                        ps.getUserStateOrDefault(userId), userId);
        ephemeralInstaller.match = IntentFilter.MATCH_CATEGORY_SCHEME_SPECIFIC_PART
                | IntentFilter.MATCH_ADJUSTMENT_NORMAL;
        // add a non-generic filter
        ephemeralInstaller.filter = new IntentFilter();
        if (intent.getAction() != null) {
            ephemeralInstaller.filter.addAction(intent.getAction());
        }
        if (intent.getData() != null && intent.getData().getPath() != null) {
            ephemeralInstaller.filter.addDataPath(
                    intent.getData().getPath(), PatternMatcher.PATTERN_LITERAL);
        }
        ephemeralInstaller.isInstantAppAvailable = true;
        // make sure this resolver is the default
        ephemeralInstaller.isDefault = true;
        ephemeralInstaller.auxiliaryInfo = auxiliaryResponse;
        if (DEBUG_INSTANT) {
            Slog.v(TAG, "Adding ephemeral installer to the ResolveInfo list");
        }

        result.add(ephemeralInstaller);
        return result;
    }

    public final PackageInfo generatePackageInfo(PackageStateInternal ps,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        if (!mUserManager.exists(userId)) return null;
        if (ps == null) {
            return null;
        }
        final int callingUid = Binder.getCallingUid();
        // Filter out ephemeral app metadata:
        //   * The system/shell/root can see metadata for any app
        //   * An installed app can see metadata for 1) other installed apps
        //     and 2) ephemeral apps that have explicitly interacted with it
        //   * Ephemeral apps can only see their own data and exposed installed apps
        //   * Holding a signature permission allows seeing instant apps
        if (shouldFilterApplication(ps, callingUid, userId)) {
            return null;
        }

        if ((flags & MATCH_UNINSTALLED_PACKAGES) != 0
                && ps.isSystem()) {
            flags |= MATCH_ANY_USER;
        }

        final PackageUserStateInternal state = ps.getUserStateOrDefault(userId);
        AndroidPackage p = ps.getPkg();
        if (p != null) {
            // Compute GIDs only if requested
            final int[] gids = (flags & PackageManager.GET_GIDS) == 0 ? EMPTY_INT_ARRAY
                    : mPermissionManager.getGidsForUid(UserHandle.getUid(userId, ps.getAppId()));
            // Compute granted permissions only if package has requested permissions
            final Set<String> permissions = ((flags & PackageManager.GET_PERMISSIONS) == 0
                    || ArrayUtils.isEmpty(p.getRequestedPermissions())) ? Collections.emptySet()
                    : mPermissionManager.getGrantedPermissions(ps.getPackageName(), userId);

            PackageInfo packageInfo = PackageInfoUtils.generate(p, gids, flags,
                    state.getFirstInstallTime(), ps.getLastUpdateTime(), permissions, state, userId,
                    ps);

            if (packageInfo == null) {
                return null;
            }

            packageInfo.packageName = packageInfo.applicationInfo.packageName =
                    resolveExternalPackageName(p);

            return packageInfo;
        } else if ((flags & MATCH_UNINSTALLED_PACKAGES) != 0
                && PackageUserStateUtils.isAvailable(state, flags)) {
            PackageInfo pi = new PackageInfo();
            pi.packageName = ps.getPackageName();
            pi.setLongVersionCode(ps.getVersionCode());
            SharedUserApi sharedUser = mSettings.getSharedUserFromPackageName(pi.packageName);
            pi.sharedUserId = (sharedUser != null) ? sharedUser.getName() : null;
            pi.firstInstallTime = state.getFirstInstallTime();
            pi.lastUpdateTime = ps.getLastUpdateTime();

            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = ps.getPackageName();
            ai.uid = UserHandle.getUid(userId, ps.getAppId());
            ai.primaryCpuAbi = ps.getPrimaryCpuAbi();
            ai.secondaryCpuAbi = ps.getSecondaryCpuAbi();
            ai.setVersionCode(ps.getVersionCode());
            ai.flags = ps.getFlags();
            ai.privateFlags = ps.getPrivateFlags();
            pi.applicationInfo = PackageInfoWithoutStateUtils.generateDelegateApplicationInfo(
                    ai, flags, state, userId);

            if (DEBUG_PACKAGE_INFO) {
                Log.v(TAG, "ps.pkg is n/a for ["
                        + ps.getPackageName() + "]. Provides a minimum info.");
            }
            return pi;
        } else {
            return null;
        }
    }

    public final PackageInfo getPackageInfo(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        return getPackageInfoInternal(packageName, PackageManager.VERSION_CODE_HIGHEST,
                flags, Binder.getCallingUid(), userId);
    }

    /**
     * Important: The provided filterCallingUid is used exclusively to filter out packages
     * that can be seen based on user state. It's typically the original caller uid prior
     * to clearing. Because it can only be provided by trusted code, its value can be
     * trusted and will be used as-is; unlike userId which will be validated by this method.
     */
    public final PackageInfo getPackageInfoInternal(String packageName, long versionCode,
            long flags, int filterCallingUid, int userId) {
        if (!mUserManager.exists(userId)) return null;
        flags = updateFlagsForPackage(flags, userId);
        enforceCrossUserPermission(Binder.getCallingUid(), userId,
                false /* requireFullPermission */, false /* checkShell */, "get package info");

        return getPackageInfoInternalBody(packageName, versionCode, flags, filterCallingUid,
                userId);
    }

    protected PackageInfo getPackageInfoInternalBody(String packageName, long versionCode,
            long flags, int filterCallingUid, int userId) {
        // reader
        // Normalize package name to handle renamed packages and static libs
        packageName = resolveInternalPackageName(packageName, versionCode);

        final boolean matchFactoryOnly = (flags & MATCH_FACTORY_ONLY) != 0;
        if (matchFactoryOnly) {
            // Instant app filtering for APEX modules is ignored
            if ((flags & MATCH_APEX) != 0) {
                return mApexManager.getPackageInfo(packageName,
                        ApexManager.MATCH_FACTORY_PACKAGE);
            }
            final PackageStateInternal ps = mSettings.getDisabledSystemPkg(packageName);
            if (ps != null) {
                if (filterSharedLibPackage(ps, filterCallingUid, userId, flags)) {
                    return null;
                }
                if (shouldFilterApplication(ps, filterCallingUid, userId)) {
                    return null;
                }
                return generatePackageInfo(ps, flags, userId);
            }
        }

        AndroidPackage p = mPackages.get(packageName);
        if (matchFactoryOnly && p != null && !p.isSystem()) {
            return null;
        }
        if (DEBUG_PACKAGE_INFO) {
            Log.v(TAG, "getPackageInfo " + packageName + ": " + p);
        }
        if (p != null) {
            final PackageStateInternal ps = getPackageStateInternal(p.getPackageName());
            if (filterSharedLibPackage(ps, filterCallingUid, userId, flags)) {
                return null;
            }
            if (ps != null && shouldFilterApplication(ps, filterCallingUid, userId)) {
                return null;
            }

            return generatePackageInfo(ps, flags, userId);
        }
        if (!matchFactoryOnly && (flags & MATCH_KNOWN_PACKAGES) != 0) {
            final PackageStateInternal ps = mSettings.getPackage(packageName);
            if (ps == null) return null;
            if (filterSharedLibPackage(ps, filterCallingUid, userId, flags)) {
                return null;
            }
            if (shouldFilterApplication(ps, filterCallingUid, userId)) {
                return null;
            }
            return generatePackageInfo(ps, flags, userId);
        }
        if ((flags & MATCH_APEX) != 0) {
            return mApexManager.getPackageInfo(packageName, ApexManager.MATCH_ACTIVE_PACKAGE);
        }
        return null;
    }

    @Override
    public String[] getAllAvailablePackageNames() {
        return mPackages.keySet().toArray(new String[0]);
    }

    @Nullable
    public final PackageStateInternal getPackageStateInternal(String packageName) {
        return getPackageStateInternal(packageName, Binder.getCallingUid());
    }

    public PackageStateInternal getPackageStateInternal(String packageName,
            int callingUid) {
        packageName = resolveInternalPackageNameInternalLocked(
                packageName, PackageManager.VERSION_CODE_HIGHEST, callingUid);
        return mSettings.getPackage(packageName);
    }

    public final ParceledListSlice<PackageInfo> getInstalledPackages(long flags, int userId) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return ParceledListSlice.emptyList();
        }
        if (!mUserManager.exists(userId)) return ParceledListSlice.emptyList();
        flags = updateFlagsForPackage(flags, userId);

        enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                false /* checkShell */, "get installed packages");

        return getInstalledPackagesBody(flags, userId, callingUid);
    }

    protected ParceledListSlice<PackageInfo> getInstalledPackagesBody(long flags, int userId,
            int callingUid) {
        // writer
        final boolean listUninstalled = (flags & MATCH_KNOWN_PACKAGES) != 0;
        final boolean listApex = (flags & MATCH_APEX) != 0;
        final boolean listFactory = (flags & MATCH_FACTORY_ONLY) != 0;

        ArrayList<PackageInfo> list;
        if (listUninstalled) {
            list = new ArrayList<>(mSettings.getPackages().size());
            for (PackageStateInternal ps : mSettings.getPackages().values()) {
                if (listFactory) {
                    if (!ps.isSystem()) {
                        continue;
                    }
                    PackageStateInternal psDisabled =
                            mSettings.getDisabledSystemPkg(ps.getPackageName());
                    if (psDisabled != null) {
                        ps = psDisabled;
                    }
                }
                if (filterSharedLibPackage(ps, callingUid, userId, flags)) {
                    continue;
                }
                if (shouldFilterApplication(ps, callingUid, userId)) {
                    continue;
                }
                final PackageInfo pi = generatePackageInfo(ps, flags, userId);
                if (pi != null) {
                    list.add(pi);
                }
            }
        } else {
            list = new ArrayList<>(mPackages.size());
            for (AndroidPackage p : mPackages.values()) {
                PackageStateInternal ps = getPackageStateInternal(p.getPackageName());
                if (listFactory) {
                    if (!p.isSystem()) {
                        continue;
                    }
                    PackageStateInternal psDisabled =
                            ps == null ? null : mSettings.getDisabledSystemPkg(ps.getPackageName());
                    if (psDisabled != null) {
                        ps = psDisabled;
                    }
                }
                if (filterSharedLibPackage(ps, callingUid, userId, flags)) {
                    continue;
                }
                if (shouldFilterApplication(ps, callingUid, userId)) {
                    continue;
                }
                final PackageInfo pi = generatePackageInfo(ps, flags, userId);
                if (pi != null) {
                    list.add(pi);
                }
            }
        }
        if (listApex) {
            if (listFactory) {
                list.addAll(mApexManager.getFactoryPackages());
            } else {
                list.addAll(mApexManager.getActivePackages());
            }
        }
        return new ParceledListSlice<>(list);
    }

    /**
     * If the filter's target user can handle the intent and is enabled: a [ResolveInfo] that
     * will forward the intent to the filter's target user, along with the highest approval of
     * any handler in the target user. Otherwise, returns null.
     */
    @Nullable
    private CrossProfileDomainInfo createForwardingResolveInfo(
            @NonNull CrossProfileIntentFilter filter, @NonNull Intent intent,
            @Nullable String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            int sourceUserId) {
        int targetUserId = filter.getTargetUserId();
        if (!isUserEnabled(targetUserId)) {
            return null;
        }

        List<ResolveInfo> resultTargetUser = mComponentResolver.queryActivities(this, intent,
                resolvedType, flags, targetUserId);
        if (CollectionUtils.isEmpty(resultTargetUser)) {
            return null;
        }

        ResolveInfo forwardingInfo = null;
        for (int i = resultTargetUser.size() - 1; i >= 0; i--) {
            ResolveInfo targetUserResolveInfo = resultTargetUser.get(i);
            if ((targetUserResolveInfo.activityInfo.applicationInfo.flags
                    & ApplicationInfo.FLAG_SUSPENDED) == 0) {
                forwardingInfo = createForwardingResolveInfoUnchecked(filter, sourceUserId,
                        targetUserId);
                break;
            }
        }

        if (forwardingInfo == null) {
            // If all the matches in the target profile are suspended, return null.
            return null;
        }

        int highestApprovalLevel = DomainVerificationManagerInternal.APPROVAL_LEVEL_NONE;

        int size = resultTargetUser.size();
        for (int i = 0; i < size; i++) {
            ResolveInfo riTargetUser = resultTargetUser.get(i);
            if (riTargetUser.handleAllWebDataURI) {
                continue;
            }
            String packageName = riTargetUser.activityInfo.packageName;
            PackageStateInternal ps = mSettings.getPackage(packageName);
            if (ps == null) {
                continue;
            }
            highestApprovalLevel = Math.max(highestApprovalLevel, mDomainVerificationManager
                    .approvalLevelForDomain(ps, intent, flags, targetUserId));
        }

        return new CrossProfileDomainInfo(forwardingInfo, highestApprovalLevel);
    }

    public final ResolveInfo createForwardingResolveInfoUnchecked(WatchedIntentFilter filter,
            int sourceUserId, int targetUserId) {
        ResolveInfo forwardingResolveInfo = new ResolveInfo();
        final long ident = Binder.clearCallingIdentity();
        boolean targetIsProfile;
        try {
            targetIsProfile = mUserManager.getUserInfo(targetUserId).isManagedProfile();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        String className;
        if (targetIsProfile) {
            className = FORWARD_INTENT_TO_MANAGED_PROFILE;
        } else {
            className = FORWARD_INTENT_TO_PARENT;
        }
        ComponentName forwardingActivityComponentName = new ComponentName(
                androidApplication().packageName, className);
        ActivityInfo forwardingActivityInfo =
                getActivityInfo(forwardingActivityComponentName, 0,
                        sourceUserId);
        if (!targetIsProfile) {
            forwardingActivityInfo.showUserIcon = targetUserId;
            forwardingResolveInfo.noResourceId = true;
        }
        forwardingResolveInfo.activityInfo = forwardingActivityInfo;
        forwardingResolveInfo.priority = 0;
        forwardingResolveInfo.preferredOrder = 0;
        forwardingResolveInfo.match = 0;
        forwardingResolveInfo.isDefault = true;
        forwardingResolveInfo.filter = new IntentFilter(filter.getIntentFilter());
        forwardingResolveInfo.targetUserId = targetUserId;
        return forwardingResolveInfo;
    }

    // Return matching ResolveInfo in target user if any.
    @Nullable
    private CrossProfileDomainInfo queryCrossProfileIntents(
            List<CrossProfileIntentFilter> matchingFilters, Intent intent, String resolvedType,
            long flags, int sourceUserId, boolean matchInCurrentProfile) {
        if (matchingFilters == null) {
            return null;
        }
        // Two {@link CrossProfileIntentFilter}s can have the same targetUserId and
        // match the same intent. For performance reasons, it is better not to
        // run queryIntent twice for the same userId
        SparseBooleanArray alreadyTriedUserIds = new SparseBooleanArray();

        CrossProfileDomainInfo resultInfo = null;

        int size = matchingFilters.size();
        for (int i = 0; i < size; i++) {
            CrossProfileIntentFilter filter = matchingFilters.get(i);
            int targetUserId = filter.getTargetUserId();
            boolean skipCurrentProfile =
                    (filter.getFlags() & PackageManager.SKIP_CURRENT_PROFILE) != 0;
            boolean skipCurrentProfileIfNoMatchFound =
                    (filter.getFlags() & PackageManager.ONLY_IF_NO_MATCH_FOUND) != 0;
            if (!skipCurrentProfile && !alreadyTriedUserIds.get(targetUserId)
                    && (!skipCurrentProfileIfNoMatchFound || !matchInCurrentProfile)) {
                // Checking if there are activities in the target user that can handle the
                // intent.
                CrossProfileDomainInfo info = createForwardingResolveInfo(filter, intent,
                        resolvedType, flags, sourceUserId);
                if (info != null) {
                    resultInfo = info;
                    break;
                }
                alreadyTriedUserIds.put(targetUserId, true);
            }
        }

        if (resultInfo == null) {
            return null;
        }

        ResolveInfo forwardingResolveInfo = resultInfo.mResolveInfo;
        if (!isUserEnabled(forwardingResolveInfo.targetUserId)) {
            return null;
        }

        List<ResolveInfo> filteredResult =
                filterIfNotSystemUser(Collections.singletonList(forwardingResolveInfo),
                        sourceUserId);
        if (filteredResult.isEmpty()) {
            return null;
        }

        return resultInfo;
    }

    private ResolveInfo querySkipCurrentProfileIntents(
            List<CrossProfileIntentFilter> matchingFilters, Intent intent, String resolvedType,
            long flags, int sourceUserId) {
        if (matchingFilters != null) {
            int size = matchingFilters.size();
            for (int i = 0; i < size; i++) {
                CrossProfileIntentFilter filter = matchingFilters.get(i);
                if ((filter.getFlags() & PackageManager.SKIP_CURRENT_PROFILE) != 0) {
                    // Checking if there are activities in the target user that can handle the
                    // intent.
                    CrossProfileDomainInfo info = createForwardingResolveInfo(filter, intent,
                            resolvedType, flags, sourceUserId);
                    if (info != null) {
                        return info.mResolveInfo;
                    }
                }
            }
        }
        return null;
    }

    public final ServiceInfo getServiceInfo(ComponentName component,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        if (!mUserManager.exists(userId)) return null;
        final int callingUid = Binder.getCallingUid();
        flags = updateFlagsForComponent(flags, userId);
        enforceCrossUserOrProfilePermission(callingUid, userId,
                false /* requireFullPermission */,
                false /* checkShell */, "get service info");
        return getServiceInfoBody(component, flags, userId, callingUid);
    }

    protected ServiceInfo getServiceInfoBody(ComponentName component,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId, int callingUid) {
        ParsedService s = mComponentResolver.getService(component);
        if (DEBUG_PACKAGE_INFO) {
            Log.v(
                    TAG, "getServiceInfo " + component + ": " + s);
        }
        if (s == null) {
            return null;
        }

        AndroidPackage pkg = mPackages.get(s.getPackageName());
        if (mSettings.isEnabledAndMatch(pkg, s, flags, userId)) {
            PackageStateInternal ps = mSettings.getPackage(component.getPackageName());
            if (ps == null) return null;
            if (shouldFilterApplication(
                    ps, callingUid, component, TYPE_SERVICE, userId)) {
                return null;
            }
            return PackageInfoUtils.generateServiceInfo(pkg,
                    s, flags, ps.getUserStateOrDefault(userId), userId, ps);
        }
        return null;
    }

    @Nullable
    public final SharedLibraryInfo getSharedLibraryInfo(String name, long version) {
        return mSharedLibraries.getSharedLibraryInfo(name, version);
    }

    /**
     * Returns the package name of the calling Uid if it's an instant app. If it isn't
     * instant, returns {@code null}.
     */
    public String getInstantAppPackageName(int callingUid) {
        // If the caller is an isolated app use the owner's uid for the lookup.
        if (Process.isIsolated(callingUid)) {
            callingUid = getIsolatedOwner(callingUid);
        }
        final int appId = UserHandle.getAppId(callingUid);
        final Object obj = mSettings.getSettingBase(appId);
        if (obj instanceof PackageStateInternal) {
            final PackageStateInternal ps = (PackageStateInternal) obj;
            final boolean isInstantApp = ps.getUserStateOrDefault(UserHandle.getUserId(callingUid))
                    .isInstantApp();
            return isInstantApp ? ps.getPkg().getPackageName() : null;
        }
        return null;
    }

    /**
     * Finds the owner for the provided isolated UID. Throws IllegalStateException if no such
     * isolated UID is found.
     */
    private int getIsolatedOwner(int isolatedUid) {
        final int ownerUid = mIsolatedOwners.get(isolatedUid, -1);
        if (ownerUid == -1) {
            throw new IllegalStateException(
                    "No owner UID found for isolated UID " + isolatedUid);
        }
        return ownerUid;
    }

    public final String resolveExternalPackageName(AndroidPackage pkg) {
        if (pkg.getStaticSharedLibName() != null) {
            return pkg.getManifestPackageName();
        }
        return pkg.getPackageName();
    }

    private String resolveInternalPackageNameInternalLocked(
            String packageName, long versionCode, int callingUid) {
        // Handle renamed packages
        String normalizedPackageName = mSettings.getRenamedPackageLPr(packageName);
        packageName = normalizedPackageName != null ? normalizedPackageName : packageName;

        // Is this a static library?
        WatchedLongSparseArray<SharedLibraryInfo> versionedLib =
                mSharedLibraries.getStaticLibraryInfos(packageName);
        if (versionedLib == null || versionedLib.size() <= 0) {
            return packageName;
        }

        // Figure out which lib versions the caller can see
        LongSparseLongArray versionsCallerCanSee = null;
        final int callingAppId = UserHandle.getAppId(callingUid);
        if (callingAppId != Process.SYSTEM_UID && callingAppId != Process.SHELL_UID
                && callingAppId != Process.ROOT_UID) {
            versionsCallerCanSee = new LongSparseLongArray();
            String libName = versionedLib.valueAt(0).getName();
            String[] uidPackages = getPackagesForUidInternal(callingUid, callingUid);
            if (uidPackages != null) {
                for (String uidPackage : uidPackages) {
                    PackageStateInternal ps = mSettings.getPackage(uidPackage);
                    final int libIdx = ArrayUtils.indexOf(ps.getUsesStaticLibraries(), libName);
                    if (libIdx >= 0) {
                        final long libVersion = ps.getUsesStaticLibrariesVersions()[libIdx];
                        versionsCallerCanSee.append(libVersion, libVersion);
                    }
                }
            }
        }

        // Caller can see nothing - done
        if (versionsCallerCanSee != null && versionsCallerCanSee.size() <= 0) {
            return packageName;
        }

        // Find the version the caller can see and the app version code
        SharedLibraryInfo highestVersion = null;
        final int versionCount = versionedLib.size();
        for (int i = 0; i < versionCount; i++) {
            SharedLibraryInfo libraryInfo = versionedLib.valueAt(i);
            if (versionsCallerCanSee != null && versionsCallerCanSee.indexOfKey(
                    libraryInfo.getLongVersion()) < 0) {
                continue;
            }
            final long libVersionCode = libraryInfo.getDeclaringPackage().getLongVersionCode();
            if (versionCode != PackageManager.VERSION_CODE_HIGHEST) {
                if (libVersionCode == versionCode) {
                    return libraryInfo.getPackageName();
                }
            } else if (highestVersion == null) {
                highestVersion = libraryInfo;
            } else if (libVersionCode  > highestVersion
                    .getDeclaringPackage().getLongVersionCode()) {
                highestVersion = libraryInfo;
            }
        }

        if (highestVersion != null) {
            return highestVersion.getPackageName();
        }

        return packageName;
    }

    public final String resolveInternalPackageName(String packageName, long versionCode) {
        final int callingUid = Binder.getCallingUid();
        return resolveInternalPackageNameInternalLocked(packageName, versionCode,
                callingUid);
    }

    /**
     * <em>IMPORTANT:</em> Not all packages returned by this method may be known
     * to the system. There are two conditions in which this may occur:
     * <ol>
     *   <li>The package is on adoptable storage and the device has been removed</li>
     *   <li>The package is being removed and the internal structures are partially updated</li>
     * </ol>
     * The second is an artifact of the current data structures and should be fixed. See
     * b/111075456 for one such instance.
     * This binder API is cached.  If the algorithm in this method changes,
     * or if the underlying objecs (as returned by getSettingLPr()) change
     * then the logic that invalidates the cache must be revisited.  See
     * calls to invalidateGetPackagesForUidCache() to locate the points at
     * which the cache is invalidated.
     */
    public final String[] getPackagesForUid(int uid) {
        return getPackagesForUidInternal(uid, Binder.getCallingUid());
    }

    private String[] getPackagesForUidInternal(int uid, int callingUid) {
        final boolean isCallerInstantApp = getInstantAppPackageName(callingUid) != null;
        final int userId = UserHandle.getUserId(uid);
        if (Process.isSdkSandboxUid(uid)) {
            uid = getBaseSdkSandboxUid();
        }
        final int appId = UserHandle.getAppId(uid);
        return getPackagesForUidInternalBody(callingUid, userId, appId, isCallerInstantApp);
    }

    protected String[] getPackagesForUidInternalBody(int callingUid, int userId, int appId,
            boolean isCallerInstantApp) {
        // reader
        final Object obj = mSettings.getSettingBase(appId);
        if (obj instanceof SharedUserSetting) {
            if (isCallerInstantApp) {
                return null;
            }
            final SharedUserSetting sus = (SharedUserSetting) obj;
            final ArraySet<PackageStateInternal> packageStates =
                    (ArraySet<PackageStateInternal>) sus.getPackageStates();
            final int n = packageStates.size();
            String[] res = new String[n];
            int i = 0;
            for (int index = 0; index < n; index++) {
                final PackageStateInternal ps = packageStates.valueAt(index);
                if (ps.getUserStateOrDefault(userId).isInstalled()
                        && !shouldFilterApplication(ps, callingUid, userId)) {
                    res[i++] = ps.getPackageName();
                }
            }
            return ArrayUtils.trimToSize(res, i);
        } else if (obj instanceof PackageStateInternal) {
            final PackageStateInternal ps = (PackageStateInternal) obj;
            if (ps.getUserStateOrDefault(userId).isInstalled()
                    && !shouldFilterApplication(ps, callingUid, userId)) {
                return new String[]{ps.getPackageName()};
            }
        }
        return null;
    }

    public final UserInfo getProfileParent(int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            return mUserManager.getProfileParent(userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Returns whether or not instant apps have been disabled remotely.
     */
    private boolean areWebInstantAppsDisabled(int userId) {
        return mWebInstantAppsDisabled.get(userId);
    }

    /**
     * Returns whether or not a full application can see an instant application.
     * <p>
     * Currently, there are four cases in which this can occur:
     * <ol>
     * <li>The calling application is a "special" process. Special processes
     *     are those with a UID < {@link Process#FIRST_APPLICATION_UID}.</li>
     * <li>The calling application has the permission
     *     {@link android.Manifest.permission#ACCESS_INSTANT_APPS}.</li>
     * <li>The calling application is the default launcher on the
     *     system partition.</li>
     * <li>The calling application is the default app prediction service.</li>
     * </ol>
     */
    public final boolean canViewInstantApps(int callingUid, int userId) {
        if (callingUid < Process.FIRST_APPLICATION_UID) {
            return true;
        }
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_INSTANT_APPS) == PERMISSION_GRANTED) {
            return true;
        }
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.VIEW_INSTANT_APPS) == PERMISSION_GRANTED) {
            final ComponentName homeComponent = getDefaultHomeActivity(userId);
            if (homeComponent != null
                    && isCallerSameApp(homeComponent.getPackageName(), callingUid)) {
                return true;
            }
            // TODO(b/122900055) Change/Remove this and replace with new permission role.
            return mAppPredictionServicePackage != null
                    && isCallerSameApp(mAppPredictionServicePackage, callingUid);
        }
        return false;
    }

    private boolean filterStaticSharedLibPackage(@Nullable PackageStateInternal ps, int uid,
            int userId, @PackageManager.ComponentInfoFlagsBits long flags) {
        // Callers can access only the static shared libs they depend on, otherwise they need to
        // explicitly ask for the static shared libraries given the caller is allowed to access
        // all static libs.
        if ((flags & PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES) != 0) {
            // System/shell/root get to see all static libs
            final int appId = UserHandle.getAppId(uid);
            if (appId == Process.SYSTEM_UID || appId == Process.SHELL_UID
                    || appId == Process.ROOT_UID) {
                return false;
            }
            // Installer gets to see all static libs.
            if (PackageManager.PERMISSION_GRANTED
                    == checkUidPermission(Manifest.permission.INSTALL_PACKAGES, uid)) {
                return false;
            }
        }

        // No package means no static lib as it is always on internal storage
        if (ps == null || ps.getPkg() == null || !ps.getPkg().isStaticSharedLibrary()) {
            return false;
        }

        final SharedLibraryInfo libraryInfo = getSharedLibraryInfo(
                ps.getPkg().getStaticSharedLibName(), ps.getPkg().getStaticSharedLibVersion());
        if (libraryInfo == null) {
            return false;
        }

        final int resolvedUid = UserHandle.getUid(userId, UserHandle.getAppId(uid));
        final String[] uidPackageNames = getPackagesForUid(resolvedUid);
        if (uidPackageNames == null) {
            return true;
        }

        for (String uidPackageName : uidPackageNames) {
            if (ps.getPackageName().equals(uidPackageName)) {
                return false;
            }
            PackageStateInternal uidPs = mSettings.getPackage(uidPackageName);
            if (uidPs != null) {
                final int index = ArrayUtils.indexOf(uidPs.getUsesStaticLibraries(),
                        libraryInfo.getName());
                if (index < 0) {
                    continue;
                }
                if (uidPs.getPkg().getUsesStaticLibrariesVersions()[index]
                        == libraryInfo.getLongVersion()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean filterSdkLibPackage(@Nullable PackageStateInternal ps, int uid,
            int userId, @PackageManager.ComponentInfoFlagsBits long flags) {
        // Callers can access only the SDK libs they depend on, otherwise they need to
        // explicitly ask for the SDKs given the caller is allowed to access
        // all shared libs.
        if ((flags & PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES) != 0) {
            // System/shell/root get to see all SDK libs.
            final int appId = UserHandle.getAppId(uid);
            if (appId == Process.SYSTEM_UID || appId == Process.SHELL_UID
                    || appId == Process.ROOT_UID) {
                return false;
            }
            // Installer gets to see all SDK libs.
            if (PackageManager.PERMISSION_GRANTED
                    == checkUidPermission(Manifest.permission.INSTALL_PACKAGES, uid)) {
                return false;
            }
        }

        // No package means no static lib as it is always on internal storage
        if (ps == null || ps.getPkg() == null || !ps.getPkg().isSdkLibrary()) {
            return false;
        }

        final SharedLibraryInfo libraryInfo = getSharedLibraryInfo(
                ps.getPkg().getSdkLibName(), ps.getPkg().getSdkLibVersionMajor());
        if (libraryInfo == null) {
            return false;
        }

        final int resolvedUid = UserHandle.getUid(userId, UserHandle.getAppId(uid));
        final String[] uidPackageNames = getPackagesForUid(resolvedUid);
        if (uidPackageNames == null) {
            return true;
        }

        for (String uidPackageName : uidPackageNames) {
            if (ps.getPackageName().equals(uidPackageName)) {
                return false;
            }
            PackageStateInternal uidPs = mSettings.getPackage(uidPackageName);
            if (uidPs != null) {
                final int index = ArrayUtils.indexOf(uidPs.getUsesSdkLibraries(),
                        libraryInfo.getName());
                if (index < 0) {
                    continue;
                }
                if (uidPs.getPkg().getUsesSdkLibrariesVersionsMajor()[index]
                        == libraryInfo.getLongVersion()) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public final boolean filterSharedLibPackage(@Nullable PackageStateInternal ps, int uid,
            int userId, @PackageManager.ComponentInfoFlagsBits long flags) {
        return filterStaticSharedLibPackage(ps, uid, userId, flags) || filterSdkLibPackage(ps, uid,
                userId, flags);
    }

    private boolean hasCrossUserPermission(
            int callingUid, int callingUserId, int userId, boolean requireFullPermission,
            boolean requirePermissionWhenSameUser) {
        if (!requirePermissionWhenSameUser && userId == callingUserId) {
            return true;
        }
        if (callingUid == Process.SYSTEM_UID || callingUid == Process.ROOT_UID) {
            return true;
        }
        if (requireFullPermission) {
            return hasPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        }
        return hasPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                || hasPermission(Manifest.permission.INTERACT_ACROSS_USERS);
    }

    /**
     * @param resolveInfos list of resolve infos in descending priority order
     * @return if the list contains a resolve info with non-negative priority
     */
    private boolean hasNonNegativePriority(List<ResolveInfo> resolveInfos) {
        return resolveInfos.size() > 0 && resolveInfos.get(0).priority >= 0;
    }

    private boolean hasPermission(String permission) {
        return mContext.checkCallingOrSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    public final boolean isCallerSameApp(String packageName, int uid) {
        if (Process.isSdkSandboxUid(uid)) {
            return (packageName != null
                    && packageName.equals(mService.getSdkSandboxPackageName()));
        }
        AndroidPackage pkg = mPackages.get(packageName);
        return pkg != null
                && UserHandle.getAppId(uid) == pkg.getUid();
    }

    public final boolean isComponentVisibleToInstantApp(@Nullable ComponentName component) {
        if (isComponentVisibleToInstantApp(component, TYPE_ACTIVITY)) {
            return true;
        }
        if (isComponentVisibleToInstantApp(component, TYPE_SERVICE)) {
            return true;
        }
        return isComponentVisibleToInstantApp(component, TYPE_PROVIDER);
    }

    public final boolean isComponentVisibleToInstantApp(
            @Nullable ComponentName component, @PackageManager.ComponentType int type) {
        if (type == TYPE_ACTIVITY) {
            final ParsedActivity activity = mComponentResolver.getActivity(component);
            if (activity == null) {
                return false;
            }
            final boolean visibleToInstantApp =
                    (activity.getFlags() & ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP) != 0;
            final boolean explicitlyVisibleToInstantApp =
                    (activity.getFlags() & ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP)
                            == 0;
            return visibleToInstantApp && explicitlyVisibleToInstantApp;
        } else if (type == TYPE_RECEIVER) {
            final ParsedActivity activity = mComponentResolver.getReceiver(component);
            if (activity == null) {
                return false;
            }
            final boolean visibleToInstantApp =
                    (activity.getFlags() & ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP) != 0;
            final boolean explicitlyVisibleToInstantApp =
                    (activity.getFlags() & ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP)
                            == 0;
            return visibleToInstantApp && !explicitlyVisibleToInstantApp;
        } else if (type == TYPE_SERVICE) {
            final ParsedService service = mComponentResolver.getService(component);
            return service != null
                    && (service.getFlags() & ServiceInfo.FLAG_VISIBLE_TO_INSTANT_APP) != 0;
        } else if (type == TYPE_PROVIDER) {
            final ParsedProvider provider = mComponentResolver.getProvider(component);
            return provider != null
                    && (provider.getFlags() & ProviderInfo.FLAG_VISIBLE_TO_INSTANT_APP) != 0;
        } else if (type == TYPE_UNKNOWN) {
            return isComponentVisibleToInstantApp(component);
        }
        return false;
    }

    /**
     * From Android R,
     *  camera intents have to match system apps. The only exception to this is if
     * the DPC has set the camera persistent preferred activity. This case was introduced
     * because it is important that the DPC has the ability to set both system and non-system
     * camera persistent preferred activities.
     *
     * @return {@code true} if the intent is a camera intent and the persistent preferred
     * activity was not set by the DPC.
     */
    public final boolean isImplicitImageCaptureIntentAndNotSetByDpc(Intent intent,
            int userId, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags) {
        return intent.isImplicitImageCaptureIntent() && !isPersistentPreferredActivitySetByDpm(
                intent, userId, resolvedType, flags);
    }

    public final boolean isInstantApp(String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "isInstantApp");

        return isInstantAppInternal(packageName, userId, callingUid);
    }

    public final boolean isInstantAppInternal(String packageName, @UserIdInt int userId,
            int callingUid) {
        if (HIDE_EPHEMERAL_APIS) {
            return false;
        }
        return isInstantAppInternalBody(packageName, userId, callingUid);
    }

    protected boolean isInstantAppInternalBody(String packageName, @UserIdInt int userId,
            int callingUid) {
        if (Process.isIsolated(callingUid)) {
            callingUid = getIsolatedOwner(callingUid);
        }
        final PackageStateInternal ps = mSettings.getPackage(packageName);
        final boolean returnAllowed =
                ps != null
                        && (isCallerSameApp(packageName, callingUid)
                        || canViewInstantApps(callingUid, userId)
                        || mInstantAppRegistry.isInstantAccessGranted(
                        userId, UserHandle.getAppId(callingUid), ps.getAppId()));
        if (returnAllowed) {
            return ps.getUserStateOrDefault(userId).isInstantApp();
        }
        return false;
    }

    private boolean isInstantAppResolutionAllowed(
            Intent intent, List<ResolveInfo> resolvedActivities, int userId,
            boolean skipPackageCheck, @PackageManager.ResolveInfoFlagsBits long flags) {
        if (mInstantAppResolverConnection == null) {
            return false;
        }
        if (instantAppInstallerActivity() == null) {
            return false;
        }
        if (intent.getComponent() != null) {
            return false;
        }
        if ((intent.getFlags() & Intent.FLAG_IGNORE_EPHEMERAL) != 0) {
            return false;
        }
        if (!skipPackageCheck && intent.getPackage() != null) {
            return false;
        }
        if (!intent.isWebIntent()) {
            // for non web intents, we should not resolve externally if an app already exists to
            // handle it or if the caller didn't explicitly request it.
            if ((resolvedActivities != null && resolvedActivities.size() != 0)
                    || (intent.getFlags() & Intent.FLAG_ACTIVITY_MATCH_EXTERNAL) == 0) {
                return false;
            }
        } else {
            if (intent.getData() == null || TextUtils.isEmpty(intent.getData().getHost())) {
                return false;
            } else if (areWebInstantAppsDisabled(userId)) {
                return false;
            }
        }
        // Deny ephemeral apps if the user chose _ALWAYS or _ALWAYS_ASK for intent resolution.
        // Or if there's already an ephemeral app installed that handles the action
        return isInstantAppResolutionAllowedBody(intent, resolvedActivities, userId,
                skipPackageCheck, flags);
    }

    // Deny ephemeral apps if the user chose _ALWAYS or _ALWAYS_ASK for intent resolution.
    // Or if there's already an ephemeral app installed that handles the action
    protected boolean isInstantAppResolutionAllowedBody(
            Intent intent, List<ResolveInfo> resolvedActivities, int userId,
            boolean skipPackageCheck, @PackageManager.ResolveInfoFlagsBits long flags) {
        final int count = (resolvedActivities == null ? 0 : resolvedActivities.size());
        for (int n = 0; n < count; n++) {
            final ResolveInfo info = resolvedActivities.get(n);
            final String packageName = info.activityInfo.packageName;
            final PackageStateInternal ps = mSettings.getPackage(packageName);
            if (ps != null) {
                // only check domain verification status if the app is not a browser
                if (!info.handleAllWebDataURI) {
                    if (PackageManagerServiceUtils.hasAnyDomainApproval(
                            mDomainVerificationManager, ps, intent, flags, userId)) {
                        if (DEBUG_INSTANT) {
                            Slog.v(TAG, "DENY instant app;" + " pkg: " + packageName
                                    + ", approved");
                        }
                        return false;
                    }
                }
                if (ps.getUserStateOrDefault(userId).isInstantApp()) {
                    if (DEBUG_INSTANT) {
                        Slog.v(TAG, "DENY instant app installed;"
                                + " pkg: " + packageName);
                    }
                    return false;
                }
            }
        }
        // We've exhausted all ways to deny ephemeral application; let the system look for them.
        return true;
    }

    private boolean isPersistentPreferredActivitySetByDpm(Intent intent, int userId,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags) {
        PersistentPreferredIntentResolver ppir =
                mSettings.getPersistentPreferredActivities(userId);
        //TODO(b/158003772): Remove double query
        List<PersistentPreferredActivity> pprefs = ppir != null
                ? ppir.queryIntent(this, intent, resolvedType,
                (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0,
                userId)
                : new ArrayList<>();
        for (PersistentPreferredActivity ppa : pprefs) {
            if (ppa.mIsSetByDpm) {
                return true;
            }
        }
        return false;
    }

    private boolean isRecentsAccessingChildProfiles(int callingUid, int targetUserId) {
        if (!mInjector.getLocalService(ActivityTaskManagerInternal.class)
                .isCallerRecents(callingUid)) {
            return false;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            final int callingUserId = UserHandle.getUserId(callingUid);
            if (ActivityManager.getCurrentUser() != callingUserId) {
                return false;
            }
            return mUserManager.isSameProfileGroup(callingUserId, targetUserId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public final boolean isSameProfileGroup(@UserIdInt int callerUserId,
            @UserIdInt int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            return UserManagerService.getInstance().isSameProfileGroup(callerUserId, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean isUserEnabled(int userId) {
        final long callingId = Binder.clearCallingIdentity();
        try {
            UserInfo userInfo = mUserManager.getUserInfo(userId);
            return userInfo != null && userInfo.isEnabled();
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Returns whether or not access to the application should be filtered.
     * <p>
     * Access may be limited based upon whether the calling or target applications
     * are instant applications.
     *
     * @see #canViewInstantApps(int, int)
     */
    public final boolean shouldFilterApplication(@Nullable PackageStateInternal ps,
            int callingUid, @Nullable ComponentName component,
            @PackageManager.ComponentType int componentType, int userId) {
        if (Process.isSdkSandboxUid(callingUid)) {
            int clientAppUid = Process.getAppUidForSdkSandboxUid(callingUid);
            // SDK sandbox should be able to see it's client app
            if (clientAppUid == UserHandle.getUid(userId, ps.getAppId())) {
                return false;
            }
        }
        // if we're in an isolated process, get the real calling UID
        if (Process.isIsolated(callingUid)) {
            callingUid = getIsolatedOwner(callingUid);
        }
        final String instantAppPkgName = getInstantAppPackageName(callingUid);
        final boolean callerIsInstantApp = instantAppPkgName != null;
        if (ps == null) {
            // pretend the application exists, but, needs to be filtered
            return callerIsInstantApp;
        }
        // if the target and caller are the same application, don't filter
        if (isCallerSameApp(ps.getPackageName(), callingUid)) {
            return false;
        }
        if (callerIsInstantApp) {
            // both caller and target are both instant, but, different applications, filter
            if (ps.getUserStateOrDefault(userId).isInstantApp()) {
                return true;
            }
            // request for a specific component; if it hasn't been explicitly exposed through
            // property or instrumentation target, filter
            if (component != null) {
                final ParsedInstrumentation instrumentation =
                        mInstrumentation.get(component);
                if (instrumentation != null
                        && isCallerSameApp(instrumentation.getTargetPackage(), callingUid)) {
                    return false;
                }
                return !isComponentVisibleToInstantApp(component, componentType);
            }
            // request for application; if no components have been explicitly exposed, filter
            return !ps.getPkg().isVisibleToInstantApps();
        }
        if (ps.getUserStateOrDefault(userId).isInstantApp()) {
            // caller can see all components of all instant applications, don't filter
            if (canViewInstantApps(callingUid, userId)) {
                return false;
            }
            // request for a specific instant application component, filter
            if (component != null) {
                return true;
            }
            // request for an instant application; if the caller hasn't been granted access,
            //filter
            return !mInstantAppRegistry.isInstantAccessGranted(
                    userId, UserHandle.getAppId(callingUid), ps.getAppId());
        }
        int appId = UserHandle.getAppId(callingUid);
        final SettingBase callingPs = mSettings.getSettingBase(appId);
        return mAppsFilter.shouldFilterApplication(callingUid, callingPs, ps, userId);
    }

    /**
     * @see #shouldFilterApplication(PackageStateInternal, int, ComponentName, int, int)
     */
    public final boolean shouldFilterApplication(
            @Nullable PackageStateInternal ps, int callingUid, int userId) {
        return shouldFilterApplication(ps, callingUid, null, TYPE_UNKNOWN, userId);
    }

    /**
     * @see #shouldFilterApplication(PackageStateInternal, int, ComponentName, int, int)
     */
    public final boolean shouldFilterApplication(@NonNull SharedUserSetting sus,
            int callingUid, int userId) {
        boolean filterApp = true;
        final ArraySet<PackageStateInternal> packageStates =
                (ArraySet<PackageStateInternal>) sus.getPackageStates();
        for (int index = packageStates.size() - 1; index >= 0 && filterApp; index--) {
            filterApp &= shouldFilterApplication(packageStates.valueAt(index),
                    callingUid, /* component */ null, TYPE_UNKNOWN, userId);
        }
        return filterApp;
    }

    /**
     * Verification statuses are ordered from the worse to the best, except for
     * INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER, which is the worse.
     */
    private int bestDomainVerificationStatus(int status1, int status2) {
        if (status1 == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER) {
            return status2;
        }
        if (status2 == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER) {
            return status1;
        }
        return (int) MathUtils.max(status1, status2);
    }

    // NOTE: Can't remove without a major refactor. Keep around for now.
    public final int checkUidPermission(String permName, int uid) {
        return mPermissionManager.checkUidPermission(uid, permName);
    }

    public int getPackageUidInternal(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int userId, int callingUid) {
        // reader
        final AndroidPackage p = mPackages.get(packageName);
        if (p != null && AndroidPackageUtils.isMatchForSystemOnly(p, flags)) {
            final PackageStateInternal ps = getPackageStateInternal(p.getPackageName(), callingUid);
            if (ps != null && ps.getUserStateOrDefault(userId).isInstalled()
                    && !shouldFilterApplication(ps, callingUid, userId)) {
                return UserHandle.getUid(userId, p.getUid());
            }
        }
        if ((flags & MATCH_KNOWN_PACKAGES) != 0) {
            final PackageStateInternal ps = mSettings.getPackage(packageName);
            if (ps != null && PackageStateUtils.isMatch(ps, flags)
                    && !shouldFilterApplication(ps, callingUid, userId)) {
                return UserHandle.getUid(userId, ps.getAppId());
            }
        }

        return -1;
    }

    /**
     * Update given flags based on encryption status of current user.
     */
    private long updateFlags(long flags, int userId) {
        if ((flags & (PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | PackageManager.MATCH_DIRECT_BOOT_AWARE)) != 0) {
            // Caller expressed an explicit opinion about what encryption
            // aware/unaware components they want to see, so fall through and
            // give them what they want
        } else {
            final UserManagerInternal umInternal = mInjector.getUserManagerInternal();
            // Caller expressed no opinion, so match based on user state
            if (umInternal.isUserUnlockingOrUnlocked(userId)) {
                flags |= PackageManager.MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
            } else {
                flags |= PackageManager.MATCH_DIRECT_BOOT_AWARE;
            }
        }
        return flags;
    }

    /**
     * Update given flags when being used to request {@link ApplicationInfo}.
     */
    public final long updateFlagsForApplication(long flags, int userId) {
        return updateFlagsForPackage(flags, userId);
    }

    /**
     * Update given flags when being used to request {@link ComponentInfo}.
     */
    public final long updateFlagsForComponent(long flags, int userId) {
        return updateFlags(flags, userId);
    }

    /**
     * Update given flags when being used to request {@link PackageInfo}.
     */
    public final long updateFlagsForPackage(long flags, int userId) {
        final boolean isCallerSystemUser = UserHandle.getCallingUserId()
                == UserHandle.USER_SYSTEM;
        if ((flags & PackageManager.MATCH_ANY_USER) != 0) {
            // require the permission to be held; the calling uid and given user id referring
            // to the same user is not sufficient
            enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false,
                    !isRecentsAccessingChildProfiles(Binder.getCallingUid(), userId),
                    "MATCH_ANY_USER flag requires INTERACT_ACROSS_USERS permission");
        } else if ((flags & PackageManager.MATCH_UNINSTALLED_PACKAGES) != 0
                && isCallerSystemUser
                && mUserManager.hasProfile(UserHandle.USER_SYSTEM)) {
            // If the caller wants all packages and has a profile associated with it,
            // then match all users. This is to make sure that launchers that need to access
            //work
            // profile apps don't start breaking. TODO: Remove this hack when launchers stop
            //using
            // MATCH_UNINSTALLED_PACKAGES to query apps in other profiles. b/31000380
            flags |= PackageManager.MATCH_ANY_USER;
        }
        return updateFlags(flags, userId);
    }

    /**
     * Update given flags when being used to request {@link ResolveInfo}.
     * <p>Instant apps are resolved specially, depending upon context. Minimally,
     * {@code}flags{@code} must have the {@link PackageManager#MATCH_INSTANT}
     * flag set. However, this flag is only honoured in three circumstances:
     * <ul>
     * <li>when called from a system process</li>
     * <li>when the caller holds the permission {@code
     * android.permission.ACCESS_INSTANT_APPS}</li>
     * <li>when resolution occurs to start an activity with a {@code android.intent.action.VIEW}
     * action and a {@code android.intent.category.BROWSABLE} category</li>
     * </ul>
     */
    public final long updateFlagsForResolve(long flags, int userId, int callingUid,
            boolean wantInstantApps, boolean isImplicitImageCaptureIntentAndNotSetByDpc) {
        return updateFlagsForResolve(flags, userId, callingUid,
                wantInstantApps, false /*onlyExposedExplicitly*/,
                isImplicitImageCaptureIntentAndNotSetByDpc);
    }

    public final long updateFlagsForResolve(long flags, int userId, int callingUid,
            boolean wantInstantApps, boolean onlyExposedExplicitly,
            boolean isImplicitImageCaptureIntentAndNotSetByDpc) {
        // Safe mode means we shouldn't match any third-party components
        if (safeMode() || isImplicitImageCaptureIntentAndNotSetByDpc) {
            flags |= PackageManager.MATCH_SYSTEM_ONLY;
        }
        if (getInstantAppPackageName(callingUid) != null) {
            // But, ephemeral apps see both ephemeral and exposed, non-ephemeral components
            if (onlyExposedExplicitly) {
                flags |= PackageManager.MATCH_EXPLICITLY_VISIBLE_ONLY;
            }
            flags |= PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY;
            flags |= PackageManager.MATCH_INSTANT;
        } else {
            final boolean wantMatchInstant = (flags & PackageManager.MATCH_INSTANT) != 0;
            final boolean allowMatchInstant = wantInstantApps
                    || (wantMatchInstant && canViewInstantApps(callingUid, userId));
            flags &= ~(PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY
                    | PackageManager.MATCH_EXPLICITLY_VISIBLE_ONLY);
            if (!allowMatchInstant) {
                flags &= ~PackageManager.MATCH_INSTANT;
            }
        }
        return updateFlagsForComponent(flags, userId);
    }

    /**
     * Checks if the request is from the system or an app that has the appropriate cross-user
     * permissions defined as follows:
     * <ul>
     * <li>INTERACT_ACROSS_USERS_FULL if {@code requireFullPermission} is true.</li>
     * <li>INTERACT_ACROSS_USERS if the given {@code userId} is in a different profile group
     * to the caller.</li>
     * <li>Otherwise,
     *  INTERACT_ACROSS_PROFILES if the given {@code userId} is in the same profile
     * group as the caller.</li>
     * </ul>
     *
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    public final void enforceCrossUserOrProfilePermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, String message) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid userId " + userId);
        }
        if (checkShell) {
            PackageManagerServiceUtils.enforceShellRestriction(
                    mInjector.getUserManagerInternal(),
                    UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, userId);
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (hasCrossUserPermission(callingUid, callingUserId, userId, requireFullPermission,
                /*requirePermissionWhenSameUser= */ false)) {
            return;
        }
        final boolean isSameProfileGroup = isSameProfileGroup(callingUserId, userId);
        if (isSameProfileGroup && PermissionChecker.checkPermissionForPreflight(
                mContext,
                android.Manifest.permission.INTERACT_ACROSS_PROFILES,
                PermissionChecker.PID_UNKNOWN,
                callingUid,
                getPackage(callingUid).getPackageName())
                == PermissionChecker.PERMISSION_GRANTED) {
            return;
        }
        String errorMessage = buildInvalidCrossUserOrProfilePermissionMessage(
                callingUid, userId, message, requireFullPermission, isSameProfileGroup);
        Slog.w(TAG, errorMessage);
        throw new SecurityException(errorMessage);
    }

    private static String buildInvalidCrossUserOrProfilePermissionMessage(int callingUid,
            @UserIdInt int userId, String message, boolean requireFullPermission,
            boolean isSameProfileGroup) {
        StringBuilder builder = new StringBuilder();
        if (message != null) {
            builder.append(message);
            builder.append(": ");
        }
        builder.append("UID ");
        builder.append(callingUid);
        builder.append(" requires ");
        builder.append(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        if (!requireFullPermission) {
            builder.append(" or ");
            builder.append(android.Manifest.permission.INTERACT_ACROSS_USERS);
            if (isSameProfileGroup) {
                builder.append(" or ");
                builder.append(android.Manifest.permission.INTERACT_ACROSS_PROFILES);
            }
        }
        builder.append(" to access user ");
        builder.append(userId);
        builder.append(".");
        return builder.toString();
    }

    /**
     * Enforces the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the {@code userId} is not for the caller.
     *
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    public final void enforceCrossUserPermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, String message) {
        enforceCrossUserPermission(callingUid, userId, requireFullPermission, checkShell, false,
                message);
    }

    /**
     * Enforces the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the {@code userId} is not for the caller.
     *
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param requirePermissionWhenSameUser When {@code true}, still require the cross user
     *                                      permission to be held even if the callingUid and
     * userId
     *                                      reference the same user.
     * @param message the message to log on security exception
     */
    public final void enforceCrossUserPermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell,
            boolean requirePermissionWhenSameUser, String message) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid userId " + userId);
        }
        if (checkShell) {
            PackageManagerServiceUtils.enforceShellRestriction(
                    mInjector.getUserManagerInternal(),
                    UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, userId);
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (hasCrossUserPermission(
                callingUid, callingUserId, userId, requireFullPermission,
                requirePermissionWhenSameUser)) {
            return;
        }
        String errorMessage = buildInvalidCrossUserPermissionMessage(
                callingUid, userId, message, requireFullPermission);
        Slog.w(TAG, errorMessage);
        throw new SecurityException(errorMessage);
    }

    private static String buildInvalidCrossUserPermissionMessage(int callingUid,
            @UserIdInt int userId, String message, boolean requireFullPermission) {
        StringBuilder builder = new StringBuilder();
        if (message != null) {
            builder.append(message);
            builder.append(": ");
        }
        builder.append("UID ");
        builder.append(callingUid);
        builder.append(" requires ");
        builder.append(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        if (!requireFullPermission) {
            builder.append(" or ");
            builder.append(android.Manifest.permission.INTERACT_ACROSS_USERS);
        }
        builder.append(" to access user ");
        builder.append(userId);
        builder.append(".");
        return builder.toString();
    }

    public SigningDetails getSigningDetails(@NonNull String packageName) {
        AndroidPackage p = mPackages.get(packageName);
        if (p == null) {
            return null;
        }
        return p.getSigningDetails();
    }

    public SigningDetails getSigningDetails(int uid) {
        final int appId = UserHandle.getAppId(uid);
        final Object obj = mSettings.getSettingBase(appId);
        if (obj != null) {
            if (obj instanceof SharedUserSetting) {
                return ((SharedUserSetting) obj).signatures.mSigningDetails;
            } else if (obj instanceof PackageStateInternal) {
                final PackageStateInternal ps = (PackageStateInternal) obj;
                return ps.getSigningDetails();
            }
        }
        return SigningDetails.UNKNOWN;
    }

    public boolean filterAppAccess(AndroidPackage pkg, int callingUid, int userId) {
        PackageStateInternal ps = getPackageStateInternal(pkg.getPackageName());
        return shouldFilterApplication(ps, callingUid,
                userId);
    }

    public boolean filterAppAccess(String packageName, int callingUid, int userId) {
        PackageStateInternal ps = getPackageStateInternal(packageName);
        return shouldFilterApplication(ps, callingUid,
                userId);
    }

    public boolean filterAppAccess(int uid, int callingUid) {
        final int userId = UserHandle.getUserId(uid);
        final int appId = UserHandle.getAppId(uid);
        final Object setting = mSettings.getSettingBase(appId);

        if (setting instanceof SharedUserSetting) {
            return shouldFilterApplication(
                    (SharedUserSetting) setting, callingUid, userId);
        } else if (setting == null
                || setting instanceof PackageStateInternal) {
            return shouldFilterApplication(
                    (PackageStateInternal) setting, callingUid, userId);
        }
        return false;
    }

    public void dump(int type, FileDescriptor fd, PrintWriter pw, DumpState dumpState) {
        final String packageName = dumpState.getTargetPackageName();
        final PackageStateInternal setting = mSettings.getPackage(packageName);
        final boolean checkin = dumpState.isCheckIn();

        // Return if the package doesn't exist.
        if (packageName != null && setting == null) {
            return;
        }

        switch (type) {
            case DumpState.DUMP_VERSION:
            {
                if (dumpState.onTitlePrinted()) {
                    pw.println();
                }
                pw.println("Database versions:");
                mSettings.dumpVersionLPr(new IndentingPrintWriter(pw, "  "));
                break;
            }

            case DumpState.DUMP_LIBS:
                mSharedLibraries.dump(pw, dumpState);
                break;

            case DumpState.DUMP_PREFERRED:
                mSettings.dumpPreferred(pw, dumpState, packageName);
                break;

            case DumpState.DUMP_PREFERRED_XML:
            {
                pw.flush();
                FileOutputStream fout = new FileOutputStream(fd);
                BufferedOutputStream str = new BufferedOutputStream(fout);
                TypedXmlSerializer serializer = Xml.newFastSerializer();
                try {
                    serializer.setOutput(str, StandardCharsets.UTF_8.name());
                    serializer.startDocument(null, true);
                    serializer.setFeature(
                            "http://xmlpull.org/v1/doc/features.html#indent-output", true);
                    mSettings.writePreferredActivitiesLPr(serializer, 0,
                            dumpState.isFullPreferred());
                    serializer.endDocument();
                    serializer.flush();
                } catch (IllegalArgumentException e) {
                    pw.println("Failed writing: " + e);
                } catch (IllegalStateException e) {
                    pw.println("Failed writing: " + e);
                } catch (IOException e) {
                    pw.println("Failed writing: " + e);
                }
                break;
            }

            case DumpState.DUMP_QUERIES:
            {
                final Integer filteringAppId = setting == null ? null : setting.getAppId();
                mAppsFilter.dumpQueries(
                        pw, filteringAppId, dumpState, mUserManager.getUserIds(),
                        this::getPackagesForUidInternalBody);
                break;
            }

            case DumpState.DUMP_DOMAIN_PREFERRED:
            {
                final android.util.IndentingPrintWriter writer =
                        new android.util.IndentingPrintWriter(pw);
                if (dumpState.onTitlePrinted()) {
                    pw.println();
                }
                writer.println("Domain verification status:");
                writer.increaseIndent();
                try {
                    mDomainVerificationManager.printState(this, writer, packageName,
                            UserHandle.USER_ALL);
                } catch (Exception e) {
                    pw.println("Failure printing domain verification information");
                    Slog.e(TAG, "Failure printing domain verification information", e);
                }
                writer.decreaseIndent();
                break;
            }

            case DumpState.DUMP_DEXOPT:
            {
                final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
                if (dumpState.onTitlePrinted()) {
                    pw.println();
                }
                ipw.println("Dexopt state:");
                ipw.increaseIndent();
                Collection<? extends PackageStateInternal> pkgSettings;
                if (setting != null) {
                    pkgSettings = Collections.singletonList(setting);
                } else {
                    pkgSettings = mSettings.getPackages().values();
                }

                for (PackageStateInternal pkgSetting : pkgSettings) {
                    final AndroidPackage pkg = pkgSetting.getPkg();
                    if (pkg == null) {
                        continue;
                    }
                    final String pkgName = pkg.getPackageName();
                    ipw.println("[" + pkgName + "]");
                    ipw.increaseIndent();

                    mPackageDexOptimizer.dumpDexoptState(ipw, pkg, pkgSetting,
                            mDexManager.getPackageUseInfoOrDefault(pkgName));
                    ipw.decreaseIndent();
                }
                ipw.println("BgDexopt state:");
                ipw.increaseIndent();
                mBackgroundDexOptService.dump(ipw);
                ipw.decreaseIndent();
                break;
            }

            case DumpState.DUMP_COMPILER_STATS:
            {
                final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
                if (dumpState.onTitlePrinted()) {
                    pw.println();
                }
                ipw.println("Compiler stats:");
                ipw.increaseIndent();
                Collection<? extends PackageStateInternal> pkgSettings;
                if (setting != null) {
                    pkgSettings = Collections.singletonList(setting);
                } else {
                    pkgSettings = mSettings.getPackages().values();
                }

                for (PackageStateInternal pkgSetting : pkgSettings) {
                    final AndroidPackage pkg = pkgSetting.getPkg();
                    if (pkg == null) {
                        continue;
                    }
                    final String pkgName = pkg.getPackageName();
                    ipw.println("[" + pkgName + "]");
                    ipw.increaseIndent();

                    final CompilerStats.PackageStats stats =
                            mCompilerStats.getPackageStats(pkgName);
                    if (stats == null) {
                        ipw.println("(No recorded stats)");
                    } else {
                        stats.dump(ipw);
                    }
                    ipw.decreaseIndent();
                }
                break;
            }

            case DumpState.DUMP_MESSAGES: {
                mSettings.dumpReadMessages(pw, dumpState);
                break;
            }

            case DumpState.DUMP_FROZEN: {
                // XXX should handle packageName != null by dumping only install data that
                // the given package is involved with.
                if (dumpState.onTitlePrinted()) {
                    pw.println();
                }
                final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", 120);
                ipw.println();
                ipw.println("Frozen packages:");
                ipw.increaseIndent();
                if (mFrozenPackages.size() == 0) {
                    ipw.println("(none)");
                } else {
                    for (int i = 0; i < mFrozenPackages.size(); i++) {
                        ipw.print("package=");
                        ipw.print(mFrozenPackages.keyAt(i));
                        ipw.print(", refCounts=");
                        ipw.println(mFrozenPackages.valueAt(i));
                    }
                }
                ipw.decreaseIndent();
            }
        } // switch
    }

    // The body of findPreferredActivity.
    protected PackageManagerService.FindPreferredActivityBodyResult findPreferredActivityBody(
            Intent intent, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            List<ResolveInfo> query, boolean always,
            boolean removeMatches, boolean debug, int userId, boolean queryMayBeFiltered,
            int callingUid, boolean isDeviceProvisioned) {
        PackageManagerService.FindPreferredActivityBodyResult
                result = new PackageManagerService.FindPreferredActivityBodyResult();

        flags = updateFlagsForResolve(
                flags, userId, callingUid, false /*includeInstantApps*/,
                isImplicitImageCaptureIntentAndNotSetByDpc(intent, userId,
                        resolvedType, flags));
        intent = PackageManagerServiceUtils.updateIntentForResolve(intent);

        // Try to find a matching persistent preferred activity.
        result.mPreferredResolveInfo = findPersistentPreferredActivity(intent,
                resolvedType, flags, query, debug, userId);

        // If a persistent preferred activity matched, use it.
        if (result.mPreferredResolveInfo != null) {
            return result;
        }

        PreferredIntentResolver pir = mSettings.getPreferredActivities(userId);
        // Get the list of preferred activities that handle the intent
        if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Looking for preferred activities...");
        List<PreferredActivity> prefs = pir != null
                ? pir.queryIntent(this, intent, resolvedType,
                (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0,
                userId)
                : null;
        if (prefs != null && prefs.size() > 0) {

            // First figure out how good the original match set is.
            // We will only allow preferred activities that came
            // from the same match quality.
            int match = 0;

            if (DEBUG_PREFERRED || debug) {
                Slog.v(TAG, "Figuring out best match...");
            }

            final int n = query.size();
            for (int j = 0; j < n; j++) {
                final ResolveInfo ri = query.get(j);
                if (DEBUG_PREFERRED || debug) {
                    Slog.v(TAG, "Match for " + ri.activityInfo
                            + ": 0x" + Integer.toHexString(ri.match));
                }
                if (ri.match > match) {
                    match = ri.match;
                }
            }

            if (DEBUG_PREFERRED || debug) {
                Slog.v(TAG, "Best match: 0x" + Integer.toHexString(match));
            }
            match &= IntentFilter.MATCH_CATEGORY_MASK;
            final int m = prefs.size();
            for (int i = 0; i < m; i++) {
                final PreferredActivity pa = prefs.get(i);
                if (DEBUG_PREFERRED || debug) {
                    Slog.v(TAG, "Checking PreferredActivity ds="
                            + (pa.countDataSchemes() > 0 ? pa.getDataScheme(0) : "<none>")
                            + "\n  component=" + pa.mPref.mComponent);
                    pa.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
                }
                if (pa.mPref.mMatch != match) {
                    if (DEBUG_PREFERRED || debug) {
                        Slog.v(TAG, "Skipping bad match "
                                + Integer.toHexString(pa.mPref.mMatch));
                    }
                    continue;
                }
                // If it's not an "always" type preferred activity and that's what we're
                // looking for, skip it.
                if (always && !pa.mPref.mAlways) {
                    if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Skipping mAlways=false entry");
                    continue;
                }
                final ActivityInfo ai = getActivityInfo(
                        pa.mPref.mComponent, flags | MATCH_DISABLED_COMPONENTS
                                | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                        userId);
                if (DEBUG_PREFERRED || debug) {
                    Slog.v(TAG, "Found preferred activity:");
                    if (ai != null) {
                        ai.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
                    } else {
                        Slog.v(TAG, "  null");
                    }
                }
                final boolean excludeSetupWizardHomeActivity = isHomeIntent(intent)
                        && !isDeviceProvisioned;
                final boolean allowSetMutation = !excludeSetupWizardHomeActivity
                        && !queryMayBeFiltered;
                if (ai == null) {
                    // Do not remove launcher's preferred activity during SetupWizard
                    // due to it may not install yet
                    if (!allowSetMutation) {
                        continue;
                    }

                    // This previously registered preferred activity
                    // component is no longer known.  Most likely an update
                    // to the app was installed and in the new version this
                    // component no longer exists.  Clean it up by removing
                    // it from the preferred activities list, and skip it.
                    Slog.w(TAG, "Removing dangling preferred activity: "
                            + pa.mPref.mComponent);
                    pir.removeFilter(pa);
                    result.mChanged = true;
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    final ResolveInfo ri = query.get(j);
                    if (!ri.activityInfo.applicationInfo.packageName
                            .equals(ai.applicationInfo.packageName)) {
                        continue;
                    }
                    if (!ri.activityInfo.name.equals(ai.name)) {
                        continue;
                    }

                    if (removeMatches && allowSetMutation) {
                        pir.removeFilter(pa);
                        result.mChanged = true;
                        if (DEBUG_PREFERRED) {
                            Slog.v(TAG, "Removing match " + pa.mPref.mComponent);
                        }
                        break;
                    }

                    // Okay we found a previously set preferred or last chosen app.
                    // If the result set is different from when this
                    // was created, and is not a subset of the preferred set, we need to
                    // clear it and re-ask the user their preference, if we're looking for
                    // an "always" type entry.

                    if (always
                            && !pa.mPref.sameSet(query, excludeSetupWizardHomeActivity, userId)) {
                        if (pa.mPref.isSuperset(query, excludeSetupWizardHomeActivity)) {
                            if (allowSetMutation) {
                                // some components of the set are no longer present in
                                // the query, but the preferred activity can still be reused
                                if (DEBUG_PREFERRED) {
                                    Slog.i(TAG, "Result set changed, but PreferredActivity"
                                            + " is still valid as only non-preferred"
                                            + " components were removed for " + intent
                                            + " type " + resolvedType);
                                }
                                // remove obsolete components and re-add the up-to-date
                                // filter
                                PreferredActivity freshPa = new PreferredActivity(pa,
                                        pa.mPref.mMatch,
                                        pa.mPref.discardObsoleteComponents(query),
                                        pa.mPref.mComponent,
                                        pa.mPref.mAlways);
                                pir.removeFilter(pa);
                                pir.addFilter(this, freshPa);
                                result.mChanged = true;
                            } else {
                                if (DEBUG_PREFERRED) {
                                    Slog.i(TAG, "Do not remove preferred activity");
                                }
                            }
                        } else {
                            if (allowSetMutation) {
                                Slog.i(TAG,
                                        "Result set changed, dropping preferred activity "
                                                + "for " + intent + " type "
                                                + resolvedType);
                                if (DEBUG_PREFERRED) {
                                    Slog.v(TAG,
                                            "Removing preferred activity since set changed "
                                                    + pa.mPref.mComponent);
                                }
                                pir.removeFilter(pa);
                                // Re-add the filter as a "last chosen" entry (!always)
                                PreferredActivity lastChosen = new PreferredActivity(
                                        pa, pa.mPref.mMatch, null, pa.mPref.mComponent,
                                        false);
                                pir.addFilter(this, lastChosen);
                                result.mChanged = true;
                            }
                            result.mPreferredResolveInfo = null;
                            return result;
                        }
                    }

                    // Yay! Either the set matched or we're looking for the last chosen
                    if (DEBUG_PREFERRED || debug) {
                        Slog.v(TAG, "Returning preferred activity: "
                                + ri.activityInfo.packageName + "/" + ri.activityInfo.name);
                    }
                    result.mPreferredResolveInfo = ri;
                    return result;
                }
            }
        }
        return result;
    }

    private static boolean isHomeIntent(Intent intent) {
        return ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(CATEGORY_HOME)
                && intent.hasCategory(CATEGORY_DEFAULT);
    }

    public final PackageManagerService.FindPreferredActivityBodyResult findPreferredActivityInternal(
            Intent intent, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            List<ResolveInfo> query, boolean always,
            boolean removeMatches, boolean debug, int userId, boolean queryMayBeFiltered) {

        final int callingUid = Binder.getCallingUid();
        // Do NOT hold the packages lock; this calls up into the settings provider which
        // could cause a deadlock.
        final boolean isDeviceProvisioned =
                android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                        android.provider.Settings.Global.DEVICE_PROVISIONED, 0) == 1;
        // Find the preferred activity - the lock is held inside the method.
        return findPreferredActivityBody(
                intent, resolvedType, flags, query, always, removeMatches, debug,
                userId, queryMayBeFiltered, callingUid, isDeviceProvisioned);
    }

    public final ResolveInfo findPersistentPreferredActivity(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            List<ResolveInfo> query, boolean debug, int userId) {
        final int n = query.size();
        PersistentPreferredIntentResolver ppir =
                mSettings.getPersistentPreferredActivities(userId);
        // Get the list of persistent preferred activities that handle the intent
        if (DEBUG_PREFERRED || debug) {
            Slog.v(TAG, "Looking for persistent preferred activities...");
        }
        List<PersistentPreferredActivity> pprefs = ppir != null
                ? ppir.queryIntent(this, intent, resolvedType,
                (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0,
                userId)
                : null;
        if (pprefs != null && pprefs.size() > 0) {
            final int m = pprefs.size();
            for (int i = 0; i < m; i++) {
                final PersistentPreferredActivity ppa = pprefs.get(i);
                if (DEBUG_PREFERRED || debug) {
                    Slog.v(TAG, "Checking PersistentPreferredActivity ds="
                            + (ppa.countDataSchemes() > 0 ? ppa.getDataScheme(0) : "<none>")
                            + "\n  component=" + ppa.mComponent);
                    ppa.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
                }
                final ActivityInfo ai = getActivityInfo(ppa.mComponent,
                        flags | MATCH_DISABLED_COMPONENTS, userId);
                if (DEBUG_PREFERRED || debug) {
                    Slog.v(TAG, "Found persistent preferred activity:");
                    if (ai != null) {
                        ai.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
                    } else {
                        Slog.v(TAG, "  null");
                    }
                }
                if (ai == null) {
                    // This previously registered persistent preferred activity
                    // component is no longer known. Ignore it and do NOT remove it.
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    final ResolveInfo ri = query.get(j);
                    if (!ri.activityInfo.applicationInfo.packageName
                            .equals(ai.applicationInfo.packageName)) {
                        continue;
                    }
                    if (!ri.activityInfo.name.equals(ai.name)) {
                        continue;
                    }
                    //  Found a persistent preference that can handle the intent.
                    if (DEBUG_PREFERRED || debug) {
                        Slog.v(TAG, "Returning persistent preferred activity: "
                                + ri.activityInfo.packageName + "/" + ri.activityInfo.name);
                    }
                    return ri;
                }
            }
        }
        return null;
    }

    @Override
    public PreferredIntentResolver getPreferredActivities(int userId) {
        return mSettings.getPreferredActivities(userId);
    }

    @NonNull
    @Override
    public ArrayMap<String, ? extends PackageStateInternal> getPackageStates() {
        return mSettings.getPackages();
    }

    @Nullable
    @Override
    public String getRenamedPackage(@NonNull String packageName) {
        return mSettings.getRenamedPackageLPr(packageName);
    }

    @NonNull
    @Override
    public WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>>
            getSharedLibraries() {
        return mSharedLibraries.getAll();
    }

    @NonNull
    @Override
    public ArraySet<String> getNotifyPackagesForReplacedReceived(@NonNull String[] packages) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);

        ArraySet<String> packagesToNotify = new ArraySet<>();
        for (String packageName : packages) {
            final PackageStateInternal packageState = getPackageStateInternal(packageName);
            if (!shouldFilterApplication(packageState, callingUid, callingUserId)) {
                packagesToNotify.add(packageName);
            }
        }

        return packagesToNotify;
    }

    @PackageManagerService.PackageStartability
    @Override
    public int getPackageStartability(boolean safeMode, @NonNull String packageName, int callingUid,
            @UserIdInt int userId) {
        final boolean userKeyUnlocked = StorageManager.isUserKeyUnlocked(userId);
        final PackageStateInternal ps = getPackageStateInternal(packageName);
        if (ps == null || shouldFilterApplication(ps, callingUid, userId)
                || !ps.getUserStateOrDefault(userId).isInstalled()) {
            return PackageManagerService.PACKAGE_STARTABILITY_NOT_FOUND;
        }

        if (safeMode && !ps.isSystem()) {
            return PackageManagerService.PACKAGE_STARTABILITY_NOT_SYSTEM;
        }

        if (mFrozenPackages.containsKey(packageName)) {
            return PackageManagerService.PACKAGE_STARTABILITY_FROZEN;
        }

        if (!userKeyUnlocked && !AndroidPackageUtils.isEncryptionAware(ps.getPkg())) {
            return PackageManagerService.PACKAGE_STARTABILITY_DIRECT_BOOT_UNSUPPORTED;
        }
        return PackageManagerService.PACKAGE_STARTABILITY_OK;
    }

    @Override
    public boolean isPackageAvailable(String packageName, int userId) {
        if (!mUserManager.exists(userId)) return false;
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "is package available");

        final PackageStateInternal ps = getPackageStateInternal(packageName);
        if (ps != null && ps.getPkg() != null) {
            if (shouldFilterApplication(ps, callingUid, userId)) {
                return false;
            }
            final PackageUserStateInternal state = ps.getUserStateOrDefault(userId);
            if (state != null) {
                return PackageUserStateUtils.isAvailable(state, 0);
            }
        }
        return false;
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return names;
        }
        final String[] out = new String[names.length];
        final int callingUserId = UserHandle.getUserId(callingUid);
        final boolean canViewInstantApps = canViewInstantApps(callingUid, callingUserId);
        for (int i=names.length-1; i>=0; i--) {
            final PackageStateInternal ps = getPackageStateInternal(names[i]);
            boolean translateName = false;
            if (ps != null && ps.getRealName() != null) {
                final boolean targetIsInstantApp = ps.getUserStateOrDefault(callingUserId)
                        .isInstantApp();
                translateName = !targetIsInstantApp
                        || canViewInstantApps
                        || mInstantAppRegistry.isInstantAccessGranted(callingUserId,
                        UserHandle.getAppId(callingUid), ps.getAppId());
            }
            out[i] = translateName ? ps.getRealName() : names[i];
        }
        return out;
    }

    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return names;
        }
        final String[] out = new String[names.length];
        final int callingUserId = UserHandle.getUserId(callingUid);
        final boolean canViewInstantApps = canViewInstantApps(callingUid, callingUserId);
        for (int i=names.length-1; i>=0; i--) {
            final String cur = getRenamedPackage(names[i]);
            boolean translateName = false;
            if (cur != null) {
                final PackageStateInternal ps = getPackageStateInternal(names[i]);
                final boolean targetIsInstantApp =
                        ps != null && ps.getUserStateOrDefault(callingUserId).isInstantApp();
                translateName = !targetIsInstantApp
                        || canViewInstantApps
                        || mInstantAppRegistry.isInstantAccessGranted(callingUserId,
                        UserHandle.getAppId(callingUid), ps.getAppId());
            }
            out[i] = translateName ? cur : names[i];
        }
        return out;
    }

    @Override
    public int[] getPackageGids(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId) {
        if (!mUserManager.exists(userId)) return null;
        final int callingUid = Binder.getCallingUid();
        flags = updateFlagsForPackage(flags, userId);
        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "getPackageGids");

        final PackageStateInternal ps = getPackageStateInternal(packageName);
        if (ps == null) {
            return null;
        }
        if (ps.getPkg() != null
                && AndroidPackageUtils.isMatchForSystemOnly(ps.getPkg(), flags)) {
            if (ps.getUserStateOrDefault(userId).isInstalled()
                    && !shouldFilterApplication(ps, callingUid, userId)) {
                return mPermissionManager.getGidsForUid(UserHandle.getUid(userId,
                        ps.getAppId()));
            }
        }
        if ((flags & MATCH_KNOWN_PACKAGES) != 0) {
            if (PackageStateUtils.isMatch(ps, flags)
                    && !shouldFilterApplication(ps, callingUid, userId)) {
                return mPermissionManager.getGidsForUid(
                        UserHandle.getUid(userId, ps.getAppId()));
            }
        }

        return null;
    }

    @Override
    public int getTargetSdkVersion(@NonNull String packageName)  {
        final PackageStateInternal ps = getPackageStateInternal(packageName);
        if (ps == null || ps.getPkg() == null) {
            return -1;
        }
        if (shouldFilterApplication(ps, Binder.getCallingUid(),
                UserHandle.getCallingUserId())) {
            return -1;
        }
        return ps.getPkg().getTargetSdkVersion();
    }

    @Override
    public boolean activitySupportsIntent(@NonNull ComponentName resolveComponentName,
            @NonNull ComponentName component, @NonNull Intent intent, String resolvedType) {
        if (component.equals(resolveComponentName)) {
            // The resolver supports EVERYTHING!
            return true;
        }
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        ParsedActivity a = mComponentResolver.getActivity(component);
        if (a == null) {
            return false;
        }
        final PackageStateInternal ps = getPackageStateInternal(component.getPackageName());
        if (ps == null) {
            return false;
        }
        if (shouldFilterApplication(
                ps, callingUid, component, TYPE_ACTIVITY, callingUserId)) {
            return false;
        }
        for (int i=0; i< a.getIntents().size(); i++) {
            if (a.getIntents().get(i).getIntentFilter()
                    .match(intent.getAction(), resolvedType, intent.getScheme(),
                            intent.getData(), intent.getCategories(), TAG) >= 0) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    @Override
    public ActivityInfo getReceiverInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId) {
        if (!mUserManager.exists(userId)) return null;
        final int callingUid = Binder.getCallingUid();
        flags = updateFlagsForComponent(flags, userId);
        enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                false /* checkShell */, "get receiver info");

        ParsedActivity a = mComponentResolver.getReceiver(component);
        if (DEBUG_PACKAGE_INFO) Log.v(
                TAG, "getReceiverInfo " + component + ": " + a);

        if (a == null) {
            return null;
        }

        final PackageStateInternal ps = getPackageStateInternal(a.getPackageName());
        if (ps == null || ps.getPkg() == null) {
            return null;
        }

        if (PackageStateUtils.isEnabledAndMatches(ps, a, flags, userId)) {
            if (shouldFilterApplication(ps, callingUid, component, TYPE_RECEIVER, userId)) {
                return null;
            }
            return PackageInfoUtils.generateActivityInfo(ps.getPkg(),
                    a, flags, ps.getUserStateOrDefault(userId), userId, ps);
        }
        return null;
    }

    @Nullable
    @Override
    public ParceledListSlice<SharedLibraryInfo> getSharedLibraries(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId) {
        if (!mUserManager.exists(userId)) return null;
        Preconditions.checkArgumentNonnegative(userId, "userId must be >= 0");
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return null;
        }

        flags = updateFlagsForPackage(flags, userId);

        final boolean canSeeStaticAndSdkLibraries =
                mContext.checkCallingOrSelfPermission(INSTALL_PACKAGES)
                        == PERMISSION_GRANTED
                        || mContext.checkCallingOrSelfPermission(DELETE_PACKAGES)
                        == PERMISSION_GRANTED
                        || canRequestPackageInstalls(packageName, callingUid, userId,
                        false  /* throwIfPermNotDeclared*/)
                        || mContext.checkCallingOrSelfPermission(REQUEST_DELETE_PACKAGES)
                        == PERMISSION_GRANTED
                        || mContext.checkCallingOrSelfPermission(
                        Manifest.permission.ACCESS_SHARED_LIBRARIES) == PERMISSION_GRANTED;

        final WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>> sharedLibraries =
                getSharedLibraries();
        List<SharedLibraryInfo> result = null;
        final int libCount = sharedLibraries.size();
        for (int i = 0; i < libCount; i++) {
            WatchedLongSparseArray<SharedLibraryInfo> versionedLib = sharedLibraries.valueAt(i);
            if (versionedLib == null) {
                continue;
            }

            final int versionCount = versionedLib.size();
            for (int j = 0; j < versionCount; j++) {
                SharedLibraryInfo libInfo = versionedLib.valueAt(j);
                if (!canSeeStaticAndSdkLibraries && (libInfo.isStatic() || libInfo.isSdk())) {
                    break;
                }
                final long identity = Binder.clearCallingIdentity();
                final VersionedPackage declaringPackage = libInfo.getDeclaringPackage();
                try {
                    PackageInfo packageInfo = getPackageInfoInternal(
                            declaringPackage.getPackageName(),
                            declaringPackage.getLongVersionCode(),
                            flags | PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES,
                            Binder.getCallingUid(), userId);
                    if (packageInfo == null) {
                        continue;
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

                SharedLibraryInfo resLibInfo = new SharedLibraryInfo(libInfo.getPath(),
                        libInfo.getPackageName(), libInfo.getAllCodePaths(),
                        libInfo.getName(), libInfo.getLongVersion(),
                        libInfo.getType(), declaringPackage,
                        getPackagesUsingSharedLibrary(libInfo, flags, callingUid, userId),
                        (libInfo.getDependencies() == null
                                ? null
                                : new ArrayList<>(libInfo.getDependencies())),
                        libInfo.isNative());

                if (result == null) {
                    result = new ArrayList<>();
                }
                result.add(resLibInfo);
            }
        }

        return result != null ? new ParceledListSlice<>(result) : null;
    }

    @Override
    public boolean canRequestPackageInstalls(@NonNull String packageName, int callingUid,
            int userId, boolean throwIfPermNotDeclared) {
        int uid = getPackageUidInternal(packageName, 0, userId, callingUid);
        if (callingUid != uid && callingUid != Process.ROOT_UID
                && callingUid != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "Caller uid " + callingUid + " does not own package " + packageName);
        }
        if (isInstantAppInternal(packageName, userId, Process.SYSTEM_UID)) {
            return false;
        }
        final AndroidPackage pkg =  mPackages.get(packageName);
        if (pkg == null) {
            return false;
        }
        if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.O) {
            return false;
        }
        if (!pkg.getRequestedPermissions().contains(
                android.Manifest.permission.REQUEST_INSTALL_PACKAGES)) {
            final String message = "Need to declare "
                    + android.Manifest.permission.REQUEST_INSTALL_PACKAGES
                    + " to call this api";
            if (throwIfPermNotDeclared) {
                throw new SecurityException(message);
            } else {
                Slog.e(TAG, message);
                return false;
            }
        }

        return !isInstallDisabledForPackage(packageName, uid, userId);
    }

    /**
     * Returns true if the system or user is explicitly preventing an otherwise valid installer to
     * complete an install. This includes checks like unknown sources and user restrictions.
     */
    @Override
    public final boolean isInstallDisabledForPackage(@NonNull String packageName, int uid,
            @UserIdInt int userId) {
        if (mUserManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, userId)
                || mUserManager.hasUserRestriction(
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, userId)) {
            return true;
        }
        if (mExternalSourcesPolicy != null) {
            int isTrusted = mExternalSourcesPolicy.getPackageTrustedToInstallApps(packageName, uid);
            return isTrusted != PackageManagerInternal.ExternalSourcesPolicy.USER_TRUSTED;
        }
        return false;
    }

    @Override
    public List<VersionedPackage> getPackagesUsingSharedLibrary(@NonNull SharedLibraryInfo libInfo,
            @PackageManager.PackageInfoFlagsBits long flags, int callingUid,
            @UserIdInt int userId) {
        List<VersionedPackage> versionedPackages = null;
        final ArrayMap<String, ? extends PackageStateInternal> packageStates = getPackageStates();
        final int packageCount = packageStates.size();
        for (int i = 0; i < packageCount; i++) {
            PackageStateInternal ps = packageStates.valueAt(i);
            if (ps == null) {
                continue;
            }

            if (!PackageUserStateUtils.isAvailable(ps.getUserStateOrDefault(userId), flags)) {
                continue;
            }

            final String libName = libInfo.getName();
            if (libInfo.isStatic() || libInfo.isSdk()) {
                final String[] libs =
                        libInfo.isStatic() ? ps.getUsesStaticLibraries() : ps.getUsesSdkLibraries();
                final long[] libsVersions = libInfo.isStatic() ? ps.getUsesStaticLibrariesVersions()
                        : ps.getUsesSdkLibrariesVersionsMajor();

                final int libIdx = ArrayUtils.indexOf(libs, libName);
                if (libIdx < 0) {
                    continue;
                }
                if (libsVersions[libIdx] != libInfo.getLongVersion()) {
                    continue;
                }
                if (shouldFilterApplication(ps, callingUid, userId)) {
                    continue;
                }
                if (versionedPackages == null) {
                    versionedPackages = new ArrayList<>();
                }
                // If the dependent is a static shared lib, use the public package name
                String dependentPackageName = ps.getPackageName();
                if (ps.getPkg() != null && ps.getPkg().isStaticSharedLibrary()) {
                    dependentPackageName = ps.getPkg().getManifestPackageName();
                }
                versionedPackages.add(new VersionedPackage(dependentPackageName,
                        ps.getVersionCode()));
            } else if (ps.getPkg() != null) {
                if (ArrayUtils.contains(ps.getPkg().getUsesLibraries(), libName)
                        || ArrayUtils.contains(ps.getPkg().getUsesOptionalLibraries(), libName)) {
                    if (shouldFilterApplication(ps, callingUid, userId)) {
                        continue;
                    }
                    if (versionedPackages == null) {
                        versionedPackages = new ArrayList<>();
                    }
                    versionedPackages.add(new VersionedPackage(ps.getPackageName(),
                            ps.getVersionCode()));
                }
            }
        }

        return versionedPackages;
    }

    @Nullable
    @Override
    public ParceledListSlice<SharedLibraryInfo> getDeclaredSharedLibraries(
            @NonNull String packageName, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_SHARED_LIBRARIES,
                "getDeclaredSharedLibraries");
        int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "getDeclaredSharedLibraries");

        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        Preconditions.checkArgumentNonnegative(userId, "userId must be >= 0");
        if (!mUserManager.exists(userId)) {
            return null;
        }

        if (getInstantAppPackageName(callingUid) != null) {
            return null;
        }

        final WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>> sharedLibraries =
                getSharedLibraries();
        List<SharedLibraryInfo> result = null;

        int libraryCount = sharedLibraries.size();
        for (int i = 0; i < libraryCount; i++) {
            WatchedLongSparseArray<SharedLibraryInfo> versionedLibrary =
                    sharedLibraries.valueAt(i);
            if (versionedLibrary == null) {
                continue;
            }

            int versionCount = versionedLibrary.size();
            for (int j = 0; j < versionCount; j++) {
                SharedLibraryInfo libraryInfo = versionedLibrary.valueAt(j);

                VersionedPackage declaringPackage = libraryInfo.getDeclaringPackage();
                if (!Objects.equals(declaringPackage.getPackageName(), packageName)) {
                    continue;
                }

                final long identity = Binder.clearCallingIdentity();
                try {
                    PackageInfo packageInfo = getPackageInfoInternal(
                            declaringPackage.getPackageName(),
                            declaringPackage.getLongVersionCode(),
                            flags | PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES,
                            Binder.getCallingUid(), userId);
                    if (packageInfo == null) {
                        continue;
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

                SharedLibraryInfo resultLibraryInfo = new SharedLibraryInfo(
                        libraryInfo.getPath(), libraryInfo.getPackageName(),
                        libraryInfo.getAllCodePaths(), libraryInfo.getName(),
                        libraryInfo.getLongVersion(), libraryInfo.getType(),
                        libraryInfo.getDeclaringPackage(),
                        getPackagesUsingSharedLibrary(
                                libraryInfo, flags, callingUid, userId),
                        libraryInfo.getDependencies() == null
                                ? null : new ArrayList<>(libraryInfo.getDependencies()),
                        libraryInfo.isNative());

                if (result == null) {
                    result = new ArrayList<>();
                }
                result.add(resultLibraryInfo);
            }
        }

        return result != null ? new ParceledListSlice<>(result) : null;
    }

    @Nullable
    @Override
    public ProviderInfo getProviderInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId) {
        if (!mUserManager.exists(userId)) return null;
        final int callingUid = Binder.getCallingUid();
        flags = updateFlagsForComponent(flags, userId);
        enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                false /* checkShell */, "get provider info");
        ParsedProvider p = mComponentResolver.getProvider(component);
        if (DEBUG_PACKAGE_INFO) Log.v(
                TAG, "getProviderInfo " + component + ": " + p);
        if (p == null) {
            return null;
        }

        final PackageStateInternal ps = getPackageStateInternal(p.getPackageName());
        if (ps == null || ps.getPkg() == null) {
            return null;
        }

        if (PackageStateUtils.isEnabledAndMatches(ps, p, flags, userId)) {
            if (shouldFilterApplication(
                    ps, callingUid, component, TYPE_PROVIDER, userId)) {
                return null;
            }
            PackageUserStateInternal state = ps.getUserStateOrDefault(userId);
            final ApplicationInfo appInfo =
                    PackageInfoUtils.generateApplicationInfo(ps.getPkg(), flags, state, userId, ps);
            if (appInfo == null) {
                return null;
            }
            return PackageInfoUtils.generateProviderInfo(ps.getPkg(), p, flags, state, appInfo,
                    userId, ps);
        }
        return null;
    }

    @Nullable
    @Override
    public String[] getSystemSharedLibraryNames() {
        // allow instant applications
        final WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>> sharedLibraries =
                getSharedLibraries();
        Set<String> libs = null;
        final int libCount = sharedLibraries.size();
        for (int i = 0; i < libCount; i++) {
            WatchedLongSparseArray<SharedLibraryInfo> versionedLib = sharedLibraries.valueAt(i);
            if (versionedLib == null) {
                continue;
            }
            final int versionCount = versionedLib.size();
            for (int j = 0; j < versionCount; j++) {
                SharedLibraryInfo libraryInfo = versionedLib.valueAt(j);
                if (!libraryInfo.isStatic()) {
                    if (libs == null) {
                        libs = new ArraySet<>();
                    }
                    libs.add(libraryInfo.getName());
                    break;
                }
                final PackageStateInternal ps =
                        getPackageStateInternal(libraryInfo.getPackageName());
                if (ps != null && !filterSharedLibPackage(ps, Binder.getCallingUid(),
                        UserHandle.getUserId(Binder.getCallingUid()),
                        PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES)) {
                    if (libs == null) {
                        libs = new ArraySet<>();
                    }
                    libs.add(libraryInfo.getName());
                    break;
                }
            }
        }

        if (libs != null) {
            String[] libsArray = new String[libs.size()];
            libs.toArray(libsArray);
            return libsArray;
        }

        return null;
    }

    @Override
    public PackageStateInternal getPackageStateFiltered(@NonNull String packageName, int callingUid,
            @UserIdInt int userId) {
        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState == null || shouldFilterApplication(packageState, callingUid, userId)) {
            return null;
        }
        return packageState;
    }

    @Override
    public int checkSignatures(@NonNull String pkg1, @NonNull String pkg2) {
        final AndroidPackage p1 = mPackages.get(pkg1);
        final AndroidPackage p2 = mPackages.get(pkg2);
        final PackageStateInternal ps1 =
                p1 == null ? null : getPackageStateInternal(p1.getPackageName());
        final PackageStateInternal ps2 =
                p2 == null ? null : getPackageStateInternal(p2.getPackageName());
        if (p1 == null || ps1 == null || p2 == null || ps2 == null) {
            return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
        }
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (shouldFilterApplication(ps1, callingUid, callingUserId)
                || shouldFilterApplication(ps2, callingUid, callingUserId)) {
            return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
        }
        return checkSignaturesInternal(p1.getSigningDetails(), p2.getSigningDetails());
    }

    @Override
    public int checkUidSignatures(int uid1, int uid2) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        // Map to base uids.
        final int appId1 = UserHandle.getAppId(uid1);
        final int appId2 = UserHandle.getAppId(uid2);
        SigningDetails p1SigningDetails;
        SigningDetails p2SigningDetails;
        Object obj = mSettings.getSettingBase(appId1);
        if (obj != null) {
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                if (shouldFilterApplication(sus, callingUid, callingUserId)) {
                    return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                }
                p1SigningDetails = sus.signatures.mSigningDetails;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                if (shouldFilterApplication(ps, callingUid, callingUserId)) {
                    return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                }
                p1SigningDetails = ps.getSigningDetails();
            } else {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
        } else {
            return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
        }
        obj = mSettings.getSettingBase(appId2);
        if (obj != null) {
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                if (shouldFilterApplication(sus, callingUid, callingUserId)) {
                    return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                }
                p2SigningDetails = sus.signatures.mSigningDetails;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                if (shouldFilterApplication(ps, callingUid, callingUserId)) {
                    return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                }
                p2SigningDetails = ps.getSigningDetails();
            } else {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
        } else {
            return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
        }
        return checkSignaturesInternal(p1SigningDetails, p2SigningDetails);
    }

    private int checkSignaturesInternal(SigningDetails p1SigningDetails,
            SigningDetails p2SigningDetails) {
        if (p1SigningDetails == null) {
            return p2SigningDetails == null
                    ? PackageManager.SIGNATURE_NEITHER_SIGNED
                    : PackageManager.SIGNATURE_FIRST_NOT_SIGNED;
        }
        if (p2SigningDetails == null) {
            return PackageManager.SIGNATURE_SECOND_NOT_SIGNED;
        }
        int result = compareSignatures(p1SigningDetails.getSignatures(),
                p2SigningDetails.getSignatures());
        if (result == PackageManager.SIGNATURE_MATCH) {
            return result;
        }
        // To support backwards compatibility with clients of this API expecting pre-key
        // rotation results if either of the packages has a signing lineage the oldest signer
        // in the lineage is used for signature verification.
        if (p1SigningDetails.hasPastSigningCertificates()
                || p2SigningDetails.hasPastSigningCertificates()) {
            Signature[] p1Signatures = p1SigningDetails.hasPastSigningCertificates()
                    ? new Signature[]{p1SigningDetails.getPastSigningCertificates()[0]}
                    : p1SigningDetails.getSignatures();
            Signature[] p2Signatures = p2SigningDetails.hasPastSigningCertificates()
                    ? new Signature[]{p2SigningDetails.getPastSigningCertificates()[0]}
                    : p2SigningDetails.getSignatures();
            result = compareSignatures(p1Signatures, p2Signatures);
        }
        return result;
    }

    @Override
    public boolean hasSigningCertificate(@NonNull String packageName, @NonNull byte[] certificate,
        @PackageManager.CertificateInputType int type) {
        final AndroidPackage p = mPackages.get(packageName);
        if (p == null) {
            return false;
        }
        final PackageStateInternal ps = getPackageStateInternal(p.getPackageName());
        if (ps == null) {
            return false;
        }
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (shouldFilterApplication(ps, callingUid, callingUserId)) {
            return false;
        }
        switch (type) {
            case CERT_INPUT_RAW_X509:
                return p.getSigningDetails().hasCertificate(certificate);
            case CERT_INPUT_SHA256:
                return p.getSigningDetails().hasSha256Certificate(certificate);
            default:
                return false;
        }
    }

    @Override
    public boolean hasUidSigningCertificate(int uid, @NonNull byte[] certificate,
            @PackageManager.CertificateInputType int type) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        // Map to base uids.
        final int appId = UserHandle.getAppId(uid);
        final SigningDetails signingDetails;
        final Object obj = mSettings.getSettingBase(appId);
        if (obj != null) {
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                if (shouldFilterApplication(sus, callingUid, callingUserId)) {
                    return false;
                }
                signingDetails = sus.signatures.mSigningDetails;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                if (shouldFilterApplication(ps, callingUid, callingUserId)) {
                    return false;
                }
                signingDetails = ps.getSigningDetails();
            } else {
                return false;
            }
        } else {
            return false;
        }
        switch (type) {
            case CERT_INPUT_RAW_X509:
                return signingDetails.hasCertificate(certificate);
            case CERT_INPUT_SHA256:
                return signingDetails.hasSha256Certificate(certificate);
            default:
                return false;
        }
    }

    @Override
    public List<String> getAllPackages() {
        PackageManagerServiceUtils.enforceSystemOrRootOrShell(
                "getAllPackages is limited to privileged callers");
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (canViewInstantApps(callingUid, callingUserId)) {
            return new ArrayList<>(mPackages.keySet());
        }
        final String instantAppPkgName = getInstantAppPackageName(callingUid);
        final List<String> result = new ArrayList<>();
        if (instantAppPkgName != null) {
            // caller is an instant application; filter unexposed applications
            for (AndroidPackage pkg : mPackages.values()) {
                if (!pkg.isVisibleToInstantApps()) {
                    continue;
                }
                result.add(pkg.getPackageName());
            }
        } else {
            // caller is a normal application; filter instant applications
            for (AndroidPackage pkg : mPackages.values()) {
                final PackageStateInternal ps = getPackageStateInternal(pkg.getPackageName());
                if (ps != null
                        && ps.getUserStateOrDefault(callingUserId).isInstantApp()
                        && !mInstantAppRegistry.isInstantAccessGranted(callingUserId,
                        UserHandle.getAppId(callingUid), ps.getAppId())) {
                    continue;
                }
                result.add(pkg.getPackageName());
            }
        }
        return result;
    }

    @Nullable
    @Override
    public String getNameForUid(int uid) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        if (Process.isSdkSandboxUid(uid)) {
            uid = getBaseSdkSandboxUid();
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        final int appId = UserHandle.getAppId(uid);
        final Object obj = mSettings.getSettingBase(appId);
        if (obj instanceof SharedUserSetting) {
            final SharedUserSetting sus = (SharedUserSetting) obj;
            if (shouldFilterApplication(sus, callingUid, callingUserId)) {
                return null;
            }
            return sus.name + ":" + sus.mAppId;
        } else if (obj instanceof PackageSetting) {
            final PackageSetting ps = (PackageSetting) obj;
            if (shouldFilterApplication(ps, callingUid, callingUserId)) {
                return null;
            }
            return ps.getPackageName();
        }
        return null;
    }

    @Nullable
    @Override
    public String[] getNamesForUids(int[] uids) {
        if (uids == null || uids.length == 0) {
            return null;
        }
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        final String[] names = new String[uids.length];
        for (int i = uids.length - 1; i >= 0; i--) {
            int uid = uids[i];
            if (Process.isSdkSandboxUid(uid)) {
                uid = getBaseSdkSandboxUid();
            }
            final int appId = UserHandle.getAppId(uid);
            final Object obj = mSettings.getSettingBase(appId);
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                if (shouldFilterApplication(sus, callingUid, callingUserId)) {
                    names[i] = null;
                } else {
                    names[i] = "shared:" + sus.name;
                }
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                if (shouldFilterApplication(ps, callingUid, callingUserId)) {
                    names[i] = null;
                } else {
                    names[i] = ps.getPackageName();
                }
            } else {
                names[i] = null;
            }
        }
        return names;
    }

    @Override
    public int getUidForSharedUser(@NonNull String sharedUserName) {
        if (sharedUserName == null) {
            return Process.INVALID_UID;
        }
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return Process.INVALID_UID;
        }
        final SharedUserSetting suid = mSettings.getSharedUserFromId(sharedUserName);
        if (suid != null && !shouldFilterApplication(suid, callingUid,
                UserHandle.getUserId(callingUid))) {
            return suid.mAppId;
        }
        return Process.INVALID_UID;
    }

    @Override
    public int getFlagsForUid(int uid) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return 0;
        }
        if (Process.isSdkSandboxUid(uid)) {
            uid = getBaseSdkSandboxUid();
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        final int appId = UserHandle.getAppId(uid);
        final Object obj = mSettings.getSettingBase(appId);
        if (obj instanceof SharedUserSetting) {
            final SharedUserSetting sus = (SharedUserSetting) obj;
            if (shouldFilterApplication(sus, callingUid, callingUserId)) {
                return 0;
            }
            return sus.getFlags();
        } else if (obj instanceof PackageSetting) {
            final PackageSetting ps = (PackageSetting) obj;
            if (shouldFilterApplication(ps, callingUid, callingUserId)) {
                return 0;
            }
            return ps.getFlags();
        }
        return 0;
    }

    @Override
    public int getPrivateFlagsForUid(int uid) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return 0;
        }
        if (Process.isSdkSandboxUid(uid)) {
            uid = getBaseSdkSandboxUid();
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        final int appId = UserHandle.getAppId(uid);
        final Object obj = mSettings.getSettingBase(appId);
        if (obj instanceof SharedUserSetting) {
            final SharedUserSetting sus = (SharedUserSetting) obj;
            if (shouldFilterApplication(sus, callingUid, callingUserId)) {
                return 0;
            }
            return sus.getPrivateFlags();
        } else if (obj instanceof PackageSetting) {
            final PackageSetting ps = (PackageSetting) obj;
            if (shouldFilterApplication(ps, callingUid, callingUserId)) {
                return 0;
            }
            return ps.getPrivateFlags();
        }
        return 0;
    }

    @Override
    public boolean isUidPrivileged(int uid) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return false;
        }
        if (Process.isSdkSandboxUid(uid)) {
            uid = getBaseSdkSandboxUid();
        }
        final int appId = UserHandle.getAppId(uid);
        final Object obj = mSettings.getSettingBase(appId);
        if (obj instanceof SharedUserSetting) {
            final SharedUserSetting sus = (SharedUserSetting) obj;
            final ArraySet<PackageStateInternal> packageStates =
                    (ArraySet<PackageStateInternal>) sus.getPackageStates();
            final int numPackages = packageStates.size();
            for (int index = 0; index < numPackages; index++) {
                final PackageStateInternal ps = packageStates.valueAt(index);
                if (ps.isPrivileged()) {
                    return true;
                }
            }
        } else if (obj instanceof PackageSetting) {
            final PackageSetting ps = (PackageSetting) obj;
            return ps.isPrivileged();
        }
        return false;
    }

    // NOTE: Can't remove due to unsupported app usage
    @NonNull
    @Override
    public String[] getAppOpPermissionPackages(@NonNull String permissionName) {
        if (permissionName == null) {
            return EmptyArray.STRING;
        }
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return EmptyArray.STRING;
        }
        final int callingUserId = UserHandle.getUserId(callingUid);

        final ArraySet<String> packageNames = new ArraySet(
                mPermissionManager.getAppOpPermissionPackages(permissionName));
        for (int i = packageNames.size() - 1; i >= 0; i--) {
            final String packageName = packageNames.valueAt(i);
            if (!shouldFilterApplication(mSettings.getPackage(packageName), callingUid,
                    callingUserId)) {
                continue;
            }
            packageNames.removeAt(i);
        }
        return packageNames.toArray(new String[packageNames.size()]);
    }

    @NonNull
    @Override
    public ParceledListSlice<PackageInfo> getPackagesHoldingPermissions(
            @NonNull String[] permissions, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId) {
        if (!mUserManager.exists(userId)) return ParceledListSlice.emptyList();
        flags = updateFlagsForPackage(flags, userId);
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true /* requireFullPermission */,
                false /* checkShell */, "get packages holding permissions");
        final boolean listUninstalled = (flags & MATCH_KNOWN_PACKAGES) != 0;

        ArrayList<PackageInfo> list = new ArrayList<>();
        boolean[] tmpBools = new boolean[permissions.length];
        for (PackageStateInternal ps : getPackageStates().values()) {
            if (ps.getPkg() == null && !listUninstalled) {
                continue;
            }
            addPackageHoldingPermissions(list, ps, permissions, tmpBools, flags, userId);
        }

        return new ParceledListSlice<>(list);
    }

    private void addPackageHoldingPermissions(ArrayList<PackageInfo> list, PackageStateInternal ps,
            String[] permissions, boolean[] tmp, @PackageManager.PackageInfoFlagsBits long flags,
            int userId) {
        int numMatch = 0;
        for (int i=0; i<permissions.length; i++) {
            final String permission = permissions[i];
            if (mPermissionManager.checkPermission(ps.getPackageName(), permission, userId)
                    == PERMISSION_GRANTED) {
                tmp[i] = true;
                numMatch++;
            } else {
                tmp[i] = false;
            }
        }
        if (numMatch == 0) {
            return;
        }
        final PackageInfo pi = generatePackageInfo(ps, flags, userId);

        // The above might return null in cases of uninstalled apps or install-state
        // skew across users/profiles.
        if (pi != null) {
            if ((flags & PackageManager.GET_PERMISSIONS) == 0) {
                if (numMatch == permissions.length) {
                    pi.requestedPermissions = permissions;
                } else {
                    pi.requestedPermissions = new String[numMatch];
                    numMatch = 0;
                    for (int i=0; i<permissions.length; i++) {
                        if (tmp[i]) {
                            pi.requestedPermissions[numMatch] = permissions[i];
                            numMatch++;
                        }
                    }
                }
            }
            list.add(pi);
        }
    }

    @NonNull
    @Override
    public List<ApplicationInfo> getInstalledApplications(
            @PackageManager.ApplicationInfoFlagsBits long flags, @UserIdInt int userId,
            int callingUid) {
        if (getInstantAppPackageName(callingUid) != null) {
            return Collections.emptyList();
        }
        if (!mUserManager.exists(userId)) return Collections.emptyList();
        flags = updateFlagsForApplication(flags, userId);
        final boolean listUninstalled = (flags & MATCH_KNOWN_PACKAGES) != 0;

        enforceCrossUserPermission(
                callingUid,
                userId,
                false /* requireFullPermission */,
                false /* checkShell */,
                "get installed application info");

        ArrayList<ApplicationInfo> list;
        final ArrayMap<String, ? extends PackageStateInternal> packageStates =
                getPackageStates();
        if (listUninstalled) {
            list = new ArrayList<>(packageStates.size());
            for (PackageStateInternal ps : packageStates.values()) {
                ApplicationInfo ai;
                long effectiveFlags = flags;
                if (ps.isSystem()) {
                    effectiveFlags |= PackageManager.MATCH_ANY_USER;
                }
                if (ps.getPkg() != null) {
                    if (filterSharedLibPackage(ps, callingUid, userId, flags)) {
                        continue;
                    }
                    if (shouldFilterApplication(ps, callingUid, userId)) {
                        continue;
                    }
                    ai = PackageInfoUtils.generateApplicationInfo(ps.getPkg(), effectiveFlags,
                            ps.getUserStateOrDefault(userId), userId, ps);
                    if (ai != null) {
                        ai.packageName = resolveExternalPackageName(ps.getPkg());
                    }
                } else {
                    // Shared lib filtering done in generateApplicationInfoFromSettingsLPw
                    // and already converts to externally visible package name
                    ai = generateApplicationInfoFromSettings(ps.getPackageName(),
                            effectiveFlags, callingUid, userId);
                }
                if (ai != null) {
                    list.add(ai);
                }
            }
        } else {
            list = new ArrayList<>(mPackages.size());
            for (PackageStateInternal packageState : packageStates.values()) {
                final AndroidPackage pkg = packageState.getPkg();
                if (pkg == null) {
                    continue;
                }
                if (filterSharedLibPackage(packageState, Binder.getCallingUid(), userId, flags)) {
                    continue;
                }
                if (shouldFilterApplication(packageState, callingUid, userId)) {
                    continue;
                }
                ApplicationInfo ai = PackageInfoUtils.generateApplicationInfo(pkg, flags,
                        packageState.getUserStateOrDefault(userId), userId, packageState);
                if (ai != null) {
                    ai.packageName = resolveExternalPackageName(pkg);
                    list.add(ai);
                }
            }
        }

        return list;
    }

    @Nullable
    @Override
    public ProviderInfo resolveContentProvider(@NonNull String name,
            @PackageManager.ResolveInfoFlagsBits long flags, @UserIdInt int userId,
            int callingUid) {
        if (!mUserManager.exists(userId)) return null;
        flags = updateFlagsForComponent(flags, userId);
        final ProviderInfo providerInfo = mComponentResolver.queryProvider(this, name, flags,
                userId);
        boolean checkedGrants = false;
        if (providerInfo != null) {
            // Looking for cross-user grants before enforcing the typical cross-users permissions
            if (userId != UserHandle.getUserId(callingUid)) {
                final UriGrantsManagerInternal ugmInternal =
                        mInjector.getLocalService(UriGrantsManagerInternal.class);
                checkedGrants =
                        ugmInternal.checkAuthorityGrants(callingUid, providerInfo, userId, true);
            }
        }
        if (!checkedGrants) {
            boolean enforceCrossUser = true;

            if (isAuthorityRedirectedForCloneProfile(name)) {
                final UserManagerInternal umInternal = mInjector.getUserManagerInternal();

                UserInfo userInfo = umInternal.getUserInfo(UserHandle.getUserId(callingUid));
                if (userInfo != null && userInfo.isCloneProfile()
                        && userInfo.profileGroupId == userId) {
                    enforceCrossUser = false;
                }
            }

            if (enforceCrossUser) {
                enforceCrossUserPermission(callingUid, userId, false, false,
                        "resolveContentProvider");
            }
        }

        if (providerInfo == null) {
            return null;
        }
        final PackageStateInternal packageState = getPackageStateInternal(
                providerInfo.packageName);
        if (!PackageStateUtils.isEnabledAndMatches(packageState, providerInfo, flags, userId)) {
            return null;
        }
        final ComponentName component =
                new ComponentName(providerInfo.packageName, providerInfo.name);
        if (shouldFilterApplication(packageState, callingUid, component, TYPE_PROVIDER, userId)) {
            return null;
        }
        return providerInfo;
    }

    @Nullable
    @Override
    public ProviderInfo getGrantImplicitAccessProviderInfo(int recipientUid,
            @NonNull String visibleAuthority) {
        final int callingUid = Binder.getCallingUid();
        final int recipientUserId = UserHandle.getUserId(recipientUid);
        // This API is exposed temporarily to only the contacts provider. (b/158688602)
        ProviderInfo contactsProvider = resolveContentProvider(
                ContactsContract.AUTHORITY, 0, UserHandle.getUserId(callingUid), callingUid);
        if (contactsProvider == null || contactsProvider.applicationInfo == null
                || !UserHandle.isSameApp(contactsProvider.applicationInfo.uid, callingUid)) {
            throw new SecurityException(
                    callingUid + " is not allow to call grantImplicitAccess");
        }
        final long token = Binder.clearCallingIdentity();
        try {
            return resolveContentProvider(visibleAuthority, 0 /*flags*/, recipientUserId,
                    callingUid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Deprecated
    public void querySyncProviders(boolean safeMode, @NonNull List<String> outNames,
            @NonNull List<ProviderInfo> outInfo) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        final List<String> names = new ArrayList<>();
        final List<ProviderInfo> infos = new ArrayList<>();
        final int callingUserId = UserHandle.getCallingUserId();
        mComponentResolver.querySyncProviders(this, names, infos, safeMode, callingUserId);
        for (int i = infos.size() - 1; i >= 0; i--) {
            final ProviderInfo providerInfo = infos.get(i);
            final PackageStateInternal ps = mSettings.getPackage(providerInfo.packageName);
            final ComponentName component =
                    new ComponentName(providerInfo.packageName, providerInfo.name);
            if (!shouldFilterApplication(ps, Binder.getCallingUid(), component,
                    TYPE_PROVIDER, callingUserId)) {
                continue;
            }
            infos.remove(i);
            names.remove(i);
        }
        if (!names.isEmpty()) {
            outNames.addAll(names);
        }
        if (!infos.isEmpty()) {
            outInfo.addAll(infos);
        }
    }

    @NonNull
    @Override
    public ParceledListSlice<ProviderInfo> queryContentProviders(@Nullable String processName,
            int uid, @PackageManager.ComponentInfoFlagsBits long flags,
            @Nullable String metaDataKey) {
        final int callingUid = Binder.getCallingUid();
        final int userId = processName != null ? UserHandle.getUserId(uid)
                : UserHandle.getCallingUserId();
        if (!mUserManager.exists(userId)) return ParceledListSlice.emptyList();
        flags = updateFlagsForComponent(flags, userId);
        ArrayList<ProviderInfo> finalList = null;
        final List<ProviderInfo> matchList = mComponentResolver.queryProviders(this, processName,
                metaDataKey, uid, flags, userId);
        final int listSize = (matchList == null ? 0 : matchList.size());
        for (int i = 0; i < listSize; i++) {
            final ProviderInfo providerInfo = matchList.get(i);
            if (!PackageStateUtils.isEnabledAndMatches(
                    mSettings.getPackage(providerInfo.packageName), providerInfo,
                    flags, userId)) {
                continue;
            }
            final PackageStateInternal ps = mSettings.getPackage(providerInfo.packageName);
            final ComponentName component =
                    new ComponentName(providerInfo.packageName, providerInfo.name);
            if (shouldFilterApplication(
                    ps, callingUid, component, TYPE_PROVIDER, userId)) {
                continue;
            }
            if (finalList == null) {
                finalList = new ArrayList<>(listSize - i);
            }
            finalList.add(providerInfo);
        }

        if (finalList != null) {
            finalList.sort(sProviderInitOrderSorter);
            return new ParceledListSlice<>(finalList);
        }

        return ParceledListSlice.emptyList();
    }

    @Nullable
    @Override
    public InstrumentationInfo getInstrumentationInfo(@NonNull ComponentName component, int flags) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        String packageName = component.getPackageName();
        final PackageStateInternal ps = mSettings.getPackage(packageName);
        AndroidPackage pkg = mPackages.get(packageName);
        if (ps == null || pkg == null) return null;
        if (shouldFilterApplication(
                ps, callingUid, component, TYPE_UNKNOWN, callingUserId)) {
            return null;
        }
        final ParsedInstrumentation i = mInstrumentation.get(component);
        return PackageInfoUtils.generateInstrumentationInfo(i, pkg, flags, callingUserId, ps);
    }

    @NonNull
    @Override
    public ParceledListSlice<InstrumentationInfo> queryInstrumentation(
            @NonNull String targetPackage, int flags) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        final PackageStateInternal ps = mSettings.getPackage(targetPackage);
        if (shouldFilterApplication(ps, callingUid, callingUserId)) {
            return ParceledListSlice.emptyList();
        }

        ArrayList<InstrumentationInfo> finalList = new ArrayList<>();

        final int numInstrumentations = mInstrumentation.size();
        for (int index = 0; index < numInstrumentations; index++) {
            final ParsedInstrumentation p = mInstrumentation.valueAt(index);
            if (targetPackage == null
                    || targetPackage.equals(p.getTargetPackage())) {
                String packageName = p.getPackageName();
                AndroidPackage pkg = mPackages.get(packageName);
                PackageStateInternal pkgSetting = getPackageStateInternal(packageName);
                if (pkg != null) {
                    InstrumentationInfo ii = PackageInfoUtils.generateInstrumentationInfo(p,
                            pkg, flags, callingUserId, pkgSetting);
                    if (ii != null) {
                        finalList.add(ii);
                    }
                }
            }
        }

        return new ParceledListSlice<>(finalList);
    }

    @NonNull
    @Override
    public List<PackageStateInternal> findSharedNonSystemLibraries(
            @NonNull PackageStateInternal pkgSetting) {
        List<SharedLibraryInfo> deps = SharedLibraryUtils.findSharedLibraries(pkgSetting);
        if (!deps.isEmpty()) {
            List<PackageStateInternal> retValue = new ArrayList<>();
            for (SharedLibraryInfo info : deps) {
                PackageStateInternal depPackageSetting =
                        getPackageStateInternal(info.getPackageName());
                if (depPackageSetting != null && depPackageSetting.getPkg() != null) {
                    retValue.add(depPackageSetting);
                }
            }
            return retValue;
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Returns true if application is not found or there was an error. Otherwise it returns
     * the hidden state of the package for the given user.
     */
    @Override
    public boolean getApplicationHiddenSettingAsUser(@NonNull String packageName,
            @UserIdInt int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USERS, null);
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "getApplicationHidden for user " + userId);
        final long callingId = Binder.clearCallingIdentity();
        try {
            PackageStateInternal ps = mSettings.getPackage(packageName);
            if (ps == null) {
                return true;
            }
            if (shouldFilterApplication(ps, callingUid, userId)) {
                return true;
            }
            return ps.getUserStateOrDefault(userId).isHidden();
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public boolean isPackageSuspendedForUser(@NonNull String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "isPackageSuspendedForUser for user " + userId);
        final PackageStateInternal ps = mSettings.getPackage(packageName);
        if (ps == null || shouldFilterApplication(ps, callingUid, userId)) {
            throw new IllegalArgumentException("Unknown target package: " + packageName);
        }
        return ps.getUserStateOrDefault(userId).isSuspended();
    }

    @Override
    public boolean isSuspendingAnyPackages(@NonNull String suspendingPackage,
            @UserIdInt int userId) {
        for (final PackageStateInternal packageState : getPackageStates().values()) {
            final PackageUserStateInternal state = packageState.getUserStateOrDefault(userId);
            if (state.getSuspendParams() != null
                    && state.getSuspendParams().containsKey(suspendingPackage)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    @Override
    public ParceledListSlice<IntentFilter> getAllIntentFilters(@NonNull String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return ParceledListSlice.emptyList();
        }
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        final PackageStateInternal ps = getPackageStateInternal(packageName);
        final AndroidPackage pkg = ps == null ? null : ps.getPkg();
        if (pkg == null || ArrayUtils.isEmpty(pkg.getActivities())) {
            return ParceledListSlice.emptyList();
        }
        if (shouldFilterApplication(ps, callingUid, callingUserId)) {
            return ParceledListSlice.emptyList();
        }
        final int count = ArrayUtils.size(pkg.getActivities());
        ArrayList<IntentFilter> result = new ArrayList<>();
        for (int n=0; n<count; n++) {
            ParsedActivity activity = pkg.getActivities().get(n);
            List<ParsedIntentInfo> intentInfos = activity.getIntents();
            for (int index = 0; index < intentInfos.size(); index++) {
                result.add(new IntentFilter(intentInfos.get(index).getIntentFilter()));
            }
        }
        return new ParceledListSlice<>(result);
    }

    @Override
    public boolean getBlockUninstallForUser(@NonNull String packageName, @UserIdInt int userId) {
            final PackageStateInternal ps = mSettings.getPackage(packageName);
            if (ps == null || shouldFilterApplication(ps, Binder.getCallingUid(), userId)) {
                return false;
            }
            return mSettings.getBlockUninstall(userId, packageName);
    }

    @Nullable
    @Override
    public SparseArray<int[]> getBroadcastAllowList(@NonNull String packageName,
            @UserIdInt int[] userIds, boolean isInstantApp) {
        if (isInstantApp) {
            return null;
        }
        PackageStateInternal setting = getPackageStateInternal(packageName, Process.SYSTEM_UID);
        if (setting == null) {
            return null;
        }
        return mAppsFilter.getVisibilityAllowList(setting, userIds, getPackageStates());
    }

    @Nullable
    @Override
    public String getInstallerPackageName(@NonNull String packageName) {
        final int callingUid = Binder.getCallingUid();
        final InstallSource installSource = getInstallSource(packageName, callingUid);
        if (installSource == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        String installerPackageName = installSource.installerPackageName;
        if (installerPackageName != null) {
            final PackageStateInternal ps = mSettings.getPackage(installerPackageName);
            if (ps == null || shouldFilterApplication(ps, callingUid,
                    UserHandle.getUserId(callingUid))) {
                installerPackageName = null;
            }
        }
        return installerPackageName;
    }

    @Nullable
    private InstallSource getInstallSource(@NonNull String packageName, int callingUid) {
        final PackageStateInternal ps = mSettings.getPackage(packageName);

        // Installer info for Apex is not stored in PackageManager
        if (ps == null && mApexManager.isApexPackage(packageName)) {
            return InstallSource.EMPTY;
        }

        if (ps == null
                || shouldFilterApplication(ps, callingUid, UserHandle.getUserId(callingUid))) {
            return null;
        }

        return ps.getInstallSource();
    }

    @Override
    @Nullable
    public InstallSourceInfo getInstallSourceInfo(@NonNull String packageName) {
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(callingUid);

        String installerPackageName;
        String initiatingPackageName;
        String originatingPackageName;

        final InstallSource installSource = getInstallSource(packageName, callingUid);
        if (installSource == null) {
            return null;
        }

        installerPackageName = installSource.installerPackageName;
        if (installerPackageName != null) {
            final PackageStateInternal ps = mSettings.getPackage(installerPackageName);
            if (ps == null || shouldFilterApplication(ps, callingUid, userId)) {
                installerPackageName = null;
            }
        }

        if (installSource.isInitiatingPackageUninstalled) {
            // We can't check visibility in the usual way, since the initiating package is no
            // longer present. So we apply simpler rules to whether to expose the info:
            // 1. Instant apps can't see it.
            // 2. Otherwise only the installed app itself can see it.
            final boolean isInstantApp = getInstantAppPackageName(callingUid) != null;
            if (!isInstantApp && isCallerSameApp(packageName, callingUid)) {
                initiatingPackageName = installSource.initiatingPackageName;
            } else {
                initiatingPackageName = null;
            }
        } else {
            if (Objects.equals(installSource.initiatingPackageName,
                    installSource.installerPackageName)) {
                // The installer and initiator will often be the same, and when they are
                // we can skip doing the same check again.
                initiatingPackageName = installerPackageName;
            } else {
                initiatingPackageName = installSource.initiatingPackageName;
                final PackageStateInternal ps = mSettings.getPackage(initiatingPackageName);
                if (ps == null || shouldFilterApplication(ps, callingUid, userId)) {
                    initiatingPackageName = null;
                }
            }
        }

        originatingPackageName = installSource.originatingPackageName;
        if (originatingPackageName != null) {
            final PackageStateInternal ps = mSettings.getPackage(originatingPackageName);
            if (ps == null || shouldFilterApplication(ps, callingUid, userId)) {
                originatingPackageName = null;
            }
        }

        // Remaining work can safely be done outside the lock. (Note that installSource is
        // immutable so it's ok to carry on reading from it.)

        if (originatingPackageName != null && mContext.checkCallingOrSelfPermission(
                Manifest.permission.INSTALL_PACKAGES) != PackageManager.PERMISSION_GRANTED) {
            originatingPackageName = null;
        }

        // If you can see the initiatingPackageName, and we have valid signing info for it,
        // then we let you see that too.
        final SigningInfo initiatingPackageSigningInfo;
        final PackageSignatures signatures = installSource.initiatingPackageSignatures;
        if (initiatingPackageName != null && signatures != null
                && signatures.mSigningDetails != SigningDetails.UNKNOWN) {
            initiatingPackageSigningInfo = new SigningInfo(signatures.mSigningDetails);
        } else {
            initiatingPackageSigningInfo = null;
        }

        return new InstallSourceInfo(initiatingPackageName, initiatingPackageSigningInfo,
                originatingPackageName, installerPackageName, installSource.packageSource);
    }

    @PackageManager.EnabledState
    @Override
    public int getApplicationEnabledSetting(@NonNull String packageName, @UserIdInt int userId) {
        if (!mUserManager.exists(userId)) return COMPONENT_ENABLED_STATE_DISABLED;
        int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                false /* checkShell */, "get enabled");
        try {
            if (shouldFilterApplication(
                    mSettings.getPackage(packageName), callingUid, userId)) {
                throw new PackageManager.NameNotFoundException(packageName);
            }
            return mSettings.getApplicationEnabledSetting(packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
    }

    @PackageManager.EnabledState
    @Override
    public int getComponentEnabledSetting(@NonNull ComponentName component, int callingUid,
            @UserIdInt int userId) {
        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "getComponentEnabled");
        return getComponentEnabledSettingInternal(component, callingUid, userId);
    }

    @PackageManager.EnabledState
    @Override
    public int getComponentEnabledSettingInternal(@NonNull ComponentName component, int callingUid,
            @UserIdInt int userId) {
        if (component == null) return COMPONENT_ENABLED_STATE_DEFAULT;
        if (!mUserManager.exists(userId)) return COMPONENT_ENABLED_STATE_DISABLED;

        try {
            if (shouldFilterApplication(
                    mSettings.getPackage(component.getPackageName()), callingUid,
                    component, TYPE_UNKNOWN, userId)) {
                throw new PackageManager.NameNotFoundException(component.getPackageName());
            }
            return mSettings.getComponentEnabledSetting(component, userId);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Unknown component: " + component);
        }
    }

    @Override
    public boolean isComponentEffectivelyEnabled(@NonNull ComponentInfo componentInfo,
            @UserIdInt int userId) {
        try {
            String packageName = componentInfo.packageName;
            int appEnabledSetting =
                    mSettings.getApplicationEnabledSetting(packageName, userId);
            if (appEnabledSetting == COMPONENT_ENABLED_STATE_DEFAULT) {
                if (!componentInfo.applicationInfo.enabled) {
                    return false;
                }
            } else if (appEnabledSetting != COMPONENT_ENABLED_STATE_ENABLED) {
                return false;
            }

            int componentEnabledSetting = mSettings.getComponentEnabledSetting(
                    componentInfo.getComponentName(), userId);
            if (componentEnabledSetting == COMPONENT_ENABLED_STATE_DEFAULT) {
                return componentInfo.isEnabled();
            } else return componentEnabledSetting == COMPONENT_ENABLED_STATE_ENABLED;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    @Nullable
    @Override
    public KeySet getKeySetByAlias(@NonNull String packageName, @NonNull String alias) {
        if (packageName == null || alias == null) {
            return null;
        }
        final AndroidPackage pkg = mPackages.get(packageName);
        if (pkg == null
                || shouldFilterApplication(getPackageStateInternal(pkg.getPackageName()),
                Binder.getCallingUid(), UserHandle.getCallingUserId())) {
            Slog.w(TAG, "KeySet requested for unknown package: " + packageName);
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        final KeySetManagerService ksms = mSettings.getKeySetManagerService();
        return new KeySet(ksms.getKeySetByAliasAndPackageNameLPr(packageName, alias));
    }

    @Nullable
    @Override
    public KeySet getSigningKeySet(@NonNull String packageName) {
        if (packageName == null) {
            return null;
        }
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        final AndroidPackage pkg = mPackages.get(packageName);
        if (pkg == null
                || shouldFilterApplication(getPackageStateInternal(pkg.getPackageName()),
                callingUid, callingUserId)) {
            Slog.w(TAG, "KeySet requested for unknown package: " + packageName
                    + ", uid:" + callingUid);
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        if (pkg.getUid() != callingUid
                && Process.SYSTEM_UID != callingUid) {
            throw new SecurityException("May not access signing KeySet of other apps.");
        }
        final KeySetManagerService ksms = mSettings.getKeySetManagerService();
        return new KeySet(ksms.getSigningKeySetByPackageNameLPr(packageName));
    }

    @Override
    public boolean isPackageSignedByKeySet(@NonNull String packageName, @NonNull KeySet ks) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return false;
        }
        if (packageName == null || ks == null) {
            return false;
        }
        final AndroidPackage pkg = mPackages.get(packageName);
        if (pkg == null
                || shouldFilterApplication(getPackageStateInternal(pkg.getPackageName()),
                callingUid, UserHandle.getUserId(callingUid))) {
            Slog.w(TAG, "KeySet requested for unknown package: " + packageName);
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        IBinder ksh = ks.getToken();
        if (ksh instanceof KeySetHandle) {
            final KeySetManagerService ksms = mSettings.getKeySetManagerService();
            return ksms.packageIsSignedByLPr(packageName, (KeySetHandle) ksh);
        }
        return false;
    }

    @Override
    public boolean isPackageSignedByKeySetExactly(@NonNull String packageName, @NonNull KeySet ks) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return false;
        }
        if (packageName == null || ks == null) {
            return false;
        }
        final AndroidPackage pkg = mPackages.get(packageName);
        if (pkg == null
                || shouldFilterApplication(getPackageStateInternal(pkg.getPackageName()),
                callingUid, UserHandle.getUserId(callingUid))) {
            Slog.w(TAG, "KeySet requested for unknown package: " + packageName);
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        IBinder ksh = ks.getToken();
        if (ksh instanceof KeySetHandle) {
            final KeySetManagerService ksms = mSettings.getKeySetManagerService();
            return ksms.packageIsSignedByExactlyLPr(packageName, (KeySetHandle) ksh);
        }
        return false;
    }

    @Nullable
    @Override
    public int[] getVisibilityAllowList(@NonNull String packageName, @UserIdInt int userId) {
        final PackageStateInternal ps =
                getPackageStateInternal(packageName, Process.SYSTEM_UID);
        if (ps == null) {
            return null;
        }
        final SparseArray<int[]> visibilityAllowList = mAppsFilter.getVisibilityAllowList(ps,
                new int[]{userId}, getPackageStates());
        return visibilityAllowList != null ? visibilityAllowList.get(userId) : null;
    }

    @Override
    public boolean canQueryPackage(int callingUid, @Nullable String targetPackageName) {
        // Since getSettingLPr returns null for ROOT_UID, add an extra check for it here.
        if (callingUid == Process.ROOT_UID || targetPackageName == null) {
            return true;
        }
        final Object setting = mSettings.getSettingBase(UserHandle.getAppId(callingUid));
        if (setting == null) {
            return false;
        }

        final int userId = UserHandle.getUserId(callingUid);
        final int targetAppId = UserHandle.getAppId(
                getPackageUid(targetPackageName, 0 /* flags */, userId));
        // For update or already installed case, leverage the existing visibility rule.
        if (targetAppId != Process.INVALID_UID) {
            final Object targetSetting = mSettings.getSettingBase(targetAppId);
            if (targetSetting instanceof PackageSetting) {
                return !shouldFilterApplication(
                        (PackageSetting) targetSetting, callingUid, userId);
            } else {
                return !shouldFilterApplication(
                        (SharedUserSetting) targetSetting, callingUid, userId);
            }
        }

        // For new installing case, check if caller declares <queries> element with the
        // target package name or has proper permission.
        if (setting instanceof PackageSetting) {
            final AndroidPackage pkg = ((PackageSetting) setting).getPkg();
            return pkg != null && mAppsFilter.canQueryPackage(pkg, targetPackageName);
        } else {
            final ArraySet<PackageStateInternal> callingSharedPkgSettings =
                    (ArraySet<PackageStateInternal>)
                            ((SharedUserSetting) setting).getPackageStates();
            for (int i = callingSharedPkgSettings.size() - 1; i >= 0; i--) {
                final AndroidPackage pkg = callingSharedPkgSettings.valueAt(i).getPkg();
                if (pkg != null && mAppsFilter.canQueryPackage(pkg, targetPackageName)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public int getPackageUid(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId) {
        if (!mUserManager.exists(userId)) return -1;
        final int callingUid = Binder.getCallingUid();
        flags = updateFlagsForPackage(flags, userId);
        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "getPackageUid");
        return getPackageUidInternal(packageName, flags, userId, callingUid);
    }

    @Override
    public boolean canAccessComponent(int callingUid, @NonNull ComponentName component,
            @UserIdInt int userId) {
        final PackageStateInternal packageState =
                getPackageStateInternal(component.getPackageName());
        return packageState != null && !shouldFilterApplication(packageState, callingUid,
                component, TYPE_UNKNOWN, userId);
    }

    @Override
    public boolean isCallerInstallerOfRecord(@NonNull AndroidPackage pkg, int callingUid) {
        if (pkg == null) {
            return false;
        }
        final PackageStateInternal packageState = getPackageStateInternal(pkg.getPackageName());
        if (packageState == null) {
            return false;
        }

        final PackageStateInternal installerPackageState = getPackageStateInternal(
                packageState.getInstallSource().installerPackageName);
        return installerPackageState != null
                && UserHandle.isSameApp(installerPackageState.getAppId(), callingUid);
    }

    @PackageManager.InstallReason
    @Override
    public int getInstallReason(@NonNull String packageName, @UserIdInt int userId) {
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "get install reason");
        final PackageStateInternal ps = mSettings.getPackage(packageName);
        if (shouldFilterApplication(ps, callingUid, userId)) {
            return PackageManager.INSTALL_REASON_UNKNOWN;
        }
        if (ps != null) {
            return ps.getUserStateOrDefault(userId).getInstallReason();
        }
        return PackageManager.INSTALL_REASON_UNKNOWN;
    }

    @Override
    public boolean canPackageQuery(@NonNull String sourcePackageName,
            @NonNull String targetPackageName, @UserIdInt int userId) {
        if (!mUserManager.exists(userId)) return false;
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "may package query");
        final PackageStateInternal sourceSetting = getPackageStateInternal(sourcePackageName);
        final PackageStateInternal targetSetting = getPackageStateInternal(targetPackageName);
        if (sourceSetting == null || targetSetting == null) {
            throw new ParcelableException(new PackageManager.NameNotFoundException("Package(s) "
                    + (sourceSetting == null ? sourcePackageName + " " : "")
                    + (targetSetting == null ? targetPackageName + " " : "")
                    + "not found."));
        }
        final boolean filterSource =
                shouldFilterApplication(sourceSetting, callingUid, userId);
        final boolean filterTarget =
                shouldFilterApplication(targetSetting, callingUid, userId);
        // The caller must have visibility of the both packages
        if (filterSource || filterTarget) {
            throw new ParcelableException(new PackageManager.NameNotFoundException("Package(s) "
                    + (filterSource ? sourcePackageName + " " : "")
                    + (filterTarget ? targetPackageName + " " : "")
                    + "not found."));
        }
        final int sourcePackageUid = UserHandle.getUid(userId, sourceSetting.getAppId());
        return !shouldFilterApplication(targetSetting, sourcePackageUid, userId);
    }

    /*
     * Returns if intent can be forwarded from the sourceUserId to the targetUserId
     */
    @Override
    public boolean canForwardTo(@NonNull Intent intent, @Nullable String resolvedType,
            @UserIdInt int sourceUserId, @UserIdInt int targetUserId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        List<CrossProfileIntentFilter> matches =
                getMatchingCrossProfileIntentFilters(intent, resolvedType, sourceUserId);
        if (matches != null) {
            int size = matches.size();
            for (int i = 0; i < size; i++) {
                if (matches.get(i).getTargetUserId() == targetUserId) return true;
            }
        }
        if (intent.hasWebURI()) {
            // cross-profile app linking works only towards the parent.
            final int callingUid = Binder.getCallingUid();
            final UserInfo parent = getProfileParent(sourceUserId);
            if (parent == null) {
                return false;
            }
            long flags = updateFlagsForResolve(0, parent.id, callingUid,
                    false /*includeInstantApps*/,
                    isImplicitImageCaptureIntentAndNotSetByDpc(intent, parent.id,
                            resolvedType, 0));
            flags |= PackageManager.MATCH_DEFAULT_ONLY;
            CrossProfileDomainInfo xpDomainInfo = getCrossProfileDomainPreferredLpr(
                    intent, resolvedType, flags, sourceUserId, parent.id);
            return xpDomainInfo != null;
        }
        return false;
    }

    @NonNull
    @Override
    public List<ApplicationInfo> getPersistentApplications(boolean safeMode, int flags) {
        final ArrayList<ApplicationInfo> finalList = new ArrayList<>();

        final int numPackages = mPackages.size();
        final int userId = UserHandle.getCallingUserId();
        for (int index = 0; index < numPackages; index++) {
            final AndroidPackage p = mPackages.valueAt(index);

            final boolean matchesUnaware = ((flags & MATCH_DIRECT_BOOT_UNAWARE) != 0)
                    && !p.isDirectBootAware();
            final boolean matchesAware = ((flags & MATCH_DIRECT_BOOT_AWARE) != 0)
                    && p.isDirectBootAware();

            if (p.isPersistent()
                    && (!safeMode || p.isSystem())
                    && (matchesUnaware || matchesAware)) {
                PackageStateInternal ps = mSettings.getPackage(p.getPackageName());
                if (ps != null) {
                    ApplicationInfo ai = PackageInfoUtils.generateApplicationInfo(p, flags,
                            ps.getUserStateOrDefault(userId), userId, ps);
                    if (ai != null) {
                        finalList.add(ai);
                    }
                }
            }
        }

        return finalList;
    }

    @NonNull
    @Override
    public SparseArray<String> getAppsWithSharedUserIds() {
        final SparseArray<String> sharedUserIds = new SparseArray<>();
        for (SharedUserSetting setting : mSettings.getAllSharedUsers()) {
            sharedUserIds.put(UserHandle.getAppId(setting.mAppId), setting.name);
        }
        return sharedUserIds;
    }

    @NonNull
    @Override
    public String[] getSharedUserPackagesForPackage(@NonNull String packageName,
            @UserIdInt int userId) {
        final PackageStateInternal packageSetting = mSettings.getPackage(packageName);
        if (packageSetting == null || mSettings.getSharedUserFromPackageName(packageName) == null) {
            return EmptyArray.STRING;
        }

        ArraySet<? extends PackageStateInternal> packages =
                mSettings.getSharedUserFromPackageName(packageName).getPackageStates();
        final int numPackages = packages.size();
        String[] res = new String[numPackages];
        int i = 0;
        for (int index = 0; index < numPackages; index++) {
            final PackageStateInternal ps = packages.valueAt(index);
            if (ps.getUserStateOrDefault(userId).isInstalled()) {
                res[i++] = ps.getPackageName();
            }
        }
        res = ArrayUtils.trimToSize(res, i);
        return res != null ? res : EmptyArray.STRING;
    }


    @NonNull
    @Override
    public Set<String> getUnusedPackages(long downgradeTimeThresholdMillis) {
        Set<String> unusedPackages = new ArraySet<>();
        long currentTimeInMillis = System.currentTimeMillis();
        final ArrayMap<String, ? extends PackageStateInternal> packageStates =
                mSettings.getPackages();
        for (int index = 0; index < packageStates.size(); index++) {
            final PackageStateInternal packageState = packageStates.valueAt(index);
            if (packageState.getPkg() == null) {
                continue;
            }
            PackageDexUsage.PackageUseInfo packageUseInfo =
                    mDexManager.getPackageUseInfoOrDefault(packageState.getPackageName());
            if (PackageManagerServiceUtils.isUnusedSinceTimeInMillis(
                    PackageStateUtils.getEarliestFirstInstallTime(packageState.getUserStates()),
                    currentTimeInMillis, downgradeTimeThresholdMillis, packageUseInfo,
                    packageState.getTransientState().getLatestPackageUseTimeInMills(),
                    packageState.getTransientState().getLatestForegroundPackageUseTimeInMills())) {
                unusedPackages.add(packageState.getPackageName());
            }
        }
        return unusedPackages;
    }

    @Nullable
    @Override
    public CharSequence getHarmfulAppWarning(@NonNull String packageName, @UserIdInt int userId) {
        final int callingUid = Binder.getCallingUid();
        final int callingAppId = UserHandle.getAppId(callingUid);

        enforceCrossUserPermission(callingUid, userId, true /*requireFullPermission*/,
                true /*checkShell*/, "getHarmfulAppInfo");

        if (callingAppId != Process.SYSTEM_UID && callingAppId != Process.ROOT_UID &&
                checkUidPermission(SET_HARMFUL_APP_WARNINGS, callingUid) != PERMISSION_GRANTED) {
            throw new SecurityException("Caller must have the "
                    + SET_HARMFUL_APP_WARNINGS + " permission.");
        }

        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        return packageState.getUserStateOrDefault(userId).getHarmfulAppWarning();
    }

    /**
     * Only keep package names that refer to {@link AndroidPackage#isSystem system} packages.
     *
     * @param pkgNames The packages to filter
     *
     * @return The filtered packages
     */
    @NonNull
    @Override
    public String[] filterOnlySystemPackages(@Nullable String... pkgNames) {
        if (pkgNames == null) {
            return ArrayUtils.emptyArray(String.class);
        }

        ArrayList<String> systemPackageNames = new ArrayList<>(pkgNames.length);

        for (String pkgName: pkgNames) {
            if (pkgName == null) {
                continue;
            }

            AndroidPackage pkg = getPackage(pkgName);
            if (pkg == null) {
                Log.w(TAG, "Could not find package " + pkgName);
                continue;
            }

            if (!pkg.isSystem()) {
                Log.w(TAG, pkgName + " is not system");
                continue;
            }

            systemPackageNames.add(pkgName);
        }

        return systemPackageNames.toArray(new String[]{});
    }

    @NonNull
    @Override
    public List<AndroidPackage> getPackagesForAppId(int appId) {
        final SettingBase settingBase = mSettings.getSettingBase(appId);
        if (settingBase instanceof SharedUserSetting) {
            final SharedUserSetting sus = (SharedUserSetting) settingBase;
            return sus.getPackages();
        } else if (settingBase instanceof PackageSetting) {
            final PackageSetting ps = (PackageSetting) settingBase;
            return List.of(ps.getPkg());
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    public int getUidTargetSdkVersion(int uid) {
        if (Process.isSdkSandboxUid(uid)) {
            uid = getBaseSdkSandboxUid();
        }
        final int appId = UserHandle.getAppId(uid);
        final SettingBase settingBase = mSettings.getSettingBase(appId);
        if (settingBase instanceof SharedUserSetting) {
            final SharedUserSetting sus = (SharedUserSetting) settingBase;
            final ArraySet<PackageStateInternal> packageStates =
                    (ArraySet<PackageStateInternal>) sus.getPackageStates();
            int vers = Build.VERSION_CODES.CUR_DEVELOPMENT;
            final int numPackages = packageStates.size();
            for (int index = 0; index < numPackages; index++) {
                final PackageStateInternal ps = packageStates.valueAt(index);
                if (ps.getPkg() != null) {
                    int v = ps.getPkg().getTargetSdkVersion();
                    if (v < vers) vers = v;
                }
            }
            return vers;
        } else if (settingBase instanceof PackageSetting) {
            final PackageSetting ps = (PackageSetting) settingBase;
            if (ps.getPkg() != null) {
                return ps.getPkg().getTargetSdkVersion();
            }
        }
        return Build.VERSION_CODES.CUR_DEVELOPMENT;
    }

    @Nullable
    @Override
    public ArrayMap<String, ProcessInfo> getProcessesForUid(int uid) {
        if (Process.isSdkSandboxUid(uid)) {
            uid = getBaseSdkSandboxUid();
        }
        final int appId = UserHandle.getAppId(uid);
        final SettingBase settingBase = mSettings.getSettingBase(appId);
        if (settingBase instanceof SharedUserSetting) {
            final SharedUserSetting sus = (SharedUserSetting) settingBase;
            return PackageInfoUtils.generateProcessInfo(sus.processes, 0);
        } else if (settingBase instanceof PackageSetting) {
            final PackageSetting ps = (PackageSetting) settingBase;
            return PackageInfoUtils.generateProcessInfo(ps.getPkg().getProcesses(), 0);
        }
        return null;
    }

    @Override
    public boolean getBlockUninstall(@UserIdInt int userId, @NonNull String packageName) {
        return mSettings.getBlockUninstall(userId, packageName);
    }

    @Nullable
    @Override
    public Pair<PackageStateInternal, SharedUserApi> getPackageOrSharedUser(int appId) {
        final SettingBase settingBase = mSettings.getSettingBase(appId);
        if (settingBase instanceof SharedUserSetting) {
            return Pair.create(null, (SharedUserApi) settingBase);
        } else if (settingBase instanceof PackageSetting) {
            return Pair.create((PackageStateInternal) settingBase, null);
        } else {
            return null;
        }
    }

    private int getBaseSdkSandboxUid() {
        return getPackage(mService.getSdkSandboxPackageName()).getUid();
    }

    @Nullable
    @Override
    public SharedUserApi getSharedUser(int sharedUserAppId) {
        return mSettings.getSharedUserFromAppId(sharedUserAppId);
    }

    @NonNull
    @Override
    public ArraySet<PackageStateInternal> getSharedUserPackages(int sharedUserAppId) {
        return mSettings.getSharedUserPackages(sharedUserAppId);
    }

    @NonNull
    @Override
    public ComponentResolverApi getComponentResolver() {
        return mComponentResolver;
    }

    @Nullable
    @Override
    public PackageStateInternal getDisabledSystemPackage(@NonNull String packageName) {
        return mSettings.getDisabledSystemPkg(packageName);
    }

    @Nullable
    @Override
    public ResolveInfo getInstantAppInstallerInfo() {
        return mInstantAppInstallerInfo;
    }

    @NonNull
    @Override
    public WatchedArrayMap<String, Integer> getFrozenPackages() {
        return mFrozenPackages;
    }

    @Override
    public void checkPackageFrozen(@NonNull String packageName) {
        if (!mFrozenPackages.containsKey(packageName)) {
            Slog.wtf(TAG, "Expected " + packageName + " to be frozen!", new Throwable());
        }
    }

    @Nullable
    @Override
    public ComponentName getInstantAppInstallerComponent() {
        return mLocalInstantAppInstallerActivity == null
                ? null : mLocalInstantAppInstallerActivity.getComponentName();
    }

    @Override
    public void dumpPermissions(@NonNull PrintWriter pw, @NonNull String packageName,
            @NonNull ArraySet<String> permissionNames, @NonNull DumpState dumpState) {
        mSettings.dumpPermissions(pw, packageName, permissionNames, dumpState);
    }

    @Override
    public void dumpPackages(@NonNull PrintWriter pw, @NonNull String packageName,
            @NonNull ArraySet<String> permissionNames, @NonNull DumpState dumpState,
            boolean checkin) {
        mSettings.dumpPackages(pw, packageName, permissionNames, dumpState, checkin);
    }

    @Override
    public void dumpKeySet(@NonNull PrintWriter pw, @NonNull String packageName,
            @NonNull DumpState dumpState) {
        mSettings.dumpKeySet(pw, packageName, dumpState);
    }

    @Override
    public void dumpSharedUsers(@NonNull PrintWriter pw, @NonNull String packageName,
            @NonNull ArraySet<String> permissionNames, @NonNull DumpState dumpState,
            boolean checkin) {
        mSettings.dumpSharedUsers(pw, packageName, permissionNames, dumpState, checkin);
    }

    @Override
    public void dumpSharedUsersProto(@NonNull ProtoOutputStream proto) {
        mSettings.dumpSharedUsersProto(proto);
    }

    @Override
    public void dumpPackagesProto(@NonNull ProtoOutputStream proto) {
        mSettings.dumpPackagesProto(proto);
    }

    @Override
    public void dumpSharedLibrariesProto(@NonNull ProtoOutputStream proto) {
        mSharedLibraries.dumpProto(proto);
    }
}
