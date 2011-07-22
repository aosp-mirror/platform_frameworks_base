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

#ifndef ANDROID_GUI_SURFACEMEDIASOURCE_H
#define ANDROID_GUI_SURFACEMEDIASOURCE_H

#include <gui/ISurfaceTexture.h>

#include <utils/threads.h>
#include <utils/Vector.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MediaBuffer.h>

namespace android {
// ----------------------------------------------------------------------------

class IGraphicBufferAlloc;
class String8;
class GraphicBuffer;

class SurfaceMediaSource : public BnSurfaceTexture, public MediaSource,
                                            public MediaBufferObserver {
public:
    enum { MIN_UNDEQUEUED_BUFFERS = 3 };
    enum {
        MIN_ASYNC_BUFFER_SLOTS = MIN_UNDEQUEUED_BUFFERS + 1,
        MIN_SYNC_BUFFER_SLOTS  = MIN_UNDEQUEUED_BUFFERS
    };
    enum { NUM_BUFFER_SLOTS = 32 };
    enum { NO_CONNECTED_API = 0 };

    struct FrameAvailableListener : public virtual RefBase {
        // onFrameAvailable() is called from queueBuffer() is the FIFO is
        // empty. You can use SurfaceMediaSource::getQueuedCount() to
        // figure out if there are more frames waiting.
        // This is called without any lock held can be called concurrently by
        // multiple threads.
        virtual void onFrameAvailable() = 0;
    };

    SurfaceMediaSource(uint32_t bufW, uint32_t bufH);

    virtual ~SurfaceMediaSource();


    // For the MediaSource interface for use by StageFrightRecorder:
    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);
    virtual sp<MetaData> getFormat();

    // Get / Set the frame rate used for encoding. Default fps = 30
    void setFrameRate(uint32_t fps) ;
    uint32_t getFrameRate( ) const;

    // The call for the StageFrightRecorder to tell us that
    // it is done using the MediaBuffer data so that its state
    // can be set to FREE for dequeuing
    virtual void signalBufferReturned(MediaBuffer* buffer);
    // end of MediaSource interface

    uint32_t getBufferCount( ) const { return mBufferCount;}


    // setBufferCount updates the number of available buffer slots.  After
    // calling this all buffer slots are both unallocated and owned by the
    // SurfaceMediaSource object (i.e. they are not owned by the client).
    virtual status_t setBufferCount(int bufferCount);

    virtual sp<GraphicBuffer> requestBuffer(int buf);

    // dequeueBuffer gets the next buffer slot index for the client to use. If a
    // buffer slot is available then that slot index is written to the location
    // pointed to by the buf argument and a status of OK is returned.  If no
    // slot is available then a status of -EBUSY is returned and buf is
    // unmodified.
    virtual status_t dequeueBuffer(int *buf, uint32_t w, uint32_t h,
            uint32_t format, uint32_t usage);

    // queueBuffer returns a filled buffer to the SurfaceMediaSource. In addition, a
    // timestamp must be provided for the buffer. The timestamp is in
    // nanoseconds, and must be monotonically increasing. Its other semantics
    // (zero point, etc) are client-dependent and should be documented by the
    // client.
    virtual status_t queueBuffer(int buf, int64_t timestamp,
            uint32_t* outWidth, uint32_t* outHeight, uint32_t* outTransform);
    virtual void cancelBuffer(int buf);

    // onFrameReceivedLocked informs the buffer consumers (StageFrightRecorder)
    // or listeners that a frame has been received
    // The buffer is not made available for dequeueing immediately. We need to
    // wait to hear from StageFrightRecorder to set the buffer FREE
    // Make sure this is called when the mutex is locked
    virtual status_t onFrameReceivedLocked();

    virtual status_t setScalingMode(int mode) { } // no op for encoding
    virtual int query(int what, int* value);

    // Just confirming to the ISurfaceTexture interface as of now
    virtual status_t setCrop(const Rect& reg) { return OK; }
    virtual status_t setTransform(uint32_t transform) {return OK;}

    // setSynchronousMode set whether dequeueBuffer is synchronous or
    // asynchronous. In synchronous mode, dequeueBuffer blocks until
    // a buffer is available, the currently bound buffer can be dequeued and
    // queued buffers will be retired in order.
    // The default mode is synchronous.
    // TODO: Clarify the minute differences bet sycn /async
    // modes (S.Encoder vis-a-vis SurfaceTexture)
    virtual status_t setSynchronousMode(bool enabled);

