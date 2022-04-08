/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include "Layer.h"

#include "renderstate/RenderState.h"
#include "utils/Color.h"

namespace android {
namespace uirenderer {

Layer::Layer(RenderState& renderState, sk_sp<SkColorFilter> colorFilter, int alpha,
        SkBlendMode mode)
        : mRenderState(renderState)
        , mColorFilter(colorFilter)
        , alpha(alpha)
        , mode(mode) {
    // TODO: This is a violation of Android's typical ref counting, but it
    // preserves the old inc/dec ref locations. This should be changed...
    incStrong(nullptr);
    renderState.registerLayer(this);
    texTransform.setIdentity();
    transform.setIdentity();
}

Layer::~Layer() {
    mRenderState.unregisterLayer(this);
}

void Layer::postDecStrong() {
    mRenderState.postDecStrong(this);
}

SkBlendMode Layer::getMode() const {
    if (mBlend || mode != SkBlendMode::kSrcOver) {
        return mode;
    } else {
        return SkBlendMode::kSrc;
    }
}

}  // namespace uirenderer
}  // namespace android
