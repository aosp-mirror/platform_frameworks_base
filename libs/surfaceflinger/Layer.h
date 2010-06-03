/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_LAYER_H
#define ANDROID_LAYER_H

#include <stdint.h>
#include <sys/types.h>

#include <ui/GraphicBuffer.h>
#include <ui/PixelFormat.h>
#include <pixelflinger/pixelflinger.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include "LayerBase.h"
#include "Transform.h"
#include "TextureManager.h"

namespace android {

// ---------------------------------------------------------------------------

class Client;
class FreezeLock;

// ---------------------------------------------------------------------------

class Layer : public LayerBaseClient
{
public:
    // lcblk is (almost) only accessed from the main SF thread, in the places
    // where it's not, a reference to Client must be held
    SharedBufferServer*     lcblk;

                 Layer(SurfaceFlinger* flinger, DisplayID display,
                         const sp<Client>& client);

        virtual ~Layer();

    status_t setBuffers(uint32_t w, uint32_t h, 
            PixelFormat format, uint32_t flags=0);

    void setBufferSize(uint32_t w, uint32_t h);
    bool isFixedSize() const;

    virtual void onDraw(const Region& clip) const;
    virtual uint32_t doTransaction(uint32_t transactionFlags);
    virtual void lockPageFlip(bool& recomputeVisibleRegions);
    virtual void unlockPageFlip(const Transform& planeTransform, Region& outDirtyRegion);
    virtual void finishPageFlip();
    virtual bool needsBlending() const      { return mNeedsBlending; }
    virtual bool needsDithering() const     { return mNeedsDithering; }
    virtual bool needsFiltering() const;
    virtual bool isSecure() const           { return mSecure; }
    virtual sp<Surface> createSurface() const;
    virtual status_t ditch();
    virtual void onRemoved();
    
    // only for debugging
    inline sp<GraphicBuffer> getBuffer(int i) const { return mBufferManager.getBuffer(i); }
    // only for debugging
    inline const sp<FreezeLock>&  getFreezeLock() const { return mFreezeLock; }
    // only for debugging
    inline PixelFormat pixelFormat() const { return mFormat; }

    virtual const char* getTypeId() const { return "Layer"; }

protected:
    virtual void dump(String8& result, char* scratch, size_t size) const;

private:
    void reloadTexture(const Region& dirty);

    uint32_t getEffectiveUsage(uint32_t usage) const;

    sp<GraphicBuffer> requestBuffer(int bufferIdx,
            uint32_t w, uint32_t h, uint32_t format, uint32_t usage);
    status_t setBufferCount(int bufferCount);

    class SurfaceLayer : public LayerBaseClient::Surface {
    public:
        SurfaceLayer(const sp<SurfaceFlinger>& flinger, const sp<Layer>& owner);
        ~SurfaceLayer();
    private:
        virtual sp<GraphicBuffer> requestBuffer(int bufferIdx,
                uint32_t w, uint32_t h, uint32_t format, uint32_t usage);
        virtual status_t setBufferCount(int bufferCount);
        sp<Layer> getOwner() const {
            return static_cast<Layer*>(Surface::getOwner().get());
        }
    };
    friend class SurfaceLayer;
    
    sp<Surface>             mSurface;

            bool            mSecure;
            int32_t         mFrontBufferIndex;
            bool            mNeedsBlending;
            bool            mNeedsDithering;
            Region          mPostedDirtyRegion;
            sp<FreezeLock>  mFreezeLock;
            PixelFormat     mFormat;

            class BufferManager {
                static const size_t NUM_BUFFERS = 2;
                struct BufferData {
                    sp<GraphicBuffer>   buffer;
                    Image               texture;
                };
                // this lock protect mBufferData[].buffer but since there
                // is very little contention, we have only one like for
                // the whole array, we also use it to protect mNumBuffers.
                mutable Mutex mLock;
                BufferData          mBufferData[SharedBufferStack::NUM_BUFFER_MAX];
                size_t              mNumBuffers;
                Texture             mFailoverTexture;
                TextureManager&     mTextureManager;
                ssize_t             mActiveBuffer;
                bool                mFailover;
                static status_t destroyTexture(Image* tex, EGLDisplay dpy);

            public:
                static size_t getDefaultBufferCount() { return NUM_BUFFERS; }
                BufferManager(TextureManager& tm);
                ~BufferManager();

                // detach/attach buffer from/to given index
                sp<GraphicBuffer> detachBuffer(size_t index);
                status_t attachBuffer(size_t index, const sp<GraphicBuffer>& buffer);

                // resize the number of active buffers
                status_t resize(size_t size);

                // ----------------------------------------------
                // must be called from GL thread

                // set/get active buffer index
                status_t setActiveBufferIndex(size_t index);
                size_t getActiveBufferIndex() const;

                // return the active buffer
                sp<GraphicBuffer> getActiveBuffer() const;

                // return the active texture (or fail-over)
                Texture getActiveTexture() const;

                // frees resources associated with all buffers
                status_t destroy(EGLDisplay dpy);

                // load bitmap data into the active buffer
                status_t loadTexture(const Region& dirty, const GGLSurface& t);

                // make active buffer an EGLImage if needed
                status_t initEglImage(EGLDisplay dpy,
                        const sp<GraphicBuffer>& buffer);

                // ----------------------------------------------
                // only for debugging
                sp<GraphicBuffer> getBuffer(size_t index) const;
            };

            TextureManager mTextureManager;
            BufferManager mBufferManager;

            // this lock protects mWidth and mHeight which are accessed from
            // the main thread and requestBuffer's binder transaction thread.
            mutable Mutex mLock;
            uint32_t    mWidth;
            uint32_t    mHeight;
            uint32_t    mReqWidth;
            uint32_t    mReqHeight;
            uint32_t    mReqFormat;
            bool        mFixedSize;

    // TODO: get rid of this
private:
    virtual void setToken(int32_t token);
    virtual int32_t getToken() const { return mToken; }
    int32_t mToken;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_LAYER_H
