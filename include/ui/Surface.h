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

namespace android {

// ---------------------------------------------------------------------------

class Rect;
class SurfaceComposerClient;

class Surface : public RefBase
{

public:
    struct SurfaceInfo {
        uint32_t    w;
        uint32_t    h;
        uint32_t    bpr;
        PixelFormat format;
        void*       bits;
        void*       base;
        uint32_t    reserved[2];
    };

    bool        isValid() const { return this && mToken>=0 && mClient!=0; }
    SurfaceID   ID() const      { return mToken; }

    status_t    lock(SurfaceInfo* info, bool blocking = true);
    status_t    lock(SurfaceInfo* info, Region* dirty, bool blocking = true);
    status_t    unlockAndPost();
    status_t    unlock();

    void*       heapBase(int i) const;
    uint32_t    getFlags() const { return mFlags; }

    // setSwapRectangle() is mainly used by EGL
    void        setSwapRectangle(const Rect& r);
    const Rect& swapRectangle() const;
    status_t    nextBuffer(SurfaceInfo* info);

    sp<Surface>         dup() const;
    static sp<Surface>  readFromParcel(Parcel* parcel);
    static status_t     writeToParcel(const sp<Surface>& surface, Parcel* parcel);
    static bool         isSameSurface(const sp<Surface>& lhs, const sp<Surface>& rhs);

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

    uint32_t    getIdentity() const { return mIdentity; }
private:
    friend class SurfaceComposerClient;

    // camera and camcorder need access to the ISurface binder interface for preview
    friend class Camera;
    friend class MediaRecorder;
    // mediaplayer needs access to ISurface for display
    friend class MediaPlayer;
    const sp<ISurface>& getISurface() const { return mSurface; }

    // can't be copied
    Surface& operator = (Surface& rhs);
    Surface(const Surface& rhs);

    Surface(const sp<SurfaceComposerClient>& client,
            const sp<ISurface>& surface,
            const ISurfaceFlingerClient::surface_data_t& data,
            uint32_t w, uint32_t h, PixelFormat format, uint32_t flags,
            bool owner = true);

    Surface(Surface const* rhs);

    ~Surface();

    Region dirtyRegion() const;
    void setDirtyRegion(const Region& region) const;

    // this locks protects calls to lockSurface() / unlockSurface()
    // and is called by SurfaceComposerClient.
    Mutex& getLock() const { return mSurfaceLock; }

    sp<SurfaceComposerClient>   mClient;
    sp<ISurface>                mSurface;
    sp<IMemoryHeap>             mHeap[2];
    SurfaceID                   mToken;
    uint32_t                    mIdentity;
    PixelFormat                 mFormat;
    uint32_t                    mFlags;
    const bool                  mOwner;
    mutable void*               mSurfaceHeapBase[2];
    mutable Region              mDirtyRegion;
    mutable Rect                mSwapRectangle;
    mutable uint8_t             mBackbufferIndex;
    mutable Mutex               mSurfaceLock;
};

}; // namespace android

#endif // ANDROID_UI_SURFACE_H

