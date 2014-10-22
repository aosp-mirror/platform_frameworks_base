/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include <android/bitmap.h>
#include <GraphicsJNI.h>

int AndroidBitmap_getInfo(JNIEnv* env, jobject jbitmap,
                          AndroidBitmapInfo* info) {
    if (NULL == env || NULL == jbitmap) {
        return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    }

    SkBitmap* bm = GraphicsJNI::getNativeBitmap(env, jbitmap);
    if (NULL == bm) {
        return ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
    }

    if (info) {
        info->width     = bm->width();
        info->height    = bm->height();
        info->stride    = bm->rowBytes();
        info->flags     = 0;

        switch (bm->colorType()) {
            case kN32_SkColorType:
                info->format = ANDROID_BITMAP_FORMAT_RGBA_8888;
                break;
            case kRGB_565_SkColorType:
                info->format = ANDROID_BITMAP_FORMAT_RGB_565;
                break;
            case kARGB_4444_SkColorType:
                info->format = ANDROID_BITMAP_FORMAT_RGBA_4444;
                break;
            case kAlpha_8_SkColorType:
                info->format = ANDROID_BITMAP_FORMAT_A_8;
                break;
            default:
                info->format = ANDROID_BITMAP_FORMAT_NONE;
                break;
        }
    }
    return ANDROID_BITMAP_RESULT_SUCCESS;
}

int AndroidBitmap_lockPixels(JNIEnv* env, jobject jbitmap, void** addrPtr) {
    if (NULL == env || NULL == jbitmap) {
        return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    }

    SkBitmap* bm = GraphicsJNI::getNativeBitmap(env, jbitmap);
    if (NULL == bm) {
        return ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
    }

    bm->lockPixels();
    void* addr = bm->getPixels();
    if (NULL == addr) {
        bm->unlockPixels();
        return ANDROID_BITMAP_RESULT_ALLOCATION_FAILED;
    }

    if (addrPtr) {
        *addrPtr = addr;
    }
    return ANDROID_BITMAP_RESULT_SUCCESS;
}

int AndroidBitmap_unlockPixels(JNIEnv* env, jobject jbitmap) {
    if (NULL == env || NULL == jbitmap) {
        return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    }

    SkBitmap* bm = GraphicsJNI::getNativeBitmap(env, jbitmap);
    if (NULL == bm) {
        return ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
    }

    // notifyPixelsChanged() needs be called to apply writes to GL-backed
    // bitmaps.  Note that this will slow down read-only accesses to the
    // bitmaps, but the NDK methods are primarily intended to be used for
    // writes.
    bm->notifyPixelsChanged();

    bm->unlockPixels();
    return ANDROID_BITMAP_RESULT_SUCCESS;
}

