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
#include <utils/Log.h>
#include <utils/Singleton.h>
#include <utils/SortedVector.h>
#include <utils/String8.h>
#include <utils/threads.h>

#include <binder/IMemory.h>
#include <binder/IServiceManager.h>

#include <ui/DisplayInfo.h>

#include <surfaceflinger/ISurface.h>
#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/ISurfaceComposerClient.h>
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

// NOTE: this is NOT a member function (it's a friend defined with its
// declaration).
static inline
int compare_type( const ComposerState& lhs, const ComposerState& rhs) {
    if (lhs.client < rhs.client)  return -1;
    if (lhs.client > rhs.client)  return 1;
    if (lhs.state.surface < rhs.state.surface)  return -1;
    if (lhs.state.surface > rhs.state.surface)  return 1;
    return 0;
}

class Composer : public Singleton<Composer>
{
    friend class Singleton<Composer>;

    mutable Mutex               mLock;
    SortedVector<ComposerState> mStates;

    Composer() : Singleton<Composer>() { }

    void closeGlobalTransactionImpl();

    layer_state_t* getLayerStateLocked(
            const sp<SurfaceComposerClient>& client, SurfaceID id);

public:

    status_t setPosition(const sp<SurfaceComposerClient>& client, SurfaceID id,
            int32_t x, int32_t y);
    status_t setSize(const sp<SurfaceComposerClient>& client, SurfaceID id,
            uint32_t w, uint32_t h);
    status_t setLayer(const sp<SurfaceComposerClient>& client, SurfaceID id,
            int32_t z);
    status_t setFlags(const sp<SurfaceComposerClient>& client, SurfaceID id,
            uint32_t flags, uint32_t mask);
    status_t setTransparentRegionHint(
            const sp<SurfaceComposerClient>& client, SurfaceID id,
            const Region& transparentRegion);
    status_t setAlpha(const sp<SurfaceComposerClient>& client, SurfaceID id,
            float alpha);
    status_t setMatrix(const sp<SurfaceComposerClient>& client, SurfaceID id,
            float dsdx, float dtdx, float dsdy, float dtdy);
    status_t setFreezeTint(
            const sp<SurfaceComposerClient>& client, SurfaceID id,
            uint32_t tint);

    static void closeGlobalTransaction() {
        Composer::getInstance().closeGlobalTransactionImpl();
    }
};

ANDROID_SINGLETON_STATIC_INSTANCE(Composer);

// ---------------------------------------------------------------------------

void Composer::closeGlobalTransactionImpl() {
    sp<ISurfaceComposer> sm(getComposerService());

    Vector<ComposerState> transaction;

    { // scope for the lock
        Mutex::Autolock _l(mLock);
        transaction = mStates;
        mStates.clear();
    }

   sm->setTransactionState(transaction);
}

layer_state_t* Composer::getLayerStateLocked(
        const sp<SurfaceComposerClient>& client, SurfaceID id) {

    ComposerState s;
    s.client = client->mClient;
    s.state.surface = id;

    ssize_t index = mStates.indexOf(s);
    if (index < 0) {
        // we don't have it, add an initialized layer_state to our list
        index = mStates.add(s);
    }

    ComposerState* const out = mStates.editArray();
    return &(out[index].state);
}

status_t Composer::setPosition(const sp<SurfaceComposerClient>& client,
        SurfaceID id, int32_t x, int32_t y) {
    Mutex::Autolock _l(mLock);
    layer_state_t* s = getLayerStateLocked(client, id);
    if (!s)
        return BAD_INDEX;
    s->what |= ISurfaceComposer::ePositionChanged;
    s->x = x;
    s->y = y;
    return NO_ERROR;
}

status_t Composer::setSize(const sp<SurfaceComposerClient>& client,
        SurfaceID id, uint32_t w, uint32_t h) {
    Mutex::Autolock _l(mLock);
    layer_state_t* s = getLayerStateLocked(client, id);
    if (!s)
        return BAD_INDEX;
    s->what |= ISurfaceComposer::eSizeChanged;
    s->w = w;
    s->h = h;
    return NO_ERROR;
}

status_t Composer::setLayer(const sp<SurfaceComposerClient>& client,
        SurfaceID id, int32_t z) {
    Mutex::Autolock _l(mLock);
    layer_state_t* s = getLayerStateLocked(client, id);
    if (!s)
        return BAD_INDEX;
    s->what |= ISurfaceComposer::eLayerChanged;
    s->z = z;
    return NO_ERROR;
}

