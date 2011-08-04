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

#include <errno.h>
#include <pthread.h>
#include <stdlib.h>

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MetaData.h>

#include <ui/GraphicBuffer.h>
#include <sys/atomics.h>

namespace android {

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

MediaBuffer::MediaBuffer(const sp<GraphicBuffer>& graphicBuffer)
    : mObserver(NULL),
      mNextBuffer(NULL),
      mRefCount(0),
      mData(NULL),
      mSize(1),
      mRangeOffset(0),
      mRangeLength(mSize),
      mGraphicBuffer(graphicBuffer),
      mOwnsData(false),
      mMetaData(new MetaData),
      mOriginal(NULL) {
}

MediaBuffer::MediaBuffer(const sp<ABuffer> &buffer)
    : mObserver(NULL),
      mNextBuffer(NULL),
      mRefCount(0),
      mData(buffer->data()),
      mSize(buffer->size()),
      mRangeOffset(0),
      mRangeLength(mSize),
      mBuffer(buffer),
      mOwnsData(false),
      mMetaData(new MetaData),
      mOriginal(NULL) {
}

void MediaBuffer::release() {
    if (mObserver == NULL) {
        CHECK_EQ(mRefCount, 0);
        delete this;
        return;
    }

    int prevCount = __atomic_dec(&mRefCount);
    if (prevCount == 1) {
        if (mObserver == NULL) {
            delete this;
            return;
        }

        mObserver->signalBufferReturned(this);
    }
    CHECK(prevCount > 0);
}

void MediaBuffer::claim() {
    CHECK(mObserver != NULL);
    CHECK_EQ(mRefCount, 1);

    mRefCount = 0;
}

void MediaBuffer::add_ref() {
    (void) __atomic_inc(&mRefCount);
}

void *MediaBuffer::data() const {
    CHECK(mGraphicBuffer == NULL);
    return mData;
}

size_t MediaBuffer::size() const {
    CHECK(mGraphicBuffer == NULL);
    return mSize;
}

size_t MediaBuffer::range_offset() const {
    return mRangeOffset;
}

size_t MediaBuffer::range_length() const {
    return mRangeLength;
}

void MediaBuffer::set_range(size_t offset, size_t length) {
    if ((mGraphicBuffer == NULL) && (offset + length > mSize)) {
        LOGE("offset = %d, length = %d, mSize = %d", offset, length, mSize);
    }
    CHECK((mGraphicBuffer != NULL) || (offset + length <= mSize));

    mRangeOffset = offset;
    mRangeLength = length;
}

sp<GraphicBuffer> MediaBuffer::graphicBuffer() const {
    return mGraphicBuffer;
}

sp<MetaData> MediaBuffer::meta_data() {
    return mMetaData;
}

void MediaBuffer::reset() {
    mMetaData->clear();
    set_range(0, mSize);
}

MediaBuffer::~MediaBuffer() {
    CHECK_EQ(mObserver, NULL);

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
    CHECK(observer == NULL || mObserver == NULL);
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
    CHECK_EQ(mGraphicBuffer, NULL);

    MediaBuffer *buffer = new MediaBuffer(mData, mSize);
    buffer->set_range(mRangeOffset, mRangeLength);
    buffer->mMetaData = new MetaData(*mMetaData.get());

    add_ref();
    buffer->mOriginal = this;

    return buffer;
}

}  // namespace android