    // connect attempts to connect a client API to the SurfaceMediaSource.  This
    // must be called before any other ISurfaceTexture methods are called except
    // for getAllocator.
    //
    // This method will fail if the connect was previously called on the
    // SurfaceMediaSource and no corresponding disconnect call was made.
    virtual status_t connect(int api);

    // disconnect attempts to disconnect a client API from the SurfaceMediaSource.
    // Calling this method will cause any subsequent calls to other
    // ISurfaceTexture methods to fail except for getAllocator and connect.
    // Successfully calling connect after this will allow the other methods to
    // succeed again.
    //
    // This method will fail if the the SurfaceMediaSource is not currently
    // connected to the specified client API.
    virtual status_t disconnect(int api);

    // getqueuedCount returns the number of queued frames waiting in the
    // FIFO. In asynchronous mode, this always returns 0 or 1 since
    // frames are not accumulating in the FIFO.
    size_t getQueuedCount() const;

    // setBufferCountServer set the buffer count. If the client has requested
    // a buffer count using setBufferCount, the server-buffer count will
    // take effect once the client sets the count back to zero.
    status_t setBufferCountServer(int bufferCount);

    // getTimestamp retrieves the timestamp associated with the image
    // set by the most recent call to updateFrameInfoLocked().
    //
    // The timestamp is in nanoseconds, and is monotonically increasing. Its
    // other semantics (zero point, etc) are source-dependent and should be
    // documented by the source.
    int64_t getTimestamp();

    // setFrameAvailableListener sets the listener object that will be notified
    // when a new frame becomes available.
    void setFrameAvailableListener(const sp<FrameAvailableListener>& listener);

    // getAllocator retrieves the binder object that must be referenced as long
    // as the GraphicBuffers dequeued from this SurfaceMediaSource are referenced.
    // Holding this binder reference prevents SurfaceFlinger from freeing the
    // buffers before the client is done with them.
    sp<IBinder> getAllocator();


    // getCurrentBuffer returns the buffer associated with the current image.
    sp<GraphicBuffer> getCurrentBuffer() const;

    // dump our state in a String
    void dump(String8& result) const;
    void dump(String8& result, const char* prefix, char* buffer,
                                                    size_t SIZE) const;

    protected:

    // freeAllBuffers frees the resources (both GraphicBuffer and EGLImage) for
    // all slots.
    void freeAllBuffers();
    static bool isExternalFormat(uint32_t format);

private:

    status_t setBufferCountServerLocked(int bufferCount);

    enum { INVALID_BUFFER_SLOT = -1 };

    struct BufferSlot {

        BufferSlot()
            : mBufferState(BufferSlot::FREE),
              mRequestBufferCalled(false),
              mTimestamp(0) {
        }

        // mGraphicBuffer points to the buffer allocated for this slot or is
        // NULL if no buffer has been allocated.
        sp<GraphicBuffer> mGraphicBuffer;

        // BufferState represents the different states in which a buffer slot
        // can be.
        enum BufferState {
            // FREE indicates that the buffer is not currently being used and
            // will not be used in the future until it gets dequeued and
            // subseqently queued by the client.
            FREE = 0,

            // DEQUEUED indicates that the buffer has been dequeued by the
            // client, but has not yet been queued or canceled. The buffer is
            // considered 'owned' by the client, and the server should not use
            // it for anything.
            //
            // Note that when in synchronous-mode (mSynchronousMode == true),
            // the buffer that's currently attached to the texture may be
            // dequeued by the client.  That means that the current buffer can
            // be in either the DEQUEUED or QUEUED state.  In asynchronous mode,
            // however, the current buffer is always in the QUEUED state.
            DEQUEUED = 1,

            // QUEUED indicates that the buffer has been queued by the client,
            // and has not since been made available for the client to dequeue.
            // Attaching the buffer to the texture does NOT transition the
            // buffer away from the QUEUED state. However, in Synchronous mode
            // the current buffer may be dequeued by the client under some
            // circumstances. See the note about the current buffer in the
            // documentation for DEQUEUED.
            QUEUED = 2,
        };

        // mBufferState is the current state of this buffer slot.
        BufferState mBufferState;

        // mRequestBufferCalled is used for validating that the client did
        // call requestBuffer() when told to do so. Technically this is not
        // needed but useful for debugging and catching client bugs.
        bool mRequestBufferCalled;

