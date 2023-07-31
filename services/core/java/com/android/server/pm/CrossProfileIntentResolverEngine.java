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

package com.android.server.pm;

import static android.content.pm.PackageManager.MATCH_ALL;

import static com.android.server.pm.PackageManagerService.DEBUG_DOMAIN_VERIFICATION;
import static com.android.server.pm.PackageManagerService.DEBUG_PREFERRED;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.config.appcloning.AppCloningDeviceConfigHelper;
import com.android.server.LocalServices;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.pm.verify.domain.DomainVerificationUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Rule based engine which decides strategy to be used for source,target pair and does cross profile
 * intent resolution. Currently, we have only default and clone strategy. The major known use-case
 * for default is work profile.
 */
public class CrossProfileIntentResolverEngine {

    private final UserManagerService mUserManager;
    private final DomainVerificationManagerInternal mDomainVerificationManager;
    private final DefaultAppProvider mDefaultAppProvider;
    private final Context mContext;
    private final UserManagerInternal mUserManagerInternal;

    private AppCloningDeviceConfigHelper mAppCloningDeviceConfigHelper;

    public CrossProfileIntentResolverEngine(UserManagerService userManager,
            DomainVerificationManagerInternal domainVerificationManager,
            DefaultAppProvider defaultAppProvider, Context context) {
        mUserManager = userManager;
        mDomainVerificationManager = domainVerificationManager;
        mDefaultAppProvider = defaultAppProvider;
        mContext = context;
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
    }

    /**
     * Returns the list of {@link CrossProfileDomainInfo} which contains {@link ResolveInfo} from
     * profiles linked directly/indirectly to user. Work-Owner as well as Clone-Owner
     * are directly related as they are child of owner. Work-Clone are indirectly linked through
     * owner profile.
     * @param computer {@link Computer} instance used for resolution by {@link ComponentResolverApi}
     * @param intent request
     * @param resolvedType the MIME data type of intent request
     * @param userId source user for which intent request is called
     * @param flags used for intent resolution
     * @param pkgName the application package name this Intent is limited to.
     * @param hasNonNegativePriorityResult signifies if current profile have any non-negative(active
     *                                     and valid) ResolveInfo in current profile.
     * @param resolveForStart true if resolution occurs to start an activity.
     * @param pkgSettingFunction function to find PackageStateInternal for given package
     * @return list of {@link CrossProfileDomainInfo} from linked profiles.
     */
    public List<CrossProfileDomainInfo> resolveIntent(@NonNull Computer computer, Intent intent,
            String resolvedType, int userId, long flags, String pkgName,
            boolean hasNonNegativePriorityResult, boolean resolveForStart,
            Function<String, PackageStateInternal> pkgSettingFunction) {
        return resolveIntentInternal(computer, intent, resolvedType, userId, userId, flags, pkgName,
                hasNonNegativePriorityResult, resolveForStart, pkgSettingFunction, null);
    }

