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

#define LOG_TAG "SurfaceFlinger"

#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <math.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/ioctl.h>

#include <cutils/log.h>
#include <cutils/properties.h>

#include <utils/IPCThreadState.h>
#include <utils/IServiceManager.h>
#include <utils/MemoryDealer.h>
#include <utils/MemoryBase.h>
#include <utils/String8.h>
#include <utils/String16.h>
#include <utils/StopWatch.h>

#include <ui/PixelFormat.h>
#include <ui/DisplayInfo.h>
#include <ui/EGLDisplaySurface.h>

#include <pixelflinger/pixelflinger.h>
#include <GLES/gl.h>

#include "clz.h"
#include "CPUGauge.h"
#include "Layer.h"
#include "LayerBlur.h"
#include "LayerBuffer.h"
#include "LayerDim.h"
#include "LayerBitmap.h"
#include "LayerScreenshot.h"
#include "SurfaceFlinger.h"
#include "RFBServer.h"
#include "VRamHeap.h"

#include "DisplayHardware/DisplayHardware.h"
#include "GPUHardware/GPUHardware.h"


// the VNC server even on local ports presents a significant
// thread as it can allow an application to control and "see" other
// applications, de-facto bypassing security permissions.
#define ENABLE_VNC_SERVER   0

#define DISPLAY_COUNT       1

namespace android {

// ---------------------------------------------------------------------------

void SurfaceFlinger::instantiate() {
    defaultServiceManager()->addService(
            String16("SurfaceFlinger"), new SurfaceFlinger());
}

void SurfaceFlinger::shutdown() {
    // we should unregister here, but not really because
    // when (if) the service manager goes away, all the services
    // it has a reference to will leave too.
}

// ---------------------------------------------------------------------------

SurfaceFlinger::LayerVector::LayerVector(const SurfaceFlinger::LayerVector& rhs)
    : lookup(rhs.lookup), layers(rhs.layers)
{
}

ssize_t SurfaceFlinger::LayerVector::indexOf(
        LayerBase* key, size_t guess) const
{
    if (guess<size() && lookup.keyAt(guess) == key)
        return guess;
    const ssize_t i = lookup.indexOfKey(key);
    if (i>=0) {
        const size_t idx = lookup.valueAt(i);
        LOG_ASSERT(layers[idx]==key,
            "LayerVector[%p]: layers[%d]=%p, key=%p",
            this, int(idx), layers[idx], key);
        return idx;
    }
    return i;
}

ssize_t SurfaceFlinger::LayerVector::add(
        LayerBase* layer,
        Vector<LayerBase*>::compar_t cmp)
{
    size_t count = layers.size();
    ssize_t l = 0;
    ssize_t h = count-1;
    ssize_t mid;
    LayerBase* const* a = layers.array();
    while (l <= h) {
        mid = l + (h - l)/2;
        const int c = cmp(a+mid, &layer);
        if (c == 0)     { l = mid; break; }
        else if (c<0)   { l = mid+1; }
        else            { h = mid-1; }
    }
    size_t order = l;
    while (order<count && !cmp(&layer, a+order)) {
        order++;
    }
    count = lookup.size();
    for (size_t i=0 ; i<count ; i++) {
        if (lookup.valueAt(i) >= order) {
            lookup.editValueAt(i)++;
        }
    }
    layers.insertAt(layer, order);
    lookup.add(layer, order);
    return order;
}

ssize_t SurfaceFlinger::LayerVector::remove(LayerBase* layer)
{
    const ssize_t keyIndex = lookup.indexOfKey(layer);
    if (keyIndex >= 0) {
        const size_t index = lookup.valueAt(keyIndex);
        LOG_ASSERT(layers[index]==layer,
                "LayerVector[%p]: layers[%u]=%p, layer=%p",
                this, int(index), layers[index], layer);
        layers.removeItemsAt(index);
        lookup.removeItemsAt(keyIndex);
        const size_t count = lookup.size();
        for (size_t i=0 ; i<count ; i++) {
            if (lookup.valueAt(i) >= size_t(index)) {
                lookup.editValueAt(i)--;
            }
        }
        return index;
    }
    return NAME_NOT_FOUND;
}

ssize_t SurfaceFlinger::LayerVector::reorder(
        LayerBase* layer,
        Vector<LayerBase*>::compar_t cmp)
{
    // XXX: it's a little lame. but oh well...
    ssize_t err = remove(layer);
    if (err >=0)
        err = add(layer, cmp);
    return err;
}

// ---------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

SurfaceFlinger::SurfaceFlinger()
    :   BnSurfaceComposer(), Thread(false),
        mTransactionFlags(0),
        mTransactionCount(0),
        mBootTime(systemTime()),
        mLastScheduledBroadcast(NULL),
        mVisibleRegionsDirty(false),
        mDeferReleaseConsole(false),
        mFreezeDisplay(false),
        mFreezeCount(0),
        mDebugRegion(0),
        mDebugCpu(0),
        mDebugFps(0),
        mDebugBackground(0),
        mDebugNoBootAnimation(0),
        mSyncObject(),
        mDeplayedTransactionPending(0),
        mConsoleSignals(0),
        mSecureFrameBuffer(0)
{
    init();
}

void SurfaceFlinger::init()
{
    LOGI("SurfaceFlinger is starting");

    // debugging stuff...
    char value[PROPERTY_VALUE_MAX];
    property_get("debug.sf.showupdates", value, "0");
    mDebugRegion = atoi(value);
    property_get("debug.sf.showcpu", value, "0");
    mDebugCpu = atoi(value);
    property_get("debug.sf.showbackground", value, "0");
    mDebugBackground = atoi(value);
    property_get("debug.sf.showfps", value, "0");
    mDebugFps = atoi(value);
    property_get("debug.sf.nobootanimation", value, "0");
    mDebugNoBootAnimation = atoi(value);

    LOGI_IF(mDebugRegion,           "showupdates enabled");
    LOGI_IF(mDebugCpu,              "showcpu enabled");
    LOGI_IF(mDebugBackground,       "showbackground enabled");
    LOGI_IF(mDebugFps,              "showfps enabled");
    LOGI_IF(mDebugNoBootAnimation,  "boot animation disabled");
}

SurfaceFlinger::~SurfaceFlinger()
{
    glDeleteTextures(1, &mWormholeTexName);
}

copybit_device_t* SurfaceFlinger::getBlitEngine() const
{
    return graphicPlane(0).displayHardware().getBlitEngine();
}

overlay_device_t* SurfaceFlinger::getOverlayEngine() const
{
    return graphicPlane(0).displayHardware().getOverlayEngine();
}

sp<IMemory> SurfaceFlinger::getCblk() const
{
    return mServerCblkMemory;
}

status_t SurfaceFlinger::requestGPU(const sp<IGPUCallback>& callback,
        gpu_info_t* gpu)
{
    IPCThreadState* ipc = IPCThreadState::self();
    const int pid = ipc->getCallingPid();
    status_t err = mGPU->request(pid, callback, gpu);
    return err;
}

status_t SurfaceFlinger::revokeGPU()
{
    return mGPU->friendlyRevoke();
}

sp<ISurfaceFlingerClient> SurfaceFlinger::createConnection()
{
    Mutex::Autolock _l(mStateLock);
    uint32_t token = mTokens.acquire();

    Client* client = new Client(token, this);
    if ((client == 0) || (client->ctrlblk == 0)) {
        mTokens.release(token);
        return 0;
    }
    status_t err = mClientsMap.add(token, client);
    if (err < 0) {
        delete client;
        mTokens.release(token);
        return 0;
    }
    sp<BClient> bclient =
        new BClient(this, token, client->controlBlockMemory());
    return bclient;
}

void SurfaceFlinger::destroyConnection(ClientID cid)
{
    Mutex::Autolock _l(mStateLock);
    Client* const client = mClientsMap.valueFor(cid);
    if (client) {
        // free all the layers this client owns
        const Vector<LayerBaseClient*>& layers = client->getLayers();
        const size_t count = layers.size();
        for (size_t i=0 ; i<count ; i++) {
            LayerBaseClient* const layer = layers[i];
            removeLayer_l(layer);
        }

        // the resources associated with this client will be freed
        // during the next transaction, after these surfaces have been
        // properly removed from the screen

        // remove this client from our ClientID->Client mapping.
        mClientsMap.removeItem(cid);

        // and add it to the list of disconnected clients
        mDisconnectedClients.add(client);

        // request a transaction
        setTransactionFlags(eTransactionNeeded);
    }
}

const GraphicPlane& SurfaceFlinger::graphicPlane(int dpy) const
{
    LOGE_IF(uint32_t(dpy) >= DISPLAY_COUNT, "Invalid DisplayID %d", dpy);
    const GraphicPlane& plane(mGraphicPlanes[dpy]);
    return plane;
}

GraphicPlane& SurfaceFlinger::graphicPlane(int dpy)
{
    return const_cast<GraphicPlane&>(
        const_cast<SurfaceFlinger const *>(this)->graphicPlane(dpy));
}

void SurfaceFlinger::bootFinished()
{
    const nsecs_t now = systemTime();
    const nsecs_t duration = now - mBootTime;
    LOGI("Boot is finished (%ld ms)", long(ns2ms(duration)) );
    if (mBootAnimation != 0) {
        mBootAnimation->requestExit();
        mBootAnimation.clear();
    }
}

void SurfaceFlinger::onFirstRef()
{
    run("SurfaceFlinger", PRIORITY_URGENT_DISPLAY);

    // Wait for the main thread to be done with its initialization
    mReadyToRunBarrier.wait();
}


static inline uint16_t pack565(int r, int g, int b) {
    return (r<<11)|(g<<5)|b;
}

// this is defined in libGLES_CM.so
extern ISurfaceComposer* GLES_localSurfaceManager;

status_t SurfaceFlinger::readyToRun()
{
    LOGI(   "SurfaceFlinger's main thread ready to run. "
            "Initializing graphics H/W...");

    // create the shared control-block
    mServerHeap = new MemoryDealer(4096, MemoryDealer::READ_ONLY);
    LOGE_IF(mServerHeap==0, "can't create shared memory dealer");

    mServerCblkMemory = mServerHeap->allocate(4096);
    LOGE_IF(mServerCblkMemory==0, "can't create shared control block");

    mServerCblk = static_cast<surface_flinger_cblk_t *>(mServerCblkMemory->pointer());
    LOGE_IF(mServerCblk==0, "can't get to shared control block's address");
    new(mServerCblk) surface_flinger_cblk_t;

    // get a reference to the GPU if we have one
    mGPU = GPUFactory::getGPU();

    // create the surface Heap manager, which manages the heaps
    // (be it in RAM or VRAM) where surfaces are allocated
    // We give 8 MB per client.
    mSurfaceHeapManager = new SurfaceHeapManager(this, 8 << 20);

    
    GLES_localSurfaceManager = static_cast<ISurfaceComposer*>(this);

    // we only support one display currently
    int dpy = 0;

    {
        // initialize the main display
        GraphicPlane& plane(graphicPlane(dpy));
        DisplayHardware* const hw = new DisplayHardware(this, dpy);
        plane.setDisplayHardware(hw);
    }

    // initialize primary screen
    // (other display should be initialized in the same manner, but
    // asynchronously, as they could come and go. None of this is supported
    // yet).
    const GraphicPlane& plane(graphicPlane(dpy));
    const DisplayHardware& hw = plane.displayHardware();
    const uint32_t w = hw.getWidth();
    const uint32_t h = hw.getHeight();
    const uint32_t f = hw.getFormat();
    hw.makeCurrent();

    // initialize the shared control block
    mServerCblk->connected |= 1<<dpy;
    display_cblk_t* dcblk = mServerCblk->displays + dpy;
    memset(dcblk, 0, sizeof(display_cblk_t));
    dcblk->w            = w;
    dcblk->h            = h;
    dcblk->format       = f;
    dcblk->orientation  = ISurfaceComposer::eOrientationDefault;
    dcblk->xdpi         = hw.getDpiX();
    dcblk->ydpi         = hw.getDpiY();
    dcblk->fps          = hw.getRefreshRate();
    dcblk->density      = hw.getDensity();
    asm volatile ("":::"memory");

    // Initialize OpenGL|ES
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 4); 
    glEnableClientState(GL_VERTEX_ARRAY);
    glEnable(GL_SCISSOR_TEST);
    glShadeModel(GL_FLAT);
    glDisable(GL_DITHER);
    glDisable(GL_CULL_FACE);

