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
#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>

#include <GLES/gl.h>
#include <GLES/glext.h>

#include <hardware/hardware.h>

#include "clz.h"
#include "LayerBase.h"
#include "SurfaceFlinger.h"
#include "DisplayHardware/DisplayHardware.h"

namespace android {

// ---------------------------------------------------------------------------

int32_t LayerBase::sSequence = 1;

LayerBase::LayerBase(SurfaceFlinger* flinger, DisplayID display)
    : dpy(display), contentDirty(false),
      sequence(uint32_t(android_atomic_inc(&sSequence))),
      mFlinger(flinger), mFiltering(false),
      mNeedsFiltering(false),
      mOrientation(0),
      mTransactionFlags(0),
      mPremultipliedAlpha(true), mName("unnamed"), mDebug(false),
      mInvalidate(0)
{
    const DisplayHardware& hw(flinger->graphicPlane(0).displayHardware());
    mFlags = hw.getFlags();
}

LayerBase::~LayerBase()
{
}

void LayerBase::setName(const String8& name) {
    mName = name;
}

String8 LayerBase::getName() const {
    return mName;
}

const GraphicPlane& LayerBase::graphicPlane(int dpy) const
{ 
    return mFlinger->graphicPlane(dpy);
}

GraphicPlane& LayerBase::graphicPlane(int dpy)
{
    return mFlinger->graphicPlane(dpy); 
}

void LayerBase::initStates(uint32_t w, uint32_t h, uint32_t flags)
{
    uint32_t layerFlags = 0;
    if (flags & ISurfaceComposer::eHidden)
        layerFlags = ISurfaceComposer::eLayerHidden;

    if (flags & ISurfaceComposer::eNonPremultiplied)
        mPremultipliedAlpha = false;

    mCurrentState.z             = 0;
    mCurrentState.w             = w;
    mCurrentState.h             = h;
    mCurrentState.requested_w   = w;
    mCurrentState.requested_h   = h;
    mCurrentState.alpha         = 0xFF;
    mCurrentState.flags         = layerFlags;
    mCurrentState.sequence      = 0;
    mCurrentState.transform.set(0, 0);

    // drawing state & current state are identical
    mDrawingState = mCurrentState;
}

void LayerBase::commitTransaction() {
    mDrawingState = mCurrentState;
}
void LayerBase::forceVisibilityTransaction() {
    // this can be called without SurfaceFlinger.mStateLock, but if we
    // can atomically increment the sequence number, it doesn't matter.
    android_atomic_inc(&mCurrentState.sequence);
    requestTransaction();
}
bool LayerBase::requestTransaction() {
    int32_t old = setTransactionFlags(eTransactionNeeded);
    return ((old & eTransactionNeeded) == 0);
}
uint32_t LayerBase::getTransactionFlags(uint32_t flags) {
    return android_atomic_and(~flags, &mTransactionFlags) & flags;
}
uint32_t LayerBase::setTransactionFlags(uint32_t flags) {
    return android_atomic_or(flags, &mTransactionFlags);
}

bool LayerBase::setPosition(float x, float y) {
    if (mCurrentState.transform.tx() == x && mCurrentState.transform.ty() == y)
        return false;
    mCurrentState.sequence++;
    mCurrentState.transform.set(x, y);
    requestTransaction();
    return true;
}
bool LayerBase::setLayer(uint32_t z) {
    if (mCurrentState.z == z)
        return false;
    mCurrentState.sequence++;
    mCurrentState.z = z;
    requestTransaction();
    return true;
}
bool LayerBase::setSize(uint32_t w, uint32_t h) {
    if (mCurrentState.requested_w == w && mCurrentState.requested_h == h)
        return false;
    mCurrentState.requested_w = w;
    mCurrentState.requested_h = h;
    requestTransaction();
    return true;
}
bool LayerBase::setAlpha(uint8_t alpha) {
    if (mCurrentState.alpha == alpha)
        return false;
    mCurrentState.sequence++;
    mCurrentState.alpha = alpha;
    requestTransaction();
    return true;
}
bool LayerBase::setMatrix(const layer_state_t::matrix22_t& matrix) {
    mCurrentState.sequence++;
    mCurrentState.transform.set(
            matrix.dsdx, matrix.dsdy, matrix.dtdx, matrix.dtdy);
    requestTransaction();
    return true;
}
bool LayerBase::setTransparentRegionHint(const Region& transparent) {
    mCurrentState.sequence++;
    mCurrentState.transparentRegion = transparent;
    requestTransaction();
    return true;
}
bool LayerBase::setFlags(uint8_t flags, uint8_t mask) {
    const uint32_t newFlags = (mCurrentState.flags & ~mask) | (flags & mask);
    if (mCurrentState.flags == newFlags)
        return false;
    mCurrentState.sequence++;
    mCurrentState.flags = newFlags;
    requestTransaction();
    return true;
}

Rect LayerBase::visibleBounds() const
{
    return mTransformedBounds;
}      

void LayerBase::setVisibleRegion(const Region& visibleRegion) {
    // always called from main thread
    visibleRegionScreen = visibleRegion;
}

void LayerBase::setCoveredRegion(const Region& coveredRegion) {
    // always called from main thread
    coveredRegionScreen = coveredRegion;
}

uint32_t LayerBase::doTransaction(uint32_t flags)
{
    const Layer::State& front(drawingState());
    const Layer::State& temp(currentState());

    if ((front.requested_w != temp.requested_w) ||
        (front.requested_h != temp.requested_h))  {
        // resize the layer, set the physical size to the requested size
        Layer::State& editTemp(currentState());
        editTemp.w = temp.requested_w;
        editTemp.h = temp.requested_h;
    }

    if ((front.w != temp.w) || (front.h != temp.h)) {
        // invalidate and recompute the visible regions if needed
        flags |= Layer::eVisibleRegion;
    }

    if (temp.sequence != front.sequence) {
        // invalidate and recompute the visible regions if needed
        flags |= eVisibleRegion;
        this->contentDirty = true;

        // we may use linear filtering, if the matrix scales us
        const uint8_t type = temp.transform.getType();
        mNeedsFiltering = (!temp.transform.preserveRects() ||
                (type >= Transform::SCALE));
    }

    // Commit the transaction
    commitTransaction();
    return flags;
}

void LayerBase::validateVisibility(const Transform& planeTransform)
{
    const Layer::State& s(drawingState());
    const Transform tr(planeTransform * s.transform);
    const bool transformed = tr.transformed();
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const uint32_t hw_h = hw.getHeight();

    uint32_t w = s.w;
    uint32_t h = s.h;    
    tr.transform(mVertices[0], 0, 0);
    tr.transform(mVertices[1], 0, h);
    tr.transform(mVertices[2], w, h);
    tr.transform(mVertices[3], w, 0);
    for (size_t i=0 ; i<4 ; i++)
        mVertices[i][1] = hw_h - mVertices[i][1];

    if (UNLIKELY(transformed)) {
        // NOTE: here we could also punt if we have too many rectangles
        // in the transparent region
        if (tr.preserveRects()) {
            // transform the transparent region
            transparentRegionScreen = tr.transform(s.transparentRegion);
        } else {
            // transformation too complex, can't do the transparent region
            // optimization.
            transparentRegionScreen.clear();
        }
    } else {
        transparentRegionScreen = s.transparentRegion;
    }

    // cache a few things...
    mOrientation = tr.getOrientation();
    mTransform = tr;
    mTransformedBounds = tr.makeBounds(w, h);
}

void LayerBase::lockPageFlip(bool& recomputeVisibleRegions)
{
}

void LayerBase::unlockPageFlip(
        const Transform& planeTransform, Region& outDirtyRegion)
{
    if ((android_atomic_and(~1, &mInvalidate)&1) == 1) {
        outDirtyRegion.orSelf(visibleRegionScreen);
    }
}

void LayerBase::invalidate()
{
    if ((android_atomic_or(1, &mInvalidate)&1) == 0) {
        mFlinger->signalEvent();
    }
}

void LayerBase::drawRegion(const Region& reg) const
{
    Region::const_iterator it = reg.begin();
    Region::const_iterator const end = reg.end();
    if (it != end) {
        Rect r;
        const DisplayHardware& hw(graphicPlane(0).displayHardware());
        const int32_t fbWidth  = hw.getWidth();
        const int32_t fbHeight = hw.getHeight();
        const GLshort vertices[][2] = { { 0, 0 }, { fbWidth, 0 }, 
                { fbWidth, fbHeight }, { 0, fbHeight }  };
        glVertexPointer(2, GL_SHORT, 0, vertices);
        while (it != end) {
            const Rect& r = *it++;
            const GLint sy = fbHeight - (r.top + r.height());
            glScissor(r.left, sy, r.width(), r.height());
            glDrawArrays(GL_TRIANGLE_FAN, 0, 4); 
        }
    }
}

void LayerBase::setGeometry(hwc_layer_t* hwcl)
{
    hwcl->compositionType = HWC_FRAMEBUFFER;
    hwcl->hints = 0;
    hwcl->flags = HWC_SKIP_LAYER;
    hwcl->transform = 0;
    hwcl->blending = HWC_BLENDING_NONE;

    // this gives us only the "orientation" component of the transform
    const State& s(drawingState());
    const uint32_t finalTransform = s.transform.getOrientation();
    // we can only handle simple transformation
    if (finalTransform & Transform::ROT_INVALID) {
        hwcl->flags = HWC_SKIP_LAYER;
    } else {
        hwcl->transform = finalTransform;
    }

    if (!isOpaque()) {
        hwcl->blending = mPremultipliedAlpha ?
                HWC_BLENDING_PREMULT : HWC_BLENDING_COVERAGE;
    }

    // scaling is already applied in mTransformedBounds
    hwcl->displayFrame.left   = mTransformedBounds.left;
    hwcl->displayFrame.top    = mTransformedBounds.top;
    hwcl->displayFrame.right  = mTransformedBounds.right;
    hwcl->displayFrame.bottom = mTransformedBounds.bottom;
    hwcl->visibleRegionScreen.rects =
            reinterpret_cast<hwc_rect_t const *>(
                    visibleRegionScreen.getArray(
                            &hwcl->visibleRegionScreen.numRects));

    hwcl->sourceCrop.left   = 0;
    hwcl->sourceCrop.top    = 0;
    hwcl->sourceCrop.right  = mTransformedBounds.width();
    hwcl->sourceCrop.bottom = mTransformedBounds.height();
}

void LayerBase::setPerFrameData(hwc_layer_t* hwcl) {
    hwcl->compositionType = HWC_FRAMEBUFFER;
    hwcl->handle = NULL;
}

void LayerBase::setFiltering(bool filtering)
{
    mFiltering = filtering;
}

bool LayerBase::getFiltering() const
{
    return mFiltering;
}

void LayerBase::draw(const Region& clip) const
{
    // reset GL state
    glEnable(GL_SCISSOR_TEST);

    onDraw(clip);
}

void LayerBase::drawForSreenShot()
{
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    setFiltering(true);
    onDraw( Region(hw.bounds()) );
    setFiltering(false);
}

void LayerBase::clearWithOpenGL(const Region& clip, GLclampf red,
                                GLclampf green, GLclampf blue,
                                GLclampf alpha) const
{
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const uint32_t fbHeight = hw.getHeight();
    glColor4f(red,green,blue,alpha);

#if defined(GL_OES_EGL_image_external)
        if (GLExtensions::getInstance().haveTextureExternal()) {
            glDisable(GL_TEXTURE_EXTERNAL_OES);
        }
#endif
    glDisable(GL_TEXTURE_2D);
    glDisable(GL_BLEND);
    glDisable(GL_DITHER);

    Region::const_iterator it = clip.begin();
    Region::const_iterator const end = clip.end();
    glEnable(GL_SCISSOR_TEST);
    glVertexPointer(2, GL_FLOAT, 0, mVertices);
    while (it != end) {
        const Rect& r = *it++;
        const GLint sy = fbHeight - (r.top + r.height());
        glScissor(r.left, sy, r.width(), r.height());
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4); 
    }
}

