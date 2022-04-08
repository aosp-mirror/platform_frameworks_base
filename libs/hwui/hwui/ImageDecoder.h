/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <SkCodec.h>
#include <SkImageInfo.h>
#include <SkPngChunkReader.h>
#include <SkRect.h>
#include <SkSize.h>
#include <cutils/compiler.h>

#include <optional>

class SkAndroidCodec;

namespace android {

class ANDROID_API ImageDecoder {
public:
    std::unique_ptr<SkAndroidCodec> mCodec;
    sk_sp<SkPngChunkReader> mPeeker;

    ImageDecoder(std::unique_ptr<SkAndroidCodec> codec,
                 sk_sp<SkPngChunkReader> peeker = nullptr);

    bool setTargetSize(int width, int height);
    bool setCropRect(const SkIRect*);

    bool setOutColorType(SkColorType outColorType);

    bool setUnpremultipliedRequired(bool unpremultipliedRequired);

    sk_sp<SkColorSpace> getDefaultColorSpace() const;
    void setOutColorSpace(sk_sp<SkColorSpace> cs);

    // The size is the final size after scaling and cropping.
    SkImageInfo getOutputInfo() const;

    bool opaque() const;
    bool gray() const;

    SkCodec::Result decode(void* pixels, size_t rowBytes);

private:
    SkISize mTargetSize;
    SkISize mDecodeSize;
    SkColorType mOutColorType;
    bool mUnpremultipliedRequired;
    sk_sp<SkColorSpace> mOutColorSpace;
    int mSampleSize;
    std::optional<SkIRect> mCropRect;

    ImageDecoder(const ImageDecoder&) = delete;
    ImageDecoder& operator=(const ImageDecoder&) = delete;

    SkAlphaType getOutAlphaType() const;
    sk_sp<SkColorSpace> getOutputColorSpace() const;
};

} // namespace android