    const uint16_t g0 = pack565(0x0F,0x1F,0x0F);
    const uint16_t g1 = pack565(0x17,0x2f,0x17);
    const uint16_t textureData[4] = { g0, g1, g1, g0 };
    glGenTextures(1, &mWormholeTexName);
    glBindTexture(GL_TEXTURE_2D, mWormholeTexName);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, 2, 2, 0,
            GL_RGB, GL_UNSIGNED_SHORT_5_6_5, textureData);

    glViewport(0, 0, w, h);
    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    glOrthof(0, w, h, 0, 0, 1);

   LayerDim::initDimmer(this, w, h);

    mReadyToRunBarrier.open();

    /*
     *  We're now ready to accept clients...
     */

    // start CPU gauge display
    if (mDebugCpu)
        mCpuGauge = new CPUGauge(this, ms2ns(500));

    // the boot animation!
    if (mDebugNoBootAnimation == false)
        mBootAnimation = new BootAnimation(this);

    if (ENABLE_VNC_SERVER)
        mRFBServer = new RFBServer(w, h, f);

    return NO_ERROR;
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Events Handler
#endif

void SurfaceFlinger::waitForEvent()
{
    // wait for something to do
    if (UNLIKELY(isFrozen())) {
        // wait 2 seconds
        int err = mSyncObject.wait(ms2ns(3000));
        if (err != NO_ERROR) {
            if (isFrozen()) {
                // we timed out and are still frozen
                LOGW("timeout expired mFreezeDisplay=%d, mFreezeCount=%d",
                        mFreezeDisplay, mFreezeCount);
                mFreezeCount = 0;
            }
        }
    } else {
        mSyncObject.wait();
    }
}

void SurfaceFlinger::signalEvent() {
    mSyncObject.open();
}

void SurfaceFlinger::signal() const {
    mSyncObject.open();
}

