/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include "Layer.h"
#include "SurfaceTextureLayer.h"

namespace android {
// ---------------------------------------------------------------------------


SurfaceTextureLayer::SurfaceTextureLayer(GLuint tex, const sp<Layer>& layer)
    : SurfaceTexture(tex), mLayer(layer) {
}

SurfaceTextureLayer::~SurfaceTextureLayer() {
}


status_t SurfaceTextureLayer::setDefaultBufferSize(uint32_t w, uint32_t h)
{
    //LOGD("%s, w=%u, h=%u", __PRETTY_FUNCTION__, w, h);
    return SurfaceTexture::setDefaultBufferSize(w, h);
}

status_t SurfaceTextureLayer::setDefaultBufferFormat(uint32_t format)
{
    mDefaultFormat = format;
    return NO_ERROR;
}

status_t SurfaceTextureLayer::setBufferCount(int bufferCount) {
    status_t res = SurfaceTexture::setBufferCount(bufferCount);
    return res;
}

status_t SurfaceTextureLayer::queueBuffer(int buf, int64_t timestamp,
        uint32_t* outWidth, uint32_t* outHeight, uint32_t* outTransform) {

    status_t res = SurfaceTexture::queueBuffer(buf, timestamp,
            outWidth, outHeight, outTransform);

    sp<Layer> layer(mLayer.promote());
    if (layer != NULL) {
        *outTransform = layer->getOrientation();
    }

    return res;
}

status_t SurfaceTextureLayer::dequeueBuffer(int *buf,
        uint32_t w, uint32_t h, uint32_t format, uint32_t usage) {

    status_t res(NO_INIT);
    sp<Layer> layer(mLayer.promote());
    if (layer != NULL) {
        if (format == 0)
            format = mDefaultFormat;
        uint32_t effectiveUsage = layer->getEffectiveUsage(usage);
        //LOGD("%s, w=%u, h=%u, format=%u, usage=%08x, effectiveUsage=%08x",
        //        __PRETTY_FUNCTION__, w, h, format, usage, effectiveUsage);
        res = SurfaceTexture::dequeueBuffer(buf, w, h, format, effectiveUsage);
    }
    return res;
}


// ---------------------------------------------------------------------------
}; // namespace android
