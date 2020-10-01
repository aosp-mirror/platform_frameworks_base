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

static int createFromStream(std::unique_ptr<SkStreamRewindable> stream, AImageDecoder** outDecoder) {
    SkCodec::Result result;
    auto codec = SkCodec::MakeFromStream(std::move(stream), &result, nullptr,
                                         SkCodec::SelectionPolicy::kPreferAnimation);
    auto androidCodec = SkAndroidCodec::MakeFromCodec(std::move(codec),
            SkAndroidCodec::ExifOrientationBehavior::kRespect);
    if (!androidCodec) {
        return ResultToErrorCode(result);
    }

    // AImageDecoderHeaderInfo_getWidth/Height return an int32_t. Ensure that
    // the conversion is safe.
    const auto& info = androidCodec->getInfo();
    if (info.width() > std::numeric_limits<int32_t>::max()
        || info.height() > std::numeric_limits<int32_t>::max()) {
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

    auto stream = std::make_unique<AAssetStreamAdaptor>(asset);
    return createFromStream(std::move(stream), outDecoder);
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
        default:
            return kUnknown_SkColorType;
    }
}

int AImageDecoder_setAndroidBitmapFormat(AImageDecoder* decoder, int32_t format) {
    if (!decoder || format < ANDROID_BITMAP_FORMAT_NONE
            || format > ANDROID_BITMAP_FORMAT_RGBA_F16) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }
    return toDecoder(decoder)->setOutColorType(getColorType((AndroidBitmapFormat) format))
            ? ANDROID_IMAGE_DECODER_SUCCESS : ANDROID_IMAGE_DECODER_INVALID_CONVERSION;
}

int AImageDecoder_setDataSpace(AImageDecoder* decoder, int32_t dataspace) {
    sk_sp<SkColorSpace> cs = uirenderer::DataSpaceToColorSpace((android_dataspace)dataspace);
    // 0 is ADATASPACE_UNKNOWN. We need an explicit request for an ADataSpace.
    if (!decoder || !dataspace || !cs) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    ImageDecoder* imageDecoder = toDecoder(decoder);
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
    return toDecoder(info)->mCodec->getInfo().width();
}

int32_t AImageDecoderHeaderInfo_getHeight(const AImageDecoderHeaderInfo* info) {
    if (!info) {
        return 0;
    }
    return toDecoder(info)->mCodec->getInfo().height();
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

    return toDecoder(decoder)->setUnpremultipliedRequired(required)
            ? ANDROID_IMAGE_DECODER_SUCCESS : ANDROID_IMAGE_DECODER_INVALID_CONVERSION;
}

int AImageDecoder_setTargetSize(AImageDecoder* decoder, int32_t width, int32_t height) {
    if (!decoder) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    return toDecoder(decoder)->setTargetSize(width, height)
            ? ANDROID_IMAGE_DECODER_SUCCESS : ANDROID_IMAGE_DECODER_INVALID_SCALE;
}

int AImageDecoder_computeSampledSize(const AImageDecoder* decoder, int sampleSize,
                                     int32_t* width, int32_t* height) {
    if (!decoder || !width || !height || sampleSize < 1) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    SkISize size = toDecoder(decoder)->mCodec->getSampledDimensions(sampleSize);
    *width = size.width();
    *height = size.height();
    return ANDROID_IMAGE_DECODER_SUCCESS;
}

int AImageDecoder_setCrop(AImageDecoder* decoder, ARect crop) {
    if (!decoder) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }

    SkIRect cropIRect;
    cropIRect.setLTRB(crop.left, crop.top, crop.right, crop.bottom);
    SkIRect* cropPtr = cropIRect == SkIRect::MakeEmpty() ? nullptr : &cropIRect;
    return toDecoder(decoder)->setCropRect(cropPtr)
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

    return ResultToErrorCode(imageDecoder->decode(pixels, stride));
}

void AImageDecoder_delete(AImageDecoder* decoder) {
    delete toDecoder(decoder);
}
