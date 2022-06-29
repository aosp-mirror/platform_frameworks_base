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

#include "aassetstreamadaptor.h"

#include <android/asset_manager.h>
#include <android/bitmap.h>
#include <android/data_space.h>
#include <android/imagedecoder.h>
#include <MimeType.h>
#include <android/rect.h>
#include <hwui/ImageDecoder.h>
#include <log/log.h>
#include <SkAndroidCodec.h>
#include <utils/Color.h>

#include <fcntl.h>
#include <limits>
#include <optional>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

using namespace android;

int ResultToErrorCode(SkCodec::Result result) {
    switch (result) {
        case SkCodec::kIncompleteInput:
            return ANDROID_IMAGE_DECODER_INCOMPLETE;
        case SkCodec::kErrorInInput:
            return ANDROID_IMAGE_DECODER_ERROR;
        case SkCodec::kInvalidInput:
            return ANDROID_IMAGE_DECODER_INVALID_INPUT;
        case SkCodec::kCouldNotRewind:
            return ANDROID_IMAGE_DECODER_SEEK_ERROR;
        case SkCodec::kUnimplemented:
            return ANDROID_IMAGE_DECODER_UNSUPPORTED_FORMAT;
        case SkCodec::kInvalidConversion:
            return ANDROID_IMAGE_DECODER_INVALID_CONVERSION;
        case SkCodec::kInvalidParameters:
            return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
        case SkCodec::kSuccess:
            return ANDROID_IMAGE_DECODER_SUCCESS;
        case SkCodec::kInvalidScale:
            return ANDROID_IMAGE_DECODER_INVALID_SCALE;
        case SkCodec::kInternalError:
            return ANDROID_IMAGE_DECODER_INTERNAL_ERROR;
    }
}

const char* AImageDecoder_resultToString(int result) {
    switch (result) {
        case        ANDROID_IMAGE_DECODER_SUCCESS:
            return "ANDROID_IMAGE_DECODER_SUCCESS";
        case        ANDROID_IMAGE_DECODER_INCOMPLETE:
            return "ANDROID_IMAGE_DECODER_INCOMPLETE";
        case        ANDROID_IMAGE_DECODER_ERROR:
            return "ANDROID_IMAGE_DECODER_ERROR";
        case        ANDROID_IMAGE_DECODER_INVALID_CONVERSION:
            return "ANDROID_IMAGE_DECODER_INVALID_CONVERSION";
        case        ANDROID_IMAGE_DECODER_INVALID_SCALE:
            return "ANDROID_IMAGE_DECODER_INVALID_SCALE";
        case        ANDROID_IMAGE_DECODER_BAD_PARAMETER:
            return "ANDROID_IMAGE_DECODER_BAD_PARAMETER";
        case        ANDROID_IMAGE_DECODER_INVALID_INPUT:
            return "ANDROID_IMAGE_DECODER_INVALID_INPUT";
        case        ANDROID_IMAGE_DECODER_SEEK_ERROR:
            return "ANDROID_IMAGE_DECODER_SEEK_ERROR";
        case        ANDROID_IMAGE_DECODER_INTERNAL_ERROR:
            return "ANDROID_IMAGE_DECODER_INTERNAL_ERROR";
        case        ANDROID_IMAGE_DECODER_UNSUPPORTED_FORMAT:
            return "ANDROID_IMAGE_DECODER_UNSUPPORTED_FORMAT";
        case        ANDROID_IMAGE_DECODER_FINISHED:
            return "ANDROID_IMAGE_DECODER_FINISHED";
        case        ANDROID_IMAGE_DECODER_INVALID_STATE:
            return "ANDROID_IMAGE_DECODER_INVALID_STATE";
        default:
            return nullptr;
    }
}

static int createFromStream(std::unique_ptr<SkStreamRewindable> stream, AImageDecoder** outDecoder) {
    SkCodec::Result result;
    auto codec = SkCodec::MakeFromStream(std::move(stream), &result, nullptr,
                                         SkCodec::SelectionPolicy::kPreferAnimation);
    // These may be swapped due to the SkEncodedOrigin, but we're just checking
    // them to make sure they fit in int32_t.
    auto dimensions = codec->dimensions();
    auto androidCodec = SkAndroidCodec::MakeFromCodec(std::move(codec));
    if (!androidCodec) {
        return ResultToErrorCode(result);
    }

    // AImageDecoderHeaderInfo_getWidth/Height return an int32_t. Ensure that
    // the conversion is safe.
    if (dimensions.width() > std::numeric_limits<int32_t>::max() ||
        dimensions.height() > std::numeric_limits<int32_t>::max()) {
        return ANDROID_IMAGE_DECODER_INVALID_INPUT;
    }

    *outDecoder = reinterpret_cast<AImageDecoder*>(new ImageDecoder(std::move(androidCodec)));
    return ANDROID_IMAGE_DECODER_SUCCESS;
}

