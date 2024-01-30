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

import android.content.Context;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.util.ArraySet;

/**
 * Helper class to manage {@link com.android.server.pm.CrossProfileIntentFilter}s.
 */
public class CrossProfileIntentFilterHelper {
    private final Context mContext;
    private final UserManagerInternal mUserManagerInternal;
    private final Settings mSettings;
    private final UserManagerService mUserManagerService;
    private final PackageManagerTracedLock mLock;

    public CrossProfileIntentFilterHelper(Settings settings, UserManagerService userManagerService,
            PackageManagerTracedLock lock, UserManagerInternal userManagerInternal,
            Context context) {
        mSettings = settings;
        mUserManagerService = userManagerService;
        mLock = lock;
        mContext = context;
        mUserManagerInternal = userManagerInternal;
    }

    /**
     * For users that have
     * {@link android.content.pm.UserProperties#getUpdateCrossProfileIntentFiltersOnOTA} set, this
     * task will update default {@link com.android.server.pm.CrossProfileIntentFilter} between that
     * user and its parent. This will only update CrossProfileIntentFilters set by system package.
     * The new default are configured in {@link UserTypeDetails}.
     */
    public void updateDefaultCrossProfileIntentFilter() {
        for (UserInfo userInfo : mUserManagerInternal.getUsers(false)) {

            UserProperties currentUserProperties = mUserManagerInternal
                    .getUserProperties(userInfo.id);

            if (currentUserProperties != null
                    && currentUserProperties.getUpdateCrossProfileIntentFiltersOnOTA()) {
                int parentUserId = mUserManagerInternal.getProfileParentId(userInfo.id);
                if (parentUserId != userInfo.id) {
                    clearCrossProfileIntentFilters(userInfo.id,
                            mContext.getOpPackageName(), parentUserId);
                    clearCrossProfileIntentFilters(parentUserId,
                            mContext.getOpPackageName(),  userInfo.id);

                    mUserManagerInternal.setDefaultCrossProfileIntentFilters(parentUserId,
                            userInfo.id);
                }
            }
        }
    }

    /**
     * Clear {@link CrossProfileIntentFilter}s configured on source user by ownerPackage
     * targeting the targetUserId. If targetUserId is null then it will clear
     * {@link CrossProfileIntentFilter} for any target user.
     * @param sourceUserId source user for whom CrossProfileIntentFilter would be configured
     * @param ownerPackage package who would have configured CrossProfileIntentFilter
     * @param targetUserId user id for which CrossProfileIntentFilter will be removed.
     *                     This can be null in which case it will clear for any target user.
     */
    public void clearCrossProfileIntentFilters(int sourceUserId, String ownerPackage,
            Integer targetUserId) {
        synchronized (mLock) {
            CrossProfileIntentResolver resolver = mSettings
                    .editCrossProfileIntentResolverLPw(sourceUserId);
            ArraySet<CrossProfileIntentFilter> set =
                    new ArraySet<>(resolver.filterSet());
            for (CrossProfileIntentFilter filter : set) {
                //Only remove if calling user is allowed based on access control of
                // {@link CrossProfileIntentFilter}
                if (filter.getOwnerPackage().equals(ownerPackage)
                        && (targetUserId == null || filter.mTargetUserId == targetUserId)
                        && mUserManagerService.isCrossProfileIntentFilterAccessible(sourceUserId,
                        filter.mTargetUserId, /* addCrossProfileIntentFilter */ false)) {
                    resolver.removeFilter(filter);
                }
            }
        }
    }
}
