/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <gui/BufferQueueDefs.h>
#include <gui/ConsumerBase.h>

#include <ui/FenceTime.h>
#include <ui/GraphicBuffer.h>

#include <utils/Mutex.h>
#include <utils/String8.h>

#include "EGLConsumer.h"
#include "ImageConsumer.h"

namespace android {

namespace uirenderer {
class RenderState;
}

/*
 * SurfaceTexture consumes buffers of graphics data from a BufferQueue,
 * and makes them available to HWUI render thread as a SkImage and to
 * an application GL render thread as an OpenGL texture.
 *
 * When attached to an application GL render thread, a typical usage
 * pattern is to set up the SurfaceTexture with the
 * desired options, and call updateTexImage() when a new frame is desired.
 * If a new frame is available, the texture will be updated.  If not,
 * the previous contents are retained.
 *
 * When attached to a HWUI render thread, the TextureView implementation
 * calls dequeueImage, which either pulls a new SkImage or returns the
 * last cached SkImage if BufferQueue is empty.
 * When attached to HWUI render thread, SurfaceTexture is compatible to
 * both Vulkan and GL drawing pipelines.
 */
class ANDROID_API SurfaceTexture : public ConsumerBase {
public:
    enum { TEXTURE_EXTERNAL = 0x8D65 };  // GL_TEXTURE_EXTERNAL_OES
    typedef ConsumerBase::FrameAvailableListener FrameAvailableListener;

    /**
     * SurfaceTexture constructs a new SurfaceTexture object. If the constructor with
     * the tex parameter is used, tex indicates the name of the OpenGL ES
     * texture to which images are to be streamed. texTarget specifies the
     * OpenGL ES texture target to which the texture will be bound in
     * updateTexImage. useFenceSync specifies whether fences should be used to
     * synchronize access to buffers if that behavior is enabled at
     * compile-time.
     *
     * A SurfaceTexture may be detached from one OpenGL ES context and then
     * attached to a different context using the detachFromContext and
     * attachToContext methods, respectively. The intention of these methods is
     * purely to allow a SurfaceTexture to be transferred from one consumer
     * context to another. If such a transfer is not needed there is no
     * requirement that either of these methods be called.
     *
     * If the constructor with the tex parameter is used, the SurfaceTexture is
     * created in a state where it is considered attached to an OpenGL ES
     * context for the purposes of the attachToContext and detachFromContext
     * methods. However, despite being considered "attached" to a context, the
     * specific OpenGL ES context doesn't get latched until the first call to
     * updateTexImage. After that point, all calls to updateTexImage must be
     * made with the same OpenGL ES context current.
     *
     * If the constructor without the tex parameter is used, the SurfaceTexture is
     * created in a detached state, and attachToContext must be called before
     * calls to updateTexImage.
     */
    SurfaceTexture(const sp<IGraphicBufferConsumer>& bq, uint32_t tex, uint32_t texureTarget,
                   bool useFenceSync, bool isControlledByApp);

    SurfaceTexture(const sp<IGraphicBufferConsumer>& bq, uint32_t texureTarget, bool useFenceSync,
                   bool isControlledByApp);

    /**
     * updateTexImage acquires the most recently queued buffer, and sets the
     * image contents of the target texture to it.
     *
     * This call may only be made while the OpenGL ES context to which the
     * target texture belongs is bound to the calling thread.
     *
     * This calls doGLFenceWait to ensure proper synchronization.
     */
    status_t updateTexImage();

    /**
     * releaseTexImage releases the texture acquired in updateTexImage().
     * This is intended to be used in single buffer mode.
     *
     * This call may only be made while the OpenGL ES context to which the
     * target texture belongs is bound to the calling thread.
     */
    status_t releaseTexImage();

