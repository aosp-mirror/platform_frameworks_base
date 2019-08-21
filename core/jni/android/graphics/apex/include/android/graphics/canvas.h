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
#ifndef ANDROID_GRAPHICS_CANVAS_H
#define ANDROID_GRAPHICS_CANVAS_H

#include <android/native_window.h>
#include <android/rect.h>
#include <jni.h>

__BEGIN_DECLS

/**
* Opaque handle for a native graphics canvas.
*/
typedef struct ACanvas ACanvas;

//  One of AHardwareBuffer_Format.
bool ACanvas_isSupportedPixelFormat(int32_t bufferFormat);

/**
 * Returns a native handle to a Java android.graphics.Canvas
 *
 * @param env
 * @param canvas
 * @return ACanvas* that is only valid for the life of the jobject.
 */
ACanvas* ACanvas_getNativeHandleFromJava(JNIEnv* env, jobject canvas);

/**
 * Updates the canvas to render into the pixels in the provided buffer
 *
 * @param canvas
 * @param buffer The buffer that will provide the backing store for this canvas.  The buffer must
 *               remain valid until the this method is called again with either another active
 *               buffer or nullptr.  If nullptr is given the canvas will release the previous buffer
 *               and set an empty backing store.
 * @param dataspace
 */
void ACanvas_setBuffer(ACanvas* canvas, const ANativeWindow_Buffer* buffer,
                       int32_t /*android_dataspace_t*/ dataspace);

/**
 * Clips operations on the canvas to the intersection of the current clip and the provided clipRect.
 */
void ACanvas_clipRect(ACanvas* canvas, const ARect& clipRect, bool doAntiAlias = false);

/**
 * Clips operations on the canvas to the difference of the current clip and the provided clipRect.
 */
void ACanvas_clipOutRect(ACanvas* canvas, const ARect& clipRect, bool doAntiAlias = false);

__END_DECLS
#endif // ANDROID_GRAPHICS_CANVAS_H