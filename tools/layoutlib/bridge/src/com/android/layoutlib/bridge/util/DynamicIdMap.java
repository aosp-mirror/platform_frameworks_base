/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.layoutlib.bridge.util;

import com.android.annotations.NonNull;
import com.android.resources.ResourceType;
import com.android.util.Pair;

import android.util.SparseArray;

import java.util.HashMap;
import java.util.Map;

public class DynamicIdMap {

    private final Map<Pair<ResourceType, String>, Integer> mDynamicIds = new HashMap<Pair<ResourceType, String>, Integer>();
    private final SparseArray<Pair<ResourceType, String>> mRevDynamicIds = new SparseArray<Pair<ResourceType, String>>();
    private int mDynamicSeed;

    public DynamicIdMap(int seed) {
        mDynamicSeed = seed;
    }

    public void reset(int seed) {
        mDynamicIds.clear();
        mRevDynamicIds.clear();
        mDynamicSeed = seed;
    }

    /**
     * Returns a dynamic integer for the given resource type/name, creating it if it doesn't
     * already exist.
     *
     * @param type the type of the resource
     * @param name the name of the resource
     * @return an integer.
     */
    @NonNull
    public Integer getId(ResourceType type, String name) {
        return getId(Pair.of(type, name));
    }

    /**
     * Returns a dynamic integer for the given resource type/name, creating it if it doesn't
     * already exist.
     *
     * @param resource the type/name of the resource
     * @return an integer.
     */
    @NonNull
    public Integer getId(Pair<ResourceType, String> resource) {
        Integer value = mDynamicIds.get(resource);
        if (value == null) {
            value = ++mDynamicSeed;
            mDynamicIds.put(resource, value);
            mRevDynamicIds.put(value, resource);
        }

        return value;
    }

    public Pair<ResourceType, String> resolveId(int id) {
        return mRevDynamicIds.get(id);
    }
}
