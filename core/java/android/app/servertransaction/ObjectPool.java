/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * An object pool that can provide reused objects if available.
 * @hide
 */
class ObjectPool {

    private static final Object sPoolSync = new Object();
    private static final Map<Class, LinkedList<? extends ObjectPoolItem>> sPoolMap =
            new HashMap<>();

    private static final int MAX_POOL_SIZE = 50;

    /**
     * Obtain an instance of a specific class from the pool
     * @param itemClass The class of the object we're looking for.
     * @return An instance or null if there is none.
     */
    public static <T extends ObjectPoolItem> T obtain(Class<T> itemClass) {
        synchronized (sPoolSync) {
            @SuppressWarnings("unchecked")
            LinkedList<T> itemPool = (LinkedList<T>) sPoolMap.get(itemClass);
            if (itemPool != null && !itemPool.isEmpty()) {
                return itemPool.poll();
            }
            return null;
        }
    }

    /**
     * Recycle the object to the pool. The object should be properly cleared before this.
     * @param item The object to recycle.
     * @see ObjectPoolItem#recycle()
     */
    public static <T extends ObjectPoolItem> void recycle(T item) {
        synchronized (sPoolSync) {
            @SuppressWarnings("unchecked")
            LinkedList<T> itemPool = (LinkedList<T>) sPoolMap.get(item.getClass());
            if (itemPool == null) {
                itemPool = new LinkedList<>();
                sPoolMap.put(item.getClass(), itemPool);
            }
            if (itemPool.contains(item)) {
                throw new IllegalStateException("Trying to recycle already recycled item");
            }

            if (itemPool.size() < MAX_POOL_SIZE) {
                itemPool.add(item);
            }
        }
    }
}
