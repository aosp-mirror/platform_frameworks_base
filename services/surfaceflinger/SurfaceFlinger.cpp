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
#include <binder/PermissionCache.h>

#include <gui/IDisplayEventConnection.h>

#include <utils/String8.h>
#include <utils/String16.h>
#include <utils/StopWatch.h>

#include <ui/GraphicBufferAllocator.h>
#include <ui/PixelFormat.h>

#include <pixelflinger/pixelflinger.h>
#include <GLES/gl.h>

#include "clz.h"
#include "DisplayEventConnection.h"
#include "EventThread.h"
#include "GLExtensions.h"
#include "DdmConnection.h"
#include "Layer.h"
#include "LayerDim.h"
#include "LayerScreenshot.h"
#include "SurfaceFlinger.h"

#include "DisplayHardware/DisplayHardware.h"
#include "DisplayHardware/HWComposer.h"

#include <private/android_filesystem_config.h>
#include <private/surfaceflinger/SharedBufferStack.h>

#define EGL_VERSION_HW_ANDROID  0x3143

#define DISPLAY_COUNT       1

namespace android {
// ---------------------------------------------------------------------------

const String16 sHardwareTest("android.permission.HARDWARE_TEST");
const String16 sAccessSurfaceFlinger("android.permission.ACCESS_SURFACE_FLINGER");
const String16 sReadFramebuffer("android.permission.READ_FRAME_BUFFER");
const String16 sDump("android.permission.DUMP");

// ---------------------------------------------------------------------------

SurfaceFlinger::SurfaceFlinger()
    :   BnSurfaceComposer(), Thread(false),
        mTransactionFlags(0),
        mTransationPending(false),
        mLayersRemoved(false),
        mBootTime(systemTime()),
        mVisibleRegionsDirty(false),
        mHwWorkListDirty(false),
        mElectronBeamAnimationMode(0),
        mDebugRegion(0),
        mDebugBackground(0),
        mDebugDDMS(0),
        mDebugDisableHWC(0),
        mDebugDisableTransformHint(0),
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
    ALOGI("SurfaceFlinger is starting");

    // debugging stuff...
    char value[PROPERTY_VALUE_MAX];

    property_get("debug.sf.showupdates", value, "0");
    mDebugRegion = atoi(value);

    property_get("debug.sf.showbackground", value, "0");
    mDebugBackground = atoi(value);

    property_get("debug.sf.ddms", value, "0");
    mDebugDDMS = atoi(value);
    if (mDebugDDMS) {
        DdmConnection::start(getServiceName());
    }

    ALOGI_IF(mDebugRegion,       "showupdates enabled");
    ALOGI_IF(mDebugBackground,   "showbackground enabled");
    ALOGI_IF(mDebugDDMS,         "DDMS debugging enabled");
}

SurfaceFlinger::~SurfaceFlinger()
{
    glDeleteTextures(1, &mWormholeTexName);
}

sp<IMemoryHeap> SurfaceFlinger::getCblk() const
{
    return mServerHeap;
}

sp<ISurfaceComposerClient> SurfaceFlinger::createConnection()
{
    sp<ISurfaceComposerClient> bclient;
    sp<Client> client(new Client(this));
    status_t err = client->initCheck();
    if (err == NO_ERROR) {
        bclient = client;
    }
    return bclient;
}

sp<IGraphicBufferAlloc> SurfaceFlinger::createGraphicBufferAlloc()
{
    sp<GraphicBufferAlloc> gba(new GraphicBufferAlloc());
    return gba;
}

const GraphicPlane& SurfaceFlinger::graphicPlane(int dpy) const
{
    ALOGE_IF(uint32_t(dpy) >= DISPLAY_COUNT, "Invalid DisplayID %d", dpy);
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
    ALOGI("Boot is finished (%ld ms)", long(ns2ms(duration)) );
    mBootFinished = true;

    // wait patiently for the window manager death
    const String16 name("window");
    sp<IBinder> window(defaultServiceManager()->getService(name));
    if (window != 0) {
        window->linkToDeath(this);
    }

    // stop boot animation
    property_set("ctl.stop", "bootanim");
}

void SurfaceFlinger::binderDied(const wp<IBinder>& who)
{
    // the window manager died on us. prepare its eulogy.

    // reset screen orientation
    setOrientation(0, eOrientationDefault, 0);

    // restart the boot-animation
    property_set("ctl.start", "bootanim");
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
    ALOGI(   "SurfaceFlinger's main thread ready to run. "
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
    ALOGE_IF(mServerHeap==0, "can't create shared memory dealer");

    mServerCblk = static_cast<surface_flinger_cblk_t*>(mServerHeap->getBase());
    ALOGE_IF(mServerCblk==0, "can't get to shared control block's address");

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

    // Initialize OpenGL|ES
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glEnableClientState(GL_VERTEX_ARRAY);
    glEnable(GL_SCISSOR_TEST);
    glShadeModel(GL_FLAT);
    glDisable(GL_DITHER);
    glDisable(GL_CULL_FACE);

    const uint16_t g0 = pack565(0x0F,0x1F,0x0F);
    const uint16_t g1 = pack565(0x17,0x2f,0x17);
    const uint16_t wormholeTexData[4] = { g0, g1, g1, g0 };
    glGenTextures(1, &mWormholeTexName);
    glBindTexture(GL_TEXTURE_2D, mWormholeTexName);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, 2, 2, 0,
            GL_RGB, GL_UNSIGNED_SHORT_5_6_5, wormholeTexData);

    const uint16_t protTexData[] = { pack565(0x03, 0x03, 0x03) };
    glGenTextures(1, &mProtectedTexName);
    glBindTexture(GL_TEXTURE_2D, mProtectedTexName);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, 1, 1, 0,
            GL_RGB, GL_UNSIGNED_SHORT_5_6_5, protTexData);

    glViewport(0, 0, w, h);
    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    // put the origin in the left-bottom corner
    glOrthof(0, w, 0, h, 0, 1); // l=0, r=w ; b=0, t=h


    // start the EventThread
    mEventThread = new EventThread(this);

    /*
     *  We're now ready to accept clients...
     */

    mReadyToRunBarrier.open();

    // start boot animation
    property_set("ctl.start", "bootanim");

    return NO_ERROR;
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Events Handler
#endif

void SurfaceFlinger::waitForEvent() {
    mEventQueue.waitMessage();
}

void SurfaceFlinger::signalEvent() {
    mEventQueue.invalidate();
}

status_t SurfaceFlinger::postMessageAsync(const sp<MessageBase>& msg,
        nsecs_t reltime, uint32_t flags) {
    return mEventQueue.postMessage(msg, reltime);
}

status_t SurfaceFlinger::postMessageSync(const sp<MessageBase>& msg,
        nsecs_t reltime, uint32_t flags) {
    status_t res = mEventQueue.postMessage(msg, reltime);
    if (res == NO_ERROR) {
        msg->wait();
    }
    return res;
}

// ----------------------------------------------------------------------------

bool SurfaceFlinger::authenticateSurfaceTexture(
        const sp<ISurfaceTexture>& surfaceTexture) const {
    Mutex::Autolock _l(mStateLock);
    sp<IBinder> surfaceTextureBinder(surfaceTexture->asBinder());

    // Check the visible layer list for the ISurface
    const LayerVector& currentLayers = mCurrentState.layersSortedByZ;
    size_t count = currentLayers.size();
    for (size_t i=0 ; i<count ; i++) {
        const sp<LayerBase>& layer(currentLayers[i]);
        sp<LayerBaseClient> lbc(layer->getLayerBaseClient());
        if (lbc != NULL) {
            wp<IBinder> lbcBinder = lbc->getSurfaceTextureBinder();
            if (lbcBinder == surfaceTextureBinder) {
                return true;
            }
        }
    }

    // Check the layers in the purgatory.  This check is here so that if a
    // SurfaceTexture gets destroyed before all the clients are done using it,
    // the error will not be reported as "surface XYZ is not authenticated", but
    // will instead fail later on when the client tries to use the surface,
    // which should be reported as "surface XYZ returned an -ENODEV".  The
    // purgatorized layers are no less authentic than the visible ones, so this
    // should not cause any harm.
    size_t purgatorySize =  mLayerPurgatory.size();
    for (size_t i=0 ; i<purgatorySize ; i++) {
        const sp<LayerBase>& layer(mLayerPurgatory.itemAt(i));
        sp<LayerBaseClient> lbc(layer->getLayerBaseClient());
        if (lbc != NULL) {
            wp<IBinder> lbcBinder = lbc->getSurfaceTextureBinder();
            if (lbcBinder == surfaceTextureBinder) {
                return true;
            }
        }
    }

    return false;
}

// ----------------------------------------------------------------------------

