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

#include <GLES/gl.h>
#include <GLES/glext.h>

#include "clz.h"
#include "BlurFilter.h"
#include "LayerBlur.h"
#include "SurfaceFlinger.h"
#include "DisplayHardware/DisplayHardware.h"

namespace android {
// ---------------------------------------------------------------------------

LayerBlur::LayerBlur(SurfaceFlinger* flinger, DisplayID display,
        const sp<Client>& client)
    : LayerBaseClient(flinger, display, client), mCacheDirty(true),
          mRefreshCache(true), mCacheAge(0), mTextureName(-1U),
          mWidthScale(1.0f), mHeightScale(1.0f),
          mBlurFormat(GGL_PIXEL_FORMAT_RGB_565)
{
}

LayerBlur::~LayerBlur()
{
    if (mTextureName != -1U) {
        glDeleteTextures(1, &mTextureName);
    }
}

void LayerBlur::setVisibleRegion(const Region& visibleRegion)
{
    LayerBaseClient::setVisibleRegion(visibleRegion);
    if (visibleRegionScreen.isEmpty()) {
        if (mTextureName != -1U) {
            // We're not visible, free the texture up.
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteTextures(1, &mTextureName);
            mTextureName = -1U;
        }
    }
}

uint32_t LayerBlur::doTransaction(uint32_t flags)
{
    // we're doing a transaction, refresh the cache!
    if (!mFlinger->isFrozen()) {
        mRefreshCache = true;
        mCacheDirty = true;
        flags |= eVisibleRegion;
        this->contentDirty = true;
    }
    return LayerBase::doTransaction(flags);    
}

void LayerBlur::unlockPageFlip(const Transform& planeTransform, Region& outDirtyRegion)
{
    // this code-path must be as tight as possible, it's called each time
    // the screen is composited.
    if (UNLIKELY(!visibleRegionScreen.isEmpty())) {
        // if anything visible below us is invalidated, the cache becomes dirty
        if (!mCacheDirty && 
                !visibleRegionScreen.intersect(outDirtyRegion).isEmpty()) {
            mCacheDirty = true;
        }
        if (mCacheDirty) {
            if (!mFlinger->isFrozen()) {
                // update everything below us that is visible
                outDirtyRegion.orSelf(visibleRegionScreen);
                nsecs_t now = systemTime();
                if ((now - mCacheAge) >= ms2ns(500)) {
                    mCacheAge = now;
                    mRefreshCache = true;
                    mCacheDirty = false;
                } else {
                    if (!mAutoRefreshPending) {
                        mFlinger->postMessageAsync(
                                new MessageBase(MessageQueue::INVALIDATE),
                                ms2ns(500));
                        mAutoRefreshPending = true;
                    }
                }
            }
        }
    }
    LayerBase::unlockPageFlip(planeTransform, outDirtyRegion);
}

void LayerBlur::onDraw(const Region& clip) const
{
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const uint32_t fbHeight = hw.getHeight();
    int x = mTransformedBounds.left;
    int y = mTransformedBounds.top;
    int w = mTransformedBounds.width();
    int h = mTransformedBounds.height();
    GLint X = x;
    GLint Y = fbHeight - (y + h);
    if (X < 0) {
        w += X;
        X = 0;
    }
    if (Y < 0) {
        h += Y;
        Y = 0;
    }
    if (w<0 || h<0) {
        // we're outside of the framebuffer
        return;
    }

    if (mTextureName == -1U) {
        // create the texture name the first time
        // can't do that in the ctor, because it runs in another thread.
        glGenTextures(1, &mTextureName);
        glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT_OES, &mReadFormat);
        glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE_OES, &mReadType);
        if (mReadFormat != GL_RGB || mReadType != GL_UNSIGNED_SHORT_5_6_5) {
            mReadFormat = GL_RGBA;
            mReadType = GL_UNSIGNED_BYTE;
            mBlurFormat = GGL_PIXEL_FORMAT_RGBX_8888;
        }
    }

    Region::const_iterator it = clip.begin();
    Region::const_iterator const end = clip.end();
    if (it != end) {
#if defined(GL_OES_texture_external)
        if (GLExtensions::getInstance().haveTextureExternal()) {
            glDisable(GL_TEXTURE_EXTERNAL_OES);
        }
#endif
        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, mTextureName);

        if (mRefreshCache) {
            mRefreshCache = false;
            mAutoRefreshPending = false;

            int32_t pixelSize = 4;
            int32_t s = w;
            if (mReadType == GL_UNSIGNED_SHORT_5_6_5) {
                // allocate enough memory for 4-bytes (2 pixels) aligned data
                s = (w + 1) & ~1;
                pixelSize = 2;
            }

            uint16_t* const pixels = (uint16_t*)malloc(s*h*pixelSize);

            // This reads the frame-buffer, so a h/w GL would have to
            // finish() its rendering first. we don't want to do that
            // too often. Read data is 4-bytes aligned.
            glReadPixels(X, Y, w, h, mReadFormat, mReadType, pixels);

            // blur that texture.
            GGLSurface bl;
            bl.version = sizeof(GGLSurface);
            bl.width = w;
            bl.height = h;
            bl.stride = s;
            bl.format = mBlurFormat;
            bl.data = (GGLubyte*)pixels;            
            blurFilter(&bl, 8, 2);

            if (GLExtensions::getInstance().haveNpot()) {
                glTexImage2D(GL_TEXTURE_2D, 0, mReadFormat, w, h, 0,
                        mReadFormat, mReadType, pixels);
                mWidthScale  = 1.0f / w;
                mHeightScale =-1.0f / h;
                mYOffset = 0;
            } else {
                GLuint tw = 1 << (31 - clz(w));
                GLuint th = 1 << (31 - clz(h));
                if (tw < GLuint(w)) tw <<= 1;
                if (th < GLuint(h)) th <<= 1;
                glTexImage2D(GL_TEXTURE_2D, 0, mReadFormat, tw, th, 0,
                        mReadFormat, mReadType, NULL);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, 
                        mReadFormat, mReadType, pixels);
                mWidthScale  = 1.0f / tw;
                mHeightScale =-1.0f / th;
                mYOffset = th-h;
            }

            free((void*)pixels);
        }

        const State& s = drawingState();
        if (UNLIKELY(s.alpha < 0xFF)) {
            const GLfloat alpha = s.alpha * (1.0f/255.0f);
            glColor4f(0, 0, 0, alpha);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
        } else {
            glDisable(GL_BLEND);
        }

        if (mFlags & DisplayHardware::SLOW_CONFIG) {
            glDisable(GL_DITHER);
        } else {
            glEnable(GL_DITHER);
        }

        glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);

        glMatrixMode(GL_TEXTURE);
        glLoadIdentity();
        glScalef(mWidthScale, mHeightScale, 1);
        glTranslatef(-x, mYOffset - y, 0);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glVertexPointer(2, GL_FLOAT, 0, mVertices);
        glTexCoordPointer(2, GL_FLOAT, 0, mVertices);
        while (it != end) {
            const Rect& r = *it++;
            const GLint sy = fbHeight - (r.top + r.height());
            glScissor(r.left, sy, r.width(), r.height());
            glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        }
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        glLoadIdentity();
        glMatrixMode(GL_MODELVIEW);
    }
}

// ---------------------------------------------------------------------------

}; // namespace android