void LayerBase::clearWithOpenGL(const Region& clip) const
{
    clearWithOpenGL(clip,0,0,0,0);
}

void LayerBase::drawWithOpenGL(const Region& clip) const
{
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const uint32_t fbHeight = hw.getHeight();
    const State& s(drawingState());

    GLenum src = mPremultipliedAlpha ? GL_ONE : GL_SRC_ALPHA;
    if (UNLIKELY(s.alpha < 0xFF)) {
        const GLfloat alpha = s.alpha * (1.0f/255.0f);
        if (mPremultipliedAlpha) {
            glColor4f(alpha, alpha, alpha, alpha);
        } else {
            glColor4f(1, 1, 1, alpha);
        }
        glEnable(GL_BLEND);
        glBlendFunc(src, GL_ONE_MINUS_SRC_ALPHA);
        glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);
    } else {
        glColor4f(1, 1, 1, 1);
        glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
        if (!isOpaque()) {
            glEnable(GL_BLEND);
            glBlendFunc(src, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }
    }

    struct TexCoords {
        GLfloat u;
        GLfloat v;
    };

    TexCoords texCoords[4];
    texCoords[0].u = 0;
    texCoords[0].v = 1;
    texCoords[1].u = 0;
    texCoords[1].v = 0;
    texCoords[2].u = 1;
    texCoords[2].v = 0;
    texCoords[3].u = 1;
    texCoords[3].v = 1;

    if (needsDithering()) {
        glEnable(GL_DITHER);
    } else {
        glDisable(GL_DITHER);
    }

    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glVertexPointer(2, GL_FLOAT, 0, mVertices);
    glTexCoordPointer(2, GL_FLOAT, 0, texCoords);

    Region::const_iterator it = clip.begin();
    Region::const_iterator const end = clip.end();
    while (it != end) {
        const Rect& r = *it++;
        const GLint sy = fbHeight - (r.top + r.height());
        glScissor(r.left, sy, r.width(), r.height());
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
    }
    glDisableClientState(GL_TEXTURE_COORD_ARRAY);
}

