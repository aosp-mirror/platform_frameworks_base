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

#ifndef ANDROID_SURFACE_FLINGER_H
#define ANDROID_SURFACE_FLINGER_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/SortedVector.h>
#include <utils/KeyedVector.h>
#include <utils/threads.h>
#include <utils/Atomic.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>

#include <binder/IMemory.h>
#include <binder/Permission.h>

#include <ui/PixelFormat.h>
#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/ISurfaceFlingerClient.h>

#include "Barrier.h"
#include "Layer.h"
#include "Tokenizer.h"

#include "MessageQueue.h"

struct copybit_device_t;
struct overlay_device_t;

namespace android {

// ---------------------------------------------------------------------------

class Client;
class BClient;
class DisplayHardware;
class FreezeLock;
class Layer;
class LayerBuffer;

typedef int32_t ClientID;

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

// ---------------------------------------------------------------------------

class Client : public RefBase
{
public:
            Client(ClientID cid, const sp<SurfaceFlinger>& flinger);
            ~Client();

            int32_t                 generateId(int pid);
            void                    free(int32_t id);
            status_t                bindLayer(const sp<LayerBaseClient>& layer, int32_t id);

    inline  bool                    isValid(int32_t i) const;
    sp<LayerBaseClient>             getLayerUser(int32_t i) const;
    void                            dump(const char* what);
    
    const Vector< wp<LayerBaseClient> >& getLayers() const { 
        return mLayers; 
    }
    
    const sp<IMemoryHeap>& getControlBlockMemory() const {
        return mCblkHeap; 
    }
    
    // pointer to this client's control block
    SharedClient*           ctrlblk;
    ClientID                cid;

    
private:
    int getClientPid() const { return mPid; }
        
    int                             mPid;
    uint32_t                        mBitmap;
    SortedVector<uint8_t>           mInUse;
    Vector< wp<LayerBaseClient> >   mLayers;
    sp<IMemoryHeap>                 mCblkHeap;
    sp<SurfaceFlinger>              mFlinger;
};

// ---------------------------------------------------------------------------

class GraphicPlane
{
public:
    static status_t orientationToTransfrom(int orientation, int w, int h,
            Transform* tr);

                                GraphicPlane();
                                ~GraphicPlane();

        bool                    initialized() const;

        void                    setDisplayHardware(DisplayHardware *);
        status_t                setOrientation(int orientation);
        int                     getOrientation() const { return mOrientation; }
        int                     getWidth() const;
        int                     getHeight() const;

        const DisplayHardware&  displayHardware() const;
        const Transform&        transform() const;
        EGLDisplay              getEGLDisplay() const;
        
private:
                                GraphicPlane(const GraphicPlane&);
        GraphicPlane            operator = (const GraphicPlane&);

        DisplayHardware*        mHw;
        Transform               mGlobalTransform;
        Transform               mDisplayTransform;
        int                     mOrientation;
        float                   mDisplayWidth;
        float                   mDisplayHeight;
        int                     mWidth;
        int                     mHeight;
};

// ---------------------------------------------------------------------------

enum {
    eTransactionNeeded      = 0x01,
    eTraversalNeeded        = 0x02
};

class SurfaceFlinger : public BnSurfaceComposer, protected Thread
{
public:
    static void instantiate();
    static void shutdown();

                    SurfaceFlinger();
    virtual         ~SurfaceFlinger();
            void    init();

    virtual status_t onTransact(
        uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags);

    virtual status_t dump(int fd, const Vector<String16>& args);

    // ISurfaceComposer interface
    virtual sp<ISurfaceFlingerClient>   createConnection();
    virtual sp<IMemoryHeap>             getCblk() const;
    virtual void                        bootFinished();
    virtual void                        openGlobalTransaction();
    virtual void                        closeGlobalTransaction();
    virtual status_t                    freezeDisplay(DisplayID dpy, uint32_t flags);
    virtual status_t                    unfreezeDisplay(DisplayID dpy, uint32_t flags);
    virtual int                         setOrientation(DisplayID dpy, int orientation, uint32_t flags);
    virtual void                        signal() const;

            void                        screenReleased(DisplayID dpy);
            void                        screenAcquired(DisplayID dpy);

