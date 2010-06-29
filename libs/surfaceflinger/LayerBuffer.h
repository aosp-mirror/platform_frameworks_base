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

#ifndef ANDROID_LAYER_BUFFER_H
#define ANDROID_LAYER_BUFFER_H

#include <stdint.h>
#include <sys/types.h>

#include "LayerBase.h"
#include "TextureManager.h"

struct copybit_device_t;

namespace android {

// ---------------------------------------------------------------------------

class Buffer;
class Region;
class OverlayRef;

// ---------------------------------------------------------------------------

class LayerBuffer : public LayerBaseClient
{
    class Source : public LightRefBase<Source> {
    public:
        Source(LayerBuffer& layer);
        virtual ~Source();
        virtual void onDraw(const Region& clip) const;
        virtual void onTransaction(uint32_t flags);
        virtual void onVisibilityResolved(const Transform& planeTransform);
        virtual void postBuffer(ssize_t offset);
        virtual void unregisterBuffers();
        virtual void destroy() { }
        SurfaceFlinger* getFlinger() const { return mLayer.mFlinger.get(); }
    protected:
        LayerBuffer& mLayer;
    };

public:
            LayerBuffer(SurfaceFlinger* flinger, DisplayID display,
                    const sp<Client>& client);
        virtual ~LayerBuffer();

    virtual void onFirstRef();
    virtual bool needsBlending() const;
    virtual const char* getTypeId() const { return "LayerBuffer"; }

    virtual sp<LayerBaseClient::Surface> createSurface() const;
    virtual status_t ditch();
    virtual void onDraw(const Region& clip) const;
    virtual uint32_t doTransaction(uint32_t flags);
    virtual void unlockPageFlip(const Transform& planeTransform, Region& outDirtyRegion);

    status_t registerBuffers(const ISurface::BufferHeap& buffers);
    void postBuffer(ssize_t offset);
    void unregisterBuffers();
    sp<OverlayRef> createOverlay(uint32_t w, uint32_t h, int32_t format,
            int32_t orientation);
    
    sp<Source> getSource() const;
    sp<Source> clearSource();
    void setNeedsBlending(bool blending);
    Rect getTransformedBounds() const {
        return mTransformedBounds;
    }

    void serverDestroy();

private:
    struct NativeBuffer {
        copybit_image_t   img;
        copybit_rect_t    crop;
    };

    static gralloc_module_t const* sGrallocModule;
    static gralloc_module_t const* getGrallocModule() {
        return sGrallocModule;
    }

    class Buffer : public LightRefBase<Buffer> {
    public:
        Buffer(const ISurface::BufferHeap& buffers,
                ssize_t offset, size_t bufferSize);
        inline bool supportsCopybit() const {
            return mSupportsCopybit;
        }
        inline status_t getStatus() const {
            return mBufferHeap.heap!=0 ? NO_ERROR : NO_INIT;
        }
        inline const NativeBuffer& getBuffer() const {
            return mNativeBuffer;
        }
    protected:
        friend class LightRefBase<Buffer>;
        Buffer& operator = (const Buffer& rhs);
        Buffer(const Buffer& rhs);
        ~Buffer();
    private:
        ISurface::BufferHeap    mBufferHeap;
        NativeBuffer            mNativeBuffer;
        bool                    mSupportsCopybit;
    };

    class BufferSource : public Source {
    public:
        BufferSource(LayerBuffer& layer, const ISurface::BufferHeap& buffers);
        virtual ~BufferSource();

        status_t getStatus() const { return mStatus; }
        sp<Buffer> getBuffer() const;
        void setBuffer(const sp<Buffer>& buffer);

        virtual void onDraw(const Region& clip) const;
        virtual void postBuffer(ssize_t offset);
        virtual void unregisterBuffers();
        virtual void destroy() { }
    private:
        status_t initTempBuffer() const;
        void clearTempBufferImage() const;
        mutable Mutex                   mBufferSourceLock;
        sp<Buffer>                      mBuffer;
        status_t                        mStatus;
        ISurface::BufferHeap            mBufferHeap;
        size_t                          mBufferSize;
        mutable Texture                 mTexture;
        mutable NativeBuffer            mTempBuffer;
        mutable TextureManager          mTextureManager;
    };
    
    class OverlaySource : public Source {
    public:
        OverlaySource(LayerBuffer& layer,
                sp<OverlayRef>* overlayRef, 
                uint32_t w, uint32_t h, int32_t format, int32_t orientation);
        virtual ~OverlaySource();
        virtual void onDraw(const Region& clip) const;
        virtual void onTransaction(uint32_t flags);
        virtual void onVisibilityResolved(const Transform& planeTransform);
        virtual void destroy();
    private:

        class OverlayChannel : public BnOverlay {
            wp<LayerBuffer> mLayer;
            virtual void destroy() {
                sp<LayerBuffer> layer(mLayer.promote());
                if (layer != 0) {
                    layer->serverDestroy();
                }
            }
        public:
            OverlayChannel(const sp<LayerBuffer>& layer)
                : mLayer(layer) {
            }
        };
        
        friend class OverlayChannel;
        bool mVisibilityChanged;

        overlay_t* mOverlay;        
        overlay_handle_t mOverlayHandle;
        overlay_control_device_t* mOverlayDevice;
        uint32_t mWidth;
        uint32_t mHeight;
        int32_t mFormat;
        int32_t mWidthStride;
        int32_t mHeightStride;
        int32_t mOrientation;
        mutable Mutex mOverlaySourceLock;
        bool mInitialized;
    };


    class SurfaceLayerBuffer : public LayerBaseClient::Surface
    {
    public:
        SurfaceLayerBuffer(const sp<SurfaceFlinger>& flinger,
                        const sp<LayerBuffer>& owner);
        virtual ~SurfaceLayerBuffer();

        virtual status_t registerBuffers(const ISurface::BufferHeap& buffers);
        virtual void postBuffer(ssize_t offset);
        virtual void unregisterBuffers();
        
        virtual sp<OverlayRef> createOverlay(
                uint32_t w, uint32_t h, int32_t format, int32_t orientation);
    private:
        sp<LayerBuffer> getOwner() const {
            return static_cast<LayerBuffer*>(Surface::getOwner().get());
        }
    };

    mutable Mutex   mLock;
    sp<Source>      mSource;
    sp<Surface>     mSurface;
    bool            mInvalidate;
    bool            mNeedsBlending;
    copybit_device_t* mBlitEngine;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_LAYER_BUFFER_H