void SurfaceFlinger::signalDelayedEvent(nsecs_t delay)
{
    if (android_atomic_or(1, &mDeplayedTransactionPending) == 0) {
        sp<DelayedTransaction> delayedEvent(new DelayedTransaction(this, delay));
        delayedEvent->run("DelayedeEvent", PRIORITY_URGENT_DISPLAY);
    }
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Main loop
#endif

bool SurfaceFlinger::threadLoop()
{
    waitForEvent();

    // check for transactions
    if (UNLIKELY(mConsoleSignals)) {
        handleConsoleEvents();
    }

    if (LIKELY(mTransactionCount == 0)) {
        // if we're in a global transaction, don't do anything.
        const uint32_t mask = eTransactionNeeded | eTraversalNeeded;
        uint32_t transactionFlags = getTransactionFlags(mask);
        if (LIKELY(transactionFlags)) {
            handleTransaction(transactionFlags);
        }
    }

    // post surfaces (if needed)
    handlePageFlip();

    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    if (LIKELY(hw.canDraw())) {
        // repaint the framebuffer (if needed)
        handleRepaint();

        // release the clients before we flip ('cause flip might block)
        unlockClients();
        executeScheduledBroadcasts();

        // sample the cpu gauge
        if (UNLIKELY(mDebugCpu)) {
            handleDebugCpu();
        }

        postFramebuffer();
    } else {
        // pretend we did the post
        unlockClients();
        executeScheduledBroadcasts();
        usleep(16667); // 60 fps period
    }
    return true;
}

void SurfaceFlinger::postFramebuffer()
{
    if (UNLIKELY(isFrozen())) {
        // we are not allowed to draw, but pause a bit to make sure
        // apps don't end up using the whole CPU, if they depend on
        // surfaceflinger for synchronization.
        usleep(8333); // 8.3ms ~ 120fps
        return;
    }

    if (!mInvalidRegion.isEmpty()) {
        const DisplayHardware& hw(graphicPlane(0).displayHardware());

        if (UNLIKELY(mDebugFps)) {
            debugShowFPS();
        }

        if (UNLIKELY(ENABLE_VNC_SERVER &&
                mRFBServer!=0 && mRFBServer->isConnected())) {
            if (!mSecureFrameBuffer) {
                GGLSurface fb;
                // backbufer, is going to become the front buffer really soon
                hw.getDisplaySurface(&fb);
                if (LIKELY(fb.data != 0)) {
                    mRFBServer->frameBufferUpdated(fb, mInvalidRegion);
                }
            }
        }

        hw.flip(mInvalidRegion);

        mInvalidRegion.clear();

        if (Layer::deletedTextures.size()) {
            glDeleteTextures(
                    Layer::deletedTextures.size(),
                    Layer::deletedTextures.array());
            Layer::deletedTextures.clear();
        }
    }
}

void SurfaceFlinger::handleConsoleEvents()
{
    // something to do with the console
    const DisplayHardware& hw = graphicPlane(0).displayHardware();

    int what = android_atomic_and(0, &mConsoleSignals);
    if (what & eConsoleAcquired) {
        hw.acquireScreen();
    }

    if (mDeferReleaseConsole && hw.canDraw()) {
        // We got the release signal before the aquire signal
        mDeferReleaseConsole = false;
        revokeGPU();
        hw.releaseScreen();
    }

    if (what & eConsoleReleased) {
        if (hw.canDraw()) {
            revokeGPU();
            hw.releaseScreen();
        } else {
            mDeferReleaseConsole = true;
        }
    }

    mDirtyRegion.set(hw.bounds());
}

void SurfaceFlinger::handleTransaction(uint32_t transactionFlags)
{
    Mutex::Autolock _l(mStateLock);

    const LayerVector& currentLayers = mCurrentState.layersSortedByZ;
    const size_t count = currentLayers.size();

    /*
     * Traversal of the children
     * (perform the transaction for each of them if needed)
     */

    const bool layersNeedTransaction = transactionFlags & eTraversalNeeded;
    if (layersNeedTransaction) {
        for (size_t i=0 ; i<count ; i++) {
            LayerBase* const layer = currentLayers[i];
            uint32_t trFlags = layer->getTransactionFlags(eTransactionNeeded);
            if (!trFlags) continue;

            const uint32_t flags = layer->doTransaction(0);
            if (flags & Layer::eVisibleRegion)
                mVisibleRegionsDirty = true;

            if (flags & Layer::eRestartTransaction) {
                // restart the transaction, but back-off a little
                layer->setTransactionFlags(eTransactionNeeded);
                setTransactionFlags(eTraversalNeeded, ms2ns(8));
            }
        }
    }

    /*
     * Perform our own transaction if needed
     */

    if (transactionFlags & eTransactionNeeded) {
        if (mCurrentState.orientation != mDrawingState.orientation) {
            // the orientation has changed, recompute all visible regions
            // and invalidate everything.

            const int dpy = 0;
            const int orientation = mCurrentState.orientation;
            GraphicPlane& plane(graphicPlane(dpy));
            plane.setOrientation(orientation);

            // update the shared control block
            const DisplayHardware& hw(plane.displayHardware());
            volatile display_cblk_t* dcblk = mServerCblk->displays + dpy;
            dcblk->orientation = orientation;
            if (orientation & eOrientationSwapMask) {
                // 90 or 270 degrees orientation
                dcblk->w = hw.getHeight();
                dcblk->h = hw.getWidth();
            } else {
                dcblk->w = hw.getWidth();
                dcblk->h = hw.getHeight();
            }

            mVisibleRegionsDirty = true;
            mDirtyRegion.set(hw.bounds());
        }

        if (mCurrentState.freezeDisplay != mDrawingState.freezeDisplay) {
            // freezing or unfreezing the display -> trigger animation if needed
            mFreezeDisplay = mCurrentState.freezeDisplay;
            const nsecs_t now = systemTime();
            if (mFreezeDisplay) {
                mFreezeDisplayTime = now;
            } else {
                //LOGD("Screen was frozen for %llu us",
                //        ns2us(now-mFreezeDisplayTime));
            }
        }

        // some layers might have been removed, so
        // we need to update the regions they're exposing.
        size_t c = mRemovedLayers.size();
        if (c) {
            mVisibleRegionsDirty = true;
        }

        const LayerVector& currentLayers = mCurrentState.layersSortedByZ;
        if (currentLayers.size() > mDrawingState.layersSortedByZ.size()) {
            // layers have been added
            mVisibleRegionsDirty = true;
        }

        // get rid of all resources we don't need anymore
        // (layers and clients)
        free_resources_l();
    }

    commitTransaction();
}

sp<FreezeLock> SurfaceFlinger::getFreezeLock() const
{
    return new FreezeLock(const_cast<SurfaceFlinger *>(this));
}

void SurfaceFlinger::computeVisibleRegions(
    LayerVector& currentLayers, Region& dirtyRegion, Region& opaqueRegion)
{
    const GraphicPlane& plane(graphicPlane(0));
    const Transform& planeTransform(plane.transform());

    Region aboveOpaqueLayers;
    Region aboveCoveredLayers;
    Region dirty;

    bool secureFrameBuffer = false;

    size_t i = currentLayers.size();
    while (i--) {
        LayerBase* const layer = currentLayers[i];
        layer->validateVisibility(planeTransform);

        // start with the whole surface at its current location
        const Layer::State& s = layer->drawingState();
        const Rect bounds(layer->visibleBounds());

        // handle hidden surfaces by setting the visible region to empty
        Region opaqueRegion;
        Region visibleRegion;
        Region coveredRegion;
        if (UNLIKELY((s.flags & ISurfaceComposer::eLayerHidden) || !s.alpha)) {
            visibleRegion.clear();
        } else {
            const bool translucent = layer->needsBlending();
            visibleRegion.set(bounds);
            coveredRegion = visibleRegion;

            // Remove the transparent area from the visible region
            if (translucent) {
                visibleRegion.subtractSelf(layer->transparentRegionScreen);
            }

            // compute the opaque region
            if (s.alpha==255 && !translucent && layer->getOrientation()>=0) {
                // the opaque region is the visible region
                opaqueRegion = visibleRegion;
            }
        }

        // subtract the opaque region covered by the layers above us
        visibleRegion.subtractSelf(aboveOpaqueLayers);
        coveredRegion.andSelf(aboveCoveredLayers);

        // compute this layer's dirty region
        if (layer->invalidate) {
            // we need to invalidate the whole region
            dirty = visibleRegion;
            // as well, as the old visible region
            dirty.orSelf(layer->visibleRegionScreen);
            layer->invalidate = false;
        } else {
            // compute the exposed region
            // dirty = what's visible now - what's wasn't covered before
            //       = what's visible now & what's was covered before
            dirty = visibleRegion.intersect(layer->coveredRegionScreen);            
        }
        dirty.subtractSelf(aboveOpaqueLayers);

        // accumulate to the screen dirty region
        dirtyRegion.orSelf(dirty);

        // updade aboveOpaqueLayers/aboveCoveredLayers for next (lower) layer
        aboveOpaqueLayers.orSelf(opaqueRegion);
        aboveCoveredLayers.orSelf(bounds);
        
        // Store the visible region is screen space
        layer->setVisibleRegion(visibleRegion);
        layer->setCoveredRegion(coveredRegion);

        // If a secure layer is partially visible, lockdown the screen!
        if (layer->isSecure() && !visibleRegion.isEmpty()) {
            secureFrameBuffer = true;
        }
    }

    mSecureFrameBuffer = secureFrameBuffer;
    opaqueRegion = aboveOpaqueLayers;
}


void SurfaceFlinger::commitTransaction()
{
    mDrawingState = mCurrentState;
    mTransactionCV.signal();
}

void SurfaceFlinger::handlePageFlip()
{
    bool visibleRegions = mVisibleRegionsDirty;
    LayerVector& currentLayers = const_cast<LayerVector&>(mDrawingState.layersSortedByZ);
    visibleRegions |= lockPageFlip(currentLayers);

        const DisplayHardware& hw = graphicPlane(0).displayHardware();
        const Region screenRegion(hw.bounds());
        if (visibleRegions) {
            Region opaqueRegion;
            computeVisibleRegions(currentLayers, mDirtyRegion, opaqueRegion);
            mWormholeRegion = screenRegion.subtract(opaqueRegion);
            mVisibleRegionsDirty = false;
        }

    unlockPageFlip(currentLayers);
    mDirtyRegion.andSelf(screenRegion);
}

bool SurfaceFlinger::lockPageFlip(const LayerVector& currentLayers)
{
    bool recomputeVisibleRegions = false;
    size_t count = currentLayers.size();
    LayerBase* const* layers = currentLayers.array();
    for (size_t i=0 ; i<count ; i++) {
        LayerBase* const layer = layers[i];
        layer->lockPageFlip(recomputeVisibleRegions);
    }
    return recomputeVisibleRegions;
}

void SurfaceFlinger::unlockPageFlip(const LayerVector& currentLayers)
{
    const GraphicPlane& plane(graphicPlane(0));
    const Transform& planeTransform(plane.transform());
    size_t count = currentLayers.size();
    LayerBase* const* layers = currentLayers.array();
    for (size_t i=0 ; i<count ; i++) {
        LayerBase* const layer = layers[i];
        layer->unlockPageFlip(planeTransform, mDirtyRegion);
    }
}

void SurfaceFlinger::handleRepaint()
{
    // set the frame buffer
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();

    if (UNLIKELY(mDebugRegion)) {
        debugFlashRegions();
    }

    // compute the invalid region
    mInvalidRegion.orSelf(mDirtyRegion);

    uint32_t flags = hw.getFlags();
    if (flags & DisplayHardware::BUFFER_PRESERVED) {
        if (flags & DisplayHardware::COPY_BACK_EXTENSION) {
            // yay. nothing to do here.
        } else {
            if (flags & DisplayHardware::UPDATE_ON_DEMAND) {
                // we need to fully redraw the part that will be updated
                mDirtyRegion.set(mInvalidRegion.bounds());
            } else {
                // TODO: we only need te redraw the part that had been drawn
                // the round before and is not drawn now
            }
        }
    } else {
        // COPY_BACK_EXTENSION makes no sense here
        if (flags & DisplayHardware::UPDATE_ON_DEMAND) {
            // we need to fully redraw the part that will be updated
            mDirtyRegion.set(mInvalidRegion.bounds());
        } else {
            // we need to redraw everything
            mDirtyRegion.set(hw.bounds());
            mInvalidRegion = mDirtyRegion;
        }
    }

    // compose all surfaces
    composeSurfaces(mDirtyRegion);

    // clear the dirty regions
    mDirtyRegion.clear();
}

void SurfaceFlinger::composeSurfaces(const Region& dirty)
{
    if (UNLIKELY(!mWormholeRegion.isEmpty())) {
        // should never happen unless the window manager has a bug
        // draw something...
        drawWormhole();
    }
    const SurfaceFlinger& flinger(*this);
    const LayerVector& drawingLayers(mDrawingState.layersSortedByZ);
    const size_t count = drawingLayers.size();
    LayerBase const* const* const layers = drawingLayers.array();
    for (size_t i=0 ; i<count ; ++i) {
        LayerBase const * const layer = layers[i];
        const Region& visibleRegion(layer->visibleRegionScreen);
        if (!visibleRegion.isEmpty())  {
            const Region clip(dirty.intersect(visibleRegion));
            if (!clip.isEmpty()) {
                layer->draw(clip);
            }
        }
    }
}

void SurfaceFlinger::unlockClients()
{
    const LayerVector& drawingLayers(mDrawingState.layersSortedByZ);
    const size_t count = drawingLayers.size();
    LayerBase* const* const layers = drawingLayers.array();
    for (size_t i=0 ; i<count ; ++i) {
        LayerBase* const layer = layers[i];
        layer->finishPageFlip();
    }
}

void SurfaceFlinger::scheduleBroadcast(Client* client)
{
    if (mLastScheduledBroadcast != client) {
        mLastScheduledBroadcast = client;
        mScheduledBroadcasts.add(client);
    }
}

void SurfaceFlinger::executeScheduledBroadcasts()
{
    SortedVector<Client*>& list = mScheduledBroadcasts;
    size_t count = list.size();
    while (count--) {
        per_client_cblk_t* const cblk = list[count]->ctrlblk;
        if (cblk->lock.tryLock() == NO_ERROR) {
            cblk->cv.broadcast();
            list.removeAt(count);
            cblk->lock.unlock();
        } else {
            // schedule another round
            LOGW("executeScheduledBroadcasts() skipped, "
                "contention on the client. We'll try again later...");
            signalDelayedEvent(ms2ns(4));
        }
    }
    mLastScheduledBroadcast = 0;
}

void SurfaceFlinger::handleDebugCpu()
{
    Mutex::Autolock _l(mDebugLock);
    if (mCpuGauge != 0)
        mCpuGauge->sample();
}

void SurfaceFlinger::debugFlashRegions()
{
    if (UNLIKELY(!mDirtyRegion.isRect())) {
        // TODO: do this only if we don't have preserving
        // swapBuffer. If we don't have update-on-demand,
        // redraw everything.
        composeSurfaces(Region(mDirtyRegion.bounds()));
    }

    glDisable(GL_TEXTURE_2D);
    glDisable(GL_BLEND);
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);

    glColor4x(0x10000, 0, 0x10000, 0x10000);

    Rect r;
    Region::iterator iterator(mDirtyRegion);
    while (iterator.iterate(&r)) {
        GLfloat vertices[][2] = {
                { r.left,  r.top },
                { r.left,  r.bottom },
                { r.right, r.bottom },
                { r.right, r.top }
        };
        glVertexPointer(2, GL_FLOAT, 0, vertices);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
    }

    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    hw.flip(mDirtyRegion.merge(mInvalidRegion));
    mInvalidRegion.clear();

    if (mDebugRegion > 1)
       usleep(mDebugRegion * 1000);

    glEnable(GL_SCISSOR_TEST);
    //mDirtyRegion.dump("mDirtyRegion");
}

