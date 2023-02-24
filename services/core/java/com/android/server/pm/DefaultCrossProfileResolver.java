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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.util.SparseBooleanArray;

import com.android.internal.app.IntentForwarderActivity;
import com.android.internal.util.CollectionUtils;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.resolution.ComponentResolverApi;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Cross profile resolver used as default strategy. Primary known use-case for this resolver is
 * work/managed profile .
 */
public final class DefaultCrossProfileResolver extends CrossProfileResolver {

    private final DomainVerificationManagerInternal mDomainVerificationManager;


    public DefaultCrossProfileResolver(ComponentResolverApi componentResolver,
            UserManagerService userManager,
            DomainVerificationManagerInternal domainVerificationManager) {
        super(componentResolver, userManager);
        mDomainVerificationManager = domainVerificationManager;
    }

    /**
     * This is Default resolution strategy primarily used by Work Profile.
     * First, it checks if we have to skip source profile and just resolve in target profile. If
     * yes, then it will return result from target profile.
     * Secondly, it find specific resolve infos in target profile
     * Thirdly, if it is web intent it finds if parent can also resolve it. The results of this
     * stage gets higher priority as compared to second stage.
     *
     * @param computer ComputerEngine instance that would be needed by ComponentResolverApi
     * @param intent request
     * @param resolvedType the MIME data type of intent request
     * @param userId source/initiating user
     * @param targetUserId target user id
     * @param flags of intent request
     * @param pkgName the application package name this Intent is limited to.
     * @param matchingFilters {@link CrossProfileIntentFilter}s configured for source user,
     *                                                        targeting the targetUserId
     * @param hasNonNegativePriorityResult if source have any non-negative(active and valid)
     *                                     resolveInfo in their profile.
     * @param pkgSettingFunction function to find PackageStateInternal for given package
     * @return list of {@link CrossProfileDomainInfo}
     */
    @Override
    public List<CrossProfileDomainInfo> resolveIntent(Computer computer, Intent intent,
            String resolvedType, int userId, int targetUserId,
            long flags, String pkgName, List<CrossProfileIntentFilter> matchingFilters,
            boolean hasNonNegativePriorityResult,
            Function<String, PackageStateInternal> pkgSettingFunction) {

        List<CrossProfileDomainInfo> xpResult = new ArrayList<>();
        if (pkgName != null) return xpResult;
        CrossProfileDomainInfo skipProfileInfo = querySkipCurrentProfileIntents(computer,
                matchingFilters, intent, resolvedType, flags, userId, pkgSettingFunction);

        if (skipProfileInfo != null) {
            xpResult.add(skipProfileInfo);
            return filterIfNotSystemUser(xpResult, userId);
        }

        CrossProfileDomainInfo specificXpInfo = queryCrossProfileIntents(computer,
                matchingFilters, intent, resolvedType, flags, userId,
                hasNonNegativePriorityResult, pkgSettingFunction);

        if (intent.hasWebURI()) {
            CrossProfileDomainInfo generalXpInfo = null;
            final UserInfo parent = getProfileParent(userId);
            if (parent != null) {
                generalXpInfo = computer.getCrossProfileDomainPreferredLpr(intent, resolvedType,
                        flags, userId, parent.id);
            }
            CrossProfileDomainInfo prioritizedXpInfo =
                    generalXpInfo != null ? generalXpInfo : specificXpInfo;
            if (prioritizedXpInfo != null) {
                xpResult.add(prioritizedXpInfo);
            }
        } else if (specificXpInfo != null) {
            xpResult.add(specificXpInfo);
        }

        return xpResult;
    }

    /**
     * Filters out CrossProfileDomainInfo if it does not have higher approval level as compared to
     * given approval level
     * @param intent request
     * @param crossProfileDomainInfos resolved in target user
     * @param flags for intent resolution
     * @param sourceUserId source user
     * @param targetUserId target user
     * @param highestApprovalLevel highest level of domain approval
     * @return filtered list of CrossProfileDomainInfo
     */
    @Override
    public List<CrossProfileDomainInfo> filterResolveInfoWithDomainPreferredActivity(
            Intent intent, List<CrossProfileDomainInfo> crossProfileDomainInfos, long flags,
            int sourceUserId, int targetUserId, int highestApprovalLevel) {

        List<CrossProfileDomainInfo> filteredCrossProfileDomainInfos = new ArrayList<>();

        if (crossProfileDomainInfos != null && !crossProfileDomainInfos.isEmpty()) {
            for (int index = 0; index < crossProfileDomainInfos.size(); index++) {
                CrossProfileDomainInfo crossProfileDomainInfo = crossProfileDomainInfos.get(index);
                if (crossProfileDomainInfo.mHighestApprovalLevel > highestApprovalLevel) {
                    filteredCrossProfileDomainInfos.add(crossProfileDomainInfo);
                }
            }
        }

        return filteredCrossProfileDomainInfos;
    }

