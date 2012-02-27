/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_GUI_BUFFERQUEUE_H
#define ANDROID_GUI_BUFFERQUEUE_H

#include <EGL/egl.h>

#include <gui/IGraphicBufferAlloc.h>
#include <gui/ISurfaceTexture.h>

#include <ui/GraphicBuffer.h>

#include <utils/String8.h>
#include <utils/Vector.h>
#include <utils/threads.h>

namespace android {
// ----------------------------------------------------------------------------

class BufferQueue : public BnSurfaceTexture {
public:
    enum { MIN_UNDEQUEUED_BUFFERS = 2 };
    enum {
        MIN_ASYNC_BUFFER_SLOTS = MIN_UNDEQUEUED_BUFFERS + 1,
        MIN_SYNC_BUFFER_SLOTS  = MIN_UNDEQUEUED_BUFFERS
    };
    enum { NUM_BUFFER_SLOTS = 32 };
    enum { NO_CONNECTED_API = 0 };

    struct FrameAvailableListener : public virtual RefBase {
        // onFrameAvailable() is called from queueBuffer() each time an
        // additional frame becomes available for consumption. This means that
        // frames that are queued while in asynchronous mode only trigger the
        // callback if no previous frames are pending. Frames queued while in
        // synchronous mode always trigger the callback.
        //
        // This is called without any lock held and can be called concurrently
        // by multiple threads.
        virtual void onFrameAvailable() = 0;
    };

    // BufferQueue manages a pool of gralloc memory slots to be used
    // by producers and consumers.
    // allowSynchronousMode specifies whether or not synchronous mode can be
    // enabled.
    BufferQueue(bool allowSynchronousMode = true);
    virtual ~BufferQueue();

    virtual int query(int what, int* value);

    // setBufferCount updates the number of available buffer slots.  After
    // calling this all buffer slots are both unallocated and owned by the
    // BufferQueue object (i.e. they are not owned by the client).
    virtual status_t setBufferCount(int bufferCount);

    virtual status_t requestBuffer(int slot, sp<GraphicBuffer>* buf);

    // dequeueBuffer gets the next buffer slot index for the client to use. If a
    // buffer slot is available then that slot index is written to the location
    // pointed to by the buf argument and a status of OK is returned.  If no
    // slot is available then a status of -EBUSY is returned and buf is
    // unmodified.
    // The width and height parameters must be no greater than the minimum of
    // GL_MAX_VIEWPORT_DIMS and GL_MAX_TEXTURE_SIZE (see: glGetIntegerv).
    // An error due to invalid dimensions might not be reported until
    // updateTexImage() is called.
    virtual status_t dequeueBuffer(int *buf, uint32_t width, uint32_t height,
            uint32_t format, uint32_t usage);

    // queueBuffer returns a filled buffer to the BufferQueue. In addition, a
    // timestamp must be provided for the buffer. The timestamp is in
    // nanoseconds, and must be monotonically increasing. Its other semantics
    // (zero point, etc) are client-dependent and should be documented by the
    // client.
    virtual status_t queueBuffer(int buf, int64_t timestamp,
            uint32_t* outWidth, uint32_t* outHeight, uint32_t* outTransform);
    virtual void cancelBuffer(int buf);
    virtual status_t setCrop(const Rect& reg);
    virtual status_t setTransform(uint32_t transform);
    virtual status_t setScalingMode(int mode);

    // setSynchronousMode set whether dequeueBuffer is synchronous or
    // asynchronous. In synchronous mode, dequeueBuffer blocks until
    // a buffer is available, the currently bound buffer can be dequeued and
    // queued buffers will be retired in order.
    // The default mode is asynchronous.
    virtual status_t setSynchronousMode(bool enabled);

    // connect attempts to connect a producer client API to the BufferQueue.
    // This must be called before any other ISurfaceTexture methods are called
    // except for getAllocator.
    //
    // This method will fail if the connect was previously called on the
    // BufferQueue and no corresponding disconnect call was made.
    virtual status_t connect(int api,
            uint32_t* outWidth, uint32_t* outHeight, uint32_t* outTransform);

    // disconnect attempts to disconnect a producer client API from the
    // BufferQueue. Calling this method will cause any subsequent calls to other
    // ISurfaceTexture methods to fail except for getAllocator and connect.
    // Successfully calling connect after this will allow the other methods to
    // succeed again.
    //
    // This method will fail if the the BufferQueue is not currently
    // connected to the specified client API.
    virtual status_t disconnect(int api);

protected:

    // freeBufferLocked frees the resources (both GraphicBuffer and EGLImage)
    // for the given slot.
    void freeBufferLocked(int index);

    // freeAllBuffersLocked frees the resources (both GraphicBuffer and
    // EGLImage) for all slots.
    void freeAllBuffersLocked();

    // freeAllBuffersExceptHeadLocked frees the resources (both GraphicBuffer
    // and EGLImage) for all slots except the head of mQueue
    void freeAllBuffersExceptHeadLocked();

    // drainQueueLocked drains the buffer queue if we're in synchronous mode
    // returns immediately otherwise. It returns NO_INIT if the BufferQueue
    // became abandoned or disconnected during this call.
    status_t drainQueueLocked();

    // drainQueueAndFreeBuffersLocked drains the buffer queue if we're in
    // synchronous mode and free all buffers. In asynchronous mode, all buffers
    // are freed except the current buffer.
    status_t drainQueueAndFreeBuffersLocked();

