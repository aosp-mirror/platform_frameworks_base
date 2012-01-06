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

#define LOG_TAG "MemoryHeapPmem"

#include <stdlib.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/ioctl.h>

#include <cutils/log.h>

#include <binder/MemoryHeapPmem.h>
#include <binder/MemoryHeapBase.h>

#ifdef HAVE_ANDROID_OS
#include <linux/android_pmem.h>
#endif

namespace android {

// ---------------------------------------------------------------------------

MemoryHeapPmem::MemoryPmem::MemoryPmem(const sp<MemoryHeapPmem>& heap)
    : BnMemory(), mClientHeap(heap)
{
}

MemoryHeapPmem::MemoryPmem::~MemoryPmem() {
    if (mClientHeap != NULL) {
        mClientHeap->remove(this);
    }
}

// ---------------------------------------------------------------------------

class SubRegionMemory : public MemoryHeapPmem::MemoryPmem {
public:
    SubRegionMemory(const sp<MemoryHeapPmem>& heap, ssize_t offset, size_t size);
    virtual ~SubRegionMemory();
    virtual sp<IMemoryHeap> getMemory(ssize_t* offset, size_t* size) const;
private:
    friend class MemoryHeapPmem;
    void revoke();
    size_t              mSize;
    ssize_t             mOffset;
};

SubRegionMemory::SubRegionMemory(const sp<MemoryHeapPmem>& heap,
        ssize_t offset, size_t size)
    : MemoryHeapPmem::MemoryPmem(heap), mSize(size), mOffset(offset)
{
#ifndef NDEBUG
    void* const start_ptr = (void*)(intptr_t(getHeap()->base()) + offset);
    memset(start_ptr, 0xda, size);
#endif

#ifdef HAVE_ANDROID_OS
    if (size > 0) {
        const size_t pagesize = getpagesize();
        size = (size + pagesize-1) & ~(pagesize-1);
        int our_fd = heap->heapID();
        struct pmem_region sub = { offset, size };
        int err = ioctl(our_fd, PMEM_MAP, &sub);
        ALOGE_IF(err<0, "PMEM_MAP failed (%s), "
                "mFD=%d, sub.offset=%lu, sub.size=%lu",
                strerror(errno), our_fd, sub.offset, sub.len);
}
#endif
}

sp<IMemoryHeap> SubRegionMemory::getMemory(ssize_t* offset, size_t* size) const
{
    if (offset) *offset = mOffset;
    if (size)   *size = mSize;
    return getHeap();
}

SubRegionMemory::~SubRegionMemory()
{
    revoke();
}


void SubRegionMemory::revoke()
{
    // NOTE: revoke() doesn't need to be protected by a lock because it
    // can only be called from MemoryHeapPmem::revoke(), which means
    // that we can't be in ~SubRegionMemory(), or in ~SubRegionMemory(),
    // which means MemoryHeapPmem::revoke() wouldn't have been able to 
    // promote() it.
    
#ifdef HAVE_ANDROID_OS
    if (mSize != 0) {
        const sp<MemoryHeapPmem>& heap(getHeap());
        int our_fd = heap->heapID();
        struct pmem_region sub;
        sub.offset = mOffset;
        sub.len = mSize;
        int err = ioctl(our_fd, PMEM_UNMAP, &sub);
        ALOGE_IF(err<0, "PMEM_UNMAP failed (%s), "
                "mFD=%d, sub.offset=%lu, sub.size=%lu",
                strerror(errno), our_fd, sub.offset, sub.len);
        mSize = 0;
    }
#endif
}

// ---------------------------------------------------------------------------

MemoryHeapPmem::MemoryHeapPmem(const sp<MemoryHeapBase>& pmemHeap,
        uint32_t flags)
    : MemoryHeapBase()
{
    char const * const device = pmemHeap->getDevice();
#ifdef HAVE_ANDROID_OS
    if (device) {
        int fd = open(device, O_RDWR | (flags & NO_CACHING ? O_SYNC : 0));
        ALOGE_IF(fd<0, "couldn't open %s (%s)", device, strerror(errno));
        if (fd >= 0) {
            int err = ioctl(fd, PMEM_CONNECT, pmemHeap->heapID());
            if (err < 0) {
                ALOGE("PMEM_CONNECT failed (%s), mFD=%d, sub-fd=%d",
                        strerror(errno), fd, pmemHeap->heapID());
                close(fd);
            } else {
                // everything went well...
                mParentHeap = pmemHeap;
                MemoryHeapBase::init(fd, 
                        pmemHeap->getBase(),
                        pmemHeap->getSize(),
                        pmemHeap->getFlags() | flags,
                        device);
            }
        }
    }
#else
    mParentHeap = pmemHeap;
    MemoryHeapBase::init( 
            dup(pmemHeap->heapID()),
            pmemHeap->getBase(),
            pmemHeap->getSize(),
            pmemHeap->getFlags() | flags,
            device);
#endif
}

MemoryHeapPmem::~MemoryHeapPmem()
{
}

sp<IMemory> MemoryHeapPmem::mapMemory(size_t offset, size_t size)
{
    sp<MemoryPmem> memory = createMemory(offset, size);
    if (memory != 0) {
        Mutex::Autolock _l(mLock);
        mAllocations.add(memory);
    }
    return memory;
}

sp<MemoryHeapPmem::MemoryPmem> MemoryHeapPmem::createMemory(
        size_t offset, size_t size)
{
    sp<SubRegionMemory> memory;
    if (heapID() > 0) 
        memory = new SubRegionMemory(this, offset, size);
    return memory;
}

status_t MemoryHeapPmem::slap()
{
#ifdef HAVE_ANDROID_OS
    size_t size = getSize();
    const size_t pagesize = getpagesize();
    size = (size + pagesize-1) & ~(pagesize-1);
    int our_fd = getHeapID();
    struct pmem_region sub = { 0, size };
    int err = ioctl(our_fd, PMEM_MAP, &sub);
    ALOGE_IF(err<0, "PMEM_MAP failed (%s), "
            "mFD=%d, sub.offset=%lu, sub.size=%lu",
            strerror(errno), our_fd, sub.offset, sub.len);
    return -errno;
#else
    return NO_ERROR;
#endif
}

status_t MemoryHeapPmem::unslap()
{
#ifdef HAVE_ANDROID_OS
    size_t size = getSize();
    const size_t pagesize = getpagesize();
    size = (size + pagesize-1) & ~(pagesize-1);
    int our_fd = getHeapID();
    struct pmem_region sub = { 0, size };
    int err = ioctl(our_fd, PMEM_UNMAP, &sub);
    ALOGE_IF(err<0, "PMEM_UNMAP failed (%s), "
            "mFD=%d, sub.offset=%lu, sub.size=%lu",
            strerror(errno), our_fd, sub.offset, sub.len);
    return -errno;
#else
    return NO_ERROR;
#endif
}

void MemoryHeapPmem::revoke()
{
    SortedVector< wp<MemoryPmem> > allocations;

    { // scope for lock
        Mutex::Autolock _l(mLock);
        allocations = mAllocations;
    }
    
    ssize_t count = allocations.size();
    for (ssize_t i=0 ; i<count ; i++) {
        sp<MemoryPmem> memory(allocations[i].promote());
        if (memory != 0)
            memory->revoke();
    }
}

void MemoryHeapPmem::remove(const wp<MemoryPmem>& memory)
{
    Mutex::Autolock _l(mLock);
    mAllocations.remove(memory);
}

// ---------------------------------------------------------------------------
}; // namespace android
