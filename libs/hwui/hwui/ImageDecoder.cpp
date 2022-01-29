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
#include <log/log.h>

#include <SkAndroidCodec.h>
#include <SkBitmap.h>
#include <SkBlendMode.h>
#include <SkCanvas.h>
#include <SkEncodedOrigin.h>
#include <SkPaint.h>

#undef LOG_TAG
#define LOG_TAG "ImageDecoder"

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

ImageDecoder::ImageDecoder(std::unique_ptr<SkAndroidCodec> codec, sk_sp<SkPngChunkReader> peeker,
                           SkCodec::ZeroInitialized zeroInit)
    : mCodec(std::move(codec))
    , mPeeker(std::move(peeker))
    , mDecodeSize(mCodec->codec()->dimensions())
    , mOutColorType(mCodec->computeOutputColorType(kN32_SkColorType))
    , mUnpremultipliedRequired(false)
    , mOutColorSpace(getDefaultColorSpace())
    , mHandleRestorePrevious(true)
{
    mTargetSize = swapWidthHeight() ? SkISize { mDecodeSize.height(), mDecodeSize.width() }
                                    : mDecodeSize;
    this->rewind();
    mOptions.fZeroInitialized = zeroInit;
}

ImageDecoder::~ImageDecoder() = default;

SkAlphaType ImageDecoder::getOutAlphaType() const {
    return opaque() ? kOpaque_SkAlphaType
                    : mUnpremultipliedRequired ? kUnpremul_SkAlphaType : kPremul_SkAlphaType;
}

static SkISize swapped(const SkISize& size) {
    return SkISize { size.height(), size.width() };
}

static bool requires_matrix_scaling(bool swapWidthHeight, const SkISize& decodeSize,
                                    const SkISize& targetSize) {
    return (swapWidthHeight && decodeSize != swapped(targetSize))
          || (!swapWidthHeight && decodeSize != targetSize);
}

SkISize ImageDecoder::getSampledDimensions(int sampleSize) const {
    auto size = mCodec->getSampledDimensions(sampleSize);
    return swapWidthHeight() ? swapped(size) : size;
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

    const bool swap = swapWidthHeight();
    const SkISize targetSize = { width, height };
    SkISize decodeSize = swap ? SkISize { height, width } : targetSize;
    int sampleSize = mCodec->computeSampleSize(&decodeSize);

    if (mUnpremultipliedRequired && !opaque()) {
        // Allow using a matrix to handle orientation, but not scaling.
        if (requires_matrix_scaling(swap, decodeSize, targetSize)) {
            return false;
        }
    }

    mTargetSize = targetSize;
    mDecodeSize = decodeSize;
    mOptions.fSampleSize = sampleSize;
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
        case kRGBA_1010102_SkColorType:
            break;
        default:
            return false;
    }

    mOutColorType = colorType;
    return true;
}