void SurfaceFlinger::drawWormhole() const
{
    const Region region(mWormholeRegion.intersect(mDirtyRegion));
    if (region.isEmpty())
        return;

    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const int32_t width = hw.getWidth();
    const int32_t height = hw.getHeight();

    glDisable(GL_BLEND);
    glDisable(GL_DITHER);

    if (LIKELY(!mDebugBackground)) {
        glClearColorx(0,0,0,0);
        Rect r;
        Region::iterator iterator(region);
        while (iterator.iterate(&r)) {
            const GLint sy = height - (r.top + r.height());
            glScissor(r.left, sy, r.width(), r.height());
            glClear(GL_COLOR_BUFFER_BIT);
        }
    } else {
        const GLshort vertices[][2] = { { 0, 0 }, { width, 0 },
                { width, height }, { 0, height }  };
        const GLshort tcoords[][2] = { { 0, 0 }, { 1, 0 },  { 1, 1 }, { 0, 1 } };
        glVertexPointer(2, GL_SHORT, 0, vertices);
        glTexCoordPointer(2, GL_SHORT, 0, tcoords);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, mWormholeTexName);
        glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
        glMatrixMode(GL_TEXTURE);
        glLoadIdentity();
        glScalef(width*(1.0f/32.0f), height*(1.0f/32.0f), 1);
        Rect r;
        Region::iterator iterator(region);
        while (iterator.iterate(&r)) {
            const GLint sy = height - (r.top + r.height());
            glScissor(r.left, sy, r.width(), r.height());
            glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        }
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
    }
}

