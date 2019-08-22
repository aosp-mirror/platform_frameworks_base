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

#include "android/graphics/canvas.h"

#include "GraphicsJNI.h"

#include <hwui/Canvas.h>
#include <utils/Color.h>

#include <SkBitmap.h>

using namespace android;

static inline Canvas* toCanvas(ACanvas* aCanvas) {
    return reinterpret_cast<Canvas*>(aCanvas);
}

static inline ACanvas* toACanvas(Canvas* canvas) {
    return reinterpret_cast<ACanvas*>(canvas);
}

bool ACanvas_isSupportedPixelFormat(int32_t bufferFormat) {
    ANativeWindow_Buffer buffer { 0, 0, 0, bufferFormat, nullptr, {0} };
    const SkColorType colorType = uirenderer::ANativeWindowToImageInfo(buffer, nullptr).colorType();
    return kUnknown_SkColorType != colorType;
}

ACanvas* ACanvas_getNativeHandleFromJava(JNIEnv* env, jobject canvasObj) {
    return toACanvas(GraphicsJNI::getNativeCanvas(env, canvasObj));
}

void ACanvas_setBuffer(ACanvas* canvas, const ANativeWindow_Buffer* buffer,
                       int32_t /*android_dataspace_t*/ dataspace) {
    SkBitmap bitmap;
    if (buffer != nullptr && buffer->width > 0 && buffer->height > 0) {
        sk_sp<SkColorSpace> cs(uirenderer::DataSpaceToColorSpace((android_dataspace)dataspace));
        SkImageInfo imageInfo = uirenderer::ANativeWindowToImageInfo(*buffer, cs);
        ssize_t rowBytes = buffer->stride * imageInfo.bytesPerPixel();
        bitmap.setInfo(imageInfo, rowBytes);
        bitmap.setPixels(buffer->bits);
    }

    toCanvas(canvas)->setBitmap(bitmap);
}

void ACanvas_clipRect(ACanvas* canvas, const ARect& clipRect, bool /*doAA*/) {
    //TODO update Canvas to take antialias param
    toCanvas(canvas)->clipRect(clipRect.left, clipRect.top, clipRect.right, clipRect.bottom,
                               SkClipOp::kIntersect);
}

void ACanvas_clipOutRect(ACanvas* canvas, const ARect& clipRect, bool /*doAA*/) {
    //TODO update Canvas to take antialias param
    toCanvas(canvas)->clipRect(clipRect.left, clipRect.top, clipRect.right, clipRect.bottom,
                               SkClipOp::kDifference);
}
