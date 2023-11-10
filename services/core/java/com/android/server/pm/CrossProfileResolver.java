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

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.UserHandle;

import com.android.internal.util.CollectionUtils;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.resolution.ComponentResolverApi;

import java.util.List;
import java.util.function.Function;

/**
 * Abstract Class act as base class for Cross Profile strategy.
 * This will be used by {@link CrossProfileIntentResolverEngine} to resolve intent across profile.
 */
public abstract class CrossProfileResolver {

    protected final ComponentResolverApi mComponentResolver;
    protected final UserManagerService mUserManager;

    public CrossProfileResolver(ComponentResolverApi componentResolver,
            UserManagerService userManager) {
        mComponentResolver = componentResolver;
        mUserManager = userManager;
    }

    /**
     * This method would be overridden by concrete implementation. This method should define how to
     * resolve given intent request in target profile.
     * @param computer ComputerEngine instance that would be needed by ComponentResolverApi
     * @param intent request
     * @param resolvedType the MIME data type of intent request
     * @param userId source/initiating user
     * @param targetUserId target user id
     * @param flags of intent request
     * @param pkgName package name if defined.
     * @param matchingFilters {@link CrossProfileIntentFilter}s configured for source user,
     *                                                        targeting the targetUserId
     * @param hasNonNegativePriorityResult if source have any non-negative(active and valid)
     *                                     resolveInfo in their profile.
     * @param pkgSettingFunction function to find PackageStateInternal for given package
     * @return list of {@link CrossProfileDomainInfo}
     */
    public abstract List<CrossProfileDomainInfo> resolveIntent(Computer computer, Intent intent,
            String resolvedType, int userId, int targetUserId, long flags,
            String pkgName, List<CrossProfileIntentFilter> matchingFilters,
            boolean hasNonNegativePriorityResult,
            Function<String, PackageStateInternal> pkgSettingFunction);

    /**
     * Filters the CrossProfileDomainInfos, the filtering technique would be defined by concrete
     * implementation class
     * @param intent request
     * @param crossProfileDomainInfos resolved in target user
     * @param flags for intent resolution
     * @param sourceUserId source user
     * @param targetUserId target user
     * @param highestApprovalLevel highest level of domain approval
     * @return filtered list of {@link CrossProfileDomainInfo}
     */
    public abstract List<CrossProfileDomainInfo> filterResolveInfoWithDomainPreferredActivity(
            Intent intent, List<CrossProfileDomainInfo> crossProfileDomainInfos, long flags,
            int sourceUserId, int targetUserId, int highestApprovalLevel);

    /**
     * Checks if mentioned user is enabled
     * @param userId of requested user
     * @return true if user is enabled
     */
    protected final boolean isUserEnabled(int userId) {
        final long callingId = Binder.clearCallingIdentity();
        try {
            UserInfo userInfo = mUserManager.getUserInfo(userId);
            return userInfo != null && userInfo.isEnabled();
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Filters out {@link CrossProfileDomainInfo} if they are not for any user apart from system
     * user. If mentioned user is system user, then returns all responses.
     * @param crossProfileDomainInfos result from resolution
     * @param userId source user id
     * @return filtered list of {@link CrossProfileDomainInfo}
     */
    protected final List<CrossProfileDomainInfo> filterIfNotSystemUser(
            List<CrossProfileDomainInfo> crossProfileDomainInfos, int userId) {
        if (userId == UserHandle.USER_SYSTEM) {
            return crossProfileDomainInfos;
        }

        for (int i = CollectionUtils.size(crossProfileDomainInfos) - 1; i >= 0; i--) {
            ResolveInfo info = crossProfileDomainInfos.get(i).mResolveInfo;
            if ((info.activityInfo.flags & ActivityInfo.FLAG_SYSTEM_USER_ONLY) != 0) {
                crossProfileDomainInfos.remove(i);
            }
        }
        return crossProfileDomainInfos;
    }

    /**
     * Returns user info of parent profile is applicable
     * @param userId requested user
     * @return parent's user info, null if parent is not present
     */
    protected final UserInfo getProfileParent(int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            return mUserManager.getProfileParent(userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
