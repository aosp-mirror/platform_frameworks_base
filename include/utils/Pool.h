/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef UTILS_POOL_H
#define UTILS_POOL_H

#include <utils/TypeHelpers.h>

namespace android {

class PoolImpl {
public:
    PoolImpl(size_t objSize);
    ~PoolImpl();

    void* allocImpl();
    void freeImpl(void* obj);

private:
    size_t mObjSize;
};

/*
 * A homogeneous typed memory pool for fixed size objects.
 * Not intended to be thread-safe.
 */
template<typename T>
class Pool : private PoolImpl {
public:
    /* Creates an initially empty pool. */
    Pool() : PoolImpl(sizeof(T)) { }

    /* Destroys the pool.
     * Assumes that the pool is empty. */
    ~Pool() { }

    /* Allocates an object from the pool, growing the pool if needed. */
    inline T* alloc() {
        void* mem = allocImpl();
        if (! traits<T>::has_trivial_ctor) {
            return new (mem) T();
        } else {
            return static_cast<T*>(mem);
        }
    }

    /* Frees an object from the pool. */
    inline void free(T* obj) {
        if (! traits<T>::has_trivial_dtor) {
            obj->~T();
        }
        freeImpl(obj);
    }
};

} // namespace android

#endif // UTILS_POOL_H
