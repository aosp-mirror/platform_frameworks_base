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
#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/StopWatch.h>

#include <core/SkBitmap.h>

#include <ui/EGLDisplaySurface.h>

#include "BlurFilter.h"
#include "LayerBase.h"
#include "LayerOrientationAnim.h"
#include "SurfaceFlinger.h"
#include "DisplayHardware/DisplayHardware.h"
#include "OrientationAnimation.h"

namespace android {
// ---------------------------------------------------------------------------

const uint32_t LayerOrientationAnim::typeInfo = LayerBase::typeInfo | 0x80;
const char* const LayerOrientationAnim::typeID = "LayerOrientationAnim";

// ---------------------------------------------------------------------------

// Animation...
const float DURATION = ms2ns(200);
const float BOUNCES_PER_SECOND = 0.5f;
//const float BOUNCES_AMPLITUDE = 1.0f/16.0f;
const float BOUNCES_AMPLITUDE = 0;
const float DIM_TARGET = 0.40f;
//#define INTERPOLATED_TIME(_t)   ((_t)*(_t))
#define INTERPOLATED_TIME(_t)   (_t)

// ---------------------------------------------------------------------------

LayerOrientationAnim::LayerOrientationAnim(
        SurfaceFlinger* flinger, DisplayID display, 
        OrientationAnimation* anim, 
        const LayerBitmap& bitmapIn,
        const LayerBitmap& bitmapOut)
    : LayerOrientationAnimBase(flinger, display), mAnim(anim), 
      mBitmapIn(bitmapIn), mBitmapOut(bitmapOut), 
      mTextureName(-1), mTextureNameIn(-1)
{
    // blur that texture. 
    mStartTime = systemTime();
    mFinishTime = 0;
    mOrientationCompleted = false;
    mFirstRedraw = false;
    mLastNormalizedTime = 0;
    mNeedsBlending = false;
    mAlphaInLerp.set(1.0f, DIM_TARGET);
    mAlphaOutLerp.set(0.5f, 1.0f);
}

LayerOrientationAnim::~LayerOrientationAnim()
{
    if (mTextureName != -1U) {
        LayerBase::deletedTextures.add(mTextureName);
    }
    if (mTextureNameIn != -1U) {
        LayerBase::deletedTextures.add(mTextureNameIn);
    }
}

bool LayerOrientationAnim::needsBlending() const 
{
    return mNeedsBlending; 
}

Point LayerOrientationAnim::getPhysicalSize() const
{
    const GraphicPlane& plane(graphicPlane(0));
    const DisplayHardware& hw(plane.displayHardware());
    return Point(hw.getWidth(), hw.getHeight());
}

void LayerOrientationAnim::validateVisibility(const Transform&)
{
    const Layer::State& s(drawingState());
    const Transform tr(s.transform);
    const Point size(getPhysicalSize());
    uint32_t w = size.x;
    uint32_t h = size.y;
    mTransformedBounds = tr.makeBounds(w, h);
    mLeft = tr.tx();
    mTop  = tr.ty();
    transparentRegionScreen.clear();
    mTransformed = true;
    mCanUseCopyBit = false;
    copybit_device_t* copybit = mFlinger->getBlitEngine();
    if (copybit) { 
        mCanUseCopyBit = true;
    }
}

void LayerOrientationAnim::onOrientationCompleted()
{
    mFinishTime = systemTime();
    mOrientationCompleted = true;
    mFirstRedraw = true;
    mNeedsBlending = true;
    mFlinger->invalidateLayerVisibility(this);
}

void LayerOrientationAnim::onDraw(const Region& clip) const
{
    const nsecs_t now = systemTime();
    float alphaIn, alphaOut;
    
    if (mOrientationCompleted) {
        if (mFirstRedraw) {
            mFirstRedraw = false;
            
            // make a copy of what's on screen
            copybit_image_t image;
            mBitmapOut.getBitmapSurface(&image);
            const DisplayHardware& hw(graphicPlane(0).displayHardware());
            hw.copyBackToImage(image);

            // and erase the screen for this round
            glDisable(GL_BLEND);
            glDisable(GL_DITHER);
            glDisable(GL_SCISSOR_TEST);
            glClearColor(0,0,0,0);
            glClear(GL_COLOR_BUFFER_BIT);
            
            // FIXME: code below is gross
            mNeedsBlending = false;
            LayerOrientationAnim* self(const_cast<LayerOrientationAnim*>(this));
            mFlinger->invalidateLayerVisibility(self);
        }

        // make sure pick-up where we left off
        const float duration = DURATION * mLastNormalizedTime;
        const float normalizedTime = (float(now - mFinishTime) / duration);
        if (normalizedTime <= 1.0f) {
            const float interpolatedTime = INTERPOLATED_TIME(normalizedTime);
            alphaIn = mAlphaInLerp.getOut();
            alphaOut = mAlphaOutLerp(interpolatedTime);
        } else {
            mAnim->onAnimationFinished();
            alphaIn = mAlphaInLerp.getOut();
            alphaOut = mAlphaOutLerp.getOut();
        }
    } else {
        const float normalizedTime = float(now - mStartTime) / DURATION;
        if (normalizedTime <= 1.0f) {
            mLastNormalizedTime = normalizedTime;
            const float interpolatedTime = INTERPOLATED_TIME(normalizedTime);
            alphaIn = mAlphaInLerp(interpolatedTime);
            alphaOut = 0.0f;
        } else {
            mLastNormalizedTime = 1.0f;
            const float to_seconds = DURATION / seconds(1);
            alphaIn = mAlphaInLerp.getOut();
            if (BOUNCES_AMPLITUDE > 0.0f) {
                const float phi = BOUNCES_PER_SECOND * 
                        (((normalizedTime - 1.0f) * to_seconds)*M_PI*2);
                if (alphaIn > 1.0f) alphaIn = 1.0f;
                else if (alphaIn < 0.0f) alphaIn = 0.0f;
                alphaIn += BOUNCES_AMPLITUDE * (1.0f - cosf(phi));
            }
            alphaOut = 0.0f;
        }
        mAlphaOutLerp.setIn(alphaIn);
    }
    drawScaled(1.0f, alphaIn, alphaOut);
}

void LayerOrientationAnim::drawScaled(float scale, float alphaIn, float alphaOut) const
{
    copybit_image_t dst;
    const GraphicPlane& plane(graphicPlane(0));
    const DisplayHardware& hw(plane.displayHardware());
    hw.getDisplaySurface(&dst);

    // clear screen
    // TODO: with update on demand, we may be able 
    // to not erase the screen at all during the animation 
    if (!mOrientationCompleted) {
        if (scale==1.0f && (alphaIn>=1.0f || alphaOut>=1.0f)) {
            // we don't need to erase the screen in that case
        } else {
            glDisable(GL_BLEND);
            glDisable(GL_DITHER);
            glDisable(GL_SCISSOR_TEST);
            glClearColor(0,0,0,0);
            glClear(GL_COLOR_BUFFER_BIT);
        }
    }
    
    copybit_image_t src;
    mBitmapIn.getBitmapSurface(&src);

    copybit_image_t srcOut;
    mBitmapOut.getBitmapSurface(&srcOut);

    const int w = dst.w*scale; 
    const int h = dst.h*scale; 
    const int xc = uint32_t(dst.w-w)/2;
    const int yc = uint32_t(dst.h-h)/2;
    const copybit_rect_t drect = { xc, yc, xc+w, yc+h }; 
    const copybit_rect_t srect = { 0, 0, src.w, src.h };
    const Region reg(Rect( drect.l, drect.t, drect.r, drect.b ));

    int err = NO_ERROR;
    const int can_use_copybit = canUseCopybit();
    if (can_use_copybit)  {
        copybit_device_t* copybit = mFlinger->getBlitEngine();
        copybit->set_parameter(copybit, COPYBIT_TRANSFORM, 0);
        copybit->set_parameter(copybit, COPYBIT_DITHER, COPYBIT_ENABLE);
        
        if (alphaIn > 0) {
            region_iterator it(reg);
            copybit->set_parameter(copybit, COPYBIT_BLUR, COPYBIT_ENABLE);
            copybit->set_parameter(copybit, COPYBIT_PLANE_ALPHA, int(alphaIn*255));
            err = copybit->stretch(copybit, &dst, &src, &drect, &srect, &it);
        }

        if (!err && alphaOut > 0.0f) {
            region_iterator it(reg);
            copybit->set_parameter(copybit, COPYBIT_BLUR, COPYBIT_DISABLE);
            copybit->set_parameter(copybit, COPYBIT_PLANE_ALPHA, int(alphaOut*255));
            err = copybit->stretch(copybit, &dst, &srcOut, &drect, &srect, &it);
        }
        LOGE_IF(err != NO_ERROR, "copybit failed (%s)", strerror(err));
    }
    if (!can_use_copybit || err) {   
        GGLSurface t;
        t.version = sizeof(GGLSurface);
        t.width  = src.w;
        t.height = src.h;
        t.stride = src.w;
        t.vstride= src.h;
        t.format = src.format;
        t.data = (GGLubyte*)(intptr_t(src.base) + src.offset);

        Transform tr;
        tr.set(scale,0,0,scale);
        tr.set(xc, yc);
        
        // FIXME: we should not access mVertices and mDrawingState like that,
        // but since we control the animation, we know it's going to work okay.
        // eventually we'd need a more formal way of doing things like this.
        LayerOrientationAnim& self(const_cast<LayerOrientationAnim&>(*this));
        tr.transform(self.mVertices[0], 0, 0);
        tr.transform(self.mVertices[1], 0, src.h);
        tr.transform(self.mVertices[2], src.w, src.h);
        tr.transform(self.mVertices[3], src.w, 0);
        if (!(mFlags & DisplayHardware::SLOW_CONFIG)) {
            // Too slow to do this in software
            self.mDrawingState.flags |= ISurfaceComposer::eLayerFilter;
        }

        if (alphaIn > 0.0f) {
            t.data = (GGLubyte*)(intptr_t(src.base) + src.offset);
            if (UNLIKELY(mTextureNameIn == -1LU)) {
                mTextureNameIn = createTexture();
                GLuint w=0, h=0;
                const Region dirty(Rect(t.width, t.height));
                loadTexture(dirty, mTextureNameIn, t, w, h);
            }
            self.mDrawingState.alpha = int(alphaIn*255);
            drawWithOpenGL(reg, mTextureNameIn, t);
        }

        if (alphaOut > 0.0f) {
            t.data = (GGLubyte*)(intptr_t(srcOut.base) + srcOut.offset);
            if (UNLIKELY(mTextureName == -1LU)) {
                mTextureName = createTexture();
                GLuint w=0, h=0;
                const Region dirty(Rect(t.width, t.height));
                loadTexture(dirty, mTextureName, t, w, h);
            }
            self.mDrawingState.alpha = int(alphaOut*255);
            drawWithOpenGL(reg, mTextureName, t);
        }
    }
}

// ---------------------------------------------------------------------------

}; // namespace android