sp<IDisplayEventConnection> SurfaceFlinger::createDisplayEventConnection() {
    sp<DisplayEventConnection> result(new DisplayEventConnection(mEventThread));
    return result;
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
    if (CC_UNLIKELY(mConsoleSignals)) {
        handleConsoleEvents();
    }

    // if we're in a global transaction, don't do anything.
    const uint32_t mask = eTransactionNeeded | eTraversalNeeded;
    uint32_t transactionFlags = peekTransactionFlags(mask);
    if (CC_UNLIKELY(transactionFlags)) {
        handleTransaction(transactionFlags);
    }

    // post surfaces (if needed)
    handlePageFlip();

    if (mDirtyRegion.isEmpty()) {
        // nothing new to do.
        return true;
    }

    if (CC_UNLIKELY(mHwWorkListDirty)) {
        // build the h/w work list
        handleWorkList();
    }

    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    if (CC_LIKELY(hw.canDraw())) {
        // repaint the framebuffer (if needed)
        handleRepaint();
        // inform the h/w that we're done compositing
        hw.compositionComplete();
        postFramebuffer();
    } else {
        // pretend we did the post
        hw.compositionComplete();
        hw.waitForRefresh();
    }
    return true;
}

void SurfaceFlinger::postFramebuffer()
{
    // this should never happen. we do the flip anyways so we don't
    // risk to cause a deadlock with hwc
    ALOGW_IF(mSwapRegion.isEmpty(), "mSwapRegion is empty");
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const nsecs_t now = systemTime();
    mDebugInSwapBuffers = now;
    hw.flip(mSwapRegion);

    size_t numLayers = mVisibleLayersSortedByZ.size();
    for (size_t i = 0; i < numLayers; i++) {
        mVisibleLayersSortedByZ[i]->onLayerDisplayed();
    }

    mLastSwapBufferTime = systemTime() - now;
    mDebugInSwapBuffers = 0;
    mSwapRegion.clear();
}

void SurfaceFlinger::handleConsoleEvents()
{
    // something to do with the console
    const DisplayHardware& hw = graphicPlane(0).displayHardware();

    int what = android_atomic_and(0, &mConsoleSignals);
    if (what & eConsoleAcquired) {
        hw.acquireScreen();
        // this is a temporary work-around, eventually this should be called
        // by the power-manager
        SurfaceFlinger::turnElectronBeamOn(mElectronBeamAnimationMode);
    }

    if (what & eConsoleReleased) {
        if (hw.isScreenAcquired()) {
            hw.releaseScreen();
        }
    }

    mDirtyRegion.set(hw.bounds());
}

void SurfaceFlinger::handleTransaction(uint32_t transactionFlags)
{
    Mutex::Autolock _l(mStateLock);
    const nsecs_t now = systemTime();
    mDebugInTransaction = now;

    // Here we're guaranteed that some transaction flags are set
    // so we can call handleTransactionLocked() unconditionally.
    // We call getTransactionFlags(), which will also clear the flags,
    // with mStateLock held to guarantee that mCurrentState won't change
    // until the transaction is committed.

    const uint32_t mask = eTransactionNeeded | eTraversalNeeded;
    transactionFlags = getTransactionFlags(mask);
    handleTransactionLocked(transactionFlags);

    mLastTransactionTime = systemTime() - now;
    mDebugInTransaction = 0;
    invalidateHwcGeometry();
    // here the transaction has been committed
}

void SurfaceFlinger::handleTransactionLocked(uint32_t transactionFlags)
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
            // Currently unused: const uint32_t flags = mCurrentState.orientationFlags;
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
                    mDirtyRegionRemovedLayer.orSelf(layer->visibleRegionScreen);
                }
            }
        }
    }

    commitTransaction();
}

void SurfaceFlinger::computeVisibleRegions(
    const LayerVector& currentLayers, Region& dirtyRegion, Region& opaqueRegion)
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
        if (CC_LIKELY(!(s.flags & ISurfaceComposer::eLayerHidden) && s.alpha)) {
            const bool translucent = !layer->isOpaque();
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
    if (!mLayersPendingRemoval.isEmpty()) {
        // Notify removed layers now that they can't be drawn from
        for (size_t i = 0; i < mLayersPendingRemoval.size(); i++) {
            mLayersPendingRemoval[i]->onRemoved();
        }
        mLayersPendingRemoval.clear();
    }

    mDrawingState = mCurrentState;
    mTransationPending = false;
    mTransactionCV.broadcast();
}

void SurfaceFlinger::handlePageFlip()
{
    bool visibleRegions = mVisibleRegionsDirty;
    const LayerVector& currentLayers(mDrawingState.layersSortedByZ);
    visibleRegions |= lockPageFlip(currentLayers);

        const DisplayHardware& hw = graphicPlane(0).displayHardware();
        const Region screenRegion(hw.bounds());
        if (visibleRegions) {
            Region opaqueRegion;
            computeVisibleRegions(currentLayers, mDirtyRegion, opaqueRegion);

            /*
             *  rebuild the visible layer list
             */
            const size_t count = currentLayers.size();
            mVisibleLayersSortedByZ.clear();
            mVisibleLayersSortedByZ.setCapacity(count);
            for (size_t i=0 ; i<count ; i++) {
                if (!currentLayers[i]->visibleRegionScreen.isEmpty())
                    mVisibleLayersSortedByZ.add(currentLayers[i]);
            }

            mWormholeRegion = screenRegion.subtract(opaqueRegion);
            mVisibleRegionsDirty = false;
            invalidateHwcGeometry();
        }

    unlockPageFlip(currentLayers);

    mDirtyRegion.orSelf(getAndClearInvalidateRegion());
    mDirtyRegion.andSelf(screenRegion);
}

void SurfaceFlinger::invalidateHwcGeometry()
{
    mHwWorkListDirty = true;
}

bool SurfaceFlinger::lockPageFlip(const LayerVector& currentLayers)
{
    bool recomputeVisibleRegions = false;
    size_t count = currentLayers.size();
    sp<LayerBase> const* layers = currentLayers.array();
    for (size_t i=0 ; i<count ; i++) {
        const sp<LayerBase>& layer(layers[i]);
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
        const sp<LayerBase>& layer(layers[i]);
        layer->unlockPageFlip(planeTransform, mDirtyRegion);
    }
}

void SurfaceFlinger::handleWorkList()
{
    mHwWorkListDirty = false;
    HWComposer& hwc(graphicPlane(0).displayHardware().getHwComposer());
    if (hwc.initCheck() == NO_ERROR) {
        const Vector< sp<LayerBase> >& currentLayers(mVisibleLayersSortedByZ);
        const size_t count = currentLayers.size();
        hwc.createWorkList(count);
        hwc_layer_t* const cur(hwc.getLayers());
        for (size_t i=0 ; cur && i<count ; i++) {
            currentLayers[i]->setGeometry(&cur[i]);
            if (mDebugDisableHWC || mDebugRegion) {
                cur[i].compositionType = HWC_FRAMEBUFFER;
                cur[i].flags |= HWC_SKIP_LAYER;
            }
        }
    }
}

void SurfaceFlinger::handleRepaint()
{
    // compute the invalid region
    mSwapRegion.orSelf(mDirtyRegion);

    if (CC_UNLIKELY(mDebugRegion)) {
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
            // TODO: we really should be able to pass a region to
            // SWAP_RECTANGLE so that we don't have to redraw all this.
            mDirtyRegion.set(mSwapRegion.bounds());
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
            mDirtyRegion.set(mSwapRegion.bounds());
        } else {
            // we need to redraw everything (the whole screen)
            mDirtyRegion.set(hw.bounds());
            mSwapRegion = mDirtyRegion;
        }
    }

    setupHardwareComposer(mDirtyRegion);
    composeSurfaces(mDirtyRegion);

    // update the swap region and clear the dirty region
    mSwapRegion.orSelf(mDirtyRegion);
    mDirtyRegion.clear();
}

