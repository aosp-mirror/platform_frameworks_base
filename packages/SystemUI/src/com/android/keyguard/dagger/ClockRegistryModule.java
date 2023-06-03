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

package com.android.keyguard.dagger;

import android.content.Context;
import android.content.res.Resources;
import android.view.LayoutInflater;

import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.shared.clocks.ClockRegistry;
import com.android.systemui.shared.clocks.DefaultClockProvider;

import dagger.Module;
import dagger.Provides;
import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.CoroutineScope;

/** Dagger Module for clocks. */
@Module
public abstract class ClockRegistryModule {
    /** Provide the ClockRegistry as a singleton so that it is not instantiated more than once. */
    @Provides
    @SysUISingleton
    public static ClockRegistry getClockRegistry(
            @Application Context context,
            PluginManager pluginManager,
            @Application CoroutineScope scope,
            @Main CoroutineDispatcher mainDispatcher,
            @Background CoroutineDispatcher bgDispatcher,
            FeatureFlags featureFlags,
            @Main Resources resources,
            LayoutInflater layoutInflater) {
        ClockRegistry registry = new ClockRegistry(
                context,
                pluginManager,
                scope,
                mainDispatcher,
                bgDispatcher,
                featureFlags.isEnabled(Flags.LOCKSCREEN_CUSTOM_CLOCKS),
                /* handleAllUsers= */ true,
                new DefaultClockProvider(context, layoutInflater, resources),
                context.getString(R.string.lockscreen_clock_id_fallback));
        registry.registerListeners();
        return registry;
    }
}
