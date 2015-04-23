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
     * Attempt to deallocate the given buffer, with the LinearAllocator attempting to rewind its
     * state if possible. No destructors are called.
     */
    void rewindIfLastAlloc(void* ptr, size_t allocSize);

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

    // Memory usage tracking
    size_t mTotalAllocated;
    size_t mWastedSpace;
    size_t mPageCount;
    size_t mDedicatedPageCount;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_LINEARALLOCATOR_H
