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
#include <utils/MemoryDealer.h>

#include <ui/PixelFormat.h>
#include <ui/ISurfaceComposer.h>
#include <ui/ISurfaceFlingerClient.h>

#include <private/ui/SharedState.h>
#include <private/ui/LayerState.h>
#include <private/ui/SurfaceFlingerSynchro.h>

#include "Barrier.h"
#include "BootAnimation.h"
#include "CPUGauge.h"
#include "Layer.h"
#include "Tokenizer.h"

struct copybit_device_t;
struct overlay_device_t;

namespace android {

// ---------------------------------------------------------------------------

class Client;
class BClient;
class DisplayHardware;
class FreezeLock;
class GPUHardwareInterface;
class IGPUCallback;
class Layer;
class LayerBuffer;
class LayerOrientationAnim;
class OrientationAnimation;
class SurfaceHeapManager;

typedef int32_t ClientID;

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

// ---------------------------------------------------------------------------

class Client
{
public:
            Client(ClientID cid, const sp<SurfaceFlinger>& flinger);
            ~Client();

            int32_t                 generateId(int pid);
            void                    free(int32_t id);
            status_t                bindLayer(LayerBaseClient* layer, int32_t id);
            sp<MemoryDealer>        createAllocator(uint32_t memory_type);

    inline  bool                    isValid(int32_t i) const;
    inline  const uint8_t*          inUseArray() const;
    inline  size_t                  numActiveLayers() const;
    LayerBaseClient*                getLayerUser(int32_t i) const;
    const Vector<LayerBaseClient*>& getLayers() const { return mLayers; }
    const sp<IMemory>&              controlBlockMemory() const { return mCblkMemory; }
    void                            dump(const char* what);
    const sp<SurfaceHeapManager>&   getSurfaceHeapManager() const;
    
    // pointer to this client's control block
    per_client_cblk_t*      ctrlblk;
    ClientID                cid;

    
private:
    int                     getClientPid() const { return mPid; }
        
    int                         mPid;
    uint32_t                    mBitmap;
    SortedVector<uint8_t>       mInUse;
    Vector<LayerBaseClient*>    mLayers;
    sp<MemoryDealer>            mCblkHeap;
    sp<SurfaceFlinger>          mFlinger;
    sp<MemoryDealer>            mSharedHeapAllocator;
    sp<MemoryDealer>            mPMemAllocator;
    sp<IMemory>                 mCblkMemory;
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
        void                    setTransform(const Transform& tr);
        status_t                setOrientation(int orientation);

        const DisplayHardware&  displayHardware() const;
        const Transform&        transform() const;
private:
                                GraphicPlane(const GraphicPlane&);
        GraphicPlane            operator = (const GraphicPlane&);

        DisplayHardware*        mHw;
        Transform               mTransform;
        Transform               mOrientationTransform;
        Transform               mGlobalTransform;
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
    virtual sp<IMemory>                 getCblk() const;
    virtual void                        bootFinished();
    virtual void                        openGlobalTransaction();
    virtual void                        closeGlobalTransaction();
    virtual status_t                    freezeDisplay(DisplayID dpy, uint32_t flags);
    virtual status_t                    unfreezeDisplay(DisplayID dpy, uint32_t flags);
    virtual int                         setOrientation(DisplayID dpy, int orientation);
    virtual void                        signal() const;
    virtual status_t requestGPU(const sp<IGPUCallback>& callback, 
            gpu_info_t* gpu);
    virtual status_t revokeGPU();

            void                        screenReleased(DisplayID dpy);
            void                        screenAcquired(DisplayID dpy);

            const sp<SurfaceHeapManager>& getSurfaceHeapManager() const { 
                return mSurfaceHeapManager; 
            }

            const sp<GPUHardwareInterface>& getGPU() const {
                return mGPU; 
            }

