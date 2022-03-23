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

import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

public class TestableDependency extends Dependency {
    private static final String TAG = "TestableDependency";

    private final ArrayMap<Object, Object> mObjs = new ArrayMap<>();
    private final ArraySet<Object> mInstantiatedObjects = new ArraySet<>();
    private final Dependency mParent;

    public TestableDependency(Dependency parent) {
        mParent = parent;
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
        if (mInstantiatedObjects.contains(key)) {
            Log.d(TAG, key + " was already initialized but overriding with testDependency.");
        }
        mObjs.put(key, obj);
    }

    @Override
    public <T> T createDependency(Object key) {
        if (mObjs.containsKey(key)) return (T) mObjs.get(key);

        mInstantiatedObjects.add(key);
        return mParent.createDependency(key);
    }

    public <T> boolean hasInstantiatedDependency(Class<T> key) {
        return mObjs.containsKey(key) || mInstantiatedObjects.contains(key);
    }
}
