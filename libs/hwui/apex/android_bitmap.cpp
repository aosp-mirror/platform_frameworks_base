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

#undef LOG_TAG
#define LOG_TAG "Bitmap"
#include <log/log.h>

#include "android/graphics/bitmap.h"
#include "TypeCast.h"
#include "GraphicsJNI.h"

#include <GraphicsJNI.h>
#include <hwui/Bitmap.h>
#include <utils/Color.h>

using namespace android;

ABitmap* ABitmap_acquireBitmapFromJava(JNIEnv* env, jobject bitmapObj) {
    Bitmap* bitmap = GraphicsJNI::getNativeBitmap(env, bitmapObj);
    if (bitmap) {
        bitmap->ref();
        return TypeCast::toABitmap(bitmap);
    }
    return nullptr;
}

void ABitmap_acquireRef(ABitmap* bitmap) {
    SkSafeRef(TypeCast::toBitmap(bitmap));
}

void ABitmap_releaseRef(ABitmap* bitmap) {
    SkSafeUnref(TypeCast::toBitmap(bitmap));
}

static AndroidBitmapFormat getFormat(const SkImageInfo& info) {
    switch (info.colorType()) {
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

static SkColorType getColorType(AndroidBitmapFormat format) {
    switch (format) {
        case ANDROID_BITMAP_FORMAT_RGBA_8888:
            return kN32_SkColorType;
        case ANDROID_BITMAP_FORMAT_RGB_565:
            return kRGB_565_SkColorType;
        case ANDROID_BITMAP_FORMAT_RGBA_4444:
            return kARGB_4444_SkColorType;
        case ANDROID_BITMAP_FORMAT_A_8:
            return kAlpha_8_SkColorType;
        case ANDROID_BITMAP_FORMAT_RGBA_F16:
            return kRGBA_F16_SkColorType;
        default:
            return kUnknown_SkColorType;
    }
}

static uint32_t getAlphaFlags(const SkImageInfo& info) {
    switch (info.alphaType()) {
        case kUnknown_SkAlphaType:
            LOG_ALWAYS_FATAL("Bitmap has no alpha type");
            break;
        case kOpaque_SkAlphaType:
            return ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE;
        case kPremul_SkAlphaType:
            return ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
        case kUnpremul_SkAlphaType:
            return ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL;
    }
}

static uint32_t getInfoFlags(const SkImageInfo& info, bool isHardware) {
    uint32_t flags = getAlphaFlags(info);
    if (isHardware) {
        flags |= ANDROID_BITMAP_FLAGS_IS_HARDWARE;
    }
    return flags;
}

ABitmap* ABitmap_copy(ABitmap* srcBitmapHandle, AndroidBitmapFormat dstFormat) {
    SkColorType dstColorType = getColorType(dstFormat);
    if (srcBitmapHandle && dstColorType != kUnknown_SkColorType) {
        SkBitmap srcBitmap;
        TypeCast::toBitmap(srcBitmapHandle)->getSkBitmap(&srcBitmap);

        sk_sp<Bitmap> dstBitmap =
                Bitmap::allocateHeapBitmap(srcBitmap.info().makeColorType(dstColorType));
        if (dstBitmap && srcBitmap.readPixels(dstBitmap->info(), dstBitmap->pixels(),
                                              dstBitmap->rowBytes(), 0, 0)) {
            return TypeCast::toABitmap(dstBitmap.release());
        }
    }
    return nullptr;
}

static AndroidBitmapInfo getInfo(const SkImageInfo& imageInfo, uint32_t rowBytes, bool isHardware) {
    AndroidBitmapInfo info;
    info.width = imageInfo.width();
    info.height = imageInfo.height();
    info.stride = rowBytes;
    info.format = getFormat(imageInfo);
    info.flags = getInfoFlags(imageInfo, isHardware);
    return info;
}

AndroidBitmapInfo ABitmap_getInfo(ABitmap* bitmapHandle) {
    Bitmap* bitmap = TypeCast::toBitmap(bitmapHandle);
    return getInfo(bitmap->info(), bitmap->rowBytes(), bitmap->isHardware());
}

ADataSpace ABitmap_getDataSpace(ABitmap* bitmapHandle) {
    Bitmap* bitmap = TypeCast::toBitmap(bitmapHandle);
    const SkImageInfo& info = bitmap->info();
    return (ADataSpace)uirenderer::ColorSpaceToADataSpace(info.colorSpace(), info.colorType());
}

AndroidBitmapInfo ABitmap_getInfoFromJava(JNIEnv* env, jobject bitmapObj) {
    uint32_t rowBytes = 0;
    bool isHardware = false;
    SkImageInfo imageInfo = GraphicsJNI::getBitmapInfo(env, bitmapObj, &rowBytes, &isHardware);
    return getInfo(imageInfo, rowBytes, isHardware);
}

void* ABitmap_getPixels(ABitmap* bitmapHandle) {
    Bitmap* bitmap = TypeCast::toBitmap(bitmapHandle);
    if (bitmap->isHardware()) {
        return nullptr;
    }
    return bitmap->pixels();
}

AndroidBitmapFormat ABitmapConfig_getFormatFromConfig(JNIEnv* env, jobject bitmapConfigObj) {
    return GraphicsJNI::getFormatFromConfig(env, bitmapConfigObj);
}

jobject ABitmapConfig_getConfigFromFormat(JNIEnv* env, AndroidBitmapFormat format) {
    return GraphicsJNI::getConfigFromFormat(env, format);
}

void ABitmap_notifyPixelsChanged(ABitmap* bitmapHandle) {
    Bitmap* bitmap = TypeCast::toBitmap(bitmapHandle);
    if (!bitmap->isImmutable()) {
        bitmap->notifyPixelsChanged();
    }
}

namespace {
SkAlphaType getAlphaType(const AndroidBitmapInfo* info) {
    switch (info->flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) {
        case ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE:
            return kOpaque_SkAlphaType;
        case ANDROID_BITMAP_FLAGS_ALPHA_PREMUL:
            return kPremul_SkAlphaType;
        case ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL:
            return kUnpremul_SkAlphaType;
        default:
            return kUnknown_SkAlphaType;
    }
}

class CompressWriter : public SkWStream {
public:
    CompressWriter(void* userContext, AndroidBitmap_CompressWriteFunc fn)
          : mUserContext(userContext), mFn(fn), mBytesWritten(0) {}

    bool write(const void* buffer, size_t size) override {
        if (mFn(mUserContext, buffer, size)) {
            mBytesWritten += size;
            return true;
        }
        return false;
    }

    size_t bytesWritten() const override { return mBytesWritten; }

private:
    void* mUserContext;
    AndroidBitmap_CompressWriteFunc mFn;
    size_t mBytesWritten;
};

} // anonymous namespace

int ABitmap_compress(const AndroidBitmapInfo* info, ADataSpace dataSpace, const void* pixels,
                     AndroidBitmapCompressFormat inFormat, int32_t quality, void* userContext,
                     AndroidBitmap_CompressWriteFunc fn) {
    Bitmap::JavaCompressFormat format;
    switch (inFormat) {
        case ANDROID_BITMAP_COMPRESS_FORMAT_JPEG:
            format = Bitmap::JavaCompressFormat::Jpeg;
            break;
        case ANDROID_BITMAP_COMPRESS_FORMAT_PNG:
            format = Bitmap::JavaCompressFormat::Png;
            break;
        case ANDROID_BITMAP_COMPRESS_FORMAT_WEBP_LOSSY:
            format = Bitmap::JavaCompressFormat::WebpLossy;
            break;
        case ANDROID_BITMAP_COMPRESS_FORMAT_WEBP_LOSSLESS:
            format = Bitmap::JavaCompressFormat::WebpLossless;
            break;
        default:
            // kWEBP_JavaEncodeFormat is a valid parameter for Bitmap::compress,
            // for the deprecated Bitmap.CompressFormat.WEBP, but it should not
            // be provided via the NDK. Other integers are likewise invalid.
            return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    }

    SkColorType colorType;
    switch (info->format) {
        case ANDROID_BITMAP_FORMAT_RGBA_8888:
            colorType = kN32_SkColorType;
            break;
        case ANDROID_BITMAP_FORMAT_RGB_565:
            colorType = kRGB_565_SkColorType;
            break;
        case ANDROID_BITMAP_FORMAT_A_8:
            // FIXME b/146637821: Should this encode as grayscale? We should
            // make the same decision as for encoding an android.graphics.Bitmap.
            // Note that encoding kAlpha_8 as WebP or JPEG will fail. Encoding
            // it to PNG encodes as GRAY+ALPHA with a secret handshake that we
            // only care about the alpha. I'm not sure whether Android decoding
            // APIs respect that handshake.
            colorType = kAlpha_8_SkColorType;
            break;
        case ANDROID_BITMAP_FORMAT_RGBA_F16:
            colorType = kRGBA_F16_SkColorType;
            break;
        default:
            return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    }

    auto alphaType = getAlphaType(info);
    if (alphaType == kUnknown_SkAlphaType) {
        return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    }

    sk_sp<SkColorSpace> cs;
    if (info->format == ANDROID_BITMAP_FORMAT_A_8) {
        // FIXME: A Java Bitmap with ALPHA_8 never has a ColorSpace. So should
        // we force that here (as I'm doing now) or should we treat anything
        // besides ADATASPACE_UNKNOWN as an error?
        cs = nullptr;
    } else {
        cs = uirenderer::DataSpaceToColorSpace((android_dataspace) dataSpace);
        // DataSpaceToColorSpace treats UNKNOWN as SRGB, but compress forces the
        // client to specify SRGB if that is what they want.
        if (!cs || dataSpace == ADATASPACE_UNKNOWN) {
            return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
        }
    }

    {
        size_t size;
        if (!Bitmap::computeAllocationSize(info->stride, info->height, &size)) {
            return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
        }
    }

    auto imageInfo =
            SkImageInfo::Make(info->width, info->height, colorType, alphaType, std::move(cs));
    SkBitmap bitmap;
    // We are not going to modify the pixels, but installPixels expects them to
    // not be const, since for all it knows we might want to draw to the SkBitmap.
    if (!bitmap.installPixels(imageInfo, const_cast<void*>(pixels), info->stride)) {
        return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    }

    CompressWriter stream(userContext, fn);
    return Bitmap::compress(bitmap, format, quality, &stream) ? ANDROID_BITMAP_RESULT_SUCCESS
                                                              : ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
}

AHardwareBuffer* ABitmap_getHardwareBuffer(ABitmap* bitmapHandle) {
    Bitmap* bitmap = TypeCast::toBitmap(bitmapHandle);
    AHardwareBuffer* buffer = bitmap->hardwareBuffer();
    if (buffer) {
        AHardwareBuffer_acquire(buffer);
    }
    return buffer;
}
