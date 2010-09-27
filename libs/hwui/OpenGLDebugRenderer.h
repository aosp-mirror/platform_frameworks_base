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

#ifndef ANDROID_UI_OPENGL_DEBUG_RENDERER_H
#define ANDROID_UI_OPENGL_DEBUG_RENDERER_H

#include "OpenGLRenderer.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Renderer
///////////////////////////////////////////////////////////////////////////////

class OpenGLDebugRenderer: public OpenGLRenderer {
public:
    OpenGLDebugRenderer(): mPrimitivesCount(0) {
    }

    ~OpenGLDebugRenderer() {
    }

    void prepare();
    void finish();

    int saveLayer(float left, float top, float right, float bottom,
            const SkPaint* p, int flags);

    void drawBitmap(SkBitmap* bitmap, float left, float top, const SkPaint* paint);
    void drawBitmap(SkBitmap* bitmap, const SkMatrix* matrix, const SkPaint* paint);
    void drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint);
    void drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
            uint32_t width, uint32_t height, float left, float top, float right, float bottom,
            const SkPaint* paint);
    void drawColor(int color, SkXfermode::Mode mode);
    void drawRect(float left, float top, float right, float bottom, const SkPaint* paint);
    void drawPath(SkPath* path, SkPaint* paint);
    void drawLines(float* points, int count, const SkPaint* paint);
    void drawText(const char* text, int bytesCount, int count, float x, float y,
            SkPaint* paint);

protected:
    void composeLayer(sp<Snapshot> current, sp<Snapshot> previous);

private:
    uint32_t mPrimitivesCount;

}; // class OpenGLDebugRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_OPENGL_DEBUG_RENDERER_H
