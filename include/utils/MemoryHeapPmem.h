/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef ANDROID_MEMORY_HEAP_PMEM_H
#define ANDROID_MEMORY_HEAP_PMEM_H

#include <stdlib.h>
#include <stdint.h>

#include <utils/MemoryDealer.h>
#include <utils/MemoryHeapBase.h>
#include <utils/IMemory.h>
#include <utils/Vector.h>

namespace android {

class MemoryHeapBase;

// ---------------------------------------------------------------------------

class SubRegionMemory;

class MemoryHeapPmem : public HeapInterface, public MemoryHeapBase
{
public:
    MemoryHeapPmem(const sp<MemoryHeapBase>& pmemHeap,
                uint32_t flags = IMemoryHeap::MAP_ONCE);
    ~MemoryHeapPmem();

    /* HeapInterface additions */
    virtual sp<IMemory> mapMemory(size_t offset, size_t size);

    /* make the whole heap visible (you know who you are) */
    virtual status_t slap();
    
    /* hide (revoke) the whole heap (the client will see the garbage page) */
    virtual status_t unslap();
    
    /* revoke all allocations made by this heap */
    virtual void revoke();
    
private:
    sp<MemoryHeapBase>              mParentHeap;
    mutable Mutex                   mLock;
    Vector< wp<SubRegionMemory> >   mAllocations;
};


// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_MEMORY_HEAP_PMEM_H
