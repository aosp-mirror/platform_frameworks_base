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

import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import static com.android.server.pm.PackageManagerService.DEBUG_INSTANT;
import static com.android.server.pm.PackageManagerService.DEBUG_INTENT_MATCHING;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.app.ResolverActivity;
import com.android.internal.util.ArrayUtils;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.resolution.ComponentResolverApi;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

final class ResolveIntentHelper {
    @NonNull
    private final Context mContext;
    @NonNull
    private final PlatformCompat mPlatformCompat;
    @NonNull
    private final UserManagerService mUserManager;
    @NonNull
    private final PreferredActivityHelper mPreferredActivityHelper;
    @NonNull
    private final DomainVerificationManagerInternal mDomainVerificationManager;
    @NonNull
    private final UserNeedsBadgingCache mUserNeedsBadging;
    @NonNull
    private final Supplier<ResolveInfo> mResolveInfoSupplier;
    @NonNull
    private final Supplier<ActivityInfo> mInstantAppInstallerActivitySupplier;

    ResolveIntentHelper(@NonNull Context context,
            @NonNull PreferredActivityHelper preferredActivityHelper,
            @NonNull PlatformCompat platformCompat, @NonNull UserManagerService userManager,
            @NonNull DomainVerificationManagerInternal domainVerificationManager,
            @NonNull UserNeedsBadgingCache userNeedsBadgingCache,
            @NonNull Supplier<ResolveInfo> resolveInfoSupplier,
            @NonNull Supplier<ActivityInfo> instantAppInstallerActivitySupplier) {
        mContext = context;
        mPreferredActivityHelper = preferredActivityHelper;
        mPlatformCompat = platformCompat;
        mUserManager = userManager;
        mDomainVerificationManager = domainVerificationManager;
        mUserNeedsBadging = userNeedsBadgingCache;
        mResolveInfoSupplier = resolveInfoSupplier;
        mInstantAppInstallerActivitySupplier = instantAppInstallerActivitySupplier;
    }

    /**
     * Normally instant apps can only be resolved when they're visible to the caller.
     * However, if {@code resolveForStart} is {@code true}, all instant apps are visible
     * since we need to allow the system to start any installed application.
     */
    public ResolveInfo resolveIntentInternal(Computer computer, Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags,
            @PackageManagerInternal.PrivateResolveFlags long privateResolveFlags, int userId,
            boolean resolveForStart, int filterCallingUid) {
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "resolveIntent");