int AImageDecoder_createFromAAsset(AAsset* asset, AImageDecoder** outDecoder) {
    if (!asset || !outDecoder) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }
    *outDecoder = nullptr;

#ifdef __ANDROID__
    auto stream = std::make_unique<AAssetStreamAdaptor>(asset);
    return createFromStream(std::move(stream), outDecoder);
#else
    return ANDROID_IMAGE_DECODER_INTERNAL_ERROR;
#endif
}

static bool isSeekable(int descriptor) {
    return ::lseek64(descriptor, 0, SEEK_CUR) != -1;
}

int AImageDecoder_createFromFd(int fd, AImageDecoder** outDecoder) {
    if (fd <= 0 || !outDecoder) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    struct stat fdStat;
    if (fstat(fd, &fdStat) == -1) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    if (!isSeekable(fd)) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    // SkFILEStream will close its descriptor. Duplicate it so the client will
    // still be responsible for closing the original.
    int dupDescriptor = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    FILE* file = fdopen(dupDescriptor, "r");
    if (!file) {
        close(dupDescriptor);
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    auto stream = std::unique_ptr<SkStreamRewindable>(new SkFILEStream(file));
    return createFromStream(std::move(stream), outDecoder);
}

int AImageDecoder_createFromBuffer(const void* buffer, size_t length,
                                   AImageDecoder** outDecoder) {
    if (!buffer || !length  || !outDecoder) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }
    *outDecoder = nullptr;

    // The client is expected to keep the buffer alive as long as the
    // AImageDecoder, so we do not need to copy the buffer.
    auto stream = std::unique_ptr<SkStreamRewindable>(
            new SkMemoryStream(buffer, length, false /* copyData */));
    return createFromStream(std::move(stream), outDecoder);
}

static ImageDecoder* toDecoder(AImageDecoder* d) {
    return reinterpret_cast<ImageDecoder*>(d);
}

static const ImageDecoder* toDecoder(const AImageDecoder* d) {
    return reinterpret_cast<const ImageDecoder*>(d);
}

// Note: This differs from the version in android_bitmap.cpp in that this
// version returns kGray_8_SkColorType for ANDROID_BITMAP_FORMAT_A_8. SkCodec
// allows decoding single channel images to gray, which Android then treats
// as A_8/ALPHA_8.
static SkColorType getColorType(AndroidBitmapFormat format) {
    switch (format) {
        case ANDROID_BITMAP_FORMAT_RGBA_8888:
            return kN32_SkColorType;
        case ANDROID_BITMAP_FORMAT_RGB_565:
            return kRGB_565_SkColorType;
        case ANDROID_BITMAP_FORMAT_RGBA_4444:
            return kARGB_4444_SkColorType;
        case ANDROID_BITMAP_FORMAT_A_8:
            return kGray_8_SkColorType;
        case ANDROID_BITMAP_FORMAT_RGBA_F16:
            return kRGBA_F16_SkColorType;
        case ANDROID_BITMAP_FORMAT_RGBA_1010102:
            return kRGBA_1010102_SkColorType;
        default:
            return kUnknown_SkColorType;
    }
}

int AImageDecoder_setAndroidBitmapFormat(AImageDecoder* decoder, int32_t format) {
    if (!decoder || format < ANDROID_BITMAP_FORMAT_NONE ||
        format > ANDROID_BITMAP_FORMAT_RGBA_1010102) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    auto* imageDecoder = toDecoder(decoder);
    if (imageDecoder->currentFrame() != 0) {
        return ANDROID_IMAGE_DECODER_INVALID_STATE;
    }

    return imageDecoder->setOutColorType(getColorType((AndroidBitmapFormat) format))
            ? ANDROID_IMAGE_DECODER_SUCCESS : ANDROID_IMAGE_DECODER_INVALID_CONVERSION;
}

