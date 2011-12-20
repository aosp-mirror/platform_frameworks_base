/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "MemoryDealer"

#include <binder/MemoryDealer.h>
#include <binder/IPCThreadState.h>
#include <binder/MemoryBase.h>

#include <utils/Log.h>
#include <utils/SortedVector.h>
#include <utils/String8.h>
#include <utils/threads.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>

#include <sys/stat.h>
#include <sys/types.h>
#include <sys/mman.h>
#include <sys/file.h>

namespace android {
// ----------------------------------------------------------------------------

/*
 * A simple templatized doubly linked-list implementation
 */

template <typename NODE>
class LinkedList
{
    NODE*  mFirst;
    NODE*  mLast;

public:
                LinkedList() : mFirst(0), mLast(0) { }
    bool        isEmpty() const { return mFirst == 0; }
    NODE const* head() const { return mFirst; }
    NODE*       head() { return mFirst; }
    NODE const* tail() const { return mLast; }
    NODE*       tail() { return mLast; }

    void insertAfter(NODE* node, NODE* newNode) {
        newNode->prev = node;
        newNode->next = node->next;
        if (node->next == 0) mLast = newNode;
        else                 node->next->prev = newNode;
        node->next = newNode;
    }

    void insertBefore(NODE* node, NODE* newNode) {
         newNode->prev = node->prev;
         newNode->next = node;
         if (node->prev == 0)   mFirst = newNode;
         else                   node->prev->next = newNode;
         node->prev = newNode;
    }

    void insertHead(NODE* newNode) {
        if (mFirst == 0) {
            mFirst = mLast = newNode;
            newNode->prev = newNode->next = 0;
        } else {
            newNode->prev = 0;
            newNode->next = mFirst;
            mFirst->prev = newNode;
            mFirst = newNode;
        }
    }

    void insertTail(NODE* newNode) {
        if (mLast == 0) {
            insertHead(newNode);
        } else {
            newNode->prev = mLast;
            newNode->next = 0;
            mLast->next = newNode;
            mLast = newNode;
        }
    }

    NODE* remove(NODE* node) {
        if (node->prev == 0)    mFirst = node->next;
        else                    node->prev->next = node->next;
        if (node->next == 0)    mLast = node->prev;
        else                    node->next->prev = node->prev;
        return node;
    }
};

// ----------------------------------------------------------------------------

class Allocation : public MemoryBase {
public:
    Allocation(const sp<MemoryDealer>& dealer,
            const sp<IMemoryHeap>& heap, ssize_t offset, size_t size);
    virtual ~Allocation();
private:
    sp<MemoryDealer> mDealer;
};

// ----------------------------------------------------------------------------

class SimpleBestFitAllocator
{
    enum {
        PAGE_ALIGNED = 0x00000001
    };
public:
    SimpleBestFitAllocator(size_t size);
    ~SimpleBestFitAllocator();

    size_t      allocate(size_t size, uint32_t flags = 0);
    status_t    deallocate(size_t offset);
    size_t      size() const;
    void        dump(const char* what) const;
    void        dump(String8& res, const char* what) const;

private:

    struct chunk_t {
        chunk_t(size_t start, size_t size)
        : start(start), size(size), free(1), prev(0), next(0) {
        }
        size_t              start;
        size_t              size : 28;
        int                 free : 4;
        mutable chunk_t*    prev;
        mutable chunk_t*    next;
    };

    ssize_t  alloc(size_t size, uint32_t flags);
    chunk_t* dealloc(size_t start);
    void     dump_l(const char* what) const;
    void     dump_l(String8& res, const char* what) const;

