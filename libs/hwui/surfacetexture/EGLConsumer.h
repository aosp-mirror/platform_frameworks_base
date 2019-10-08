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

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <gui/BufferQueueDefs.h>

#include <ui/FenceTime.h>
#include <ui/GraphicBuffer.h>
#include <utils/Mutex.h>

namespace android {

class SurfaceTexture;

/*
 * EGLConsumer implements the parts of SurfaceTexture that deal with
 * textures attached to an GL context.
 */
class EGLConsumer {
public:
    EGLConsumer();

    /**
     * updateTexImage acquires the most recently queued buffer, and sets the
     * image contents of the target texture to it.
     *
     * This call may only be made while the OpenGL ES context to which the
     * target texture belongs is bound to the calling thread.
     *
     * This calls doGLFenceWait to ensure proper synchronization.
     */
    status_t updateTexImage(SurfaceTexture& st);

    /*
     * releaseTexImage releases the texture acquired in updateTexImage().
     * This is intended to be used in single buffer mode.
     *
     * This call may only be made while the OpenGL ES context to which the
     * target texture belongs is bound to the calling thread.
     */
    status_t releaseTexImage(SurfaceTexture& st);

    /**
     * detachFromContext detaches the EGLConsumer from the calling thread's
     * current OpenGL ES context.  This context must be the same as the context
     * that was current for previous calls to updateTexImage.
     *
     * Detaching a EGLConsumer from an OpenGL ES context will result in the
     * deletion of the OpenGL ES texture object into which the images were being
     * streamed.  After a EGLConsumer has been detached from the OpenGL ES
     * context calls to updateTexImage will fail returning INVALID_OPERATION
     * until the EGLConsumer is attached to a new OpenGL ES context using the
     * attachToContext method.
     */
    status_t detachFromContext(SurfaceTexture& st);

    /**
     * attachToContext attaches a EGLConsumer that is currently in the
     * 'detached' state to the current OpenGL ES context.  A EGLConsumer is
     * in the 'detached' state iff detachFromContext has successfully been
     * called and no calls to attachToContext have succeeded since the last
     * detachFromContext call.  Calls to attachToContext made on a
     * EGLConsumer that is not in the 'detached' state will result in an
     * INVALID_OPERATION error.
     *
     * The tex argument specifies the OpenGL ES texture object name in the
     * new context into which the image contents will be streamed.  A successful
     * call to attachToContext will result in this texture object being bound to
     * the texture target and populated with the image contents that were
     * current at the time of the last call to detachFromContext.
     */
    status_t attachToContext(uint32_t tex, SurfaceTexture& st);

    /**
     * onAcquireBufferLocked amends the ConsumerBase method to update the
     * mEglSlots array in addition to the ConsumerBase behavior.
     */
    void onAcquireBufferLocked(BufferItem* item, SurfaceTexture& st);

    /**
     * onReleaseBufferLocked amends the ConsumerBase method to update the
     * mEglSlots array in addition to the ConsumerBase.
     */
    void onReleaseBufferLocked(int slot);

    /**
     * onFreeBufferLocked frees up the given buffer slot. If the slot has been
     * initialized this will release the reference to the GraphicBuffer in that
     * slot and destroy the EGLImage in that slot.  Otherwise it has no effect.
     */
    void onFreeBufferLocked(int slotIndex);

    /**
     * onAbandonLocked amends the ConsumerBase method to clear
     * mCurrentTextureImage in addition to the ConsumerBase behavior.
     */
    void onAbandonLocked();

protected:
    struct PendingRelease {
        PendingRelease()
                : isPending(false)
                , currentTexture(-1)
                , graphicBuffer()
                , display(nullptr)
                , fence(nullptr) {}

        bool isPending;
        int currentTexture;
        sp<GraphicBuffer> graphicBuffer;
        EGLDisplay display;
        EGLSyncKHR fence;
    };

    /**
     * This releases the buffer in the slot referenced by mCurrentTexture,
     * then updates state to refer to the BufferItem, which must be a
     * newly-acquired buffer. If pendingRelease is not null, the parameters
     * which would have been passed to releaseBufferLocked upon the successful
     * completion of the method will instead be returned to the caller, so that
     * it may call releaseBufferLocked itself later.
     */
    status_t updateAndReleaseLocked(const BufferItem& item, PendingRelease* pendingRelease,
                                    SurfaceTexture& st);

    /**
     * Binds mTexName and the current buffer to mTexTarget.  Uses
     * mCurrentTexture if it's set, mCurrentTextureImage if not.  If the
     * bind succeeds, this calls doGLFenceWait.
     */
    status_t bindTextureImageLocked(SurfaceTexture& st);

    /**
     * Gets the current EGLDisplay and EGLContext values, and compares them
     * to mEglDisplay and mEglContext.  If the fields have been previously
     * set, the values must match; if not, the fields are set to the current
     * values.
     * The contextCheck argument is used to ensure that a GL context is
     * properly set; when set to false, the check is not performed.
     */
    status_t checkAndUpdateEglStateLocked(SurfaceTexture& st, bool contextCheck = false);

    /**
     * EglImage is a utility class for tracking and creating EGLImageKHRs. There
     * is primarily just one image per slot, but there is also special cases:
     *  - For releaseTexImage, we use a debug image (mReleasedTexImage)
     *  - After freeBuffer, we must still keep the current image/buffer
     * Reference counting EGLImages lets us handle all these cases easily while
     * also only creating new EGLImages from buffers when required.
     */
    class EglImage : public LightRefBase<EglImage> {
    public:
        EglImage(sp<GraphicBuffer> graphicBuffer);

