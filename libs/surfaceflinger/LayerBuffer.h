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
class Overlay;

class LayerBuffer : public LayerBaseClient
{
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
    virtual void unlockPageFlip(const Transform& planeTransform, Region& outDirtyRegion);

    status_t registerBuffers(int w, int h, int hstride, int vstride,
            PixelFormat format, const sp<IMemoryHeap>& heap);
    void postBuffer(ssize_t offset);
    void unregisterBuffers();
    sp<Overlay> createOverlay(uint32_t w, uint32_t h, int32_t format);
    void invalidate();
    void invalidateLocked();

private:

    struct NativeBuffer
    {
        copybit_image_t   img;
        copybit_rect_t    crop;
    };

    class Buffer
    {
    public:
        Buffer(const sp<IMemoryHeap>& heap, ssize_t offset,
                int w, int h, int hs, int vs, int f);
        inline void incStrong(void*) const {
            android_atomic_inc(&mCount);
        }
        inline void decStrong(void*) const {
            int32_t c = android_atomic_dec(&mCount);
            //LOGE_IF(c<1, "Buffer::decStrong() called too many times");
            if (c == 1) {
                 delete this;
             }
        }
        inline status_t getStatus() const {
            return mHeap!=0 ? NO_ERROR : NO_INIT;
        }
        inline const NativeBuffer& getBuffer() const {
            return mNativeBuffer;
        }
    protected:
        Buffer& operator = (const Buffer& rhs);
        Buffer(const Buffer& rhs);
        ~Buffer();
        mutable volatile int32_t mCount;
    private:
        sp<IMemoryHeap>    mHeap;
        NativeBuffer       mNativeBuffer;
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
        virtual sp<Overlay> createOverlay(
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
    sp<Buffer> getBuffer() const;
    void       setBuffer(const sp<Buffer>& buffer);
    sp<SurfaceBuffer>   getClientSurface() const;

    mutable Mutex   mLock;
    sp<IMemoryHeap> mHeap;
    sp<Buffer>      mBuffer;
    int             mWidth;
    int             mHeight;
    int             mHStride;
    int             mVStride;
    int             mFormat;
    mutable GLuint  mTextureName;
    bool            mInvalidate;
    bool            mNeedsBlending;
    mutable wp<SurfaceBuffer> mClientSurface;
    mutable sp<MemoryDealer> mTemporaryDealer;
    mutable LayerBitmap mTempBitmap;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_LAYER_BUFFER_H
