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

#ifndef ANDROID_MEMORY_DEALER_H
#define ANDROID_MEMORY_DEALER_H


#include <stdint.h>
#include <sys/types.h>

#include <binder/IMemory.h>
#include <utils/threads.h>
#include <binder/MemoryHeapBase.h>

namespace android {
// ----------------------------------------------------------------------------
class String8;

/*
 * interface for implementing a "heap". A heap basically provides
 * the IMemoryHeap interface for cross-process sharing and the
 * ability to map/unmap pages within the heap.
 */
class HeapInterface : public virtual BnMemoryHeap
{
public:
    // all values must be page-aligned
    virtual sp<IMemory> mapMemory(size_t offset, size_t size) = 0;

    HeapInterface();
protected:
    virtual ~HeapInterface();
};

// ----------------------------------------------------------------------------

/*
 * interface for implementing an allocator. An allocator provides
 * methods for allocating and freeing memory blocks and dumping
 * its state.
 */
class AllocatorInterface : public RefBase
{
public:
    enum {
        PAGE_ALIGNED = 0x00000001
    };

    virtual size_t      allocate(size_t size, uint32_t flags = 0) = 0;
    virtual status_t    deallocate(size_t offset) = 0;
    virtual size_t      size() const = 0;
    virtual void        dump(const char* what, uint32_t flags = 0) const = 0;
    virtual void        dump(String8& res,
            const char* what, uint32_t flags = 0) const = 0;

    AllocatorInterface();
protected:
    virtual ~AllocatorInterface();
};

// ----------------------------------------------------------------------------

/*
 * concrete implementation of HeapInterface on top of mmap() 
 */
class SharedHeap : public HeapInterface, public MemoryHeapBase
{
public:
                        SharedHeap();
                        SharedHeap(size_t size, uint32_t flags = 0, char const * name = NULL);
    virtual             ~SharedHeap();
    virtual sp<IMemory> mapMemory(size_t offset, size_t size);
};

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


/*
 * concrete implementation of AllocatorInterface using a simple
 * best-fit allocation scheme
 */
class SimpleBestFitAllocator : public AllocatorInterface
{
public:

                        SimpleBestFitAllocator(size_t size);
    virtual             ~SimpleBestFitAllocator();

    virtual size_t      allocate(size_t size, uint32_t flags = 0);
    virtual status_t    deallocate(size_t offset);
    virtual size_t      size() const;
    virtual void        dump(const char* what, uint32_t flags = 0) const;
    virtual void        dump(String8& res,
            const char* what, uint32_t flags = 0) const;

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
    void     dump_l(const char* what, uint32_t flags = 0) const;
    void     dump_l(String8& res, const char* what, uint32_t flags = 0) const;

    static const int    kMemoryAlign;
    mutable Mutex       mLock;
    LinkedList<chunk_t> mList;
    size_t              mHeapSize;
};

// ----------------------------------------------------------------------------

class MemoryDealer : public RefBase
{
public:

    enum {
        READ_ONLY = MemoryHeapBase::READ_ONLY,
        PAGE_ALIGNED = AllocatorInterface::PAGE_ALIGNED
    };

    // creates a memory dealer with the SharedHeap and SimpleBestFitAllocator
    MemoryDealer(size_t size, uint32_t flags = 0, const char* name = 0);

    // provide a custom heap but use the SimpleBestFitAllocator
    MemoryDealer(const sp<HeapInterface>& heap);

    // provide both custom heap and allocotar
    MemoryDealer(
            const sp<HeapInterface>& heap,
            const sp<AllocatorInterface>& allocator);

    virtual sp<IMemory> allocate(size_t size, uint32_t flags = 0);
    virtual void        deallocate(size_t offset);
    virtual void        dump(const char* what, uint32_t flags = 0) const;


    sp<IMemoryHeap> getMemoryHeap() const { return heap(); }
    sp<AllocatorInterface> getAllocator() const { return allocator(); }

protected:
    virtual ~MemoryDealer();

private:    
    const sp<HeapInterface>&        heap() const;
    const sp<AllocatorInterface>&   allocator() const;

    class Allocation : public BnMemory {
    public:
        Allocation(const sp<MemoryDealer>& dealer,
                ssize_t offset, size_t size, const sp<IMemory>& memory);
        virtual ~Allocation();
        virtual sp<IMemoryHeap> getMemory(ssize_t* offset, size_t* size) const;
    private:
        sp<MemoryDealer>        mDealer;
        ssize_t                 mOffset;
        size_t                  mSize;
        sp<IMemory>             mMemory;
    };

    sp<HeapInterface>           mHeap;
    sp<AllocatorInterface>      mAllocator;
};


// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_MEMORY_DEALER_H
