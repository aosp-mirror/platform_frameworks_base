/*
 * Copyright (C) 2011 The Android Open Source Project
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

// #define LOG_NDEBUG 0
#define LOG_TAG "SurfaceMediaSource"

#include <media/stagefright/SurfaceMediaSource.h>
#include <ui/GraphicBuffer.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/openmax/OMX_IVCommon.h>

#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/SurfaceComposerClient.h>
#include <surfaceflinger/IGraphicBufferAlloc.h>
#include <OMX_Component.h>

#include <utils/Log.h>
#include <utils/String8.h>

namespace android {

SurfaceMediaSource::SurfaceMediaSource(uint32_t bufW, uint32_t bufH) :
                mDefaultWidth(bufW),
                mDefaultHeight(bufH),
                mPixelFormat(0),
                mBufferCount(MIN_ASYNC_BUFFER_SLOTS),
                mClientBufferCount(0),
                mServerBufferCount(MIN_ASYNC_BUFFER_SLOTS),
                mCurrentSlot(INVALID_BUFFER_SLOT),
                mCurrentTimestamp(0),
                mSynchronousMode(true),
                mConnectedApi(NO_CONNECTED_API),
                mFrameRate(30),
                mStarted(false)   {
    LOGV("SurfaceMediaSource::SurfaceMediaSource");
    sp<ISurfaceComposer> composer(ComposerService::getComposerService());
    mGraphicBufferAlloc = composer->createGraphicBufferAlloc();
}

SurfaceMediaSource::~SurfaceMediaSource() {
    LOGV("SurfaceMediaSource::~SurfaceMediaSource");
    if (mStarted) {
        stop();
    }
    freeAllBuffers();
}

size_t SurfaceMediaSource::getQueuedCount() const {
    Mutex::Autolock lock(mMutex);
    return mQueue.size();
}

status_t SurfaceMediaSource::setBufferCountServerLocked(int bufferCount) {
    if (bufferCount > NUM_BUFFER_SLOTS)
        return BAD_VALUE;

    // special-case, nothing to do
    if (bufferCount == mBufferCount)
        return OK;

    if (!mClientBufferCount &&
        bufferCount >= mBufferCount) {
        // easy, we just have more buffers
        mBufferCount = bufferCount;
        mServerBufferCount = bufferCount;
        mDequeueCondition.signal();
    } else {
        // we're here because we're either
        // - reducing the number of available buffers
        // - or there is a client-buffer-count in effect

        // less than 2 buffers is never allowed
        if (bufferCount < 2)
            return BAD_VALUE;

        // when there is non client-buffer-count in effect, the client is not
        // allowed to dequeue more than one buffer at a time,
        // so the next time they dequeue a buffer, we know that they don't
        // own one. the actual resizing will happen during the next
        // dequeueBuffer.

        mServerBufferCount = bufferCount;
    }
    return OK;
}

// Called from the consumer side
status_t SurfaceMediaSource::setBufferCountServer(int bufferCount) {
    Mutex::Autolock lock(mMutex);
    return setBufferCountServerLocked(bufferCount);
}

status_t SurfaceMediaSource::setBufferCount(int bufferCount) {
    LOGV("SurfaceMediaSource::setBufferCount");
    if (bufferCount > NUM_BUFFER_SLOTS) {
        LOGE("setBufferCount: bufferCount is larger than the number of buffer slots");
        return BAD_VALUE;
    }

    Mutex::Autolock lock(mMutex);
    // Error out if the user has dequeued buffers
    for (int i = 0 ; i < mBufferCount ; i++) {
        if (mSlots[i].mBufferState == BufferSlot::DEQUEUED) {
            LOGE("setBufferCount: client owns some buffers");
            return INVALID_OPERATION;
        }
    }

    if (bufferCount == 0) {
        const int minBufferSlots = mSynchronousMode ?
                MIN_SYNC_BUFFER_SLOTS : MIN_ASYNC_BUFFER_SLOTS;
        mClientBufferCount = 0;
        bufferCount = (mServerBufferCount >= minBufferSlots) ?
                mServerBufferCount : minBufferSlots;
        return setBufferCountServerLocked(bufferCount);
    }

    // We don't allow the client to set a buffer-count less than
    // MIN_ASYNC_BUFFER_SLOTS (3), there is no reason for it.
    if (bufferCount < MIN_ASYNC_BUFFER_SLOTS) {
        return BAD_VALUE;
    }

    // here we're guaranteed that the client doesn't have dequeued buffers
    // and will release all of its buffer references.
    freeAllBuffers();
    mBufferCount = bufferCount;
    mClientBufferCount = bufferCount;
    mCurrentSlot = INVALID_BUFFER_SLOT;
    mQueue.clear();
    mDequeueCondition.signal();
    return OK;
}

sp<GraphicBuffer> SurfaceMediaSource::requestBuffer(int buf) {
    LOGV("SurfaceMediaSource::requestBuffer");
    Mutex::Autolock lock(mMutex);
    if (buf < 0 || mBufferCount <= buf) {
        LOGE("requestBuffer: slot index out of range [0, %d]: %d",
                mBufferCount, buf);
        return 0;
    }
    mSlots[buf].mRequestBufferCalled = true;
    return mSlots[buf].mGraphicBuffer;
}

status_t SurfaceMediaSource::dequeueBuffer(int *outBuf, uint32_t w, uint32_t h,
                                            uint32_t format, uint32_t usage) {
    LOGV("dequeueBuffer");


    // Check for the buffer size- the client should just use the
    // default width and height, and not try to set those.
    // This is needed since
    // the getFormat() returns mDefaultWidth/ Height for the OMX. It is
    // queried by OMX in the beginning and not every time a frame comes.
    // Not sure if there is  a way to update the
    // frame size while recording. So as of now, the client side
    // sets the default values via the constructor, and the encoder is
    // setup to encode frames of that size
    // The design might need to change in the future.
    // TODO: Currently just uses mDefaultWidth/Height. In the future
    // we might declare mHeight and mWidth and check against those here.
    if ((w != 0) || (h != 0)) {
        LOGE("dequeuebuffer: invalid buffer size! Req: %dx%d, Found: %dx%d",
                mDefaultWidth, mDefaultHeight, w, h);
        return BAD_VALUE;
    }

    Mutex::Autolock lock(mMutex);

    status_t returnFlags(OK);

    int found, foundSync;
    int dequeuedCount = 0;
    bool tryAgain = true;
    while (tryAgain) {
        // We need to wait for the FIFO to drain if the number of buffer
        // needs to change.
        //
        // The condition "number of buffer needs to change" is true if
        // - the client doesn't care about how many buffers there are
        // - AND the actual number of buffer is different from what was
        //   set in the last setBufferCountServer()
        //                         - OR -
        //   setBufferCountServer() was set to a value incompatible with
        //   the synchronization mode (for instance because the sync mode
        //   changed since)
        //
        // As long as this condition is true AND the FIFO is not empty, we
        // wait on mDequeueCondition.

        int minBufferCountNeeded = mSynchronousMode ?
                MIN_SYNC_BUFFER_SLOTS : MIN_ASYNC_BUFFER_SLOTS;

        if (!mClientBufferCount &&
                ((mServerBufferCount != mBufferCount) ||
                        (mServerBufferCount < minBufferCountNeeded))) {
            // wait for the FIFO to drain
            while (!mQueue.isEmpty()) {
                LOGV("Waiting for the FIFO to drain");
                mDequeueCondition.wait(mMutex);
            }
            // need to check again since the mode could have changed
            // while we were waiting
            minBufferCountNeeded = mSynchronousMode ?
                    MIN_SYNC_BUFFER_SLOTS : MIN_ASYNC_BUFFER_SLOTS;
        }

        if (!mClientBufferCount &&
                ((mServerBufferCount != mBufferCount) ||
                        (mServerBufferCount < minBufferCountNeeded))) {
            // here we're guaranteed that mQueue is empty
            freeAllBuffers();
            mBufferCount = mServerBufferCount;
            if (mBufferCount < minBufferCountNeeded)
                mBufferCount = minBufferCountNeeded;
            mCurrentSlot = INVALID_BUFFER_SLOT;
            returnFlags |= ISurfaceTexture::RELEASE_ALL_BUFFERS;
        }

        // look for a free buffer to give to the client
        found = INVALID_BUFFER_SLOT;
        foundSync = INVALID_BUFFER_SLOT;
        dequeuedCount = 0;
        for (int i = 0; i < mBufferCount; i++) {
            const int state = mSlots[i].mBufferState;
            if (state == BufferSlot::DEQUEUED) {
                dequeuedCount++;
                continue; // won't be continuing if could
                // dequeue a non 'FREE' current slot like
                // that in SurfaceTexture
            }
            // In case of Encoding, we do not deque the mCurrentSlot buffer
            //  since we follow synchronous mode (unlike possibly in
            //  SurfaceTexture that could be using the asynch mode
            //  or has some mechanism in GL to be able to wait till the
            //  currentslot is done using the data)
            // Here, we have to wait for the MPEG4Writer(or equiv)
            // to tell us when it's done using the current buffer
            if (state == BufferSlot::FREE) {
                foundSync = i;
                // Unlike that in SurfaceTexture,
                // We don't need to worry if it is the
                // currentslot or not as it is in state FREE
                found = i;
                break;
            }
        }

        // clients are not allowed to dequeue more than one buffer
        // if they didn't set a buffer count.
        if (!mClientBufferCount && dequeuedCount) {
            return -EINVAL;
        }

        // See whether a buffer has been queued since the last setBufferCount so
        // we know whether to perform the MIN_UNDEQUEUED_BUFFERS check below.
        bool bufferHasBeenQueued = mCurrentSlot != INVALID_BUFFER_SLOT;
        if (bufferHasBeenQueued) {
            // make sure the client is not trying to dequeue more buffers
            // than allowed.
            const int avail = mBufferCount - (dequeuedCount+1);
            if (avail < (MIN_UNDEQUEUED_BUFFERS-int(mSynchronousMode))) {
                LOGE("dequeueBuffer: MIN_UNDEQUEUED_BUFFERS=%d exceeded (dequeued=%d)",
                        MIN_UNDEQUEUED_BUFFERS-int(mSynchronousMode),
                        dequeuedCount);
                return -EBUSY;
            }
        }

        // we're in synchronous mode and didn't find a buffer, we need to wait
        // for for some buffers to be consumed
        tryAgain = mSynchronousMode && (foundSync == INVALID_BUFFER_SLOT);
        if (tryAgain) {
            LOGW("Waiting..In synchronous mode and no buffer to dQ");
            mDequeueCondition.wait(mMutex);
        }
    }

    if (mSynchronousMode && found == INVALID_BUFFER_SLOT) {
        // foundSync guaranteed to be != INVALID_BUFFER_SLOT
        found = foundSync;
    }

    if (found == INVALID_BUFFER_SLOT) {
        return -EBUSY;
    }

    const int buf = found;
    *outBuf = found;

    const bool useDefaultSize = !w && !h;
    if (useDefaultSize) {
        // use the default size
        w = mDefaultWidth;
        h = mDefaultHeight;
    }

    const bool updateFormat = (format != 0);
    if (!updateFormat) {
        // keep the current (or default) format
        format = mPixelFormat;
    }

    // buffer is now in DEQUEUED (but can also be current at the same time,
    // if we're in synchronous mode)
    mSlots[buf].mBufferState = BufferSlot::DEQUEUED;

    const sp<GraphicBuffer>& buffer(mSlots[buf].mGraphicBuffer);
    if ((buffer == NULL) ||
        (uint32_t(buffer->width)  != w) ||
        (uint32_t(buffer->height) != h) ||
        (uint32_t(buffer->format) != format) ||
        ((uint32_t(buffer->usage) & usage) != usage)) {
            usage |= GraphicBuffer::USAGE_HW_TEXTURE;
            status_t error;
            sp<GraphicBuffer> graphicBuffer(
                    mGraphicBufferAlloc->createGraphicBuffer(
                                    w, h, format, usage, &error));
            if (graphicBuffer == 0) {
                LOGE("dequeueBuffer: SurfaceComposer::createGraphicBuffer failed");
                return error;
            }
            if (updateFormat) {
                mPixelFormat = format;
            }
            mSlots[buf].mGraphicBuffer = graphicBuffer;
            mSlots[buf].mRequestBufferCalled = false;
            returnFlags |= ISurfaceTexture::BUFFER_NEEDS_REALLOCATION;
    }
    return returnFlags;
}

status_t SurfaceMediaSource::setSynchronousMode(bool enabled) {
    Mutex::Autolock lock(mMutex);

    status_t err = OK;
    if (!enabled) {
        // going to asynchronous mode, drain the queue
        while (mSynchronousMode != enabled && !mQueue.isEmpty()) {
            mDequeueCondition.wait(mMutex);
        }
    }

    if (mSynchronousMode != enabled) {
        // - if we're going to asynchronous mode, the queue is guaranteed to be
        // empty here
        // - if the client set the number of buffers, we're guaranteed that
        // we have at least 3 (because we don't allow less)
        mSynchronousMode = enabled;
        mDequeueCondition.signal();
    }
    return err;
}

status_t SurfaceMediaSource::connect(int api) {
    LOGV("SurfaceMediaSource::connect");
    Mutex::Autolock lock(mMutex);
    status_t err = NO_ERROR;
    switch (api) {
        case NATIVE_WINDOW_API_EGL:
        case NATIVE_WINDOW_API_CPU:
        case NATIVE_WINDOW_API_MEDIA:
        case NATIVE_WINDOW_API_CAMERA:
            if (mConnectedApi != NO_CONNECTED_API) {
                err = -EINVAL;
            } else {
                mConnectedApi = api;
            }
            break;
        default:
            err = -EINVAL;
            break;
    }
    return err;
}

status_t SurfaceMediaSource::disconnect(int api) {
    LOGV("SurfaceMediaSource::disconnect");
    Mutex::Autolock lock(mMutex);
    status_t err = NO_ERROR;
    switch (api) {
        case NATIVE_WINDOW_API_EGL:
        case NATIVE_WINDOW_API_CPU:
        case NATIVE_WINDOW_API_MEDIA:
        case NATIVE_WINDOW_API_CAMERA:
            if (mConnectedApi == api) {
                mConnectedApi = NO_CONNECTED_API;
            } else {
                err = -EINVAL;
            }
            break;
        default:
            err = -EINVAL;
            break;
    }
    return err;
}

status_t SurfaceMediaSource::queueBuffer(int buf, int64_t timestamp,
        uint32_t* outWidth, uint32_t* outHeight, uint32_t* outTransform) {
    LOGV("queueBuffer");

    Mutex::Autolock lock(mMutex);
    if (buf < 0 || buf >= mBufferCount) {
        LOGE("queueBuffer: slot index out of range [0, %d]: %d",
                mBufferCount, buf);
        return -EINVAL;
    } else if (mSlots[buf].mBufferState != BufferSlot::DEQUEUED) {
        LOGE("queueBuffer: slot %d is not owned by the client (state=%d)",
                buf, mSlots[buf].mBufferState);
        return -EINVAL;
    } else if (!mSlots[buf].mRequestBufferCalled) {
        LOGE("queueBuffer: slot %d was enqueued without requesting a "
                "buffer", buf);
        return -EINVAL;
    }

    if (mSynchronousMode) {
        // in synchronous mode we queue all buffers in a FIFO
        mQueue.push_back(buf);
        LOGV("Client queued buffer on slot: %d, Q size = %d",
                                                buf, mQueue.size());
    } else {
        // in asynchronous mode we only keep the most recent buffer
        if (mQueue.empty()) {
            mQueue.push_back(buf);
        } else {
            Fifo::iterator front(mQueue.begin());
            // buffer currently queued is freed
            mSlots[*front].mBufferState = BufferSlot::FREE;
            // and we record the new buffer index in the queued list
            *front = buf;
        }
    }

    mSlots[buf].mBufferState = BufferSlot::QUEUED;
    mSlots[buf].mTimestamp = timestamp;
    // TODO: (Confirm) Don't want to signal dequeue here.
    // May be just in asynchronous mode?
    // mDequeueCondition.signal();

    // Once the queuing is done, we need to let the listener
    // and signal the buffer consumer (encoder) know that a
    // buffer is available
    onFrameReceivedLocked();

    *outWidth = mDefaultWidth;
    *outHeight = mDefaultHeight;
    *outTransform = 0;

    return OK;
}


// onFrameReceivedLocked informs the buffer consumers (StageFrightRecorder)
// or listeners that a frame has been received
// It is supposed to be called only from queuebuffer.
// The buffer is NOT made available for dequeueing immediately. We need to
// wait to hear from StageFrightRecorder to set the buffer FREE
// Make sure this is called when the mutex is locked
status_t SurfaceMediaSource::onFrameReceivedLocked() {
    LOGV("On Frame Received");
    // Signal the encoder that a new frame has arrived
    mFrameAvailableCondition.signal();

    // call back the listener
    // TODO: The listener may not be needed in SurfaceMediaSource at all.
    // This can be made a SurfaceTexture specific thing
    sp<FrameAvailableListener> listener;
    if (mSynchronousMode || mQueue.empty()) {
        listener = mFrameAvailableListener;
    }

    if (listener != 0) {
        listener->onFrameAvailable();
    }
    return OK;
}


void SurfaceMediaSource::cancelBuffer(int buf) {
    LOGV("SurfaceMediaSource::cancelBuffer");
    Mutex::Autolock lock(mMutex);
    if (buf < 0 || buf >= mBufferCount) {
        LOGE("cancelBuffer: slot index out of range [0, %d]: %d",
                mBufferCount, buf);
        return;
    } else if (mSlots[buf].mBufferState != BufferSlot::DEQUEUED) {
        LOGE("cancelBuffer: slot %d is not owned by the client (state=%d)",
                buf, mSlots[buf].mBufferState);
        return;
    }
    mSlots[buf].mBufferState = BufferSlot::FREE;
    mDequeueCondition.signal();
}

nsecs_t SurfaceMediaSource::getTimestamp() {
    LOGV("SurfaceMediaSource::getTimestamp");
    Mutex::Autolock lock(mMutex);
    return mCurrentTimestamp;
}


void SurfaceMediaSource::setFrameAvailableListener(
        const sp<FrameAvailableListener>& listener) {
    LOGV("SurfaceMediaSource::setFrameAvailableListener");
    Mutex::Autolock lock(mMutex);
    mFrameAvailableListener = listener;
}

sp<IBinder> SurfaceMediaSource::getAllocator() {
    LOGV("getAllocator");
    return mGraphicBufferAlloc->asBinder();
}


void SurfaceMediaSource::freeAllBuffers() {
    LOGV("freeAllBuffers");
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        mSlots[i].mGraphicBuffer = 0;
        mSlots[i].mBufferState = BufferSlot::FREE;
    }
}

sp<GraphicBuffer> SurfaceMediaSource::getCurrentBuffer() const {
    Mutex::Autolock lock(mMutex);
    return mCurrentBuf;
}

int SurfaceMediaSource::query(int what, int* outValue)
{
    LOGV("query");
    Mutex::Autolock lock(mMutex);
    int value;
    switch (what) {
    case NATIVE_WINDOW_WIDTH:
        value = mDefaultWidth;
        if (!mDefaultWidth && !mDefaultHeight && mCurrentBuf != 0)
            value = mCurrentBuf->width;
        break;
    case NATIVE_WINDOW_HEIGHT:
        value = mDefaultHeight;
        if (!mDefaultWidth && !mDefaultHeight && mCurrentBuf != 0)
            value = mCurrentBuf->height;
        break;
    case NATIVE_WINDOW_FORMAT:
        value = mPixelFormat;
        break;
    case NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS:
        value = mSynchronousMode ?
                (MIN_UNDEQUEUED_BUFFERS-1) : MIN_UNDEQUEUED_BUFFERS;
        break;
    default:
        return BAD_VALUE;
    }
    outValue[0] = value;
    return NO_ERROR;
}

void SurfaceMediaSource::dump(String8& result) const
{
    char buffer[1024];
    dump(result, "", buffer, 1024);
}

void SurfaceMediaSource::dump(String8& result, const char* prefix,
        char* buffer, size_t SIZE) const
{
    Mutex::Autolock _l(mMutex);
    snprintf(buffer, SIZE,
            "%smBufferCount=%d, mSynchronousMode=%d, default-size=[%dx%d], "
            "mPixelFormat=%d, \n",
            prefix, mBufferCount, mSynchronousMode, mDefaultWidth, mDefaultHeight,
            mPixelFormat);
    result.append(buffer);

    String8 fifo;
    int fifoSize = 0;
    Fifo::const_iterator i(mQueue.begin());
    while (i != mQueue.end()) {
        snprintf(buffer, SIZE, "%02d ", *i++);
        fifoSize++;
        fifo.append(buffer);
    }

    result.append(buffer);

    struct {
        const char * operator()(int state) const {
            switch (state) {
                case BufferSlot::DEQUEUED: return "DEQUEUED";
                case BufferSlot::QUEUED: return "QUEUED";
                case BufferSlot::FREE: return "FREE";
                default: return "Unknown";
            }
        }
    } stateName;

    for (int i = 0; i < mBufferCount; i++) {
        const BufferSlot& slot(mSlots[i]);
        snprintf(buffer, SIZE,
                "%s%s[%02d] state=%-8s, "
                "timestamp=%lld\n",
                prefix, (i==mCurrentSlot)?">":" ", i, stateName(slot.mBufferState),
                slot.mTimestamp
        );
        result.append(buffer);
    }
}

status_t SurfaceMediaSource::setFrameRate(int32_t fps)
{
    Mutex::Autolock lock(mMutex);
    const int MAX_FRAME_RATE = 60;
    if (fps < 0 || fps > MAX_FRAME_RATE) {
        return BAD_VALUE;
    }
    mFrameRate = fps;
    return OK;
}

bool SurfaceMediaSource::isMetaDataStoredInVideoBuffers() const {
    LOGV("isMetaDataStoredInVideoBuffers");
    return true;
}

int32_t SurfaceMediaSource::getFrameRate( ) const {
    Mutex::Autolock lock(mMutex);
    return mFrameRate;
}

status_t SurfaceMediaSource::start(MetaData *params)
{
    LOGV("start");
    Mutex::Autolock lock(mMutex);
    CHECK(!mStarted);
    mStarted = true;
    return OK;
}


status_t SurfaceMediaSource::stop()
{
    LOGV("Stop");

    Mutex::Autolock lock(mMutex);
    // TODO: Add waiting on mFrameCompletedCondition here?
    mStarted = false;
    mFrameAvailableCondition.signal();

    return OK;
}

sp<MetaData> SurfaceMediaSource::getFormat()
{
    LOGV("getFormat");
    Mutex::Autolock autoLock(mMutex);
    sp<MetaData> meta = new MetaData;

    meta->setInt32(kKeyWidth, mDefaultWidth);
    meta->setInt32(kKeyHeight, mDefaultHeight);
    // The encoder format is set as an opaque colorformat
    // The encoder will later find out the actual colorformat
    // from the GL Frames itself.
    meta->setInt32(kKeyColorFormat, OMX_COLOR_FormatAndroidOpaque);
    meta->setInt32(kKeyStride, mDefaultWidth);
    meta->setInt32(kKeySliceHeight, mDefaultHeight);
    meta->setInt32(kKeyFrameRate, mFrameRate);
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_RAW);
    return meta;
}

status_t SurfaceMediaSource::read( MediaBuffer **buffer,
                                const ReadOptions *options)
{
    LOGV("Read. Size of queued buffer: %d", mQueue.size());
    *buffer = NULL;

    Mutex::Autolock autoLock(mMutex) ;
    // If the recording has started and the queue is empty, then just
    // wait here till the frames come in from the client side
    while (mStarted && mQueue.empty()) {
        LOGV("NO FRAMES! Recorder waiting for FrameAvailableCondition");
        mFrameAvailableCondition.wait(mMutex);
    }

    // If the loop was exited as a result of stopping the recording,
    // it is OK
    if (!mStarted) {
        return OK;
    }

    // Update the current buffer info
    // TODO: mCurrentSlot can be made a bufferstate since there
    // can be more than one "current" slots.
    Fifo::iterator front(mQueue.begin());
    mCurrentSlot = *front;
    mCurrentBuf = mSlots[mCurrentSlot].mGraphicBuffer;
    mCurrentTimestamp = mSlots[mCurrentSlot].mTimestamp;

    // Pass the data to the MediaBuffer
    // TODO: Change later to pass in only the metadata
    *buffer = new MediaBuffer(mCurrentBuf);
    (*buffer)->setObserver(this);
    (*buffer)->add_ref();
    (*buffer)->meta_data()->setInt64(kKeyTime, mCurrentTimestamp);

    return OK;
}

void SurfaceMediaSource::signalBufferReturned(MediaBuffer *buffer) {
    LOGV("signalBufferReturned");

    bool foundBuffer = false;
    Mutex::Autolock autoLock(mMutex);

    if (!mStarted) {
        LOGV("started = false. Nothing to do");
        return;
    }

    for (Fifo::iterator it = mQueue.begin(); it != mQueue.end(); ++it) {
        if (mSlots[*it].mGraphicBuffer  ==  buffer->graphicBuffer()) {
            LOGV("Buffer %d returned. Setting it 'FREE'. New Queue size = %d",
                    *it, mQueue.size()-1);
            mSlots[*it].mBufferState = BufferSlot::FREE;
            mQueue.erase(it);
            buffer->setObserver(0);
            buffer->release();
            mDequeueCondition.signal();
            mFrameCompleteCondition.signal();
            foundBuffer = true;
            break;
        }
    }

    if (!foundBuffer) {
        CHECK_EQ(0, "signalBufferReturned: bogus buffer");
    }
}



} // end of namespace android