void SurfaceFlinger::setupHardwareComposer(Region& dirtyInOut)
{
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    HWComposer& hwc(hw.getHwComposer());
    hwc_layer_t* const cur(hwc.getLayers());
    if (!cur) {
        return;
    }

    const Vector< sp<LayerBase> >& layers(mVisibleLayersSortedByZ);
    size_t count = layers.size();

    ALOGE_IF(hwc.getNumLayers() != count,
            "HAL number of layers (%d) doesn't match surfaceflinger (%d)",
            hwc.getNumLayers(), count);

    // just to be extra-safe, use the smallest count
    if (hwc.initCheck() == NO_ERROR) {
        count = count < hwc.getNumLayers() ? count : hwc.getNumLayers();
    }

    /*
     *  update the per-frame h/w composer data for each layer
     *  and build the transparent region of the FB
     */
    for (size_t i=0 ; i<count ; i++) {
        const sp<LayerBase>& layer(layers[i]);
        layer->setPerFrameData(&cur[i]);
    }
    const size_t fbLayerCount = hwc.getLayerCount(HWC_FRAMEBUFFER);
    status_t err = hwc.prepare();
    ALOGE_IF(err, "HWComposer::prepare failed (%s)", strerror(-err));

    if (err == NO_ERROR) {
        // what's happening here is tricky.
        // we want to clear all the layers with the CLEAR_FB flags
        // that are opaque.
        // however, since some GPU are efficient at preserving
        // the backbuffer, we want to take advantage of that so we do the
        // clear only in the dirty region (other areas will be preserved
        // on those GPUs).
        //   NOTE: on non backbuffer preserving GPU, the dirty region
        //   has already been expanded as needed, so the code is correct
        //   there too.
        //
        // However, the content of the framebuffer cannot be trusted when
        // we switch to/from FB/OVERLAY, in which case we need to
        // expand the dirty region to those areas too.
        //
        // Note also that there is a special case when switching from
        // "no layers in FB" to "some layers in FB", where we need to redraw
        // the entire FB, since some areas might contain uninitialized
        // data.
        //
        // Also we want to make sure to not clear areas that belong to
        // layers above that won't redraw (we would just be erasing them),
        // that is, we can't erase anything outside the dirty region.

        Region transparent;

        if (!fbLayerCount && hwc.getLayerCount(HWC_FRAMEBUFFER)) {
            transparent.set(hw.getBounds());
            dirtyInOut = transparent;
        } else {
            for (size_t i=0 ; i<count ; i++) {
                const sp<LayerBase>& layer(layers[i]);
                if ((cur[i].hints & HWC_HINT_CLEAR_FB) && layer->isOpaque()) {
                    transparent.orSelf(layer->visibleRegionScreen);
                }
                bool isOverlay = (cur[i].compositionType != HWC_FRAMEBUFFER);
                if (isOverlay != layer->isOverlay()) {
                    // we transitioned to/from overlay, so add this layer
                    // to the dirty region so the framebuffer can be either
                    // cleared or redrawn.
                    dirtyInOut.orSelf(layer->visibleRegionScreen);
                }
                layer->setOverlay(isOverlay);
            }
            // don't erase stuff outside the dirty region
            transparent.andSelf(dirtyInOut);
        }

        /*
         *  clear the area of the FB that need to be transparent
         */
        if (!transparent.isEmpty()) {
            glClearColor(0,0,0,0);
            Region::const_iterator it = transparent.begin();
            Region::const_iterator const end = transparent.end();
            const int32_t height = hw.getHeight();
            while (it != end) {
                const Rect& r(*it++);
                const GLint sy = height - (r.top + r.height());
                glScissor(r.left, sy, r.width(), r.height());
                glClear(GL_COLOR_BUFFER_BIT);
            }
        }
    }
}

void SurfaceFlinger::composeSurfaces(const Region& dirty)
{
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    HWComposer& hwc(hw.getHwComposer());

    const size_t fbLayerCount = hwc.getLayerCount(HWC_FRAMEBUFFER);
    if (CC_UNLIKELY(fbLayerCount && !mWormholeRegion.isEmpty())) {
        // should never happen unless the window manager has a bug
        // draw something...
        drawWormhole();
    }

    /*
     * and then, render the layers targeted at the framebuffer
     */
    hwc_layer_t* const cur(hwc.getLayers());
    const Vector< sp<LayerBase> >& layers(mVisibleLayersSortedByZ);
    size_t count = layers.size();
    for (size_t i=0 ; i<count ; i++) {
        if (cur && (cur[i].compositionType != HWC_FRAMEBUFFER)) {
            continue;
        }
        const sp<LayerBase>& layer(layers[i]);
        const Region clip(dirty.intersect(layer->visibleRegionScreen));
        if (!clip.isEmpty()) {
            layer->draw(clip);
        }
    }
}

void SurfaceFlinger::debugFlashRegions()
{
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const uint32_t flags = hw.getFlags();
    const int32_t height = hw.getHeight();
    if (mSwapRegion.isEmpty()) {
        return;
    }

    if (!((flags & DisplayHardware::SWAP_RECTANGLE) ||
            (flags & DisplayHardware::BUFFER_PRESERVED))) {
        const Region repaint((flags & DisplayHardware::PARTIAL_UPDATES) ?
                mDirtyRegion.bounds() : hw.bounds());
        composeSurfaces(repaint);
    }

    glDisable(GL_TEXTURE_EXTERNAL_OES);
    glDisable(GL_TEXTURE_2D);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);

    static int toggle = 0;
    toggle = 1 - toggle;
    if (toggle) {
        glColor4f(1, 0, 1, 1);
    } else {
        glColor4f(1, 1, 0, 1);
    }

    Region::const_iterator it = mDirtyRegion.begin();
    Region::const_iterator const end = mDirtyRegion.end();
    while (it != end) {
        const Rect& r = *it++;
        GLfloat vertices[][2] = {
                { r.left,  height - r.top },
                { r.left,  height - r.bottom },
                { r.right, height - r.bottom },
                { r.right, height - r.top }
        };
        glVertexPointer(2, GL_FLOAT, 0, vertices);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
    }

    hw.flip(mSwapRegion);

    if (mDebugRegion > 1)
        usleep(mDebugRegion * 1000);

    glEnable(GL_SCISSOR_TEST);
}

void SurfaceFlinger::drawWormhole() const
{
    const Region region(mWormholeRegion.intersect(mDirtyRegion));
    if (region.isEmpty())
        return;

    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const int32_t width = hw.getWidth();
    const int32_t height = hw.getHeight();

    if (CC_LIKELY(!mDebugBackground)) {
        glClearColor(0,0,0,0);
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

        glDisable(GL_TEXTURE_EXTERNAL_OES);
        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, mWormholeTexName);
        glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
        glMatrixMode(GL_TEXTURE);
        glLoadIdentity();

        glDisable(GL_BLEND);

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
        glDisable(GL_TEXTURE_2D);
        glLoadIdentity();
        glMatrixMode(GL_MODELVIEW);
    }
}

status_t SurfaceFlinger::addLayer(const sp<LayerBase>& layer)
{
    Mutex::Autolock _l(mStateLock);
    addLayer_l(layer);
    setTransactionFlags(eTransactionNeeded|eTraversalNeeded);
    return NO_ERROR;
}

status_t SurfaceFlinger::addLayer_l(const sp<LayerBase>& layer)
{
    ssize_t i = mCurrentState.layersSortedByZ.add(layer);
    return (i < 0) ? status_t(i) : status_t(NO_ERROR);
}

ssize_t SurfaceFlinger::addClientLayer(const sp<Client>& client,
        const sp<LayerBaseClient>& lbc)
{
    // attach this layer to the client
    size_t name = client->attachLayer(lbc);

    Mutex::Autolock _l(mStateLock);

    // add this layer to the current state list
    addLayer_l(lbc);

    return ssize_t(name);
}

status_t SurfaceFlinger::removeLayer(const sp<LayerBase>& layer)
{
    Mutex::Autolock _l(mStateLock);
    status_t err = purgatorizeLayer_l(layer);
    if (err == NO_ERROR)
        setTransactionFlags(eTransactionNeeded);
    return err;
}

status_t SurfaceFlinger::removeLayer_l(const sp<LayerBase>& layerBase)
{
    sp<LayerBaseClient> lbc(layerBase->getLayerBaseClient());
    if (lbc != 0) {
        mLayerMap.removeItem( lbc->getSurfaceBinder() );
    }
    ssize_t index = mCurrentState.layersSortedByZ.remove(layerBase);
    if (index >= 0) {
        mLayersRemoved = true;
        return NO_ERROR;
    }
    return status_t(index);
}

status_t SurfaceFlinger::purgatorizeLayer_l(const sp<LayerBase>& layerBase)
{
    // First add the layer to the purgatory list, which makes sure it won't
    // go away, then remove it from the main list (through a transaction).
    ssize_t err = removeLayer_l(layerBase);
    if (err >= 0) {
        mLayerPurgatory.add(layerBase);
    }

    mLayersPendingRemoval.push(layerBase);

    // it's possible that we don't find a layer, because it might
    // have been destroyed already -- this is not technically an error
    // from the user because there is a race between Client::destroySurface(),
    // ~Client() and ~ISurface().
    return (err == NAME_NOT_FOUND) ? status_t(NO_ERROR) : err;
}

status_t SurfaceFlinger::invalidateLayerVisibility(const sp<LayerBase>& layer)
{
    layer->forceVisibilityTransaction();
    setTransactionFlags(eTraversalNeeded);
    return NO_ERROR;
}

uint32_t SurfaceFlinger::peekTransactionFlags(uint32_t flags)
{
    return android_atomic_release_load(&mTransactionFlags);
}

uint32_t SurfaceFlinger::getTransactionFlags(uint32_t flags)
{
    return android_atomic_and(~flags, &mTransactionFlags) & flags;
}

uint32_t SurfaceFlinger::setTransactionFlags(uint32_t flags)
{
    uint32_t old = android_atomic_or(flags, &mTransactionFlags);
    if ((old & flags)==0) { // wake the server up
        signalEvent();
    }
    return old;
}


