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

#define LOG_TAG "OpenGLRenderer"

#include <utils/StopWatch.h>

#include "OpenGLDebugRenderer.h"

namespace android {
namespace uirenderer {

void OpenGLDebugRenderer::prepare() {
    mPrimitivesCount = 0;
    LOGD("========= Frame start =========");
    OpenGLRenderer::prepare();
}

void OpenGLDebugRenderer::finish() {
    LOGD("========= Frame end =========");
    LOGD("Primitives draw count = %d", mPrimitivesCount);
    OpenGLRenderer::finish();
}

void OpenGLDebugRenderer::composeLayer(sp<Snapshot> current, sp<Snapshot> previous) {
    mPrimitivesCount++;
    StopWatch w("composeLayer");
    return OpenGLRenderer::composeLayer(current, previous);
}

int OpenGLDebugRenderer::saveLayer(float left, float top, float right, float bottom,
        const SkPaint* p, int flags) {
    mPrimitivesCount++;
    StopWatch w("saveLayer");
    return OpenGLRenderer::saveLayer(left, top, right, bottom, p, flags);
}

void OpenGLDebugRenderer::drawBitmap(SkBitmap* bitmap, float left, float top,
        const SkPaint* paint) {
    mPrimitivesCount++;
    StopWatch w("drawBitmap");
    OpenGLRenderer::drawBitmap(bitmap, left, top, paint);
}

void OpenGLDebugRenderer::drawBitmap(SkBitmap* bitmap, const SkMatrix* matrix,
        const SkPaint* paint) {
    mPrimitivesCount++;
    StopWatch w("drawBitmapMatrix");
    OpenGLRenderer::drawBitmap(bitmap, matrix, paint);
}

void OpenGLDebugRenderer::drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop,
        float dstRight, float dstBottom, const SkPaint* paint) {
    mPrimitivesCount++;
    StopWatch w("drawBitmapRect");
    OpenGLRenderer::drawBitmap(bitmap, srcLeft, srcTop, srcRight, srcBottom,
            dstLeft, dstTop, dstRight, dstBottom, paint);
}

void OpenGLDebugRenderer::drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
        uint32_t width, uint32_t height, float left, float top, float right, float bottom,
        const SkPaint* paint) {
    mPrimitivesCount++;
    StopWatch w("drawPatch");
    OpenGLRenderer::drawPatch(bitmap, xDivs, yDivs, width, height,
            left, top, right, bottom, paint);
}

void OpenGLDebugRenderer::drawColor(int color, SkXfermode::Mode mode) {
    mPrimitivesCount++;
    StopWatch w("drawColor");
    OpenGLRenderer::drawColor(color, mode);
}

void OpenGLDebugRenderer::drawRect(float left, float top, float right, float bottom,
        const SkPaint* paint) {
    mPrimitivesCount++;
    StopWatch w("drawRect");
    OpenGLRenderer::drawRect(left, top, right, bottom, paint);
}

void OpenGLDebugRenderer::drawPath(SkPath* path, SkPaint* paint) {
    mPrimitivesCount++;
    StopWatch w("drawPath");
    OpenGLRenderer::drawPath(path, paint);
}

void OpenGLDebugRenderer::drawLines(float* points, int count, const SkPaint* paint) {
    mPrimitivesCount++;
    StopWatch w("drawLines");
    OpenGLRenderer::drawLines(points, count, paint);
}

void OpenGLDebugRenderer::drawText(const char* text, int bytesCount, int count, float x, float y,
        SkPaint* paint) {
    mPrimitivesCount++;
    StopWatch w("drawText");
    OpenGLRenderer::drawText(text, bytesCount, count, x, y, paint);
}

}; // namespace uirenderer
}; // namespace android
