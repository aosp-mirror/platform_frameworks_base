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

#pragma once

#include <android/hardware_buffer.h>
#include <utils/NdkUtils.h>
#include <utils/RefBase.h>

#include "FunctorDrawable.h"

namespace android {
namespace uirenderer {

namespace skiapipeline {

/**
 * This drawable wraps a Vulkan functor enabling it to be recorded into a list
 * of Skia drawing commands.
 */
class VkInteropFunctorDrawable : public FunctorDrawable {
public:
    using FunctorDrawable::FunctorDrawable;

    virtual ~VkInteropFunctorDrawable() {}

    static void vkInvokeFunctor(Functor* functor);

    void syncFunctor(const WebViewSyncData& data) const override;

protected:
    virtual void onDraw(SkCanvas* canvas) override;

private:
    // Variables below describe/store temporary offscreen buffer used for Vulkan pipeline.
    UniqueAHardwareBuffer mFrameBuffer;
    SkImageInfo mFBInfo;
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