            overlay_control_device_t* getOverlayEngine() const;

            
    status_t removeLayer(const sp<LayerBase>& layer);
    status_t addLayer(const sp<LayerBase>& layer);
    status_t invalidateLayerVisibility(const sp<LayerBase>& layer);
    
private:
    friend class BClient;
    friend class LayerBase;
    friend class LayerBuffer;
    friend class LayerBaseClient;
    friend class LayerBaseClient::Surface;
    friend class Layer;
    friend class LayerBlur;
    friend class LayerDim;

    sp<ISurface> createSurface(ClientID client, int pid, const String8& name,
            ISurfaceFlingerClient::surface_data_t* params,
            DisplayID display, uint32_t w, uint32_t h, PixelFormat format,
            uint32_t flags);

    sp<LayerBaseClient> createNormalSurfaceLocked(
            const sp<Client>& client, DisplayID display,
            int32_t id, uint32_t w, uint32_t h, uint32_t flags,
            PixelFormat& format);

    sp<LayerBaseClient> createBlurSurfaceLocked(
            const sp<Client>& client, DisplayID display,
            int32_t id, uint32_t w, uint32_t h, uint32_t flags);

    sp<LayerBaseClient> createDimSurfaceLocked(
            const sp<Client>& client, DisplayID display,
            int32_t id, uint32_t w, uint32_t h, uint32_t flags);

    sp<LayerBaseClient> createPushBuffersSurfaceLocked(
            const sp<Client>& client, DisplayID display,
            int32_t id, uint32_t w, uint32_t h, uint32_t flags);

    status_t removeSurface(SurfaceID surface_id);
    status_t destroySurface(const sp<LayerBaseClient>& layer);
    status_t setClientState(ClientID cid, int32_t count, const layer_state_t* states);


    class LayerVector {
    public:
        inline              LayerVector() { }
                            LayerVector(const LayerVector&);
        inline size_t       size() const { return layers.size(); }
        inline sp<LayerBase> const* array() const { return layers.array(); }
        ssize_t             add(const sp<LayerBase>&, Vector< sp<LayerBase> >::compar_t);
        ssize_t             remove(const sp<LayerBase>&);
        ssize_t             reorder(const sp<LayerBase>&, Vector< sp<LayerBase> >::compar_t);
        ssize_t             indexOf(const sp<LayerBase>& key, size_t guess=0) const;
        inline sp<LayerBase> operator [] (size_t i) const { return layers[i]; }
    private:
        KeyedVector< sp<LayerBase> , size_t> lookup;
        Vector< sp<LayerBase> >              layers;
    };

    struct State {
        State() {
            orientation = ISurfaceComposer::eOrientationDefault;
            freezeDisplay = 0;
        }
        LayerVector     layersSortedByZ;
        uint8_t         orientation;
        uint8_t         orientationType;
        uint8_t         freezeDisplay;
    };

    virtual bool        threadLoop();
    virtual status_t    readyToRun();
    virtual void        onFirstRef();

public:     // hack to work around gcc 4.0.3 bug
    const GraphicPlane&     graphicPlane(int dpy) const;
          GraphicPlane&     graphicPlane(int dpy);
private:

            void        waitForEvent();
public:     // hack to work around gcc 4.0.3 bug
            void        signalEvent();
private:
            void        signalDelayedEvent(nsecs_t delay);

            void        handleConsoleEvents();
            void        handleTransaction(uint32_t transactionFlags);
            void        handleTransactionLocked(
                            uint32_t transactionFlags, 
                            Vector< sp<LayerBase> >& ditchedLayers);

            void        computeVisibleRegions(
                            LayerVector& currentLayers,
                            Region& dirtyRegion,
                            Region& wormholeRegion);

            void        handlePageFlip();
            bool        lockPageFlip(const LayerVector& currentLayers);
            void        unlockPageFlip(const LayerVector& currentLayers);
            void        handleRepaint();
            void        postFramebuffer();
            void        composeSurfaces(const Region& dirty);
            void        unlockClients();


            void        destroyConnection(ClientID cid);
            sp<LayerBaseClient> getLayerUser_l(SurfaceID index) const;
            status_t    addLayer_l(const sp<LayerBase>& layer);
            status_t    removeLayer_l(const sp<LayerBase>& layer);
            status_t    purgatorizeLayer_l(const sp<LayerBase>& layer);
            void        free_resources_l();

            uint32_t    getTransactionFlags(uint32_t flags);
            uint32_t    setTransactionFlags(uint32_t flags, nsecs_t delay = 0);
            void        commitTransaction();