void SurfaceFlinger::debugShowFPS() const
{
    static int mFrameCount;
    static int mLastFrameCount = 0;
    static nsecs_t mLastFpsTime = 0;
    static float mFps = 0;
    mFrameCount++;
    nsecs_t now = systemTime();
    nsecs_t diff = now - mLastFpsTime;
    if (diff > ms2ns(250)) {
        mFps =  ((mFrameCount - mLastFrameCount) * float(s2ns(1))) / diff;
        mLastFpsTime = now;
        mLastFrameCount = mFrameCount;
    }
    // XXX: mFPS has the value we want
 }

status_t SurfaceFlinger::addLayer_l(LayerBase* layer)
{
    ssize_t i = mCurrentState.layersSortedByZ.add(
                layer, &LayerBase::compareCurrentStateZ);
    LayerBaseClient* lbc = LayerBase::dynamicCast<LayerBaseClient*>(layer);
    if (lbc) {
        mLayerMap.add(lbc->serverIndex(), lbc);
    }
    mRemovedLayers.remove(layer);
    return NO_ERROR;
}

status_t SurfaceFlinger::removeLayer_l(LayerBase* layerBase)
{
    ssize_t index = mCurrentState.layersSortedByZ.remove(layerBase);
    if (index >= 0) {
        mRemovedLayers.add(layerBase);
        LayerBaseClient* layer = LayerBase::dynamicCast<LayerBaseClient*>(layerBase);
        if (layer) {
            mLayerMap.removeItem(layer->serverIndex());
        }
        return NO_ERROR;
    }
    // it's possible that we don't find a layer, because it might
    // have been destroyed already -- this is not technically an error
    // from the user because there is a race between destroySurface,
    // destroyclient and destroySurface-from-a-transaction.
    return (index == NAME_NOT_FOUND) ? status_t(NO_ERROR) : index;
}

void SurfaceFlinger::free_resources_l()
{
    // Destroy layers that were removed
    destroy_all_removed_layers_l();

    // free resources associated with disconnected clients
    SortedVector<Client*>& scheduledBroadcasts(mScheduledBroadcasts);
    Vector<Client*>& disconnectedClients(mDisconnectedClients);
    const size_t count = disconnectedClients.size();
    for (size_t i=0 ; i<count ; i++) {
        Client* client = disconnectedClients[i];
        // if this client is the scheduled broadcast list,
        // remove it from there (and we don't need to signal it
        // since it is dead).
        int32_t index = scheduledBroadcasts.indexOf(client);
        if (index >= 0) {
            scheduledBroadcasts.removeItemsAt(index);
        }
        mTokens.release(client->cid);
        delete client;
    }
    disconnectedClients.clear();
}

void SurfaceFlinger::destroy_all_removed_layers_l()
{
    size_t c = mRemovedLayers.size();
    while (c--) {
        LayerBase* const removed_layer = mRemovedLayers[c];

        LOGE_IF(mCurrentState.layersSortedByZ.indexOf(removed_layer) >= 0,
            "layer %p removed but still in the current state list",
            removed_layer);

        delete removed_layer;
    }
    mRemovedLayers.clear();
}


uint32_t SurfaceFlinger::getTransactionFlags(uint32_t flags)
{
    return android_atomic_and(~flags, &mTransactionFlags) & flags;
}

uint32_t SurfaceFlinger::setTransactionFlags(uint32_t flags, nsecs_t delay)
{
    uint32_t old = android_atomic_or(flags, &mTransactionFlags);
    if ((old & flags)==0) { // wake the server up
        if (delay > 0) {
            signalDelayedEvent(delay);
        } else {
            signalEvent();
        }
    }
    return old;
}

void SurfaceFlinger::openGlobalTransaction()
{
    android_atomic_inc(&mTransactionCount);
}

void SurfaceFlinger::closeGlobalTransaction()
{
    if (android_atomic_dec(&mTransactionCount) == 1) {
        signalEvent();
    }
}

status_t SurfaceFlinger::freezeDisplay(DisplayID dpy, uint32_t flags)
{
    if (UNLIKELY(uint32_t(dpy) >= DISPLAY_COUNT))
        return BAD_VALUE;

    Mutex::Autolock _l(mStateLock);
    mCurrentState.freezeDisplay = 1;
    setTransactionFlags(eTransactionNeeded);

    // flags is intended to communicate some sort of animation behavior
    // (for instance fadding)
    return NO_ERROR;
}

status_t SurfaceFlinger::unfreezeDisplay(DisplayID dpy, uint32_t flags)
{
    if (UNLIKELY(uint32_t(dpy) >= DISPLAY_COUNT))
        return BAD_VALUE;

    Mutex::Autolock _l(mStateLock);
    mCurrentState.freezeDisplay = 0;
    setTransactionFlags(eTransactionNeeded);

    // flags is intended to communicate some sort of animation behavior
    // (for instance fadding)
    return NO_ERROR;
}

int SurfaceFlinger::setOrientation(DisplayID dpy, int orientation)
{
    if (UNLIKELY(uint32_t(dpy) >= DISPLAY_COUNT))
        return BAD_VALUE;

    Mutex::Autolock _l(mStateLock);
    if (mCurrentState.orientation != orientation) {
        if (uint32_t(orientation)<=eOrientation270 || orientation==42) {
            mCurrentState.orientation = orientation;
            setTransactionFlags(eTransactionNeeded);
            mTransactionCV.wait(mStateLock);
        } else {
            orientation = BAD_VALUE;
        }
    }
    return orientation;
}