void SurfaceFlinger::setTransactionState(const Vector<ComposerState>& state,
        int orientation, uint32_t flags) {
    Mutex::Autolock _l(mStateLock);

    uint32_t transactionFlags = 0;
    if (mCurrentState.orientation != orientation) {
        if (uint32_t(orientation)<=eOrientation270 || orientation==42) {
            mCurrentState.orientation = orientation;
            transactionFlags |= eTransactionNeeded;
        } else if (orientation != eOrientationUnchanged) {
            ALOGW("setTransactionState: ignoring unrecognized orientation: %d",
                    orientation);
        }
    }

    const size_t count = state.size();
    for (size_t i=0 ; i<count ; i++) {
        const ComposerState& s(state[i]);
        sp<Client> client( static_cast<Client *>(s.client.get()) );
        transactionFlags |= setClientStateLocked(client, s.state);
    }

    if (transactionFlags) {
        // this triggers the transaction
        setTransactionFlags(transactionFlags);

        // if this is a synchronous transaction, wait for it to take effect
        // before returning.
        if (flags & eSynchronous) {
            mTransationPending = true;
        }
        while (mTransationPending) {
            status_t err = mTransactionCV.waitRelative(mStateLock, s2ns(5));
            if (CC_UNLIKELY(err != NO_ERROR)) {
                // just in case something goes wrong in SF, return to the
                // called after a few seconds.
                ALOGW_IF(err == TIMED_OUT, "closeGlobalTransaction timed out!");
                mTransationPending = false;
                break;
            }
        }
    }
}

int SurfaceFlinger::setOrientation(DisplayID dpy,
        int orientation, uint32_t flags)
{
    if (CC_UNLIKELY(uint32_t(dpy) >= DISPLAY_COUNT))
        return BAD_VALUE;

    Mutex::Autolock _l(mStateLock);
    if (mCurrentState.orientation != orientation) {
        if (uint32_t(orientation)<=eOrientation270 || orientation==42) {
            mCurrentState.orientationFlags = flags;
            mCurrentState.orientation = orientation;
            setTransactionFlags(eTransactionNeeded);
            mTransactionCV.wait(mStateLock);
        } else {
            orientation = BAD_VALUE;
        }
    }
    return orientation;
}

sp<ISurface> SurfaceFlinger::createSurface(
        ISurfaceComposerClient::surface_data_t* params,
        const String8& name,
        const sp<Client>& client,
        DisplayID d, uint32_t w, uint32_t h, PixelFormat format,
        uint32_t flags)
{
    sp<LayerBaseClient> layer;
    sp<ISurface> surfaceHandle;

    if (int32_t(w|h) < 0) {
        ALOGE("createSurface() failed, w or h is negative (w=%d, h=%d)",
                int(w), int(h));
        return surfaceHandle;
    }

    //ALOGD("createSurface for pid %d (%d x %d)", pid, w, h);
    sp<Layer> normalLayer;
    switch (flags & eFXSurfaceMask) {
        case eFXSurfaceNormal:
            normalLayer = createNormalSurface(client, d, w, h, flags, format);
            layer = normalLayer;
            break;
        case eFXSurfaceBlur:
            // for now we treat Blur as Dim, until we can implement it
            // efficiently.
        case eFXSurfaceDim:
            layer = createDimSurface(client, d, w, h, flags);
            break;
        case eFXSurfaceScreenshot:
            layer = createScreenshotSurface(client, d, w, h, flags);
            break;
    }

    if (layer != 0) {
        layer->initStates(w, h, flags);
        layer->setName(name);
        ssize_t token = addClientLayer(client, layer);

        surfaceHandle = layer->getSurface();
        if (surfaceHandle != 0) {
            params->token = token;
            params->identity = layer->getIdentity();
            if (normalLayer != 0) {
                Mutex::Autolock _l(mStateLock);
                mLayerMap.add(layer->getSurfaceBinder(), normalLayer);
            }
        }

        setTransactionFlags(eTransactionNeeded);
    }

    return surfaceHandle;
}

sp<Layer> SurfaceFlinger::createNormalSurface(
        const sp<Client>& client, DisplayID display,
        uint32_t w, uint32_t h, uint32_t flags,
        PixelFormat& format)
{
    // initialize the surfaces
    switch (format) { // TODO: take h/w into account
    case PIXEL_FORMAT_TRANSPARENT:
    case PIXEL_FORMAT_TRANSLUCENT:
        format = PIXEL_FORMAT_RGBA_8888;
        break;
    case PIXEL_FORMAT_OPAQUE:
#ifdef NO_RGBX_8888
        format = PIXEL_FORMAT_RGB_565;
#else
        format = PIXEL_FORMAT_RGBX_8888;
#endif
        break;
    }

#ifdef NO_RGBX_8888
    if (format == PIXEL_FORMAT_RGBX_8888)
        format = PIXEL_FORMAT_RGBA_8888;
#endif

    sp<Layer> layer = new Layer(this, display, client);
    status_t err = layer->setBuffers(w, h, format, flags);
    if (CC_LIKELY(err != NO_ERROR)) {
        ALOGE("createNormalSurfaceLocked() failed (%s)", strerror(-err));
        layer.clear();
    }
    return layer;
}

sp<LayerDim> SurfaceFlinger::createDimSurface(
        const sp<Client>& client, DisplayID display,
        uint32_t w, uint32_t h, uint32_t flags)
{
    sp<LayerDim> layer = new LayerDim(this, display, client);
    return layer;
}

sp<LayerScreenshot> SurfaceFlinger::createScreenshotSurface(
        const sp<Client>& client, DisplayID display,
        uint32_t w, uint32_t h, uint32_t flags)
{
    sp<LayerScreenshot> layer = new LayerScreenshot(this, display, client);
    return layer;
}

status_t SurfaceFlinger::removeSurface(const sp<Client>& client, SurfaceID sid)
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
    sp<LayerBaseClient> layer = client->getLayerUser(sid);
    if (layer != 0) {
        err = purgatorizeLayer_l(layer);
        if (err == NO_ERROR) {
            setTransactionFlags(eTransactionNeeded);
        }
    }
    return err;
}

status_t SurfaceFlinger::destroySurface(const wp<LayerBaseClient>& layer)
{
    // called by ~ISurface() when all references are gone
    status_t err = NO_ERROR;
    sp<LayerBaseClient> l(layer.promote());
    if (l != NULL) {
        Mutex::Autolock _l(mStateLock);
        err = removeLayer_l(l);
        if (err == NAME_NOT_FOUND) {
            // The surface wasn't in the current list, which means it was
            // removed already, which means it is in the purgatory,
            // and need to be removed from there.
            ssize_t idx = mLayerPurgatory.remove(l);
            ALOGE_IF(idx < 0,
                    "layer=%p is not in the purgatory list", l.get());
        }
        ALOGE_IF(err<0 && err != NAME_NOT_FOUND,
                "error removing layer=%p (%s)", l.get(), strerror(-err));
    }
    return err;
}