bool ImageDecoder::setUnpremultipliedRequired(bool required) {
    if (required && !opaque()) {
        if (requires_matrix_scaling(swapWidthHeight(), mDecodeSize, mTargetSize)) {
            return false;
        }
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

bool ImageDecoder::swapWidthHeight() const {
    return SkEncodedOriginSwapsWidthHeight(mCodec->codec()->getOrigin());
}

int ImageDecoder::width() const {
    return swapWidthHeight()
            ? mCodec->codec()->dimensions().height()
            : mCodec->codec()->dimensions().width();
}

int ImageDecoder::height() const {
    return swapWidthHeight()
            ? mCodec->codec()->dimensions().width()
            : mCodec->codec()->dimensions().height();
}

bool ImageDecoder::opaque() const {
    return mCurrentFrameIsOpaque;
}

bool ImageDecoder::gray() const {
    return mCodec->getInfo().colorType() == kGray_8_SkColorType;
}

bool ImageDecoder::isAnimated() {
    return mCodec->codec()->getFrameCount() > 1;
}

int ImageDecoder::currentFrame() const {
    return mOptions.fFrameIndex;
}

bool ImageDecoder::rewind() {
    mOptions.fFrameIndex = 0;
    mOptions.fPriorFrame = SkCodec::kNoFrame;
    mCurrentFrameIsIndependent = true;
    mCurrentFrameIsOpaque = mCodec->getInfo().isOpaque();
    mRestoreState = RestoreState::kDoNothing;
    mRestoreFrame = nullptr;

    // TODO: Rewind the input now instead of in the next call to decode, and
    // plumb through whether rewind succeeded.
    return true;
}

void ImageDecoder::setHandleRestorePrevious(bool handle) {
    mHandleRestorePrevious = handle;
    if (!handle) {
        mRestoreFrame = nullptr;
    }
}

bool ImageDecoder::advanceFrame() {
    const int frameIndex = ++mOptions.fFrameIndex;
    const int frameCount = mCodec->codec()->getFrameCount();
    if (frameIndex >= frameCount) {
        // Prevent overflow from repeated calls to advanceFrame.
        mOptions.fFrameIndex = frameCount;
        return false;
    }

    SkCodec::FrameInfo frameInfo;
    if (!mCodec->codec()->getFrameInfo(frameIndex, &frameInfo)
            || !frameInfo.fFullyReceived) {
        // Mark the decoder as finished, requiring a rewind.
        mOptions.fFrameIndex = frameCount;
        return false;
    }

    mCurrentFrameIsIndependent = frameInfo.fRequiredFrame == SkCodec::kNoFrame;
    mCurrentFrameIsOpaque = frameInfo.fAlphaType == kOpaque_SkAlphaType;

    if (frameInfo.fDisposalMethod == SkCodecAnimation::DisposalMethod::kRestorePrevious) {
        switch (mRestoreState) {
            case RestoreState::kDoNothing:
            case RestoreState::kNeedsRestore:
                mRestoreState = RestoreState::kFirstRPFrame;
                mOptions.fPriorFrame = frameIndex - 1;
                break;
            case RestoreState::kFirstRPFrame:
                mRestoreState = RestoreState::kRPFrame;
                break;
            case RestoreState::kRPFrame:
                // Unchanged.
                break;
        }
    } else { // New frame is not restore previous
        switch (mRestoreState) {
            case RestoreState::kFirstRPFrame:
            case RestoreState::kRPFrame:
                mRestoreState = RestoreState::kNeedsRestore;
                break;
            case RestoreState::kNeedsRestore:
                mRestoreState = RestoreState::kDoNothing;
                mRestoreFrame = nullptr;
                [[fallthrough]];
            case RestoreState::kDoNothing:
                mOptions.fPriorFrame = frameIndex - 1;
                break;
        }
    }

    return true;
}

SkCodec::FrameInfo ImageDecoder::getCurrentFrameInfo() {
    LOG_ALWAYS_FATAL_IF(finished());

    auto dims = mCodec->codec()->dimensions();
    SkCodec::FrameInfo info;
    if (!mCodec->codec()->getFrameInfo(mOptions.fFrameIndex, &info)) {
        // SkCodec may return false for a non-animated image. Provide defaults.
        info.fRequiredFrame = SkCodec::kNoFrame;
        info.fDuration = 0;
        info.fFullyReceived = true;
        info.fAlphaType = mCodec->codec()->getInfo().alphaType();
        info.fHasAlphaWithinBounds = info.fAlphaType != kOpaque_SkAlphaType;
        info.fDisposalMethod = SkCodecAnimation::DisposalMethod::kKeep;
        info.fBlend = SkCodecAnimation::Blend::kSrc;
        info.fFrameRect = SkIRect::MakeSize(dims);
    }

    if (auto origin = mCodec->codec()->getOrigin(); origin != kDefault_SkEncodedOrigin) {
        if (SkEncodedOriginSwapsWidthHeight(origin)) {
            dims = swapped(dims);
        }
        auto matrix = SkEncodedOriginToMatrix(origin, dims.width(), dims.height());
        auto rect = SkRect::Make(info.fFrameRect);
        LOG_ALWAYS_FATAL_IF(!matrix.mapRect(&rect));
        rect.roundIn(&info.fFrameRect);
    }
    return info;
}

bool ImageDecoder::finished() const {
    return mOptions.fFrameIndex >= mCodec->codec()->getFrameCount();
}

bool ImageDecoder::handleRestorePrevious(const SkImageInfo& outputInfo, void* pixels,
                                         size_t rowBytes) {
    if (!mHandleRestorePrevious) {
        return true;
    }

    switch (mRestoreState) {
        case RestoreState::kFirstRPFrame:{
            // This frame is marked kRestorePrevious. The prior frame should be in
            // |pixels|, and it is what we'll restore after each consecutive
            // kRestorePrevious frame. Cache it now.
            if (!(mRestoreFrame = Bitmap::allocateHeapBitmap(outputInfo))) {
                return false;
            }

            const uint8_t* srcRow = static_cast<uint8_t*>(pixels);
                  uint8_t* dstRow = static_cast<uint8_t*>(mRestoreFrame->pixels());
            for (int y = 0; y < outputInfo.height(); y++) {
                memcpy(dstRow, srcRow, outputInfo.minRowBytes());
                srcRow += rowBytes;
                dstRow += mRestoreFrame->rowBytes();
            }
            break;
        }
        case RestoreState::kRPFrame:
        case RestoreState::kNeedsRestore:
            // Restore the cached frame. It's possible that the client skipped decoding a frame, so
            // we never cached it.
            if (mRestoreFrame) {
                const uint8_t* srcRow = static_cast<uint8_t*>(mRestoreFrame->pixels());
                      uint8_t* dstRow = static_cast<uint8_t*>(pixels);
                for (int y = 0; y < outputInfo.height(); y++) {
                    memcpy(dstRow, srcRow, outputInfo.minRowBytes());
                    srcRow += mRestoreFrame->rowBytes();
                    dstRow += rowBytes;
                }
            }
            break;
        case RestoreState::kDoNothing:
            break;
    }
    return true;
}

SkCodec::Result ImageDecoder::decode(void* pixels, size_t rowBytes) {
    // This was checked inside setTargetSize, but it's possible the first frame
    // was opaque, so that method succeeded, but after calling advanceFrame, the
    // current frame is not opaque.
    if (mUnpremultipliedRequired && !opaque()) {
        // Allow using a matrix to handle orientation, but not scaling.
        if (requires_matrix_scaling(swapWidthHeight(), mDecodeSize, mTargetSize)) {
            return SkCodec::kInvalidScale;
        }
    }

    const auto outputInfo = getOutputInfo();
    if (!handleRestorePrevious(outputInfo, pixels, rowBytes)) {
        return SkCodec::kInternalError;
    }

    void* decodePixels = pixels;
    size_t decodeRowBytes = rowBytes;
    const auto decodeInfo = SkImageInfo::Make(mDecodeSize, mOutColorType, getOutAlphaType(),
                                              getOutputColorSpace());
    // Used if we need a temporary before scaling or subsetting.
    // FIXME: Use scanline decoding on only a couple lines to save memory. b/70709380.
    SkBitmap tmp;
    const bool scale = mDecodeSize != mTargetSize;
    const auto origin = mCodec->codec()->getOrigin();
    const bool handleOrigin = origin != kDefault_SkEncodedOrigin;
    SkMatrix outputMatrix;
    if (scale || handleOrigin || mCropRect) {
        if (mCropRect) {
            outputMatrix.setTranslate(-mCropRect->fLeft, -mCropRect->fTop);
        }

        int targetWidth  = mTargetSize.width();
        int targetHeight = mTargetSize.height();
        if (handleOrigin) {
            outputMatrix.preConcat(SkEncodedOriginToMatrix(origin, targetWidth, targetHeight));
            if (SkEncodedOriginSwapsWidthHeight(origin)) {
                std::swap(targetWidth, targetHeight);
            }
        }
        if (scale) {
            float scaleX = (float) targetWidth  / mDecodeSize.width();
            float scaleY = (float) targetHeight / mDecodeSize.height();
            outputMatrix.preScale(scaleX, scaleY);
        }
        // It's possible that this portion *does* have alpha, even if the
        // composed frame does not. In that case, the SkBitmap needs to have
        // alpha so it blends properly.
        if (!tmp.setInfo(decodeInfo.makeAlphaType(mUnpremultipliedRequired ? kUnpremul_SkAlphaType
                                                                           : kPremul_SkAlphaType)))
        {
            return SkCodec::kInternalError;
        }
        if (!Bitmap::allocateHeapBitmap(&tmp)) {
            return SkCodec::kInternalError;
        }
        decodePixels = tmp.getPixels();
        decodeRowBytes = tmp.rowBytes();

        if (!mCurrentFrameIsIndependent) {
            SkMatrix inverse;
            if (outputMatrix.invert(&inverse)) {
                SkCanvas canvas(tmp, SkCanvas::ColorBehavior::kLegacy);
                canvas.setMatrix(inverse);
                SkBitmap priorFrame;
                priorFrame.installPixels(outputInfo, pixels, rowBytes);
                priorFrame.setImmutable(); // Don't want asImage() to force a copy
                canvas.drawImage(priorFrame.asImage(), 0, 0,
                                 SkSamplingOptions(SkFilterMode::kLinear));
            } else {
                ALOGE("Failed to invert matrix!");
            }
        }

        // Even if the client did not provide zero initialized memory, the
        // memory we decode into is.
        mOptions.fZeroInitialized = SkCodec::kYes_ZeroInitialized;
    }

    auto result = mCodec->getAndroidPixels(decodeInfo, decodePixels, decodeRowBytes, &mOptions);

    // The next call to decode() may not provide zero initialized memory.
    mOptions.fZeroInitialized = SkCodec::kNo_ZeroInitialized;

    if (scale || handleOrigin || mCropRect) {
        SkBitmap scaledBm;
        if (!scaledBm.installPixels(outputInfo, pixels, rowBytes)) {
            return SkCodec::kInternalError;
        }

        SkPaint paint;
        paint.setBlendMode(SkBlendMode::kSrc);

        SkCanvas canvas(scaledBm, SkCanvas::ColorBehavior::kLegacy);
        canvas.setMatrix(outputMatrix);
        tmp.setImmutable(); // Don't want asImage() to force copy
        canvas.drawImage(tmp.asImage(), 0, 0, SkSamplingOptions(SkFilterMode::kLinear), &paint);
    }

    return result;
}

