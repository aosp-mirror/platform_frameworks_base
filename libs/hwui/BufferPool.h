/*
 * Copyright (C) 2016 The Android Open Source Project
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

#pragma once

#include <utils/RefBase.h>
#include <utils/Log.h>

#include <atomic>
#include <stdint.h>
#include <memory>
#include <mutex>

namespace android {
namespace uirenderer {

/*
 * Simple thread-safe pool of int64_t arrays of a provided size.
 *
 * Permits allocating a client-provided max number of buffers.
 * If all buffers are in use, refuses to service any more
 * acquire requests until buffers are re-released to the pool.
 */
class BufferPool : public VirtualLightRefBase {
public:
    class Buffer {
    public:
        int64_t* getBuffer() { return mBuffer.get(); }
        size_t getSize() { return mSize; }

        void release() {
            LOG_ALWAYS_FATAL_IF(mPool.get() == nullptr, "attempt to release unacquired buffer");
            mPool->release(this);
        }

        Buffer* incRef() {
            mRefs++;
            return this;
        }

        int decRef() {
            int refs = mRefs.fetch_sub(1);
            LOG_ALWAYS_FATAL_IF(refs == 0, "buffer reference decremented below 0");
            return refs - 1;
        }

    private:
        friend class BufferPool;

        Buffer(BufferPool* pool, size_t size) {
            mSize = size;
            mBuffer.reset(new int64_t[size]);
            mPool = pool;
            mRefs++;
        }

        void setPool(BufferPool* pool) {
            mPool = pool;
        }

        std::unique_ptr<Buffer> mNext;
        std::unique_ptr<int64_t[]> mBuffer;
        sp<BufferPool> mPool;
        size_t mSize;

        std::atomic_int mRefs;
    };

    BufferPool(size_t bufferSize, size_t count)
            : mBufferSize(bufferSize), mCount(count) {}

    /**
     * Acquires a buffer from the buffer pool if available.
     *
     * Only `mCount` buffers are allowed to be in use at a single
     * instance.
     *
     * If no buffer is available, i.e. `mCount` buffers are in use,
     * returns nullptr.
     *
     * The pointer returned from this method *MUST NOT* be freed, instead
     * BufferPool::release() must be called upon it when the client
     * is done with it. Failing to release buffers will eventually make the
     * BufferPool refuse to service any more BufferPool::acquire() requests.
     */
    BufferPool::Buffer* acquire() {
        std::lock_guard<std::mutex> lock(mLock);

        if (mHead.get() != nullptr) {
            BufferPool::Buffer* res = mHead.release();
            mHead = std::move(res->mNext);
            res->mNext.reset(nullptr);
            res->setPool(this);
            res->incRef();
            return res;
        }

        if (mAllocatedCount < mCount) {
            ++mAllocatedCount;
            return new BufferPool::Buffer(this, mBufferSize);
        }

        return nullptr;
    }

    /**
     * Releases a buffer previously acquired by BufferPool::acquire().
     *
     * The released buffer is not valid after calling this method and
     * attempting to use will result in undefined behavior.
     */
    void release(BufferPool::Buffer* buffer) {
        std::lock_guard<std::mutex> lock(mLock);

        if (buffer->decRef() != 0) {
            return;
        }

        buffer->setPool(nullptr);

        BufferPool::Buffer* list = mHead.get();
        if (list == nullptr) {
            mHead.reset(buffer);
            mHead->mNext.reset(nullptr);
            return;
        }

        while (list->mNext.get() != nullptr) {
            list = list->mNext.get();
        }

        list->mNext.reset(buffer);
    }

    /*
     * Used for testing.
     */
    size_t getAvailableBufferCount() {
        size_t remainingToAllocateCount = mCount - mAllocatedCount;

        BufferPool::Buffer* list = mHead.get();
        if (list == nullptr) return remainingToAllocateCount;

        int count = 1;
        while (list->mNext.get() != nullptr) {
            count++;
            list = list->mNext.get();
        }

        return count + remainingToAllocateCount;
    }

private:
    mutable std::mutex mLock;

    size_t mBufferSize;
    size_t mCount;
    size_t mAllocatedCount = 0;
    std::unique_ptr<BufferPool::Buffer> mHead;
};

}; // namespace uirenderer
}; // namespace android
