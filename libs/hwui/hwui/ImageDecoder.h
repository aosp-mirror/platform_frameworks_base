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

#include <SkAndroidCodec.h>
#include <SkCodec.h>
#include <SkColorSpace.h>
#include <SkImageInfo.h>
#include <SkPngChunkReader.h>
#include <SkRect.h>
#include <SkRefCnt.h>
#include <SkSize.h>
#include <cutils/compiler.h>

#include <optional>

namespace android {

class Bitmap;

class ANDROID_API ImageDecoder final {
public:
    std::unique_ptr<SkAndroidCodec> mCodec;
    sk_sp<SkPngChunkReader> mPeeker;

    ImageDecoder(std::unique_ptr<SkAndroidCodec> codec, sk_sp<SkPngChunkReader> peeker = nullptr,
                 SkCodec::ZeroInitialized zeroInit = SkCodec::kNo_ZeroInitialized);
    ~ImageDecoder();

    SkISize getSampledDimensions(int sampleSize) const;
    bool setTargetSize(int width, int height);
    bool setCropRect(const SkIRect*);

    bool setOutColorType(SkColorType outColorType);

    bool setUnpremultipliedRequired(bool unpremultipliedRequired);

    sk_sp<SkColorSpace> getDefaultColorSpace() const;
    void setOutColorSpace(sk_sp<SkColorSpace> cs);

    // The size is the final size after scaling, adjusting for the origin, and
    // cropping.
    SkImageInfo getOutputInfo() const;

    int width() const;
    int height() const;

    // True if the current frame is opaque.
    bool opaque() const;

    bool gray() const;

    SkCodec::Result decode(void* pixels, size_t rowBytes);

    // Return true if the decoder has advanced beyond all frames.
    bool finished() const;

    bool advanceFrame();
    bool rewind();

    bool isAnimated();
    int currentFrame() const;

    SkCodec::FrameInfo getCurrentFrameInfo();

    // Set whether the ImageDecoder should handle RestorePrevious frames.
    void setHandleRestorePrevious(bool handle);

private:
    // State machine for keeping track of how to handle RestorePrevious (RP)
    // frames in decode().
    enum class RestoreState {
        // Neither this frame nor the prior is RP, so there is no need to cache
        // or restore.
        kDoNothing,

        // This is the first in a sequence of one or more RP frames. decode()
        // needs to cache the provided pixels.
        kFirstRPFrame,

        // This is the second (or later) in a sequence of multiple RP frames.
        // decode() needs to restore the cached frame that preceded the first RP
        // frame in the sequence.
        kRPFrame,

        // This is the first non-RP frame after a sequence of one or more RP
        // frames. decode() still needs to restore the cached frame. Separate
        // from kRPFrame because if the following frame is RP the state will
        // change to kFirstRPFrame.
        kNeedsRestore,
    };

    SkISize mTargetSize;
    SkISize mDecodeSize;
    SkColorType mOutColorType;
    bool mUnpremultipliedRequired;
    sk_sp<SkColorSpace> mOutColorSpace;
    SkAndroidCodec::AndroidOptions mOptions;
    bool mCurrentFrameIsIndependent;
    bool mCurrentFrameIsOpaque;
    bool mHandleRestorePrevious;
    RestoreState mRestoreState;
    sk_sp<Bitmap> mRestoreFrame;
    std::optional<SkIRect> mCropRect;

    ImageDecoder(const ImageDecoder&) = delete;
    ImageDecoder& operator=(const ImageDecoder&) = delete;

    SkAlphaType getOutAlphaType() const;
    sk_sp<SkColorSpace> getOutputColorSpace() const;
    bool swapWidthHeight() const;
    // Store/restore a frame if necessary. Returns false on error.
    bool handleRestorePrevious(const SkImageInfo&, void* pixels, size_t rowBytes);
};

} // namespace android