        // mTimestamp is the current timestamp for this buffer slot. This gets
        // to set by queueBuffer each time this slot is queued.
        int64_t mTimestamp;
    };

    // mSlots is the array of buffer slots that must be mirrored on the client
    // side. This allows buffer ownership to be transferred between the client
    // and server without sending a GraphicBuffer over binder. The entire array
    // is initialized to NULL at construction time, and buffers are allocated
    // for a slot when requestBuffer is called with that slot's index.
    BufferSlot mSlots[NUM_BUFFER_SLOTS];

    // mDefaultWidth holds the default width of allocated buffers. It is used
    // in requestBuffers() if a width and height of zero is specified.
    uint32_t mDefaultWidth;

    // mDefaultHeight holds the default height of allocated buffers. It is used
    // in requestBuffers() if a width and height of zero is specified.
    uint32_t mDefaultHeight;

    // mPixelFormat holds the pixel format of allocated buffers. It is used
    // in requestBuffers() if a format of zero is specified.
    uint32_t mPixelFormat;

    // mBufferCount is the number of buffer slots that the client and server
    // must maintain. It defaults to MIN_ASYNC_BUFFER_SLOTS and can be changed
    // by calling setBufferCount or setBufferCountServer
    int mBufferCount;

    // mClientBufferCount is the number of buffer slots requested by the
    // client. The default is zero, which means the client doesn't care how
    // many buffers there are
    int mClientBufferCount;

    // mServerBufferCount buffer count requested by the server-side
    int mServerBufferCount;

    // mCurrentSlot is the buffer slot index of the buffer that is currently
    // being used by buffer consumer
    // (e.g. StageFrightRecorder in the case of SurfaceMediaSource or GLTexture
    // in the case of SurfaceTexture).
    // It is initialized to INVALID_BUFFER_SLOT,
    // indicating that no buffer slot is currently bound to the texture. Note,
    // however, that a value of INVALID_BUFFER_SLOT does not necessarily mean
    // that no buffer is bound to the texture. A call to setBufferCount will
    // reset mCurrentTexture to INVALID_BUFFER_SLOT.
    int mCurrentSlot;


    // mCurrentBuf is the graphic buffer of the current slot to be used by
    // buffer consumer. It's possible that this buffer is not associated
    // with any buffer slot, so we must track it separately in order to
    // properly use IGraphicBufferAlloc::freeAllGraphicBuffersExcept.
    sp<GraphicBuffer> mCurrentBuf;


    // mCurrentTimestamp is the timestamp for the current texture. It
    // gets set to mLastQueuedTimestamp each time updateTexImage is called.
    int64_t mCurrentTimestamp;

    // mGraphicBufferAlloc is the connection to SurfaceFlinger that is used to
    // allocate new GraphicBuffer objects.
    sp<IGraphicBufferAlloc> mGraphicBufferAlloc;

    // mFrameAvailableListener is the listener object that will be called when a
    // new frame becomes available. If it is not NULL it will be called from
    // queueBuffer.
    sp<FrameAvailableListener> mFrameAvailableListener;

    // mSynchronousMode whether we're in synchronous mode or not
    bool mSynchronousMode;

    // mConnectedApi indicates the API that is currently connected to this
    // SurfaceTexture.  It defaults to NO_CONNECTED_API (= 0), and gets updated
    // by the connect and disconnect methods.
    int mConnectedApi;

    // mDequeueCondition condition used for dequeueBuffer in synchronous mode
    mutable Condition mDequeueCondition;


    // mQueue is a FIFO of queued buffers used in synchronous mode
    typedef Vector<int> Fifo;
    Fifo mQueue;

    // mMutex is the mutex used to prevent concurrent access to the member
    // variables of SurfaceMediaSource objects. It must be locked whenever the
    // member variables are accessed.
    mutable Mutex mMutex;

    ////////////////////////// For MediaSource
    // Set to a default of 30 fps if not specified by the client side
    int32_t mFrameRate;

    // mStarted is a flag to check if the recording has started
    bool mStarted;

    // mFrameAvailableCondition condition used to indicate whether there
    // is a frame available for dequeuing
    Condition mFrameAvailableCondition;
    Condition mFrameCompleteCondition;

    // Avoid copying and equating and default constructor
    DISALLOW_IMPLICIT_CONSTRUCTORS(SurfaceMediaSource);
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GUI_SURFACEMEDIASOURCE_H
