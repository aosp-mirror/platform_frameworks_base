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

#define LOG_NDEBUG 1

#include "utils/LinearAllocator.h"

#include <stdlib.h>
#include <utils/Log.h>
#include <utils/Macros.h>

// The maximum amount of wasted space we can have per page
// Allocations exceeding this will have their own dedicated page
// If this is too low, we will malloc too much
// Too high, and we may waste too much space
// Must be smaller than kInitialPageSize
#define MAX_WASTE_RATIO (0.5f)

#if LOG_NDEBUG
#define ADD_ALLOCATION()
#define RM_ALLOCATION()
#else
#include <utils/Thread.h>
#include <utils/Timers.h>
static size_t s_totalAllocations = 0;
static nsecs_t s_nextLog = 0;
static android::Mutex s_mutex;

static void _logUsageLocked() {
    nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
    if (now > s_nextLog) {
        s_nextLog = now + milliseconds_to_nanoseconds(10);
        ALOGV("Total pages allocated: %zu", s_totalAllocations);
    }
}

static void _addAllocation(int count) {
    android::AutoMutex lock(s_mutex);
    s_totalAllocations += count;
    _logUsageLocked();
}

#define ADD_ALLOCATION(size) _addAllocation(1);
#define RM_ALLOCATION(size) _addAllocation(-1);
#endif

#define min(x, y) (((x) < (y)) ? (x) : (y))

namespace android {
namespace uirenderer {

// The ideal size of a page allocation (these need to be multiples of 8)
static constexpr size_t kInitialPageSize = 512;  // 512b
static constexpr size_t kMaxPageSize = 131072;   // 128kb

class LinearAllocator::Page {
public:
    Page* next() { return mNextPage; }
    void setNext(Page* next) { mNextPage = next; }

    Page() : mNextPage(0) {}

    void* operator new(size_t /*size*/, void* buf) { return buf; }

    void* start() { return (void*)(((size_t)this) + sizeof(Page)); }

    void* end(int pageSize) { return (void*)(((size_t)start()) + pageSize); }

private:
    Page(const Page& /*other*/) {}
    Page* mNextPage;
};

LinearAllocator::LinearAllocator()
        : mPageSize(kInitialPageSize)
        , mMaxAllocSize(kInitialPageSize * MAX_WASTE_RATIO)
        , mNext(0)
        , mCurrentPage(0)
        , mPages(0)
        , mTotalAllocated(0)
        , mWastedSpace(0)
        , mPageCount(0)
        , mDedicatedPageCount(0) {}

LinearAllocator::~LinearAllocator(void) {
    while (mDtorList) {
        auto node = mDtorList;
        mDtorList = node->next;
        node->dtor(node->addr);
    }
    Page* p = mPages;
    while (p) {
        Page* next = p->next();
        p->~Page();
        free(p);
        RM_ALLOCATION();
        p = next;
    }
}

void* LinearAllocator::start(Page* p) {
    return ALIGN_PTR((size_t)p + sizeof(Page));
}

void* LinearAllocator::end(Page* p) {
    return ((char*)p) + mPageSize;
}

bool LinearAllocator::fitsInCurrentPage(size_t size) {
    return mNext && ((char*)mNext + size) <= end(mCurrentPage);
}

void LinearAllocator::ensureNext(size_t size) {
    if (fitsInCurrentPage(size)) return;

    if (mCurrentPage && mPageSize < kMaxPageSize) {
        mPageSize = min(kMaxPageSize, mPageSize * 2);
        mMaxAllocSize = mPageSize * MAX_WASTE_RATIO;
        mPageSize = ALIGN(mPageSize);
    }
    mWastedSpace += mPageSize;
    Page* p = newPage(mPageSize);
    if (mCurrentPage) {
        mCurrentPage->setNext(p);
    }
    mCurrentPage = p;
    if (!mPages) {
        mPages = mCurrentPage;
    }
    mNext = start(mCurrentPage);
}

void* LinearAllocator::allocImpl(size_t size) {
    size = ALIGN(size);
    if (size > mMaxAllocSize && !fitsInCurrentPage(size)) {
        ALOGV("Exceeded max size %zu > %zu", size, mMaxAllocSize);
        // Allocation is too large, create a dedicated page for the allocation
        Page* page = newPage(size);
        mDedicatedPageCount++;
        page->setNext(mPages);
        mPages = page;
        if (!mCurrentPage) mCurrentPage = mPages;
        return start(page);
    }
    ensureNext(size);
    void* ptr = mNext;
    mNext = ((char*)mNext) + size;
    mWastedSpace -= size;
    return ptr;
}

void LinearAllocator::addToDestructionList(Destructor dtor, void* addr) {
    static_assert(std::is_standard_layout<DestructorNode>::value,
                  "DestructorNode must have standard layout");
    static_assert(std::is_trivially_destructible<DestructorNode>::value,
                  "DestructorNode must be trivially destructable");
    auto node = new (allocImpl(sizeof(DestructorNode))) DestructorNode();
    node->dtor = dtor;
    node->addr = addr;
    node->next = mDtorList;
    mDtorList = node;
}

void LinearAllocator::runDestructorFor(void* addr) {
    auto node = mDtorList;
    DestructorNode* previous = nullptr;
    while (node) {
        if (node->addr == addr) {
            if (previous) {
                previous->next = node->next;
            } else {
                mDtorList = node->next;
            }
            node->dtor(node->addr);
            rewindIfLastAlloc(node, sizeof(DestructorNode));
            break;
        }
        previous = node;
        node = node->next;
    }
}

void LinearAllocator::rewindIfLastAlloc(void* ptr, size_t allocSize) {
    // First run the destructor as running the destructor will
    // also rewind for the DestructorNode allocation which will
    // have been allocated after this void* if it has a destructor
    runDestructorFor(ptr);
    // Don't bother rewinding across pages
    allocSize = ALIGN(allocSize);
    if (ptr >= start(mCurrentPage) && ptr < end(mCurrentPage) &&
        ptr == ((char*)mNext - allocSize)) {
        mWastedSpace += allocSize;
        mNext = ptr;
    }
}

LinearAllocator::Page* LinearAllocator::newPage(size_t pageSize) {
    pageSize = ALIGN(pageSize + sizeof(LinearAllocator::Page));
    ADD_ALLOCATION();
    mTotalAllocated += pageSize;
    mPageCount++;
    void* buf = malloc(pageSize);
    return new (buf) Page();
}

static const char* toSize(size_t value, float& result) {
    if (value < 2000) {
        result = value;
        return "B";
    }
    if (value < 2000000) {
        result = value / 1024.0f;
        return "KB";
    }
    result = value / 1048576.0f;
    return "MB";
}

void LinearAllocator::dumpMemoryStats(const char* prefix) {
    float prettySize;
    const char* prettySuffix;
    prettySuffix = toSize(mTotalAllocated, prettySize);
    ALOGD("%sTotal allocated: %.2f%s", prefix, prettySize, prettySuffix);
    prettySuffix = toSize(mWastedSpace, prettySize);
    ALOGD("%sWasted space: %.2f%s (%.1f%%)", prefix, prettySize, prettySuffix,
          (float)mWastedSpace / (float)mTotalAllocated * 100.0f);
    ALOGD("%sPages %zu (dedicated %zu)", prefix, mPageCount, mDedicatedPageCount);
}

}  // namespace uirenderer
}  // namespace android
