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

package com.android.server.pm.resolution;

import android.annotation.NonNull;
import android.util.ArrayMap;

import com.android.server.pm.UserManagerService;
import com.android.server.pm.UserNeedsBadgingCache;

public class ComponentResolverSnapshot extends ComponentResolverBase {

    public ComponentResolverSnapshot(@NonNull ComponentResolver orig,
            @NonNull UserNeedsBadgingCache userNeedsBadgingCache) {
        super(UserManagerService.getInstance());
        mActivities = new ComponentResolver.ActivityIntentResolver(orig.mActivities, mUserManager,
                userNeedsBadgingCache);
        mProviders = new ComponentResolver.ProviderIntentResolver(orig.mProviders, mUserManager);
        mReceivers = new ComponentResolver.ReceiverIntentResolver(orig.mReceivers, mUserManager,
                userNeedsBadgingCache);
        mServices = new ComponentResolver.ServiceIntentResolver(orig.mServices, mUserManager);
        mProvidersByAuthority = new ArrayMap<>(orig.mProvidersByAuthority);
    }
}
