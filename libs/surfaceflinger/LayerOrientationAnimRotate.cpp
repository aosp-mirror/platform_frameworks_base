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

#include <core/SkBitmap.h>

#include <ui/EGLDisplaySurface.h>

#include "LayerBase.h"
#include "LayerOrientationAnim.h"
#include "LayerOrientationAnimRotate.h"
#include "SurfaceFlinger.h"
#include "DisplayHardware/DisplayHardware.h"
#include "OrientationAnimation.h"

namespace android {
// ---------------------------------------------------------------------------

const uint32_t LayerOrientationAnimRotate::typeInfo = LayerBase::typeInfo | 0x100;
const char* const LayerOrientationAnimRotate::typeID = "LayerOrientationAnimRotate";

// ---------------------------------------------------------------------------

const float ROTATION = M_PI * 0.5f;
const float ROTATION_FACTOR = 1.0f; // 1.0 or 2.0
const float DURATION = ms2ns(200);
const float BOUNCES_PER_SECOND = 0.8;
const float BOUNCES_AMPLITUDE = (5.0f/180.f) * M_PI;

LayerOrientationAnimRotate::LayerOrientationAnimRotate(
        SurfaceFlinger* flinger, DisplayID display, 
        OrientationAnimation* anim, 
        const LayerBitmap& bitmap,
        const LayerBitmap& bitmapIn)
    : LayerOrientationAnimBase(flinger, display), mAnim(anim), 
      mBitmap(bitmap), mBitmapIn(bitmapIn), 
      mTextureName(-1), mTextureNameIn(-1)
{
    mStartTime = systemTime();
    mFinishTime = 0;
    mOrientationCompleted = false;
    mFirstRedraw = false;
    mLastNormalizedTime = 0;
    mLastAngle = 0;
    mLastScale = 0;
    mNeedsBlending = false;
    const GraphicPlane& plane(graphicPlane(0));
    mOriginalTargetOrientation = plane.getOrientation(); 
}

LayerOrientationAnimRotate::~LayerOrientationAnimRotate()
{
    if (mTextureName != -1U) {
        LayerBase::deletedTextures.add(mTextureName);
    }
    if (mTextureNameIn != -1U) {
        LayerBase::deletedTextures.add(mTextureNameIn);
    }
}

bool LayerOrientationAnimRotate::needsBlending() const 
{
    return mNeedsBlending; 
}

Point LayerOrientationAnimRotate::getPhysicalSize() const
{
    const GraphicPlane& plane(graphicPlane(0));
    const DisplayHardware& hw(plane.displayHardware());
    return Point(hw.getWidth(), hw.getHeight());
}

void LayerOrientationAnimRotate::validateVisibility(const Transform&)
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
}

void LayerOrientationAnimRotate::onOrientationCompleted()
{
    mFinishTime = systemTime();
    mOrientationCompleted = true;
    mFirstRedraw = true;
    mNeedsBlending = true;
    mFlinger->invalidateLayerVisibility(this);
}

void LayerOrientationAnimRotate::onDraw(const Region& clip) const
{
    // Animation...

    const nsecs_t now = systemTime();
    float angle, scale, alpha;
    
    if (mOrientationCompleted) {
        if (mFirstRedraw) {
            // make a copy of what's on screen
            copybit_image_t image;
            mBitmapIn.getBitmapSurface(&image);
            const DisplayHardware& hw(graphicPlane(0).displayHardware());
            hw.copyBackToImage(image);
            
            // FIXME: code below is gross
            mFirstRedraw = false; 
            mNeedsBlending = false;
            LayerOrientationAnimRotate* self(const_cast<LayerOrientationAnimRotate*>(this));
            mFlinger->invalidateLayerVisibility(self);
        }

        // make sure pick-up where we left off
        const float duration = DURATION * mLastNormalizedTime;
        const float normalizedTime = (float(now - mFinishTime) / duration);
        if (normalizedTime <= 1.0f) {
            const float squaredTime = normalizedTime*normalizedTime;
            angle = (ROTATION*ROTATION_FACTOR - mLastAngle)*squaredTime + mLastAngle;
            scale = (1.0f - mLastScale)*squaredTime + mLastScale;
            alpha = normalizedTime;
        } else {
            mAnim->onAnimationFinished();
            angle = ROTATION;
            alpha = 1.0f;
            scale = 1.0f;
        }
    } else {
        // FIXME: works only for portrait framebuffers
        const Point size(getPhysicalSize());
        const float TARGET_SCALE = size.x * (1.0f / size.y);
        const float normalizedTime = float(now - mStartTime) / DURATION;
        if (normalizedTime <= 1.0f) {
            mLastNormalizedTime = normalizedTime;
            const float squaredTime = normalizedTime*normalizedTime;
            angle = ROTATION * squaredTime;
            scale = (TARGET_SCALE - 1.0f)*squaredTime + 1.0f;
            alpha = 0;
        } else {
            mLastNormalizedTime = 1.0f;
            angle = ROTATION;
            if (BOUNCES_AMPLITUDE) {
                const float to_seconds = DURATION / seconds(1);
                const float phi = BOUNCES_PER_SECOND * 
                (((normalizedTime - 1.0f) * to_seconds)*M_PI*2);
                angle += BOUNCES_AMPLITUDE * sinf(phi);
            }
            scale = TARGET_SCALE;
            alpha = 0;
        }
        mLastAngle = angle;
        mLastScale = scale;
    }
    drawScaled(angle, scale, alpha);
}

