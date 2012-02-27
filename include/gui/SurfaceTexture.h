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
#include <GLES2/gl2ext.h>

#include <gui/ISurfaceTexture.h>
#include <gui/BufferQueue.h>

#include <ui/GraphicBuffer.h>

#include <utils/String8.h>
#include <utils/Vector.h>
#include <utils/threads.h>

#define ANDROID_GRAPHICS_SURFACETEXTURE_JNI_ID "mSurfaceTexture"

namespace android {
// ----------------------------------------------------------------------------


class String8;

class SurfaceTexture : public BufferQueue {
public:

    // SurfaceTexture constructs a new SurfaceTexture object. tex indicates the
    // name of the OpenGL ES texture to which images are to be streamed. This
    // texture name cannot be changed once the SurfaceTexture is created.
    // allowSynchronousMode specifies whether or not synchronous mode can be
    // enabled. texTarget specifies the OpenGL ES texture target to which the
    // texture will be bound in updateTexImage. useFenceSync specifies whether
    // fences should be used to synchronize access to buffers if that behavior
    // is enabled at compile-time.
    SurfaceTexture(GLuint tex, bool allowSynchronousMode = true,
            GLenum texTarget = GL_TEXTURE_EXTERNAL_OES, bool useFenceSync = true);

    virtual ~SurfaceTexture();



    // updateTexImage sets the image contents of the target texture to that of
    // the most recently queued buffer.
    //
    // This call may only be made while the OpenGL ES context to which the
    // target texture belongs is bound to the calling thread.
    status_t updateTexImage();

    // setBufferCountServer set the buffer count. If the client has requested
    // a buffer count using setBufferCount, the server-buffer count will
    // take effect once the client sets the count back to zero.
    status_t setBufferCountServer(int bufferCount);

    // getTransformMatrix retrieves the 4x4 texture coordinate transform matrix
    // associated with the texture image set by the most recent call to
    // updateTexImage.
    //
    // This transform matrix maps 2D homogeneous texture coordinates of the form
    // (s, t, 0, 1) with s and t in the inclusive range [0, 1] to the texture
    // coordinate that should be used to sample that location from the texture.
    // Sampling the texture outside of the range of this transform is undefined.
    //
    // This transform is necessary to compensate for transforms that the stream
    // content producer may implicitly apply to the content. By forcing users of
    // a SurfaceTexture to apply this transform we avoid performing an extra
    // copy of the data that would be needed to hide the transform from the
    // user.
    //
    // The matrix is stored in column-major order so that it may be passed
    // directly to OpenGL ES via the glLoadMatrixf or glUniformMatrix4fv
    // functions.
    void getTransformMatrix(float mtx[16]);

    // getTimestamp retrieves the timestamp associated with the texture image
    // set by the most recent call to updateTexImage.
    //
    // The timestamp is in nanoseconds, and is monotonically increasing. Its
    // other semantics (zero point, etc) are source-dependent and should be
    // documented by the source.
    int64_t getTimestamp();

    // setFrameAvailableListener sets the listener object that will be notified
    // when a new frame becomes available.
    void setFrameAvailableListener(const sp<FrameAvailableListener>& listener);

    // getAllocator retrieves the binder object that must be referenced as long
    // as the GraphicBuffers dequeued from this SurfaceTexture are referenced.
    // Holding this binder reference prevents SurfaceFlinger from freeing the
    // buffers before the client is done with them.
    sp<IBinder> getAllocator();

    // setDefaultBufferSize is used to set the size of buffers returned by
    // requestBuffers when a with and height of zero is requested.
    // A call to setDefaultBufferSize() may trigger requestBuffers() to
    // be called from the client.
    // The width and height parameters must be no greater than the minimum of
    // GL_MAX_VIEWPORT_DIMS and GL_MAX_TEXTURE_SIZE (see: glGetIntegerv).
    // An error due to invalid dimensions might not be reported until
    // updateTexImage() is called.
    status_t setDefaultBufferSize(uint32_t width, uint32_t height);

