/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.util.leak;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Like WeakHashMap, but uses identity instead of equality when comparing keys.
 */
public class WeakIdentityHashMap<K,V> {

    private final HashMap<WeakReference<K>,V> mMap = new HashMap<>();
    private final ReferenceQueue<Object> mRefQueue = new ReferenceQueue<>();

    private void cleanUp() {
        Reference<?> ref;
        while ((ref = mRefQueue.poll()) != null) {
            mMap.remove(ref);
        }
    }

    public void put(K key, V value) {
        cleanUp();
        mMap.put(new CmpWeakReference<>(key, mRefQueue), value);
    }

    public V get(K key) {
        cleanUp();
        return mMap.get(new CmpWeakReference<>(key));
    }

    public Collection<V> values() {
        cleanUp();
        return mMap.values();
    }

    public Set<Map.Entry<WeakReference<K>, V>> entrySet() {
        return mMap.entrySet();
    }

    public int size() {
        cleanUp();
        return mMap.size();
    }

    public boolean isEmpty() {
        cleanUp();
        return mMap.isEmpty();
    }

    private static class CmpWeakReference<K> extends WeakReference<K> {
        private final int mHashCode;

        public CmpWeakReference(K key) {
            super(key);
            mHashCode = System.identityHashCode(key);
        }

        public CmpWeakReference(K key, ReferenceQueue<Object> refQueue) {
            super(key, refQueue);
            mHashCode = System.identityHashCode(key);
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            K k = get();
            if (k != null && o instanceof CmpWeakReference) {
                return ((CmpWeakReference) o).get() == k;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return mHashCode;
        }
    }
}