            copybit_device_t* getBlitEngine() const;
            overlay_control_device_t* getOverlayEngine() const;

            
    status_t removeLayer(LayerBase* layer);
    status_t addLayer(LayerBase* layer);
    status_t invalidateLayerVisibility(LayerBase* layer);
    
private:
    friend class BClient;
    friend class LayerBase;
    friend class LayerBuffer;
    friend class LayerBaseClient;
    friend class Layer;
    friend class LayerBlur;

    sp<ISurface> createSurface(ClientID client, int pid, 
            ISurfaceFlingerClient::surface_data_t* params,
            DisplayID display, uint32_t w, uint32_t h, PixelFormat format,
            uint32_t flags);

    LayerBaseClient* createNormalSurfaceLocked(Client* client, DisplayID display,
            int32_t id, uint32_t w, uint32_t h, PixelFormat format, uint32_t flags);

    LayerBaseClient* createBlurSurfaceLocked(Client* client, DisplayID display,
            int32_t id, uint32_t w, uint32_t h, uint32_t flags);

    LayerBaseClient* createDimSurfaceLocked(Client* client, DisplayID display,
            int32_t id, uint32_t w, uint32_t h, uint32_t flags);

    LayerBaseClient* createPushBuffersSurfaceLocked(Client* client, DisplayID display,
            int32_t id, uint32_t w, uint32_t h, uint32_t flags);

    status_t    destroySurface(SurfaceID surface_id);
    status_t    setClientState(ClientID cid, int32_t count, const layer_state_t* states);


    class LayerVector {
    public:
        inline              LayerVector() { }
                            LayerVector(const LayerVector&);
        inline size_t       size() const { return layers.size(); }
        inline LayerBase*const* array() const { return layers.array(); }
        ssize_t             add(LayerBase*, Vector<LayerBase*>::compar_t);
        ssize_t             remove(LayerBase*);
        ssize_t             reorder(LayerBase*, Vector<LayerBase*>::compar_t);
        ssize_t             indexOf(LayerBase* key, size_t guess=0) const;
        inline LayerBase*   operator [] (size_t i) const { return layers[i]; }
    private:
        KeyedVector<LayerBase*, size_t> lookup;
        Vector<LayerBase*>              layers;
    };

    struct State {
        State() {
            orientation = ISurfaceComposer::eOrientationDefault;
            freezeDisplay = 0;
        }
        LayerVector     layersSortedByZ;
        uint8_t         orientation;
        uint8_t         freezeDisplay;
    };

    class DelayedTransaction : public Thread
    {
        friend class SurfaceFlinger;
        sp<SurfaceFlinger>  mFlinger;
        nsecs_t             mDelay;
    public:
        DelayedTransaction(const sp<SurfaceFlinger>& flinger, nsecs_t delay)
            : Thread(false), mFlinger(flinger), mDelay(delay) {
        }
        virtual bool threadLoop() {
            usleep(mDelay / 1000);
            if (android_atomic_and(~1,
                    &mFlinger->mDeplayedTransactionPending) == 1) {
                mFlinger->signalEvent();
            }
            return false;
        }
    };

    virtual bool        threadLoop();
    virtual status_t    readyToRun();
    virtual void        onFirstRef();

    const GraphicPlane&     graphicPlane(int dpy) const;
          GraphicPlane&     graphicPlane(int dpy);

            void        waitForEvent();
            void        signalEvent();
            void        signalDelayedEvent(nsecs_t delay);

            void        handleConsoleEvents();
            void        handleTransaction(uint32_t transactionFlags);

            void        computeVisibleRegions(
                            LayerVector& currentLayers,
                            Region& dirtyRegion,
                            Region& wormholeRegion);

            void        handlePageFlip();
            bool        lockPageFlip(const LayerVector& currentLayers);
            void        unlockPageFlip(const LayerVector& currentLayers);
            void        handleRepaint();
            void        handleDebugCpu();
            void        scheduleBroadcast(Client* client);
            void        executeScheduledBroadcasts();
            void        postFramebuffer();
            void        composeSurfaces(const Region& dirty);
            void        unlockClients();


