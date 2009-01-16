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

#ifndef ANDROID_SURFACE_COMPOSER_CLIENT_H
#define ANDROID_SURFACE_COMPOSER_CLIENT_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/SortedVector.h>
#include <utils/KeyedVector.h>
#include <utils/RefBase.h>
#include <utils/threads.h>

#include <ui/PixelFormat.h>
#include <ui/ISurfaceComposer.h>
#include <ui/Region.h>
#include <ui/Surface.h>

namespace android {

// ---------------------------------------------------------------------------

class Region;
class SurfaceFlingerSynchro;
struct per_client_cblk_t;
struct layer_cblk_t;

class SurfaceComposerClient : virtual public RefBase
{
public:    
                SurfaceComposerClient();
    virtual     ~SurfaceComposerClient();

    // Always make sure we could initialize
    status_t    initCheck() const;

    // Return the connection of this client
    sp<IBinder> connection() const;
    
    // Retrieve a client for an existing connection.
    static sp<SurfaceComposerClient>
                clientForConnection(const sp<IBinder>& conn);

    // Forcibly remove connection before all references have gone away.
    void        dispose();

    // ------------------------------------------------------------------------
    // surface creation / destruction

    //! Create a surface
    sp<Surface>   createSurface(
            int pid,            //!< pid of the process the surfacec is for
            DisplayID display,  //!< Display to create this surface on
            uint32_t w,         //!< width in pixel
            uint32_t h,         //!< height in pixel
            PixelFormat format, //!< pixel-format desired
            uint32_t flags = 0  //!< usage flags
    );

    // ------------------------------------------------------------------------
    // Composer parameters
    // All composer parameters must be changed within a transaction
    // several surfaces can be updated in one transaction, all changes are
    // committed at once when the transaction is closed.
    // CloseTransaction() usually requires an IPC with the server.
    
    //! Open a composer transaction
    status_t    openTransaction();

    //! commit the transaction
    status_t    closeTransaction();

    //! Open a composer transaction on all active SurfaceComposerClients.
    static void openGlobalTransaction();
        
    //! Close a composer transaction on all active SurfaceComposerClients.
    static void closeGlobalTransaction();
    
    //! Freeze the specified display but not transactions.
    static status_t freezeDisplay(DisplayID dpy, uint32_t flags = 0);
        
    //! Resume updates on the specified display.
    static status_t unfreezeDisplay(DisplayID dpy, uint32_t flags = 0);

    //! Set the orientation of the given display
    static int setOrientation(DisplayID dpy, int orientation);

    // Query the number of displays
    static ssize_t getNumberOfDisplays();

    // Get information about a display
    static status_t getDisplayInfo(DisplayID dpy, DisplayInfo* info);
    static ssize_t getDisplayWidth(DisplayID dpy);
    static ssize_t getDisplayHeight(DisplayID dpy);
    static ssize_t getDisplayOrientation(DisplayID dpy);


private:
    friend class Surface;
    
    SurfaceComposerClient(const sp<ISurfaceComposer>& sm, 
            const sp<IBinder>& conn);

    status_t    hide(Surface* surface);
    status_t    show(Surface* surface, int32_t layer = -1);
    status_t    freeze(Surface* surface);
    status_t    unfreeze(Surface* surface);
    status_t    setFlags(Surface* surface, uint32_t flags, uint32_t mask);
    status_t    setTransparentRegionHint(Surface* surface, const Region& transparent);
    status_t    setLayer(Surface* surface, int32_t layer);
    status_t    setAlpha(Surface* surface, float alpha=1.0f);
    status_t    setFreezeTint(Surface* surface, uint32_t tint);
    status_t    setMatrix(Surface* surface, float dsdx, float dtdx, float dsdy, float dtdy);
    status_t    setPosition(Surface* surface, int32_t x, int32_t y);
    status_t    setSize(Surface* surface, uint32_t w, uint32_t h);
    
    //! Unlock the surface, and specify the dirty region if any
    status_t    unlockAndPostSurface(Surface* surface);
    status_t    unlockSurface(Surface* surface);

    status_t    lockSurface(Surface* surface,
                            Surface::SurfaceInfo* info,
                            Region* dirty,
                            bool blocking = true);

    status_t    nextBuffer(Surface* surface,
                            Surface::SurfaceInfo* info);

    status_t    destroySurface(SurfaceID sid);

    void        _init(const sp<ISurfaceComposer>& sm,
                    const sp<ISurfaceFlingerClient>& conn);
    void        _signal_server();
    static void _send_dirty_region(layer_cblk_t* lcblk, const Region& dirty);

    inline layer_state_t*   _get_state_l(const sp<Surface>& surface);
    layer_state_t*          _lockLayerState(const sp<Surface>& surface);
    inline void             _unlockLayerState();

    status_t validateSurface(
            per_client_cblk_t const* cblk, Surface const * surface);

    void pinHeap(const sp<IMemoryHeap>& heap);

    mutable     Mutex                               mLock;
                layer_state_t*                      mPrebuiltLayerState;
                SortedVector<layer_state_t>         mStates;
                int32_t                             mTransactionOpen;

                // these don't need to be protected because they never change
                // after assignment
                status_t                    mStatus;
                per_client_cblk_t*          mControl;
                sp<IMemory>                 mControlMemory;
                sp<ISurfaceFlingerClient>   mClient;
                sp<IMemoryHeap>             mSurfaceHeap;
                uint8_t*                    mSurfaceHeapBase;
                void*                       mGL;
                SurfaceFlingerSynchro*      mSignalServer;
};

}; // namespace android

#endif // ANDROID_SURFACE_COMPOSER_CLIENT_H

