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

#include "TypeCast.h"
#include "GraphicsJNI.h"

#include <hwui/Canvas.h>
#include <utils/Color.h>

#include <SkBitmap.h>
#include <SkColorSpace.h>
#include <SkSurface.h>
#include <SkRefCnt.h>

using namespace android;

/*
 * Converts a buffer and dataspace into an SkBitmap only if the resulting bitmap can be treated as a
 * rendering destination for a Canvas.  If the buffer is null or the format is one that we cannot
 * render into with a Canvas then false is returned and the outBitmap param is unmodified.
 */
static bool convert(const ANativeWindow_Buffer* buffer,
                    int32_t /*android_dataspace_t*/ dataspace,
                    SkBitmap* outBitmap) {
    if (buffer == nullptr) {
        return false;
    }

    sk_sp<SkColorSpace> cs(uirenderer::DataSpaceToColorSpace((android_dataspace)dataspace));
    SkImageInfo imageInfo = uirenderer::ANativeWindowToImageInfo(*buffer, cs);
    size_t rowBytes = buffer->stride * imageInfo.bytesPerPixel();

    // If SkSurfaces::WrapPixels fails then we should as well as we will not be able to
    // draw into the canvas.
    sk_sp<SkSurface> surface = SkSurfaces::WrapPixels(imageInfo, buffer->bits, rowBytes);
    if (surface.get() != nullptr) {
        if (outBitmap) {
            outBitmap->setInfo(imageInfo, rowBytes);
            outBitmap->setPixels(buffer->bits);
        }
        return true;
    }
    return false;
}

bool ACanvas_isSupportedPixelFormat(int32_t bufferFormat) {
    char pixels[8];
    ANativeWindow_Buffer buffer { 1, 1, 1, bufferFormat, pixels, {0} };
    return convert(&buffer, HAL_DATASPACE_UNKNOWN, nullptr);
}

ACanvas* ACanvas_getNativeHandleFromJava(JNIEnv* env, jobject canvasObj) {
    return TypeCast::toACanvas(GraphicsJNI::getNativeCanvas(env, canvasObj));
}

ACanvas* ACanvas_createCanvas(const ANativeWindow_Buffer* buffer,
                              int32_t /*android_dataspace_t*/ dataspace) {
    SkBitmap bitmap;
    bool isValidBuffer = convert(buffer, dataspace, &bitmap);
    return isValidBuffer ? TypeCast::toACanvas(Canvas::create_canvas(bitmap)) : nullptr;
}

void ACanvas_destroyCanvas(ACanvas* canvas) {
    delete TypeCast::toCanvas(canvas);
}

bool ACanvas_setBuffer(ACanvas* canvas, const ANativeWindow_Buffer* buffer,
                       int32_t /*android_dataspace_t*/ dataspace) {
    SkBitmap bitmap;
    bool isValidBuffer = (buffer == nullptr) ? false : convert(buffer, dataspace, &bitmap);
    TypeCast::toCanvas(canvas)->setBitmap(bitmap);
    return isValidBuffer;
}

void ACanvas_clipRect(ACanvas* canvas, const ARect* clipRect, bool /*doAA*/) {
    //TODO update Canvas to take antialias param
    TypeCast::toCanvas(canvas)->clipRect(clipRect->left, clipRect->top, clipRect->right,
                                         clipRect->bottom, SkClipOp::kIntersect);
}

void ACanvas_clipOutRect(ACanvas* canvas, const ARect* clipRect, bool /*doAA*/) {
    //TODO update Canvas to take antialias param
    TypeCast::toCanvas(canvas)->clipRect(clipRect->left, clipRect->top, clipRect->right,
                                         clipRect->bottom, SkClipOp::kDifference);
}

void ACanvas_drawRect(ACanvas* canvas, const ARect* rect, const APaint* paint) {
    TypeCast::toCanvas(canvas)->drawRect(rect->left, rect->top, rect->right, rect->bottom,
                                         TypeCast::toPaintRef(paint));
}

void ACanvas_drawBitmap(ACanvas* canvas, const ABitmap* bitmap, float left, float top,
                        const APaint* paint) {
    TypeCast::toCanvas(canvas)->drawBitmap(TypeCast::toBitmapRef(bitmap), left, top,
                                           TypeCast::toPaint(paint));
}