    /**
     * If current/source profile needs to be skipped, returns CrossProfileDomainInfo from target
     * profile. If any of the matchingFilters have flag {@link PackageManager#SKIP_CURRENT_PROFILE}
     * set that would signify that current profile needs to be skipped.
     * @param computer ComputerEngine instance that would be needed by ComponentResolverApi
     * @param matchingFilters {@link CrossProfileIntentFilter}s configured for source user,
     *                                                        targeting the targetUserId
     * @param intent request
     * @param resolvedType the MIME data type of intent request
     * @param flags for intent resolution
     * @param sourceUserId source user
     * @param pkgSettingFunction function to find PackageStateInternal for given package
     * @return CrossProfileDomainInfo if current profile needs to be skipped, else null
     */
    @Nullable
    private CrossProfileDomainInfo querySkipCurrentProfileIntents(Computer computer,
            List<CrossProfileIntentFilter> matchingFilters, Intent intent, String resolvedType,
            long flags, int sourceUserId,
            Function<String, PackageStateInternal> pkgSettingFunction) {
        if (matchingFilters != null) {
            int size = matchingFilters.size();
            for (int i = 0; i < size; i++) {
                CrossProfileIntentFilter filter = matchingFilters.get(i);
                if ((filter.getFlags() & PackageManager.SKIP_CURRENT_PROFILE) != 0) {
                    // Checking if there are activities in the target user that can handle the
                    // intent.
                    CrossProfileDomainInfo info = createForwardingResolveInfo(computer, filter,
                            intent, resolvedType, flags, sourceUserId, pkgSettingFunction);
                    if (info != null) {
                        return info;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Resolves and returns CrossProfileDomainInfo(ForwardingResolveInfo) from target profile if
     * current profile should be skipped when there is no result or if target profile should not
     * be skipped.
     *
     * @param computer ComputerEngine instance that would be needed by ComponentResolverApi
     * @param matchingFilters {@link CrossProfileIntentFilter}s configured for source user,
     *                                                        targeting the targetUserId
     * @param intent request
     * @param resolvedType the MIME data type of intent request
     * @param flags for intent resolution
     * @param sourceUserId source user
     * @param matchInCurrentProfile true if current/source profile have some non-negative
     *                              resolveInfo
     * @param pkgSettingFunction function to find PackageStateInternal for given package
     * @return CrossProfileDomainInfo returns forwarding intent resolver in CrossProfileDomainInfo.
     * It returns null if there are no matching filters or no valid/active activity available
     */
    @Nullable
    private CrossProfileDomainInfo queryCrossProfileIntents(Computer computer,
            List<CrossProfileIntentFilter> matchingFilters, Intent intent, String resolvedType,
            long flags, int sourceUserId, boolean matchInCurrentProfile,
            Function<String, PackageStateInternal> pkgSettingFunction) {
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
                CrossProfileDomainInfo info = createForwardingResolveInfo(computer, filter, intent,
                        resolvedType, flags, sourceUserId, pkgSettingFunction);
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

        List<CrossProfileDomainInfo> filteredResult =
                filterIfNotSystemUser(Collections.singletonList(resultInfo), sourceUserId);
        if (filteredResult.isEmpty()) {
            return null;
        }

        return resultInfo;
    }

    /**
     * Creates a Forwarding Resolve Info, used when we have to signify that target profile's
     * resolveInfo should be considered without providing list of resolve infos.
     * @param computer ComputerEngine instance that would be needed by ComponentResolverApi
     * @param filter {@link CrossProfileIntentFilter} configured for source user,
     *                                                        targeting the targetUserId
     * @param intent request
     * @param resolvedType the MIME data type of intent request
     * @param flags for intent resolution
     * @param sourceUserId source user
     * @return CrossProfileDomainInfo whose ResolveInfo is forwarding. It would be resolved by
     * {@link IntentForwarderActivity}. It returns null if there are no valid/active activities
     */
    @Nullable
    protected CrossProfileDomainInfo createForwardingResolveInfo(Computer computer,
            @NonNull CrossProfileIntentFilter filter, @NonNull Intent intent,
            @Nullable String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            int sourceUserId, @NonNull Function<String, PackageStateInternal> pkgSettingFunction) {
        int targetUserId = filter.getTargetUserId();
        if (!isUserEnabled(targetUserId)) {
            return null;
        }

        List<ResolveInfo> resultTargetUser = mComponentResolver.queryActivities(computer, intent,
                resolvedType, flags, Binder.getCallingUid(), targetUserId);
        if (CollectionUtils.isEmpty(resultTargetUser)) {
            return null;
        }

        ResolveInfo forwardingInfo = null;
        for (int i = resultTargetUser.size() - 1; i >= 0; i--) {
            ResolveInfo targetUserResolveInfo = resultTargetUser.get(i);
            if ((targetUserResolveInfo.activityInfo.applicationInfo.flags
                    & ApplicationInfo.FLAG_SUSPENDED) == 0) {
                forwardingInfo = computer.createForwardingResolveInfoUnchecked(filter, sourceUserId,
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
            PackageStateInternal ps = pkgSettingFunction.apply(packageName);
            if (ps == null) {
                continue;
            }
            highestApprovalLevel = Math.max(highestApprovalLevel, mDomainVerificationManager
                    .approvalLevelForDomain(ps, intent, flags, targetUserId));
        }

        return new CrossProfileDomainInfo(forwardingInfo, highestApprovalLevel, targetUserId);
    }
}
