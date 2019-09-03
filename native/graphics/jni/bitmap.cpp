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
#include <android/graphics/bitmap.h>
#include <android/data_space.h>

int AndroidBitmap_getInfo(JNIEnv* env, jobject jbitmap,
                          AndroidBitmapInfo* info) {
    if (NULL == env || NULL == jbitmap) {
        return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    }

    if (info) {
        *info = ABitmap_getInfoFromJava(env, jbitmap);
    }
    return ANDROID_BITMAP_RESULT_SUCCESS;
}

int32_t AndroidBitmap_getDataSpace(JNIEnv* env, jobject jbitmap) {
    if (NULL == env || NULL == jbitmap) {
        return ADATASPACE_UNKNOWN; // Or return a real error?
    }

    android::graphics::Bitmap bitmap(env, jbitmap);
    return bitmap.getDataSpace();
}

int AndroidBitmap_lockPixels(JNIEnv* env, jobject jbitmap, void** addrPtr) {
    if (NULL == env || NULL == jbitmap) {
        return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    }

    android::graphics::Bitmap bitmap(env, jbitmap);
    void* addr = bitmap.isValid() ? bitmap.getPixels() : nullptr;

    if (!addr) {
        return ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
    }

    ABitmap_acquireRef(bitmap.get());

    if (addrPtr) {
        *addrPtr = addr;
    }
    return ANDROID_BITMAP_RESULT_SUCCESS;
}

int AndroidBitmap_unlockPixels(JNIEnv* env, jobject jbitmap) {
    if (NULL == env || NULL == jbitmap) {
        return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    }

    android::graphics::Bitmap bitmap(env, jbitmap);

    if (!bitmap.isValid()) {
        return ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
    }

    bitmap.notifyPixelsChanged();
    ABitmap_releaseRef(bitmap.get());
    return ANDROID_BITMAP_RESULT_SUCCESS;
}
