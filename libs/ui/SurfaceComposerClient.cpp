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
#include <binder/IServiceManager.h>
#include <binder/IMemory.h>
#include <utils/Log.h>

#include <ui/DisplayInfo.h>
#include <ui/ISurfaceComposer.h>
#include <ui/ISurfaceFlingerClient.h>
#include <ui/ISurface.h>
#include <ui/SurfaceComposerClient.h>
#include <ui/Rect.h>

#include <private/ui/LayerState.h>
#include <private/ui/SharedBufferStack.h>

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
static sp<IMemoryHeap>                                      gServerCblkMemory;
static volatile surface_flinger_cblk_t*                     gServerCblk;

static sp<ISurfaceComposer> getComposerService()
{
    sp<ISurfaceComposer> sc;
    Mutex::Autolock _l(gLock);
    if (gSurfaceManager != 0) {
        sc = gSurfaceManager;
    } else {
        // release the lock while we're waiting...
        gLock.unlock();

        sp<IBinder> binder;
        sp<IServiceManager> sm = defaultServiceManager();
        do {
            binder = sm->getService(String16("SurfaceFlinger"));
            if (binder == 0) {
                LOGW("SurfaceFlinger not published, waiting...");
                usleep(500000); // 0.5 s
            }
        } while(binder == 0);

        // grab the lock again for updating gSurfaceManager
        gLock.lock();
        if (gSurfaceManager == 0) {
            sc = interface_cast<ISurfaceComposer>(binder);
            gSurfaceManager = sc;
        } else {
            sc = gSurfaceManager;
        }
    }
    return sc;
}

