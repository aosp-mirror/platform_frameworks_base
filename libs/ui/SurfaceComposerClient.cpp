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

#define LOG_TAG "SurfaceComposerClient"

#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>

#include <cutils/memory.h>

#include <utils/Atomic.h>
#include <utils/Errors.h>
#include <utils/threads.h>
#include <utils/KeyedVector.h>
#include <utils/IPCThreadState.h>
#include <utils/IServiceManager.h>
#include <utils/IMemory.h>
#include <utils/Log.h>

#include <ui/ISurfaceComposer.h>
#include <ui/ISurfaceFlingerClient.h>
#include <ui/ISurface.h>
#include <ui/SurfaceComposerClient.h>
#include <ui/DisplayInfo.h>
#include <ui/Rect.h>
#include <ui/Point.h>

#include <private/ui/SharedState.h>
#include <private/ui/LayerState.h>
#include <private/ui/SurfaceFlingerSynchro.h>

#include <pixelflinger/pixelflinger.h>

#include <utils/BpBinder.h>

#define VERBOSE(...)	((void)0)
//#define VERBOSE			LOGD

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

namespace android {

// ---------------------------------------------------------------------------

// Must not be holding SurfaceComposerClient::mLock when acquiring gLock here.
static Mutex                                                gLock;
static sp<ISurfaceComposer>                                 gSurfaceManager;
static DefaultKeyedVector< sp<IBinder>, sp<SurfaceComposerClient> > gActiveConnections;
static SortedVector<sp<SurfaceComposerClient> >             gOpenTransactions;
static sp<IMemory>                                          gServerCblkMemory;
static volatile surface_flinger_cblk_t*                     gServerCblk;

const sp<ISurfaceComposer>& _get_surface_manager()
{
    if (gSurfaceManager != 0) {
        return gSurfaceManager;
    }

    sp<IBinder> binder;
    sp<IServiceManager> sm = defaultServiceManager();
    do {
        binder = sm->getService(String16("SurfaceFlinger"));
        if (binder == 0) {
            LOGW("SurfaceFlinger not published, waiting...");
            usleep(500000); // 0.5 s
        }
    } while(binder == 0);
    sp<ISurfaceComposer> sc(interface_cast<ISurfaceComposer>(binder));

    Mutex::Autolock _l(gLock);
    if (gSurfaceManager == 0) {
        gSurfaceManager = sc;
    }
    return gSurfaceManager;
}

static volatile surface_flinger_cblk_t const * get_cblk()
{
    if (gServerCblk == 0) {
        const sp<ISurfaceComposer>& sm(_get_surface_manager());
        Mutex::Autolock _l(gLock);
        if (gServerCblk == 0) {
            gServerCblkMemory = sm->getCblk();
            LOGE_IF(gServerCblkMemory==0, "Can't get server control block");
            gServerCblk = (surface_flinger_cblk_t *)gServerCblkMemory->pointer();
            LOGE_IF(gServerCblk==0, "Can't get server control block address");
        }
    }
    return gServerCblk;
}

// ---------------------------------------------------------------------------

static void copyBlt(const GGLSurface& dst,
        const GGLSurface& src, const Region& reg)
{
    Region::iterator iterator(reg);
    if (iterator) {
        // NOTE: dst and src must be the same format
        Rect r;
        const size_t bpp = bytesPerPixel(src.format);
        const size_t dbpr = dst.stride * bpp;
        const size_t sbpr = src.stride * bpp;
        while (iterator.iterate(&r)) {
            ssize_t h = r.bottom - r.top;
            if (h) {
                size_t size = (r.right - r.left) * bpp;
                uint8_t* s = src.data + (r.left + src.stride * r.top) * bpp;
                uint8_t* d = dst.data + (r.left + dst.stride * r.top) * bpp;
                if (dbpr==sbpr && size==sbpr) {
                    size *= h;
                    h = 1;
                }
                do {
                    memcpy(d, s, size);
                    d += dbpr;
                    s += sbpr;
                } while (--h > 0);
            }
        }
    }
}

// ---------------------------------------------------------------------------

surface_flinger_cblk_t::surface_flinger_cblk_t()
{
}

// ---------------------------------------------------------------------------

per_client_cblk_t::per_client_cblk_t()
{
}

// these functions are used by the clients
inline status_t per_client_cblk_t::validate(size_t i) const {
    if (uint32_t(i) >= NUM_LAYERS_MAX)
        return BAD_INDEX;
    if (layers[i].swapState & eInvalidSurface)
        return NO_MEMORY;
    return NO_ERROR;
}

int32_t per_client_cblk_t::lock_layer(size_t i, uint32_t flags)
{
    int32_t index;
    uint32_t state;
    int timeout = 0;
    status_t result;
    layer_cblk_t * const layer = layers + i;
    const bool blocking = flags & BLOCKING;
    const bool inspect  = flags & INSPECT;

    do {
        state = layer->swapState;

        if (UNLIKELY((state&(eFlipRequested|eNextFlipPending)) == eNextFlipPending)) {
            LOGE("eNextFlipPending set but eFlipRequested not set, "
                 "layer=%d (lcblk=%p), state=%08x",
                 int(i), layer, int(state));
            return INVALID_OPERATION;
        }

        if (UNLIKELY(state&eLocked)) {
            LOGE("eLocked set when entering lock_layer(), "
                 "layer=%d (lcblk=%p), state=%08x",
                 int(i), layer, int(state));
            return WOULD_BLOCK;
        }


	    if (state & (eFlipRequested | eNextFlipPending | eResizeRequested
                        | eInvalidSurface))
        {
	        int32_t resizeIndex;
	        Mutex::Autolock _l(lock);
	            // might block for a very short amount of time
	            // will never cause the server to block (trylock())

	        goto start_loop_here;

	        // We block the client if:
	        // eNextFlipPending:  we've used both buffers already, so we need to
	        //                    wait for one to become availlable.
	        // eResizeRequested:  the buffer we're going to acquire is being
	        //                    resized. Block until it is done.
	        // eFlipRequested && eBusy: the buffer we're going to acquire is
	        //                    currently in use by the server.
	        // eInvalidSurface:   this is a special case, we don't block in this
	        //                    case, we just return an error.

	        while((state & (eNextFlipPending|eInvalidSurface)) ||
	              (state & ((resizeIndex) ? eResizeBuffer1 : eResizeBuffer0)) ||
	              ((state & (eFlipRequested|eBusy)) == (eFlipRequested|eBusy)) )
	        {
	            if (state & eInvalidSurface)
	                return NO_MEMORY;

	            if (!blocking)
	                return WOULD_BLOCK;

                timeout = 0;
                result = cv.waitRelative(lock, seconds(1));
	            if (__builtin_expect(result!=NO_ERROR, false)) {
                    const int newState = layer->swapState;
                    LOGW(   "lock_layer timed out (is the CPU pegged?) "
                            "layer=%d, lcblk=%p, state=%08x (was %08x)",
                            int(i), layer, newState, int(state));
                    timeout = newState != int(state);
                }

	        start_loop_here:
	            state = layer->swapState;
	            resizeIndex = (state&eIndex) ^ ((state&eFlipRequested)>>1);
	        }

            LOGW_IF(timeout,
                    "lock_layer() timed out but didn't appear to need "
                    "to be locked and we recovered "
                    "(layer=%d, lcblk=%p, state=%08x)",
                    int(i), layer, int(state));
	    }

	    // eFlipRequested is not set and cannot be set by another thread: it's
	    // safe to use the first buffer without synchronization.

        // Choose the index depending on eFlipRequested.
        // When it's set, choose the 'other' buffer.
        index = (state&eIndex) ^ ((state&eFlipRequested)>>1);

	    // make sure this buffer is valid
	    if (layer->surface[index].bits_offset < 0) {
	        return status_t(layer->surface[index].bits_offset);
	    }

        if (inspect) {
            // we just want to inspect this layer. don't lock it.
            goto done;
        }

	    // last thing before we're done, we need to atomically lock the state
    } while (android_atomic_cmpxchg(state, state|eLocked, &(layer->swapState)));

    VERBOSE("locked layer=%d (lcblk=%p), buffer=%d, state=0x%08x",
         int(i), layer, int(index), int(state));

    // store the index of the locked buffer (for client use only)
    layer->flags &= ~eBufferIndex;
    layer->flags |= ((index << eBufferIndexShift) & eBufferIndex);

done:
    return index;
}

uint32_t per_client_cblk_t::unlock_layer_and_post(size_t i)
{
    // atomically set eFlipRequested and clear eLocked and optionnaly
    // set eNextFlipPending if eFlipRequested was already set

    layer_cblk_t * const layer = layers + i;
    int32_t oldvalue, newvalue;
    do {
        oldvalue = layer->swapState;
            // get current value

        newvalue = oldvalue & ~eLocked;
            // clear eLocked

        newvalue |= eFlipRequested;
            // set eFlipRequested

        if (oldvalue & eFlipRequested)
            newvalue |= eNextFlipPending;
            // if eFlipRequested was alread set, set eNextFlipPending

    } while (android_atomic_cmpxchg(oldvalue, newvalue, &(layer->swapState)));

    VERBOSE("request pageflip for layer=%d, buffer=%d, state=0x%08x",
            int(i), int((layer->flags & eBufferIndex) >> eBufferIndexShift),
            int(newvalue));

    // from this point, the server can kick in at anytime and use the first
    // buffer, so we cannot use it anymore, and we must use the 'other'
    // buffer instead (or wait if it is not availlable yet, see lock_layer).

    return newvalue;
}

void per_client_cblk_t::unlock_layer(size_t i)
{
    layer_cblk_t * const layer = layers + i;
    android_atomic_and(~eLocked, &layer->swapState);
}

// ---------------------------------------------------------------------------

static inline int compare_type( const layer_state_t& lhs,
                                const layer_state_t& rhs) {
    if (lhs.surface < rhs.surface)  return -1;
    if (lhs.surface > rhs.surface)  return 1;
    return 0;
}

SurfaceComposerClient::SurfaceComposerClient()
{
    const sp<ISurfaceComposer>& sm(_get_surface_manager());
    if (sm == 0) {
        _init(0, 0);
        return;
    }

    _init(sm, sm->createConnection());

    if (mClient != 0) {
        Mutex::Autolock _l(gLock);
        VERBOSE("Adding client %p to map", this);
        gActiveConnections.add(mClient->asBinder(), this);
    }
}

SurfaceComposerClient::SurfaceComposerClient(
        const sp<ISurfaceComposer>& sm, const sp<IBinder>& conn)
{
    _init(sm, interface_cast<ISurfaceFlingerClient>(conn));
}

void SurfaceComposerClient::_init(
        const sp<ISurfaceComposer>& sm, const sp<ISurfaceFlingerClient>& conn)
{
    VERBOSE("Creating client %p, conn %p", this, conn.get());

    mSignalServer = 0;
    mPrebuiltLayerState = 0;
    mTransactionOpen = 0;
    mStatus = NO_ERROR;
    mControl = 0;

    mClient = conn;
    if (mClient == 0) {
        mStatus = NO_INIT;
        return;
    }

    mClient->getControlBlocks(&mControlMemory);
    mSignalServer = new SurfaceFlingerSynchro(sm);
    mControl = static_cast<per_client_cblk_t *>(mControlMemory->pointer());
}

SurfaceComposerClient::~SurfaceComposerClient()
{
    VERBOSE("Destroying client %p, conn %p", this, mClient.get());
    dispose();
}

status_t SurfaceComposerClient::initCheck() const
{
    return mStatus;
}

status_t SurfaceComposerClient::validateSurface(
        per_client_cblk_t const* cblk, Surface const * surface)
{
    SurfaceID index = surface->ID();
    if (cblk == 0) {
        LOGE("cblk is null (surface id=%d, identity=%u)",
                index, surface->getIdentity());
        return NO_INIT;
    }

    status_t err = cblk->validate(index);
    if (err != NO_ERROR) {
        LOGE("surface (id=%d, identity=%u) is invalid, err=%d (%s)",
                index, surface->getIdentity(), err, strerror(-err));
        return err;
    }

    if (surface->getIdentity() != uint32_t(cblk->layers[index].identity)) {
        LOGE("using an invalid surface id=%d, identity=%u should be %d",
                index, surface->getIdentity(), cblk->layers[index].identity);
        return NO_INIT;
    }

    return NO_ERROR;
}

sp<IBinder> SurfaceComposerClient::connection() const
{
    return (mClient != 0) ? mClient->asBinder() : 0;
}

sp<SurfaceComposerClient>
SurfaceComposerClient::clientForConnection(const sp<IBinder>& conn)
{
    sp<SurfaceComposerClient> client;

    { // scope for lock
        Mutex::Autolock _l(gLock);
        client = gActiveConnections.valueFor(conn);
    }

    if (client == 0) {
        // Need to make a new client.
        const sp<ISurfaceComposer>& sm(_get_surface_manager());
        client = new SurfaceComposerClient(sm, conn);
        if (client != 0 && client->initCheck() == NO_ERROR) {
            Mutex::Autolock _l(gLock);
            gActiveConnections.add(conn, client);
            //LOGD("we have %d connections", gActiveConnections.size());
        } else {
            client.clear();
        }
    }

    return client;
}

void SurfaceComposerClient::dispose()
{
    // this can be called more than once.

    sp<IMemory>                 controlMemory;
    sp<ISurfaceFlingerClient>   client;
    sp<IMemoryHeap>             surfaceHeap;

    {
        Mutex::Autolock _lg(gLock);
        Mutex::Autolock _lm(mLock);

        delete mSignalServer;
        mSignalServer = 0;

        if (mClient != 0) {
            client = mClient;
            mClient.clear();

            ssize_t i = gActiveConnections.indexOfKey(client->asBinder());
            if (i >= 0 && gActiveConnections.valueAt(i) == this) {
                VERBOSE("Removing client %p from map at %d", this, int(i));
                gActiveConnections.removeItemsAt(i);
            }
        }

        delete mPrebuiltLayerState;
        mPrebuiltLayerState = 0;
        controlMemory = mControlMemory;
        surfaceHeap = mSurfaceHeap;
        mControlMemory.clear();
        mSurfaceHeap.clear();
        mControl = 0;
        mStatus = NO_INIT;
    }
}

status_t SurfaceComposerClient::getDisplayInfo(
        DisplayID dpy, DisplayInfo* info)
{
    if (uint32_t(dpy)>=NUM_DISPLAY_MAX)
        return BAD_VALUE;

    volatile surface_flinger_cblk_t const * cblk = get_cblk();
    volatile display_cblk_t const * dcblk = cblk->displays + dpy;

    info->w              = dcblk->w;
    info->h              = dcblk->h;
    info->orientation    = dcblk->orientation;
    info->xdpi           = dcblk->xdpi;
    info->ydpi           = dcblk->ydpi;
    info->fps            = dcblk->fps;
    info->density        = dcblk->density;
    return getPixelFormatInfo(dcblk->format, &(info->pixelFormatInfo));
}

ssize_t SurfaceComposerClient::getDisplayWidth(DisplayID dpy)
{
    if (uint32_t(dpy)>=NUM_DISPLAY_MAX)
        return BAD_VALUE;
    volatile surface_flinger_cblk_t const * cblk = get_cblk();
    volatile display_cblk_t const * dcblk = cblk->displays + dpy;
    return dcblk->w;
}

ssize_t SurfaceComposerClient::getDisplayHeight(DisplayID dpy)
{
    if (uint32_t(dpy)>=NUM_DISPLAY_MAX)
        return BAD_VALUE;
    volatile surface_flinger_cblk_t const * cblk = get_cblk();
    volatile display_cblk_t const * dcblk = cblk->displays + dpy;
    return dcblk->h;
}

ssize_t SurfaceComposerClient::getDisplayOrientation(DisplayID dpy)
{
    if (uint32_t(dpy)>=NUM_DISPLAY_MAX)
        return BAD_VALUE;
    volatile surface_flinger_cblk_t const * cblk = get_cblk();
    volatile display_cblk_t const * dcblk = cblk->displays + dpy;
    return dcblk->orientation;
}

ssize_t SurfaceComposerClient::getNumberOfDisplays()
{
    volatile surface_flinger_cblk_t const * cblk = get_cblk();
    uint32_t connected = cblk->connected;
    int n = 0;
    while (connected) {
        if (connected&1) n++;
        connected >>= 1;
    }
    return n;
}

sp<Surface> SurfaceComposerClient::createSurface(
        int pid,
        DisplayID display,
        uint32_t w,
        uint32_t h,
        PixelFormat format,
        uint32_t flags)
{
    sp<Surface> result;
    if (mStatus == NO_ERROR) {
        ISurfaceFlingerClient::surface_data_t data;
        sp<ISurface> surface = mClient->createSurface(&data, pid,
                display, w, h, format, flags);
        if (surface != 0) {
            if (uint32_t(data.token) < NUM_LAYERS_MAX) {
                result = new Surface(this, surface, data, w, h, format, flags);
            }
        }
    }
    return result;
}

status_t SurfaceComposerClient::destroySurface(SurfaceID sid)
{
    if (mStatus != NO_ERROR)
        return mStatus;

    // it's okay to destroy a surface while a transaction is open,
    // (transactions really are a client-side concept)
    // however, this indicates probably a misuse of the API or a bug
    // in the client code.
    LOGW_IF(mTransactionOpen,
         "Destroying surface while a transaction is open. "
         "Client %p: destroying surface %d, mTransactionOpen=%d",
         this, sid, mTransactionOpen);

    status_t err = mClient->destroySurface(sid);
    return err;
}

status_t SurfaceComposerClient::nextBuffer(Surface* surface,
                        Surface::SurfaceInfo* info)
{
    SurfaceID index = surface->ID();
    per_client_cblk_t* const cblk = mControl;
    status_t err = validateSurface(cblk, surface);
    if (err != NO_ERROR)
        return err;

    int32_t backIdx = surface->mBackbufferIndex;
    layer_cblk_t* const lcblk = &(cblk->layers[index]);
    const surface_info_t* const front = lcblk->surface + (1-backIdx);
        info->w      = front->w;
        info->h      = front->h;
        info->format = front->format;
        info->base   = surface->heapBase(1-backIdx);
        info->bits   = reinterpret_cast<void*>(intptr_t(info->base) + front->bits_offset);
        info->bpr    = front->bpr;

    return 0;
}

status_t SurfaceComposerClient::lockSurface(
        Surface* surface,
        Surface::SurfaceInfo* other,
        Region* dirty,
        bool blocking)
{
    Mutex::Autolock _l(surface->getLock());

    SurfaceID index = surface->ID();
    per_client_cblk_t* const cblk = mControl;
    status_t err = validateSurface(cblk, surface);
    if (err != NO_ERROR)
        return err;

    int32_t backIdx = cblk->lock_layer(size_t(index),
            per_client_cblk_t::BLOCKING);
    if (backIdx >= 0) {
        surface->mBackbufferIndex = backIdx;
        layer_cblk_t* const lcblk = &(cblk->layers[index]);
        const surface_info_t* const back = lcblk->surface + backIdx;
        const surface_info_t* const front = lcblk->surface + (1-backIdx);
            other->w      = back->w;
            other->h      = back->h;
            other->format = back->format;
            other->base   = surface->heapBase(backIdx);
            other->bits   = reinterpret_cast<void*>(intptr_t(other->base) + back->bits_offset);
            other->bpr    = back->bpr;

        const Rect bounds(other->w, other->h);
        Region newDirtyRegion;

        if (back->flags & surface_info_t::eBufferDirty) {
            /* it is safe to write *back here, because we're guaranteed
             * SurfaceFlinger is not touching it (since it just granted
             * access to us) */
            const_cast<surface_info_t*>(back)->flags &=
                    ~surface_info_t::eBufferDirty;

            // content is meaningless in this case and the whole surface
            // needs to be redrawn.

            newDirtyRegion.set(bounds);
            if (dirty) {
                *dirty = newDirtyRegion;
            }

            //if (bytesPerPixel(other->format) == 4) {
            //    android_memset32(
            //        (uint32_t*)other->bits, 0xFF00FF00, other->h * other->bpr);
            //} else {
            //    android_memset16( // fill with green
            //        (uint16_t*)other->bits, 0x7E0, other->h * other->bpr);
            //}
        }
        else
        {
            if (dirty) {
                dirty->andSelf(Region(bounds));
                newDirtyRegion = *dirty;
            } else {
                newDirtyRegion.set(bounds);
            }

            Region copyback;
            if (!(lcblk->flags & eNoCopyBack)) {
                const Region previousDirtyRegion(surface->dirtyRegion());
                copyback = previousDirtyRegion.subtract(newDirtyRegion);
            }

            if (!copyback.isEmpty()) {
                // copy front to back
                GGLSurface cb;
                    cb.version = sizeof(GGLSurface);
                    cb.width = back->w;
                    cb.height = back->h;
                    cb.stride = back->stride;
                    cb.data = (GGLubyte*)surface->heapBase(backIdx);
                    cb.data += back->bits_offset;
                    cb.format = back->format;

                GGLSurface t;
                    t.version = sizeof(GGLSurface);
                    t.width = front->w;
                    t.height = front->h;
                    t.stride = front->stride;
                    t.data = (GGLubyte*)surface->heapBase(1-backIdx);
                    t.data += front->bits_offset;
                    t.format = front->format;

                //const Region copyback(lcblk->region + 1-backIdx);
                copyBlt(cb, t, copyback);
            }
        }

        // update dirty region
        surface->setDirtyRegion(newDirtyRegion);
    }
    return (backIdx < 0) ? status_t(backIdx) : status_t(NO_ERROR);
}

void SurfaceComposerClient::_signal_server()
{
    mSignalServer->signal();
}

void SurfaceComposerClient::_send_dirty_region(
        layer_cblk_t* lcblk, const Region& dirty)
{
    const int32_t index = (lcblk->flags & eBufferIndex) >> eBufferIndexShift;
    flat_region_t* flat_region = lcblk->region + index;
    status_t err = dirty.write(flat_region, sizeof(flat_region_t));
    if (err < NO_ERROR) {
        // region doesn't fit, use the bounds
        const Region reg(dirty.bounds());
        reg.write(flat_region, sizeof(flat_region_t));
    }
}

status_t SurfaceComposerClient::unlockAndPostSurface(Surface* surface)
{
    Mutex::Autolock _l(surface->getLock());

    SurfaceID index = surface->ID();
    per_client_cblk_t* const cblk = mControl;
    status_t err = validateSurface(cblk, surface);
    if (err != NO_ERROR)
        return err;

    Region dirty(surface->dirtyRegion());
    const Rect& swapRect(surface->swapRectangle());
    if (swapRect.isValid()) {
        dirty.set(swapRect);
    }

    // transmit the dirty region
    layer_cblk_t* const lcblk = &(cblk->layers[index]);
    _send_dirty_region(lcblk, dirty);
    uint32_t newstate = cblk->unlock_layer_and_post(size_t(index));
    if (!(newstate & eNextFlipPending))
        _signal_server();
    return NO_ERROR;
}

status_t SurfaceComposerClient::unlockSurface(Surface* surface)
{
    Mutex::Autolock _l(surface->getLock());

    SurfaceID index = surface->ID();
    per_client_cblk_t* const cblk = mControl;
    status_t err = validateSurface(cblk, surface);
    if (err != NO_ERROR)
        return err;

    layer_cblk_t* const lcblk = &(cblk->layers[index]);
    cblk->unlock_layer(size_t(index));
    return NO_ERROR;
}

void SurfaceComposerClient::openGlobalTransaction()
{
    Mutex::Autolock _l(gLock);

    if (gOpenTransactions.size()) {
        LOGE("openGlobalTransaction() called more than once. skipping.");
        return;
    }

    const size_t N = gActiveConnections.size();
    VERBOSE("openGlobalTransaction (%ld clients)", N);
    for (size_t i=0; i<N; i++) {
        sp<SurfaceComposerClient> client(gActiveConnections.valueAt(i));
        if (gOpenTransactions.indexOf(client) < 0) {
            if (client->openTransaction() == NO_ERROR) {
                if (gOpenTransactions.add(client) < 0) {
                    // Ooops!
                    LOGE(   "Unable to add a SurfaceComposerClient "
                            "to the global transaction set (out of memory?)");
                    client->closeTransaction();
                    // let it go, it'll fail later when the user
                    // tries to do something with the transaction
                }
            } else {
                LOGE("openTransaction on client %p failed", client.get());
                // let it go, it'll fail later when the user
                // tries to do something with the transaction
            }
        }
    }
}

void SurfaceComposerClient::closeGlobalTransaction()
{
    gLock.lock();
        SortedVector< sp<SurfaceComposerClient> > clients(gOpenTransactions);
        gOpenTransactions.clear();
    gLock.unlock();

    const size_t N = clients.size();
    VERBOSE("closeGlobalTransaction (%ld clients)", N);
    if (N == 1) {
        clients[0]->closeTransaction();
    } else {
        const sp<ISurfaceComposer>& sm(_get_surface_manager());
        sm->openGlobalTransaction();
        for (size_t i=0; i<N; i++) {
            clients[i]->closeTransaction();
        }
        sm->closeGlobalTransaction();
    }
}

status_t SurfaceComposerClient::freezeDisplay(DisplayID dpy, uint32_t flags)
{
    const sp<ISurfaceComposer>& sm(_get_surface_manager());
    return sm->freezeDisplay(dpy, flags);
}

status_t SurfaceComposerClient::unfreezeDisplay(DisplayID dpy, uint32_t flags)
{
    const sp<ISurfaceComposer>& sm(_get_surface_manager());
    return sm->unfreezeDisplay(dpy, flags);
}

int SurfaceComposerClient::setOrientation(DisplayID dpy, 
        int orientation, uint32_t flags)
{
    const sp<ISurfaceComposer>& sm(_get_surface_manager());
    return sm->setOrientation(dpy, orientation, flags);
}

status_t SurfaceComposerClient::openTransaction()
{
    if (mStatus != NO_ERROR)
        return mStatus;
    Mutex::Autolock _l(mLock);
    VERBOSE(   "openTransaction (client %p, mTransactionOpen=%d)",
            this, mTransactionOpen);
    mTransactionOpen++;
    if (mPrebuiltLayerState == 0) {
        mPrebuiltLayerState = new layer_state_t;
    }
    return NO_ERROR;
}


status_t SurfaceComposerClient::closeTransaction()
{
    if (mStatus != NO_ERROR)
        return mStatus;

    Mutex::Autolock _l(mLock);

    VERBOSE(   "closeTransaction (client %p, mTransactionOpen=%d)",
            this, mTransactionOpen);

    if (mTransactionOpen <= 0) {
        LOGE(   "closeTransaction (client %p, mTransactionOpen=%d) "
                "called more times than openTransaction()",
                this, mTransactionOpen);
        return INVALID_OPERATION;
    }

    if (mTransactionOpen >= 2) {
        mTransactionOpen--;
        return NO_ERROR;
    }

    mTransactionOpen = 0;
    const ssize_t count = mStates.size();
    if (count) {
        mClient->setState(count, mStates.array());
        mStates.clear();
    }
    return NO_ERROR;
}

layer_state_t* SurfaceComposerClient::_get_state_l(const sp<Surface>& surface)
{
    SurfaceID index = surface->ID();
    per_client_cblk_t* const cblk = mControl;
    status_t err = validateSurface(cblk, surface.get());
    if (err != NO_ERROR)
        return 0;

    // API usage error, do nothing.
    if (mTransactionOpen<=0) {
        LOGE("Not in transaction (client=%p, SurfaceID=%d, mTransactionOpen=%d",
                this, int(index), mTransactionOpen);
        return 0;
    }

    // use mPrebuiltLayerState just to find out if we already have it
    layer_state_t& dummy = *mPrebuiltLayerState;
    dummy.surface = index;
    ssize_t i = mStates.indexOf(dummy);
    if (i < 0) {
        // we don't have it, add an initialized layer_state to our list
        i = mStates.add(dummy);
    }
    return mStates.editArray() + i;
}

layer_state_t* SurfaceComposerClient::_lockLayerState(const sp<Surface>& surface)
{
    layer_state_t* s;
    mLock.lock();
    s = _get_state_l(surface);
    if (!s) mLock.unlock();
    return s;
}

void SurfaceComposerClient::_unlockLayerState()
{
    mLock.unlock();
}

status_t SurfaceComposerClient::setPosition(Surface* surface, int32_t x, int32_t y)
{
    layer_state_t* s = _lockLayerState(surface);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::ePositionChanged;
    s->x = x;
    s->y = y;
    _unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setSize(Surface* surface, uint32_t w, uint32_t h)
{
    layer_state_t* s = _lockLayerState(surface);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eSizeChanged;
    s->w = w;
    s->h = h;
    _unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setLayer(Surface* surface, int32_t z)
{
    layer_state_t* s = _lockLayerState(surface);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eLayerChanged;
    s->z = z;
    _unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::hide(Surface* surface)
{
    return setFlags(surface, ISurfaceComposer::eLayerHidden,
            ISurfaceComposer::eLayerHidden);
}

status_t SurfaceComposerClient::show(Surface* surface, int32_t)
{
    return setFlags(surface, 0, ISurfaceComposer::eLayerHidden);
}

status_t SurfaceComposerClient::freeze(Surface* surface)
{
    return setFlags(surface, ISurfaceComposer::eLayerFrozen,
            ISurfaceComposer::eLayerFrozen);
}

status_t SurfaceComposerClient::unfreeze(Surface* surface)
{
    return setFlags(surface, 0, ISurfaceComposer::eLayerFrozen);
}

status_t SurfaceComposerClient::setFlags(Surface* surface,
        uint32_t flags, uint32_t mask)
{
    layer_state_t* s = _lockLayerState(surface);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eVisibilityChanged;
    s->flags &= ~mask;
    s->flags |= (flags & mask);
    s->mask |= mask;
    _unlockLayerState();
    return NO_ERROR;
}


status_t SurfaceComposerClient::setTransparentRegionHint(
        Surface* surface, const Region& transparentRegion)
{
    layer_state_t* s = _lockLayerState(surface);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eTransparentRegionChanged;
    s->transparentRegion = transparentRegion;
    _unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setAlpha(Surface* surface, float alpha)
{
    layer_state_t* s = _lockLayerState(surface);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eAlphaChanged;
    s->alpha = alpha;
    _unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setMatrix(
        Surface* surface,
        float dsdx, float dtdx,
        float dsdy, float dtdy )
{
    layer_state_t* s = _lockLayerState(surface);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eMatrixChanged;
    layer_state_t::matrix22_t matrix;
    matrix.dsdx = dsdx;
    matrix.dtdx = dtdx;
    matrix.dsdy = dsdy;
    matrix.dtdy = dtdy;
    s->matrix = matrix;
    _unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setFreezeTint(Surface* surface, uint32_t tint)
{
    layer_state_t* s = _lockLayerState(surface);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eFreezeTintChanged;
    s->tint = tint;
    _unlockLayerState();
    return NO_ERROR;
}

}; // namespace android