    /**
     * Resolves intent in directly linked profiles and return list of {@link CrossProfileDomainInfo}
     * which contains {@link ResolveInfo}. This would also recursively call profiles not directly
     * linked using Depth First Search.
     *
     * It first finds {@link CrossProfileIntentFilter} configured in current profile to find list of
     * target user profiles that can serve current intent request. It uses corresponding strategy
     * for each pair (source,target) user to resolve intent from target profile and returns combined
     * results.
     * @param computer {@link Computer} instance used for resolution by {@link ComponentResolverApi}
     * @param intent request
     * @param resolvedType the MIME data type of intent request
     * @param sourceUserId source user for which intent request is called
     * @param userId current user for cross profile resolution
     * @param flags used for intent resolution
     * @param pkgName the application package name this Intent is limited to.
     * @param hasNonNegativePriorityResult signifies if current profile have any non-negative(active
     *                                     and valid) ResolveInfo in current profile.
     * @param resolveForStart true if resolution occurs to start an activity.
     * @param pkgSettingFunction function to find PackageStateInternal for given package
     * @param visitedUserIds users for which we have already performed resolution
     * @return list of {@link CrossProfileDomainInfo} from linked profiles.
     */
    private List<CrossProfileDomainInfo> resolveIntentInternal(@NonNull Computer computer,
            Intent intent, String resolvedType, int sourceUserId, int userId, long flags,
            String pkgName, boolean hasNonNegativePriorityResult, boolean resolveForStart,
            Function<String, PackageStateInternal> pkgSettingFunction,
            Set<Integer> visitedUserIds) {

        if (visitedUserIds != null) visitedUserIds.add(userId);
        List<CrossProfileDomainInfo> crossProfileDomainInfos = new ArrayList<>();

        List<CrossProfileIntentFilter> matchingFilters =
                computer.getMatchingCrossProfileIntentFilters(intent, resolvedType,
                        userId);

        if (matchingFilters == null || matchingFilters.isEmpty()) {
            /** if intent is web intent, checking if parent profile should handle the intent
             * even if there is no matching filter. The configuration is based on user profile
             * restriction android.os.UserManager#ALLOW_PARENT_PROFILE_APP_LINKING **/
            if (sourceUserId == userId && intent.hasWebURI()) {
                UserInfo parent = computer.getProfileParent(userId);
                if (parent != null) {
                    CrossProfileDomainInfo generalizedCrossProfileDomainInfo = computer
                            .getCrossProfileDomainPreferredLpr(intent, resolvedType, flags,
                                    userId, parent.id);
                    if (generalizedCrossProfileDomainInfo != null) {
                        crossProfileDomainInfos.add(generalizedCrossProfileDomainInfo);
                    }
                }
            }
            return crossProfileDomainInfos;
        }

        // Grouping the CrossProfileIntentFilters based on targerId
        SparseArray<List<CrossProfileIntentFilter>> crossProfileIntentFiltersByUser =
                new SparseArray<>();

        for (int index = 0; index < matchingFilters.size(); index++) {
            CrossProfileIntentFilter crossProfileIntentFilter = matchingFilters.get(index);

            if (!crossProfileIntentFiltersByUser
                    .contains(crossProfileIntentFilter.mTargetUserId)) {
                crossProfileIntentFiltersByUser.put(crossProfileIntentFilter.mTargetUserId,
                        new ArrayList<>());
            }
            crossProfileIntentFiltersByUser.get(crossProfileIntentFilter.mTargetUserId)
                    .add(crossProfileIntentFilter);
        }

        if (visitedUserIds == null) {
            visitedUserIds = new HashSet<>();
            visitedUserIds.add(userId);
        }

        /*
         For each target user, we would call their corresponding strategy
         {@link CrossProfileResolver} to resolve intent in corresponding user
         */
        for (int index = 0; index < crossProfileIntentFiltersByUser.size(); index++) {

            int targetUserId = crossProfileIntentFiltersByUser.keyAt(index);

            //if user is already visited then skip resolution for particular user.
            if (visitedUserIds.contains(targetUserId)) {
                continue;
            }

            // Choosing strategy based on source and target user
            CrossProfileResolver crossProfileResolver =
                    chooseCrossProfileResolver(computer, userId, targetUserId,
                            resolveForStart, flags);

        /*
        If {@link CrossProfileResolver} is available for source,target pair we will call it to
        get {@link CrossProfileDomainInfo}s from that user.
         */
            if (crossProfileResolver != null) {
                List<CrossProfileDomainInfo> crossProfileInfos = crossProfileResolver
                        .resolveIntent(computer, intent, resolvedType, userId,
                                targetUserId, flags, pkgName,
                                crossProfileIntentFiltersByUser.valueAt(index),
                                hasNonNegativePriorityResult, pkgSettingFunction);
                crossProfileDomainInfos.addAll(crossProfileInfos);
                visitedUserIds.add(targetUserId);

                /*
                Adding target user to queue if flag
                {@link CrossProfileIntentFilter#FLAG_ALLOW_CHAINED_RESOLUTION} is set for any
                {@link CrossProfileIntentFilter}
                 */
                boolean allowChainedResolution = false;
                for (int filterIndex = 0; filterIndex < crossProfileIntentFiltersByUser
                        .valueAt(index).size(); filterIndex++) {
                    if ((CrossProfileIntentFilter
                            .FLAG_ALLOW_CHAINED_RESOLUTION & crossProfileIntentFiltersByUser
                            .valueAt(index).get(filterIndex).mFlags) != 0) {
                        allowChainedResolution = true;
                        break;
                    }
                }
                if (allowChainedResolution) {
                    crossProfileDomainInfos.addAll(resolveIntentInternal(computer, intent,
                            resolvedType, sourceUserId, targetUserId, flags, pkgName,
                            hasNonNegativePriority(crossProfileInfos), resolveForStart,
                            pkgSettingFunction, visitedUserIds));
                }

            }
        }

        return crossProfileDomainInfos;
    }