static volatile surface_flinger_cblk_t const * get_cblk()
{
    if (gServerCblk == 0) {
        sp<ISurfaceComposer> sm(getComposerService());
        Mutex::Autolock _l(gLock);
        if (gServerCblk == 0) {
            gServerCblkMemory = sm->getCblk();
            LOGE_IF(gServerCblkMemory==0, "Can't get server control block");
            gServerCblk = (surface_flinger_cblk_t *)gServerCblkMemory->getBase();
            LOGE_IF(gServerCblk==0, "Can't get server control block address");
        }
    }
    return gServerCblk;
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
    sp<ISurfaceComposer> sm(getComposerService());
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


status_t SurfaceComposerClient::linkToComposerDeath(
        const sp<IBinder::DeathRecipient>& recipient,
        void* cookie, uint32_t flags)
{
    sp<ISurfaceComposer> sm(getComposerService());
    return sm->asBinder()->linkToDeath(recipient, cookie, flags);    
}

void SurfaceComposerClient::_init(
        const sp<ISurfaceComposer>& sm, const sp<ISurfaceFlingerClient>& conn)
{
    VERBOSE("Creating client %p, conn %p", this, conn.get());

    mPrebuiltLayerState = 0;
    mTransactionOpen = 0;
    mStatus = NO_ERROR;
    mControl = 0;

    mClient = conn;
    if (mClient == 0) {
        mStatus = NO_INIT;
        return;
    }

    mControlMemory = mClient->getControlBlock();
    mSignalServer = sm;
    mControl = static_cast<SharedClient *>(mControlMemory->getBase());
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
        sp<ISurfaceComposer> sm(getComposerService());
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

    sp<IMemoryHeap>             controlMemory;
    sp<ISurfaceFlingerClient>   client;

    {
        Mutex::Autolock _lg(gLock);
        Mutex::Autolock _lm(mLock);

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
        mControlMemory.clear();
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


void SurfaceComposerClient::signalServer()
{
    mSignalServer->signal();
}

sp<SurfaceControl> SurfaceComposerClient::createSurface(
        int pid,
        DisplayID display,
        uint32_t w,
        uint32_t h,
        PixelFormat format,
        uint32_t flags)
{
    sp<SurfaceControl> result;
    if (mStatus == NO_ERROR) {
        ISurfaceFlingerClient::surface_data_t data;
        sp<ISurface> surface = mClient->createSurface(&data, pid,
                display, w, h, format, flags);
        if (surface != 0) {
            if (uint32_t(data.token) < NUM_LAYERS_MAX) {
                result = new SurfaceControl(this, surface, data, w, h, format, flags);
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

    sp<ISurfaceComposer> sm(getComposerService());
    sm->openGlobalTransaction();
    for (size_t i=0; i<N; i++) {
        clients[i]->closeTransaction();
    }
    sm->closeGlobalTransaction();

}


status_t SurfaceComposerClient::freezeDisplay(DisplayID dpy, uint32_t flags)
{
    sp<ISurfaceComposer> sm(getComposerService());
    return sm->freezeDisplay(dpy, flags);
}

status_t SurfaceComposerClient::unfreezeDisplay(DisplayID dpy, uint32_t flags)
{
    sp<ISurfaceComposer> sm(getComposerService());
    return sm->unfreezeDisplay(dpy, flags);
}

int SurfaceComposerClient::setOrientation(DisplayID dpy, 
        int orientation, uint32_t flags)
{
    sp<ISurfaceComposer> sm(getComposerService());
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

layer_state_t* SurfaceComposerClient::_get_state_l(SurfaceID index)
{
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

layer_state_t* SurfaceComposerClient::_lockLayerState(SurfaceID id)
{
    layer_state_t* s;
    mLock.lock();
    s = _get_state_l(id);
    if (!s) mLock.unlock();
    return s;
}

void SurfaceComposerClient::_unlockLayerState()
{
    mLock.unlock();
}

status_t SurfaceComposerClient::setPosition(SurfaceID id, int32_t x, int32_t y)
{
    layer_state_t* s = _lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::ePositionChanged;
    s->x = x;
    s->y = y;
    _unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setSize(SurfaceID id, uint32_t w, uint32_t h)
{
    layer_state_t* s = _lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eSizeChanged;
    s->w = w;
    s->h = h;
    _unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setLayer(SurfaceID id, int32_t z)
{
    layer_state_t* s = _lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eLayerChanged;
    s->z = z;
    _unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::hide(SurfaceID id)
{
    return setFlags(id, ISurfaceComposer::eLayerHidden,
            ISurfaceComposer::eLayerHidden);
}

status_t SurfaceComposerClient::show(SurfaceID id, int32_t)
{
    return setFlags(id, 0, ISurfaceComposer::eLayerHidden);
}

status_t SurfaceComposerClient::freeze(SurfaceID id)
{
    return setFlags(id, ISurfaceComposer::eLayerFrozen,
            ISurfaceComposer::eLayerFrozen);
}

status_t SurfaceComposerClient::unfreeze(SurfaceID id)
{
    return setFlags(id, 0, ISurfaceComposer::eLayerFrozen);
}

status_t SurfaceComposerClient::setFlags(SurfaceID id,
        uint32_t flags, uint32_t mask)
{
    layer_state_t* s = _lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eVisibilityChanged;
    s->flags &= ~mask;
    s->flags |= (flags & mask);
    s->mask |= mask;
    _unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setTransparentRegionHint(
        SurfaceID id, const Region& transparentRegion)
{
    layer_state_t* s = _lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eTransparentRegionChanged;
    s->transparentRegion = transparentRegion;
    _unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setAlpha(SurfaceID id, float alpha)
{
    layer_state_t* s = _lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eAlphaChanged;
    s->alpha = alpha;
    _unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setMatrix(
        SurfaceID id,
        float dsdx, float dtdx,
        float dsdy, float dtdy )
{
    layer_state_t* s = _lockLayerState(id);
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

status_t SurfaceComposerClient::setFreezeTint(SurfaceID id, uint32_t tint)
{
    layer_state_t* s = _lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eFreezeTintChanged;
    s->tint = tint;
    _unlockLayerState();
    return NO_ERROR;
}

}; // namespace android