void LayerBase::dump(String8& result, char* buffer, size_t SIZE) const
{
    const Layer::State& s(drawingState());
    snprintf(buffer, SIZE,
            "+ %s %p\n"
            "      "
            "z=%9d, pos=(%g,%g), size=(%4d,%4d), "
            "isOpaque=%1d, needsDithering=%1d, invalidate=%1d, "
            "alpha=0x%02x, flags=0x%08x, tr=[%.2f, %.2f][%.2f, %.2f]\n",
            getTypeId(), this, s.z, s.transform.tx(), s.transform.ty(), s.w, s.h,
            isOpaque(), needsDithering(), contentDirty,
            s.alpha, s.flags,
            s.transform[0][0], s.transform[0][1],
            s.transform[1][0], s.transform[1][1]);
    result.append(buffer);
}

void LayerBase::shortDump(String8& result, char* scratch, size_t size) const
{
    LayerBase::dump(result, scratch, size);
}


// ---------------------------------------------------------------------------

int32_t LayerBaseClient::sIdentity = 1;

LayerBaseClient::LayerBaseClient(SurfaceFlinger* flinger, DisplayID display,
        const sp<Client>& client)
    : LayerBase(flinger, display),
      mHasSurface(false),
      mClientRef(client),
      mIdentity(uint32_t(android_atomic_inc(&sIdentity)))
{
}

