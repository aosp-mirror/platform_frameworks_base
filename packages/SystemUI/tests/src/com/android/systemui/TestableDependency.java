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

package com.android.systemui;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.util.ArrayMap;
import android.util.ArraySet;

public class TestableDependency extends Dependency {
    private final ArrayMap<Object, Object> mObjs = new ArrayMap<>();
    private final ArraySet<Object> mInstantiatedObjects = new ArraySet<>();

    public TestableDependency(Context context) {
        mContext = context;
        if (SystemUIFactory.getInstance() == null) {
            SystemUIFactory.createFromConfig(context);
        }
        start();
    }

    public <T> T injectMockDependency(Class<T> cls) {
        final T mock = mock(cls);
        injectTestDependency(cls, mock);
        return mock;
    }

    public <T> void injectTestDependency(DependencyKey<T> key, T obj) {
        mObjs.put(key, obj);
    }

    public <T> void injectTestDependency(Class<T> key, T obj) {
        mObjs.put(key, obj);
    }

    @Override
    protected <T> T createDependency(Object key) {
        if (mObjs.containsKey(key)) return (T) mObjs.get(key);
        mInstantiatedObjects.add(key);
        return super.createDependency(key);
    }

    public <T> boolean hasInstantiatedDependency(Class<T> key) {
        return mObjs.containsKey(key) || mInstantiatedObjects.contains(key);
    }
}
