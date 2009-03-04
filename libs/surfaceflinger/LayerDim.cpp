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

#include "LayerDim.h"
#include "SurfaceFlinger.h"
#include "VRamHeap.h"
#include "DisplayHardware/DisplayHardware.h"

namespace android {
// ---------------------------------------------------------------------------

const uint32_t LayerDim::typeInfo = LayerBaseClient::typeInfo | 0x10;
const char* const LayerDim::typeID = "LayerDim";
sp<MemoryDealer> LayerDim::mDimmerDealer;
LayerBitmap LayerDim::mDimmerBitmap;

// ---------------------------------------------------------------------------

LayerDim::LayerDim(SurfaceFlinger* flinger, DisplayID display,
        Client* client, int32_t i)
     : LayerBaseClient(flinger, display, client, i)
{
}

void LayerDim::initDimmer(SurfaceFlinger* flinger, uint32_t w, uint32_t h)
{
    // must only be called once.
    mDimmerDealer = flinger->getSurfaceHeapManager()
            ->createHeap(ISurfaceComposer::eHardware);
    if (mDimmerDealer != 0) {
        mDimmerBitmap.init(mDimmerDealer);
        mDimmerBitmap.setBits(w, h, 1, PIXEL_FORMAT_RGB_565);
        mDimmerBitmap.clear();
    }
}

LayerDim::~LayerDim()
{
}

void LayerDim::onDraw(const Region& clip) const
{
    const State& s(drawingState());

    Region::iterator iterator(clip);
    if (s.alpha>0 && iterator) {
        const DisplayHardware& hw(graphicPlane(0).displayHardware());

        status_t err = NO_ERROR;
        const int can_use_copybit = canUseCopybit();
        if (can_use_copybit)  {
            // StopWatch watch("copybit");
            copybit_image_t dst;
            hw.getDisplaySurface(&dst);
            const copybit_rect_t& drect
                = reinterpret_cast<const copybit_rect_t&>(mTransformedBounds);

            copybit_image_t src;
            mDimmerBitmap.getBitmapSurface(&src);
            const copybit_rect_t& srect(drect);

            copybit_device_t* copybit = mFlinger->getBlitEngine();
            copybit->set_parameter(copybit, COPYBIT_TRANSFORM, 0);
            copybit->set_parameter(copybit, COPYBIT_PLANE_ALPHA, s.alpha);
            copybit->set_parameter(copybit, COPYBIT_DITHER, COPYBIT_ENABLE);
            region_iterator it(clip);
            err = copybit->stretch(copybit, &dst, &src, &drect, &srect, &it);
        }

        if (!can_use_copybit || err) {
            const GGLfixed alpha = (s.alpha << 16)/255;
            const uint32_t fbHeight = hw.getHeight();
            glDisable(GL_TEXTURE_2D);
            glDisable(GL_DITHER);
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            glColor4x(0, 0, 0, alpha);
            glVertexPointer(2, GL_FIXED, 0, mVertices);
            Rect r;
            while (iterator.iterate(&r)) {
                const GLint sy = fbHeight - (r.top + r.height());
                glScissor(r.left, sy, r.width(), r.height());
                glDrawArrays(GL_TRIANGLE_FAN, 0, 4); 
            }
        }
    }
}

// ---------------------------------------------------------------------------

}; // namespace android