status_t Composer::setFlags(const sp<SurfaceComposerClient>& client,
        SurfaceID id, uint32_t flags,
        uint32_t mask) {
    Mutex::Autolock _l(mLock);
    layer_state_t* s = getLayerStateLocked(client, id);
    if (!s)
        return BAD_INDEX;
    s->what |= ISurfaceComposer::eVisibilityChanged;
    s->flags &= ~mask;
    s->flags |= (flags & mask);
    s->mask |= mask;
    return NO_ERROR;
}

status_t Composer::setTransparentRegionHint(
        const sp<SurfaceComposerClient>& client, SurfaceID id,
        const Region& transparentRegion) {
    Mutex::Autolock _l(mLock);
    layer_state_t* s = getLayerStateLocked(client, id);
    if (!s)
        return BAD_INDEX;
    s->what |= ISurfaceComposer::eTransparentRegionChanged;
    s->transparentRegion = transparentRegion;
    return NO_ERROR;
}

status_t Composer::setAlpha(const sp<SurfaceComposerClient>& client,
        SurfaceID id, float alpha) {
    Mutex::Autolock _l(mLock);
    layer_state_t* s = getLayerStateLocked(client, id);
    if (!s)
        return BAD_INDEX;
    s->what |= ISurfaceComposer::eAlphaChanged;
    s->alpha = alpha;
    return NO_ERROR;
}

status_t Composer::setMatrix(const sp<SurfaceComposerClient>& client,
        SurfaceID id, float dsdx, float dtdx,
        float dsdy, float dtdy) {
    Mutex::Autolock _l(mLock);
    layer_state_t* s = getLayerStateLocked(client, id);
    if (!s)
        return BAD_INDEX;
    s->what |= ISurfaceComposer::eMatrixChanged;
    layer_state_t::matrix22_t matrix;
    matrix.dsdx = dsdx;
    matrix.dtdx = dtdx;
    matrix.dsdy = dsdy;
    matrix.dtdy = dtdy;
    s->matrix = matrix;
    return NO_ERROR;
}

status_t Composer::setFreezeTint(const sp<SurfaceComposerClient>& client,
        SurfaceID id, uint32_t tint) {
    Mutex::Autolock _l(mLock);
    layer_state_t* s = getLayerStateLocked(client, id);
    if (!s)
        return BAD_INDEX;
    s->what |= ISurfaceComposer::eFreezeTintChanged;
    s->tint = tint;
    return NO_ERROR;
}

// ---------------------------------------------------------------------------

SurfaceComposerClient::SurfaceComposerClient()
    : mStatus(NO_INIT), mComposer(Composer::getInstance())
{
}

void SurfaceComposerClient::onFirstRef() {
    sp<ISurfaceComposer> sm(getComposerService());
    if (sm != 0) {
        sp<ISurfaceComposerClient> conn = sm->createConnection();
        if (conn != 0) {
            mClient = conn;
            mStatus = NO_ERROR;
        }
    }
}

SurfaceComposerClient::~SurfaceComposerClient() {
    dispose();
}

status_t SurfaceComposerClient::initCheck() const {
    return mStatus;
}

sp<IBinder> SurfaceComposerClient::connection() const {
    return (mClient != 0) ? mClient->asBinder() : 0;
}

status_t SurfaceComposerClient::linkToComposerDeath(
        const sp<IBinder::DeathRecipient>& recipient,
        void* cookie, uint32_t flags) {
    sp<ISurfaceComposer> sm(getComposerService());
    return sm->asBinder()->linkToDeath(recipient, cookie, flags);
}

void SurfaceComposerClient::dispose() {
    // this can be called more than once.
    sp<ISurfaceComposerClient> client;
    Mutex::Autolock _lm(mLock);
    if (mClient != 0) {
        client = mClient; // hold ref while lock is held
        mClient.clear();
    }
    mStatus = NO_INIT;
}

sp<SurfaceControl> SurfaceComposerClient::createSurface(
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

    return SurfaceComposerClient::createSurface(name, display,
            w, h, format, flags);
}

sp<SurfaceControl> SurfaceComposerClient::createSurface(
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
        sp<ISurface> surface = mClient->createSurface(&data, name,
                display, w, h, format, flags);
        if (surface != 0) {
            result = new SurfaceControl(this, surface, data);
        }
    }
    return result;
}

status_t SurfaceComposerClient::destroySurface(SurfaceID sid) {
    if (mStatus != NO_ERROR)
        return mStatus;
    status_t err = mClient->destroySurface(sid);
    return err;
}

inline Composer& SurfaceComposerClient::getComposer() {
    return mComposer;
}

// ----------------------------------------------------------------------------

