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

#include "ImageDecoder.h"

#include <hwui/Bitmap.h>

#include <SkAndroidCodec.h>
#include <SkCanvas.h>
#include <SkPaint.h>

using namespace android;

sk_sp<SkColorSpace> ImageDecoder::getDefaultColorSpace() const {
    const skcms_ICCProfile* encodedProfile = mCodec->getICCProfile();
    if (encodedProfile) {
        // If the profile maps directly to an SkColorSpace, that SkColorSpace
        // will be returned. Otherwise, nullptr will be returned. In either
        // case, using this SkColorSpace results in doing no color correction.
        return SkColorSpace::Make(*encodedProfile);
    }

    // The image has no embedded color profile, and should be treated as SRGB.
    return SkColorSpace::MakeSRGB();
}

ImageDecoder::ImageDecoder(std::unique_ptr<SkAndroidCodec> codec, sk_sp<SkPngChunkReader> peeker)
    : mCodec(std::move(codec))
    , mPeeker(std::move(peeker))
    , mTargetSize(mCodec->getInfo().dimensions())
    , mDecodeSize(mTargetSize)
    , mOutColorType(mCodec->computeOutputColorType(kN32_SkColorType))
    , mUnpremultipliedRequired(false)
    , mOutColorSpace(getDefaultColorSpace())
    , mSampleSize(1)
{
}

SkAlphaType ImageDecoder::getOutAlphaType() const {
    return opaque() ? kOpaque_SkAlphaType
                    : mUnpremultipliedRequired ? kUnpremul_SkAlphaType : kPremul_SkAlphaType;
}

bool ImageDecoder::setTargetSize(int width, int height) {
    if (width <= 0 || height <= 0) {
        return false;
    }

    auto info = SkImageInfo::Make(width, height, mOutColorType, getOutAlphaType());
    size_t rowBytes = info.minRowBytes();
    if (rowBytes == 0) {
        // This would have overflowed.
        return false;
    }

    size_t pixelMemorySize;
    if (!Bitmap::computeAllocationSize(rowBytes, height, &pixelMemorySize)) {
        return false;
    }

    if (mCropRect) {
        if (mCropRect->right() > width || mCropRect->bottom() > height) {
            return false;
        }
    }

    SkISize targetSize = { width, height }, decodeSize = targetSize;
    int sampleSize = mCodec->computeSampleSize(&decodeSize);

    if (decodeSize != targetSize && mUnpremultipliedRequired && !opaque()) {
        return false;
    }

    mTargetSize = targetSize;
    mDecodeSize = decodeSize;
    mSampleSize = sampleSize;
    return true;
}

bool ImageDecoder::setCropRect(const SkIRect* crop) {
    if (!crop) {
        mCropRect.reset();
        return true;
    }

    if (crop->left() >= crop->right() || crop->top() >= crop->bottom()) {
        return false;
    }

    const auto& size = mTargetSize;
    if (crop->left() < 0 || crop->top() < 0
            || crop->right() > size.width() || crop->bottom() > size.height()) {
      return false;
    }

    mCropRect.emplace(*crop);
    return true;
}

bool ImageDecoder::setOutColorType(SkColorType colorType) {
    switch (colorType) {
        case kRGB_565_SkColorType:
            if (!opaque()) {
                return false;
            }
            break;
        case kGray_8_SkColorType:
            if (!gray()) {
                return false;
            }
            break;
        case kN32_SkColorType:
            break;
        case kRGBA_F16_SkColorType:
            break;
        default:
            return false;
    }

    mOutColorType = colorType;
    return true;
}

bool ImageDecoder::setUnpremultipliedRequired(bool required) {
    if (required && !opaque() && mDecodeSize != mTargetSize) {
        return false;
    }
    mUnpremultipliedRequired = required;
    return true;
}

void ImageDecoder::setOutColorSpace(sk_sp<SkColorSpace> colorSpace) {
    mOutColorSpace = std::move(colorSpace);
}

sk_sp<SkColorSpace> ImageDecoder::getOutputColorSpace() const {
    // kGray_8 is used for ALPHA_8, which ignores the color space.
    return mOutColorType == kGray_8_SkColorType ? nullptr : mOutColorSpace;
}


SkImageInfo ImageDecoder::getOutputInfo() const {
    SkISize size = mCropRect ? mCropRect->size() : mTargetSize;
    return SkImageInfo::Make(size, mOutColorType, getOutAlphaType(), getOutputColorSpace());
}

bool ImageDecoder::opaque() const {
    return mCodec->getInfo().alphaType() == kOpaque_SkAlphaType;
}

bool ImageDecoder::gray() const {
    return mCodec->getInfo().colorType() == kGray_8_SkColorType;
}

SkCodec::Result ImageDecoder::decode(void* pixels, size_t rowBytes) {
    void* decodePixels = pixels;
    size_t decodeRowBytes = rowBytes;
    auto decodeInfo = SkImageInfo::Make(mDecodeSize, mOutColorType, getOutAlphaType(),
                                        getOutputColorSpace());
    // Used if we need a temporary before scaling or subsetting.
    // FIXME: Use scanline decoding on only a couple lines to save memory. b/70709380.
    SkBitmap tmp;
    const bool scale = mDecodeSize != mTargetSize;
    if (scale || mCropRect) {
        if (!tmp.setInfo(decodeInfo)) {
            return SkCodec::kInternalError;
        }
        if (!Bitmap::allocateHeapBitmap(&tmp)) {
            return SkCodec::kInternalError;
        }
        decodePixels = tmp.getPixels();
        decodeRowBytes = tmp.rowBytes();
    }

    SkAndroidCodec::AndroidOptions options;
    options.fSampleSize = mSampleSize;
    auto result = mCodec->getAndroidPixels(decodeInfo, decodePixels, decodeRowBytes, &options);

    if (scale || mCropRect) {
        SkBitmap scaledBm;
        if (!scaledBm.installPixels(getOutputInfo(), pixels, rowBytes)) {
            return SkCodec::kInternalError;
        }

        SkPaint paint;
        paint.setBlendMode(SkBlendMode::kSrc);
        paint.setFilterQuality(kLow_SkFilterQuality);  // bilinear filtering

        SkCanvas canvas(scaledBm, SkCanvas::ColorBehavior::kLegacy);
        if (mCropRect) {
            canvas.translate(-mCropRect->fLeft, -mCropRect->fTop);
        }
        if (scale) {
            float scaleX = (float) mTargetSize.width()  / mDecodeSize.width();
            float scaleY = (float) mTargetSize.height() / mDecodeSize.height();
            canvas.scale(scaleX, scaleY);
        }

        canvas.drawBitmap(tmp, 0.0f, 0.0f, &paint);
    }

    return result;
}