    /**
     * Returns {@link CrossProfileResolver} strategy based on source and target user
     * @param computer {@link Computer} instance used for resolution by {@link ComponentResolverApi}
     * @param sourceUserId source user
     * @param targetUserId target user
     * @param resolveForStart true if resolution occurs to start an activity.
     * @param flags used for intent resolver selection
     * @return {@code CrossProfileResolver} which has value if source and target have
     * strategy configured otherwise null.
     */
    @SuppressWarnings("unused")
    private CrossProfileResolver chooseCrossProfileResolver(@NonNull Computer computer,
            @UserIdInt int sourceUserId, @UserIdInt int targetUserId, boolean resolveForStart,
            long flags) {
        /**
         * If source or target user is clone profile, using {@link NoFilteringResolver}
         * We would return NoFilteringResolver only if it is allowed(feature flag is set).
         */
        if (shouldUseNoFilteringResolver(sourceUserId, targetUserId)) {
            if (mAppCloningDeviceConfigHelper == null) {
                //lazy initialization of helper till required, to improve performance.
                mAppCloningDeviceConfigHelper = AppCloningDeviceConfigHelper.getInstance(mContext);
            }
            if (NoFilteringResolver.isIntentRedirectionAllowed(mContext,
                    mAppCloningDeviceConfigHelper, resolveForStart, flags)) {
                return new NoFilteringResolver(computer.getComponentResolver(),
                        mUserManager);
            } else {
                return null;
            }
        }
        return new DefaultCrossProfileResolver(computer.getComponentResolver(),
                mUserManager, mDomainVerificationManager);
    }

    /**
     * Returns true if we source user can reach target user for given intent. The source can
     * directly or indirectly reach to target.
     * @param computer {@link Computer} instance used for resolution by {@link ComponentResolverApi}
     * @param intent request
     * @param resolvedType the MIME data type of intent request
     * @param sourceUserId source user
     * @param targetUserId target user
     * @return true if we source user can reach target user for given intent
     */
    public boolean canReachTo(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, @UserIdInt int sourceUserId,
            @UserIdInt int targetUserId) {
        Set<Integer> visitedUserIds = new HashSet<>();
        return canReachToInternal(computer, intent, resolvedType, sourceUserId, targetUserId,
                visitedUserIds);
    }