            if (!mUserManager.exists(userId)) return null;
            final int callingUid = Binder.getCallingUid();
            flags = computer.updateFlagsForResolve(flags, userId, filterCallingUid, resolveForStart,
                    computer.isImplicitImageCaptureIntentAndNotSetByDpc(intent, userId,
                            resolvedType, flags));
            computer.enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                    false /*checkShell*/, "resolve intent");

            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "queryIntentActivities");
            final List<ResolveInfo> query = computer.queryIntentActivitiesInternal(intent,
                    resolvedType, flags, privateResolveFlags, filterCallingUid, userId,
                    resolveForStart, true /*allowDynamicSplits*/);
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);

            final boolean queryMayBeFiltered =
                    UserHandle.getAppId(filterCallingUid) >= Process.FIRST_APPLICATION_UID
                            && !resolveForStart;

            final ResolveInfo bestChoice = chooseBestActivity(computer, intent, resolvedType, flags,
                    privateResolveFlags, query, userId, queryMayBeFiltered);
            final boolean nonBrowserOnly =
                    (privateResolveFlags & PackageManagerInternal.RESOLVE_NON_BROWSER_ONLY) != 0;
            if (nonBrowserOnly && bestChoice != null && bestChoice.handleAllWebDataURI) {
                return null;
            }
            return bestChoice;
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private ResolveInfo chooseBestActivity(Computer computer, Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags,
            @PackageManagerInternal.PrivateResolveFlags long privateResolveFlags,
            List<ResolveInfo> query, int userId, boolean queryMayBeFiltered) {
        if (query != null) {
            final int n = query.size();
            if (n == 1) {
                return query.get(0);
            } else if (n > 1) {
                final boolean debug = ((intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0);
                // If there is more than one activity with the same priority,
                // then let the user decide between them.
                ResolveInfo r0 = query.get(0);
                ResolveInfo r1 = query.get(1);
                if (DEBUG_INTENT_MATCHING || debug) {
                    Slog.v(TAG, r0.activityInfo.name + "=" + r0.priority + " vs "
                            + r1.activityInfo.name + "=" + r1.priority);
                }
                // If the first activity has a higher priority, or a different
                // default, then it is always desirable to pick it.
                if (r0.priority != r1.priority
                        || r0.preferredOrder != r1.preferredOrder
                        || r0.isDefault != r1.isDefault) {
                    return query.get(0);
                }
                // If we have saved a preference for a preferred activity for
                // this Intent, use that.
                ResolveInfo ri = mPreferredActivityHelper.findPreferredActivityNotLocked(computer,
                        intent, resolvedType, flags, query, true, false, debug,
                        userId, queryMayBeFiltered);
                if (ri != null) {
                    return ri;
                }
                int browserCount = 0;
                for (int i = 0; i < n; i++) {
                    ri = query.get(i);
                    if (ri.handleAllWebDataURI) {
                        browserCount++;
                    }
                    // If we have an ephemeral app, use it
                    if (ri.activityInfo.applicationInfo.isInstantApp()) {
                        final String packageName = ri.activityInfo.packageName;
                        final PackageStateInternal ps =
                                computer.getPackageStateInternal(packageName);
                        if (ps != null && PackageManagerServiceUtils.hasAnyDomainApproval(
                                mDomainVerificationManager, ps, intent, flags, userId)) {
                            return ri;
                        }
                    }
                }
                if ((privateResolveFlags
                        & PackageManagerInternal.RESOLVE_NON_RESOLVER_ONLY) != 0) {
                    return null;
                }
                ri = new ResolveInfo(mResolveInfoSupplier.get());
                // if all resolve options are browsers, mark the resolver's info as if it were
                // also a browser.
                ri.handleAllWebDataURI = browserCount == n;
                ri.activityInfo = new ActivityInfo(ri.activityInfo);
                ri.activityInfo.labelRes = ResolverActivity.getLabelRes(intent.getAction());
                // If all of the options come from the same package, show the application's
                // label and icon instead of the generic resolver's.
                // Some calls like Intent.resolveActivityInfo query the ResolveInfo from here
                // and then throw away the ResolveInfo itself, meaning that the caller loses
                // the resolvePackageName. Therefore the activityInfo.labelRes above provides
                // a fallback for this case; we only set the target package's resources on
                // the ResolveInfo, not the ActivityInfo.
                final String intentPackage = intent.getPackage();
                if (!TextUtils.isEmpty(intentPackage) && allHavePackage(query, intentPackage)) {
                    final ApplicationInfo appi = query.get(0).activityInfo.applicationInfo;
                    ri.resolvePackageName = intentPackage;
                    if (mUserNeedsBadging.get(userId)) {
                        ri.noResourceId = true;
                    } else {
                        ri.icon = appi.icon;
                    }
                    ri.iconResourceId = appi.icon;
                    ri.labelRes = appi.labelRes;
                }
                ri.activityInfo.applicationInfo = new ApplicationInfo(
                        ri.activityInfo.applicationInfo);
                if (userId != 0) {
                    ri.activityInfo.applicationInfo.uid = UserHandle.getUid(userId,
                            UserHandle.getAppId(ri.activityInfo.applicationInfo.uid));
                }
                // Make sure that the resolver is displayable in car mode
                if (ri.activityInfo.metaData == null) ri.activityInfo.metaData = new Bundle();
                ri.activityInfo.metaData.putBoolean(Intent.METADATA_DOCK_HOME, true);
                return ri;
            }
        }
        return null;
    }

    /**
     * Return true if the given list is not empty and all of its contents have
     * an activityInfo with the given package name.
     */
    private boolean allHavePackage(List<ResolveInfo> list, String packageName) {
        if (ArrayUtils.isEmpty(list)) {
            return false;
        }
        for (int i = 0, n = list.size(); i < n; i++) {
            final ResolveInfo ri = list.get(i);
            final ActivityInfo ai = ri != null ? ri.activityInfo : null;
            if (ai == null || !packageName.equals(ai.packageName)) {
                return false;
            }
        }
        return true;
    }

    public IntentSender getLaunchIntentSenderForPackage(@NonNull Computer computer,
            String packageName, String callingPackage, String featureId, int userId)
            throws RemoteException {
        Objects.requireNonNull(packageName);
        final int callingUid = Binder.getCallingUid();
        computer.enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                false /* checkShell */, "get launch intent sender for package");
        final int packageUid = computer.getPackageUid(callingPackage, 0 /* flags */, userId);
        if (!UserHandle.isSameApp(callingUid, packageUid)) {
            throw new SecurityException("getLaunchIntentSenderForPackage() from calling uid: "
                    + callingUid + " does not own package: " + callingPackage);
        }

        // Using the same implementation with the #getLaunchIntentForPackage to get the ResolveInfo.
        // Pass the resolveForStart as true in queryIntentActivities to skip the app filtering.
        final Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_INFO);
        intentToResolve.setPackage(packageName);
        final ContentResolver contentResolver = mContext.getContentResolver();
        String resolvedType = intentToResolve.resolveTypeIfNeeded(contentResolver);
        List<ResolveInfo> ris = computer.queryIntentActivitiesInternal(intentToResolve, resolvedType,
                0 /* flags */, 0 /* privateResolveFlags */, callingUid, userId,
                true /* resolveForStart */, false /* allowDynamicSplits */);
        if (ris == null || ris.size() <= 0) {
            intentToResolve.removeCategory(Intent.CATEGORY_INFO);
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
            intentToResolve.setPackage(packageName);
            resolvedType = intentToResolve.resolveTypeIfNeeded(contentResolver);
            ris = computer.queryIntentActivitiesInternal(intentToResolve, resolvedType,
                    0 /* flags */, 0 /* privateResolveFlags */, callingUid, userId,
                    true /* resolveForStart */, false /* allowDynamicSplits */);
        }

        final Intent intent = new Intent(intentToResolve);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // For the case of empty result, no component name is assigned into the intent. A
        // non-launchable IntentSender which contains the failed intent is created. The
        // SendIntentException is thrown if the IntentSender#sendIntent is invoked.
        if (ris != null && !ris.isEmpty()) {
            // am#isIntentSenderTargetedToPackage returns false if both package name and component
            // name are set in the intent. Clear the package name to have the api return true and
            // prevent the package existence info from side channel leaks by the api.
            intent.setPackage(null);
            intent.setClassName(ris.get(0).activityInfo.packageName,
                    ris.get(0).activityInfo.name);
        }
        final IIntentSender target = ActivityManager.getService().getIntentSenderWithFeature(
                ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                featureId, null /* token */, null /* resultWho */,
                1 /* requestCode */, new Intent[]{intent},
                resolvedType != null ? new String[]{resolvedType} : null,
                PendingIntent.FLAG_IMMUTABLE, null /* bOptions */, userId);
        return new IntentSender(target);
    }

    /**
     * Retrieve all receivers that can handle a broadcast of the given intent.
     * @param queryingUid the results will be filtered in the context of this UID instead.
     */
    @NonNull
    public List<ResolveInfo> queryIntentReceiversInternal(Computer computer, Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId,
            int queryingUid) {
        return queryIntentReceiversInternal(computer, intent, resolvedType, flags, userId,
                queryingUid, false);
    }

    /**
     * @see PackageManagerInternal#queryIntentReceivers(Intent, String, long, int, int, boolean)
     */
    @NonNull
    public List<ResolveInfo> queryIntentReceiversInternal(Computer computer, Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId,
            int filterCallingUid, boolean forSend) {
        if (!mUserManager.exists(userId)) return Collections.emptyList();
        // The identity used to filter the receiver components
        final int queryingUid = forSend ? Process.SYSTEM_UID : filterCallingUid;
        computer.enforceCrossUserPermission(queryingUid, userId,
                false /*requireFullPermission*/, false /*checkShell*/, "query intent receivers");
        final String instantAppPkgName = computer.getInstantAppPackageName(queryingUid);
        flags = computer.updateFlagsForResolve(flags, userId, queryingUid,
                false /*includeInstantApps*/,
                computer.isImplicitImageCaptureIntentAndNotSetByDpc(intent, userId,
                        resolvedType, flags));
        Intent originalIntent = null;
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                originalIntent = intent;
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        final ComponentResolverApi componentResolver = computer.getComponentResolver();
        List<ResolveInfo> list = Collections.emptyList();
        if (comp != null) {
            final ActivityInfo ai = computer.getReceiverInfo(comp, flags, userId);
            if (ai != null) {
                // When specifying an explicit component, we prevent the activity from being
                // used when either 1) the calling package is normal and the activity is within
                // an instant application or 2) the calling package is ephemeral and the
                // activity is not visible to instant applications.
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
                final boolean isTargetExplicitlyVisibleToInstantApp = isTargetVisibleToInstantApp
                        && (ai.flags & ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP) == 0;
                final boolean isTargetHiddenFromInstantApp = !isTargetVisibleToInstantApp
                        || (matchExplicitlyVisibleOnly && !isTargetExplicitlyVisibleToInstantApp);
                final boolean blockResolution =
                        !isTargetSameInstantApp
                                && ((!matchInstantApp && !isCallerInstantApp && isTargetInstantApp)
                                || (matchVisibleToInstantAppOnly && isCallerInstantApp
                                && isTargetHiddenFromInstantApp));
                if (!blockResolution) {
                    ResolveInfo ri = new ResolveInfo();
                    ri.activityInfo = ai;
                    list = new ArrayList<>(1);
                    list.add(ri);
                    PackageManagerServiceUtils.applyEnforceIntentFilterMatching(
                            mPlatformCompat, componentResolver, list, true, intent,
                            resolvedType, filterCallingUid);
                }
            }
        } else {
            String pkgName = intent.getPackage();
            if (pkgName == null) {
                final List<ResolveInfo> result = componentResolver
                        .queryReceivers(computer, intent, resolvedType, flags, userId);
                if (result != null) {
                    list = result;
                }
            }
            final AndroidPackage pkg = computer.getPackage(pkgName);
            if (pkg != null) {
                final List<ResolveInfo> result = componentResolver.queryReceivers(computer,
                        intent, resolvedType, flags, pkg.getReceivers(), userId);
                if (result != null) {
                    list = result;
                }
            }
        }

        if (originalIntent != null) {
            // We also have to ensure all components match the original intent
            PackageManagerServiceUtils.applyEnforceIntentFilterMatching(
                    mPlatformCompat, componentResolver,
                    list, true, originalIntent, resolvedType, filterCallingUid);
        }

        return computer.applyPostResolutionFilter(list, instantAppPkgName, false, queryingUid,
                false, userId, intent);
    }


    public ResolveInfo resolveServiceInternal(@NonNull Computer computer, Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId,
            int callingUid) {
        if (!mUserManager.exists(userId)) return null;
        flags = computer.updateFlagsForResolve(flags, userId, callingUid, false /*includeInstantApps*/,
                false /* isImplicitImageCaptureIntentAndNotSetByDpc */);
        List<ResolveInfo> query = computer.queryIntentServicesInternal(
                intent, resolvedType, flags, userId, callingUid, false /*includeInstantApps*/);
        if (query != null) {
            if (query.size() >= 1) {
                // If there is more than one service with the same priority,
                // just arbitrarily pick the first one.
                return query.get(0);
            }
        }
        return null;
    }

    public @NonNull List<ResolveInfo> queryIntentContentProvidersInternal(
            @NonNull Computer computer, Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        if (!mUserManager.exists(userId)) return Collections.emptyList();
        final int callingUid = Binder.getCallingUid();
        final String instantAppPkgName = computer.getInstantAppPackageName(callingUid);
        flags = computer.updateFlagsForResolve(flags, userId, callingUid, false /*includeInstantApps*/,
                false /* isImplicitImageCaptureIntentAndNotSetByDpc */);
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ProviderInfo pi = computer.getProviderInfo(comp, flags, userId);
            if (pi != null) {
                // When specifying an explicit component, we prevent the provider from being
                // used when either 1) the provider is in an instant application and the
                // caller is not the same instant application or 2) the calling package is an
                // instant application and the provider is not visible to instant applications.
                final boolean matchInstantApp =
                        (flags & PackageManager.MATCH_INSTANT) != 0;
                final boolean matchVisibleToInstantAppOnly =
                        (flags & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
                final boolean isCallerInstantApp =
                        instantAppPkgName != null;
                final boolean isTargetSameInstantApp =
                        comp.getPackageName().equals(instantAppPkgName);
                final boolean isTargetInstantApp =
                        (pi.applicationInfo.privateFlags
                                & ApplicationInfo.PRIVATE_FLAG_INSTANT) != 0;
                final boolean isTargetHiddenFromInstantApp =
                        (pi.flags & ProviderInfo.FLAG_VISIBLE_TO_INSTANT_APP) == 0;
                final boolean blockResolution =
                        !isTargetSameInstantApp
                                && ((!matchInstantApp && !isCallerInstantApp && isTargetInstantApp)
                                || (matchVisibleToInstantAppOnly && isCallerInstantApp
                                && isTargetHiddenFromInstantApp));
                final boolean blockNormalResolution = !isTargetInstantApp && !isCallerInstantApp
                        && computer.shouldFilterApplication(
                        computer.getPackageStateInternal(pi.applicationInfo.packageName,
                                Process.SYSTEM_UID), callingUid, userId);
                if (!blockResolution && !blockNormalResolution) {
                    final ResolveInfo ri = new ResolveInfo();
                    ri.providerInfo = pi;
                    list.add(ri);
                }
            }
            return list;
        }

        final ComponentResolverApi componentResolver = computer.getComponentResolver();
        String pkgName = intent.getPackage();
        if (pkgName == null) {
            final List<ResolveInfo> resolveInfos = componentResolver.queryProviders(computer,
                    intent, resolvedType, flags, userId);
            if (resolveInfos == null) {
                return Collections.emptyList();
            }
            return applyPostContentProviderResolutionFilter(computer, resolveInfos,
                    instantAppPkgName, userId, callingUid);
        }
        final AndroidPackage pkg = computer.getPackage(pkgName);
        if (pkg != null) {
            final List<ResolveInfo> resolveInfos = componentResolver.queryProviders(computer,
                    intent, resolvedType, flags, pkg.getProviders(), userId);
            if (resolveInfos == null) {
                return Collections.emptyList();
            }
            return applyPostContentProviderResolutionFilter(computer, resolveInfos,
                    instantAppPkgName, userId, callingUid);
        }
        return Collections.emptyList();
    }

    private List<ResolveInfo> applyPostContentProviderResolutionFilter(@NonNull Computer computer,
            List<ResolveInfo> resolveInfos, String instantAppPkgName,
            @UserIdInt int userId, int callingUid) {
        for (int i = resolveInfos.size() - 1; i >= 0; i--) {
            final ResolveInfo info = resolveInfos.get(i);

            if (instantAppPkgName == null) {
                PackageStateInternal resolvedSetting = computer.getPackageStateInternal(
                        info.providerInfo.packageName, 0);
                if (!computer.shouldFilterApplication(resolvedSetting, callingUid, userId)) {
                    continue;
                }
            }

            final boolean isEphemeralApp = info.providerInfo.applicationInfo.isInstantApp();
            // allow providers that are defined in the provided package
            if (isEphemeralApp && instantAppPkgName.equals(info.providerInfo.packageName)) {
                if (info.providerInfo.splitName != null
                        && !ArrayUtils.contains(info.providerInfo.applicationInfo.splitNames,
                        info.providerInfo.splitName)) {
                    if (mInstantAppInstallerActivitySupplier.get() == null) {
                        if (DEBUG_INSTANT) {
                            Slog.v(TAG, "No installer - not adding it to the ResolveInfo list");
                        }
                        resolveInfos.remove(i);
                        continue;
                    }
                    // requested provider is defined in a split that hasn't been installed yet.
                    // add the installer to the resolve list
                    if (DEBUG_INSTANT) {
                        Slog.v(TAG, "Adding ephemeral installer to the ResolveInfo list");
                    }
                    final ResolveInfo installerInfo = new ResolveInfo(
                            computer.getInstantAppInstallerInfo());
                    installerInfo.auxiliaryInfo = new AuxiliaryResolveInfo(
                            null /*failureActivity*/,
                            info.providerInfo.packageName,
                            info.providerInfo.applicationInfo.longVersionCode,
                            info.providerInfo.splitName);
                    // add a non-generic filter
                    installerInfo.filter = new IntentFilter();
                    // load resources from the correct package
                    installerInfo.resolvePackageName = info.getComponentInfo().packageName;
                    resolveInfos.set(i, installerInfo);
                }
                continue;
            }
            // allow providers that have been explicitly exposed to instant applications
            if (!isEphemeralApp && (
                    (info.providerInfo.flags & ProviderInfo.FLAG_VISIBLE_TO_INSTANT_APP) != 0)) {
                continue;
            }
            resolveInfos.remove(i);
        }
        return resolveInfos;
    }

    public @NonNull List<ResolveInfo> queryIntentActivityOptionsInternal(Computer computer,
            ComponentName caller, Intent[] specifics, String[] specificTypes, Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        if (!mUserManager.exists(userId)) return Collections.emptyList();
        final int callingUid = Binder.getCallingUid();
        flags = computer.updateFlagsForResolve(flags, userId, callingUid,
                false /*includeInstantApps*/,
                computer.isImplicitImageCaptureIntentAndNotSetByDpc(intent, userId,
                        resolvedType, flags));
        computer.enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "query intent activity options");
        final String resultsAction = intent.getAction();

        final List<ResolveInfo> results = computer.queryIntentActivitiesInternal(intent,
                resolvedType, flags | PackageManager.GET_RESOLVED_FILTER, userId);

        if (DEBUG_INTENT_MATCHING) {
            Log.v(TAG, "Query " + intent + ": " + results);
        }

        int specificsPos = 0;
        int N;

        // todo: note that the algorithm used here is O(N^2).  This
        // isn't a problem in our current environment, but if we start running
        // into situations where we have more than 5 or 10 matches then this
        // should probably be changed to something smarter...

        // First we go through and resolve each of the specific items
        // that were supplied, taking care of removing any corresponding
        // duplicate items in the generic resolve list.
        if (specifics != null) {
            for (int i = 0; i < specifics.length; i++) {
                final Intent sintent = specifics[i];
                if (sintent == null) {
                    continue;
                }

                if (DEBUG_INTENT_MATCHING) {
                    Log.v(TAG, "Specific #" + i + ": " + sintent);
                }

                String action = sintent.getAction();
                if (resultsAction != null && resultsAction.equals(action)) {
                    // If this action was explicitly requested, then don't
                    // remove things that have it.
                    action = null;
                }

                ResolveInfo ri = null;
                ActivityInfo ai = null;

                ComponentName comp = sintent.getComponent();
                if (comp == null) {
                    ri = resolveIntentInternal(computer, sintent,
                            specificTypes != null ? specificTypes[i] : null, flags,
                            0 /*privateResolveFlags*/, userId, false, Binder.getCallingUid());
                    if (ri == null) {
                        continue;
                    }
                    if (ri == mResolveInfoSupplier.get()) {
                        // ACK!  Must do something better with this.
                    }
                    ai = ri.activityInfo;
                    comp = new ComponentName(ai.applicationInfo.packageName,
                            ai.name);
                } else {
                    ai = computer.getActivityInfo(comp, flags, userId);
                    if (ai == null) {
                        continue;
                    }
                }

                // Look for any generic query activities that are duplicates
                // of this specific one, and remove them from the results.
                if (DEBUG_INTENT_MATCHING) Log.v(TAG, "Specific #" + i + ": " + ai);
                N = results.size();
                int j;
                for (j = specificsPos; j < N; j++) {
                    ResolveInfo sri = results.get(j);
                    if ((sri.activityInfo.name.equals(comp.getClassName())
                            && sri.activityInfo.applicationInfo.packageName.equals(
                            comp.getPackageName()))
                            || (action != null && sri.filter.matchAction(action))) {
                        results.remove(j);
                        if (DEBUG_INTENT_MATCHING) {
                            Log.v(
                                    TAG, "Removing duplicate item from " + j
                                            + " due to specific " + specificsPos);
                        }
                        if (ri == null) {
                            ri = sri;
                        }
                        j--;
                        N--;
                    }
                }

                // Add this specific item to its proper place.
                if (ri == null) {
                    ri = new ResolveInfo();
                    ri.activityInfo = ai;
                }
                results.add(specificsPos, ri);
                ri.specificIndex = i;
                specificsPos++;
            }
        }

        // Now we go through the remaining generic results and remove any
        // duplicate actions that are found here.
        N = results.size();
        for (int i = specificsPos; i < N - 1; i++) {
            final ResolveInfo rii = results.get(i);
            if (rii.filter == null) {
                continue;
            }

            // Iterate over all of the actions of this result's intent
            // filter...  typically this should be just one.
            final Iterator<String> it = rii.filter.actionsIterator();
            if (it == null) {
                continue;
            }
            while (it.hasNext()) {
                final String action = it.next();
                if (resultsAction != null && resultsAction.equals(action)) {
                    // If this action was explicitly requested, then don't
                    // remove things that have it.
                    continue;
                }
                for (int j = i + 1; j < N; j++) {
                    final ResolveInfo rij = results.get(j);
                    if (rij.filter != null && rij.filter.hasAction(action)) {
                        results.remove(j);
                        if (DEBUG_INTENT_MATCHING) {
                            Log.v(
                                    TAG, "Removing duplicate item from " + j
                                            + " due to action " + action + " at " + i);
                        }
                        j--;
                        N--;
                    }
                }
            }

            // If the caller didn't request filter information, drop it now
            // so we don't have to marshall/unmarshall it.
            if ((flags & PackageManager.GET_RESOLVED_FILTER) == 0) {
                rii.filter = null;
            }
        }

        // Filter out the caller activity if so requested.
        if (caller != null) {
            N = results.size();
            for (int i = 0; i < N; i++) {
                ActivityInfo ainfo = results.get(i).activityInfo;
                if (caller.getPackageName().equals(ainfo.applicationInfo.packageName)
                        && caller.getClassName().equals(ainfo.name)) {
                    results.remove(i);
                    break;
                }
            }
        }

        // If the caller didn't request filter information,
        // drop them now so we don't have to
        // marshall/unmarshall it.
        if ((flags & PackageManager.GET_RESOLVED_FILTER) == 0) {
            N = results.size();
            for (int i = 0; i < N; i++) {
                results.get(i).filter = null;
            }
        }

        if (DEBUG_INTENT_MATCHING) Log.v(TAG, "Result: " + results);
        return results;
    }

}
