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

#ifndef ANDROID_UI_SURFACE_H
#define ANDROID_UI_SURFACE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/RefBase.h>
#include <utils/threads.h>

#include <ui/ISurface.h>
#include <ui/PixelFormat.h>
#include <ui/Region.h>
#include <ui/ISurfaceFlingerClient.h>

#include <ui/egl/android_natives.h>

namespace android {

// ---------------------------------------------------------------------------

class BufferMapper;
class IOMX;
class Rect;
class Surface;
class SurfaceComposerClient;
class SharedClient;
class SharedBufferClient;

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
        
    SurfaceID   ID() const      { return mToken; }
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
    friend class Camera;
    friend class MediaRecorder;
    // mediaplayer needs access to ISurface for display
    friend class MediaPlayer;
    // for testing
    friend class Test;
    const sp<ISurface>& getISurface() const { return mSurface; }
    

    friend class Surface;

    SurfaceControl(
            const sp<SurfaceComposerClient>& client,
            const sp<ISurface>& surface,
            const ISurfaceFlingerClient::surface_data_t& data,
            uint32_t w, uint32_t h, PixelFormat format, uint32_t flags);

    ~SurfaceControl();

    status_t validate(SharedClient const* cblk) const;
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
    : public EGLNativeBase<android_native_window_t, Surface, RefBase>
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

    Surface(const Parcel& data);

    static bool isValid(const sp<Surface>& surface) {
        return (surface != 0) && surface->isValid();
    }

    static bool isSameSurface(
            const sp<Surface>& lhs, const sp<Surface>& rhs);

    bool        isValid();
    SurfaceID   ID() const          { return mToken; }
    uint32_t    getFlags() const    { return mFlags; }
    uint32_t    getIdentity() const { return mIdentity; }

    // the lock/unlock APIs must be used from the same thread
    status_t    lock(SurfaceInfo* info, bool blocking = true);
    status_t    lock(SurfaceInfo* info, Region* dirty, bool blocking = true);
    status_t    unlockAndPost();

    // setSwapRectangle() is intended to be used by GL ES clients
    void        setSwapRectangle(const Rect& r);

private:
    // can't be copied
    Surface& operator = (Surface& rhs);
    Surface(const Surface& rhs);

    Surface(const sp<SurfaceControl>& control);
    void init();
     ~Surface();
  
    friend class SurfaceComposerClient;
    friend class SurfaceControl;

    
    // camera and camcorder need access to the ISurface binder interface for preview
    friend class Camera;
    friend class MediaRecorder;
    // mediaplayer needs access to ISurface for display
    friend class MediaPlayer;
    friend class IOMX;
    // this is just to be able to write some unit tests
    friend class Test;

    sp<SurfaceComposerClient> getClient() const;
    sp<ISurface> getISurface() const;

    status_t getBufferLocked(int index, int usage);
   
           status_t validate(SharedClient const* cblk) const;

    inline const BufferMapper& getBufferMapper() const { return mBufferMapper; }
    inline BufferMapper& getBufferMapper() { return mBufferMapper; }
    
    static int setSwapInterval(android_native_window_t* window, int interval);
    static int dequeueBuffer(android_native_window_t* window, android_native_buffer_t** buffer);
    static int lockBuffer(android_native_window_t* window, android_native_buffer_t* buffer);
    static int queueBuffer(android_native_window_t* window, android_native_buffer_t* buffer);
    static int query(android_native_window_t* window, int what, int* value);
    static int perform(android_native_window_t* window, int operation, ...);

    int dequeueBuffer(android_native_buffer_t** buffer);
    int lockBuffer(android_native_buffer_t* buffer);
    int queueBuffer(android_native_buffer_t* buffer);
    int query(int what, int* value);
    int perform(int operation, va_list args);

    status_t dequeueBuffer(sp<SurfaceBuffer>* buffer);

    
    void setUsage(uint32_t reqUsage);
    bool getUsage(uint32_t* usage);
    
    // constants
    sp<SurfaceComposerClient>   mClient;
    sp<ISurface>                mSurface;
    SurfaceID                   mToken;
    uint32_t                    mIdentity;
    PixelFormat                 mFormat;
    uint32_t                    mFlags;
    BufferMapper&               mBufferMapper;
    SharedBufferClient*         mSharedBufferClient;

    // protected by mSurfaceLock
    Rect                        mSwapRectangle;
    uint32_t                    mUsage;
    int32_t                     mUsageChanged;
    
    // protected by mSurfaceLock. These are also used from lock/unlock
    // but in that case, they must be called form the same thread.
    sp<SurfaceBuffer>           mBuffers[2];
    mutable Region              mDirtyRegion;

    // must be used from the lock/unlock thread
    sp<SurfaceBuffer>           mLockedBuffer;
    sp<SurfaceBuffer>           mPostedBuffer;
    mutable Region              mOldDirtyRegion;
    bool                        mNeedFullUpdate;

    // query() must be called from dequeueBuffer() thread
    uint32_t                    mWidth;
    uint32_t                    mHeight;

    // Inherently thread-safe
    mutable Mutex               mSurfaceLock;
    mutable Mutex               mApiLock;
};

}; // namespace android

#endif // ANDROID_UI_SURFACE_H

