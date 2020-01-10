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

#define LOG_TAG "Bitmap"
#include <log/log.h>

#include "android/graphics/bitmap.h"
#include "TypeCast.h"
#include "GraphicsJNI.h"

#include <GraphicsJNI.h>
#include <hwui/Bitmap.h>

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

static uint32_t getInfoFlags(const SkImageInfo& info) {
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

static AndroidBitmapInfo getInfo(const SkImageInfo& imageInfo, uint32_t rowBytes) {
    AndroidBitmapInfo info;
    info.width = imageInfo.width();
    info.height = imageInfo.height();
    info.stride = rowBytes;
    info.format = getFormat(imageInfo);
    info.flags = getInfoFlags(imageInfo);
    return info;
}

AndroidBitmapInfo ABitmap_getInfo(ABitmap* bitmapHandle) {
    Bitmap* bitmap = TypeCast::toBitmap(bitmapHandle);
    return getInfo(bitmap->info(), bitmap->rowBytes());
}

static bool nearlyEqual(float a, float b) {
    // By trial and error, this is close enough to match for the ADataSpaces we
    // compare for.
    return ::fabs(a-b) < .002f;
}

static bool nearlyEqual(const skcms_TransferFunction& x, const skcms_TransferFunction& y) {
    return nearlyEqual(x.g, y.g)
        && nearlyEqual(x.a, y.a)
        && nearlyEqual(x.b, y.b)
        && nearlyEqual(x.c, y.c)
        && nearlyEqual(x.d, y.d)
        && nearlyEqual(x.e, y.e)
        && nearlyEqual(x.f, y.f);
}

static bool nearlyEqual(const skcms_Matrix3x3& x, const skcms_Matrix3x3& y) {
    for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 3; j++) {
            if (!nearlyEqual(x.vals[i][j], y.vals[i][j])) return false;
        }
    }
    return true;
}

static constexpr skcms_TransferFunction k2Dot6 =
      { 2.6f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f };

// Skia's SkNamedGamut::kDCIP3 is based on a white point of D65. This gamut
// matches the white point used by ColorSpace.Named.DCIP3.
static constexpr skcms_Matrix3x3 kDCIP3 = {{
  { 0.486143, 0.323835, 0.154234 },
  { 0.226676, 0.710327, 0.0629966 },
  { 0.000800549, 0.0432385, 0.78275 },
}};

ADataSpace ABitmap_getDataSpace(ABitmap* bitmapHandle) {
    Bitmap* bitmap = TypeCast::toBitmap(bitmapHandle);
    const SkImageInfo& info = bitmap->info();
    SkColorSpace* colorSpace = info.colorSpace();
    if (!colorSpace) {
        return ADATASPACE_UNKNOWN;
    }

    if (colorSpace->isSRGB()) {
        if (info.colorType() == kRGBA_F16_SkColorType) {
            return ADATASPACE_SCRGB;
        }
        return ADATASPACE_SRGB;
    }

    skcms_TransferFunction fn;
    LOG_ALWAYS_FATAL_IF(!colorSpace->isNumericalTransferFn(&fn));

    skcms_Matrix3x3 gamut;
    LOG_ALWAYS_FATAL_IF(!colorSpace->toXYZD50(&gamut));

    if (nearlyEqual(gamut, SkNamedGamut::kSRGB)) {
        if (nearlyEqual(fn, SkNamedTransferFn::kLinear)) {
            // Skia doesn't differentiate amongst the RANGES. In Java, we associate
            // LINEAR_EXTENDED_SRGB with F16, and LINEAR_SRGB with other Configs.
            // Make the same association here.
            if (info.colorType() == kRGBA_F16_SkColorType) {
                return ADATASPACE_SCRGB_LINEAR;
            }
            return ADATASPACE_SRGB_LINEAR;
        }

        if (nearlyEqual(fn, SkNamedTransferFn::kRec2020)) {
            return ADATASPACE_BT709;
        }
    }

    if (nearlyEqual(fn, SkNamedTransferFn::kSRGB) && nearlyEqual(gamut, SkNamedGamut::kDCIP3)) {
        return ADATASPACE_DISPLAY_P3;
    }

    if (nearlyEqual(fn, SkNamedTransferFn::k2Dot2) && nearlyEqual(gamut, SkNamedGamut::kAdobeRGB)) {
        return ADATASPACE_ADOBE_RGB;
    }

    if (nearlyEqual(fn, SkNamedTransferFn::kRec2020)
            && nearlyEqual(gamut, SkNamedGamut::kRec2020)) {
        return ADATASPACE_BT2020;
    }

    if (nearlyEqual(fn, k2Dot6) && nearlyEqual(gamut, kDCIP3)) {
        return ADATASPACE_DCI_P3;
    }

    return ADATASPACE_UNKNOWN;
}

AndroidBitmapInfo ABitmap_getInfoFromJava(JNIEnv* env, jobject bitmapObj) {
    uint32_t rowBytes = 0;
    SkImageInfo imageInfo = GraphicsJNI::getBitmapInfo(env, bitmapObj, &rowBytes);
    return getInfo(imageInfo, rowBytes);
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
    if (bitmap->isImmutable()) {
        ALOGE("Attempting to modify an immutable Bitmap!");
    }
    return bitmap->notifyPixelsChanged();
}