            friend class FreezeLock;
            sp<FreezeLock> getFreezeLock() const;
            inline void incFreezeCount() {
                if (mFreezeCount == 0)
                    mFreezeDisplayTime = 0;
                mFreezeCount++;
            }
            inline void decFreezeCount() { if (mFreezeCount > 0) mFreezeCount--; }
            inline bool hasFreezeRequest() const { return mFreezeDisplay; }
            inline bool isFrozen() const { 
                return (mFreezeDisplay || mFreezeCount>0) && mBootFinished;
            }

            
            void        debugFlashRegions();
            void        debugShowFPS() const;
            void        drawWormhole() const;
           

    mutable     MessageQueue    mEventQueue;
    
                
                
                // access must be protected by mStateLock
    mutable     Mutex                   mStateLock;
                State                   mCurrentState;
                State                   mDrawingState;
    volatile    int32_t                 mTransactionFlags;
    volatile    int32_t                 mTransactionCount;
                Condition               mTransactionCV;
                bool                    mResizeTransationPending;
                
                // protected by mStateLock (but we could use another lock)
                Tokenizer                               mTokens;
                DefaultKeyedVector<ClientID, sp<Client> >   mClientsMap;
                DefaultKeyedVector<SurfaceID, sp<LayerBaseClient> >   mLayerMap;
                GraphicPlane                            mGraphicPlanes[1];
                bool                                    mLayersRemoved;
                Vector< sp<Client> >                    mDisconnectedClients;

                // constant members (no synchronization needed for access)
                sp<IMemoryHeap>             mServerHeap;
                surface_flinger_cblk_t*     mServerCblk;
                GLuint                      mWormholeTexName;
                nsecs_t                     mBootTime;
                Permission                  mHardwareTest;
                Permission                  mAccessSurfaceFlinger;
                Permission                  mDump;
                
                // Can only accessed from the main thread, these members
                // don't need synchronization
                Region                      mDirtyRegion;
                Region                      mDirtyRegionRemovedLayer;
                Region                      mInvalidRegion;
                Region                      mWormholeRegion;
                bool                        mVisibleRegionsDirty;
                bool                        mDeferReleaseConsole;
                bool                        mFreezeDisplay;
                int32_t                     mFreezeCount;
                nsecs_t                     mFreezeDisplayTime;

                // don't use a lock for these, we don't care
                int                         mDebugRegion;
                int                         mDebugBackground;
                volatile nsecs_t            mDebugInSwapBuffers;
                nsecs_t                     mLastSwapBufferTime;
                volatile nsecs_t            mDebugInTransaction;
                nsecs_t                     mLastTransactionTime;
                bool                        mBootFinished;

                // these are thread safe
    mutable     Barrier                     mReadyToRunBarrier;

                // atomic variables
                enum {
                    eConsoleReleased = 1,
                    eConsoleAcquired = 2
                };
   volatile     int32_t                     mConsoleSignals;

   // only written in the main thread, only read in other threads
   volatile     int32_t                     mSecureFrameBuffer;
};

// ---------------------------------------------------------------------------

class FreezeLock : public LightRefBase<FreezeLock> {
    SurfaceFlinger* mFlinger;
public:
    FreezeLock(SurfaceFlinger* flinger)
        : mFlinger(flinger) {
        mFlinger->incFreezeCount();
    }
    ~FreezeLock() {
        mFlinger->decFreezeCount();
    }
};

// ---------------------------------------------------------------------------

class BClient : public BnSurfaceFlingerClient
{
public:
    BClient(SurfaceFlinger *flinger, ClientID cid,
            const sp<IMemoryHeap>& cblk);
    ~BClient();

    // ISurfaceFlingerClient interface
    virtual sp<IMemoryHeap> getControlBlock() const;

    virtual sp<ISurface> createSurface(
            surface_data_t* params, int pid, const String8& name,
            DisplayID display, uint32_t w, uint32_t h,PixelFormat format,
            uint32_t flags);

    virtual status_t destroySurface(SurfaceID surfaceId);
    virtual status_t setState(int32_t count, const layer_state_t* states);

private:
    ClientID            mId;
    SurfaceFlinger*     mFlinger;
    sp<IMemoryHeap>     mCblk;
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_SURFACE_FLINGER_H
