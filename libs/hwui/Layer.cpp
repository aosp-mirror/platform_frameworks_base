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

#include <SkToSRGBColorFilter.h>

namespace android {
namespace uirenderer {

Layer::Layer(RenderState& renderState, Api api, sk_sp<SkColorFilter> colorFilter, int alpha,
             SkBlendMode mode)
        : GpuMemoryTracker(GpuObjectType::Layer)
        , mRenderState(renderState)
        , mode(mode)
        , mApi(api)
        , mColorFilter(colorFilter)
        , alpha(alpha) {
    // TODO: This is a violation of Android's typical ref counting, but it
    // preserves the old inc/dec ref locations. This should be changed...
    incStrong(nullptr);
    buildColorSpaceWithFilter();
    renderState.registerLayer(this);
}

Layer::~Layer() {
    mRenderState.unregisterLayer(this);
}

void Layer::setColorFilter(sk_sp<SkColorFilter> filter) {
    if (filter != mColorFilter) {
        mColorFilter = filter;
        buildColorSpaceWithFilter();
    }
}

void Layer::setDataSpace(android_dataspace dataspace) {
    if (dataspace != mCurrentDataspace) {
        mCurrentDataspace = dataspace;
        buildColorSpaceWithFilter();
    }
}

void Layer::buildColorSpaceWithFilter() {
    sk_sp<SkColorFilter> colorSpaceFilter;
    sk_sp<SkColorSpace> colorSpace = DataSpaceToColorSpace(mCurrentDataspace);
    if (colorSpace && !colorSpace->isSRGB()) {
        colorSpaceFilter = SkToSRGBColorFilter::Make(colorSpace);
    }

    if (mColorFilter && colorSpaceFilter) {
        mColorSpaceWithFilter = mColorFilter->makeComposed(colorSpaceFilter);
    } else if (colorSpaceFilter) {
        mColorSpaceWithFilter = colorSpaceFilter;
    } else {
        mColorSpaceWithFilter = mColorFilter;
    }
}

void Layer::postDecStrong() {
    mRenderState.postDecStrong(this);
}

};  // namespace uirenderer
};  // namespace android
