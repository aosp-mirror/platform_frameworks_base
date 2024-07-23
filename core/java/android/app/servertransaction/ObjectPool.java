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

/**
 * An object pool that can provide reused objects if available.
 *
 * @hide
 * @deprecated This class is deprecated. Directly create new instances of objects instead of
 * obtaining them from this pool.
 * TODO(b/311089192): Clean up usages of the pool.
 */
@Deprecated
class ObjectPool {

    /**
     * Obtain an instance of a specific class from the pool
     *
     * @param ignoredItemClass The class of the object we're looking for.
     * @return An instance or null if there is none.
     * @deprecated This method is deprecated. Directly create new instances of objects instead of
     * obtaining them from this pool.
     */
    @Deprecated
    public static <T extends ObjectPoolItem> T obtain(Class<T> ignoredItemClass) {
        return null;
    }

    /**
     * Recycle the object to the pool. The object should be properly cleared before this.
     *
     * @param ignoredItem The object to recycle.
     * @see ObjectPoolItem#recycle()
     * @deprecated This method is deprecated. The object pool is no longer used, so there's
     * no need to recycle objects.
     */
    @Deprecated
    public static <T extends ObjectPoolItem> void recycle(T ignoredItem) {
    }
}