int AImageDecoder_setDataSpace(AImageDecoder* decoder, int32_t dataspace) {
    sk_sp<SkColorSpace> cs = uirenderer::DataSpaceToColorSpace((android_dataspace)dataspace);
    // 0 is ADATASPACE_UNKNOWN. We need an explicit request for an ADataSpace.
    if (!decoder || !dataspace || !cs) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    ImageDecoder* imageDecoder = toDecoder(decoder);
    if (imageDecoder->currentFrame() != 0) {
        return ANDROID_IMAGE_DECODER_INVALID_STATE;
    }

    imageDecoder->setOutColorSpace(std::move(cs));
    return ANDROID_IMAGE_DECODER_SUCCESS;
}

const AImageDecoderHeaderInfo* AImageDecoder_getHeaderInfo(const AImageDecoder* decoder) {
    return reinterpret_cast<const AImageDecoderHeaderInfo*>(decoder);
}

static const ImageDecoder* toDecoder(const AImageDecoderHeaderInfo* info) {
    return reinterpret_cast<const ImageDecoder*>(info);
}

int32_t AImageDecoderHeaderInfo_getWidth(const AImageDecoderHeaderInfo* info) {
    if (!info) {
        return 0;
    }
    return toDecoder(info)->width();
}

int32_t AImageDecoderHeaderInfo_getHeight(const AImageDecoderHeaderInfo* info) {
    if (!info) {
        return 0;
    }
    return toDecoder(info)->height();
}

const char* AImageDecoderHeaderInfo_getMimeType(const AImageDecoderHeaderInfo* info) {
    if (!info) {
        return nullptr;
    }
    return getMimeType(toDecoder(info)->mCodec->getEncodedFormat());
}

int32_t AImageDecoderHeaderInfo_getDataSpace(const AImageDecoderHeaderInfo* info) {
    if (!info) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    // Note: This recomputes the color type because it's possible the client has
    // changed the output color type, so we cannot rely on it. Alternatively,
    // we could store the ADataSpace in the ImageDecoder.
    const ImageDecoder* imageDecoder = toDecoder(info);
    SkColorType colorType = imageDecoder->mCodec->computeOutputColorType(kN32_SkColorType);
    sk_sp<SkColorSpace> colorSpace = imageDecoder->getDefaultColorSpace();
    return uirenderer::ColorSpaceToADataSpace(colorSpace.get(), colorType);
}

// FIXME: Share with getFormat in android_bitmap.cpp?
static AndroidBitmapFormat getFormat(SkColorType colorType) {
    switch (colorType) {
        case kN32_SkColorType:
            return ANDROID_BITMAP_FORMAT_RGBA_8888;
        case kRGB_565_SkColorType:
            return ANDROID_BITMAP_FORMAT_RGB_565;
        case kARGB_4444_SkColorType:
            return ANDROID_BITMAP_FORMAT_RGBA_4444;
        case kAlpha_8_SkColorType:
            return ANDROID_BITMAP_FORMAT_A_8;
        case kRGBA_F16_SkColorType:
            return ANDROID_BITMAP_FORMAT_RGBA_F16;
        case kRGBA_1010102_SkColorType:
            return ANDROID_BITMAP_FORMAT_RGBA_1010102;
        default:
            return ANDROID_BITMAP_FORMAT_NONE;
    }
}

int32_t AImageDecoderHeaderInfo_getAndroidBitmapFormat(const AImageDecoderHeaderInfo* info) {
    if (!info) {
        return ANDROID_BITMAP_FORMAT_NONE;
    }
    return getFormat(toDecoder(info)->mCodec->computeOutputColorType(kN32_SkColorType));
}

int AImageDecoderHeaderInfo_getAlphaFlags(const AImageDecoderHeaderInfo* info) {
    if (!info) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }
    switch (toDecoder(info)->mCodec->getInfo().alphaType()) {
        case kUnknown_SkAlphaType:
            LOG_ALWAYS_FATAL("Invalid alpha type");
            return ANDROID_IMAGE_DECODER_INTERNAL_ERROR;
        case kUnpremul_SkAlphaType:
            // fall through. premul is the default.
        case kPremul_SkAlphaType:
            return ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
        case kOpaque_SkAlphaType:
            return ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE;
    }
}