    /**
     * Returns true if we source user can reach target user for given intent. The source can
     * directly or indirectly reach to target. This will perform depth first search to check if
     * source can reach target.
     * @param computer {@link Computer} instance used for resolution by {@link ComponentResolverApi}
     * @param intent request
     * @param resolvedType the MIME data type of intent request
     * @param sourceUserId source user
     * @param targetUserId target user
     * @param visitedUserIds users for which resolution is checked
     * @return true if we source user can reach target user for given intent
     */
    private boolean canReachToInternal(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, @UserIdInt int sourceUserId,
            @UserIdInt int targetUserId, Set<Integer> visitedUserIds) {
        if (sourceUserId == targetUserId) return true;
        visitedUserIds.add(sourceUserId);

        List<CrossProfileIntentFilter> matches =
                computer.getMatchingCrossProfileIntentFilters(intent, resolvedType, sourceUserId);

        if (matches != null) {
            for (int index = 0; index < matches.size(); index++) {
                CrossProfileIntentFilter crossProfileIntentFilter = matches.get(index);
                if (crossProfileIntentFilter.mTargetUserId == targetUserId) {
                    return true;
                }
                if (visitedUserIds.contains(crossProfileIntentFilter.mTargetUserId)) {
                    continue;
                }

                /*
                 If source cannot directly reach to target, we will add
                 CrossProfileIntentFilter.mTargetUserId user to queue to check if target user
                 can be reached via CrossProfileIntentFilter.mTargetUserId i.e. it can be
                 indirectly reached through chained/linked profiles.
                 */
                if ((CrossProfileIntentFilter.FLAG_ALLOW_CHAINED_RESOLUTION
                        & crossProfileIntentFilter.mFlags) != 0) {
                    visitedUserIds.add(crossProfileIntentFilter.mTargetUserId);
                    if (canReachToInternal(computer, intent, resolvedType,
                            crossProfileIntentFilter.mTargetUserId, targetUserId, visitedUserIds)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if any of the matching {@link CrossProfileIntentFilter} suggest we should skip the
     * current profile based on flag {@link PackageManager#SKIP_CURRENT_PROFILE}.
     * @param computer {@link Computer} instance used to find {@link CrossProfileIntentFilter}
     *                                 for user
     * @param intent request
     * @param resolvedType the MIME data type of intent request
     * @param sourceUserId id of initiating user space
     * @return boolean if we should skip resolution in current/source profile.
     */
    public boolean shouldSkipCurrentProfile(Computer computer, Intent intent, String resolvedType,
            int sourceUserId) {
        List<CrossProfileIntentFilter> matches =
                computer.getMatchingCrossProfileIntentFilters(intent, resolvedType, sourceUserId);
        if (matches != null) {
            for (int matchIndex = 0; matchIndex < matches.size(); matchIndex++) {
                CrossProfileIntentFilter crossProfileIntentFilter = matches.get(matchIndex);
                if ((crossProfileIntentFilter.getFlags()
                        & PackageManager.SKIP_CURRENT_PROFILE) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Combines result from current and cross profile. This also does filtering based on domain(if
     * required).
     * @param computer {@link Computer} instance
     * @param intent request
     * @param resolvedType the MIME data type of intent request
     * @param instantAppPkgName package name if instant app is allowed
     * @param pkgName the application package name this Intent is limited to.
     * @param allowDynamicSplits true if dynamic splits is allowed
     * @param matchFlags flags for intent request
     * @param userId user id of source user
     * @param filterCallingUid uid of calling process
     * @param resolveForStart true if resolution occurs because an application is starting
     * @param candidates resolveInfos from current profile
     * @param crossProfileCandidates crossProfileDomainInfos from cross profile, it has ResolveInfo
     * @param areWebInstantAppsDisabled true if web instant apps are disabled
     * @param addInstant true if instant apps are allowed
     * @param sortResult true if caller would need to sort the results
     * @param pkgSettingFunction function to find PackageStateInternal for given package
     * @return QueryIntentActivitiesResult which contains resolveInfos
     */
    public QueryIntentActivitiesResult combineFilterAndCreateQueryActivitiesResponse(
            Computer computer, Intent intent, String resolvedType, String instantAppPkgName,
            String pkgName, boolean allowDynamicSplits, long matchFlags, int userId,
            int filterCallingUid, boolean resolveForStart, List<ResolveInfo> candidates,
            List<CrossProfileDomainInfo> crossProfileCandidates, boolean areWebInstantAppsDisabled,
            boolean addInstant, boolean sortResult,
            Function<String, PackageStateInternal> pkgSettingFunction) {

        if (shouldSkipCurrentProfile(computer, intent, resolvedType, userId)) {
            /*
             if current profile is skipped return results from cross profile after filtering
             ephemeral activities.
             */
            candidates = resolveInfoFromCrossProfileDomainInfo(crossProfileCandidates);
            return new QueryIntentActivitiesResult(computer.applyPostResolutionFilter(candidates,
                    instantAppPkgName, allowDynamicSplits, filterCallingUid, resolveForStart,
                    userId, intent));
        }

        if (pkgName == null && intent.hasWebURI()) {
            if (!addInstant && ((candidates.size() <= 1 && crossProfileCandidates.isEmpty())
                    || (candidates.isEmpty() && !crossProfileCandidates.isEmpty()))) {
                candidates.addAll(resolveInfoFromCrossProfileDomainInfo(crossProfileCandidates));
                return new QueryIntentActivitiesResult(computer.applyPostResolutionFilter(
                        candidates, instantAppPkgName, allowDynamicSplits, filterCallingUid,
                        resolveForStart, userId, intent));
            }
            /*
             if there are multiple results from current and cross profile, combining and filtering
             results based on domain priority.
             */
            candidates = filterCandidatesWithDomainPreferredActivitiesLPr(computer, intent,
                    matchFlags, candidates, crossProfileCandidates, userId,
                    areWebInstantAppsDisabled, resolveForStart, pkgSettingFunction);
        } else {
            candidates.addAll(resolveInfoFromCrossProfileDomainInfo(crossProfileCandidates));
        }
        // When we have only a single result belonging to a different user space, we check
        // if filtering is configured on cross-profile intent resolution for the originating user.
        // Accordingly, we modify the intent to reflect the content-owner as the originating user,
        // preventing the resolving user to be assumed the same, when activity is started.

        // In case more than one result is present, the resolver sheet is opened which takes care of
        // cross user access.
        if (candidates.size() == 1 && !UserHandle.of(userId).equals(candidates.get(0).userHandle)
                && isNoFilteringPropertyConfiguredForUser(userId)) {
            intent.prepareToLeaveUser(userId);
        }
        return new QueryIntentActivitiesResult(sortResult, addInstant, candidates);
    }
    /**
     * It filters and combines results from current and cross profile based on domain priority.
     * @param computer {@link Computer} instance
     * @param intent request
     * @param matchFlags flags for intent request
     * @param candidates resolveInfos from current profile
     * @param crossProfileCandidates crossProfileDomainInfos from cross profile, it have ResolveInfo
     * @param userId user id of source user
     * @param areWebInstantAppsDisabled true if web instant apps are disabled
     * @param resolveForStart true if intent is for resolution
     * @param pkgSettingFunction function to find PackageStateInternal for given package
     * @return list of ResolveInfo
     */
    private List<ResolveInfo> filterCandidatesWithDomainPreferredActivitiesLPr(Computer computer,
            Intent intent, long matchFlags, List<ResolveInfo> candidates,
            List<CrossProfileDomainInfo> crossProfileCandidates, int userId,
            boolean areWebInstantAppsDisabled, boolean resolveForStart,
            Function<String, PackageStateInternal> pkgSettingFunction) {
        final boolean debug = (intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0;

        if (DEBUG_PREFERRED || DEBUG_DOMAIN_VERIFICATION) {
            Slog.v(TAG, "Filtering results with preferred activities. Candidates count: "
                    + candidates.size());
        }

        final List<ResolveInfo> result =
                filterCandidatesWithDomainPreferredActivitiesLPrBody(computer, intent, matchFlags,
                        candidates, crossProfileCandidates, userId, areWebInstantAppsDisabled,
                        debug, resolveForStart, pkgSettingFunction);

        if (DEBUG_PREFERRED || DEBUG_DOMAIN_VERIFICATION) {
            Slog.v(TAG, "Filtered results with preferred activities. New candidates count: "
                    + result.size());
            for (ResolveInfo info : result) {
                Slog.v(TAG, " + " + info.activityInfo);
            }
        }
        return result;
    }

    /**
     * Filters candidates satisfying domain criteria.
     * @param computer {@link Computer} instance
     * @param intent request
     * @param matchFlags flags for intent request
     * @param candidates resolveInfos from current profile
     * @param crossProfileCandidates crossProfileDomainInfos from cross profile, it have ResolveInfo
     * @param userId user id of source user
     * @param areWebInstantAppsDisabled true if web instant apps are disabled
     * @param debug true if resolution logs needed to be printed
     * @param resolveForStart true if intent is for resolution
     * @param pkgSettingFunction function to find PackageStateInternal for given package
     * @return list of resolve infos
     */
    private List<ResolveInfo> filterCandidatesWithDomainPreferredActivitiesLPrBody(
            Computer computer, Intent intent, long matchFlags, List<ResolveInfo> candidates,
            List<CrossProfileDomainInfo> crossProfileCandidates, int userId,
            boolean areWebInstantAppsDisabled, boolean debug, boolean resolveForStart,
            Function<String, PackageStateInternal> pkgSettingFunction) {
        final ArrayList<ResolveInfo> result = new ArrayList<>();
        final ArrayList<ResolveInfo> matchAllList = new ArrayList<>();
        final ArrayList<ResolveInfo> undefinedList = new ArrayList<>();

        // Blocking instant apps is usually done in applyPostResolutionFilter, but since
        // domain verification can resolve to a single result, which can be an instant app,
        // it will then be filtered to an empty list in that method. Instead, do blocking
        // here so that instant apps can be ignored for approval filtering and a lower
        // priority result chosen instead.
        final boolean blockInstant = intent.isWebIntent() && areWebInstantAppsDisabled;

        final int count = candidates.size();
        // First, try to use approved apps.
        for (int n = 0; n < count; n++) {
            ResolveInfo info = candidates.get(n);
            if (blockInstant && (info.isInstantAppAvailable
                    || computer.isInstantAppInternal(info.activityInfo.packageName, userId,
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

        /**
         * Grouping CrossProfileDomainInfo based on target user
         */
        SparseArray<List<CrossProfileDomainInfo>> categorizeResolveInfoByTargetUser =
                new SparseArray<>();
        if (crossProfileCandidates != null && !crossProfileCandidates.isEmpty()) {
            for (int index = 0; index < crossProfileCandidates.size(); index++) {
                CrossProfileDomainInfo crossProfileDomainInfo = crossProfileCandidates.get(index);
                if (!categorizeResolveInfoByTargetUser
                        .contains(crossProfileDomainInfo.mTargetUserId)) {
                    categorizeResolveInfoByTargetUser.put(crossProfileDomainInfo.mTargetUserId,
                            new ArrayList<>());
                }
                categorizeResolveInfoByTargetUser.get(crossProfileDomainInfo.mTargetUserId)
                        .add(crossProfileDomainInfo);
            }
        }

        if (!DomainVerificationUtils.isDomainVerificationIntent(intent, matchFlags)) {
            result.addAll(undefinedList);

            // calling cross profile strategy to filter corresponding results
            result.addAll(filterCrossProfileCandidatesWithDomainPreferredActivities(computer,
                    intent, matchFlags, categorizeResolveInfoByTargetUser, userId,
                    DomainVerificationManagerInternal.APPROVAL_LEVEL_NONE, resolveForStart));
            includeBrowser = true;
        } else {
            Pair<List<ResolveInfo>, Integer> infosAndLevel = mDomainVerificationManager
                    .filterToApprovedApp(intent, undefinedList, userId, pkgSettingFunction);
            List<ResolveInfo> approvedInfos = infosAndLevel.first;
            Integer highestApproval = infosAndLevel.second;

            // If no apps are approved for the domain, resolve only to browsers
            if (approvedInfos.isEmpty()) {
                includeBrowser = true;
                // calling cross profile strategy to filter corresponding results
                result.addAll(filterCrossProfileCandidatesWithDomainPreferredActivities(computer,
                        intent, matchFlags, categorizeResolveInfoByTargetUser, userId,
                        DomainVerificationManagerInternal.APPROVAL_LEVEL_NONE, resolveForStart));
            } else {
                result.addAll(approvedInfos);

                // If the other profile has an app that's higher approval, add it
                // calling cross profile strategy to filter corresponding results
                result.addAll(filterCrossProfileCandidatesWithDomainPreferredActivities(computer,
                        intent, matchFlags, categorizeResolveInfoByTargetUser, userId,
                        highestApproval, resolveForStart));
            }
        }

        if (includeBrowser) {
            // Also add browsers (all of them or only the default one)
            if (DEBUG_DOMAIN_VERIFICATION) {
                Slog.v(TAG, " ...including browsers in candidate set");
            }
            if ((matchFlags & MATCH_ALL) != 0) {
                result.addAll(matchAllList);
            } else {
                // Browser/generic handling case. If there's a default browser, go straight
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
     * Filter cross profile results by calling their respective strategy
     * @param computer {@link Computer} instance
     * @param intent request
     * @param flags for intent request
     * @param categorizeResolveInfoByTargetUser group of targetuser and its corresponding
     *                                          CrossProfileDomainInfos
     * @param sourceUserId user id for intent
     * @param highestApprovalLevel domain approval level
     * @param resolveForStart true if intent is for resolution
     * @return list of ResolveInfos
     */
    private List<ResolveInfo> filterCrossProfileCandidatesWithDomainPreferredActivities(
            Computer computer, Intent intent, long flags, SparseArray<List<CrossProfileDomainInfo>>
            categorizeResolveInfoByTargetUser, int sourceUserId, int highestApprovalLevel,
            boolean resolveForStart) {

        List<CrossProfileDomainInfo> crossProfileDomainInfos = new ArrayList<>();

        for (int index = 0; index < categorizeResolveInfoByTargetUser.size(); index++) {

            // if resolve info does not target user or has default value, add results as they are.
            if (categorizeResolveInfoByTargetUser.keyAt(index) == -2) {
                crossProfileDomainInfos.addAll(categorizeResolveInfoByTargetUser.valueAt(index));
            } else {
                // finding cross profile strategy based on source and target user
                CrossProfileResolver crossProfileIntentResolver =
                        chooseCrossProfileResolver(computer, sourceUserId,
                                categorizeResolveInfoByTargetUser.keyAt(index), resolveForStart,
                                flags);
                // if strategy is available call it and add its filtered results
                if (crossProfileIntentResolver != null) {
                    crossProfileDomainInfos.addAll(crossProfileIntentResolver
                            .filterResolveInfoWithDomainPreferredActivity(intent,
                                    categorizeResolveInfoByTargetUser.valueAt(index),
                                    flags, sourceUserId, categorizeResolveInfoByTargetUser
                                            .keyAt(index), highestApprovalLevel));
                } else {
                    // if strategy is not available call it, add the results
                    crossProfileDomainInfos.addAll(categorizeResolveInfoByTargetUser
                            .valueAt(index));
                }
            }
        }
        return resolveInfoFromCrossProfileDomainInfo(crossProfileDomainInfos);
    }

    /**
     * Extract ResolveInfo from CrossProfileDomainInfo
     * @param crossProfileDomainInfos cross profile results
     * @return list of ResolveInfo
     */
    private List<ResolveInfo> resolveInfoFromCrossProfileDomainInfo(List<CrossProfileDomainInfo>
            crossProfileDomainInfos) {
        List<ResolveInfo> resolveInfoList = new ArrayList<>();

        for (int infoIndex = 0; infoIndex < crossProfileDomainInfos.size(); infoIndex++) {
            resolveInfoList.add(crossProfileDomainInfos.get(infoIndex).mResolveInfo);
        }

        return resolveInfoList;
    }

    /**
     * @param crossProfileDomainInfos list of cross profile domain info in descending priority order
     * @return if the list contains a resolve info with non-negative priority
     */
    private boolean hasNonNegativePriority(List<CrossProfileDomainInfo> crossProfileDomainInfos) {
        return crossProfileDomainInfos.size() > 0
                && crossProfileDomainInfos.get(0).mResolveInfo != null
                && crossProfileDomainInfos.get(0).mResolveInfo.priority >= 0;
    }

    /**
     * Deciding if we need to user {@link NoFilteringResolver} based on source and target user
     * @param sourceUserId id of initiating user
     * @param targetUserId id of cross profile linked user
     * @return true if {@link NoFilteringResolver} is applicable in this case.
     */
    private boolean shouldUseNoFilteringResolver(@UserIdInt int sourceUserId,
            @UserIdInt int targetUserId) {
        return isNoFilteringPropertyConfiguredForUser(sourceUserId)
                || isNoFilteringPropertyConfiguredForUser(targetUserId);
    }

    /**
     * Check if configure property for cross profile intent resolution strategy for user is
     * {@link UserProperties#CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_NO_FILTERING}
     * @param userId id of user to check for property
     * @return true if user have property set to
     * {@link UserProperties#CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_NO_FILTERING}
     */
    private boolean isNoFilteringPropertyConfiguredForUser(@UserIdInt int userId) {
        if (!mUserManager.isProfile(userId)) return false;
        UserProperties userProperties = mUserManagerInternal.getUserProperties(userId);
        if (userProperties == null) return false;

        return userProperties.getCrossProfileIntentResolutionStrategy()
                == UserProperties.CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_NO_FILTERING;
    }
}
