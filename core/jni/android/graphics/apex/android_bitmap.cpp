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
