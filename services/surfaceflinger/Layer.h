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

class FreezeLock;
class Client;
class GLExtensions;
class UserClient;

// ---------------------------------------------------------------------------

class Layer : public LayerBaseClient, private RefBase::Destroyer
{
public:
            Layer(SurfaceFlinger* flinger, DisplayID display,
                    const sp<Client>& client);

    virtual ~Layer();

    virtual const char* getTypeId() const { return "Layer"; }

    // the this layer's size and format
    status_t setBuffers(uint32_t w, uint32_t h, 
            PixelFormat format, uint32_t flags=0);

    // associate a UserClient to this Layer
    status_t setToken(const sp<UserClient>& uc, SharedClient* sc, int32_t idx);
    int32_t getToken() const;
    sp<UserClient> getClient() const;

    // Set this Layer's buffers size
    void setBufferSize(uint32_t w, uint32_t h);
    bool isFixedSize() const;

    // LayerBase interface
    virtual void drawForSreenShot() const;
    virtual void onDraw(const Region& clip) const;
    virtual uint32_t doTransaction(uint32_t transactionFlags);
    virtual void lockPageFlip(bool& recomputeVisibleRegions);
    virtual void unlockPageFlip(const Transform& planeTransform, Region& outDirtyRegion);
    virtual bool needsBlending() const      { return mNeedsBlending; }
    virtual bool needsDithering() const     { return mNeedsDithering; }
    virtual bool needsFiltering() const;
    virtual bool isSecure() const           { return mSecure; }
    virtual sp<Surface> createSurface() const;
    virtual void onRemoved();
    virtual bool setBypass(bool enable);

    void updateBuffersOrientation();

    inline sp<GraphicBuffer> getBypassBuffer() const {
        return mBufferManager.getActiveBuffer(); }

    // only for debugging
    inline sp<GraphicBuffer> getBuffer(int i) const {
        return mBufferManager.getBuffer(i); }
    // only for debugging
    inline const sp<FreezeLock>&  getFreezeLock() const {
        return mFreezeLock; }

protected:
    virtual void destroy(RefBase const* base);
    virtual void dump(String8& result, char* scratch, size_t size) const;

private:
    void reloadTexture(const Region& dirty);
    uint32_t getEffectiveUsage(uint32_t usage) const;
    sp<GraphicBuffer> requestBuffer(int bufferIdx,
            uint32_t w, uint32_t h, uint32_t format, uint32_t usage);
    status_t setBufferCount(int bufferCount);

    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------

    class ClientRef {
        ClientRef(const ClientRef& rhs);
        ClientRef& operator = (const ClientRef& rhs);
        mutable Mutex mLock;
        // binder thread, page-flip thread
        sp<SharedBufferServer> mControlBlock;
        wp<UserClient> mUserClient;
        int32_t mToken;
    public:
        ClientRef();
        ~ClientRef();
        int32_t getToken() const;
        sp<UserClient> getClient() const;
        status_t setToken(const sp<UserClient>& uc,
                const sp<SharedBufferServer>& sharedClient, int32_t token);
        sp<UserClient> getUserClientUnsafe() const;
        class Access {
            Access(const Access& rhs);
            Access& operator = (const Access& rhs);
            sp<UserClient> mUserClientStrongRef;
            sp<SharedBufferServer> mControlBlock;
        public:
            Access(const ClientRef& ref);
            ~Access();
            inline SharedBufferServer* get() const { return mControlBlock.get(); }
        };
        friend class Access;
    };

    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------

    // thread-safe
    ClientRef mUserClientRef;

    // constants
    sp<Surface> mSurface;
    PixelFormat mFormat;
    const GLExtensions& mGLExtensions;
    bool mNeedsBlending;
    bool mNeedsDithering;

    // page-flip thread (currently main thread)
    bool mSecure;
    Region mPostedDirtyRegion;

    // page-flip thread and transaction thread (currently main thread)
    sp<FreezeLock>  mFreezeLock;

    // see threading usage in declaration
    TextureManager mTextureManager;
    BufferManager mBufferManager;

    // binder thread, transaction thread
    mutable Mutex mLock;
    uint32_t mWidth;
    uint32_t mHeight;
    uint32_t mReqWidth;
    uint32_t mReqHeight;
    uint32_t mReqFormat;
    bool mNeedsScaling;
    bool mFixedSize;
    bool mBypassState;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_LAYER_H
