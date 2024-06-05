/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.recents;

import android.content.Context;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.ContextComponentHelper;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;

/**
 * Dagger injection module for {@link RecentsImplementation}
 */
@Module
public abstract class RecentsModule {

    /** Start Recents.  */
    @Binds
    @IntoMap
    @ClassKey(Recents.class)
    abstract CoreStartable bindRecentsStartable(Recents impl);

    /** Listen to config changes for Recents.  */
    @Binds
    @IntoSet
    abstract ConfigurationListener bindRecentsConfigChanges(Recents impl);

    /** Start ScreenPinningRequest.  */
    @Binds
    @IntoMap
    @ClassKey(ScreenPinningRequest.class)
    abstract CoreStartable bindScreenPinningRequestStartable(ScreenPinningRequest impl);

    /** Listen to config changes for ScreenPinningRequest.  */
    @Binds
    @IntoSet
    abstract ConfigurationListener bindScreenPinningRequestConfigChanges(ScreenPinningRequest impl);

    /**
     * @return The {@link RecentsImplementation} from the config.
     */
    @Provides
    public static RecentsImplementation provideRecentsImpl(Context context,
            ContextComponentHelper componentHelper) {
        final String clsName = context.getString(R.string.config_recentsComponent);
        if (clsName == null || clsName.length() == 0) {
            throw new RuntimeException("No recents component configured", null);
        }
        RecentsImplementation impl = componentHelper.resolveRecents(clsName);

        if (impl == null) {
            Class<?> cls = null;
            try {
                cls = context.getClassLoader().loadClass(clsName);
            } catch (Throwable t) {
                throw new RuntimeException("Error loading recents component: " + clsName, t);
            }
            try {
                impl = (RecentsImplementation) cls.newInstance();
            } catch (Throwable t) {
                throw new RuntimeException("Error creating recents component: " + clsName, t);
            }
        }

        return impl;
    }

    /** */
    @Binds
    @IntoMap
    @ClassKey(OverviewProxyRecentsImpl.class)
    public abstract RecentsImplementation bindOverviewProxyRecentsImpl(
            OverviewProxyRecentsImpl impl);
}
