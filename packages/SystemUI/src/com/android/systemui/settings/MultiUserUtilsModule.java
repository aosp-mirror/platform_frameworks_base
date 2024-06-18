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

package com.android.systemui.settings;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.UserManager;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlagsClassic;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

import javax.inject.Provider;

import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.CoroutineScope;

/**
 * Dagger Module for classes found within the com.android.systemui.settings package.
 */
@Module
public abstract class MultiUserUtilsModule {
    @Binds
    @SysUISingleton
    abstract UserContextProvider bindUserContextProvider(UserTracker tracker);

    @Binds
    @SysUISingleton
    abstract UserContentResolverProvider bindUserContentResolverProvider(
            UserTracker tracker);

    @SysUISingleton
    @Provides
    static UserTracker provideUserTracker(
            Context context,
            Provider<FeatureFlagsClassic> featureFlagsProvider,
            UserManager userManager,
            IActivityManager iActivityManager,
            DumpManager dumpManager,
            @Application CoroutineScope appScope,
            @Background CoroutineDispatcher backgroundDispatcher,
            @Background Handler handler
    ) {
        int startingUser = ActivityManager.getCurrentUser();
        UserTrackerImpl tracker = new UserTrackerImpl(context, featureFlagsProvider, userManager,
                iActivityManager, dumpManager, appScope, backgroundDispatcher, handler);
        tracker.initialize(startingUser);
        return tracker;
    }

    @SysUISingleton
    @Provides
    static DisplayTracker provideDisplayTracker(
            DisplayManager displayManager,
            @Background Handler handler
    ) {
        return new DisplayTrackerImpl(displayManager, handler);
    }

    @Binds
    @IntoMap
    @ClassKey(UserFileManagerImpl.class)
    abstract CoreStartable bindUserFileManagerCoreStartable(UserFileManagerImpl sysui);

    @Binds
    abstract UserFileManager bindUserFileManager(UserFileManagerImpl impl);
}