            void        destroyConnection(ClientID cid);
            LayerBaseClient* getLayerUser_l(SurfaceID index) const;
            status_t    addLayer_l(LayerBase* layer);
            status_t    removeLayer_l(LayerBase* layer);
            void        destroy_all_removed_layers_l();
            void        free_resources_l();

            uint32_t    getTransactionFlags(uint32_t flags);
            uint32_t    setTransactionFlags(uint32_t flags, nsecs_t delay = 0);
            void        commitTransaction();


            friend class FreezeLock;
            sp<FreezeLock> getFreezeLock() const;
            inline void incFreezeCount() { mFreezeCount++; }
            inline void decFreezeCount() { if (mFreezeCount > 0) mFreezeCount--; }
            inline bool hasFreezeRequest() const { return mFreezeDisplay; }
            inline bool isFrozen() const { 
                return mFreezeDisplay || mFreezeCount>0;
            }

            
            void        debugFlashRegions();
            void        debugShowFPS() const;
            void        drawWormhole() const;
           
                // access must be protected by mStateLock
    mutable     Mutex                   mStateLock;
                State                   mCurrentState;
                State                   mDrawingState;
    volatile    int32_t                 mTransactionFlags;
    volatile    int32_t                 mTransactionCount;
                Condition               mTransactionCV;

                // protected by mStateLock (but we could use another lock)
                Tokenizer                               mTokens;
                DefaultKeyedVector<ClientID, Client*>   mClientsMap;
                DefaultKeyedVector<SurfaceID, LayerBaseClient*>   mLayerMap;
                GraphicPlane                            mGraphicPlanes[1];
                SortedVector<LayerBase*>                mRemovedLayers;
                Vector<Client*>                         mDisconnectedClients;

                // constant members (no synchronization needed for access)
                sp<MemoryDealer>            mServerHeap;
                sp<IMemory>                 mServerCblkMemory;
                surface_flinger_cblk_t*     mServerCblk;
                sp<SurfaceHeapManager>      mSurfaceHeapManager;
                sp<GPUHardwareInterface>    mGPU;
                GLuint                      mWormholeTexName;
                sp<BootAnimation>           mBootAnimation;
                nsecs_t                     mBootTime;
                
                // Can only accessed from the main thread, these members
                // don't need synchronization
                Region                      mDirtyRegion;
                Region                      mInvalidRegion;
                Region                      mWormholeRegion;
                Client*                     mLastScheduledBroadcast;
                SortedVector<Client*>       mScheduledBroadcasts;
                bool                        mVisibleRegionsDirty;
                bool                        mDeferReleaseConsole;
                bool                        mFreezeDisplay;
                int32_t                     mFreezeCount;
                nsecs_t                     mFreezeDisplayTime;
                friend class OrientationAnimation;
                OrientationAnimation*       mOrientationAnimation;

                // access protected by mDebugLock
    mutable     Mutex                       mDebugLock;
                sp<CPUGauge>                mCpuGauge;

                // don't use a lock for these, we don't care
                int                         mDebugRegion;
                int                         mDebugCpu;
                int                         mDebugFps;
                int                         mDebugBackground;
                int                         mDebugNoBootAnimation;

                // these are thread safe
    mutable     Barrier                     mReadyToRunBarrier;
    mutable     SurfaceFlingerSynchro       mSyncObject;
    volatile    int32_t                     mDeplayedTransactionPending;

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
            const sp<IMemory>& cblk);
    ~BClient();

    // ISurfaceFlingerClient interface
    virtual void getControlBlocks(sp<IMemory>* ctrl) const;

    virtual sp<ISurface> createSurface(
            surface_data_t* params, int pid,
            DisplayID display, uint32_t w, uint32_t h,PixelFormat format,
            uint32_t flags);

    virtual status_t destroySurface(SurfaceID surfaceId);
    virtual status_t setState(int32_t count, const layer_state_t* states);

private:
    ClientID            mId;
    SurfaceFlinger*     mFlinger;
    sp<IMemory>         mCblk;
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_SURFACE_FLINGER_H
