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

import com.android.systemui.R;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger injection module for {@link RecentsImplementation}
 */
@Module
public class RecentsModule {
    /**
     * @return The {@link RecentsImplementation} from the config.
     */
    @Provides
    public RecentsImplementation provideRecentsImpl(Context context) {
        final String clsName = context.getString(R.string.config_recentsComponent);
        if (clsName == null || clsName.length() == 0) {
            throw new RuntimeException("No recents component configured", null);
        }
        Class<?> cls = null;
        try {
            cls = context.getClassLoader().loadClass(clsName);
        } catch (Throwable t) {
            throw new RuntimeException("Error loading recents component: " + clsName, t);
        }
        try {
            RecentsImplementation impl = (RecentsImplementation) cls.newInstance();
            return impl;
        } catch (Throwable t) {
            throw new RuntimeException("Error creating recents component: " + clsName, t);
        }
    }
}
