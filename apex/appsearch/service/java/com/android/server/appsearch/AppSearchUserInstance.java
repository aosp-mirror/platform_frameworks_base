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
package com.android.server.appsearch;

import android.annotation.NonNull;

import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.stats.PlatformLogger;
import com.android.server.appsearch.visibilitystore.VisibilityStoreImpl;

import java.util.Objects;

/**
 * Container for AppSearch classes that should only be initialized once per device-user and make up
 * the core of the AppSearch system.
 */
public final class AppSearchUserInstance {
    private final PlatformLogger mLogger;
    private final AppSearchImpl mAppSearchImpl;
    private final VisibilityStoreImpl mVisibilityStore;

    AppSearchUserInstance(
            @NonNull PlatformLogger logger,
            @NonNull AppSearchImpl appSearchImpl,
            @NonNull VisibilityStoreImpl visibilityStore) {
        mLogger = Objects.requireNonNull(logger);
        mAppSearchImpl = Objects.requireNonNull(appSearchImpl);
        mVisibilityStore = Objects.requireNonNull(visibilityStore);
    }

    @NonNull
    public PlatformLogger getLogger() {
        return mLogger;
    }

    @NonNull
    public AppSearchImpl getAppSearchImpl() {
        return mAppSearchImpl;
    }

    @NonNull
    public VisibilityStoreImpl getVisibilityStore() {
        return mVisibilityStore;
    }
}