    static const int    kMemoryAlign;
    mutable Mutex       mLock;
    LinkedList<chunk_t> mList;
    size_t              mHeapSize;
};

// ----------------------------------------------------------------------------

Allocation::Allocation(
        const sp<MemoryDealer>& dealer,
        const sp<IMemoryHeap>& heap, ssize_t offset, size_t size)
    : MemoryBase(heap, offset, size), mDealer(dealer)
{
#ifndef NDEBUG
    void* const start_ptr = (void*)(intptr_t(heap->base()) + offset);
    memset(start_ptr, 0xda, size);
#endif
}

Allocation::~Allocation()
{
    size_t freedOffset = getOffset();
    size_t freedSize   = getSize();
    if (freedSize) {
        /* NOTE: it's VERY important to not free allocations of size 0 because
         * they're special as they don't have any record in the allocator
         * and could alias some real allocation (their offset is zero). */

        // keep the size to unmap in excess
        size_t pagesize = getpagesize();
        size_t start = freedOffset;
        size_t end = start + freedSize;
        start &= ~(pagesize-1);
        end = (end + pagesize-1) & ~(pagesize-1);

        // give back to the kernel the pages we don't need
        size_t free_start = freedOffset;
        size_t free_end = free_start + freedSize;
        if (start < free_start)
            start = free_start;
        if (end > free_end)
            end = free_end;
        start = (start + pagesize-1) & ~(pagesize-1);
        end &= ~(pagesize-1);

        if (start < end) {
            void* const start_ptr = (void*)(intptr_t(getHeap()->base()) + start);
            size_t size = end-start;

#ifndef NDEBUG
            memset(start_ptr, 0xdf, size);
#endif

            // MADV_REMOVE is not defined on Dapper based Goobuntu
#ifdef MADV_REMOVE
            if (size) {
                int err = madvise(start_ptr, size, MADV_REMOVE);
                LOGW_IF(err, "madvise(%p, %u, MADV_REMOVE) returned %s",
                        start_ptr, size, err<0 ? strerror(errno) : "Ok");
            }
#endif
        }

        // This should be done after madvise(MADV_REMOVE), otherwise madvise()
        // might kick out the memory region that's allocated and/or written
        // right after the deallocation.
        mDealer->deallocate(freedOffset);
    }
}

// ----------------------------------------------------------------------------

MemoryDealer::MemoryDealer(size_t size, const char* name)
    : mHeap(new MemoryHeapBase(size, 0, name)),
    mAllocator(new SimpleBestFitAllocator(size))
{    
}

MemoryDealer::~MemoryDealer()
{
    delete mAllocator;
}

sp<IMemory> MemoryDealer::allocate(size_t size)
{
    sp<IMemory> memory;
    const ssize_t offset = allocator()->allocate(size);
    if (offset >= 0) {
        memory = new Allocation(this, heap(), offset, size);
    }
    return memory;
}

void MemoryDealer::deallocate(size_t offset)
{
    allocator()->deallocate(offset);
}

void MemoryDealer::dump(const char* what) const
{
    allocator()->dump(what);
}

const sp<IMemoryHeap>& MemoryDealer::heap() const {
    return mHeap;
}

SimpleBestFitAllocator* MemoryDealer::allocator() const {
    return mAllocator;
}

// ----------------------------------------------------------------------------

// align all the memory blocks on a cache-line boundary
const int SimpleBestFitAllocator::kMemoryAlign = 32;

SimpleBestFitAllocator::SimpleBestFitAllocator(size_t size)
{
    size_t pagesize = getpagesize();
    mHeapSize = ((size + pagesize-1) & ~(pagesize-1));

    chunk_t* node = new chunk_t(0, mHeapSize / kMemoryAlign);
    mList.insertHead(node);
}

SimpleBestFitAllocator::~SimpleBestFitAllocator()
{
    while(!mList.isEmpty()) {
        delete mList.remove(mList.head());
    }
}

size_t SimpleBestFitAllocator::size() const
{
    return mHeapSize;
}

size_t SimpleBestFitAllocator::allocate(size_t size, uint32_t flags)
{
    Mutex::Autolock _l(mLock);
    ssize_t offset = alloc(size, flags);
    return offset;
}

status_t SimpleBestFitAllocator::deallocate(size_t offset)
{
    Mutex::Autolock _l(mLock);
    chunk_t const * const freed = dealloc(offset);
    if (freed) {
        return NO_ERROR;
    }
    return NAME_NOT_FOUND;
}

ssize_t SimpleBestFitAllocator::alloc(size_t size, uint32_t flags)
{
    if (size == 0) {
        return 0;
    }
    size = (size + kMemoryAlign-1) / kMemoryAlign;
    chunk_t* free_chunk = 0;
    chunk_t* cur = mList.head();

    size_t pagesize = getpagesize();
    while (cur) {
        int extra = 0;
        if (flags & PAGE_ALIGNED)
            extra = ( -cur->start & ((pagesize/kMemoryAlign)-1) ) ;

        // best fit
        if (cur->free && (cur->size >= (size+extra))) {
            if ((!free_chunk) || (cur->size < free_chunk->size)) {
                free_chunk = cur;
            }
            if (cur->size == size) {
                break;
            }
        }
        cur = cur->next;
    }

    if (free_chunk) {
        const size_t free_size = free_chunk->size;
        free_chunk->free = 0;
        free_chunk->size = size;
        if (free_size > size) {
            int extra = 0;
            if (flags & PAGE_ALIGNED)
                extra = ( -free_chunk->start & ((pagesize/kMemoryAlign)-1) ) ;
            if (extra) {
                chunk_t* split = new chunk_t(free_chunk->start, extra);
                free_chunk->start += extra;
                mList.insertBefore(free_chunk, split);
            }

            LOGE_IF((flags&PAGE_ALIGNED) && 
                    ((free_chunk->start*kMemoryAlign)&(pagesize-1)),
                    "PAGE_ALIGNED requested, but page is not aligned!!!");

            const ssize_t tail_free = free_size - (size+extra);
            if (tail_free > 0) {
                chunk_t* split = new chunk_t(
                        free_chunk->start + free_chunk->size, tail_free);
                mList.insertAfter(free_chunk, split);
            }
        }
        return (free_chunk->start)*kMemoryAlign;
    }
    return NO_MEMORY;
}

SimpleBestFitAllocator::chunk_t* SimpleBestFitAllocator::dealloc(size_t start)
{
    start = start / kMemoryAlign;
    chunk_t* cur = mList.head();
    while (cur) {
        if (cur->start == start) {
            LOG_FATAL_IF(cur->free,
                "block at offset 0x%08lX of size 0x%08lX already freed",
                cur->start*kMemoryAlign, cur->size*kMemoryAlign);

            // merge freed blocks together
            chunk_t* freed = cur;
            cur->free = 1;
            do {
                chunk_t* const p = cur->prev;
                chunk_t* const n = cur->next;
                if (p && (p->free || !cur->size)) {
                    freed = p;
                    p->size += cur->size;
                    mList.remove(cur);
                    delete cur;
                }
                cur = n;
            } while (cur && cur->free);

            #ifndef NDEBUG
                if (!freed->free) {
                    dump_l("dealloc (!freed->free)");
                }
            #endif
            LOG_FATAL_IF(!freed->free,
                "freed block at offset 0x%08lX of size 0x%08lX is not free!",
                freed->start * kMemoryAlign, freed->size * kMemoryAlign);

            return freed;
        }
        cur = cur->next;
    }
    return 0;
}

void SimpleBestFitAllocator::dump(const char* what) const
{
    Mutex::Autolock _l(mLock);
    dump_l(what);
}

void SimpleBestFitAllocator::dump_l(const char* what) const
{
    String8 result;
    dump_l(result, what);
    ALOGD("%s", result.string());
}

void SimpleBestFitAllocator::dump(String8& result,
        const char* what) const
{
    Mutex::Autolock _l(mLock);
    dump_l(result, what);
}

void SimpleBestFitAllocator::dump_l(String8& result,
        const char* what) const
{
    size_t size = 0;
    int32_t i = 0;
    chunk_t const* cur = mList.head();
    
    const size_t SIZE = 256;
    char buffer[SIZE];
    snprintf(buffer, SIZE, "  %s (%p, size=%u)\n",
            what, this, (unsigned int)mHeapSize);
    
    result.append(buffer);
            
    while (cur) {
        const char* errs[] = {"", "| link bogus NP",
                            "| link bogus PN", "| link bogus NP+PN" };
        int np = ((cur->next) && cur->next->prev != cur) ? 1 : 0;
        int pn = ((cur->prev) && cur->prev->next != cur) ? 2 : 0;

        snprintf(buffer, SIZE, "  %3u: %08x | 0x%08X | 0x%08X | %s %s\n",
            i, int(cur), int(cur->start*kMemoryAlign),
            int(cur->size*kMemoryAlign),
                    int(cur->free) ? "F" : "A",
                    errs[np|pn]);
        
        result.append(buffer);

        if (!cur->free)
            size += cur->size*kMemoryAlign;

        i++;
        cur = cur->next;
    }
    snprintf(buffer, SIZE,
            "  size allocated: %u (%u KB)\n", int(size), int(size/1024));
    result.append(buffer);
}


}; // namespace android
