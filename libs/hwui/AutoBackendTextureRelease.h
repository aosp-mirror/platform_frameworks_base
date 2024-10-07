/*
 * Copyright 2019 The Android Open Source Project
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

#include <SkImage.h>
#include <android/hardware_buffer.h>
#include <include/android/GrAHardwareBufferUtils.h>
#include <include/gpu/ganesh/GrBackendSurface.h>
#include <system/graphics.h>

namespace android {
namespace uirenderer {

// Friend TestUtils serves as a proxy for any test cases that require access to private members.
class TestUtils;

/**
 * AutoBackendTextureRelease manages EglImage/VkImage lifetime. It is a ref-counted object
 * that keeps GPU resources alive until the last SkImage object using them is destroyed.
 */
class AutoBackendTextureRelease final {
public:
    AutoBackendTextureRelease(GrDirectContext* context,
                              AHardwareBuffer* buffer);

    const GrBackendTexture& getTexture() const { return mBackendTexture; }

    // Only called on the RenderThread, so it need not be thread-safe.
    void ref() { mUsageCount++; }

    void unref(bool releaseImage);

    inline sk_sp<SkImage> getImage() const { return mImage; }

    void makeImage(AHardwareBuffer* buffer,
                   android_dataspace dataspace,
                   GrDirectContext* context);

    void newBufferContent(GrDirectContext* context);

    void releaseQueueOwnership(GrDirectContext* context);

private:
    // The only way to invoke dtor is with unref, when mUsageCount is 0.
    ~AutoBackendTextureRelease() {}

    GrBackendTexture mBackendTexture;
    GrAHardwareBufferUtils::DeleteImageProc mDeleteProc;
    GrAHardwareBufferUtils::UpdateImageProc mUpdateProc;
    GrAHardwareBufferUtils::TexImageCtx mImageCtx;

    // Starting with refcount 1, because the first ref is held by SurfaceTexture. Additional refs
    // are held by SkImages.
    int mUsageCount = 1;

    // mImage is the SkImage created from mBackendTexture.
    sk_sp<SkImage> mImage;

    // Friend TestUtils serves as a proxy for any test cases that require access to private members.
    friend class TestUtils;
};

} /* namespace uirenderer */
} /* namespace android */
