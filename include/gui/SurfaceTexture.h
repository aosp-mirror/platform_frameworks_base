/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_GUI_SURFACETEXTURE_H
#define ANDROID_GUI_SURFACETEXTURE_H

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>

#include <gui/ISurfaceTexture.h>

#include <ui/GraphicBuffer.h>

#include <utils/threads.h>

#define ANDROID_GRAPHICS_SURFACETEXTURE_JNI_ID "mSurfaceTexture"

namespace android {
// ----------------------------------------------------------------------------

class SurfaceTexture : public BnSurfaceTexture {
public:
    enum { MIN_BUFFER_SLOTS = 3 };
    enum { NUM_BUFFER_SLOTS = 32 };

    // tex indicates the name OpenGL texture to which images are to be streamed.
    // This texture name cannot be changed once the SurfaceTexture is created.
    SurfaceTexture(GLuint tex);

    virtual ~SurfaceTexture();

    // setBufferCount updates the number of available buffer slots.  After
    // calling this all buffer slots are both unallocated and owned by the
    // SurfaceTexture object (i.e. they are not owned by the client).
    virtual status_t setBufferCount(int bufferCount);

    virtual sp<GraphicBuffer> requestBuffer(int buf, uint32_t w, uint32_t h,
            uint32_t format, uint32_t usage);

    // dequeueBuffer gets the next buffer slot index for the client to use. If a
    // buffer slot is available then that slot index is written to the location
    // pointed to by the buf argument and a status of OK is returned.  If no
    // slot is available then a status of -EBUSY is returned and buf is
    // unmodified.
    virtual status_t dequeueBuffer(int *buf);

    virtual status_t queueBuffer(int buf);
    virtual void cancelBuffer(int buf);
    virtual status_t setCrop(const Rect& reg);
    virtual status_t setTransform(uint32_t transform);

    // updateTexImage sets the image contents of the target texture to that of
    // the most recently queued buffer.
    //
    // This call may only be made while the OpenGL ES context to which the
    // target texture belongs is bound to the calling thread.
    status_t updateTexImage();

private:

    // freeAllBuffers frees the resources (both GraphicBuffer and EGLImage) for
    // all slots.
    void freeAllBuffers();

    // createImage creates a new EGLImage from a GraphicBuffer.
    EGLImageKHR createImage(EGLDisplay dpy,
            const sp<GraphicBuffer>& graphicBuffer);

    enum { INVALID_BUFFER_SLOT = -1 };

    struct BufferSlot {
        // mGraphicBuffer points to the buffer allocated for this slot or is NULL
        // if no buffer has been allocated.
        sp<GraphicBuffer> mGraphicBuffer;

        // mEglImage is the EGLImage created from mGraphicBuffer.
        EGLImageKHR mEglImage;

        // mEglDisplay is the EGLDisplay used to create mEglImage.
        EGLDisplay mEglDisplay;

        // mOwnedByClient indicates whether the slot is currently accessible to a
        // client and should not be used by the SurfaceTexture object. It gets
        // set to true when dequeueBuffer returns the slot and is reset to false
        // when the client calls either queueBuffer or cancelBuffer on the slot.
        bool mOwnedByClient;
    };

    // mSlots is the array of buffer slots that must be mirrored on the client
    // side. This allows buffer ownership to be transferred between the client
    // and server without sending a GraphicBuffer over binder. The entire array
    // is initialized to NULL at construction time, and buffers are allocated
    // for a slot when requestBuffer is called with that slot's index.
    BufferSlot mSlots[NUM_BUFFER_SLOTS];

    // mBufferCount is the number of buffer slots that the client and server
    // must maintain. It defaults to MIN_BUFFER_SLOTS and can be changed by
    // calling setBufferCount.
    int mBufferCount;

    // mCurrentTexture is the buffer slot index of the buffer that is currently
    // bound to the OpenGL texture. A value of INVALID_BUFFER_SLOT, indicating
    // that no buffer is currently bound to the texture.
    int mCurrentTexture;

    // mLastQueued is the buffer slot index of the most recently enqueued buffer.
    // At construction time it is initialized to INVALID_BUFFER_SLOT, and is
    // updated each time queueBuffer is called.
    int mLastQueued;

    // mTexName is the name of the OpenGL texture to which streamed images will
    // be bound when updateTexImage is called. It is set at construction time 
    // changed with a call to setTexName.
    const GLuint mTexName;

    // mMutex is the mutex used to prevent concurrent access to the member
    // variables of SurfaceTexture objects. It must be locked whenever the
    // member variables are accessed.
    Mutex mMutex;
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GUI_SURFACETEXTURE_H