    // getCurrentBuffer returns the buffer associated with the current image.
    sp<GraphicBuffer> getCurrentBuffer() const;

    // getCurrentTextureTarget returns the texture target of the current
    // texture as returned by updateTexImage().
    GLenum getCurrentTextureTarget() const;

    // getCurrentCrop returns the cropping rectangle of the current buffer
    Rect getCurrentCrop() const;

    // getCurrentTransform returns the transform of the current buffer
    uint32_t getCurrentTransform() const;

    // getCurrentScalingMode returns the scaling mode of the current buffer
    uint32_t getCurrentScalingMode() const;

    // isSynchronousMode returns whether the SurfaceTexture is currently in
    // synchronous mode.
    bool isSynchronousMode() const;

    // abandon frees all the buffers and puts the SurfaceTexture into the
    // 'abandoned' state.  Once put in this state the SurfaceTexture can never
    // leave it.  When in the 'abandoned' state, all methods of the
    // ISurfaceTexture interface will fail with the NO_INIT error.
    //
    // Note that while calling this method causes all the buffers to be freed
    // from the perspective of the the SurfaceTexture, if there are additional
    // references on the buffers (e.g. if a buffer is referenced by a client or
    // by OpenGL ES as a texture) then those buffer will remain allocated.
    void abandon();

    // set the name of the SurfaceTexture that will be used to identify it in
    // log messages.
    void setName(const String8& name);

    // dump our state in a String
    void dump(String8& result) const;
    void dump(String8& result, const char* prefix, char* buffer, size_t SIZE) const;

protected:

    static bool isExternalFormat(uint32_t format);

private:

    // createImage creates a new EGLImage from a GraphicBuffer.
    EGLImageKHR createImage(EGLDisplay dpy,
            const sp<GraphicBuffer>& graphicBuffer);

    // computeCurrentTransformMatrix computes the transform matrix for the
    // current texture.  It uses mCurrentTransform and the current GraphicBuffer
    // to compute this matrix and stores it in mCurrentTransformMatrix.
    void computeCurrentTransformMatrix();

    // mCurrentTextureBuf is the graphic buffer of the current texture. It's
    // possible that this buffer is not associated with any buffer slot, so we
    // must track it separately in order to support the getCurrentBuffer method.
    sp<GraphicBuffer> mCurrentTextureBuf;

    // mCurrentCrop is the crop rectangle that applies to the current texture.
    // It gets set each time updateTexImage is called.
    Rect mCurrentCrop;

    // mCurrentTransform is the transform identifier for the current texture. It
    // gets set each time updateTexImage is called.
    uint32_t mCurrentTransform;

    // mCurrentScalingMode is the scaling mode for the current texture. It gets
    // set to each time updateTexImage is called.
    uint32_t mCurrentScalingMode;

    // mCurrentTransformMatrix is the transform matrix for the current texture.
    // It gets computed by computeTransformMatrix each time updateTexImage is
    // called.
    float mCurrentTransformMatrix[16];

    // mCurrentTimestamp is the timestamp for the current texture. It
    // gets set each time updateTexImage is called.
    int64_t mCurrentTimestamp;

    // mTexName is the name of the OpenGL texture to which streamed images will
    // be bound when updateTexImage is called. It is set at construction time
    // changed with a call to setTexName.
    const GLuint mTexName;

    // mUseFenceSync indicates whether creation of the EGL_KHR_fence_sync
    // extension should be used to prevent buffers from being dequeued before
    // it's safe for them to be written. It gets set at construction time and
    // never changes.
    const bool mUseFenceSync;

    // mTexTarget is the GL texture target with which the GL texture object is
    // associated.  It is set in the constructor and never changed.  It is
    // almost always GL_TEXTURE_EXTERNAL_OES except for one use case in Android
    // Browser.  In that case it is set to GL_TEXTURE_2D to allow
    // glCopyTexSubImage to read from the texture.  This is a hack to work
    // around a GL driver limitation on the number of FBO attachments, which the
    // browser's tile cache exceeds.
    const GLenum mTexTarget;

};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GUI_SURFACETEXTURE_H
