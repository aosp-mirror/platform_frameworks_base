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

#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <math.h>
#include <limits.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/ioctl.h>

#include <cutils/log.h>
#include <cutils/properties.h>

#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/MemoryHeapBase.h>

#include <utils/String8.h>
#include <utils/String16.h>
#include <utils/StopWatch.h>

#include <ui/GraphicBufferAllocator.h>
#include <ui/PixelFormat.h>

#include <pixelflinger/pixelflinger.h>
#include <GLES/gl.h>

#include "clz.h"
#include "Layer.h"
#include "LayerBlur.h"
#include "LayerBuffer.h"
#include "LayerDim.h"
#include "SurfaceFlinger.h"

#include "DisplayHardware/DisplayHardware.h"

/* ideally AID_GRAPHICS would be in a semi-public header
 * or there would be a way to map a user/group name to its id
 */
#ifndef AID_GRAPHICS
#define AID_GRAPHICS 1003
#endif

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
        const sp<LayerBase>& key, size_t guess) const
{
    if (guess<size() && lookup.keyAt(guess) == key)
        return guess;
    const ssize_t i = lookup.indexOfKey(key);
    if (i>=0) {
        const size_t idx = lookup.valueAt(i);
        LOGE_IF(layers[idx]!=key,
            "LayerVector[%p]: layers[%d]=%p, key=%p",
            this, int(idx), layers[idx].get(), key.get());
        return idx;
    }
    return i;
}

