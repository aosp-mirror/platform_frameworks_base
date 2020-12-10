/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.settings.dagger;

import android.content.Context;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.settings.CurrentUserContentResolverProvider;
import com.android.systemui.settings.CurrentUserContextTracker;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module for classes found within the com.android.systemui.settings package.
 */
@Module
public abstract class SettingsModule {

    /**
     * Provides and initializes a CurrentUserContextTracker
     */
    @Singleton
    @Provides
    static CurrentUserContextTracker provideCurrentUserContextTracker(
            Context context,
            BroadcastDispatcher broadcastDispatcher) {
        CurrentUserContextTracker tracker =
                new CurrentUserContextTracker(context, broadcastDispatcher);
        tracker.initialize();
        return tracker;
    }

    @Binds
    @Singleton
    abstract CurrentUserContentResolverProvider bindCurrentUserContentResolverTracker(
            CurrentUserContextTracker tracker);
}
