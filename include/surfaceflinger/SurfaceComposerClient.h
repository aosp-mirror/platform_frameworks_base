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

#ifndef ANDROID_SF_SURFACE_COMPOSER_CLIENT_H
#define ANDROID_SF_SURFACE_COMPOSER_CLIENT_H

#include <stdint.h>
#include <sys/types.h>

#include <binder/IBinder.h>

#include <utils/RefBase.h>
#include <utils/Singleton.h>
#include <utils/SortedVector.h>
#include <utils/threads.h>

#include <ui/PixelFormat.h>
#include <ui/Region.h>

#include <surfaceflinger/Surface.h>

namespace android {

// ---------------------------------------------------------------------------

class DisplayInfo;
class Composer;
class IMemoryHeap;
class ISurfaceComposer;
class Region;
class surface_flinger_cblk_t;
struct layer_state_t;

// ---------------------------------------------------------------------------

class ComposerService : public Singleton<ComposerService>
{
    // these are constants
    sp<ISurfaceComposer> mComposerService;
    sp<IMemoryHeap> mServerCblkMemory;
    surface_flinger_cblk_t volatile* mServerCblk;
    ComposerService();
    friend class Singleton<ComposerService>;
public:
    static sp<ISurfaceComposer> getComposerService();
    static surface_flinger_cblk_t const volatile * getControlBlock();
};

// ---------------------------------------------------------------------------

class Composer;

class SurfaceComposerClient : public RefBase
{
    friend class Composer;
public:    
                SurfaceComposerClient();
    virtual     ~SurfaceComposerClient();

    // Always make sure we could initialize
    status_t    initCheck() const;

    // Return the connection of this client
    sp<IBinder> connection() const;
    
    // Forcibly remove connection before all references have gone away.
    void        dispose();

    // ------------------------------------------------------------------------
    // surface creation / destruction

    //! Create a surface
    sp<SurfaceControl> createSurface(
            const String8& name,// name of the surface
            DisplayID display,  // Display to create this surface on
            uint32_t w,         // width in pixel
            uint32_t h,         // height in pixel
            PixelFormat format, // pixel-format desired
            uint32_t flags = 0  // usage flags
    );

    sp<SurfaceControl> createSurface(
            DisplayID display,  // Display to create this surface on
            uint32_t w,         // width in pixel
            uint32_t h,         // height in pixel
            PixelFormat format, // pixel-format desired
            uint32_t flags = 0  // usage flags
    );


    // ------------------------------------------------------------------------
    // Composer parameters
    // All composer parameters must be changed within a transaction
    // several surfaces can be updated in one transaction, all changes are
    // committed at once when the transaction is closed.
    // closeGlobalTransaction() usually requires an IPC with the server.

    //! Open a composer transaction on all active SurfaceComposerClients.
    static void openGlobalTransaction();
        
    //! Close a composer transaction on all active SurfaceComposerClients.
    static void closeGlobalTransaction(bool synchronous = false);
    
    //! Freeze the specified display but not transactions.
    static status_t freezeDisplay(DisplayID dpy, uint32_t flags = 0);
        
    //! Resume updates on the specified display.
    static status_t unfreezeDisplay(DisplayID dpy, uint32_t flags = 0);

    //! Set the orientation of the given display
    static int setOrientation(DisplayID dpy, int orientation, uint32_t flags);

    // Query the number of displays
    static ssize_t getNumberOfDisplays();

    // Get information about a display
    static status_t getDisplayInfo(DisplayID dpy, DisplayInfo* info);
    static ssize_t getDisplayWidth(DisplayID dpy);
    static ssize_t getDisplayHeight(DisplayID dpy);
    static ssize_t getDisplayOrientation(DisplayID dpy);

    status_t linkToComposerDeath(const sp<IBinder::DeathRecipient>& recipient,
            void* cookie = NULL, uint32_t flags = 0);

    status_t    hide(SurfaceID id);
    status_t    show(SurfaceID id, int32_t layer = -1);
    status_t    freeze(SurfaceID id);
    status_t    unfreeze(SurfaceID id);
    status_t    setFlags(SurfaceID id, uint32_t flags, uint32_t mask);
    status_t    setTransparentRegionHint(SurfaceID id, const Region& transparent);
    status_t    setLayer(SurfaceID id, int32_t layer);
    status_t    setAlpha(SurfaceID id, float alpha=1.0f);
    status_t    setFreezeTint(SurfaceID id, uint32_t tint);
    status_t    setMatrix(SurfaceID id, float dsdx, float dtdx, float dsdy, float dtdy);
    status_t    setPosition(SurfaceID id, float x, float y);
    status_t    setSize(SurfaceID id, uint32_t w, uint32_t h);
    status_t    destroySurface(SurfaceID sid);

private:
    virtual void onFirstRef();
    Composer& getComposer();

    mutable     Mutex                       mLock;
                status_t                    mStatus;
                sp<ISurfaceComposerClient>  mClient;
                Composer&                   mComposer;
};

// ---------------------------------------------------------------------------

class ScreenshotClient
{
    sp<IMemoryHeap> mHeap;
    uint32_t mWidth;
    uint32_t mHeight;
    PixelFormat mFormat;
public:
    ScreenshotClient();

    // frees the previous screenshot and capture a new one
    status_t update();
    status_t update(uint32_t reqWidth, uint32_t reqHeight);
    status_t update(uint32_t reqWidth, uint32_t reqHeight,
            uint32_t minLayerZ, uint32_t maxLayerZ);

    // release memory occupied by the screenshot
    void release();

    // pixels are valid until this object is freed or
    // release() or update() is called
    void const* getPixels() const;

    uint32_t getWidth() const;
    uint32_t getHeight() const;
    PixelFormat getFormat() const;
    uint32_t getStride() const;
    // size of allocated memory in bytes
    size_t getSize() const;
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_SF_SURFACE_COMPOSER_CLIENT_H
