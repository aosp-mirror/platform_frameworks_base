/*
 * Copyright (C) 2013 The Android Open Source Project
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
#ifndef HARDWAREBUFFERRENDERER_H_
#define HARDWAREBUFFERRENDERER_H_

#include <android-base/unique_fd.h>
#include <android/hardware_buffer.h>

#include "SkColorSpace.h"
#include "SkMatrix.h"
#include "SkSurface.h"

namespace android {
namespace uirenderer {
namespace renderthread {

using namespace android::uirenderer::renderthread;

using RenderCallback = std::function<void(android::base::unique_fd&&, int)>;

class RenderProxy;

class HardwareBufferRenderParams {
public:
    HardwareBufferRenderParams() = default;
    HardwareBufferRenderParams(const SkMatrix& transform, const sk_sp<SkColorSpace>& colorSpace,
                               RenderCallback&& callback)
            : mTransform(transform)
            , mColorSpace(colorSpace)
            , mRenderCallback(std::move(callback)) {}
    const SkMatrix& getTransform() const { return mTransform; }
    sk_sp<SkColorSpace> getColorSpace() const { return mColorSpace; }

    void invokeRenderCallback(android::base::unique_fd&& fenceFd, int status) {
        if (mRenderCallback) {
            std::invoke(mRenderCallback, std::move(fenceFd), status);
        }
    }

private:
    SkMatrix mTransform = SkMatrix::I();
    sk_sp<SkColorSpace> mColorSpace = SkColorSpace::MakeSRGB();
    RenderCallback mRenderCallback = nullptr;
};

}  // namespace renderthread
}  // namespace uirenderer
}  // namespace android
#endif  // HARDWAREBUFFERRENDERER_H_