int AImageDecoder_setUnpremultipliedRequired(AImageDecoder* decoder, bool required) {
    if (!decoder) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    auto* imageDecoder = toDecoder(decoder);
    if (imageDecoder->currentFrame() != 0) {
        return ANDROID_IMAGE_DECODER_INVALID_STATE;
    }

    return imageDecoder->setUnpremultipliedRequired(required)
            ? ANDROID_IMAGE_DECODER_SUCCESS : ANDROID_IMAGE_DECODER_INVALID_CONVERSION;
}

int AImageDecoder_setTargetSize(AImageDecoder* decoder, int32_t width, int32_t height) {
    if (!decoder) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    auto* imageDecoder = toDecoder(decoder);
    if (imageDecoder->currentFrame() != 0) {
        return ANDROID_IMAGE_DECODER_INVALID_STATE;
    }

    return imageDecoder->setTargetSize(width, height)
            ? ANDROID_IMAGE_DECODER_SUCCESS : ANDROID_IMAGE_DECODER_INVALID_SCALE;
}

int AImageDecoder_computeSampledSize(const AImageDecoder* decoder, int sampleSize,
                                     int32_t* width, int32_t* height) {
    if (!decoder || !width || !height || sampleSize < 1) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    SkISize size = toDecoder(decoder)->getSampledDimensions(sampleSize);
    *width = size.width();
    *height = size.height();
    return ANDROID_IMAGE_DECODER_SUCCESS;
}

int AImageDecoder_setCrop(AImageDecoder* decoder, ARect crop) {
    if (!decoder) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    auto* imageDecoder = toDecoder(decoder);
    if (imageDecoder->currentFrame() != 0) {
        return ANDROID_IMAGE_DECODER_INVALID_STATE;
    }

    SkIRect cropIRect;
    cropIRect.setLTRB(crop.left, crop.top, crop.right, crop.bottom);
    SkIRect* cropPtr = cropIRect == SkIRect::MakeEmpty() ? nullptr : &cropIRect;
    return imageDecoder->setCropRect(cropPtr)
            ? ANDROID_IMAGE_DECODER_SUCCESS : ANDROID_IMAGE_DECODER_BAD_PARAMETER;
}


size_t AImageDecoder_getMinimumStride(AImageDecoder* decoder) {
    if (!decoder) {
        return 0;
    }

    SkImageInfo info = toDecoder(decoder)->getOutputInfo();
    return info.minRowBytes();
}

int AImageDecoder_decodeImage(AImageDecoder* decoder,
                              void* pixels, size_t stride,
                              size_t size) {
    if (!decoder || !pixels || !stride) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    ImageDecoder* imageDecoder = toDecoder(decoder);

    SkImageInfo info = imageDecoder->getOutputInfo();
    size_t minSize = info.computeByteSize(stride);
    if (SkImageInfo::ByteSizeOverflowed(minSize) || size < minSize || !info.validRowBytes(stride)) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    if (imageDecoder->finished()) {
        return ANDROID_IMAGE_DECODER_FINISHED;
    }

    return ResultToErrorCode(imageDecoder->decode(pixels, stride));
}

void AImageDecoder_delete(AImageDecoder* decoder) {
    delete toDecoder(decoder);
}

bool AImageDecoder_isAnimated(AImageDecoder* decoder) {
    if (!decoder) return false;

    ImageDecoder* imageDecoder = toDecoder(decoder);
    return imageDecoder->isAnimated();
}

int32_t AImageDecoder_getRepeatCount(AImageDecoder* decoder) {
    if (!decoder) return ANDROID_IMAGE_DECODER_BAD_PARAMETER;

    ImageDecoder* imageDecoder = toDecoder(decoder);
    const int count = imageDecoder->mCodec->codec()->getRepetitionCount();

    // Skia should not report anything out of range, but defensively treat
    // negative and too big as INFINITE.
    if (count == SkCodec::kRepetitionCountInfinite || count < 0
        || count > std::numeric_limits<int32_t>::max()) {
        return ANDROID_IMAGE_DECODER_INFINITE;
    }
    return count;
}

int AImageDecoder_advanceFrame(AImageDecoder* decoder) {
    if (!decoder) return ANDROID_IMAGE_DECODER_BAD_PARAMETER;

    ImageDecoder* imageDecoder = toDecoder(decoder);
    if (!imageDecoder->isAnimated()) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    const auto colorType = imageDecoder->getOutputInfo().colorType();
    switch (colorType) {
        case kN32_SkColorType:
        case kRGBA_F16_SkColorType:
            break;
        default:
            return ANDROID_IMAGE_DECODER_INVALID_STATE;
    }

    if (imageDecoder->advanceFrame()) {
        return ANDROID_IMAGE_DECODER_SUCCESS;
    }

    if (imageDecoder->finished()) {
        return ANDROID_IMAGE_DECODER_FINISHED;
    }

    return ANDROID_IMAGE_DECODER_INCOMPLETE;
}

