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
#include <android/graphics/Bitmap.h>

int AndroidBitmap_getInfo(JNIEnv* env, jobject jbitmap,
                          AndroidBitmapInfo* info) {
    if (NULL == env || NULL == jbitmap) {
        return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    }

    if (info) {
        android::bitmap::imageInfo(env, jbitmap, info);
    }
    return ANDROID_BITMAP_RESULT_SUCCESS;
}

int AndroidBitmap_lockPixels(JNIEnv* env, jobject jbitmap, void** addrPtr) {
    if (NULL == env || NULL == jbitmap) {
        return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
    }

    void* addr = android::bitmap::lockPixels(env, jbitmap);
    if (!addr) {
        return ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
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

    bool unlocked = android::bitmap::unlockPixels(env, jbitmap);
    if (!unlocked) {
        return ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
    }
    return ANDROID_BITMAP_RESULT_SUCCESS;
}
