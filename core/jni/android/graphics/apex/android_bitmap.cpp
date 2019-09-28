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

#include "android/graphics/bitmap.h"
#include "Bitmap.h"
#include "TypeCast.h"

#include <hwui/Bitmap.h>

using namespace android;

ABitmap* ABitmap_acquireBitmapFromJava(JNIEnv* env, jobject bitmapObj) {
    Bitmap& bitmap = android::bitmap::toBitmap(env, bitmapObj);
    bitmap.ref();
    return TypeCast::toABitmap(&bitmap);
}

void ABitmap_acquireRef(ABitmap* bitmap) {
    SkSafeRef(TypeCast::toBitmap(bitmap));
}

void ABitmap_releaseRef(ABitmap* bitmap) {
    SkSafeUnref(TypeCast::toBitmap(bitmap));
}

static AndroidBitmapFormat getFormat(Bitmap* bitmap) {
    switch (bitmap->colorType()) {
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

AndroidBitmapInfo ABitmap_getInfo(ABitmap* bitmapHandle) {
    Bitmap* bitmap = TypeCast::toBitmap(bitmapHandle);

    AndroidBitmapInfo info;
    info.width = bitmap->width();
    info.height = bitmap->height();
    info.stride = bitmap->rowBytes();
    info.format = getFormat(bitmap);
    return info;
}

void* ABitmap_getPixels(ABitmap* bitmapHandle) {
    Bitmap* bitmap = TypeCast::toBitmap(bitmapHandle);
    if (bitmap->isHardware()) {
        return nullptr;
    }
    return bitmap->pixels();
}