int AImageDecoder_rewind(AImageDecoder* decoder) {
    if (!decoder) return ANDROID_IMAGE_DECODER_BAD_PARAMETER;

    ImageDecoder* imageDecoder = toDecoder(decoder);
    if (!imageDecoder->isAnimated()) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    return imageDecoder->rewind() ? ANDROID_IMAGE_DECODER_SUCCESS
                                  : ANDROID_IMAGE_DECODER_SEEK_ERROR;
}

AImageDecoderFrameInfo* AImageDecoderFrameInfo_create() {
    return reinterpret_cast<AImageDecoderFrameInfo*>(new SkCodec::FrameInfo);
}

static SkCodec::FrameInfo* toFrameInfo(AImageDecoderFrameInfo* info) {
    return reinterpret_cast<SkCodec::FrameInfo*>(info);
}

static const SkCodec::FrameInfo* toFrameInfo(const AImageDecoderFrameInfo* info) {
    return reinterpret_cast<const SkCodec::FrameInfo*>(info);
}

void AImageDecoderFrameInfo_delete(AImageDecoderFrameInfo* info) {
    delete toFrameInfo(info);
}

int AImageDecoder_getFrameInfo(AImageDecoder* decoder,
        AImageDecoderFrameInfo* info) {
    if (!decoder || !info) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    auto* imageDecoder = toDecoder(decoder);
    if (imageDecoder->finished()) {
        return ANDROID_IMAGE_DECODER_FINISHED;
    }

    *toFrameInfo(info) = imageDecoder->getCurrentFrameInfo();
    return ANDROID_IMAGE_DECODER_SUCCESS;
}

int64_t AImageDecoderFrameInfo_getDuration(const AImageDecoderFrameInfo* info) {
    if (!info) return ANDROID_IMAGE_DECODER_BAD_PARAMETER;

    return toFrameInfo(info)->fDuration * 1'000'000;
}

ARect AImageDecoderFrameInfo_getFrameRect(const AImageDecoderFrameInfo* info) {
    if (!info) {
        return { 0, 0, 0, 0};
    }

    const SkIRect& r = toFrameInfo(info)->fFrameRect;
    return { r.left(), r.top(), r.right(), r.bottom() };
}

bool AImageDecoderFrameInfo_hasAlphaWithinBounds(const AImageDecoderFrameInfo* info) {
    if (!info) return false;

    return toFrameInfo(info)->fHasAlphaWithinBounds;
}

int32_t AImageDecoderFrameInfo_getDisposeOp(const AImageDecoderFrameInfo* info) {
    if (!info) return ANDROID_IMAGE_DECODER_BAD_PARAMETER;

    static_assert(static_cast<int>(SkCodecAnimation::DisposalMethod::kKeep)
                  == ANDROID_IMAGE_DECODER_DISPOSE_OP_NONE);
    static_assert(static_cast<int>(SkCodecAnimation::DisposalMethod::kRestoreBGColor)
                  == ANDROID_IMAGE_DECODER_DISPOSE_OP_BACKGROUND);
    static_assert(static_cast<int>(SkCodecAnimation::DisposalMethod::kRestorePrevious)
                  == ANDROID_IMAGE_DECODER_DISPOSE_OP_PREVIOUS);
    return static_cast<int>(toFrameInfo(info)->fDisposalMethod);
}

int32_t AImageDecoderFrameInfo_getBlendOp(const AImageDecoderFrameInfo* info) {
    if (!info) return ANDROID_IMAGE_DECODER_BAD_PARAMETER;

    switch (toFrameInfo(info)->fBlend) {
        case SkCodecAnimation::Blend::kSrc:
            return ANDROID_IMAGE_DECODER_BLEND_OP_SRC;
        case SkCodecAnimation::Blend::kSrcOver:
            return ANDROID_IMAGE_DECODER_BLEND_OP_SRC_OVER;
    }
}

void AImageDecoder_setInternallyHandleDisposePrevious(AImageDecoder* decoder, bool handle) {
    if (decoder) {
        toDecoder(decoder)->setHandleRestorePrevious(handle);
    }
}
