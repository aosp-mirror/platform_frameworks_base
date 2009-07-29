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

#include <stdint.h>
#include <sys/types.h>
#include <limits.h>

#include "LayerOrientationAnim.h"
#include "OrientationAnimation.h"
#include "SurfaceFlinger.h"
#include "VRamHeap.h"

#include "DisplayHardware/DisplayHardware.h"

namespace android {

// ---------------------------------------------------------------------------

OrientationAnimation::OrientationAnimation(const sp<SurfaceFlinger>& flinger)
    : mFlinger(flinger), mLayerOrientationAnim(NULL), mState(DONE)
{
    // allocate a memory-dealer for this the first time
    mTemporaryDealer = mFlinger->getSurfaceHeapManager()->createHeap(
            ISurfaceComposer::eHardware);
}

OrientationAnimation::~OrientationAnimation()
{
}

void OrientationAnimation::onOrientationChanged(uint32_t type)
{
    if (mState == DONE) {
        mType = type;
        if (!(type & ISurfaceComposer::eOrientationAnimationDisable)) {
            mState = PREPARE;
        }
    }
}

void OrientationAnimation::onAnimationFinished()
{
    if (mState != DONE)
        mState = FINISH;
}

bool OrientationAnimation::run_impl()
{
    bool skip_frame;
    switch (mState) {
        default:
        case DONE:
            skip_frame = done();
            break;
        case PREPARE:
            skip_frame = prepare();
            break;
        case PHASE1:
            skip_frame = phase1();
            break;
        case PHASE2:
            skip_frame = phase2();
            break;
        case FINISH:
            skip_frame = finished();
            break;
    }
    return skip_frame;
}

bool OrientationAnimation::done()
{
    return done_impl();
}

bool OrientationAnimation::prepare()
{
    mState = PHASE1;
    
    const GraphicPlane& plane(mFlinger->graphicPlane(0));
    const DisplayHardware& hw(plane.displayHardware());
    const uint32_t w = hw.getWidth();
    const uint32_t h = hw.getHeight();

    LayerBitmap bitmap;
    bitmap.init(mTemporaryDealer);
    bitmap.setBits(w, h, 1, hw.getFormat());

    LayerBitmap bitmapIn;
    bitmapIn.init(mTemporaryDealer);
    bitmapIn.setBits(w, h, 1, hw.getFormat());

    copybit_image_t front;
    bitmap.getBitmapSurface(&front);
    hw.copyFrontToImage(front);

    LayerOrientationAnimBase* l;
    
    l = new LayerOrientationAnim(
            mFlinger.get(), 0, this, bitmap, bitmapIn);

    l->initStates(w, h, 0);
    l->setLayer(INT_MAX-1);
    mFlinger->addLayer(l);
    mLayerOrientationAnim = l;
    return true;
}

bool OrientationAnimation::phase1()
{
    if (mFlinger->isFrozen() == false) {
        // start phase 2
        mState = PHASE2;
        mLayerOrientationAnim->onOrientationCompleted();
        mLayerOrientationAnim->invalidate();
        return true;
        
    }
    //mLayerOrientationAnim->invalidate();
    return false;
}

bool OrientationAnimation::phase2()
{
    // do the 2nd phase of the animation
    mLayerOrientationAnim->invalidate();
    return false;
}

bool OrientationAnimation::finished()
{
    mState = DONE;
    mFlinger->removeLayer(mLayerOrientationAnim);
    mLayerOrientationAnim = NULL;
    return true;
}

// ---------------------------------------------------------------------------

}; // namespace android
