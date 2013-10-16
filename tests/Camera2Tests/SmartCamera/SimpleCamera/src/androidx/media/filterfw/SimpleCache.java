/*
 * Copyright (C) 2011 The Android Open Source Project
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

package androidx.media.filterfw;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This is a simple LRU cache that is used internally for managing repetitive objects.
 */
class SimpleCache<K, V> extends LinkedHashMap<K, V> {

    private int mMaxEntries;

    public SimpleCache(final int maxEntries) {
        super(maxEntries + 1, 1f, true);
        mMaxEntries = maxEntries;
    }

    @Override
    protected boolean removeEldestEntry(final Map.Entry<K, V> eldest) {
        return super.size() > mMaxEntries;
    }
}
