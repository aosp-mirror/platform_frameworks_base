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
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/threads.h>
#include <utils/SortedVector.h>
#include <utils/Log.h>
#include <utils/Singleton.h>

#include <binder/IServiceManager.h>
#include <binder/IMemory.h>

#include <ui/DisplayInfo.h>

#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/ISurfaceComposerClient.h>
#include <surfaceflinger/ISurface.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include <private/surfaceflinger/LayerState.h>
#include <private/surfaceflinger/SharedBufferStack.h>


namespace android {
// ---------------------------------------------------------------------------

ANDROID_SINGLETON_STATIC_INSTANCE(ComposerService);

ComposerService::ComposerService()
: Singleton<ComposerService>() {
    const String16 name("SurfaceFlinger");
    while (getService(name, &mComposerService) != NO_ERROR) {
        usleep(250000);
    }
    mServerCblkMemory = mComposerService->getCblk();
    mServerCblk = static_cast<surface_flinger_cblk_t volatile *>(
            mServerCblkMemory->getBase());
}

sp<ISurfaceComposer> ComposerService::getComposerService() {
    return ComposerService::getInstance().mComposerService;
}

surface_flinger_cblk_t const volatile * ComposerService::getControlBlock() {
    return ComposerService::getInstance().mServerCblk;
}

static inline sp<ISurfaceComposer> getComposerService() {
    return ComposerService::getComposerService();
}

static inline surface_flinger_cblk_t const volatile * get_cblk() {
    return ComposerService::getControlBlock();
}

// ---------------------------------------------------------------------------

class Composer : public Singleton<Composer>
{
    Mutex mLock;
    SortedVector< wp<SurfaceComposerClient> > mActiveConnections;
    SortedVector<sp<SurfaceComposerClient> > mOpenTransactions;

    Composer() : Singleton<Composer>() {
    }

    void addClientImpl(const sp<SurfaceComposerClient>& client) {
        Mutex::Autolock _l(mLock);
        mActiveConnections.add(client);
    }

    void removeClientImpl(const sp<SurfaceComposerClient>& client) {
        Mutex::Autolock _l(mLock);
        mActiveConnections.remove(client);
    }

    void openGlobalTransactionImpl()
    {
        Mutex::Autolock _l(mLock);
        if (mOpenTransactions.size()) {
            LOGE("openGlobalTransaction() called more than once. skipping.");
            return;
        }
        const size_t N = mActiveConnections.size();
        for (size_t i=0; i<N; i++) {
            sp<SurfaceComposerClient> client(mActiveConnections[i].promote());
            if (client != 0 && mOpenTransactions.indexOf(client) < 0) {
                if (client->openTransaction() == NO_ERROR) {
                    mOpenTransactions.add(client);
                } else {
                    LOGE("openTransaction on client %p failed", client.get());
                    // let it go, it'll fail later when the user
                    // tries to do something with the transaction
                }
            }
        }
    }

    void closeGlobalTransactionImpl()
    {
        mLock.lock();
            SortedVector< sp<SurfaceComposerClient> > clients(mOpenTransactions);
            mOpenTransactions.clear();
        mLock.unlock();

        sp<ISurfaceComposer> sm(getComposerService());
        sm->openGlobalTransaction();
            const size_t N = clients.size();
            for (size_t i=0; i<N; i++) {
                clients[i]->closeTransaction();
            }
        sm->closeGlobalTransaction();
    }