        /**
         * createIfNeeded creates an EGLImage if required (we haven't created
         * one yet, or the EGLDisplay or crop-rect has changed).
         */
        status_t createIfNeeded(EGLDisplay display, bool forceCreate = false);

        /**
         * This calls glEGLImageTargetTexture2DOES to bind the image to the
         * texture in the specified texture target.
         */
        void bindToTextureTarget(uint32_t texTarget);

        const sp<GraphicBuffer>& graphicBuffer() { return mGraphicBuffer; }
        const native_handle* graphicBufferHandle() {
            return mGraphicBuffer == nullptr ? nullptr : mGraphicBuffer->handle;
        }

    private:
        // Only allow instantiation using ref counting.
        friend class LightRefBase<EglImage>;
        virtual ~EglImage();

        // createImage creates a new EGLImage from a GraphicBuffer.
        EGLImageKHR createImage(EGLDisplay dpy, const sp<GraphicBuffer>& graphicBuffer);

        // Disallow copying
        EglImage(const EglImage& rhs);
        void operator=(const EglImage& rhs);

        // mGraphicBuffer is the buffer that was used to create this image.
        sp<GraphicBuffer> mGraphicBuffer;

        // mEglImage is the EGLImage created from mGraphicBuffer.
        EGLImageKHR mEglImage;

        // mEGLDisplay is the EGLDisplay that was used to create mEglImage.
        EGLDisplay mEglDisplay;

        // mCropRect is the crop rectangle passed to EGL when mEglImage
        // was created.
        Rect mCropRect;
    };

    /**
     * doGLFenceWaitLocked inserts a wait command into the OpenGL ES command
     * stream to ensure that it is safe for future OpenGL ES commands to
     * access the current texture buffer.
     */
    status_t doGLFenceWaitLocked(SurfaceTexture& st) const;

    /**
     * syncForReleaseLocked performs the synchronization needed to release the
     * current slot from an OpenGL ES context.  If needed it will set the
     * current slot's fence to guard against a producer accessing the buffer
     * before the outstanding accesses have completed.
     */
    status_t syncForReleaseLocked(EGLDisplay dpy, SurfaceTexture& st);

    /**
     * returns a graphic buffer used when the texture image has been released
     */
    static sp<GraphicBuffer> getDebugTexImageBuffer();

    /**
     * The default consumer usage flags that EGLConsumer always sets on its
     * BufferQueue instance; these will be OR:d with any additional flags passed
     * from the EGLConsumer user. In particular, EGLConsumer will always
     * consume buffers as hardware textures.
     */
    static const uint64_t DEFAULT_USAGE_FLAGS = GraphicBuffer::USAGE_HW_TEXTURE;

    /**
     * mCurrentTextureImage is the EglImage/buffer of the current texture. It's
     * possible that this buffer is not associated with any buffer slot, so we
     * must track it separately in order to support the getCurrentBuffer method.
     */
    sp<EglImage> mCurrentTextureImage;

    /**
     * EGLSlot contains the information and object references that
     * EGLConsumer maintains about a BufferQueue buffer slot.
     */
    struct EglSlot {
        EglSlot() : mEglFence(EGL_NO_SYNC_KHR) {}

        /**
         * mEglImage is the EGLImage created from mGraphicBuffer.
         */
        sp<EglImage> mEglImage;

        /**
         * mFence is the EGL sync object that must signal before the buffer
         * associated with this buffer slot may be dequeued. It is initialized
         * to EGL_NO_SYNC_KHR when the buffer is created and (optionally, based
         * on a compile-time option) set to a new sync object in updateTexImage.
         */
        EGLSyncKHR mEglFence;
    };

    /**
     * mEglDisplay is the EGLDisplay with which this EGLConsumer is currently
     * associated.  It is intialized to EGL_NO_DISPLAY and gets set to the
     * current display when updateTexImage is called for the first time and when
     * attachToContext is called.
     */
    EGLDisplay mEglDisplay;

    /**
     * mEglContext is the OpenGL ES context with which this EGLConsumer is
     * currently associated.  It is initialized to EGL_NO_CONTEXT and gets set
     * to the current GL context when updateTexImage is called for the first
     * time and when attachToContext is called.
     */
    EGLContext mEglContext;

    /**
     * mEGLSlots stores the buffers that have been allocated by the BufferQueue
     * for each buffer slot.  It is initialized to null pointers, and gets
     * filled in with the result of BufferQueue::acquire when the
     * client dequeues a buffer from a
     * slot that has not yet been used. The buffer allocated to a slot will also
     * be replaced if the requested buffer usage or geometry differs from that
     * of the buffer allocated to a slot.
     */
    EglSlot mEglSlots[BufferQueueDefs::NUM_BUFFER_SLOTS];

    /**
     * protects static initialization
     */
    static Mutex sStaticInitLock;

    /**
     * mReleasedTexImageBuffer is a dummy buffer used when in single buffer
     * mode and releaseTexImage() has been called
     */
    static sp<GraphicBuffer> sReleasedTexImageBuffer;
    sp<EglImage> mReleasedTexImage;
};

}  // namespace android