uint32_t SurfaceFlinger::setClientStateLocked(
        const sp<Client>& client,
        const layer_state_t& s)
{
    uint32_t flags = 0;
    sp<LayerBaseClient> layer(client->getLayerUser(s.surface));
    if (layer != 0) {
        const uint32_t what = s.what;
        if (what & ePositionChanged) {
            if (layer->setPosition(s.x, s.y))
                flags |= eTraversalNeeded;
        }
        if (what & eLayerChanged) {
            ssize_t idx = mCurrentState.layersSortedByZ.indexOf(layer);
            if (layer->setLayer(s.z)) {
                mCurrentState.layersSortedByZ.removeAt(idx);
                mCurrentState.layersSortedByZ.add(layer);
                // we need traversal (state changed)
                // AND transaction (list changed)
                flags |= eTransactionNeeded|eTraversalNeeded;
            }
        }
        if (what & eSizeChanged) {
            if (layer->setSize(s.w, s.h)) {
                flags |= eTraversalNeeded;
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
    return flags;
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
    const size_t SIZE = 4096;
    char buffer[SIZE];
    String8 result;

    if (!PermissionCache::checkCallingPermission(sDump)) {
        snprintf(buffer, SIZE, "Permission Denial: "
                "can't dump SurfaceFlinger from pid=%d, uid=%d\n",
                IPCThreadState::self()->getCallingPid(),
                IPCThreadState::self()->getCallingUid());
        result.append(buffer);
    } else {
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

        bool dumpAll = true;
        size_t index = 0;
        if (args.size()) {
            dumpAll = false;
            if (args[index] == String16("--latency")) {
                index++;
                dumpStatsLocked(args, index, result, buffer, SIZE);
            }
        }

        if (dumpAll) {
            dumpAllLocked(result, buffer, SIZE);
        }

        if (locked) {
            mStateLock.unlock();
        }
    }
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

void SurfaceFlinger::dumpStatsLocked(const Vector<String16>& args, size_t& index,
        String8& result, char* buffer, size_t SIZE) const
{
    String8 name;
    if (index < args.size()) {
        name = String8(args[index]);
        index++;
    }

    const LayerVector& currentLayers = mCurrentState.layersSortedByZ;
    const size_t count = currentLayers.size();
    for (size_t i=0 ; i<count ; i++) {
        const sp<LayerBase>& layer(currentLayers[i]);
        if (name.isEmpty()) {
            snprintf(buffer, SIZE, "%s\n", layer->getName().string());
            result.append(buffer);
        }
        if (name.isEmpty() || (name == layer->getName())) {
            layer->dumpStats(result, buffer, SIZE);
        }
    }
}

void SurfaceFlinger::dumpAllLocked(
        String8& result, char* buffer, size_t SIZE) const
{
    // figure out if we're stuck somewhere
    const nsecs_t now = systemTime();
    const nsecs_t inSwapBuffers(mDebugInSwapBuffers);
    const nsecs_t inTransaction(mDebugInTransaction);
    nsecs_t inSwapBuffersDuration = (inSwapBuffers) ? now-inSwapBuffers : 0;
    nsecs_t inTransactionDuration = (inTransaction) ? now-inTransaction : 0;

    /*
     * Dump the visible layer list
     */
    const LayerVector& currentLayers = mCurrentState.layersSortedByZ;
    const size_t count = currentLayers.size();
    snprintf(buffer, SIZE, "Visible layers (count = %d)\n", count);
    result.append(buffer);
    for (size_t i=0 ; i<count ; i++) {
        const sp<LayerBase>& layer(currentLayers[i]);
        layer->dump(result, buffer, SIZE);
    }

    /*
     * Dump the layers in the purgatory
     */

    const size_t purgatorySize = mLayerPurgatory.size();
    snprintf(buffer, SIZE, "Purgatory state (%d entries)\n", purgatorySize);
    result.append(buffer);
    for (size_t i=0 ; i<purgatorySize ; i++) {
        const sp<LayerBase>& layer(mLayerPurgatory.itemAt(i));
        layer->shortDump(result, buffer, SIZE);
    }

    /*
     * Dump SurfaceFlinger global state
     */

    snprintf(buffer, SIZE, "SurfaceFlinger global state:\n");
    result.append(buffer);

    const GLExtensions& extensions(GLExtensions::getInstance());
    snprintf(buffer, SIZE, "GLES: %s, %s, %s\n",
            extensions.getVendor(),
            extensions.getRenderer(),
            extensions.getVersion());
    result.append(buffer);

    snprintf(buffer, SIZE, "EGL : %s\n",
            eglQueryString(graphicPlane(0).getEGLDisplay(),
                    EGL_VERSION_HW_ANDROID));
    result.append(buffer);

    snprintf(buffer, SIZE, "EXTS: %s\n", extensions.getExtension());
    result.append(buffer);

    mWormholeRegion.dump(result, "WormholeRegion");
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    snprintf(buffer, SIZE,
            "  orientation=%d, canDraw=%d\n",
            mCurrentState.orientation, hw.canDraw());
    result.append(buffer);
    snprintf(buffer, SIZE,
            "  last eglSwapBuffers() time: %f us\n"
            "  last transaction time     : %f us\n"
            "  refresh-rate              : %f fps\n"
            "  x-dpi                     : %f\n"
            "  y-dpi                     : %f\n",
            mLastSwapBufferTime/1000.0,
            mLastTransactionTime/1000.0,
            hw.getRefreshRate(),
            hw.getDpiX(),
            hw.getDpiY());
    result.append(buffer);

    snprintf(buffer, SIZE, "  eglSwapBuffers time: %f us\n",
            inSwapBuffersDuration/1000.0);
    result.append(buffer);

    snprintf(buffer, SIZE, "  transaction time: %f us\n",
            inTransactionDuration/1000.0);
    result.append(buffer);

    /*
     * VSYNC state
     */
    mEventThread->dump(result, buffer, SIZE);

    /*
     * Dump HWComposer state
     */
    HWComposer& hwc(hw.getHwComposer());
    snprintf(buffer, SIZE, "h/w composer state:\n");
    result.append(buffer);
    snprintf(buffer, SIZE, "  h/w composer %s and %s\n",
            hwc.initCheck()==NO_ERROR ? "present" : "not present",
                    (mDebugDisableHWC || mDebugRegion) ? "disabled" : "enabled");
    result.append(buffer);
    hwc.dump(result, buffer, SIZE, mVisibleLayersSortedByZ);

    /*
     * Dump gralloc state
     */
    const GraphicBufferAllocator& alloc(GraphicBufferAllocator::get());
    alloc.dump(result);
    hw.dump(result);
}

status_t SurfaceFlinger::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch (code) {
        case CREATE_CONNECTION:
        case SET_TRANSACTION_STATE:
        case SET_ORIENTATION:
        case BOOT_FINISHED:
        case TURN_ELECTRON_BEAM_OFF:
        case TURN_ELECTRON_BEAM_ON:
        {
            // codes that require permission check
            IPCThreadState* ipc = IPCThreadState::self();
            const int pid = ipc->getCallingPid();
            const int uid = ipc->getCallingUid();
            if ((uid != AID_GRAPHICS) &&
                    !PermissionCache::checkPermission(sAccessSurfaceFlinger, pid, uid)) {
                ALOGE("Permission Denial: "
                        "can't access SurfaceFlinger pid=%d, uid=%d", pid, uid);
                return PERMISSION_DENIED;
            }
            break;
        }
        case CAPTURE_SCREEN:
        {
            // codes that require permission check
            IPCThreadState* ipc = IPCThreadState::self();
            const int pid = ipc->getCallingPid();
            const int uid = ipc->getCallingUid();
            if ((uid != AID_GRAPHICS) &&
                    !PermissionCache::checkPermission(sReadFramebuffer, pid, uid)) {
                ALOGE("Permission Denial: "
                        "can't read framebuffer pid=%d, uid=%d", pid, uid);
                return PERMISSION_DENIED;
            }
            break;
        }
    }

    status_t err = BnSurfaceComposer::onTransact(code, data, reply, flags);
    if (err == UNKNOWN_TRANSACTION || err == PERMISSION_DENIED) {
        CHECK_INTERFACE(ISurfaceComposer, data, reply);
        if (CC_UNLIKELY(!PermissionCache::checkCallingPermission(sHardwareTest))) {
            IPCThreadState* ipc = IPCThreadState::self();
            const int pid = ipc->getCallingPid();
            const int uid = ipc->getCallingUid();
            ALOGE("Permission Denial: "
                    "can't access SurfaceFlinger pid=%d, uid=%d", pid, uid);
            return PERMISSION_DENIED;
        }
        int n;
        switch (code) {
            case 1000: // SHOW_CPU, NOT SUPPORTED ANYMORE
            case 1001: // SHOW_FPS, NOT SUPPORTED ANYMORE
                return NO_ERROR;
            case 1002:  // SHOW_UPDATES
                n = data.readInt32();
                mDebugRegion = n ? n : (mDebugRegion ? 0 : 1);
                invalidateHwcGeometry();
                repaintEverything();
                return NO_ERROR;
            case 1003:  // SHOW_BACKGROUND
                n = data.readInt32();
                mDebugBackground = n ? 1 : 0;
                return NO_ERROR;
            case 1004:{ // repaint everything
                repaintEverything();
                return NO_ERROR;
            }
            case 1005:{ // force transaction
                setTransactionFlags(eTransactionNeeded|eTraversalNeeded);
                return NO_ERROR;
            }
            case 1008:  // toggle use of hw composer
                n = data.readInt32();
                mDebugDisableHWC = n ? 1 : 0;
                invalidateHwcGeometry();
                repaintEverything();
                return NO_ERROR;
            case 1009:  // toggle use of transform hint
                n = data.readInt32();
                mDebugDisableTransformHint = n ? 1 : 0;
                invalidateHwcGeometry();
                repaintEverything();
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

void SurfaceFlinger::repaintEverything() {
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const Rect bounds(hw.getBounds());
    setInvalidateRegion(Region(bounds));
    signalEvent();
}

void SurfaceFlinger::setInvalidateRegion(const Region& reg) {
    Mutex::Autolock _l(mInvalidateLock);
    mInvalidateRegion = reg;
}

Region SurfaceFlinger::getAndClearInvalidateRegion() {
    Mutex::Autolock _l(mInvalidateLock);
    Region reg(mInvalidateRegion);
    mInvalidateRegion.clear();
    return reg;
}

// ---------------------------------------------------------------------------

status_t SurfaceFlinger::renderScreenToTexture(DisplayID dpy,
        GLuint* textureName, GLfloat* uOut, GLfloat* vOut)
{
    Mutex::Autolock _l(mStateLock);
    return renderScreenToTextureLocked(dpy, textureName, uOut, vOut);
}

status_t SurfaceFlinger::renderScreenToTextureLocked(DisplayID dpy,
        GLuint* textureName, GLfloat* uOut, GLfloat* vOut)
{
    if (!GLExtensions::getInstance().haveFramebufferObject())
        return INVALID_OPERATION;

    // get screen geometry
    const DisplayHardware& hw(graphicPlane(dpy).displayHardware());
    const uint32_t hw_w = hw.getWidth();
    const uint32_t hw_h = hw.getHeight();
    GLfloat u = 1;
    GLfloat v = 1;

    // make sure to clear all GL error flags
    while ( glGetError() != GL_NO_ERROR ) ;

    // create a FBO
    GLuint name, tname;
    glGenTextures(1, &tname);
    glBindTexture(GL_TEXTURE_2D, tname);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB,
            hw_w, hw_h, 0, GL_RGB, GL_UNSIGNED_BYTE, 0);
    if (glGetError() != GL_NO_ERROR) {
        while ( glGetError() != GL_NO_ERROR ) ;
        GLint tw = (2 << (31 - clz(hw_w)));
        GLint th = (2 << (31 - clz(hw_h)));
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB,
                tw, th, 0, GL_RGB, GL_UNSIGNED_BYTE, 0);
        u = GLfloat(hw_w) / tw;
        v = GLfloat(hw_h) / th;
    }
    glGenFramebuffersOES(1, &name);
    glBindFramebufferOES(GL_FRAMEBUFFER_OES, name);
    glFramebufferTexture2DOES(GL_FRAMEBUFFER_OES,
            GL_COLOR_ATTACHMENT0_OES, GL_TEXTURE_2D, tname, 0);

    // redraw the screen entirely...
    glDisable(GL_TEXTURE_EXTERNAL_OES);
    glDisable(GL_TEXTURE_2D);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0,0,0,1);
    glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST);
    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();
    const Vector< sp<LayerBase> >& layers(mVisibleLayersSortedByZ);
    const size_t count = layers.size();
    for (size_t i=0 ; i<count ; ++i) {
        const sp<LayerBase>& layer(layers[i]);
        layer->drawForSreenShot();
    }

    hw.compositionComplete();

    // back to main framebuffer
    glBindFramebufferOES(GL_FRAMEBUFFER_OES, 0);
    glDisable(GL_SCISSOR_TEST);
    glDeleteFramebuffersOES(1, &name);

    *textureName = tname;
    *uOut = u;
    *vOut = v;
    return NO_ERROR;
}

// ---------------------------------------------------------------------------

status_t SurfaceFlinger::electronBeamOffAnimationImplLocked()
{
    // get screen geometry
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const uint32_t hw_w = hw.getWidth();
    const uint32_t hw_h = hw.getHeight();
    const Region screenBounds(hw.getBounds());

    GLfloat u, v;
    GLuint tname;
    status_t result = renderScreenToTextureLocked(0, &tname, &u, &v);
    if (result != NO_ERROR) {
        return result;
    }

    GLfloat vtx[8];
    const GLfloat texCoords[4][2] = { {0,0}, {0,v}, {u,v}, {u,0} };
    glBindTexture(GL_TEXTURE_2D, tname);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexCoordPointer(2, GL_FLOAT, 0, texCoords);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glVertexPointer(2, GL_FLOAT, 0, vtx);

    /*
     * Texture coordinate mapping
     *
     *                 u
     *    1 +----------+---+
     *      |     |    |   |  image is inverted
     *      |     V    |   |  w.r.t. the texture
     *  1-v +----------+   |  coordinates
     *      |              |
     *      |              |
     *      |              |
     *    0 +--------------+
     *      0              1
     *
     */

    class s_curve_interpolator {
        const float nbFrames, s, v;
    public:
        s_curve_interpolator(int nbFrames, float s)
        : nbFrames(1.0f / (nbFrames-1)), s(s),
          v(1.0f + expf(-s + 0.5f*s)) {
        }
        float operator()(int f) {
            const float x = f * nbFrames;
            return ((1.0f/(1.0f + expf(-x*s + 0.5f*s))) - 0.5f) * v + 0.5f;
        }
    };

    class v_stretch {
        const GLfloat hw_w, hw_h;
    public:
        v_stretch(uint32_t hw_w, uint32_t hw_h)
        : hw_w(hw_w), hw_h(hw_h) {
        }
        void operator()(GLfloat* vtx, float v) {
            const GLfloat w = hw_w + (hw_w * v);
            const GLfloat h = hw_h - (hw_h * v);
            const GLfloat x = (hw_w - w) * 0.5f;
            const GLfloat y = (hw_h - h) * 0.5f;
            vtx[0] = x;         vtx[1] = y;
            vtx[2] = x;         vtx[3] = y + h;
            vtx[4] = x + w;     vtx[5] = y + h;
            vtx[6] = x + w;     vtx[7] = y;
        }
    };

    class h_stretch {
        const GLfloat hw_w, hw_h;
    public:
        h_stretch(uint32_t hw_w, uint32_t hw_h)
        : hw_w(hw_w), hw_h(hw_h) {
        }
        void operator()(GLfloat* vtx, float v) {
            const GLfloat w = hw_w - (hw_w * v);
            const GLfloat h = 1.0f;
            const GLfloat x = (hw_w - w) * 0.5f;
            const GLfloat y = (hw_h - h) * 0.5f;
            vtx[0] = x;         vtx[1] = y;
            vtx[2] = x;         vtx[3] = y + h;
            vtx[4] = x + w;     vtx[5] = y + h;
            vtx[6] = x + w;     vtx[7] = y;
        }
    };

    // the full animation is 24 frames
    char value[PROPERTY_VALUE_MAX];
    property_get("debug.sf.electron_frames", value, "24");
    int nbFrames = (atoi(value) + 1) >> 1;
    if (nbFrames <= 0) // just in case
        nbFrames = 24;

    s_curve_interpolator itr(nbFrames, 7.5f);
    s_curve_interpolator itg(nbFrames, 8.0f);
    s_curve_interpolator itb(nbFrames, 8.5f);

    v_stretch vverts(hw_w, hw_h);

    glMatrixMode(GL_TEXTURE);
    glLoadIdentity();
    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();

    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE);
    for (int i=0 ; i<nbFrames ; i++) {
        float x, y, w, h;
        const float vr = itr(i);
        const float vg = itg(i);
        const float vb = itb(i);

        // clear screen
        glColorMask(1,1,1,1);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_TEXTURE_2D);

        // draw the red plane
        vverts(vtx, vr);
        glColorMask(1,0,0,1);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        // draw the green plane
        vverts(vtx, vg);
        glColorMask(0,1,0,1);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        // draw the blue plane
        vverts(vtx, vb);
        glColorMask(0,0,1,1);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        // draw the white highlight (we use the last vertices)
        glDisable(GL_TEXTURE_2D);
        glColorMask(1,1,1,1);
        glColor4f(vg, vg, vg, 1);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        hw.flip(screenBounds);
    }

    h_stretch hverts(hw_w, hw_h);
    glDisable(GL_BLEND);
    glDisable(GL_TEXTURE_2D);
    glColorMask(1,1,1,1);
    for (int i=0 ; i<nbFrames ; i++) {
        const float v = itg(i);
        hverts(vtx, v);
        glClear(GL_COLOR_BUFFER_BIT);
        glColor4f(1-v, 1-v, 1-v, 1);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        hw.flip(screenBounds);
    }

    glColorMask(1,1,1,1);
    glEnable(GL_SCISSOR_TEST);
    glDisableClientState(GL_TEXTURE_COORD_ARRAY);
    glDeleteTextures(1, &tname);
    glDisable(GL_TEXTURE_2D);
    glDisable(GL_BLEND);
    return NO_ERROR;
}

