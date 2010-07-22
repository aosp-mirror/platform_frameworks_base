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

const uint32_t LayerBase::typeInfo = 1;
const char* const LayerBase::typeID = "LayerBase";

const uint32_t LayerBaseClient::typeInfo = LayerBase::typeInfo | 2;
const char* const LayerBaseClient::typeID = "LayerBaseClient";

// ---------------------------------------------------------------------------

LayerBase::LayerBase(SurfaceFlinger* flinger, DisplayID display)
    : dpy(display), contentDirty(false),
      mFlinger(flinger),
      mTransformed(false),
      mUseLinearFiltering(false),
      mOrientation(0),
      mLeft(0), mTop(0),
      mTransactionFlags(0),
      mPremultipliedAlpha(true), mDebug(false),
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

bool LayerBase::setPosition(int32_t x, int32_t y) {
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
    // TODO: check the matrix has changed
    mCurrentState.sequence++;
    mCurrentState.transform.set(
            matrix.dsdx, matrix.dsdy, matrix.dtdx, matrix.dtdy);
    requestTransaction();
    return true;
}
bool LayerBase::setTransparentRegionHint(const Region& transparent) {
    // TODO: check the region has changed
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

        const bool linearFiltering = mUseLinearFiltering;
        mUseLinearFiltering = false;
        if (!(mFlags & DisplayHardware::SLOW_CONFIG)) {
            // we may use linear filtering, if the matrix scales us
            const uint8_t type = temp.transform.getType();
            if (!temp.transform.preserveRects() || (type >= Transform::SCALE)) {
                mUseLinearFiltering = true;
            }
        }
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
   
    uint32_t w = s.w;
    uint32_t h = s.h;    
    tr.transform(mVertices[0], 0, 0);
    tr.transform(mVertices[1], 0, h);
    tr.transform(mVertices[2], w, h);
    tr.transform(mVertices[3], w, 0);
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
    mTransformedBounds = tr.makeBounds(w, h);
    mTransformed = transformed;
    mLeft = tr.tx();
    mTop  = tr.ty();
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

void LayerBase::finishPageFlip()
{
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

void LayerBase::draw(const Region& inClip) const
{
    // invalidate the region we'll update
    Region clip(inClip);  // copy-on-write, so no-op most of the time

    // Remove the transparent area from the clipping region
    const State& s = drawingState();
    if (LIKELY(!s.transparentRegion.isEmpty())) {
        clip.subtract(transparentRegionScreen);
        if (clip.isEmpty()) {
            // usually this won't happen because this should be taken care of
            // by SurfaceFlinger::computeVisibleRegions()
            return;
        }        
    }

    // reset GL state
    glEnable(GL_SCISSOR_TEST);

    onDraw(clip);

    /*
    glDisable(GL_TEXTURE_2D);
    glDisable(GL_DITHER);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glColor4x(0, 0x8000, 0, 0x10000);
    drawRegion(transparentRegionScreen);
    glDisable(GL_BLEND);
    */
}

GLuint LayerBase::createTexture() const
{
    GLuint textureName = -1;
    glGenTextures(1, &textureName);
    glBindTexture(GL_TEXTURE_2D, textureName);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    return textureName;
}

void LayerBase::clearWithOpenGL(const Region& clip, GLclampx red,
                                GLclampx green, GLclampx blue,
                                GLclampx alpha) const
{
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const uint32_t fbHeight = hw.getHeight();
    glColor4x(red,green,blue,alpha);
    glDisable(GL_TEXTURE_2D);
    glDisable(GL_BLEND);
    glDisable(GL_DITHER);

    Region::const_iterator it = clip.begin();
    Region::const_iterator const end = clip.end();
    glEnable(GL_SCISSOR_TEST);
    glVertexPointer(2, GL_FIXED, 0, mVertices);
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

void LayerBase::drawWithOpenGL(const Region& clip, const Texture& texture) const
{
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const uint32_t fbHeight = hw.getHeight();
    const State& s(drawingState());
    
    // bind our texture
    validateTexture(texture.name);
    uint32_t width  = texture.width; 
    uint32_t height = texture.height;
    
    glEnable(GL_TEXTURE_2D);

    if (UNLIKELY(s.alpha < 0xFF)) {
        // We have an alpha-modulation. We need to modulate all
        // texture components by alpha because we're always using 
        // premultiplied alpha.
        
        // If the texture doesn't have an alpha channel we can
        // use REPLACE and switch to non premultiplied alpha
        // blending (SRCA/ONE_MINUS_SRCA).
        
        GLenum env, src;
        if (needsBlending()) {
            env = GL_MODULATE;
            src = mPremultipliedAlpha ? GL_ONE : GL_SRC_ALPHA;
        } else {
            env = GL_REPLACE;
            src = GL_SRC_ALPHA;
        }
        const GGLfixed alpha = (s.alpha << 16)/255;
        glColor4x(alpha, alpha, alpha, alpha);
        glEnable(GL_BLEND);
        glBlendFunc(src, GL_ONE_MINUS_SRC_ALPHA);
        glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, env);
    } else {
        glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
        glColor4x(0x10000, 0x10000, 0x10000, 0x10000);
        if (needsBlending()) {
            GLenum src = mPremultipliedAlpha ? GL_ONE : GL_SRC_ALPHA;
            glEnable(GL_BLEND);
            glBlendFunc(src, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }
    }

    Region::const_iterator it = clip.begin();
    Region::const_iterator const end = clip.end();

    //StopWatch watch("GL transformed");
    const GLfixed texCoords[4][2] = {
            { 0,        0 },
            { 0,        0x10000 },
            { 0x10000,  0x10000 },
            { 0x10000,  0 }
    };

    glMatrixMode(GL_TEXTURE);
    glLoadIdentity();

    // the texture's source is rotated
    switch (texture.transform) {
        case HAL_TRANSFORM_ROT_90:
            glTranslatef(0, 1, 0);
            glRotatef(-90, 0, 0, 1);
            break;
        case HAL_TRANSFORM_ROT_180:
            glTranslatef(1, 1, 0);
            glRotatef(-180, 0, 0, 1);
            break;
        case HAL_TRANSFORM_ROT_270:
            glTranslatef(1, 0, 0);
            glRotatef(-270, 0, 0, 1);
            break;
    }

    if (texture.NPOTAdjust) {
        glScalef(texture.wScale, texture.hScale, 1.0f);
    }

    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glVertexPointer(2, GL_FIXED, 0, mVertices);
    glTexCoordPointer(2, GL_FIXED, 0, texCoords);

    while (it != end) {
        const Rect& r = *it++;
        const GLint sy = fbHeight - (r.top + r.height());
        glScissor(r.left, sy, r.width(), r.height());
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
    }
    glDisableClientState(GL_TEXTURE_COORD_ARRAY);
}

void LayerBase::validateTexture(GLint textureName) const
{
    glBindTexture(GL_TEXTURE_2D, textureName);
    // TODO: reload the texture if needed
    // this is currently done in loadTexture() below
    if (mUseLinearFiltering) {
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    } else {
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    }

    if (needsDithering()) {
        glEnable(GL_DITHER);
    } else {
        glDisable(GL_DITHER);
    }
}

bool LayerBase::isSupportedYuvFormat(int format) const
{
    switch (format) {
        case HAL_PIXEL_FORMAT_YCbCr_422_SP:
        case HAL_PIXEL_FORMAT_YCbCr_420_SP:
        case HAL_PIXEL_FORMAT_YCbCr_422_P:
        case HAL_PIXEL_FORMAT_YCbCr_420_P:
        case HAL_PIXEL_FORMAT_YCbCr_422_I:
        case HAL_PIXEL_FORMAT_YCbCr_420_I:
        case HAL_PIXEL_FORMAT_YCrCb_420_SP:
            return true;
    }
    return false;
}

void LayerBase::loadTexture(Texture* texture, 
        const Region& dirty, const GGLSurface& t) const
{
    if (texture->name == -1U) {
        // uh?
        return;
    }

    glBindTexture(GL_TEXTURE_2D, texture->name);

    /*
     * In OpenGL ES we can't specify a stride with glTexImage2D (however,
     * GL_UNPACK_ALIGNMENT is a limited form of stride).
     * So if the stride here isn't representable with GL_UNPACK_ALIGNMENT, we
     * need to do something reasonable (here creating a bigger texture).
     * 
     * extra pixels = (((stride - width) * pixelsize) / GL_UNPACK_ALIGNMENT);
     * 
     * This situation doesn't happen often, but some h/w have a limitation
     * for their framebuffer (eg: must be multiple of 8 pixels), and
     * we need to take that into account when using these buffers as
     * textures.
     *
     * This should never be a problem with POT textures
     */
    
    int unpack = __builtin_ctz(t.stride * bytesPerPixel(t.format));
    unpack = 1 << ((unpack > 3) ? 3 : unpack);
    glPixelStorei(GL_UNPACK_ALIGNMENT, unpack);
    
    /*
     * round to POT if needed 
     */
    if (!(mFlags & DisplayHardware::NPOT_EXTENSION)) {
        texture->NPOTAdjust = true;
    }
    
    if (texture->NPOTAdjust) {
        // find the smallest power-of-two that will accommodate our surface
        texture->potWidth  = 1 << (31 - clz(t.width));
        texture->potHeight = 1 << (31 - clz(t.height));
        if (texture->potWidth  < t.width)  texture->potWidth  <<= 1;
        if (texture->potHeight < t.height) texture->potHeight <<= 1;
        texture->wScale = float(t.width)  / texture->potWidth;
        texture->hScale = float(t.height) / texture->potHeight;
    } else {
        texture->potWidth  = t.width;
        texture->potHeight = t.height;
    }

    Rect bounds(dirty.bounds());
    GLvoid* data = 0;
    if (texture->width != t.width || texture->height != t.height) {
        texture->width  = t.width;
        texture->height = t.height;

        // texture size changed, we need to create a new one
        bounds.set(Rect(t.width, t.height));
        if (t.width  == texture->potWidth &&
            t.height == texture->potHeight) {
            // we can do it one pass
            data = t.data;
        }

        if (t.format == HAL_PIXEL_FORMAT_RGB_565) {
            glTexImage2D(GL_TEXTURE_2D, 0,
                    GL_RGB, texture->potWidth, texture->potHeight, 0,
                    GL_RGB, GL_UNSIGNED_SHORT_5_6_5, data);
        } else if (t.format == HAL_PIXEL_FORMAT_RGBA_4444) {
            glTexImage2D(GL_TEXTURE_2D, 0,
                    GL_RGBA, texture->potWidth, texture->potHeight, 0,
                    GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4, data);
        } else if (t.format == HAL_PIXEL_FORMAT_RGBA_8888 ||
                   t.format == HAL_PIXEL_FORMAT_RGBX_8888) {
            glTexImage2D(GL_TEXTURE_2D, 0,
                    GL_RGBA, texture->potWidth, texture->potHeight, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, data);
        } else if (isSupportedYuvFormat(t.format)) {
            // just show the Y plane of YUV buffers
            glTexImage2D(GL_TEXTURE_2D, 0,
                    GL_LUMINANCE, texture->potWidth, texture->potHeight, 0,
                    GL_LUMINANCE, GL_UNSIGNED_BYTE, data);
        } else {
            // oops, we don't handle this format!
            LOGE("layer %p, texture=%d, using format %d, which is not "
                 "supported by the GL", this, texture->name, t.format);
        }
    }
    if (!data) {
        if (t.format == HAL_PIXEL_FORMAT_RGB_565) {
            glTexSubImage2D(GL_TEXTURE_2D, 0,
                    0, bounds.top, t.width, bounds.height(),
                    GL_RGB, GL_UNSIGNED_SHORT_5_6_5,
                    t.data + bounds.top*t.stride*2);
        } else if (t.format == HAL_PIXEL_FORMAT_RGBA_4444) {
            glTexSubImage2D(GL_TEXTURE_2D, 0,
                    0, bounds.top, t.width, bounds.height(),
                    GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4,
                    t.data + bounds.top*t.stride*2);
        } else if (t.format == HAL_PIXEL_FORMAT_RGBA_8888 ||
                   t.format == HAL_PIXEL_FORMAT_RGBX_8888) {
            glTexSubImage2D(GL_TEXTURE_2D, 0,
                    0, bounds.top, t.width, bounds.height(),
                    GL_RGBA, GL_UNSIGNED_BYTE,
                    t.data + bounds.top*t.stride*4);
        } else if (isSupportedYuvFormat(t.format)) {
            // just show the Y plane of YUV buffers
            glTexSubImage2D(GL_TEXTURE_2D, 0,
                    0, bounds.top, t.width, bounds.height(),
                    GL_LUMINANCE, GL_UNSIGNED_BYTE,
                    t.data + bounds.top*t.stride);
        }
    }
}

status_t LayerBase::initializeEglImage(
        const sp<GraphicBuffer>& buffer, Texture* texture)
{
    status_t err = NO_ERROR;

    // we need to recreate the texture
    EGLDisplay dpy(mFlinger->graphicPlane(0).getEGLDisplay());

    // free the previous image
    if (texture->image != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR(dpy, texture->image);
        texture->image = EGL_NO_IMAGE_KHR;
    }

    // construct an EGL_NATIVE_BUFFER_ANDROID
    android_native_buffer_t* clientBuf = buffer->getNativeBuffer();

    // create the new EGLImageKHR
    const EGLint attrs[] = {
            EGL_IMAGE_PRESERVED_KHR,    EGL_TRUE,
            EGL_NONE,                   EGL_NONE
    };
    texture->image = eglCreateImageKHR(
            dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
            (EGLClientBuffer)clientBuf, attrs);

    if (texture->image != EGL_NO_IMAGE_KHR) {
        glBindTexture(GL_TEXTURE_2D, texture->name);
        glEGLImageTargetTexture2DOES(GL_TEXTURE_2D,
                (GLeglImageOES)texture->image);
        GLint error = glGetError();
        if (UNLIKELY(error != GL_NO_ERROR)) {
            LOGE("layer=%p, glEGLImageTargetTexture2DOES(%p) "
                 "failed err=0x%04x",
                 this, texture->image, error);
            err = INVALID_OPERATION;
        } else {
            // Everything went okay!
            texture->NPOTAdjust = false;
            texture->dirty  = false;
            texture->width  = clientBuf->width;
            texture->height = clientBuf->height;
        }
    } else {
        LOGE("layer=%p, eglCreateImageKHR() failed. err=0x%4x",
                this, eglGetError());
        err = INVALID_OPERATION;
    }
    return err;
}


// ---------------------------------------------------------------------------

int32_t LayerBaseClient::sIdentity = 0;

LayerBaseClient::LayerBaseClient(SurfaceFlinger* flinger, DisplayID display,
        const sp<Client>& client, int32_t i)
    : LayerBase(flinger, display), lcblk(NULL), client(client), mIndex(i),
      mIdentity(uint32_t(android_atomic_inc(&sIdentity)))
{
    lcblk = new SharedBufferServer(
            client->ctrlblk, i, NUM_BUFFERS,
            mIdentity);
}

void LayerBaseClient::onFirstRef()
{    
    sp<Client> client(this->client.promote());
    if (client != 0) {
        client->bindLayer(this, mIndex);
    }
}

LayerBaseClient::~LayerBaseClient()
{
    sp<Client> client(this->client.promote());
    if (client != 0) {
        client->free(mIndex);
    }
    delete lcblk;
}

int32_t LayerBaseClient::serverIndex() const 
{
    sp<Client> client(this->client.promote());
    if (client != 0) {
        return (client->cid<<16)|mIndex;
    }
    return 0xFFFF0000 | mIndex;
}

sp<LayerBaseClient::Surface> LayerBaseClient::getSurface()
{
    sp<Surface> s;
    Mutex::Autolock _l(mLock);
    s = mClientSurface.promote();
    if (s == 0) {
        s = createSurface();
        mClientSurface = s;
    }
    return s;
}

sp<LayerBaseClient::Surface> LayerBaseClient::createSurface() const
{
    return new Surface(mFlinger, clientIndex(), mIdentity,
            const_cast<LayerBaseClient *>(this));
}

// called with SurfaceFlinger::mStateLock as soon as the layer is entered
// in the purgatory list
void LayerBaseClient::onRemoved()
{
    // wake up the condition
    lcblk->setStatus(NO_INIT);
}

// ---------------------------------------------------------------------------

LayerBaseClient::Surface::Surface(
        const sp<SurfaceFlinger>& flinger,
        SurfaceID id, int identity, 
        const sp<LayerBaseClient>& owner) 
    : mFlinger(flinger), mToken(id), mIdentity(identity), mOwner(owner)
{
}

LayerBaseClient::Surface::~Surface() 
{
    /*
     * This is a good place to clean-up all client resources 
     */

    // destroy client resources
    sp<LayerBaseClient> layer = getOwner();
    if (layer != 0) {
        mFlinger->destroySurface(layer);
    }
}

sp<LayerBaseClient> LayerBaseClient::Surface::getOwner() const {
    sp<LayerBaseClient> owner(mOwner.promote());
    return owner;
}

status_t LayerBaseClient::Surface::onTransact(
        uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch (code) {
        case REGISTER_BUFFERS:
        case UNREGISTER_BUFFERS:
        case CREATE_OVERLAY:
        {
            if (!mFlinger->mAccessSurfaceFlinger.checkCalling()) {
                IPCThreadState* ipc = IPCThreadState::self();
                const int pid = ipc->getCallingPid();
                const int uid = ipc->getCallingUid();
                LOGE("Permission Denial: "
                        "can't access SurfaceFlinger pid=%d, uid=%d", pid, uid);
                return PERMISSION_DENIED;
            }
        }
    }
    return BnSurface::onTransact(code, data, reply, flags);
}

sp<GraphicBuffer> LayerBaseClient::Surface::requestBuffer(int index, int usage) 
{
    return NULL; 
}

status_t LayerBaseClient::Surface::registerBuffers(
        const ISurface::BufferHeap& buffers) 
{ 
    return INVALID_OPERATION; 
}

void LayerBaseClient::Surface::postBuffer(ssize_t offset) 
{
}

void LayerBaseClient::Surface::unregisterBuffers() 
{
}

sp<OverlayRef> LayerBaseClient::Surface::createOverlay(
        uint32_t w, uint32_t h, int32_t format, int32_t orientation)
{
    return NULL;
};

// ---------------------------------------------------------------------------

}; // namespace android