    /**
     * getTransformMatrix retrieves the 4x4 texture coordinate transform matrix
     * associated with the texture image set by the most recent call to
     * updateTexImage.
     *
     * This transform matrix maps 2D homogeneous texture coordinates of the form
     * (s, t, 0, 1) with s and t in the inclusive range [0, 1] to the texture
     * coordinate that should be used to sample that location from the texture.
     * Sampling the texture outside of the range of this transform is undefined.
     *
     * This transform is necessary to compensate for transforms that the stream
     * content producer may implicitly apply to the content. By forcing users of
     * a SurfaceTexture to apply this transform we avoid performing an extra
     * copy of the data that would be needed to hide the transform from the
     * user.
     *
     * The matrix is stored in column-major order so that it may be passed
     * directly to OpenGL ES via the glLoadMatrixf or glUniformMatrix4fv
     * functions.
     */
    void getTransformMatrix(float mtx[16]);

    /**
     * Computes the transform matrix documented by getTransformMatrix
     * from the BufferItem sub parts.
     */
    static void computeTransformMatrix(float outTransform[16], const sp<GraphicBuffer>& buf,
                                       const Rect& cropRect, uint32_t transform, bool filtering);

    /**
     * Scale the crop down horizontally or vertically such that it has the
     * same aspect ratio as the buffer does.
     */
    static Rect scaleDownCrop(const Rect& crop, uint32_t bufferWidth, uint32_t bufferHeight);

    /**
     * getTimestamp retrieves the timestamp associated with the texture image
     * set by the most recent call to updateTexImage.
     *
     * The timestamp is in nanoseconds, and is monotonically increasing. Its
     * other semantics (zero point, etc) are source-dependent and should be
     * documented by the source.
     */
    int64_t getTimestamp();

    /**
     * getDataSpace retrieves the DataSpace associated with the texture image
     * set by the most recent call to updateTexImage.
     */
    android_dataspace getCurrentDataSpace();

    /**
     * getFrameNumber retrieves the frame number associated with the texture
     * image set by the most recent call to updateTexImage.
     *
     * The frame number is an incrementing counter set to 0 at the creation of
     * the BufferQueue associated with this consumer.
     */
    uint64_t getFrameNumber();

    /**
     * setDefaultBufferSize is used to set the size of buffers returned by
     * requestBuffers when a with and height of zero is requested.
     * A call to setDefaultBufferSize() may trigger requestBuffers() to
     * be called from the client.
     * The width and height parameters must be no greater than the minimum of
     * GL_MAX_VIEWPORT_DIMS and GL_MAX_TEXTURE_SIZE (see: glGetIntegerv).
     * An error due to invalid dimensions might not be reported until
     * updateTexImage() is called.
     */
    status_t setDefaultBufferSize(uint32_t width, uint32_t height);

    /**
     * setFilteringEnabled sets whether the transform matrix should be computed
     * for use with bilinear filtering.
     */
    void setFilteringEnabled(bool enabled);

    /**
     * getCurrentTextureTarget returns the texture target of the current
     * texture as returned by updateTexImage().
     */
    uint32_t getCurrentTextureTarget() const;

    /**
     * getCurrentCrop returns the cropping rectangle of the current buffer.
     */
    Rect getCurrentCrop() const;

    /**
     * getCurrentTransform returns the transform of the current buffer.
     */
    uint32_t getCurrentTransform() const;

    /**
     * getCurrentScalingMode returns the scaling mode of the current buffer.
     */
    uint32_t getCurrentScalingMode() const;

    /**
     * getCurrentFence returns the fence indicating when the current buffer is
     * ready to be read from.
     */
    sp<Fence> getCurrentFence() const;

    /**
     * getCurrentFence returns the FenceTime indicating when the current
     * buffer is ready to be read from.
     */
    std::shared_ptr<FenceTime> getCurrentFenceTime() const;

    /**
     * setConsumerUsageBits overrides the ConsumerBase method to OR
     * DEFAULT_USAGE_FLAGS to usage.
     */
    status_t setConsumerUsageBits(uint64_t usage);

