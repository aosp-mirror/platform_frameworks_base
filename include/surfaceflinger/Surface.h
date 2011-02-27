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

#ifndef ANDROID_SF_SURFACE_H
#define ANDROID_SF_SURFACE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/KeyedVector.h>
#include <utils/RefBase.h>
#include <utils/threads.h>

#include <ui/PixelFormat.h>
#include <ui/Region.h>
#include <ui/egl/android_natives.h>

#include <surfaceflinger/ISurface.h>
#include <surfaceflinger/ISurfaceComposerClient.h>

#define ANDROID_VIEW_SURFACE_JNI_ID    "mNativeSurface"

namespace android {

// ---------------------------------------------------------------------------

class GraphicBuffer;
class GraphicBufferMapper;
class IOMX;
class Rect;
class Surface;
class SurfaceComposerClient;
class SharedClient;
class SharedBufferClient;
class SurfaceClient;

// ---------------------------------------------------------------------------

class SurfaceControl : public RefBase
{
public:
    static bool isValid(const sp<SurfaceControl>& surface) {
        return (surface != 0) && surface->isValid();
    }
    bool isValid() {
        return mToken>=0 && mClient!=0;
    }
    static bool isSameSurface(
            const sp<SurfaceControl>& lhs, const sp<SurfaceControl>& rhs);
        
    uint32_t    getFlags() const { return mFlags; }
    uint32_t    getIdentity() const { return mIdentity; }

    // release surface data from java
    void        clear();
    
    status_t    setLayer(int32_t layer);
    status_t    setPosition(int32_t x, int32_t y);
    status_t    setSize(uint32_t w, uint32_t h);
    status_t    hide();
    status_t    show(int32_t layer = -1);
    status_t    freeze();
    status_t    unfreeze();
    status_t    setFlags(uint32_t flags, uint32_t mask);
    status_t    setTransparentRegionHint(const Region& transparent);
    status_t    setAlpha(float alpha=1.0f);
    status_t    setMatrix(float dsdx, float dtdx, float dsdy, float dtdy);
    status_t    setFreezeTint(uint32_t tint);

    static status_t writeSurfaceToParcel(
            const sp<SurfaceControl>& control, Parcel* parcel);

    sp<Surface> getSurface() const;

private:
    // can't be copied
    SurfaceControl& operator = (SurfaceControl& rhs);
    SurfaceControl(const SurfaceControl& rhs);

    
    friend class SurfaceComposerClient;

    // camera and camcorder need access to the ISurface binder interface for preview
    friend class CameraService;
    friend class MediaRecorder;
    // mediaplayer needs access to ISurface for display
    friend class MediaPlayer;
    // for testing
    friend class Test;
    // videoEditor preview classes
    friend class VideoEditorPreviewController;

    const sp<ISurface>& getISurface() const { return mSurface; }
    

    friend class Surface;

    SurfaceControl(
            const sp<SurfaceComposerClient>& client,
            const sp<ISurface>& surface,
            const ISurfaceComposerClient::surface_data_t& data,
            uint32_t w, uint32_t h, PixelFormat format, uint32_t flags);

    ~SurfaceControl();

    status_t validate() const;
    void destroy();
    
    sp<SurfaceComposerClient>   mClient;
    sp<ISurface>                mSurface;
    SurfaceID                   mToken;
    uint32_t                    mIdentity;
    uint32_t                    mWidth;
    uint32_t                    mHeight;
    PixelFormat                 mFormat;
    uint32_t                    mFlags;
    mutable Mutex               mLock;
    
