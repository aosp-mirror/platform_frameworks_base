/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "VkFunctorDrawable.h"
#include <private/hwui/DrawVkInfo.h>

#include "thread/ThreadBase.h"
#include "utils/TimeUtils.h"
#include <GrBackendDrawableInfo.h>
#include <thread>
#include <utils/Color.h>
#include <utils/Trace.h>
#include <utils/TraceUtils.h>
#include <SkImage.h>
#include <vk/GrVkTypes.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

VkFunctorDrawHandler::VkFunctorDrawHandler(Functor *functor)
    : INHERITED()
    , mFunctor(functor) {}

VkFunctorDrawHandler::~VkFunctorDrawHandler() {
    // TODO(cblume) Fill in the DrawVkInfo parameters.
    (*mFunctor)(DrawVkInfo::kModePostComposite, nullptr);
}

void VkFunctorDrawHandler::draw(const GrBackendDrawableInfo& info) {
    ATRACE_CALL();

    GrVkDrawableInfo vulkan_info;
    if (!info.getVkDrawableInfo(&vulkan_info)) {
        return;
    }

    DrawVkInfo draw_vk_info;
    // TODO(cblume) Fill in the rest of the parameters and test the actual call.
    draw_vk_info.isLayer = true;

    (*mFunctor)(DrawVkInfo::kModeComposite, &draw_vk_info);
}

VkFunctorDrawable::VkFunctorDrawable(Functor* functor, GlFunctorLifecycleListener* listener,
                                     SkCanvas* canvas)
    : FunctorDrawable(functor, listener, canvas) {}

VkFunctorDrawable::~VkFunctorDrawable() = default;

void VkFunctorDrawable::syncFunctor() const {
    (*mFunctor)(DrawVkInfo::kModeSync, nullptr);
}

void VkFunctorDrawable::onDraw(SkCanvas* /*canvas*/) {
    LOG_ALWAYS_FATAL("VkFunctorDrawable::onDraw() should never be called.");
    // Instead of calling onDraw(), the call should come from onSnapGpuDrawHandler.
}

std::unique_ptr<FunctorDrawable::GpuDrawHandler> VkFunctorDrawable::onSnapGpuDrawHandler(
    GrBackendApi backendApi, const SkMatrix& matrix) {
    if (backendApi != GrBackendApi::kVulkan) {
        return nullptr;
    }
    std::unique_ptr<VkFunctorDrawHandler> draw(new VkFunctorDrawHandler(mFunctor));
    return std::move(draw);
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
