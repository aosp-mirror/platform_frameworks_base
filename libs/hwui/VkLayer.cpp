/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "VkLayer.h"

#include "renderstate/RenderState.h"

#include <SkCanvas.h>
#include <SkSurface.h>

namespace android {
namespace uirenderer {

void VkLayer::updateTexture() {
    sk_sp<SkSurface> surface;
    SkImageInfo info = SkImageInfo::MakeS32(mWidth, mHeight, kPremul_SkAlphaType);
    surface = SkSurface::MakeRenderTarget(mRenderState.getGrContext(), SkBudgeted::kNo, info);
    surface->getCanvas()->clear(SK_ColorBLUE);
    mImage = surface->makeImageSnapshot();
}

void VkLayer::onVkContextDestroyed() {
    mImage = nullptr;
}

};  // namespace uirenderer
};  // namespace android