    /**
     * detachFromContext detaches the SurfaceTexture from the calling thread's
     * current OpenGL ES context.  This context must be the same as the context
     * that was current for previous calls to updateTexImage.
     *
     * Detaching a SurfaceTexture from an OpenGL ES context will result in the
     * deletion of the OpenGL ES texture object into which the images were being
     * streamed.  After a SurfaceTexture has been detached from the OpenGL ES
     * context calls to updateTexImage will fail returning INVALID_OPERATION
     * until the SurfaceTexture is attached to a new OpenGL ES context using the
     * attachToContext method.
     */
    status_t detachFromContext();

    /**
     * attachToContext attaches a SurfaceTexture that is currently in the
     * 'detached' state to the current OpenGL ES context.  A SurfaceTexture is
     * in the 'detached' state iff detachFromContext has successfully been
     * called and no calls to attachToContext have succeeded since the last
     * detachFromContext call.  Calls to attachToContext made on a
     * SurfaceTexture that is not in the 'detached' state will result in an
     * INVALID_OPERATION error.
     *
     * The tex argument specifies the OpenGL ES texture object name in the
     * new context into which the image contents will be streamed.  A successful
     * call to attachToContext will result in this texture object being bound to
     * the texture target and populated with the image contents that were
     * current at the time of the last call to detachFromContext.
     */
    status_t attachToContext(uint32_t tex);

    sk_sp<SkImage> dequeueImage(SkMatrix& transformMatrix, bool* queueEmpty,
                                uirenderer::RenderState& renderState);

    /**
     * attachToView attaches a SurfaceTexture that is currently in the
     * 'detached' state to HWUI View system.
     */
    void attachToView();

    /**
     * detachFromView detaches a SurfaceTexture from HWUI View system.
     */
    void detachFromView();

protected:
    /**
     * abandonLocked overrides the ConsumerBase method to clear
     * mCurrentTextureImage in addition to the ConsumerBase behavior.
     */
    virtual void abandonLocked();

    /**
     * dumpLocked overrides the ConsumerBase method to dump SurfaceTexture-
     * specific info in addition to the ConsumerBase behavior.
     */
    virtual void dumpLocked(String8& result, const char* prefix) const override;

    /**
     * acquireBufferLocked overrides the ConsumerBase method to update the
     * mEglSlots array in addition to the ConsumerBase behavior.
     */
    virtual status_t acquireBufferLocked(BufferItem* item, nsecs_t presentWhen,
                                         uint64_t maxFrameNumber = 0) override;

    /**
     * releaseBufferLocked overrides the ConsumerBase method to update the
     * mEglSlots array in addition to the ConsumerBase.
     */
    virtual status_t releaseBufferLocked(int slot, const sp<GraphicBuffer> graphicBuffer,
                                         EGLDisplay display, EGLSyncKHR eglFence) override;

    /**
     * freeBufferLocked frees up the given buffer slot. If the slot has been
     * initialized this will release the reference to the GraphicBuffer in that
     * slot and destroy the EGLImage in that slot.  Otherwise it has no effect.
     *
     * This method must be called with mMutex locked.
     */
    virtual void freeBufferLocked(int slotIndex);

    /**
     * computeCurrentTransformMatrixLocked computes the transform matrix for the
     * current texture.  It uses mCurrentTransform and the current GraphicBuffer
     * to compute this matrix and stores it in mCurrentTransformMatrix.
     * mCurrentTextureImage must not be NULL.
     */
    void computeCurrentTransformMatrixLocked();

    /**
     * The default consumer usage flags that SurfaceTexture always sets on its
     * BufferQueue instance; these will be OR:d with any additional flags passed
     * from the SurfaceTexture user. In particular, SurfaceTexture will always
     * consume buffers as hardware textures.
     */
    static const uint64_t DEFAULT_USAGE_FLAGS = GraphicBuffer::USAGE_HW_TEXTURE;

    /**
     * mCurrentCrop is the crop rectangle that applies to the current texture.
     * It gets set each time updateTexImage is called.
     */
    Rect mCurrentCrop;

    /**
     * mCurrentTransform is the transform identifier for the current texture. It
     * gets set each time updateTexImage is called.
     */
    uint32_t mCurrentTransform;

