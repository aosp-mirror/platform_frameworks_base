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

#define LOG_TAG "MediaBuffer"
#include <utils/Log.h>

#undef NDEBUG
#include <assert.h>

#include <errno.h>
#include <pthread.h>
#include <stdlib.h>

#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MetaData.h>

namespace android {

// XXX make this truly atomic.
static int atomic_add(int *value, int delta) {
    int prev_value = *value;
    *value += delta;

    return prev_value;
}

MediaBuffer::MediaBuffer(void *data, size_t size)
    : mObserver(NULL),
      mNextBuffer(NULL),
      mRefCount(0),
      mData(data),
      mSize(size),
      mRangeOffset(0),
      mRangeLength(size),
      mOwnsData(false),
      mMetaData(new MetaData),
      mOriginal(NULL) {
}

MediaBuffer::MediaBuffer(size_t size)
    : mObserver(NULL),
      mNextBuffer(NULL),
      mRefCount(0),
      mData(malloc(size)),
      mSize(size),
      mRangeOffset(0),
      mRangeLength(size),
      mOwnsData(true),
      mMetaData(new MetaData),
      mOriginal(NULL) {
}

void MediaBuffer::release() {
    if (mObserver == NULL) {
        assert(mRefCount == 0);
        delete this;
        return;
    }

    int prevCount = atomic_add(&mRefCount, -1);
    if (prevCount == 1) {
        if (mObserver == NULL) {
            delete this;
            return;
        }

        mObserver->signalBufferReturned(this);
    }
    assert(prevCount > 0);
}

void MediaBuffer::claim() {
    assert(mObserver != NULL);
    assert(mRefCount == 1);

    mRefCount = 0;
}

void MediaBuffer::add_ref() {
    atomic_add(&mRefCount, 1);
}

void *MediaBuffer::data() const {
    return mData;
}

size_t MediaBuffer::size() const {
    return mSize;
}

size_t MediaBuffer::range_offset() const {
    return mRangeOffset;
}

size_t MediaBuffer::range_length() const {
    return mRangeLength;
}

void MediaBuffer::set_range(size_t offset, size_t length) {
    if (offset < 0 || offset + length > mSize) {
        LOGE("offset = %d, length = %d, mSize = %d", offset, length, mSize);
    }
    assert(offset >= 0 && offset + length <= mSize);

    mRangeOffset = offset;
    mRangeLength = length;
}

sp<MetaData> MediaBuffer::meta_data() {
    return mMetaData;
}

void MediaBuffer::reset() {
    mMetaData->clear();
    set_range(0, mSize);
}

MediaBuffer::~MediaBuffer() {
    assert(mObserver == NULL);

    if (mOwnsData && mData != NULL) {
        free(mData);
        mData = NULL;
    }

    if (mOriginal != NULL) {
        mOriginal->release();
        mOriginal = NULL;
    }
}

void MediaBuffer::setObserver(MediaBufferObserver *observer) {
    assert(observer == NULL || mObserver == NULL);
    mObserver = observer;
}

void MediaBuffer::setNextBuffer(MediaBuffer *buffer) {
    mNextBuffer = buffer;
}

MediaBuffer *MediaBuffer::nextBuffer() {
    return mNextBuffer;
}

int MediaBuffer::refcount() const {
    return mRefCount;
}

MediaBuffer *MediaBuffer::clone() {
    MediaBuffer *buffer = new MediaBuffer(mData, mSize);
    buffer->set_range(mRangeOffset, mRangeLength);
    buffer->mMetaData = new MetaData(*mMetaData.get());

    add_ref();
    buffer->mOriginal = this;

    return buffer;
}

}  // namespace android