ssize_t SurfaceFlinger::LayerVector::add(
        const sp<LayerBase>& layer,
        Vector< sp<LayerBase> >::compar_t cmp)
{
    size_t count = layers.size();
    ssize_t l = 0;
    ssize_t h = count-1;
    ssize_t mid;
    sp<LayerBase> const* a = layers.array();
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

ssize_t SurfaceFlinger::LayerVector::remove(const sp<LayerBase>& layer)
{
    const ssize_t keyIndex = lookup.indexOfKey(layer);
    if (keyIndex >= 0) {
        const size_t index = lookup.valueAt(keyIndex);
        LOGE_IF(layers[index]!=layer,
                "LayerVector[%p]: layers[%u]=%p, layer=%p",
                this, int(index), layers[index].get(), layer.get());
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
        const sp<LayerBase>& layer,
        Vector< sp<LayerBase> >::compar_t cmp)
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
        mResizeTransationPending(false),
        mLayersRemoved(false),
        mBootTime(systemTime()),
        mHardwareTest("android.permission.HARDWARE_TEST"),
        mAccessSurfaceFlinger("android.permission.ACCESS_SURFACE_FLINGER"),
        mDump("android.permission.DUMP"),
        mVisibleRegionsDirty(false),
        mDeferReleaseConsole(false),
        mFreezeDisplay(false),
        mFreezeCount(0),
        mFreezeDisplayTime(0),
        mDebugRegion(0),
        mDebugBackground(0),
        mDebugInSwapBuffers(0),
        mLastSwapBufferTime(0),
        mDebugInTransaction(0),
        mLastTransactionTime(0),
        mBootFinished(false),
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
    property_get("debug.sf.showbackground", value, "0");
    mDebugBackground = atoi(value);

    LOGI_IF(mDebugRegion,           "showupdates enabled");
    LOGI_IF(mDebugBackground,       "showbackground enabled");
}

SurfaceFlinger::~SurfaceFlinger()
{
    glDeleteTextures(1, &mWormholeTexName);
}

overlay_control_device_t* SurfaceFlinger::getOverlayEngine() const
{
    return graphicPlane(0).displayHardware().getOverlayEngine();
}

sp<IMemoryHeap> SurfaceFlinger::getCblk() const
{
    return mServerHeap;
}

sp<ISurfaceFlingerClient> SurfaceFlinger::createConnection()
{
    Mutex::Autolock _l(mStateLock);
    uint32_t token = mTokens.acquire();

    sp<Client> client = new Client(token, this);
    if (client->ctrlblk == 0) {
        mTokens.release(token);
        return 0;
    }
    status_t err = mClientsMap.add(token, client);
    if (err < 0) {
        mTokens.release(token);
        return 0;
    }
    sp<BClient> bclient =
        new BClient(this, token, client->getControlBlockMemory());
    return bclient;
}

void SurfaceFlinger::destroyConnection(ClientID cid)
{
    Mutex::Autolock _l(mStateLock);
    sp<Client> client = mClientsMap.valueFor(cid);
    if (client != 0) {
        // free all the layers this client owns
        Vector< wp<LayerBaseClient> > layers(client->getLayers());
        const size_t count = layers.size();
        for (size_t i=0 ; i<count ; i++) {
            sp<LayerBaseClient> layer(layers[i].promote());
            if (layer != 0) {
                purgatorizeLayer_l(layer);
            }
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
    mBootFinished = true;
    property_set("ctl.stop", "bootanim");
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

status_t SurfaceFlinger::readyToRun()
{
    LOGI(   "SurfaceFlinger's main thread ready to run. "
            "Initializing graphics H/W...");

    // we only support one display currently
    int dpy = 0;

    {
        // initialize the main display
        GraphicPlane& plane(graphicPlane(dpy));
        DisplayHardware* const hw = new DisplayHardware(this, dpy);
        plane.setDisplayHardware(hw);
    }

    // create the shared control-block
    mServerHeap = new MemoryHeapBase(4096,
            MemoryHeapBase::READ_ONLY, "SurfaceFlinger read-only heap");
    LOGE_IF(mServerHeap==0, "can't create shared memory dealer");
    
    mServerCblk = static_cast<surface_flinger_cblk_t*>(mServerHeap->getBase());
    LOGE_IF(mServerCblk==0, "can't get to shared control block's address");
    
    new(mServerCblk) surface_flinger_cblk_t;

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
    dcblk->w            = plane.getWidth();
    dcblk->h            = plane.getHeight();
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

    // start boot animation
    property_set("ctl.start", "bootanim");
    
    return NO_ERROR;
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Events Handler
#endif

void SurfaceFlinger::waitForEvent()
{
    while (true) {
        nsecs_t timeout = -1;
        const nsecs_t freezeDisplayTimeout = ms2ns(5000);
        if (UNLIKELY(isFrozen())) {
            // wait 5 seconds
            const nsecs_t now = systemTime();
            if (mFreezeDisplayTime == 0) {
                mFreezeDisplayTime = now;
            }
            nsecs_t waitTime = freezeDisplayTimeout - (now - mFreezeDisplayTime);
            timeout = waitTime>0 ? waitTime : 0;
        }

        MessageList::value_type msg = mEventQueue.waitMessage(timeout);

        // see if we timed out
        if (isFrozen()) {
            const nsecs_t now = systemTime();
            nsecs_t frozenTime = (now - mFreezeDisplayTime);
            if (frozenTime >= freezeDisplayTimeout) {
                // we timed out and are still frozen
                LOGW("timeout expired mFreezeDisplay=%d, mFreezeCount=%d",
                        mFreezeDisplay, mFreezeCount);
                mFreezeDisplayTime = 0;
                mFreezeCount = 0;
                mFreezeDisplay = false;
            }
        }

        if (msg != 0) {
            switch (msg->what) {
                case MessageQueue::INVALIDATE:
                    // invalidate message, just return to the main loop
                    return;
            }
        }
    }
}

void SurfaceFlinger::signalEvent() {
    mEventQueue.invalidate();
}

void SurfaceFlinger::signal() const {
    // this is the IPC call
    const_cast<SurfaceFlinger*>(this)->signalEvent();
}

void SurfaceFlinger::signalDelayedEvent(nsecs_t delay)
{
    mEventQueue.postMessage( new MessageBase(MessageQueue::INVALIDATE), delay);
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
    if (LIKELY(hw.canDraw() && !isFrozen())) {
        // repaint the framebuffer (if needed)
        handleRepaint();

        // inform the h/w that we're done compositing
        hw.compositionComplete();

        // release the clients before we flip ('cause flip might block)
        unlockClients();

        postFramebuffer();
    } else {
        // pretend we did the post
        unlockClients();
        usleep(16667); // 60 fps period
    }
    return true;
}

void SurfaceFlinger::postFramebuffer()
{
    if (!mInvalidRegion.isEmpty()) {
        const DisplayHardware& hw(graphicPlane(0).displayHardware());
        const nsecs_t now = systemTime();
        mDebugInSwapBuffers = now;
        hw.flip(mInvalidRegion);
        mLastSwapBufferTime = systemTime() - now;
        mDebugInSwapBuffers = 0;
        mInvalidRegion.clear();
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
        // We got the release signal before the acquire signal
        mDeferReleaseConsole = false;
        hw.releaseScreen();
    }

    if (what & eConsoleReleased) {
        if (hw.canDraw()) {
            hw.releaseScreen();
        } else {
            mDeferReleaseConsole = true;
        }
    }

    mDirtyRegion.set(hw.bounds());
}

void SurfaceFlinger::handleTransaction(uint32_t transactionFlags)
{
    Vector< sp<LayerBase> > ditchedLayers;

    { // scope for the lock
        Mutex::Autolock _l(mStateLock);
        const nsecs_t now = systemTime();
        mDebugInTransaction = now;
        handleTransactionLocked(transactionFlags, ditchedLayers);
        mLastTransactionTime = systemTime() - now;
        mDebugInTransaction = 0;
    }

    // do this without lock held
    const size_t count = ditchedLayers.size();
    for (size_t i=0 ; i<count ; i++) {
        if (ditchedLayers[i] != 0) {
            //LOGD("ditching layer %p", ditchedLayers[i].get());
            ditchedLayers[i]->ditch();
        }
    }
}

void SurfaceFlinger::handleTransactionLocked(
        uint32_t transactionFlags, Vector< sp<LayerBase> >& ditchedLayers)
{
    const LayerVector& currentLayers(mCurrentState.layersSortedByZ);
    const size_t count = currentLayers.size();

    /*
     * Traversal of the children
     * (perform the transaction for each of them if needed)
     */

    const bool layersNeedTransaction = transactionFlags & eTraversalNeeded;
    if (layersNeedTransaction) {
        for (size_t i=0 ; i<count ; i++) {
            const sp<LayerBase>& layer = currentLayers[i];
            uint32_t trFlags = layer->getTransactionFlags(eTransactionNeeded);
            if (!trFlags) continue;

            const uint32_t flags = layer->doTransaction(0);
            if (flags & Layer::eVisibleRegion)
                mVisibleRegionsDirty = true;
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
            const uint32_t type = mCurrentState.orientationType;
            GraphicPlane& plane(graphicPlane(dpy));
            plane.setOrientation(orientation);

            // update the shared control block
            const DisplayHardware& hw(plane.displayHardware());
            volatile display_cblk_t* dcblk = mServerCblk->displays + dpy;
            dcblk->orientation = orientation;
            dcblk->w = plane.getWidth();
            dcblk->h = plane.getHeight();

            mVisibleRegionsDirty = true;
            mDirtyRegion.set(hw.bounds());
        }

        if (mCurrentState.freezeDisplay != mDrawingState.freezeDisplay) {
            // freezing or unfreezing the display -> trigger animation if needed
            mFreezeDisplay = mCurrentState.freezeDisplay;
            if (mFreezeDisplay)
                 mFreezeDisplayTime = 0;
        }

        if (currentLayers.size() > mDrawingState.layersSortedByZ.size()) {
            // layers have been added
            mVisibleRegionsDirty = true;
        }

        // some layers might have been removed, so
        // we need to update the regions they're exposing.
        if (mLayersRemoved) {
            mLayersRemoved = false;
            mVisibleRegionsDirty = true;
            const LayerVector& previousLayers(mDrawingState.layersSortedByZ);
            const size_t count = previousLayers.size();
            for (size_t i=0 ; i<count ; i++) {
                const sp<LayerBase>& layer(previousLayers[i]);
                if (currentLayers.indexOf( layer ) < 0) {
                    // this layer is not visible anymore
                    ditchedLayers.add(layer);
                    mDirtyRegionRemovedLayer.orSelf(layer->visibleRegionScreen);
                }
            }
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
    const DisplayHardware& hw(plane.displayHardware());
    const Region screenRegion(hw.bounds());

    Region aboveOpaqueLayers;
    Region aboveCoveredLayers;
    Region dirty;

    bool secureFrameBuffer = false;

    size_t i = currentLayers.size();
    while (i--) {
        const sp<LayerBase>& layer = currentLayers[i];
        layer->validateVisibility(planeTransform);

        // start with the whole surface at its current location
        const Layer::State& s(layer->drawingState());

        /*
         * opaqueRegion: area of a surface that is fully opaque.
         */
        Region opaqueRegion;

        /*
         * visibleRegion: area of a surface that is visible on screen
         * and not fully transparent. This is essentially the layer's
         * footprint minus the opaque regions above it.
         * Areas covered by a translucent surface are considered visible.
         */
        Region visibleRegion;

        /*
         * coveredRegion: area of a surface that is covered by all
         * visible regions above it (which includes the translucent areas).
         */
        Region coveredRegion;


        // handle hidden surfaces by setting the visible region to empty
        if (LIKELY(!(s.flags & ISurfaceComposer::eLayerHidden) && s.alpha)) {
            const bool translucent = layer->needsBlending();
            const Rect bounds(layer->visibleBounds());
            visibleRegion.set(bounds);
            visibleRegion.andSelf(screenRegion);
            if (!visibleRegion.isEmpty()) {
                // Remove the transparent area from the visible region
                if (translucent) {
                    visibleRegion.subtractSelf(layer->transparentRegionScreen);
                }

                // compute the opaque region
                const int32_t layerOrientation = layer->getOrientation();
                if (s.alpha==255 && !translucent &&
                        ((layerOrientation & Transform::ROT_INVALID) == false)) {
                    // the opaque region is the layer's footprint
                    opaqueRegion = visibleRegion;
                }
            }
        }

        // Clip the covered region to the visible region
        coveredRegion = aboveCoveredLayers.intersect(visibleRegion);

        // Update aboveCoveredLayers for next (lower) layer
        aboveCoveredLayers.orSelf(visibleRegion);

        // subtract the opaque region covered by the layers above us
        visibleRegion.subtractSelf(aboveOpaqueLayers);

        // compute this layer's dirty region
        if (layer->contentDirty) {
            // we need to invalidate the whole region
            dirty = visibleRegion;
            // as well, as the old visible region
            dirty.orSelf(layer->visibleRegionScreen);
            layer->contentDirty = false;
        } else {
            /* compute the exposed region:
             *   the exposed region consists of two components:
             *   1) what's VISIBLE now and was COVERED before
             *   2) what's EXPOSED now less what was EXPOSED before
             *
             * note that (1) is conservative, we start with the whole
             * visible region but only keep what used to be covered by
             * something -- which mean it may have been exposed.
             *
             * (2) handles areas that were not covered by anything but got
             * exposed because of a resize.
             */
            const Region newExposed = visibleRegion - coveredRegion;
            const Region oldVisibleRegion = layer->visibleRegionScreen;
            const Region oldCoveredRegion = layer->coveredRegionScreen;
            const Region oldExposed = oldVisibleRegion - oldCoveredRegion;
            dirty = (visibleRegion&oldCoveredRegion) | (newExposed-oldExposed);
        }
        dirty.subtractSelf(aboveOpaqueLayers);

        // accumulate to the screen dirty region
        dirtyRegion.orSelf(dirty);

        // Update aboveOpaqueLayers for next (lower) layer
        aboveOpaqueLayers.orSelf(opaqueRegion);
        
        // Store the visible region is screen space
        layer->setVisibleRegion(visibleRegion);
        layer->setCoveredRegion(coveredRegion);

        // If a secure layer is partially visible, lock-down the screen!
        if (layer->isSecure() && !visibleRegion.isEmpty()) {
            secureFrameBuffer = true;
        }
    }

    // invalidate the areas where a layer was removed
    dirtyRegion.orSelf(mDirtyRegionRemovedLayer);
    mDirtyRegionRemovedLayer.clear();

    mSecureFrameBuffer = secureFrameBuffer;
    opaqueRegion = aboveOpaqueLayers;
}


void SurfaceFlinger::commitTransaction()
{
    mDrawingState = mCurrentState;
    mResizeTransationPending = false;
    mTransactionCV.broadcast();
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
    sp<LayerBase> const* layers = currentLayers.array();
    for (size_t i=0 ; i<count ; i++) {
        const sp<LayerBase>& layer = layers[i];
        layer->lockPageFlip(recomputeVisibleRegions);
    }
    return recomputeVisibleRegions;
}

void SurfaceFlinger::unlockPageFlip(const LayerVector& currentLayers)
{
    const GraphicPlane& plane(graphicPlane(0));
    const Transform& planeTransform(plane.transform());
    size_t count = currentLayers.size();
    sp<LayerBase> const* layers = currentLayers.array();
    for (size_t i=0 ; i<count ; i++) {
        const sp<LayerBase>& layer = layers[i];
        layer->unlockPageFlip(planeTransform, mDirtyRegion);
    }
}


void SurfaceFlinger::handleRepaint()
{
    // compute the invalid region
    mInvalidRegion.orSelf(mDirtyRegion);
    if (mInvalidRegion.isEmpty()) {
        // nothing to do
        return;
    }

    if (UNLIKELY(mDebugRegion)) {
        debugFlashRegions();
    }

    // set the frame buffer
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();

    uint32_t flags = hw.getFlags();
    if ((flags & DisplayHardware::SWAP_RECTANGLE) || 
        (flags & DisplayHardware::BUFFER_PRESERVED)) 
    {
        // we can redraw only what's dirty, but since SWAP_RECTANGLE only
        // takes a rectangle, we must make sure to update that whole
        // rectangle in that case
        if (flags & DisplayHardware::SWAP_RECTANGLE) {
            // FIXME: we really should be able to pass a region to
            // SWAP_RECTANGLE so that we don't have to redraw all this.
            mDirtyRegion.set(mInvalidRegion.bounds());
        } else {
            // in the BUFFER_PRESERVED case, obviously, we can update only
            // what's needed and nothing more.
            // NOTE: this is NOT a common case, as preserving the backbuffer
            // is costly and usually involves copying the whole update back.
        }
    } else {
        if (flags & DisplayHardware::PARTIAL_UPDATES) {
            // We need to redraw the rectangle that will be updated
            // (pushed to the framebuffer).
            // This is needed because PARTIAL_UPDATES only takes one
            // rectangle instead of a region (see DisplayHardware::flip())
            mDirtyRegion.set(mInvalidRegion.bounds());
        } else {
            // we need to redraw everything (the whole screen)
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
    sp<LayerBase> const* const layers = drawingLayers.array();
    for (size_t i=0 ; i<count ; ++i) {
        const sp<LayerBase>& layer = layers[i];
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
    sp<LayerBase> const* const layers = drawingLayers.array();
    for (size_t i=0 ; i<count ; ++i) {
        const sp<LayerBase>& layer = layers[i];
        layer->finishPageFlip();
    }
}

void SurfaceFlinger::debugFlashRegions()
{
     const DisplayHardware& hw(graphicPlane(0).displayHardware());
     const uint32_t flags = hw.getFlags();

     if (!((flags & DisplayHardware::SWAP_RECTANGLE) ||
             (flags & DisplayHardware::BUFFER_PRESERVED))) {
         const Region repaint((flags & DisplayHardware::PARTIAL_UPDATES) ?
                 mDirtyRegion.bounds() : hw.bounds());
         composeSurfaces(repaint);
     }
    
    glDisable(GL_TEXTURE_2D);
    glDisable(GL_BLEND);
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);

    static int toggle = 0;
    toggle = 1 - toggle;
    if (toggle) {
        glColor4x(0x10000, 0, 0x10000, 0x10000);
    } else {
        glColor4x(0x10000, 0x10000, 0, 0x10000);
    }

    Region::const_iterator it = mDirtyRegion.begin();
    Region::const_iterator const end = mDirtyRegion.end();
    while (it != end) {
        const Rect& r = *it++;
        GLfloat vertices[][2] = {
                { r.left,  r.top },
                { r.left,  r.bottom },
                { r.right, r.bottom },
                { r.right, r.top }
        };
        glVertexPointer(2, GL_FLOAT, 0, vertices);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
    }
    
    if (mInvalidRegion.isEmpty()) {
        mDirtyRegion.dump("mDirtyRegion");
        mInvalidRegion.dump("mInvalidRegion");
    }
    hw.flip(mInvalidRegion);

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
        Region::const_iterator it = region.begin();
        Region::const_iterator const end = region.end();
        while (it != end) {
            const Rect& r = *it++;
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
        Region::const_iterator it = region.begin();
        Region::const_iterator const end = region.end();
        while (it != end) {
            const Rect& r = *it++;
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

status_t SurfaceFlinger::addLayer(const sp<LayerBase>& layer)
{
    Mutex::Autolock _l(mStateLock);
    addLayer_l(layer);
    setTransactionFlags(eTransactionNeeded|eTraversalNeeded);
    return NO_ERROR;
}

status_t SurfaceFlinger::removeLayer(const sp<LayerBase>& layer)
{
    Mutex::Autolock _l(mStateLock);
    status_t err = purgatorizeLayer_l(layer);
    if (err == NO_ERROR)
        setTransactionFlags(eTransactionNeeded);
    return err;
}

status_t SurfaceFlinger::invalidateLayerVisibility(const sp<LayerBase>& layer)
{
    layer->forceVisibilityTransaction();
    setTransactionFlags(eTraversalNeeded);
    return NO_ERROR;
}

status_t SurfaceFlinger::addLayer_l(const sp<LayerBase>& layer)
{
    if (layer == 0)
        return BAD_VALUE;
    ssize_t i = mCurrentState.layersSortedByZ.add(
                layer, &LayerBase::compareCurrentStateZ);
    sp<LayerBaseClient> lbc = LayerBase::dynamicCast< LayerBaseClient* >(layer.get());
    if (lbc != 0) {
        mLayerMap.add(lbc->serverIndex(), lbc);
    }
    return NO_ERROR;
}

status_t SurfaceFlinger::removeLayer_l(const sp<LayerBase>& layerBase)
{
    ssize_t index = mCurrentState.layersSortedByZ.remove(layerBase);
    if (index >= 0) {
        mLayersRemoved = true;
        sp<LayerBaseClient> layer =
            LayerBase::dynamicCast< LayerBaseClient* >(layerBase.get());
        if (layer != 0) {
            mLayerMap.removeItem(layer->serverIndex());
        }
        return NO_ERROR;
    }
    return status_t(index);
}

status_t SurfaceFlinger::purgatorizeLayer_l(const sp<LayerBase>& layerBase)
{
    // remove the layer from the main list (through a transaction).
    ssize_t err = removeLayer_l(layerBase);

    layerBase->onRemoved();

    // it's possible that we don't find a layer, because it might
    // have been destroyed already -- this is not technically an error
    // from the user because there is a race between BClient::destroySurface(),
    // ~BClient() and ~ISurface().
    return (err == NAME_NOT_FOUND) ? status_t(NO_ERROR) : err;
}


void SurfaceFlinger::free_resources_l()
{
    // free resources associated with disconnected clients
    Vector< sp<Client> >& disconnectedClients(mDisconnectedClients);
    const size_t count = disconnectedClients.size();
    for (size_t i=0 ; i<count ; i++) {
        sp<Client> client = disconnectedClients[i];
        mTokens.release(client->cid);
    }
    disconnectedClients.clear();
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

        // if there is a transaction with a resize, wait for it to 
        // take effect before returning.
        Mutex::Autolock _l(mStateLock);
        while (mResizeTransationPending) {
            status_t err = mTransactionCV.waitRelative(mStateLock, s2ns(5));
            if (CC_UNLIKELY(err != NO_ERROR)) {
                // just in case something goes wrong in SF, return to the
                // called after a few seconds.
                LOGW_IF(err == TIMED_OUT, "closeGlobalTransaction timed out!");
                mResizeTransationPending = false;
                break;
            }
        }
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
    // (for instance fading)
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
    // (for instance fading)
    return NO_ERROR;
}

int SurfaceFlinger::setOrientation(DisplayID dpy, 
        int orientation, uint32_t flags)
{
    if (UNLIKELY(uint32_t(dpy) >= DISPLAY_COUNT))
        return BAD_VALUE;

    Mutex::Autolock _l(mStateLock);
    if (mCurrentState.orientation != orientation) {
        if (uint32_t(orientation)<=eOrientation270 || orientation==42) {
            mCurrentState.orientationType = flags;
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
        const String8& name, ISurfaceFlingerClient::surface_data_t* params,
        DisplayID d, uint32_t w, uint32_t h, PixelFormat format,
        uint32_t flags)
{
    sp<LayerBaseClient> layer;
    sp<LayerBaseClient::Surface> surfaceHandle;

    if (int32_t(w|h) < 0) {
        LOGE("createSurface() failed, w or h is negative (w=%d, h=%d)",
                int(w), int(h));
        return surfaceHandle;
    }
    
    Mutex::Autolock _l(mStateLock);
    sp<Client> client = mClientsMap.valueFor(clientId);
    if (UNLIKELY(client == 0)) {
        LOGE("createSurface() failed, client not found (id=%d)", clientId);
        return surfaceHandle;
    }

    //LOGD("createSurface for pid %d (%d x %d)", pid, w, h);
    int32_t id = client->generateId(pid);
    if (uint32_t(id) >= NUM_LAYERS_MAX) {
        LOGE("createSurface() failed, generateId = %d", id);
        return surfaceHandle;
    }

    switch (flags & eFXSurfaceMask) {
        case eFXSurfaceNormal:
            if (UNLIKELY(flags & ePushBuffers)) {
                layer = createPushBuffersSurfaceLocked(client, d, id,
                        w, h, flags);
            } else {
                layer = createNormalSurfaceLocked(client, d, id,
                        w, h, flags, format);
            }
            break;
        case eFXSurfaceBlur:
            layer = createBlurSurfaceLocked(client, d, id, w, h, flags);
            break;
        case eFXSurfaceDim:
            layer = createDimSurfaceLocked(client, d, id, w, h, flags);
            break;
    }

    if (layer != 0) {
        layer->setName(name);
        setTransactionFlags(eTransactionNeeded);
        surfaceHandle = layer->getSurface();
        if (surfaceHandle != 0) { 
            params->token = surfaceHandle->getToken();
            params->identity = surfaceHandle->getIdentity();
            params->width = w;
            params->height = h;
            params->format = format;
        }
    }

    return surfaceHandle;
}

sp<LayerBaseClient> SurfaceFlinger::createNormalSurfaceLocked(
        const sp<Client>& client, DisplayID display,
        int32_t id, uint32_t w, uint32_t h, uint32_t flags,
        PixelFormat& format)
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

    sp<Layer> layer = new Layer(this, display, client, id);
    status_t err = layer->setBuffers(w, h, format, flags);
    if (LIKELY(err == NO_ERROR)) {
        layer->initStates(w, h, flags);
        addLayer_l(layer);
    } else {
        LOGE("createNormalSurfaceLocked() failed (%s)", strerror(-err));
        layer.clear();
    }
    return layer;
}

sp<LayerBaseClient> SurfaceFlinger::createBlurSurfaceLocked(
        const sp<Client>& client, DisplayID display,
        int32_t id, uint32_t w, uint32_t h, uint32_t flags)
{
    sp<LayerBlur> layer = new LayerBlur(this, display, client, id);
    layer->initStates(w, h, flags);
    addLayer_l(layer);
    return layer;
}

sp<LayerBaseClient> SurfaceFlinger::createDimSurfaceLocked(
        const sp<Client>& client, DisplayID display,
        int32_t id, uint32_t w, uint32_t h, uint32_t flags)
{
    sp<LayerDim> layer = new LayerDim(this, display, client, id);
    layer->initStates(w, h, flags);
    addLayer_l(layer);
    return layer;
}

sp<LayerBaseClient> SurfaceFlinger::createPushBuffersSurfaceLocked(
        const sp<Client>& client, DisplayID display,
        int32_t id, uint32_t w, uint32_t h, uint32_t flags)
{
    sp<LayerBuffer> layer = new LayerBuffer(this, display, client, id);
    layer->initStates(w, h, flags);
    addLayer_l(layer);
    return layer;
}

status_t SurfaceFlinger::removeSurface(SurfaceID index)
{
    /*
     * called by the window manager, when a surface should be marked for
     * destruction.
     * 
     * The surface is removed from the current and drawing lists, but placed
     * in the purgatory queue, so it's not destroyed right-away (we need
     * to wait for all client's references to go away first).
     */

    status_t err = NAME_NOT_FOUND;
    Mutex::Autolock _l(mStateLock);
    sp<LayerBaseClient> layer = getLayerUser_l(index);
    if (layer != 0) {
        err = purgatorizeLayer_l(layer);
        if (err == NO_ERROR) {
            setTransactionFlags(eTransactionNeeded);
        }
    }
    return err;
}

status_t SurfaceFlinger::destroySurface(const sp<LayerBaseClient>& layer)
{
    // called by ~ISurface() when all references are gone
    
    class MessageDestroySurface : public MessageBase {
        SurfaceFlinger* flinger;
        sp<LayerBaseClient> layer;
    public:
        MessageDestroySurface(
                SurfaceFlinger* flinger, const sp<LayerBaseClient>& layer)
            : flinger(flinger), layer(layer) { }
        virtual bool handler() {
            sp<LayerBaseClient> l(layer);
            layer.clear(); // clear it outside of the lock;
            Mutex::Autolock _l(flinger->mStateLock);
            /*
             * remove the layer from the current list -- chances are that it's 
             * not in the list anyway, because it should have been removed 
             * already upon request of the client (eg: window manager). 
             * However, a buggy client could have not done that.
             * Since we know we don't have any more clients, we don't need
             * to use the purgatory.
             */
            status_t err = flinger->removeLayer_l(l);
            LOGE_IF(err<0 && err != NAME_NOT_FOUND,
                    "error removing layer=%p (%s)", l.get(), strerror(-err));
            return true;
        }
    };

    mEventQueue.postMessage( new MessageDestroySurface(this, layer) );
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
        sp<LayerBaseClient> layer(getLayerUser_l(s.surface | cid));
        if (layer != 0) {
            const uint32_t what = s.what;
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
                if (layer->setSize(s.w, s.h)) {
                    flags |= eTraversalNeeded;
                    mResizeTransationPending = true;
                }
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

sp<LayerBaseClient> SurfaceFlinger::getLayerUser_l(SurfaceID s) const
{
    sp<LayerBaseClient> layer = mLayerMap.valueFor(s);
    return layer;
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
    if (!mDump.checkCalling()) {
        snprintf(buffer, SIZE, "Permission Denial: "
                "can't dump SurfaceFlinger from pid=%d, uid=%d\n",
                IPCThreadState::self()->getCallingPid(),
                IPCThreadState::self()->getCallingUid());
        result.append(buffer);
    } else {

        // figure out if we're stuck somewhere
        const nsecs_t now = systemTime();
        const nsecs_t inSwapBuffers(mDebugInSwapBuffers);
        const nsecs_t inTransaction(mDebugInTransaction);
        nsecs_t inSwapBuffersDuration = (inSwapBuffers) ? now-inSwapBuffers : 0;
        nsecs_t inTransactionDuration = (inTransaction) ? now-inTransaction : 0;

        // Try to get the main lock, but don't insist if we can't
        // (this would indicate SF is stuck, but we want to be able to
        // print something in dumpsys).
        int retry = 3;
        while (mStateLock.tryLock()<0 && --retry>=0) {
            usleep(1000000);
        }
        const bool locked(retry >= 0);
        if (!locked) {
            snprintf(buffer, SIZE, 
                    "SurfaceFlinger appears to be unresponsive, "
                    "dumping anyways (no locks held)\n");
            result.append(buffer);
        }

        size_t s = mClientsMap.size();
        char name[64];
        for (size_t i=0 ; i<s ; i++) {
            sp<Client> client = mClientsMap.valueAt(i);
            sprintf(name, "  Client (id=0x%08x)", client->cid);
            client->dump(name);
        }
        const LayerVector& currentLayers = mCurrentState.layersSortedByZ;
        const size_t count = currentLayers.size();
        for (size_t i=0 ; i<count ; i++) {
            /*** LayerBase ***/
            const sp<LayerBase>& layer = currentLayers[i];
            const Layer::State& s = layer->drawingState();
            snprintf(buffer, SIZE,
                    "+ %s %p\n"
                    "      "
                    "z=%9d, pos=(%4d,%4d), size=(%4d,%4d), "
                    "needsBlending=%1d, needsDithering=%1d, invalidate=%1d, "
                    "alpha=0x%02x, flags=0x%08x, tr=[%.2f, %.2f][%.2f, %.2f]\n",
                    layer->getTypeID(), layer.get(),
                    s.z, layer->tx(), layer->ty(), s.w, s.h,
                    layer->needsBlending(), layer->needsDithering(),
                    layer->contentDirty,
                    s.alpha, s.flags,
                    s.transform[0][0], s.transform[0][1],
                    s.transform[1][0], s.transform[1][1]);
            result.append(buffer);
            buffer[0] = 0;
            /*** LayerBaseClient ***/
            sp<LayerBaseClient> lbc =
                LayerBase::dynamicCast< LayerBaseClient* >(layer.get());
            if (lbc != 0) {
                sp<Client> client(lbc->client.promote());
                snprintf(buffer, SIZE,
                        "      name=%s\n", lbc->getName().string());
                result.append(buffer);
                snprintf(buffer, SIZE,
                        "      id=0x%08x, client=0x%08x, identity=%u\n",
                        lbc->clientIndex(), client.get() ? client->cid : 0,
                        lbc->getIdentity());

                result.append(buffer);
                buffer[0] = 0;
            }
            /*** Layer ***/
            sp<Layer> l = LayerBase::dynamicCast< Layer* >(layer.get());
            if (l != 0) {
                SharedBufferStack::Statistics stats = l->lcblk->getStats();
                result.append( l->lcblk->dump("      ") );
                sp<const GraphicBuffer> buf0(l->getBuffer(0));
                sp<const GraphicBuffer> buf1(l->getBuffer(1));
                uint32_t w0=0, h0=0, s0=0;
                uint32_t w1=0, h1=0, s1=0;
                if (buf0 != 0) {
                    w0 = buf0->getWidth();
                    h0 = buf0->getHeight();
                    s0 = buf0->getStride();
                }
                if (buf1 != 0) {
                    w1 = buf1->getWidth();
                    h1 = buf1->getHeight();
                    s1 = buf1->getStride();
                }
                snprintf(buffer, SIZE,
                        "      "
                        "format=%2d, [%3ux%3u:%3u] [%3ux%3u:%3u],"
                        " freezeLock=%p, dq-q-time=%u us\n",
                        l->pixelFormat(),
                        w0, h0, s0, w1, h1, s1,
                        l->getFreezeLock().get(), stats.totalTime);
                result.append(buffer);
                buffer[0] = 0;
            }
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
        snprintf(buffer, SIZE,
                "  last eglSwapBuffers() time: %f us\n"
                "  last transaction time     : %f us\n",
                mLastSwapBufferTime/1000.0, mLastTransactionTime/1000.0);
        result.append(buffer);
        if (inSwapBuffersDuration || !locked) {
            snprintf(buffer, SIZE, "  eglSwapBuffers time: %f us\n",
                    inSwapBuffersDuration/1000.0);
            result.append(buffer);
        }
        if (inTransactionDuration || !locked) {
            snprintf(buffer, SIZE, "  transaction time: %f us\n",
                    inTransactionDuration/1000.0);
            result.append(buffer);
        }
        snprintf(buffer, SIZE, "  client count: %d\n", mClientsMap.size());
        result.append(buffer);
        const GraphicBufferAllocator& alloc(GraphicBufferAllocator::get());
        alloc.dump(result);

        if (locked) {
            mStateLock.unlock();
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
        {
            // codes that require permission check
            IPCThreadState* ipc = IPCThreadState::self();
            const int pid = ipc->getCallingPid();
            const int uid = ipc->getCallingUid();
            if ((uid != AID_GRAPHICS) && !mAccessSurfaceFlinger.check(pid, uid)) {
                LOGE("Permission Denial: "
                        "can't access SurfaceFlinger pid=%d, uid=%d", pid, uid);
                return PERMISSION_DENIED;
            }
        }
    }
    status_t err = BnSurfaceComposer::onTransact(code, data, reply, flags);
    if (err == UNKNOWN_TRANSACTION || err == PERMISSION_DENIED) {
        CHECK_INTERFACE(ISurfaceComposer, data, reply);
        if (UNLIKELY(!mHardwareTest.checkCalling())) {
            IPCThreadState* ipc = IPCThreadState::self();
            const int pid = ipc->getCallingPid();
            const int uid = ipc->getCallingUid();
            LOGE("Permission Denial: "
                    "can't access SurfaceFlinger pid=%d, uid=%d", pid, uid);
            return PERMISSION_DENIED;
        }
        int n;
        switch (code) {
            case 1000: // SHOW_CPU, NOT SUPPORTED ANYMORE
                return NO_ERROR;
            case 1001:  // SHOW_FPS, NOT SUPPORTED ANYMORE
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
                return NO_ERROR;
            }
            case 1005:{ // force transaction
                setTransactionFlags(eTransactionNeeded|eTraversalNeeded);
                return NO_ERROR;
            }
            case 1007: // set mFreezeCount
                mFreezeCount = data.readInt32();
                mFreezeDisplayTime = 0;
                return NO_ERROR;
            case 1010:  // interrogate.
                reply->writeInt32(0);
                reply->writeInt32(0);
                reply->writeInt32(mDebugRegion);
                reply->writeInt32(mDebugBackground);
                return NO_ERROR;
            case 1013: {
                Mutex::Autolock _l(mStateLock);
                const DisplayHardware& hw(graphicPlane(0).displayHardware());
                reply->writeInt32(hw.getPageFlipCount());
            }
            return NO_ERROR;
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
    const int pgsize = getpagesize();
    const int cblksize = ((sizeof(SharedClient)+(pgsize-1))&~(pgsize-1));

    mCblkHeap = new MemoryHeapBase(cblksize, 0,
            "SurfaceFlinger Client control-block");

    ctrlblk = static_cast<SharedClient *>(mCblkHeap->getBase());
    if (ctrlblk) { // construct the shared structure in-place.
        new(ctrlblk) SharedClient;
    }
}

Client::~Client() {
    if (ctrlblk) {
        ctrlblk->~SharedClient();  // destroy our shared-structure.
    }
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

status_t Client::bindLayer(const sp<LayerBaseClient>& layer, int32_t id)
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

bool Client::isValid(int32_t i) const {
    return (uint32_t(i)<NUM_LAYERS_MAX) && (mBitmap & (1<<(31-i)));
}

sp<LayerBaseClient> Client::getLayerUser(int32_t i) const {
    sp<LayerBaseClient> lbc;
    ssize_t idx = mInUse.indexOf(uint8_t(i));
    if (idx >= 0) {
        lbc = mLayers[idx].promote();
        LOGE_IF(lbc==0, "getLayerUser(i=%d), idx=%d is dead", int(i), int(idx));
    }
    return lbc;
}

void Client::dump(const char* what)
{
}

// ---------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

BClient::BClient(SurfaceFlinger *flinger, ClientID cid, const sp<IMemoryHeap>& cblk)
    : mId(cid), mFlinger(flinger), mCblk(cblk)
{
}

BClient::~BClient() {
    // destroy all resources attached to this client
    mFlinger->destroyConnection(mId);
}

sp<IMemoryHeap> BClient::getControlBlock() const {
    return mCblk;
}

sp<ISurface> BClient::createSurface(
        ISurfaceFlingerClient::surface_data_t* params, int pid,
        const String8& name,
        DisplayID display, uint32_t w, uint32_t h, PixelFormat format,
        uint32_t flags)
{
    return mFlinger->createSurface(mId, pid, name, params, display, w, h,
            format, flags);
}

status_t BClient::destroySurface(SurfaceID sid)
{
    sid |= (mId << 16); // add the client-part to id
    return mFlinger->removeSurface(sid);
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

int GraphicPlane::getWidth() const {
    return mWidth;
}

int GraphicPlane::getHeight() const {
    return mHeight;
}

void GraphicPlane::setDisplayHardware(DisplayHardware *hw)
{
    mHw = hw;

    // initialize the display orientation transform.
    // it's a constant that should come from the display driver.
    int displayOrientation = ISurfaceComposer::eOrientationDefault;
    char property[PROPERTY_VALUE_MAX];
    if (property_get("ro.sf.hwrotation", property, NULL) > 0) {
        //displayOrientation
        switch (atoi(property)) {
        case 90:
            displayOrientation = ISurfaceComposer::eOrientation90;
            break;
        case 270:
            displayOrientation = ISurfaceComposer::eOrientation270;
            break;
        }
    }

    const float w = hw->getWidth();
    const float h = hw->getHeight();
    GraphicPlane::orientationToTransfrom(displayOrientation, w, h,
            &mDisplayTransform);
    if (displayOrientation & ISurfaceComposer::eOrientationSwapMask) {
        mDisplayWidth = h;
        mDisplayHeight = w;
    } else {
        mDisplayWidth = w;
        mDisplayHeight = h;
    }

    setOrientation(ISurfaceComposer::eOrientationDefault);
}

status_t GraphicPlane::orientationToTransfrom(
        int orientation, int w, int h, Transform* tr)
{
    uint32_t flags = 0;
    switch (orientation) {
    case ISurfaceComposer::eOrientationDefault:
        flags = Transform::ROT_0;
        break;
    case ISurfaceComposer::eOrientation90:
        flags = Transform::ROT_90;
        break;
    case ISurfaceComposer::eOrientation180:
        flags = Transform::ROT_180;
        break;
    case ISurfaceComposer::eOrientation270:
        flags = Transform::ROT_270;
        break;
    default:
        return BAD_VALUE;
    }
    tr->set(flags, w, h);
    return NO_ERROR;
}

status_t GraphicPlane::setOrientation(int orientation)
{
    // If the rotation can be handled in hardware, this is where
    // the magic should happen.

    const DisplayHardware& hw(displayHardware());
    const float w = mDisplayWidth;
    const float h = mDisplayHeight;
    mWidth = int(w);
    mHeight = int(h);

    Transform orientationTransform;
    GraphicPlane::orientationToTransfrom(orientation, w, h,
            &orientationTransform);
    if (orientation & ISurfaceComposer::eOrientationSwapMask) {
        mWidth = int(h);
        mHeight = int(w);
    }

    mOrientation = orientation;
    mGlobalTransform = mDisplayTransform * orientationTransform;
    return NO_ERROR;
}

const DisplayHardware& GraphicPlane::displayHardware() const {
    return *mHw;
}

const Transform& GraphicPlane::transform() const {
    return mGlobalTransform;
}

EGLDisplay GraphicPlane::getEGLDisplay() const {
    return mHw->getEGLDisplay();
}

// ---------------------------------------------------------------------------

}; // namespace android