sp<ISurface> SurfaceFlinger::createSurface(ClientID clientId, int pid,
        ISurfaceFlingerClient::surface_data_t* params,
        DisplayID d, uint32_t w, uint32_t h, PixelFormat format,
        uint32_t flags)
{
    LayerBaseClient* layer = 0;
    sp<LayerBaseClient::Surface> surfaceHandle;
    Mutex::Autolock _l(mStateLock);
    Client* const c = mClientsMap.valueFor(clientId);
    if (UNLIKELY(!c)) {
        LOGE("createSurface() failed, client not found (id=%d)", clientId);
        return surfaceHandle;
    }

    //LOGD("createSurface for pid %d (%d x %d)", pid, w, h);
    int32_t id = c->generateId(pid);
    if (uint32_t(id) >= NUM_LAYERS_MAX) {
        LOGE("createSurface() failed, generateId = %d", id);
        return surfaceHandle;
    }

    switch (flags & eFXSurfaceMask) {
        case eFXSurfaceNormal:
            if (UNLIKELY(flags & ePushBuffers)) {
                layer = createPushBuffersSurfaceLocked(c, d, id, w, h, flags);
            } else {
                layer = createNormalSurfaceLocked(c, d, id, w, h, format, flags);
            }
            break;
        case eFXSurfaceBlur:
            layer = createBlurSurfaceLocked(c, d, id, w, h, flags);
            break;
        case eFXSurfaceDim:
            layer = createDimSurfaceLocked(c, d, id, w, h, flags);
            break;
    }

    if (layer) {
        setTransactionFlags(eTransactionNeeded);
        surfaceHandle = layer->getSurface();
        if (surfaceHandle != 0)
            surfaceHandle->getSurfaceData(params);
    }

    return surfaceHandle;
}

LayerBaseClient* SurfaceFlinger::createNormalSurfaceLocked(
        Client* client, DisplayID display,
        int32_t id, uint32_t w, uint32_t h, PixelFormat format, uint32_t flags)
{
    // initialize the surfaces
    switch (format) { // TODO: take h/w into account
    case PIXEL_FORMAT_TRANSPARENT:
    case PIXEL_FORMAT_TRANSLUCENT:
        format = PIXEL_FORMAT_RGBA_8888;
        break;
    case PIXEL_FORMAT_OPAQUE:
        format = PIXEL_FORMAT_RGB_565;
        break;
    }

    Layer* layer = new Layer(this, display, client, id);
    status_t err = layer->setBuffers(client, w, h, format, flags);
    if (LIKELY(err == NO_ERROR)) {
        layer->initStates(w, h, flags);
        addLayer_l(layer);
    } else {
        LOGE("createNormalSurfaceLocked() failed (%s)", strerror(-err));
        delete layer;
        return 0;
    }
    return layer;
}

LayerBaseClient* SurfaceFlinger::createBlurSurfaceLocked(
        Client* client, DisplayID display,
        int32_t id, uint32_t w, uint32_t h, uint32_t flags)
{
    LayerBlur* layer = new LayerBlur(this, display, client, id);
    layer->initStates(w, h, flags);
    addLayer_l(layer);
    return layer;
}

LayerBaseClient* SurfaceFlinger::createDimSurfaceLocked(
        Client* client, DisplayID display,
        int32_t id, uint32_t w, uint32_t h, uint32_t flags)
{
    LayerDim* layer = new LayerDim(this, display, client, id);
    layer->initStates(w, h, flags);
    addLayer_l(layer);
    return layer;
}

LayerBaseClient* SurfaceFlinger::createPushBuffersSurfaceLocked(
        Client* client, DisplayID display,
        int32_t id, uint32_t w, uint32_t h, uint32_t flags)
{
    LayerBuffer* layer = new LayerBuffer(this, display, client, id);
    layer->initStates(w, h, flags);
    addLayer_l(layer);
    return layer;
}

status_t SurfaceFlinger::destroySurface(SurfaceID index)
{
    Mutex::Autolock _l(mStateLock);
    LayerBaseClient* const layer = getLayerUser_l(index);
    status_t err = removeLayer_l(layer);
    if (err < 0)
        return err;
    setTransactionFlags(eTransactionNeeded);
    return NO_ERROR;
}

status_t SurfaceFlinger::setClientState(
        ClientID cid,
        int32_t count,
        const layer_state_t* states)
{
    Mutex::Autolock _l(mStateLock);
    uint32_t flags = 0;
    cid <<= 16;
    for (int i=0 ; i<count ; i++) {
        const layer_state_t& s = states[i];
        LayerBaseClient* layer = getLayerUser_l(s.surface | cid);
        if (layer) {
            const uint32_t what = s.what;
            // check if it has been destroyed first
            if (what & eDestroyed) {
                if (removeLayer_l(layer) == NO_ERROR) {
                    flags |= eTransactionNeeded;
                    // we skip everything else... well, no, not really
                    // we skip ONLY that transaction.
                    continue;
                }
            }
            if (what & ePositionChanged) {
                if (layer->setPosition(s.x, s.y))
                    flags |= eTraversalNeeded;
            }
            if (what & eLayerChanged) {
                if (layer->setLayer(s.z)) {
                    mCurrentState.layersSortedByZ.reorder(
                            layer, &Layer::compareCurrentStateZ);
                    // we need traversal (state changed)
                    // AND transaction (list changed)
                    flags |= eTransactionNeeded|eTraversalNeeded;
                }
            }
            if (what & eSizeChanged) {
                if (layer->setSize(s.w, s.h))
                    flags |= eTraversalNeeded;
            }
            if (what & eAlphaChanged) {
                if (layer->setAlpha(uint8_t(255.0f*s.alpha+0.5f)))
                    flags |= eTraversalNeeded;
            }
            if (what & eMatrixChanged) {
                if (layer->setMatrix(s.matrix))
                    flags |= eTraversalNeeded;
            }
            if (what & eTransparentRegionChanged) {
                if (layer->setTransparentRegionHint(s.transparentRegion))
                    flags |= eTraversalNeeded;
            }
            if (what & eVisibilityChanged) {
                if (layer->setFlags(s.flags, s.mask))
                    flags |= eTraversalNeeded;
            }
        }
    }
    if (flags) {
        setTransactionFlags(flags);
    }
    return NO_ERROR;
}

LayerBaseClient* SurfaceFlinger::getLayerUser_l(SurfaceID s) const
{
    return mLayerMap.valueFor(s);
}

void SurfaceFlinger::screenReleased(int dpy)
{
    // this may be called by a signal handler, we can't do too much in here
    android_atomic_or(eConsoleReleased, &mConsoleSignals);
    signalEvent();
}

void SurfaceFlinger::screenAcquired(int dpy)
{
    // this may be called by a signal handler, we can't do too much in here
    android_atomic_or(eConsoleAcquired, &mConsoleSignals);
    signalEvent();
}