    /**
     * mCurrentScalingMode is the scaling mode for the current texture. It gets
     * set each time updateTexImage is called.
     */
    uint32_t mCurrentScalingMode;

    /**
     * mCurrentFence is the fence received from BufferQueue in updateTexImage.
     */
    sp<Fence> mCurrentFence;

    /**
     * The FenceTime wrapper around mCurrentFence.
     */
    std::shared_ptr<FenceTime> mCurrentFenceTime{FenceTime::NO_FENCE};

    /**
     * mCurrentTransformMatrix is the transform matrix for the current texture.
     * It gets computed by computeTransformMatrix each time updateTexImage is
     * called.
     */
    float mCurrentTransformMatrix[16];

    /**
     * mCurrentTimestamp is the timestamp for the current texture. It
     * gets set each time updateTexImage is called.
     */
    int64_t mCurrentTimestamp;

    /**
     * mCurrentDataSpace is the dataspace for the current texture. It
     * gets set each time updateTexImage is called.
     */
    android_dataspace mCurrentDataSpace;

    /**
     * mCurrentFrameNumber is the frame counter for the current texture.
     * It gets set each time updateTexImage is called.
     */
    uint64_t mCurrentFrameNumber;

    uint32_t mDefaultWidth, mDefaultHeight;

    /**
     * mFilteringEnabled indicates whether the transform matrix is computed for
     * use with bilinear filtering. It defaults to true and is changed by
     * setFilteringEnabled().
     */
    bool mFilteringEnabled;

    /**
     * mTexName is the name of the OpenGL texture to which streamed images will
     * be bound when updateTexImage is called. It is set at construction time
     * and can be changed with a call to attachToContext.
     */
    uint32_t mTexName;

    /**
     * mUseFenceSync indicates whether creation of the EGL_KHR_fence_sync
     * extension should be used to prevent buffers from being dequeued before
     * it's safe for them to be written. It gets set at construction time and
     * never changes.
     */
    const bool mUseFenceSync;

    /**
     * mTexTarget is the GL texture target with which the GL texture object is
     * associated.  It is set in the constructor and never changed.  It is
     * almost always GL_TEXTURE_EXTERNAL_OES except for one use case in Android
     * Browser.  In that case it is set to GL_TEXTURE_2D to allow
     * glCopyTexSubImage to read from the texture.  This is a hack to work
     * around a GL driver limitation on the number of FBO attachments, which the
     * browser's tile cache exceeds.
     */
    const uint32_t mTexTarget;

    /**
     * mCurrentTexture is the buffer slot index of the buffer that is currently
     * bound to the OpenGL texture. It is initialized to INVALID_BUFFER_SLOT,
     * indicating that no buffer slot is currently bound to the texture. Note,
     * however, that a value of INVALID_BUFFER_SLOT does not necessarily mean
     * that no buffer is bound to the texture. A call to setBufferCount will
     * reset mCurrentTexture to INVALID_BUFFER_SLOT.
     */
    int mCurrentTexture;

    enum class OpMode { detached, attachedToView, attachedToGL };
    /**
     * mOpMode indicates whether the SurfaceTexture is currently attached to
     * an OpenGL ES context or the HWUI view system.  For legacy reasons, this is initialized to,
     * "attachedToGL" indicating that the SurfaceTexture is considered to be attached to
     * whatever GL context is current at the time of the first updateTexImage call.
     * It is set to "detached" by detachFromContext, and then set to "attachedToGL" again by
     * attachToContext.
     * attachToView/detachFromView are used to attach/detach from HWUI view system.
     */
    OpMode mOpMode;

    /**
     * mEGLConsumer has SurfaceTexture logic used when attached to GL context.
     */
    EGLConsumer mEGLConsumer;

    /**
     * mImageConsumer has SurfaceTexture logic used when attached to HWUI view system.
     */
    ImageConsumer mImageConsumer;

    friend class ImageConsumer;
    friend class EGLConsumer;
};

// ----------------------------------------------------------------------------
}  // namespace android
