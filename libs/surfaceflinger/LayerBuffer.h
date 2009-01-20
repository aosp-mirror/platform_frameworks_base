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

#include <utils/IMemory.h>
#include <private/ui/LayerState.h>
#include <GLES/eglnatives.h>

#include "LayerBase.h"
#include "LayerBitmap.h"

namespace android {

// ---------------------------------------------------------------------------

class MemoryDealer;
class Region;
class OverlayRef;

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
    protected:
        LayerBuffer& mLayer;
    };


public:
    static const uint32_t typeInfo;
    static const char* const typeID;
    virtual char const* getTypeID() const { return typeID; }
    virtual uint32_t getTypeInfo() const { return typeInfo; }

            LayerBuffer(SurfaceFlinger* flinger, DisplayID display,
                        Client* client, int32_t i);
        virtual ~LayerBuffer();

    virtual bool needsBlending() const;

    virtual sp<LayerBaseClient::Surface> getSurface() const;
    virtual void onDraw(const Region& clip) const;
    virtual uint32_t doTransaction(uint32_t flags);
    virtual void unlockPageFlip(const Transform& planeTransform, Region& outDirtyRegion);

    status_t registerBuffers(int w, int h, int hstride, int vstride,
            PixelFormat format, const sp<IMemoryHeap>& heap);
    void postBuffer(ssize_t offset);
    void unregisterBuffers();
    sp<OverlayRef> createOverlay(uint32_t w, uint32_t h, int32_t format);
    
    sp<Source> getSource() const;
    sp<Source> clearSource();
    void setNeedsBlending(bool blending);
    const Rect& getTransformedBounds() const {
        return mTransformedBounds;
    }

private:
    struct NativeBuffer {
        copybit_image_t   img;
        copybit_rect_t    crop;
    };

    class Buffer : public LightRefBase<Buffer> {
    public:
        Buffer(const sp<IMemoryHeap>& heap, ssize_t offset,
                int w, int h, int hs, int vs, int f);
        inline status_t getStatus() const {
            return mHeap!=0 ? NO_ERROR : NO_INIT;
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
        sp<IMemoryHeap>    mHeap;
        NativeBuffer       mNativeBuffer;
    };

    class BufferSource : public Source {
    public:
        BufferSource(LayerBuffer& layer,
                int w, int h, int hstride, int vstride,
                PixelFormat format, const sp<IMemoryHeap>& heap);
        virtual ~BufferSource();

        status_t getStatus() const { return mStatus; }
        sp<Buffer> getBuffer() const;
        void setBuffer(const sp<Buffer>& buffer);

        virtual void onDraw(const Region& clip) const;
        virtual void postBuffer(ssize_t offset);
        virtual void unregisterBuffers();
    private:
        mutable Mutex   mLock;
        sp<IMemoryHeap> mHeap;
        sp<Buffer>      mBuffer;
        status_t        mStatus;
        int             mWidth;
        int             mHeight;
        int             mHStride;
        int             mVStride;
        int             mFormat;
        mutable sp<MemoryDealer> mTemporaryDealer;
        mutable LayerBitmap mTempBitmap;
        mutable GLuint  mTextureName;
    };
    
    class OverlaySource : public Source {
    public:
        OverlaySource(LayerBuffer& layer,
                sp<OverlayRef>* overlayRef, 
                uint32_t w, uint32_t h, int32_t format);
        virtual ~OverlaySource();
        virtual void onTransaction(uint32_t flags);
        virtual void onVisibilityResolved(const Transform& planeTransform);
    private:
        void serverDestroy(); 
        void destroyOverlay(); 
        class OverlayChannel : public BnOverlay {
            mutable Mutex mLock;
            sp<OverlaySource> mSource;
            virtual void destroy() {
                sp<OverlaySource> source;
                { // scope for the lock;
                    Mutex::Autolock _l(mLock);
                    source = mSource;
                    mSource.clear();
                }
                if (source != 0) {
                    source->serverDestroy();
                }
            }
        public:
            OverlayChannel(const sp<OverlaySource>& source)
                : mSource(source) {
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
        mutable Mutex mLock;
    };


    class SurfaceBuffer : public LayerBaseClient::Surface
    {
    public:
                SurfaceBuffer(SurfaceID id, LayerBuffer* owner);
        virtual ~SurfaceBuffer();
        virtual status_t registerBuffers(int w, int h, int hstride, int vstride,
                PixelFormat format, const sp<IMemoryHeap>& heap);
        virtual void postBuffer(ssize_t offset);
        virtual void unregisterBuffers();
        virtual sp<OverlayRef> createOverlay(
                uint32_t w, uint32_t h, int32_t format);
       void disown();
    private:
        LayerBuffer* getOwner() const {
            Mutex::Autolock _l(mLock);
            return mOwner;
        }
        mutable Mutex   mLock;
        LayerBuffer*    mOwner;
    };

    friend class SurfaceFlinger;
    sp<SurfaceBuffer>   getClientSurface() const;

    mutable Mutex   mLock;
    sp<Source>      mSource;

    bool            mInvalidate;
    bool            mNeedsBlending;
    mutable wp<SurfaceBuffer> mClientSurface;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_LAYER_BUFFER_H
