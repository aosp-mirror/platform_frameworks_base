/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include <media/stagefright/CachingDataSource.h>

#undef NDEBUG
#include <assert.h>

#include <stdlib.h>
#include <string.h>

namespace android {

CachingDataSource::CachingDataSource(
        const sp<DataSource> &source, size_t pageSize, int numPages)
    : mSource(source),
      mData(malloc(pageSize * numPages)),
      mPageSize(pageSize),
      mFirst(NULL),
      mLast(NULL) {
    for (int i = 0; i < numPages; ++i) {
        Page *page = new Page;
        page->mPrev = mLast;
        page->mNext = NULL;

        if (mLast == NULL) {
            mFirst = page;
        } else {
            mLast->mNext = page;
        }

        mLast = page;

        page->mOffset = -1;
        page->mLength = 0;
        page->mData = (char *)mData + mPageSize * i;
    }
}

CachingDataSource::~CachingDataSource() {
    Page *page = mFirst;
    while (page != NULL) {
        Page *next = page->mNext;
        delete page;
        page = next;
    }
    mFirst = mLast = NULL;

    free(mData);
    mData = NULL;
}

status_t CachingDataSource::InitCheck() const {
    return OK;
}

ssize_t CachingDataSource::read_at(off_t offset, void *data, size_t size) {
    Mutex::Autolock autoLock(mLock);

    size_t total = 0;
    while (size > 0) {
        Page *page = mFirst;
        while (page != NULL) {
            if (page->mOffset >= 0 && offset >= page->mOffset
                && offset < page->mOffset + (off_t)page->mLength) {
                break;
            }
            page = page->mNext;
        }

        if (page == NULL) {
            page = allocate_page();
            page->mOffset = offset - offset % mPageSize;
            ssize_t n = mSource->read_at(page->mOffset, page->mData, mPageSize);
            if (n < 0) {
                page->mLength = 0;
            } else {
                page->mLength = (size_t)n;
            }
            mFirst->mPrev = page;
            page->mNext = mFirst;
            page->mPrev = NULL;
            mFirst = page;

            if (n < 0) {
                return n;
            }

            if (offset >= page->mOffset + (off_t)page->mLength) {
                break;
            }
        } else {
            // Move "page" to the front in LRU order.
            if (page->mNext != NULL) {
                page->mNext->mPrev = page->mPrev;
            } else {
                mLast = page->mPrev;
            }

            if (page->mPrev != NULL) {
                page->mPrev->mNext = page->mNext;
            } else {
                mFirst = page->mNext;
            }

            mFirst->mPrev = page;
            page->mNext = mFirst;
            page->mPrev = NULL;
            mFirst = page;
        }

        size_t copy = page->mLength - (offset - page->mOffset);
        if (copy > size) {
            copy = size;
        }
        memcpy(data,(const char *)page->mData + (offset - page->mOffset),
               copy);

        total += copy;

        if (page->mLength < mPageSize) {
            // This was the final page. There is no more data beyond it.
            break;
        }

        offset += copy;
        size -= copy;
        data = (char *)data + copy;
    }

    return total;
}

CachingDataSource::Page *CachingDataSource::allocate_page() {
    // The last page is the least recently used, i.e. oldest.

    Page *page = mLast;

    page->mPrev->mNext = NULL;
    mLast = page->mPrev;
    page->mPrev = NULL;

    return page;
}

}  // namespace android