    status_t setBufferCountServerLocked(int bufferCount);

    enum { INVALID_BUFFER_SLOT = -1 };

    struct BufferSlot {

        BufferSlot()
        : mEglImage(EGL_NO_IMAGE_KHR),
          mEglDisplay(EGL_NO_DISPLAY),
          mBufferState(BufferSlot::FREE),
          mRequestBufferCalled(false),
          mTransform(0),
          mScalingMode(NATIVE_WINDOW_SCALING_MODE_FREEZE),
          mTimestamp(0),
          mFrameNumber(0),
          mFence(EGL_NO_SYNC_KHR) {
            mCrop.makeInvalid();
        }

        // mGraphicBuffer points to the buffer allocated for this slot or is NULL
        // if no buffer has been allocated.
        sp<GraphicBuffer> mGraphicBuffer;

        // mEglImage is the EGLImage created from mGraphicBuffer.
        EGLImageKHR mEglImage;

        // mEglDisplay is the EGLDisplay used to create mEglImage.
        EGLDisplay mEglDisplay;

        // BufferState represents the different states in which a buffer slot
        // can be.
        enum BufferState {
            // FREE indicates that the buffer is not currently being used and
            // will not be used in the future until it gets dequeued and
            // subsequently queued by the client.
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

        // mCrop is the current crop rectangle for this buffer slot. This gets
        // set to mNextCrop each time queueBuffer gets called for this buffer.
        Rect mCrop;

        // mTransform is the current transform flags for this buffer slot. This
        // gets set to mNextTransform each time queueBuffer gets called for this
        // slot.
        uint32_t mTransform;

        // mScalingMode is the current scaling mode for this buffer slot. This
        // gets set to mNextScalingMode each time queueBuffer gets called for
        // this slot.
        uint32_t mScalingMode;

        // mTimestamp is the current timestamp for this buffer slot. This gets
        // to set by queueBuffer each time this slot is queued.
        int64_t mTimestamp;

        // mFrameNumber is the number of the queued frame for this slot.
        uint64_t mFrameNumber;

        // mFence is the EGL sync object that must signal before the buffer
        // associated with this buffer slot may be dequeued. It is initialized
        // to EGL_NO_SYNC_KHR when the buffer is created and (optionally, based
        // on a compile-time option) set to a new sync object in updateTexImage.
        EGLSyncKHR mFence;
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

    // mClientBufferCount is the number of buffer slots requested by the client.
    // The default is zero, which means the client doesn't care how many buffers
    // there is.
    int mClientBufferCount;

    // mServerBufferCount buffer count requested by the server-side
    int mServerBufferCount;

    // mCurrentTexture is the buffer slot index of the buffer that is currently
    // bound to the OpenGL texture. It is initialized to INVALID_BUFFER_SLOT,
    // indicating that no buffer slot is currently bound to the texture. Note,
    // however, that a value of INVALID_BUFFER_SLOT does not necessarily mean
    // that no buffer is bound to the texture. A call to setBufferCount will
    // reset mCurrentTexture to INVALID_BUFFER_SLOT.
    int mCurrentTexture;

    // mNextCrop is the crop rectangle that will be used for the next buffer
    // that gets queued. It is set by calling setCrop.
    Rect mNextCrop;

    // mNextTransform is the transform identifier that will be used for the next
    // buffer that gets queued. It is set by calling setTransform.
    uint32_t mNextTransform;

    // mNextScalingMode is the scaling mode that will be used for the next
    // buffers that get queued. It is set by calling setScalingMode.
    int mNextScalingMode;

    // mGraphicBufferAlloc is the connection to SurfaceFlinger that is used to
    // allocate new GraphicBuffer objects.
    sp<IGraphicBufferAlloc> mGraphicBufferAlloc;

    // mFrameAvailableListener is the listener object that will be called when a
    // new frame becomes available. If it is not NULL it will be called from
    // queueBuffer.
    sp<FrameAvailableListener> mFrameAvailableListener;

    // mSynchronousMode whether we're in synchronous mode or not
    bool mSynchronousMode;

    // mAllowSynchronousMode whether we allow synchronous mode or not
    const bool mAllowSynchronousMode;

    // mConnectedApi indicates the API that is currently connected to this
    // BufferQueue.  It defaults to NO_CONNECTED_API (= 0), and gets updated
    // by the connect and disconnect methods.
    int mConnectedApi;

    // mDequeueCondition condition used for dequeueBuffer in synchronous mode
    mutable Condition mDequeueCondition;

    // mQueue is a FIFO of queued buffers used in synchronous mode
    typedef Vector<int> Fifo;
    Fifo mQueue;

    // mAbandoned indicates that the BufferQueue will no longer be used to
    // consume images buffers pushed to it using the ISurfaceTexture interface.
    // It is initialized to false, and set to true in the abandon method.  A
    // BufferQueue that has been abandoned will return the NO_INIT error from
    // all ISurfaceTexture methods capable of returning an error.
    bool mAbandoned;

    // mName is a string used to identify the BufferQueue in log messages.
    // It is set by the setName method.
    String8 mName;

    // mMutex is the mutex used to prevent concurrent access to the member
    // variables of BufferQueue objects. It must be locked whenever the
    // member variables are accessed.
    mutable Mutex mMutex;

    // mFrameCounter is the free running counter, incremented for every buffer queued
    // with the surface Texture.
    uint64_t mFrameCounter;
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GUI_BUFFERQUEUE_H