status_t SurfaceFlinger::dump(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 1024;
    char buffer[SIZE];
    String8 result;
    if (checkCallingPermission(
            String16("android.permission.DUMP")) == false)
    { // not allowed
        snprintf(buffer, SIZE, "Permission Denial: "
                "can't dump SurfaceFlinger from pid=%d, uid=%d\n",
                IPCThreadState::self()->getCallingPid(),
                IPCThreadState::self()->getCallingUid());
        result.append(buffer);
    } else {
        Mutex::Autolock _l(mStateLock);
        size_t s = mClientsMap.size();
        char name[64];
        for (size_t i=0 ; i<s ; i++) {
            Client* client = mClientsMap.valueAt(i);
            sprintf(name, "  Client (id=0x%08x)", client->cid);
            client->dump(name);
        }
        const LayerVector& currentLayers = mCurrentState.layersSortedByZ;
        const size_t count = currentLayers.size();
        for (size_t i=0 ; i<count ; i++) {
            /*** LayerBase ***/
            LayerBase const * const layer = currentLayers[i];
            const Layer::State& s = layer->drawingState();
            snprintf(buffer, SIZE,
                    "+ %s %p\n"
                    "      "
                    "z=%9d, pos=(%4d,%4d), size=(%4d,%4d), "
                    "needsBlending=%1d, invalidate=%1d, "
                    "alpha=0x%02x, flags=0x%08x, tr=[%.2f, %.2f][%.2f, %.2f]\n",
                    layer->getTypeID(), layer,
                    s.z, layer->tx(), layer->ty(), s.w, s.h,
                    layer->needsBlending(), layer->invalidate,
                    s.alpha, s.flags,
                    s.transform[0], s.transform[1],
                    s.transform[2], s.transform[3]);
            result.append(buffer);
            buffer[0] = 0;
            /*** LayerBaseClient ***/
            LayerBaseClient* const lbc =
                LayerBase::dynamicCast<LayerBaseClient*>((LayerBase*)layer);
            if (lbc) {
                snprintf(buffer, SIZE,
                        "      "
                        "id=0x%08x, client=0x%08x, identity=%u\n",
                        lbc->clientIndex(), lbc->client ? lbc->client->cid : 0,
                        lbc->getIdentity());
            }
            result.append(buffer);
            buffer[0] = 0;
            /*** LayerBuffer ***/
            LayerBuffer* const lbuf =
                LayerBase::dynamicCast<LayerBuffer*>((LayerBase*)layer);
            if (lbuf) {
                sp<LayerBuffer::Buffer> lbb(lbuf->getBuffer());
                if (lbb != 0) {
                    const LayerBuffer::NativeBuffer& nbuf(lbb->getBuffer());
                    snprintf(buffer, SIZE,
                            "      "
                            "mBuffer={w=%u, h=%u, f=%d, offset=%u, base=%p, fd=%d }\n",
                            nbuf.img.w, nbuf.img.h, nbuf.img.format, nbuf.img.offset,
                            nbuf.img.base, nbuf.img.fd);
                }
            }
            result.append(buffer);
            buffer[0] = 0;
            /*** Layer ***/
            Layer* const l = LayerBase::dynamicCast<Layer*>((LayerBase*)layer);
            if (l) {
                const LayerBitmap& buf0(l->getBuffer(0));
                const LayerBitmap& buf1(l->getBuffer(1));
                snprintf(buffer, SIZE,
                        "      "
                        "format=%2d, [%3ux%3u:%3u] [%3ux%3u:%3u], mTextureName=%d,"
                        " freezeLock=%p, swapState=0x%08x\n",
                        l->pixelFormat(),
                        buf0.width(), buf0.height(), buf0.stride(),
                        buf1.width(), buf1.height(), buf1.stride(),
                        l->getTextureName(), l->getFreezeLock().get(),
                        l->lcblk->swapState);
            }
            result.append(buffer);
            buffer[0] = 0;
            s.transparentRegion.dump(result, "transparentRegion");
            layer->transparentRegionScreen.dump(result, "transparentRegionScreen");
            layer->visibleRegionScreen.dump(result, "visibleRegionScreen");
        }
        mWormholeRegion.dump(result, "WormholeRegion");
        const DisplayHardware& hw(graphicPlane(0).displayHardware());
        snprintf(buffer, SIZE,
                "  display frozen: %s, freezeCount=%d, orientation=%d, canDraw=%d\n",
                mFreezeDisplay?"yes":"no", mFreezeCount,
                mCurrentState.orientation, hw.canDraw());
        result.append(buffer);

        sp<AllocatorInterface> allocator;
        if (mGPU != 0) {
            snprintf(buffer, SIZE, "  GPU owner: %d\n", mGPU->getOwner());
            result.append(buffer);
            allocator = mGPU->getAllocator();
            if (allocator != 0) {
                allocator->dump(result, "GPU Allocator");
            }
        }
        allocator = mSurfaceHeapManager->getAllocator(NATIVE_MEMORY_TYPE_PMEM);
        if (allocator != 0) {
            allocator->dump(result, "PMEM Allocator");
        }
    }
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t SurfaceFlinger::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch (code) {
        case CREATE_CONNECTION:
        case OPEN_GLOBAL_TRANSACTION:
        case CLOSE_GLOBAL_TRANSACTION:
        case SET_ORIENTATION:
        case FREEZE_DISPLAY:
        case UNFREEZE_DISPLAY:
        case BOOT_FINISHED:
        case REVOKE_GPU:
        {
            // codes that require permission check
            IPCThreadState* ipc = IPCThreadState::self();
            const int pid = ipc->getCallingPid();
            const int self_pid = getpid();
            if (UNLIKELY(pid != self_pid)) {
                // we're called from a different process, do the real check
                if (!checkCallingPermission(
                        String16("android.permission.ACCESS_SURFACE_FLINGER")))
                {
                    const int uid = ipc->getCallingUid();
                    LOGE("Permission Denial: "
                            "can't access SurfaceFlinger pid=%d, uid=%d", pid, uid);
                    return PERMISSION_DENIED;
                }
            }
        }
    }

    status_t err = BnSurfaceComposer::onTransact(code, data, reply, flags);
    if (err == UNKNOWN_TRANSACTION || err == PERMISSION_DENIED) {
        if (code == 1012) {
            // take screen-shot of the front buffer
            if (UNLIKELY(checkCallingPermission(
                    String16("android.permission.READ_FRAME_BUFFER")) == false))
            { // not allowed
                LOGE("Permission Denial: "
                        "can't take screenshots from pid=%d, uid=%d\n",
                        IPCThreadState::self()->getCallingPid(),
                        IPCThreadState::self()->getCallingUid());
                return PERMISSION_DENIED;
            }

            if (UNLIKELY(mSecureFrameBuffer)) {
                LOGE("A secure window is on screen: "
                        "can't take screenshots from pid=%d, uid=%d\n",
                        IPCThreadState::self()->getCallingPid(),
                        IPCThreadState::self()->getCallingUid());
                return PERMISSION_DENIED;
            }

            LOGI("Taking a screenshot...");

            LayerScreenshot* l = new LayerScreenshot(this, 0);

            Mutex::Autolock _l(mStateLock);
            const DisplayHardware& hw(graphicPlane(0).displayHardware());
            l->initStates(hw.getWidth(), hw.getHeight(), 0);
            l->setLayer(INT_MAX);

            addLayer_l(l);
            setTransactionFlags(eTransactionNeeded|eTraversalNeeded);

            l->takeScreenshot(mStateLock, reply);

            removeLayer_l(l);
            setTransactionFlags(eTransactionNeeded);
            return NO_ERROR;
        } else {
            // HARDWARE_TEST stuff...
            if (UNLIKELY(checkCallingPermission(
                    String16("android.permission.HARDWARE_TEST")) == false))
            { // not allowed
                LOGE("Permission Denial: pid=%d, uid=%d\n",
                        IPCThreadState::self()->getCallingPid(),
                        IPCThreadState::self()->getCallingUid());
                return PERMISSION_DENIED;
            }
            int n;
            switch (code) {
            case 1000: // SHOW_CPU
                n = data.readInt32();
                mDebugCpu = n ? 1 : 0;
                if (mDebugCpu) {
                    if (mCpuGauge == 0) {
                        mCpuGauge = new CPUGauge(this, ms2ns(500));
                    }
                } else {
                    if (mCpuGauge != 0) {
                        mCpuGauge->requestExitAndWait();
                        Mutex::Autolock _l(mDebugLock);
                        mCpuGauge.clear();
                    }
                }
                return NO_ERROR;
            case 1001:  // SHOW_FPS
                n = data.readInt32();
                mDebugFps = n ? 1 : 0;
                return NO_ERROR;
            case 1002:  // SHOW_UPDATES
                n = data.readInt32();
                mDebugRegion = n ? n : (mDebugRegion ? 0 : 1);
                return NO_ERROR;
            case 1003:  // SHOW_BACKGROUND
                n = data.readInt32();
                mDebugBackground = n ? 1 : 0;
                return NO_ERROR;
            case 1004:{ // repaint everything
                Mutex::Autolock _l(mStateLock);
                const DisplayHardware& hw(graphicPlane(0).displayHardware());
                mDirtyRegion.set(hw.bounds()); // careful that's not thread-safe
                signalEvent();
                }
                return NO_ERROR;
            case 1005: // ask GPU revoke
                mGPU->friendlyRevoke();
                return NO_ERROR;
            case 1006: // revoke GPU
                mGPU->unconditionalRevoke();
                return NO_ERROR;
            case 1007: // set mFreezeCount
                mFreezeCount = data.readInt32();
                return NO_ERROR;
            case 1010:  // interrogate.
                reply->writeInt32(mDebugCpu);
                reply->writeInt32(0);
                reply->writeInt32(mDebugRegion);
                reply->writeInt32(mDebugBackground);
                return NO_ERROR;
            case 1013: { // screenshot
                Mutex::Autolock _l(mStateLock);
                const DisplayHardware& hw(graphicPlane(0).displayHardware());
                reply->writeInt32(hw.getPageFlipCount());
            }
            return NO_ERROR;
            }
        }
    }
    return err;
}