void SurfaceComposerClient::openGlobalTransaction() {
    // Currently a no-op
}

void SurfaceComposerClient::closeGlobalTransaction() {
    Composer::closeGlobalTransaction();
}

// ----------------------------------------------------------------------------

status_t SurfaceComposerClient::setFreezeTint(SurfaceID id, uint32_t tint) {
    return getComposer().setFreezeTint(this, id, tint);
}

status_t SurfaceComposerClient::setPosition(SurfaceID id, int32_t x, int32_t y) {
    return getComposer().setPosition(this, id, x, y);
}

status_t SurfaceComposerClient::setSize(SurfaceID id, uint32_t w, uint32_t h) {
    return getComposer().setSize(this, id, w, h);
}

status_t SurfaceComposerClient::setLayer(SurfaceID id, int32_t z) {
    return getComposer().setLayer(this, id, z);
}

status_t SurfaceComposerClient::hide(SurfaceID id) {
    return getComposer().setFlags(this, id,
            ISurfaceComposer::eLayerHidden,
            ISurfaceComposer::eLayerHidden);
}

status_t SurfaceComposerClient::show(SurfaceID id, int32_t) {
    return getComposer().setFlags(this, id,
            0,
            ISurfaceComposer::eLayerHidden);
}

status_t SurfaceComposerClient::freeze(SurfaceID id) {
    return getComposer().setFlags(this, id,
            ISurfaceComposer::eLayerFrozen,
            ISurfaceComposer::eLayerFrozen);
}

status_t SurfaceComposerClient::unfreeze(SurfaceID id) {
    return getComposer().setFlags(this, id,
            0,
            ISurfaceComposer::eLayerFrozen);
}

status_t SurfaceComposerClient::setFlags(SurfaceID id, uint32_t flags,
        uint32_t mask) {
    return getComposer().setFlags(this, id, flags, mask);
}

status_t SurfaceComposerClient::setTransparentRegionHint(SurfaceID id,
        const Region& transparentRegion) {
    return getComposer().setTransparentRegionHint(this, id, transparentRegion);
}

status_t SurfaceComposerClient::setAlpha(SurfaceID id, float alpha) {
    return getComposer().setAlpha(this, id, alpha);
}

status_t SurfaceComposerClient::setMatrix(SurfaceID id, float dsdx, float dtdx,
        float dsdy, float dtdy) {
    return getComposer().setMatrix(this, id, dsdx, dtdx, dsdy, dtdy);
}

// ----------------------------------------------------------------------------

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

// ----------------------------------------------------------------------------

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

// ----------------------------------------------------------------------------

ScreenshotClient::ScreenshotClient()
    : mWidth(0), mHeight(0), mFormat(PIXEL_FORMAT_NONE) {
}

status_t ScreenshotClient::update() {
    sp<ISurfaceComposer> s(ComposerService::getComposerService());
    if (s == NULL) return NO_INIT;
    mHeap = 0;
    return s->captureScreen(0, &mHeap,
            &mWidth, &mHeight, &mFormat, 0, 0,
            0, -1UL);
}

status_t ScreenshotClient::update(uint32_t reqWidth, uint32_t reqHeight) {
    sp<ISurfaceComposer> s(ComposerService::getComposerService());
    if (s == NULL) return NO_INIT;
    mHeap = 0;
    return s->captureScreen(0, &mHeap,
            &mWidth, &mHeight, &mFormat, reqWidth, reqHeight,
            0, -1UL);
}

status_t ScreenshotClient::update(uint32_t reqWidth, uint32_t reqHeight,
        uint32_t minLayerZ, uint32_t maxLayerZ) {
    sp<ISurfaceComposer> s(ComposerService::getComposerService());
    if (s == NULL) return NO_INIT;
    mHeap = 0;
    return s->captureScreen(0, &mHeap,
            &mWidth, &mHeight, &mFormat, reqWidth, reqHeight,
            minLayerZ, maxLayerZ);
}

void ScreenshotClient::release() {
    mHeap = 0;
}

void const* ScreenshotClient::getPixels() const {
    return mHeap->getBase();
}

uint32_t ScreenshotClient::getWidth() const {
    return mWidth;
}

uint32_t ScreenshotClient::getHeight() const {
    return mHeight;
}

PixelFormat ScreenshotClient::getFormat() const {
    return mFormat;
}

uint32_t ScreenshotClient::getStride() const {
    return mWidth;
}

size_t ScreenshotClient::getSize() const {
    return mHeap->getSize();
}

// ----------------------------------------------------------------------------
}; // namespace android

