/*
 * Copyright 2012, The Android Open Source Project
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#ifndef ANDROID_LINEARALLOCATOR_H
#define ANDROID_LINEARALLOCATOR_H

#include <stddef.h>
#include <type_traits>

namespace android {
namespace uirenderer {

/**
 * A memory manager that internally allocates multi-kbyte buffers for placing objects in. It avoids
 * the overhead of malloc when many objects are allocated. It is most useful when creating many
 * small objects with a similar lifetime, and doesn't add significant overhead for large
 * allocations.
 */
class LinearAllocator {
public:
    LinearAllocator();
    ~LinearAllocator();

    /**
     * Reserves and returns a region of memory of at least size 'size', aligning as needed.
     * Typically this is used in an object's overridden new() method or as a replacement for malloc.
     *
     * The lifetime of the returned buffers is tied to that of the LinearAllocator. If calling
     * delete() on an object stored in a buffer is needed, it should be overridden to use
     * rewindIfLastAlloc()
     */
    void* alloc(size_t size);

    /**
     * Allocates an instance of the template type with the default constructor
     * and adds it to the automatic destruction list.
     */
    template<class T>
    T* alloc() {
        T* ret = new (*this) T;
        autoDestroy(ret);
        return ret;
    }

    /**
     * Adds the pointer to the tracking list to have its destructor called
     * when the LinearAllocator is destroyed.
     */
    template<class T>
    void autoDestroy(T* addr) {
        if (!std::is_trivially_destructible<T>::value) {
            auto dtor = [](void* addr) { ((T*)addr)->~T(); };
            addToDestructionList(dtor, addr);
        }
    }

    /**
     * Attempt to deallocate the given buffer, with the LinearAllocator attempting to rewind its
     * state if possible.
     */
    void rewindIfLastAlloc(void* ptr, size_t allocSize);

    /**
     * Same as rewindIfLastAlloc(void*, size_t)
     */
    template<class T>
    void rewindIfLastAlloc(T* ptr) {
        rewindIfLastAlloc((void*)ptr, sizeof(T));
    }

    /**
     * Dump memory usage statistics to the log (allocated and wasted space)
     */
    void dumpMemoryStats(const char* prefix = "");

    /**
     * The number of bytes used for buffers allocated in the LinearAllocator (does not count space
     * wasted)
     */
    size_t usedSize() const { return mTotalAllocated - mWastedSpace; }

private:
    LinearAllocator(const LinearAllocator& other);

    class Page;
    typedef void (*Destructor)(void* addr);
    struct DestructorNode {
        Destructor dtor;
        void* addr;
        DestructorNode* next = nullptr;
    };

    void addToDestructionList(Destructor, void* addr);
    void runDestructorFor(void* addr);
    Page* newPage(size_t pageSize);
    bool fitsInCurrentPage(size_t size);
    void ensureNext(size_t size);
    void* start(Page *p);
    void* end(Page* p);

    size_t mPageSize;
    size_t mMaxAllocSize;
    void* mNext;
    Page* mCurrentPage;
    Page* mPages;
    DestructorNode* mDtorList = nullptr;

    // Memory usage tracking
    size_t mTotalAllocated;
    size_t mWastedSpace;
    size_t mPageCount;
    size_t mDedicatedPageCount;
};

}; // namespace uirenderer
}; // namespace android

void* operator new(std::size_t size, android::uirenderer::LinearAllocator& la);

#endif // ANDROID_LINEARALLOCATOR_H