    friend class Singleton<Composer>;

public:
    static void addClient(const sp<SurfaceComposerClient>& client) {
        Composer::getInstance().addClientImpl(client);
    }
    static void removeClient(const sp<SurfaceComposerClient>& client) {
        Composer::getInstance().removeClientImpl(client);
    }
    static void openGlobalTransaction() {
        Composer::getInstance().openGlobalTransactionImpl();
    }
    static void closeGlobalTransaction() {
        Composer::getInstance().closeGlobalTransactionImpl();
    }
};

ANDROID_SINGLETON_STATIC_INSTANCE(Composer);

// ---------------------------------------------------------------------------

static inline int compare_type( const layer_state_t& lhs,
                                const layer_state_t& rhs) {
    if (lhs.surface < rhs.surface)  return -1;
    if (lhs.surface > rhs.surface)  return 1;
    return 0;
}

SurfaceComposerClient::SurfaceComposerClient()
    : mTransactionOpen(0), mPrebuiltLayerState(0), mStatus(NO_INIT)
{
}

void SurfaceComposerClient::onFirstRef()
{
    sp<ISurfaceComposer> sm(getComposerService());
    if (sm != 0) {
        sp<ISurfaceComposerClient> conn = sm->createConnection();
        if (conn != 0) {
            mClient = conn;
            Composer::addClient(this);
            mPrebuiltLayerState = new layer_state_t;
            mStatus = NO_ERROR;
        }
    }
}

SurfaceComposerClient::~SurfaceComposerClient()
{
    delete mPrebuiltLayerState;
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

status_t SurfaceComposerClient::linkToComposerDeath(
        const sp<IBinder::DeathRecipient>& recipient,
        void* cookie, uint32_t flags)
{
    sp<ISurfaceComposer> sm(getComposerService());
    return sm->asBinder()->linkToDeath(recipient, cookie, flags);
}

void SurfaceComposerClient::dispose()
{
    // this can be called more than once.
    sp<ISurfaceComposerClient> client;
    Mutex::Autolock _lm(mLock);
    if (mClient != 0) {
        Composer::removeClient(this);
        client = mClient; // hold ref while lock is held
        mClient.clear();
    }
    mStatus = NO_INIT;
}

status_t SurfaceComposerClient::getDisplayInfo(
        DisplayID dpy, DisplayInfo* info)
{
    if (uint32_t(dpy)>=SharedBufferStack::NUM_DISPLAY_MAX)
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
    if (uint32_t(dpy)>=SharedBufferStack::NUM_DISPLAY_MAX)
        return BAD_VALUE;
    volatile surface_flinger_cblk_t const * cblk = get_cblk();
    volatile display_cblk_t const * dcblk = cblk->displays + dpy;
    return dcblk->w;
}

ssize_t SurfaceComposerClient::getDisplayHeight(DisplayID dpy)
{
    if (uint32_t(dpy)>=SharedBufferStack::NUM_DISPLAY_MAX)
        return BAD_VALUE;
    volatile surface_flinger_cblk_t const * cblk = get_cblk();
    volatile display_cblk_t const * dcblk = cblk->displays + dpy;
    return dcblk->h;
}

ssize_t SurfaceComposerClient::getDisplayOrientation(DisplayID dpy)
{
    if (uint32_t(dpy)>=SharedBufferStack::NUM_DISPLAY_MAX)
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

sp<SurfaceControl> SurfaceComposerClient::createSurface(
        int pid,
        DisplayID display,
        uint32_t w,
        uint32_t h,
        PixelFormat format,
        uint32_t flags)
{
    String8 name;
    const size_t SIZE = 128;
    char buffer[SIZE];
    snprintf(buffer, SIZE, "<pid_%d>", getpid());
    name.append(buffer);

    return SurfaceComposerClient::createSurface(pid, name, display,
            w, h, format, flags);
}

sp<SurfaceControl> SurfaceComposerClient::createSurface(
        int pid,
        const String8& name,
        DisplayID display,
        uint32_t w,
        uint32_t h,
        PixelFormat format,
        uint32_t flags)
{
    sp<SurfaceControl> result;
    if (mStatus == NO_ERROR) {
        ISurfaceComposerClient::surface_data_t data;
        sp<ISurface> surface = mClient->createSurface(&data, pid, name,
                display, w, h, format, flags);
        if (surface != 0) {
            if (uint32_t(data.token) < SharedBufferStack::NUM_LAYERS_MAX) {
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
    Composer::openGlobalTransaction();
}

void SurfaceComposerClient::closeGlobalTransaction()
{
    Composer::closeGlobalTransaction();
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
    mTransactionOpen++;
    return NO_ERROR;
}

status_t SurfaceComposerClient::closeTransaction()
{
    if (mStatus != NO_ERROR)
        return mStatus;

    Mutex::Autolock _l(mLock);
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

layer_state_t* SurfaceComposerClient::get_state_l(SurfaceID index)
{
    // API usage error, do nothing.
    if (mTransactionOpen<=0) {
        LOGE("Not in transaction (client=%p, SurfaceID=%d, mTransactionOpen=%d",
                this, int(index), mTransactionOpen);
        return 0;
    }

    // use mPrebuiltLayerState just to find out if we already have it
    layer_state_t& dummy(*mPrebuiltLayerState);
    dummy.surface = index;
    ssize_t i = mStates.indexOf(dummy);
    if (i < 0) {
        // we don't have it, add an initialized layer_state to our list
        i = mStates.add(dummy);
    }
    return mStates.editArray() + i;
}

layer_state_t* SurfaceComposerClient::lockLayerState(SurfaceID id)
{
    layer_state_t* s;
    mLock.lock();
    s = get_state_l(id);
    if (!s) mLock.unlock();
    return s;
}

void SurfaceComposerClient::unlockLayerState()
{
    mLock.unlock();
}

status_t SurfaceComposerClient::setPosition(SurfaceID id, int32_t x, int32_t y)
{
    layer_state_t* s = lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::ePositionChanged;
    s->x = x;
    s->y = y;
    unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setSize(SurfaceID id, uint32_t w, uint32_t h)
{
    layer_state_t* s = lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eSizeChanged;
    s->w = w;
    s->h = h;
    unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setLayer(SurfaceID id, int32_t z)
{
    layer_state_t* s = lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eLayerChanged;
    s->z = z;
    unlockLayerState();
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
    layer_state_t* s = lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eVisibilityChanged;
    s->flags &= ~mask;
    s->flags |= (flags & mask);
    s->mask |= mask;
    unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setTransparentRegionHint(
        SurfaceID id, const Region& transparentRegion)
{
    layer_state_t* s = lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eTransparentRegionChanged;
    s->transparentRegion = transparentRegion;
    unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setAlpha(SurfaceID id, float alpha)
{
    layer_state_t* s = lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eAlphaChanged;
    s->alpha = alpha;
    unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setMatrix(
        SurfaceID id,
        float dsdx, float dtdx,
        float dsdy, float dtdy )
{
    layer_state_t* s = lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eMatrixChanged;
    layer_state_t::matrix22_t matrix;
    matrix.dsdx = dsdx;
    matrix.dtdx = dtdx;
    matrix.dsdy = dsdy;
    matrix.dtdy = dtdy;
    s->matrix = matrix;
    unlockLayerState();
    return NO_ERROR;
}

status_t SurfaceComposerClient::setFreezeTint(SurfaceID id, uint32_t tint)
{
    layer_state_t* s = lockLayerState(id);
    if (!s) return BAD_INDEX;
    s->what |= ISurfaceComposer::eFreezeTintChanged;
    s->tint = tint;
    unlockLayerState();
    return NO_ERROR;
}

// ----------------------------------------------------------------------------
}; // namespace android