LayerBaseClient::~LayerBaseClient()
{
    sp<Client> c(mClientRef.promote());
    if (c != 0) {
        c->detachLayer(this);
    }
}

sp<ISurface> LayerBaseClient::createSurface()
{
    class BSurface : public BnSurface, public LayerCleaner {
        virtual sp<ISurfaceTexture> getSurfaceTexture() const { return 0; }
    public:
        BSurface(const sp<SurfaceFlinger>& flinger,
                const sp<LayerBaseClient>& layer)
            : LayerCleaner(flinger, layer) { }
    };
    sp<ISurface> sur(new BSurface(mFlinger, this));
    return sur;
}

sp<ISurface> LayerBaseClient::getSurface()
{
    sp<ISurface> s;
    Mutex::Autolock _l(mLock);

    LOG_ALWAYS_FATAL_IF(mHasSurface,
            "LayerBaseClient::getSurface() has already been called");

    mHasSurface = true;
    s = createSurface();
    mClientSurfaceBinder = s->asBinder();
    return s;
}

wp<IBinder> LayerBaseClient::getSurfaceBinder() const {
    return mClientSurfaceBinder;
}

wp<IBinder> LayerBaseClient::getSurfaceTextureBinder() const {
    return 0;
}

void LayerBaseClient::dump(String8& result, char* buffer, size_t SIZE) const
{
    LayerBase::dump(result, buffer, SIZE);

    sp<Client> client(mClientRef.promote());
    snprintf(buffer, SIZE,
            "      name=%s\n"
            "      client=%p, identity=%u\n",
            getName().string(),
            client.get(), getIdentity());

    result.append(buffer);
}


void LayerBaseClient::shortDump(String8& result, char* scratch, size_t size) const
{
    LayerBaseClient::dump(result, scratch, size);
}

// ---------------------------------------------------------------------------

LayerBaseClient::LayerCleaner::LayerCleaner(const sp<SurfaceFlinger>& flinger,
        const sp<LayerBaseClient>& layer)
    : mFlinger(flinger), mLayer(layer) {
}

LayerBaseClient::LayerCleaner::~LayerCleaner() {
    // destroy client resources
    mFlinger->destroySurface(mLayer);
}

// ---------------------------------------------------------------------------

}; // namespace android