    mutable sp<Surface>         mSurfaceData;
};
    
// ---------------------------------------------------------------------------

class Surface 
    : public EGLNativeBase<ANativeWindow, Surface, RefBase>
{
public:
    struct SurfaceInfo {
        uint32_t    w;
        uint32_t    h;
        uint32_t    s;
        uint32_t    usage;
        PixelFormat format;
        void*       bits;
        uint32_t    reserved[2];
    };

    static status_t writeToParcel(
            const sp<Surface>& control, Parcel* parcel);

    static sp<Surface> readFromParcel(const Parcel& data);

    static bool isValid(const sp<Surface>& surface) {
        return (surface != 0) && surface->isValid();
    }

    bool        isValid();
    uint32_t    getFlags() const    { return mFlags; }
    uint32_t    getIdentity() const { return mIdentity; }

    // the lock/unlock APIs must be used from the same thread
    status_t    lock(SurfaceInfo* info, bool blocking = true);
    status_t    lock(SurfaceInfo* info, Region* dirty, bool blocking = true);
    status_t    unlockAndPost();

    // setSwapRectangle() is intended to be used by GL ES clients
    void        setSwapRectangle(const Rect& r);


private:
    /*
     * Android frameworks friends
     * (eventually this should go away and be replaced by proper APIs)
     */
    // camera and camcorder need access to the ISurface binder interface for preview
    friend class CameraService;
    friend class MediaRecorder;
    // MediaPlayer needs access to ISurface for display
    friend class MediaPlayer;
    friend class IOMX;
    friend class SoftwareRenderer;
    // this is just to be able to write some unit tests
    friend class Test;
    // videoEditor preview classes
    friend class VideoEditorPreviewController;
    friend class PreviewRenderer;

private:
    friend class SurfaceComposerClient;
    friend class SurfaceControl;

    // can't be copied
    Surface& operator = (Surface& rhs);
    Surface(const Surface& rhs);

    Surface(const sp<SurfaceControl>& control);
    Surface(const Parcel& data, const sp<IBinder>& ref);
    ~Surface();


    /*
     *  ANativeWindow hooks
     */
    static int setSwapInterval(ANativeWindow* window, int interval);
    static int dequeueBuffer(ANativeWindow* window, android_native_buffer_t** buffer);
    static int cancelBuffer(ANativeWindow* window, android_native_buffer_t* buffer);
    static int lockBuffer(ANativeWindow* window, android_native_buffer_t* buffer);
    static int queueBuffer(ANativeWindow* window, android_native_buffer_t* buffer);
    static int query(ANativeWindow* window, int what, int* value);
    static int perform(ANativeWindow* window, int operation, ...);

    int dequeueBuffer(android_native_buffer_t** buffer);
    int lockBuffer(android_native_buffer_t* buffer);
    int queueBuffer(android_native_buffer_t* buffer);
    int cancelBuffer(android_native_buffer_t* buffer);
    int query(int what, int* value);
    int perform(int operation, va_list args);

    void dispatch_setUsage(va_list args);
    int  dispatch_connect(va_list args);
    int  dispatch_disconnect(va_list args);
    int  dispatch_crop(va_list args);
    int  dispatch_set_buffer_count(va_list args);
    int  dispatch_set_buffers_geometry(va_list args);
    int  dispatch_set_buffers_transform(va_list args);
    
    void setUsage(uint32_t reqUsage);
    int  connect(int api);
    int  disconnect(int api);
    int  crop(Rect const* rect);
    int  setBufferCount(int bufferCount);
    int  setBuffersGeometry(int w, int h, int format);
    int  setBuffersTransform(int transform);

    /*
     *  private stuff...
     */
    void init();
    status_t validate(bool inCancelBuffer = false) const;
    sp<ISurface> getISurface() const;

    // When the buffer pool is a fixed size we want to make sure SurfaceFlinger
    // won't stall clients, so we require an extra buffer.
    enum { MIN_UNDEQUEUED_BUFFERS = 2 };

    inline const GraphicBufferMapper& getBufferMapper() const { return mBufferMapper; }
    inline GraphicBufferMapper& getBufferMapper() { return mBufferMapper; }

    status_t getBufferLocked(int index,
            uint32_t w, uint32_t h, uint32_t format, uint32_t usage);
    int getBufferIndex(const sp<GraphicBuffer>& buffer) const;

    int getConnectedApi() const;
    
    bool needNewBuffer(int bufIdx,
            uint32_t *pWidth, uint32_t *pHeight,
            uint32_t *pFormat, uint32_t *pUsage) const;

    static void cleanCachedSurfacesLocked();

    class BufferInfo {
        uint32_t mWidth;
        uint32_t mHeight;
        uint32_t mFormat;
        uint32_t mUsage;
        mutable uint32_t mDirty;
        enum {
            GEOMETRY = 0x01
        };
    public:
        BufferInfo();
        void set(uint32_t w, uint32_t h, uint32_t format);
        void set(uint32_t usage);
        void get(uint32_t *pWidth, uint32_t *pHeight,
                uint32_t *pFormat, uint32_t *pUsage) const;
        bool validateBuffer(const sp<GraphicBuffer>& buffer) const;
    };

    // constants
    GraphicBufferMapper&        mBufferMapper;
    SurfaceClient&              mClient;
    SharedBufferClient*         mSharedBufferClient;
    status_t                    mInitCheck;
    sp<ISurface>                mSurface;
    uint32_t                    mIdentity;
    PixelFormat                 mFormat;
    uint32_t                    mFlags;

    // protected by mSurfaceLock
    Rect                        mSwapRectangle;
    int                         mConnected;
    Rect                        mNextBufferCrop;
    uint32_t                    mNextBufferTransform;
    BufferInfo                  mBufferInfo;
    
    // protected by mSurfaceLock. These are also used from lock/unlock
    // but in that case, they must be called form the same thread.
    mutable Region              mDirtyRegion;

    // must be used from the lock/unlock thread
    sp<GraphicBuffer>           mLockedBuffer;
    sp<GraphicBuffer>           mPostedBuffer;
    mutable Region              mOldDirtyRegion;
    bool                        mReserved;

    // only used from dequeueBuffer()
    Vector< sp<GraphicBuffer> > mBuffers;

    // query() must be called from dequeueBuffer() thread
    uint32_t                    mWidth;
    uint32_t                    mHeight;

    // Inherently thread-safe
    mutable Mutex               mSurfaceLock;
    mutable Mutex               mApiLock;

    // A cache of Surface objects that have been deserialized into this process.
    static Mutex sCachedSurfacesLock;
    static DefaultKeyedVector<wp<IBinder>, wp<Surface> > sCachedSurfaces;
};

}; // namespace android

#endif // ANDROID_SF_SURFACE_H