status_t SurfaceFlinger::electronBeamOnAnimationImplLocked()
{
    status_t result = PERMISSION_DENIED;

    if (!GLExtensions::getInstance().haveFramebufferObject())
        return INVALID_OPERATION;


    // get screen geometry
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const uint32_t hw_w = hw.getWidth();
    const uint32_t hw_h = hw.getHeight();
    const Region screenBounds(hw.bounds());

    GLfloat u, v;
    GLuint tname;
    result = renderScreenToTextureLocked(0, &tname, &u, &v);
    if (result != NO_ERROR) {
        return result;
    }

    GLfloat vtx[8];
    const GLfloat texCoords[4][2] = { {0,v}, {0,0}, {u,0}, {u,v} };
    glBindTexture(GL_TEXTURE_2D, tname);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexCoordPointer(2, GL_FLOAT, 0, texCoords);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glVertexPointer(2, GL_FLOAT, 0, vtx);

    class s_curve_interpolator {
        const float nbFrames, s, v;
    public:
        s_curve_interpolator(int nbFrames, float s)
        : nbFrames(1.0f / (nbFrames-1)), s(s),
          v(1.0f + expf(-s + 0.5f*s)) {
        }
        float operator()(int f) {
            const float x = f * nbFrames;
            return ((1.0f/(1.0f + expf(-x*s + 0.5f*s))) - 0.5f) * v + 0.5f;
        }
    };

    class v_stretch {
        const GLfloat hw_w, hw_h;
    public:
        v_stretch(uint32_t hw_w, uint32_t hw_h)
        : hw_w(hw_w), hw_h(hw_h) {
        }
        void operator()(GLfloat* vtx, float v) {
            const GLfloat w = hw_w + (hw_w * v);
            const GLfloat h = hw_h - (hw_h * v);
            const GLfloat x = (hw_w - w) * 0.5f;
            const GLfloat y = (hw_h - h) * 0.5f;
            vtx[0] = x;         vtx[1] = y;
            vtx[2] = x;         vtx[3] = y + h;
            vtx[4] = x + w;     vtx[5] = y + h;
            vtx[6] = x + w;     vtx[7] = y;
        }
    };

    class h_stretch {
        const GLfloat hw_w, hw_h;
    public:
        h_stretch(uint32_t hw_w, uint32_t hw_h)
        : hw_w(hw_w), hw_h(hw_h) {
        }
        void operator()(GLfloat* vtx, float v) {
            const GLfloat w = hw_w - (hw_w * v);
            const GLfloat h = 1.0f;
            const GLfloat x = (hw_w - w) * 0.5f;
            const GLfloat y = (hw_h - h) * 0.5f;
            vtx[0] = x;         vtx[1] = y;
            vtx[2] = x;         vtx[3] = y + h;
            vtx[4] = x + w;     vtx[5] = y + h;
            vtx[6] = x + w;     vtx[7] = y;
        }
    };

    // the full animation is 12 frames
    int nbFrames = 8;
    s_curve_interpolator itr(nbFrames, 7.5f);
    s_curve_interpolator itg(nbFrames, 8.0f);
    s_curve_interpolator itb(nbFrames, 8.5f);

    h_stretch hverts(hw_w, hw_h);
    glDisable(GL_BLEND);
    glDisable(GL_TEXTURE_2D);
    glColorMask(1,1,1,1);
    for (int i=nbFrames-1 ; i>=0 ; i--) {
        const float v = itg(i);
        hverts(vtx, v);
        glClear(GL_COLOR_BUFFER_BIT);
        glColor4f(1-v, 1-v, 1-v, 1);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        hw.flip(screenBounds);
    }

    nbFrames = 4;
    v_stretch vverts(hw_w, hw_h);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE);
    for (int i=nbFrames-1 ; i>=0 ; i--) {
        float x, y, w, h;
        const float vr = itr(i);
        const float vg = itg(i);
        const float vb = itb(i);

        // clear screen
        glColorMask(1,1,1,1);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_TEXTURE_2D);

        // draw the red plane
        vverts(vtx, vr);
        glColorMask(1,0,0,1);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        // draw the green plane
        vverts(vtx, vg);
        glColorMask(0,1,0,1);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        // draw the blue plane
        vverts(vtx, vb);
        glColorMask(0,0,1,1);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        hw.flip(screenBounds);
    }

    glColorMask(1,1,1,1);
    glEnable(GL_SCISSOR_TEST);
    glDisableClientState(GL_TEXTURE_COORD_ARRAY);
    glDeleteTextures(1, &tname);
    glDisable(GL_TEXTURE_2D);
    glDisable(GL_BLEND);

    return NO_ERROR;
}