void LayerOrientationAnimRotate::drawScaled(float f, float s, float alpha) const
{
    copybit_image_t dst;
    const GraphicPlane& plane(graphicPlane(0));
    const DisplayHardware& hw(plane.displayHardware());
    hw.getDisplaySurface(&dst);

    // clear screen
    // TODO: with update on demand, we may be able 
    // to not erase the screen at all during the animation 
    glDisable(GL_BLEND);
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0,0,0,0);
    glClear(GL_COLOR_BUFFER_BIT);
    
    const int w = dst.w; 
    const int h = dst.h; 

    copybit_image_t src;
    mBitmap.getBitmapSurface(&src);
    const copybit_rect_t srect = { 0, 0, src.w, src.h };


    GGLSurface t;
    t.version = sizeof(GGLSurface);
    t.width  = src.w;
    t.height = src.h;
    t.stride = src.w;
    t.vstride= src.h;
    t.format = src.format;
    t.data = (GGLubyte*)(intptr_t(src.base) + src.offset);

    if (!mOriginalTargetOrientation) {
        f = -f;
    }

    Transform tr;
    tr.set(f, w*0.5f, h*0.5f);
    tr.scale(s, w*0.5f, h*0.5f);

    // FIXME: we should not access mVertices and mDrawingState like that,
    // but since we control the animation, we know it's going to work okay.
    // eventually we'd need a more formal way of doing things like this.
    LayerOrientationAnimRotate& self(const_cast<LayerOrientationAnimRotate&>(*this));
    tr.transform(self.mVertices[0], 0, 0);
    tr.transform(self.mVertices[1], 0, src.h);
    tr.transform(self.mVertices[2], src.w, src.h);
    tr.transform(self.mVertices[3], src.w, 0);

    if (!(mFlags & DisplayHardware::SLOW_CONFIG)) {
        // Too slow to do this in software
        self.mDrawingState.flags |= ISurfaceComposer::eLayerFilter;
    }

    if (UNLIKELY(mTextureName == -1LU)) {
        mTextureName = createTexture();
        GLuint w=0, h=0;
        const Region dirty(Rect(t.width, t.height));
        loadTexture(dirty, mTextureName, t, w, h);
    }
    self.mDrawingState.alpha = 255; //-int(alpha*255);
    const Region clip(Rect( srect.l, srect.t, srect.r, srect.b ));
    drawWithOpenGL(clip, mTextureName, t);
    
    if (alpha > 0) {
        const float sign = (!mOriginalTargetOrientation) ? 1.0f : -1.0f;
        tr.set(f + sign*(M_PI * 0.5f * ROTATION_FACTOR), w*0.5f, h*0.5f);
        tr.scale(s, w*0.5f, h*0.5f);
        tr.transform(self.mVertices[0], 0, 0);
        tr.transform(self.mVertices[1], 0, src.h);
        tr.transform(self.mVertices[2], src.w, src.h);
        tr.transform(self.mVertices[3], src.w, 0);

        copybit_image_t src;
        mBitmapIn.getBitmapSurface(&src);
        t.data = (GGLubyte*)(intptr_t(src.base) + src.offset);
        if (UNLIKELY(mTextureNameIn == -1LU)) {
            mTextureNameIn = createTexture();
            GLuint w=0, h=0;
            const Region dirty(Rect(t.width, t.height));
            loadTexture(dirty, mTextureNameIn, t, w, h);
        }
        self.mDrawingState.alpha = int(alpha*255);
        const Region clip(Rect( srect.l, srect.t, srect.r, srect.b ));
        drawWithOpenGL(clip, mTextureNameIn, t);
    }
}

// ---------------------------------------------------------------------------

}; // namespace android
