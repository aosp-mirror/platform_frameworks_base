/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef _ANDROID_VIEW_SURFACE_H
#define _ANDROID_VIEW_SURFACE_H

#include <android/native_window.h>
#include <system/graphics.h>
#include <ui/PublicFormat.h>

#include "jni.h"

namespace android {

class Surface;
class IGraphicBufferProducer;

/* Gets the underlying ANativeWindow for a Surface. */
extern sp<ANativeWindow> android_view_Surface_getNativeWindow(
        JNIEnv* env, jobject surfaceObj);

/* Returns true if the object is an instance of Surface. */
extern bool android_view_Surface_isInstanceOf(JNIEnv* env, jobject obj);

/* Gets the underlying Surface from a Surface Java object. */
extern sp<Surface> android_view_Surface_getSurface(JNIEnv* env, jobject surfaceObj);

/* Creates a Surface from an android::Surface. */
extern jobject android_view_Surface_createFromSurface(JNIEnv* env,
        const sp<Surface>& surface);

/* Creates a Surface from an IGraphicBufferProducer. */
extern jobject android_view_Surface_createFromIGraphicBufferProducer(JNIEnv* env,
        const sp<IGraphicBufferProducer>& bufferProducer);

/* Convert from android.graphics.ImageFormat/PixelFormat enums to graphics.h HAL
 * format */
extern int android_view_Surface_mapPublicFormatToHalFormat(PublicFormat f);

/* Convert from android.graphics.ImageFormat/PixelFormat enums to graphics.h HAL
 * dataspace */
extern android_dataspace android_view_Surface_mapPublicFormatToHalDataspace(
        PublicFormat f);

/* Convert from HAL format, dataspace pair to
 * android.graphics.ImageFormat/PixelFormat.
 * For unknown/unspecified pairs, returns PublicFormat::UNKNOWN */
extern PublicFormat android_view_Surface_mapHalFormatDataspaceToPublicFormat(
        int format, android_dataspace dataSpace);

} // namespace android

#endif // _ANDROID_VIEW_SURFACE_H
