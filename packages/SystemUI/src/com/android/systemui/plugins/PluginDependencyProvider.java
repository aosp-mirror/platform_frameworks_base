/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins;

import android.util.ArrayMap;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.PluginDependency.DependencyProvider;
import com.android.systemui.shared.plugins.PluginManager;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 */
@Singleton
public class PluginDependencyProvider extends DependencyProvider {

    private final ArrayMap<Class<?>, Object> mDependencies = new ArrayMap<>();
    private final PluginManager mManager;

    /**
     */
    @Inject
    public PluginDependencyProvider(PluginManager manager) {
        mManager = manager;
        PluginDependency.sProvider = this;
    }

    public <T> void allowPluginDependency(Class<T> cls) {
        allowPluginDependency(cls, Dependency.get(cls));
    }

    public <T> void allowPluginDependency(Class<T> cls, T obj) {
        synchronized (mDependencies) {
            mDependencies.put(cls, obj);
        }
    }

    @Override
    <T> T get(Plugin p, Class<T> cls) {
        if (!mManager.dependsOn(p, cls)) {
            throw new IllegalArgumentException(p.getClass() + " does not depend on " + cls);
        }
        synchronized (mDependencies) {
            if (!mDependencies.containsKey(cls)) {
                throw new IllegalArgumentException("Unknown dependency " + cls);
            }
            return (T) mDependencies.get(cls);
        }
    }
}
