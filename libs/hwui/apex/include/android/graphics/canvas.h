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

#include <android/graphics/bitmap.h>
#include <android/graphics/paint.h>
#include <android/native_window.h>
#include <android/rect.h>
#include <cutils/compiler.h>
#include <jni.h>

__BEGIN_DECLS

/**
 * Opaque handle for a native graphics canvas.
 */
typedef struct ACanvas ACanvas;

//  One of AHardwareBuffer_Format.
ANDROID_API bool ACanvas_isSupportedPixelFormat(int32_t bufferFormat);

/**
 * Returns a native handle to a Java android.graphics.Canvas
 *
 * @return ACanvas* that is only valid for the life of the jobject.
 */
ANDROID_API ACanvas* ACanvas_getNativeHandleFromJava(JNIEnv* env, jobject canvas);

/**
 * Creates a canvas that wraps the buffer
 *
 * @param buffer is a required param.  If no buffer is provided a nullptr will be returned.
 */
ANDROID_API ACanvas* ACanvas_createCanvas(const ANativeWindow_Buffer* buffer,
                              int32_t /*android_dataspace_t*/ dataspace);

ANDROID_API void ACanvas_destroyCanvas(ACanvas* canvas);

/**
 * Updates the canvas to render into the pixels in the provided buffer
 *
 * @param buffer The buffer that will provide the backing store for this canvas.  The buffer must
 *               remain valid until the this method is called again with either another active
 *               buffer or nullptr.  If nullptr is given the canvas will release the previous buffer
 *               and set an empty backing store.
 * @return A boolean value indicating whether or not the buffer was successfully set. If false the
 *         method will behave as if nullptr were passed as the input buffer and the previous buffer
 *         will still be released.
 */
ANDROID_API bool ACanvas_setBuffer(ACanvas* canvas, const ANativeWindow_Buffer* buffer,
                       int32_t /*android_dataspace_t*/ dataspace);

/**
 * Clips operations on the canvas to the intersection of the current clip and the provided clipRect.
 *
 * @param clipRect required
 */
ANDROID_API void ACanvas_clipRect(ACanvas* canvas, const ARect* clipRect, bool doAntiAlias = false);

/**
 * Clips operations on the canvas to the difference of the current clip and the provided clipRect.
 *
 * @param clipRect required
 */
ANDROID_API void ACanvas_clipOutRect(ACanvas* canvas, const ARect* clipRect, bool doAntiAlias = false);

/**
 *
 * @param rect required
 * @param paint required
 */
ANDROID_API void ACanvas_drawRect(ACanvas* canvas, const ARect* rect, const APaint* paint);

/**
 *
 * @param bitmap required
 * @param left
 * @param top
 * @param paint
 */
ANDROID_API void ACanvas_drawBitmap(ACanvas* canvas, const ABitmap* bitmap, float left, float top,
                        const APaint* paint);

__END_DECLS

#ifdef	__cplusplus
namespace android {
namespace graphics {
    class Canvas {
    public:
        Canvas(JNIEnv* env, jobject canvasObj) :
                mCanvas(ACanvas_getNativeHandleFromJava(env, canvasObj)),
                mOwnedPtr(false) {}
        Canvas(const ANativeWindow_Buffer& buffer, int32_t /*android_dataspace_t*/ dataspace) :
                mCanvas(ACanvas_createCanvas(&buffer, dataspace)),
                mOwnedPtr(true) {}
        ~Canvas() {
            if (mOwnedPtr) {
                ACanvas_destroyCanvas(mCanvas);
            }
        }

        bool setBuffer(const ANativeWindow_Buffer* buffer,
                       int32_t /*android_dataspace_t*/ dataspace) {
            return ACanvas_setBuffer(mCanvas, buffer, dataspace);
        }

        void clipRect(const ARect& clipRect, bool doAntiAlias = false) {
            ACanvas_clipRect(mCanvas, &clipRect, doAntiAlias);
        }

        void drawRect(const ARect& rect, const Paint& paint) {
            ACanvas_drawRect(mCanvas, &rect, &paint.get());
        }
        void drawBitmap(const Bitmap& bitmap, float left, float top, const Paint* paint) {
            const APaint* aPaint = (paint) ? &paint->get() : nullptr;
            ACanvas_drawBitmap(mCanvas, bitmap.get(), left, top, aPaint);
        }

    private:
        ACanvas* mCanvas;
        const bool mOwnedPtr;
    };
}; // namespace graphics
}; // namespace android
#endif // __cplusplus

#endif // ANDROID_GRAPHICS_CANVAS_H