// ---------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

Client::Client(ClientID clientID, const sp<SurfaceFlinger>& flinger)
    : ctrlblk(0), cid(clientID), mPid(0), mBitmap(0), mFlinger(flinger)
{
    mSharedHeapAllocator = getSurfaceHeapManager()->createHeap();
    const int pgsize = getpagesize();
    const int cblksize=((sizeof(per_client_cblk_t)+(pgsize-1))&~(pgsize-1));
    mCblkHeap = new MemoryDealer(cblksize);
    mCblkMemory = mCblkHeap->allocate(cblksize);
    if (mCblkMemory != 0) {
        ctrlblk = static_cast<per_client_cblk_t *>(mCblkMemory->pointer());
        if (ctrlblk) { // construct the shared structure in-place.
            new(ctrlblk) per_client_cblk_t;
        }
    }
}

Client::~Client() {
    if (ctrlblk) {
        const int pgsize = getpagesize();
        ctrlblk->~per_client_cblk_t();  // destroy our shared-structure.
    }
}

const sp<SurfaceHeapManager>& Client::getSurfaceHeapManager() const {
    return mFlinger->getSurfaceHeapManager();
}

int32_t Client::generateId(int pid)
{
    const uint32_t i = clz( ~mBitmap );
    if (i >= NUM_LAYERS_MAX) {
        return NO_MEMORY;
    }
    mPid = pid;
    mInUse.add(uint8_t(i));
    mBitmap |= 1<<(31-i);
    return i;
}
status_t Client::bindLayer(LayerBaseClient* layer, int32_t id)
{
    ssize_t idx = mInUse.indexOf(id);
    if (idx < 0)
        return NAME_NOT_FOUND;
    return mLayers.insertAt(layer, idx);
}
void Client::free(int32_t id)
{
    ssize_t idx = mInUse.remove(uint8_t(id));
    if (idx >= 0) {
        mBitmap &= ~(1<<(31-id));
        mLayers.removeItemsAt(idx);
    }
}

sp<MemoryDealer> Client::createAllocator(uint32_t flags)
{
    sp<MemoryDealer> allocator;
    allocator = getSurfaceHeapManager()->createHeap(
            flags, getClientPid(), mSharedHeapAllocator);
    return allocator;
}

bool Client::isValid(int32_t i) const {
    return (uint32_t(i)<NUM_LAYERS_MAX) && (mBitmap & (1<<(31-i)));
}
const uint8_t* Client::inUseArray() const {
    return mInUse.array();
}
size_t Client::numActiveLayers() const {
    return mInUse.size();
}
LayerBaseClient* Client::getLayerUser(int32_t i) const {
    ssize_t idx = mInUse.indexOf(uint8_t(i));
    if (idx<0) return 0;
    return mLayers[idx];
}

void Client::dump(const char* what)
{
}

// ---------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

BClient::BClient(SurfaceFlinger *flinger, ClientID cid, const sp<IMemory>& cblk)
    : mId(cid), mFlinger(flinger), mCblk(cblk)
{
}

BClient::~BClient() {
    // destroy all resources attached to this client
    mFlinger->destroyConnection(mId);
}

void BClient::getControlBlocks(sp<IMemory>* ctrl) const {
    *ctrl = mCblk;
}

sp<ISurface> BClient::createSurface(
        ISurfaceFlingerClient::surface_data_t* params, int pid,
        DisplayID display, uint32_t w, uint32_t h, PixelFormat format,
        uint32_t flags)
{
    return mFlinger->createSurface(mId, pid, params, display, w, h, format, flags);
}

status_t BClient::destroySurface(SurfaceID sid)
{
    sid |= (mId << 16); // add the client-part to id
    return mFlinger->destroySurface(sid);
}

status_t BClient::setState(int32_t count, const layer_state_t* states)
{
    return mFlinger->setClientState(mId, count, states);
}

// ---------------------------------------------------------------------------

GraphicPlane::GraphicPlane()
    : mHw(0)
{
}

GraphicPlane::~GraphicPlane() {
    delete mHw;
}

bool GraphicPlane::initialized() const {
    return mHw ? true : false;
}

void GraphicPlane::setDisplayHardware(DisplayHardware *hw) {
    mHw = hw;
}

void GraphicPlane::setTransform(const Transform& tr) {
    mTransform = tr;
    mGlobalTransform = mOrientationTransform * mTransform;
}

status_t GraphicPlane::setOrientation(int orientation)
{
    float a, b, c, d, x, y;

    const DisplayHardware& hw(displayHardware());
    const float w = hw.getWidth();
    const float h = hw.getHeight();

    if (orientation == ISurfaceComposer::eOrientationDefault) {
        // make sure the default orientation is optimal
        mOrientationTransform.reset();
        mGlobalTransform = mTransform;
        return NO_ERROR;
    }

    // If the rotation can be handled in hardware, this is where
    // the magic should happen.

    switch (orientation) {
    case ISurfaceComposer::eOrientation90:
        a=0; b=-1; c=1; d=0; x=w; y=0;
        break;
    case ISurfaceComposer::eOrientation180:
        a=-1; b=0; c=0; d=-1; x=w; y=h;
        break;
    case ISurfaceComposer::eOrientation270:
        a=0; b=1; c=-1; d=0; x=0; y=h;
        break;
    case 42: {
        const float r = (3.14159265f / 180.0f) * 42.0f;
        const float si = sinf(r);
        const float co = cosf(r);
        a=co; b=-si; c=si; d=co;
        x = si*(h*0.5f) + (1-co)*(w*0.5f);
        y =-si*(w*0.5f) + (1-co)*(h*0.5f);
    } break;
    default:
        return BAD_VALUE;
    }
    mOrientationTransform.set(a, b, c, d);
    mOrientationTransform.set(x, y);
    mGlobalTransform = mOrientationTransform * mTransform;
    return NO_ERROR;
}

const DisplayHardware& GraphicPlane::displayHardware() const {
    return *mHw;
}

const Transform& GraphicPlane::transform() const {
    return mGlobalTransform;
}

// ---------------------------------------------------------------------------

}; // namespace android