// ---------------------------------------------------------------------------

status_t SurfaceFlinger::turnElectronBeamOffImplLocked(int32_t mode)
{
    DisplayHardware& hw(graphicPlane(0).editDisplayHardware());
    if (!hw.canDraw()) {
        // we're already off
        return NO_ERROR;
    }

    // turn off hwc while we're doing the animation
    hw.getHwComposer().disable();
    // and make sure to turn it back on (if needed) next time we compose
    invalidateHwcGeometry();

    if (mode & ISurfaceComposer::eElectronBeamAnimationOff) {
        electronBeamOffAnimationImplLocked();
    }

    // always clear the whole screen at the end of the animation
    glClearColor(0,0,0,1);
    glDisable(GL_SCISSOR_TEST);
    glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST);
    hw.flip( Region(hw.bounds()) );

    return NO_ERROR;
}

status_t SurfaceFlinger::turnElectronBeamOff(int32_t mode)
{
    class MessageTurnElectronBeamOff : public MessageBase {
        SurfaceFlinger* flinger;
        int32_t mode;
        status_t result;
    public:
        MessageTurnElectronBeamOff(SurfaceFlinger* flinger, int32_t mode)
            : flinger(flinger), mode(mode), result(PERMISSION_DENIED) {
        }
        status_t getResult() const {
            return result;
        }
        virtual bool handler() {
            Mutex::Autolock _l(flinger->mStateLock);
            result = flinger->turnElectronBeamOffImplLocked(mode);
            return true;
        }
    };

    sp<MessageBase> msg = new MessageTurnElectronBeamOff(this, mode);
    status_t res = postMessageSync(msg);
    if (res == NO_ERROR) {
        res = static_cast<MessageTurnElectronBeamOff*>( msg.get() )->getResult();

        // work-around: when the power-manager calls us we activate the
        // animation. eventually, the "on" animation will be called
        // by the power-manager itself
        mElectronBeamAnimationMode = mode;
    }
    return res;
}

// ---------------------------------------------------------------------------

status_t SurfaceFlinger::turnElectronBeamOnImplLocked(int32_t mode)
{
    DisplayHardware& hw(graphicPlane(0).editDisplayHardware());
    if (hw.canDraw()) {
        // we're already on
        return NO_ERROR;
    }
    if (mode & ISurfaceComposer::eElectronBeamAnimationOn) {
        electronBeamOnAnimationImplLocked();
    }

    // make sure to redraw the whole screen when the animation is done
    mDirtyRegion.set(hw.bounds());
    signalEvent();

    return NO_ERROR;
}

status_t SurfaceFlinger::turnElectronBeamOn(int32_t mode)
{
    class MessageTurnElectronBeamOn : public MessageBase {
        SurfaceFlinger* flinger;
        int32_t mode;
        status_t result;
    public:
        MessageTurnElectronBeamOn(SurfaceFlinger* flinger, int32_t mode)
            : flinger(flinger), mode(mode), result(PERMISSION_DENIED) {
        }
        status_t getResult() const {
            return result;
        }
        virtual bool handler() {
            Mutex::Autolock _l(flinger->mStateLock);
            result = flinger->turnElectronBeamOnImplLocked(mode);
            return true;
        }
    };

    postMessageAsync( new MessageTurnElectronBeamOn(this, mode) );
    return NO_ERROR;
}

// ---------------------------------------------------------------------------

status_t SurfaceFlinger::captureScreenImplLocked(DisplayID dpy,
        sp<IMemoryHeap>* heap,
        uint32_t* w, uint32_t* h, PixelFormat* f,
        uint32_t sw, uint32_t sh,
        uint32_t minLayerZ, uint32_t maxLayerZ)
{
    status_t result = PERMISSION_DENIED;

    // only one display supported for now
    if (CC_UNLIKELY(uint32_t(dpy) >= DISPLAY_COUNT))
        return BAD_VALUE;

    if (!GLExtensions::getInstance().haveFramebufferObject())
        return INVALID_OPERATION;

    // get screen geometry
    const DisplayHardware& hw(graphicPlane(dpy).displayHardware());
    const uint32_t hw_w = hw.getWidth();
    const uint32_t hw_h = hw.getHeight();

    if ((sw > hw_w) || (sh > hw_h))
        return BAD_VALUE;

    sw = (!sw) ? hw_w : sw;
    sh = (!sh) ? hw_h : sh;
    const size_t size = sw * sh * 4;

    //ALOGD("screenshot: sw=%d, sh=%d, minZ=%d, maxZ=%d",
    //        sw, sh, minLayerZ, maxLayerZ);

    // make sure to clear all GL error flags
    while ( glGetError() != GL_NO_ERROR ) ;

    // create a FBO
    GLuint name, tname;
    glGenRenderbuffersOES(1, &tname);
    glBindRenderbufferOES(GL_RENDERBUFFER_OES, tname);
    glRenderbufferStorageOES(GL_RENDERBUFFER_OES, GL_RGBA8_OES, sw, sh);
    glGenFramebuffersOES(1, &name);
    glBindFramebufferOES(GL_FRAMEBUFFER_OES, name);
    glFramebufferRenderbufferOES(GL_FRAMEBUFFER_OES,
            GL_COLOR_ATTACHMENT0_OES, GL_RENDERBUFFER_OES, tname);

    GLenum status = glCheckFramebufferStatusOES(GL_FRAMEBUFFER_OES);

    if (status == GL_FRAMEBUFFER_COMPLETE_OES) {

        // invert everything, b/c glReadPixel() below will invert the FB
        glViewport(0, 0, sw, sh);
        glScissor(0, 0, sw, sh);
        glEnable(GL_SCISSOR_TEST);
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrthof(0, hw_w, hw_h, 0, 0, 1);
        glMatrixMode(GL_MODELVIEW);

        // redraw the screen entirely...
        glClearColor(0,0,0,1);
        glClear(GL_COLOR_BUFFER_BIT);

        const LayerVector& layers(mDrawingState.layersSortedByZ);
        const size_t count = layers.size();
        for (size_t i=0 ; i<count ; ++i) {
            const sp<LayerBase>& layer(layers[i]);
            const uint32_t flags = layer->drawingState().flags;
            if (!(flags & ISurfaceComposer::eLayerHidden)) {
                const uint32_t z = layer->drawingState().z;
                if (z >= minLayerZ && z <= maxLayerZ) {
                    layer->drawForSreenShot();
                }
            }
        }

        // XXX: this is needed on tegra
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, 0, sw, sh);

        // check for errors and return screen capture
        if (glGetError() != GL_NO_ERROR) {
            // error while rendering
            result = INVALID_OPERATION;
        } else {
            // allocate shared memory large enough to hold the
            // screen capture
            sp<MemoryHeapBase> base(
                    new MemoryHeapBase(size, 0, "screen-capture") );
            void* const ptr = base->getBase();
            if (ptr) {
                // capture the screen with glReadPixels()
                glReadPixels(0, 0, sw, sh, GL_RGBA, GL_UNSIGNED_BYTE, ptr);
                if (glGetError() == GL_NO_ERROR) {
                    *heap = base;
                    *w = sw;
                    *h = sh;
                    *f = PIXEL_FORMAT_RGBA_8888;
                    result = NO_ERROR;
                }
            } else {
                result = NO_MEMORY;
            }
        }
        glEnable(GL_SCISSOR_TEST);
        glViewport(0, 0, hw_w, hw_h);
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
    } else {
        result = BAD_VALUE;
    }

    // release FBO resources
    glBindFramebufferOES(GL_FRAMEBUFFER_OES, 0);
    glDeleteRenderbuffersOES(1, &tname);
    glDeleteFramebuffersOES(1, &name);

    hw.compositionComplete();

    // ALOGD("screenshot: result = %s", result<0 ? strerror(result) : "OK");

    return result;
}


status_t SurfaceFlinger::captureScreen(DisplayID dpy,
        sp<IMemoryHeap>* heap,
        uint32_t* width, uint32_t* height, PixelFormat* format,
        uint32_t sw, uint32_t sh,
        uint32_t minLayerZ, uint32_t maxLayerZ)
{
    // only one display supported for now
    if (CC_UNLIKELY(uint32_t(dpy) >= DISPLAY_COUNT))
        return BAD_VALUE;

    if (!GLExtensions::getInstance().haveFramebufferObject())
        return INVALID_OPERATION;

    class MessageCaptureScreen : public MessageBase {
        SurfaceFlinger* flinger;
        DisplayID dpy;
        sp<IMemoryHeap>* heap;
        uint32_t* w;
        uint32_t* h;
        PixelFormat* f;
        uint32_t sw;
        uint32_t sh;
        uint32_t minLayerZ;
        uint32_t maxLayerZ;
        status_t result;
    public:
        MessageCaptureScreen(SurfaceFlinger* flinger, DisplayID dpy,
                sp<IMemoryHeap>* heap, uint32_t* w, uint32_t* h, PixelFormat* f,
                uint32_t sw, uint32_t sh,
                uint32_t minLayerZ, uint32_t maxLayerZ)
            : flinger(flinger), dpy(dpy),
              heap(heap), w(w), h(h), f(f), sw(sw), sh(sh),
              minLayerZ(minLayerZ), maxLayerZ(maxLayerZ),
              result(PERMISSION_DENIED)
        {
        }
        status_t getResult() const {
            return result;
        }
        virtual bool handler() {
            Mutex::Autolock _l(flinger->mStateLock);

            // if we have secure windows, never allow the screen capture
            if (flinger->mSecureFrameBuffer)
                return true;

            result = flinger->captureScreenImplLocked(dpy,
                    heap, w, h, f, sw, sh, minLayerZ, maxLayerZ);

            return true;
        }
    };

    sp<MessageBase> msg = new MessageCaptureScreen(this,
            dpy, heap, width, height, format, sw, sh, minLayerZ, maxLayerZ);
    status_t res = postMessageSync(msg);
    if (res == NO_ERROR) {
        res = static_cast<MessageCaptureScreen*>( msg.get() )->getResult();
    }
    return res;
}

// ---------------------------------------------------------------------------

sp<Layer> SurfaceFlinger::getLayer(const sp<ISurface>& sur) const
{
    sp<Layer> result;
    Mutex::Autolock _l(mStateLock);
    result = mLayerMap.valueFor( sur->asBinder() ).promote();
    return result;
}

// ---------------------------------------------------------------------------

Client::Client(const sp<SurfaceFlinger>& flinger)
    : mFlinger(flinger), mNameGenerator(1)
{
}

Client::~Client()
{
    const size_t count = mLayers.size();
    for (size_t i=0 ; i<count ; i++) {
        sp<LayerBaseClient> layer(mLayers.valueAt(i).promote());
        if (layer != 0) {
            mFlinger->removeLayer(layer);
        }
    }
}

status_t Client::initCheck() const {
    return NO_ERROR;
}

size_t Client::attachLayer(const sp<LayerBaseClient>& layer)
{
    Mutex::Autolock _l(mLock);
    size_t name = mNameGenerator++;
    mLayers.add(name, layer);
    return name;
}

void Client::detachLayer(const LayerBaseClient* layer)
{
    Mutex::Autolock _l(mLock);
    // we do a linear search here, because this doesn't happen often
    const size_t count = mLayers.size();
    for (size_t i=0 ; i<count ; i++) {
        if (mLayers.valueAt(i) == layer) {
            mLayers.removeItemsAt(i, 1);
            break;
        }
    }
}
sp<LayerBaseClient> Client::getLayerUser(int32_t i) const
{
    Mutex::Autolock _l(mLock);
    sp<LayerBaseClient> lbc;
    wp<LayerBaseClient> layer(mLayers.valueFor(i));
    if (layer != 0) {
        lbc = layer.promote();
        ALOGE_IF(lbc==0, "getLayerUser(name=%d) is dead", int(i));
    }
    return lbc;
}


status_t Client::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    // these must be checked
     IPCThreadState* ipc = IPCThreadState::self();
     const int pid = ipc->getCallingPid();
     const int uid = ipc->getCallingUid();
     const int self_pid = getpid();
     if (CC_UNLIKELY(pid != self_pid && uid != AID_GRAPHICS && uid != 0)) {
         // we're called from a different process, do the real check
         if (!PermissionCache::checkCallingPermission(sAccessSurfaceFlinger))
         {
             ALOGE("Permission Denial: "
                     "can't openGlobalTransaction pid=%d, uid=%d", pid, uid);
             return PERMISSION_DENIED;
         }
     }
     return BnSurfaceComposerClient::onTransact(code, data, reply, flags);
}


sp<ISurface> Client::createSurface(
        ISurfaceComposerClient::surface_data_t* params,
        const String8& name,
        DisplayID display, uint32_t w, uint32_t h, PixelFormat format,
        uint32_t flags)
{
    /*
     * createSurface must be called from the GL thread so that it can
     * have access to the GL context.
     */

    class MessageCreateSurface : public MessageBase {
        sp<ISurface> result;
        SurfaceFlinger* flinger;
        ISurfaceComposerClient::surface_data_t* params;
        Client* client;
        const String8& name;
        DisplayID display;
        uint32_t w, h;
        PixelFormat format;
        uint32_t flags;
    public:
        MessageCreateSurface(SurfaceFlinger* flinger,
                ISurfaceComposerClient::surface_data_t* params,
                const String8& name, Client* client,
                DisplayID display, uint32_t w, uint32_t h, PixelFormat format,
                uint32_t flags)
            : flinger(flinger), params(params), client(client), name(name),
              display(display), w(w), h(h), format(format), flags(flags)
        {
        }
        sp<ISurface> getResult() const { return result; }
        virtual bool handler() {
            result = flinger->createSurface(params, name, client,
                    display, w, h, format, flags);
            return true;
        }
    };

    sp<MessageBase> msg = new MessageCreateSurface(mFlinger.get(),
            params, name, this, display, w, h, format, flags);
    mFlinger->postMessageSync(msg);
    return static_cast<MessageCreateSurface*>( msg.get() )->getResult();
}
status_t Client::destroySurface(SurfaceID sid) {
    return mFlinger->removeSurface(this, sid);
}

// ---------------------------------------------------------------------------

GraphicBufferAlloc::GraphicBufferAlloc() {}

GraphicBufferAlloc::~GraphicBufferAlloc() {}

sp<GraphicBuffer> GraphicBufferAlloc::createGraphicBuffer(uint32_t w, uint32_t h,
        PixelFormat format, uint32_t usage, status_t* error) {
    sp<GraphicBuffer> graphicBuffer(new GraphicBuffer(w, h, format, usage));
    status_t err = graphicBuffer->initCheck();
    *error = err;
    if (err != 0 || graphicBuffer->handle == 0) {
        if (err == NO_MEMORY) {
            GraphicBuffer::dumpAllocationsToSystemLog();
        }
        ALOGE("GraphicBufferAlloc::createGraphicBuffer(w=%d, h=%d) "
             "failed (%s), handle=%p",
                w, h, strerror(-err), graphicBuffer->handle);
        return 0;
    }
    return graphicBuffer;
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

DisplayHardware& GraphicPlane::editDisplayHardware() {
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
