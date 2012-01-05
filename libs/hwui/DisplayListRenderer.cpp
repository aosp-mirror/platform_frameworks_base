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


#include "DisplayListLogBuffer.h"
#include "DisplayListRenderer.h"
#include <utils/String8.h>
#include "Caches.h"

namespace android {
namespace uirenderer {


///////////////////////////////////////////////////////////////////////////////
// Display list
///////////////////////////////////////////////////////////////////////////////

const char* DisplayList::OP_NAMES[] = {
    "Save",
    "Restore",
    "RestoreToCount",
    "SaveLayer",
    "SaveLayerAlpha",
    "Translate",
    "Rotate",
    "Scale",
    "Skew",
    "SetMatrix",
    "ConcatMatrix",
    "ClipRect",
    "DrawDisplayList",
    "DrawLayer",
    "DrawBitmap",
    "DrawBitmapMatrix",
    "DrawBitmapRect",
    "DrawBitmapMesh",
    "DrawPatch",
    "DrawColor",
    "DrawRect",
    "DrawRoundRect",
    "DrawCircle",
    "DrawOval",
    "DrawArc",
    "DrawPath",
    "DrawLines",
    "DrawPoints",
    "DrawText",
    "ResetShader",
    "SetupShader",
    "ResetColorFilter",
    "SetupColorFilter",
    "ResetShadow",
    "SetupShadow",
    "DrawGLFunction"
};

void DisplayList::outputLogBuffer(int fd) {
    DisplayListLogBuffer& logBuffer = DisplayListLogBuffer::getInstance();
    if (logBuffer.isEmpty()) {
        return;
    }

    FILE *file = fdopen(fd, "a");

    fprintf(file, "\nRecent DisplayList operations\n");
    logBuffer.outputCommands(file, OP_NAMES);

    String8 cachesLog;
    Caches::getInstance().dumpMemoryUsage(cachesLog);
    fprintf(file, "\nCaches:\n%s", cachesLog.string());
    fprintf(file, "\n");

    fflush(file);
}

DisplayList::DisplayList(const DisplayListRenderer& recorder) {
    initFromDisplayListRenderer(recorder);
}

DisplayList::~DisplayList() {
    clearResources();
}

void DisplayList::clearResources() {
    sk_free((void*) mReader.base());

    Caches& caches = Caches::getInstance();

    for (size_t i = 0; i < mBitmapResources.size(); i++) {
        caches.resourceCache.decrementRefcount(mBitmapResources.itemAt(i));
    }
    mBitmapResources.clear();

    for (size_t i = 0; i < mFilterResources.size(); i++) {
        caches.resourceCache.decrementRefcount(mFilterResources.itemAt(i));
    }
    mFilterResources.clear();

    for (size_t i = 0; i < mShaders.size(); i++) {
        caches.resourceCache.decrementRefcount(mShaders.itemAt(i));
        caches.resourceCache.destructor(mShaders.itemAt(i));
    }
    mShaders.clear();

    for (size_t i = 0; i < mPaints.size(); i++) {
        delete mPaints.itemAt(i);
    }
    mPaints.clear();

    for (size_t i = 0; i < mPaths.size(); i++) {
        SkPath* path = mPaths.itemAt(i);
        caches.pathCache.remove(path);
        delete path;
    }
    mPaths.clear();

    for (size_t i = 0; i < mMatrices.size(); i++) {
        delete mMatrices.itemAt(i);
    }
    mMatrices.clear();
}

void DisplayList::initFromDisplayListRenderer(const DisplayListRenderer& recorder, bool reusing) {
    const SkWriter32& writer = recorder.writeStream();
    init();

    if (writer.size() == 0) {
        return;
    }

    if (reusing) {
        // re-using display list - clear out previous allocations
        clearResources();
    }

    mSize = writer.size();
    void* buffer = sk_malloc_throw(mSize);
    writer.flatten(buffer);
    mReader.setMemory(buffer, mSize);

    Caches& caches = Caches::getInstance();

    const Vector<SkBitmap*> &bitmapResources = recorder.getBitmapResources();
    for (size_t i = 0; i < bitmapResources.size(); i++) {
        SkBitmap* resource = bitmapResources.itemAt(i);
        mBitmapResources.add(resource);
        caches.resourceCache.incrementRefcount(resource);
    }

    const Vector<SkiaColorFilter*> &filterResources = recorder.getFilterResources();
    for (size_t i = 0; i < filterResources.size(); i++) {
        SkiaColorFilter* resource = filterResources.itemAt(i);
        mFilterResources.add(resource);
        caches.resourceCache.incrementRefcount(resource);
    }

    const Vector<SkiaShader*> &shaders = recorder.getShaders();
    for (size_t i = 0; i < shaders.size(); i++) {
        SkiaShader* resource = shaders.itemAt(i);
        mShaders.add(resource);
        caches.resourceCache.incrementRefcount(resource);
    }

    const Vector<SkPaint*> &paints = recorder.getPaints();
    for (size_t i = 0; i < paints.size(); i++) {
        mPaints.add(paints.itemAt(i));
    }

    const Vector<SkPath*> &paths = recorder.getPaths();
    for (size_t i = 0; i < paths.size(); i++) {
        mPaths.add(paths.itemAt(i));
    }

    const Vector<SkMatrix*> &matrices = recorder.getMatrices();
    for (size_t i = 0; i < matrices.size(); i++) {
        mMatrices.add(matrices.itemAt(i));
    }
}

void DisplayList::init() {
    mSize = 0;
    mIsRenderable = true;
}

size_t DisplayList::getSize() {
    return mSize;
}

/**
 * This function is a simplified version of replay(), where we simply retrieve and log the
 * display list. This function should remain in sync with the replay() function.
 */
void DisplayList::output(OpenGLRenderer& renderer, uint32_t level) {
    TextContainer text;

    uint32_t count = (level + 1) * 2;
    char indent[count + 1];
    for (uint32_t i = 0; i < count; i++) {
        indent[i] = ' ';
    }
    indent[count] = '\0';
    LOGD("%sStart display list (%p)", (char*) indent + 2, this);

    int saveCount = renderer.getSaveCount() - 1;

    mReader.rewind();

    while (!mReader.eof()) {
        int op = mReader.readInt();

        switch (op) {
            case DrawGLFunction: {
                Functor *functor = (Functor *) getInt();
                LOGD("%s%s %p", (char*) indent, OP_NAMES[op], functor);
            }
            break;
            case Save: {
                int rendererNum = getInt();
                LOGD("%s%s %d", (char*) indent, OP_NAMES[op], rendererNum);
            }
            break;
            case Restore: {
                LOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case RestoreToCount: {
                int restoreCount = saveCount + getInt();
                LOGD("%s%s %d", (char*) indent, OP_NAMES[op], restoreCount);
            }
            break;
            case SaveLayer: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                SkPaint* paint = getPaint();
                int flags = getInt();
                LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %p, 0x%x", (char*) indent,
                    OP_NAMES[op], f1, f2, f3, f4, paint, flags);
            }
            break;
            case SaveLayerAlpha: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                int alpha = getInt();
                int flags = getInt();
                LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %d, 0x%x", (char*) indent,
                    OP_NAMES[op], f1, f2, f3, f4, alpha, flags);
            }
            break;
            case Translate: {
                float f1 = getFloat();
                float f2 = getFloat();
                LOGD("%s%s %.2f, %.2f", (char*) indent, OP_NAMES[op], f1, f2);
            }
            break;
            case Rotate: {
                float rotation = getFloat();
                LOGD("%s%s %.2f", (char*) indent, OP_NAMES[op], rotation);
            }
            break;
            case Scale: {
                float sx = getFloat();
                float sy = getFloat();
                LOGD("%s%s %.2f, %.2f", (char*) indent, OP_NAMES[op], sx, sy);
            }
            break;
            case Skew: {
                float sx = getFloat();
                float sy = getFloat();
                LOGD("%s%s %.2f, %.2f", (char*) indent, OP_NAMES[op], sx, sy);
            }
            break;
            case SetMatrix: {
                SkMatrix* matrix = getMatrix();
                LOGD("%s%s %p", (char*) indent, OP_NAMES[op], matrix);
            }
            break;
            case ConcatMatrix: {
                SkMatrix* matrix = getMatrix();
                LOGD("%s%s %p", (char*) indent, OP_NAMES[op], matrix);
            }
            break;
            case ClipRect: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                int regionOp = getInt();
                LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %d", (char*) indent, OP_NAMES[op],
                    f1, f2, f3, f4, regionOp);
            }
            break;
            case DrawDisplayList: {
                DisplayList* displayList = getDisplayList();
                uint32_t width = getUInt();
                uint32_t height = getUInt();
                LOGD("%s%s %p, %dx%d, %d", (char*) indent, OP_NAMES[op],
                    displayList, width, height, level + 1);
                renderer.outputDisplayList(displayList, level + 1);
            }
            break;
            case DrawLayer: {
                Layer* layer = (Layer*) getInt();
                float x = getFloat();
                float y = getFloat();
                SkPaint* paint = getPaint();
                LOGD("%s%s %p, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
                    layer, x, y, paint);
            }
            break;
            case DrawBitmap: {
                SkBitmap* bitmap = getBitmap();
                float x = getFloat();
                float y = getFloat();
                SkPaint* paint = getPaint();
                LOGD("%s%s %p, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
                    bitmap, x, y, paint);
            }
            break;
            case DrawBitmapMatrix: {
                SkBitmap* bitmap = getBitmap();
                SkMatrix* matrix = getMatrix();
                SkPaint* paint = getPaint();
                LOGD("%s%s %p, %p, %p", (char*) indent, OP_NAMES[op],
                    bitmap, matrix, paint);
            }
            break;
            case DrawBitmapRect: {
                SkBitmap* bitmap = getBitmap();
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                float f5 = getFloat();
                float f6 = getFloat();
                float f7 = getFloat();
                float f8 = getFloat();
                SkPaint* paint = getPaint();
                LOGD("%s%s %p, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %p",
                    (char*) indent, OP_NAMES[op], bitmap, f1, f2, f3, f4, f5, f6, f7, f8, paint);
            }
            break;
            case DrawBitmapMesh: {
                int verticesCount = 0;
                uint32_t colorsCount = 0;
                SkBitmap* bitmap = getBitmap();
                uint32_t meshWidth = getInt();
                uint32_t meshHeight = getInt();
                float* vertices = getFloats(verticesCount);
                bool hasColors = getInt();
                int* colors = hasColors ? getInts(colorsCount) : NULL;
                SkPaint* paint = getPaint();
                LOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case DrawPatch: {
                int32_t* xDivs = NULL;
                int32_t* yDivs = NULL;
                uint32_t* colors = NULL;
                uint32_t xDivsCount = 0;
                uint32_t yDivsCount = 0;
                int8_t numColors = 0;
                SkBitmap* bitmap = getBitmap();
                xDivs = getInts(xDivsCount);
                yDivs = getInts(yDivsCount);
                colors = getUInts(numColors);
                float left = getFloat();
                float top = getFloat();
                float right = getFloat();
                float bottom = getFloat();
                SkPaint* paint = getPaint();
                LOGD("%s%s %.2f, %.2f, %.2f, %.2f", (char*) indent, OP_NAMES[op],
                        left, top, right, bottom);
            }
            break;
            case DrawColor: {
                int color = getInt();
                int xferMode = getInt();
                LOGD("%s%s 0x%x %d", (char*) indent, OP_NAMES[op], color, xferMode);
            }
            break;
            case DrawRect: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                SkPaint* paint = getPaint();
                LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
                    f1, f2, f3, f4, paint);
            }
            break;
            case DrawRoundRect: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                float f5 = getFloat();
                float f6 = getFloat();
                SkPaint* paint = getPaint();
                LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %p",
                    (char*) indent, OP_NAMES[op], f1, f2, f3, f4, f5, f6, paint);
            }
            break;
            case DrawCircle: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                SkPaint* paint = getPaint();
                LOGD("%s%s %.2f, %.2f, %.2f, %p",
                    (char*) indent, OP_NAMES[op], f1, f2, f3, paint);
            }
            break;
            case DrawOval: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                SkPaint* paint = getPaint();
                LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %p",
                    (char*) indent, OP_NAMES[op], f1, f2, f3, f4, paint);
            }
            break;
            case DrawArc: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                float f5 = getFloat();
                float f6 = getFloat();
                int i1 = getInt();
                SkPaint* paint = getPaint();
                LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %d, %p",
                    (char*) indent, OP_NAMES[op], f1, f2, f3, f4, f5, f6, i1, paint);
            }
            break;
            case DrawPath: {
                SkPath* path = getPath();
                SkPaint* paint = getPaint();
                LOGD("%s%s %p, %p", (char*) indent, OP_NAMES[op], path, paint);
            }
            break;
            case DrawLines: {
                int count = 0;
                float* points = getFloats(count);
                SkPaint* paint = getPaint();
                LOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case DrawPoints: {
                int count = 0;
                float* points = getFloats(count);
                SkPaint* paint = getPaint();
                LOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case DrawText: {
                getText(&text);
                int count = getInt();
                float x = getFloat();
                float y = getFloat();
                SkPaint* paint = getPaint();
                float length = getFloat();
                LOGD("%s%s %s, %d, %d, %.2f, %.2f, %p, %.2f", (char*) indent, OP_NAMES[op],
                    text.text(), text.length(), count, x, y, paint, length);
            }
            break;
            case ResetShader: {
                LOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case SetupShader: {
                SkiaShader* shader = getShader();
                LOGD("%s%s %p", (char*) indent, OP_NAMES[op], shader);
            }
            break;
            case ResetColorFilter: {
                LOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case SetupColorFilter: {
                SkiaColorFilter *colorFilter = getColorFilter();
                LOGD("%s%s %p", (char*) indent, OP_NAMES[op], colorFilter);
            }
            break;
            case ResetShadow: {
                LOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case SetupShadow: {
                float radius = getFloat();
                float dx = getFloat();
                float dy = getFloat();
                int color = getInt();
                LOGD("%s%s %.2f, %.2f, %.2f, 0x%x", (char*) indent, OP_NAMES[op],
                    radius, dx, dy, color);
            }
            break;
            default:
                LOGD("Display List error: op not handled: %s%s",
                    (char*) indent, OP_NAMES[op]);
                break;
        }
    }

    LOGD("%sDone", (char*) indent + 2);
}

/**
 * Changes to replay(), specifically those involving opcode or parameter changes, should be mimicked
 * in the output() function, since that function processes the same list of opcodes for the
 * purposes of logging display list info for a given view.
 */
bool DisplayList::replay(OpenGLRenderer& renderer, Rect& dirty, uint32_t level) {
    bool needsInvalidate = false;
    TextContainer text;
    mReader.rewind();

#if DEBUG_DISPLAY_LIST
    uint32_t count = (level + 1) * 2;
    char indent[count + 1];
    for (uint32_t i = 0; i < count; i++) {
        indent[i] = ' ';
    }
    indent[count] = '\0';
    DISPLAY_LIST_LOGD("%sStart display list (%p)", (char*) indent + 2, this);
#endif

    DisplayListLogBuffer& logBuffer = DisplayListLogBuffer::getInstance();
    int saveCount = renderer.getSaveCount() - 1;
    while (!mReader.eof()) {
        int op = mReader.readInt();
        logBuffer.writeCommand(level, op);

        switch (op) {
            case DrawGLFunction: {
                Functor *functor = (Functor *) getInt();
                DISPLAY_LIST_LOGD("%s%s %p", (char*) indent, OP_NAMES[op], functor);
                needsInvalidate |= renderer.callDrawGLFunction(functor, dirty);
            }
            break;
            case Save: {
                int rendererNum = getInt();
                DISPLAY_LIST_LOGD("%s%s %d", (char*) indent, OP_NAMES[op], rendererNum);
                renderer.save(rendererNum);
            }
            break;
            case Restore: {
                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                renderer.restore();
            }
            break;
            case RestoreToCount: {
                int restoreCount = saveCount + getInt();
                DISPLAY_LIST_LOGD("%s%s %d", (char*) indent, OP_NAMES[op], restoreCount);
                renderer.restoreToCount(restoreCount);
            }
            break;
            case SaveLayer: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                SkPaint* paint = getPaint();
                int flags = getInt();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %p, 0x%x", (char*) indent,
                    OP_NAMES[op], f1, f2, f3, f4, paint, flags);
                renderer.saveLayer(f1, f2, f3, f4, paint, flags);
            }
            break;
            case SaveLayerAlpha: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                int alpha = getInt();
                int flags = getInt();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %d, 0x%x", (char*) indent,
                    OP_NAMES[op], f1, f2, f3, f4, alpha, flags);
                renderer.saveLayerAlpha(f1, f2, f3, f4, alpha, flags);
            }
            break;
            case Translate: {
                float f1 = getFloat();
                float f2 = getFloat();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f", (char*) indent, OP_NAMES[op], f1, f2);
                renderer.translate(f1, f2);
            }
            break;
            case Rotate: {
                float rotation = getFloat();
                DISPLAY_LIST_LOGD("%s%s %.2f", (char*) indent, OP_NAMES[op], rotation);
                renderer.rotate(rotation);
            }
            break;
            case Scale: {
                float sx = getFloat();
                float sy = getFloat();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f", (char*) indent, OP_NAMES[op], sx, sy);
                renderer.scale(sx, sy);
            }
            break;
            case Skew: {
                float sx = getFloat();
                float sy = getFloat();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f", (char*) indent, OP_NAMES[op], sx, sy);
                renderer.skew(sx, sy);
            }
            break;
            case SetMatrix: {
                SkMatrix* matrix = getMatrix();
                DISPLAY_LIST_LOGD("%s%s %p", (char*) indent, OP_NAMES[op], matrix);
                renderer.setMatrix(matrix);
            }
            break;
            case ConcatMatrix: {
                SkMatrix* matrix = getMatrix();
                DISPLAY_LIST_LOGD("%s%s %p", (char*) indent, OP_NAMES[op], matrix);
                renderer.concatMatrix(matrix);
            }
            break;
            case ClipRect: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                int regionOp = getInt();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %d", (char*) indent, OP_NAMES[op],
                    f1, f2, f3, f4, regionOp);
                renderer.clipRect(f1, f2, f3, f4, (SkRegion::Op) regionOp);
            }
            break;
            case DrawDisplayList: {
                DisplayList* displayList = getDisplayList();
                uint32_t width = getUInt();
                uint32_t height = getUInt();
                DISPLAY_LIST_LOGD("%s%s %p, %dx%d, %d", (char*) indent, OP_NAMES[op],
                    displayList, width, height, level + 1);
                needsInvalidate |= renderer.drawDisplayList(displayList, width, height,
                        dirty, level + 1);
            }
            break;
            case DrawLayer: {
                Layer* layer = (Layer*) getInt();
                float x = getFloat();
                float y = getFloat();
                SkPaint* paint = getPaint();
                DISPLAY_LIST_LOGD("%s%s %p, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
                    layer, x, y, paint);
                renderer.drawLayer(layer, x, y, paint);
            }
            break;
            case DrawBitmap: {
                SkBitmap* bitmap = getBitmap();
                float x = getFloat();
                float y = getFloat();
                SkPaint* paint = getPaint();
                DISPLAY_LIST_LOGD("%s%s %p, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
                    bitmap, x, y, paint);
                renderer.drawBitmap(bitmap, x, y, paint);
            }
            break;
            case DrawBitmapMatrix: {
                SkBitmap* bitmap = getBitmap();
                SkMatrix* matrix = getMatrix();
                SkPaint* paint = getPaint();
                DISPLAY_LIST_LOGD("%s%s %p, %p, %p", (char*) indent, OP_NAMES[op],
                    bitmap, matrix, paint);
                renderer.drawBitmap(bitmap, matrix, paint);
            }
            break;
            case DrawBitmapRect: {
                SkBitmap* bitmap = getBitmap();
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                float f5 = getFloat();
                float f6 = getFloat();
                float f7 = getFloat();
                float f8 = getFloat();
                SkPaint* paint = getPaint();
                DISPLAY_LIST_LOGD("%s%s %p, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %p",
                    (char*) indent, OP_NAMES[op], bitmap, f1, f2, f3, f4, f5, f6, f7, f8, paint);
                renderer.drawBitmap(bitmap, f1, f2, f3, f4, f5, f6, f7, f8, paint);
            }
            break;
            case DrawBitmapMesh: {
                int verticesCount = 0;
                uint32_t colorsCount = 0;

                SkBitmap* bitmap = getBitmap();
                uint32_t meshWidth = getInt();
                uint32_t meshHeight = getInt();
                float* vertices = getFloats(verticesCount);
                bool hasColors = getInt();
                int* colors = hasColors ? getInts(colorsCount) : NULL;
                SkPaint* paint = getPaint();

                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                renderer.drawBitmapMesh(bitmap, meshWidth, meshHeight, vertices, colors, paint);
            }
            break;
            case DrawPatch: {
                int32_t* xDivs = NULL;
                int32_t* yDivs = NULL;
                uint32_t* colors = NULL;
                uint32_t xDivsCount = 0;
                uint32_t yDivsCount = 0;
                int8_t numColors = 0;

                SkBitmap* bitmap = getBitmap();

                xDivs = getInts(xDivsCount);
                yDivs = getInts(yDivsCount);
                colors = getUInts(numColors);

                float left = getFloat();
                float top = getFloat();
                float right = getFloat();
                float bottom = getFloat();
                SkPaint* paint = getPaint();

                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                renderer.drawPatch(bitmap, xDivs, yDivs, colors, xDivsCount, yDivsCount,
                        numColors, left, top, right, bottom, paint);
            }
            break;
            case DrawColor: {
                int color = getInt();
                int xferMode = getInt();
                DISPLAY_LIST_LOGD("%s%s 0x%x %d", (char*) indent, OP_NAMES[op], color, xferMode);
                renderer.drawColor(color, (SkXfermode::Mode) xferMode);
            }
            break;
            case DrawRect: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                SkPaint* paint = getPaint();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
                    f1, f2, f3, f4, paint);
                renderer.drawRect(f1, f2, f3, f4, paint);
            }
            break;
            case DrawRoundRect: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                float f5 = getFloat();
                float f6 = getFloat();
                SkPaint* paint = getPaint();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %p",
                    (char*) indent, OP_NAMES[op], f1, f2, f3, f4, f5, f6, paint);
                renderer.drawRoundRect(f1, f2, f3, f4, f5, f6, paint);
            }
            break;
            case DrawCircle: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                SkPaint* paint = getPaint();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %p",
                    (char*) indent, OP_NAMES[op], f1, f2, f3, paint);
                renderer.drawCircle(f1, f2, f3, paint);
            }
            break;
            case DrawOval: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                SkPaint* paint = getPaint();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %p",
                    (char*) indent, OP_NAMES[op], f1, f2, f3, f4, paint);
                renderer.drawOval(f1, f2, f3, f4, paint);
            }
            break;
            case DrawArc: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                float f5 = getFloat();
                float f6 = getFloat();
                int i1 = getInt();
                SkPaint* paint = getPaint();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %d, %p",
                    (char*) indent, OP_NAMES[op], f1, f2, f3, f4, f5, f6, i1, paint);
                renderer.drawArc(f1, f2, f3, f4, f5, f6, i1 == 1, paint);
            }
            break;
            case DrawPath: {
                SkPath* path = getPath();
                SkPaint* paint = getPaint();
                DISPLAY_LIST_LOGD("%s%s %p, %p", (char*) indent, OP_NAMES[op], path, paint);
                renderer.drawPath(path, paint);
            }
            break;
            case DrawLines: {
                int count = 0;
                float* points = getFloats(count);
                SkPaint* paint = getPaint();
                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                renderer.drawLines(points, count, paint);
            }
            break;
            case DrawPoints: {
                int count = 0;
                float* points = getFloats(count);
                SkPaint* paint = getPaint();
                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                renderer.drawPoints(points, count, paint);
            }
            break;
            case DrawText: {
                getText(&text);
                int count = getInt();
                float x = getFloat();
                float y = getFloat();
                SkPaint* paint = getPaint();
                float length = getFloat();
                DISPLAY_LIST_LOGD("%s%s %s, %d, %d, %.2f, %.2f, %p, %.2f", (char*) indent,
                        OP_NAMES[op], text.text(), text.length(), count, x, y, paint, length);
                renderer.drawText(text.text(), text.length(), count, x, y, paint, length);
            }
            break;
            case ResetShader: {
                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                renderer.resetShader();
            }
            break;
            case SetupShader: {
                SkiaShader* shader = getShader();
                DISPLAY_LIST_LOGD("%s%s %p", (char*) indent, OP_NAMES[op], shader);
                renderer.setupShader(shader);
            }
            break;
            case ResetColorFilter: {
                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                renderer.resetColorFilter();
            }
            break;
            case SetupColorFilter: {
                SkiaColorFilter *colorFilter = getColorFilter();
                DISPLAY_LIST_LOGD("%s%s %p", (char*) indent, OP_NAMES[op], colorFilter);
                renderer.setupColorFilter(colorFilter);
            }
            break;
            case ResetShadow: {
                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                renderer.resetShadow();
            }
            break;
            case SetupShadow: {
                float radius = getFloat();
                float dx = getFloat();
                float dy = getFloat();
                int color = getInt();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, 0x%x", (char*) indent, OP_NAMES[op],
                    radius, dx, dy, color);
                renderer.setupShadow(radius, dx, dy, color);
            }
            break;
            default:
                DISPLAY_LIST_LOGD("Display List error: op not handled: %s%s",
                    (char*) indent, OP_NAMES[op]);
                break;
        }
    }

    DISPLAY_LIST_LOGD("%sDone, returning %d", (char*) indent + 2, needsInvalidate);
    return needsInvalidate;
}

///////////////////////////////////////////////////////////////////////////////
// Base structure
///////////////////////////////////////////////////////////////////////////////

DisplayListRenderer::DisplayListRenderer(): mWriter(MIN_WRITER_SIZE), mHasDrawOps(false) {
}

DisplayListRenderer::~DisplayListRenderer() {
    reset();
}

void DisplayListRenderer::reset() {
    mWriter.reset();

    Caches& caches = Caches::getInstance();
    for (size_t i = 0; i < mBitmapResources.size(); i++) {
        caches.resourceCache.decrementRefcount(mBitmapResources.itemAt(i));
    }
    mBitmapResources.clear();

    for (size_t i = 0; i < mFilterResources.size(); i++) {
        caches.resourceCache.decrementRefcount(mFilterResources.itemAt(i));
    }
    mFilterResources.clear();

    for (size_t i = 0; i < mShaders.size(); i++) {
        caches.resourceCache.decrementRefcount(mShaders.itemAt(i));
    }
    mShaders.clear();
    mShaderMap.clear();

    mPaints.clear();
    mPaintMap.clear();

    mPaths.clear();
    mPathMap.clear();

    mMatrices.clear();

    mHasDrawOps = false;
}

///////////////////////////////////////////////////////////////////////////////
// Operations
///////////////////////////////////////////////////////////////////////////////

DisplayList* DisplayListRenderer::getDisplayList(DisplayList* displayList) {
    if (!displayList) {
        displayList = new DisplayList(*this);
    } else {
        displayList->initFromDisplayListRenderer(*this, true);
    }
    displayList->setRenderable(mHasDrawOps);
    return displayList;
}

void DisplayListRenderer::setViewport(int width, int height) {
    mOrthoMatrix.loadOrtho(0, width, height, 0, -1, 1);

    mWidth = width;
    mHeight = height;
}

void DisplayListRenderer::prepareDirty(float left, float top,
        float right, float bottom, bool opaque) {
    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSaveCount = 1;
    mSnapshot->setClip(0.0f, 0.0f, mWidth, mHeight);
    mRestoreSaveCount = -1;
}

void DisplayListRenderer::finish() {
    insertRestoreToCount();
    OpenGLRenderer::finish();
}

void DisplayListRenderer::interrupt() {
}

void DisplayListRenderer::resume() {
}

bool DisplayListRenderer::callDrawGLFunction(Functor *functor, Rect& dirty) {
    // Ignore dirty during recording, it matters only when we replay
    addOp(DisplayList::DrawGLFunction);
    addInt((int) functor);
    return false; // No invalidate needed at record-time
}

int DisplayListRenderer::save(int flags) {
    addOp(DisplayList::Save);
    addInt(flags);
    return OpenGLRenderer::save(flags);
}

void DisplayListRenderer::restore() {
    if (mRestoreSaveCount < 0) {
        addOp(DisplayList::Restore);
    } else {
        mRestoreSaveCount--;
    }
    OpenGLRenderer::restore();
}

void DisplayListRenderer::restoreToCount(int saveCount) {
    mRestoreSaveCount = saveCount;
    OpenGLRenderer::restoreToCount(saveCount);
}

int DisplayListRenderer::saveLayer(float left, float top, float right, float bottom,
        SkPaint* p, int flags) {
    addOp(DisplayList::SaveLayer);
    addBounds(left, top, right, bottom);
    addPaint(p);
    addInt(flags);
    return OpenGLRenderer::save(flags);
}

int DisplayListRenderer::saveLayerAlpha(float left, float top, float right, float bottom,
        int alpha, int flags) {
    addOp(DisplayList::SaveLayerAlpha);
    addBounds(left, top, right, bottom);
    addInt(alpha);
    addInt(flags);
    return OpenGLRenderer::save(flags);
}

void DisplayListRenderer::translate(float dx, float dy) {
    addOp(DisplayList::Translate);
    addPoint(dx, dy);
    OpenGLRenderer::translate(dx, dy);
}

void DisplayListRenderer::rotate(float degrees) {
    addOp(DisplayList::Rotate);
    addFloat(degrees);
    OpenGLRenderer::rotate(degrees);
}

void DisplayListRenderer::scale(float sx, float sy) {
    addOp(DisplayList::Scale);
    addPoint(sx, sy);
    OpenGLRenderer::scale(sx, sy);
}

void DisplayListRenderer::skew(float sx, float sy) {
    addOp(DisplayList::Skew);
    addPoint(sx, sy);
    OpenGLRenderer::skew(sx, sy);
}

void DisplayListRenderer::setMatrix(SkMatrix* matrix) {
    addOp(DisplayList::SetMatrix);
    addMatrix(matrix);
    OpenGLRenderer::setMatrix(matrix);
}

void DisplayListRenderer::concatMatrix(SkMatrix* matrix) {
    addOp(DisplayList::ConcatMatrix);
    addMatrix(matrix);
    OpenGLRenderer::concatMatrix(matrix);
}

bool DisplayListRenderer::clipRect(float left, float top, float right, float bottom,
        SkRegion::Op op) {
    addOp(DisplayList::ClipRect);
    addBounds(left, top, right, bottom);
    addInt(op);
    return OpenGLRenderer::clipRect(left, top, right, bottom, op);
}

bool DisplayListRenderer::drawDisplayList(DisplayList* displayList,
        uint32_t width, uint32_t height, Rect& dirty, uint32_t level) {
    // dirty is an out parameter and should not be recorded,
    // it matters only when replaying the display list
    addOp(DisplayList::DrawDisplayList);
    addDisplayList(displayList);
    addSize(width, height);
    return false;
}

void DisplayListRenderer::drawLayer(Layer* layer, float x, float y, SkPaint* paint) {
    addOp(DisplayList::DrawLayer);
    addInt((int) layer);
    addPoint(x, y);
    addPaint(paint);
}

void DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float left, float top,
        SkPaint* paint) {
    addOp(DisplayList::DrawBitmap);
    addBitmap(bitmap);
    addPoint(left, top);
    addPaint(paint);
}

void DisplayListRenderer::drawBitmap(SkBitmap* bitmap, SkMatrix* matrix,
        SkPaint* paint) {
    addOp(DisplayList::DrawBitmapMatrix);
    addBitmap(bitmap);
    addMatrix(matrix);
    addPaint(paint);
}

void DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop,
        float dstRight, float dstBottom, SkPaint* paint) {
    addOp(DisplayList::DrawBitmapRect);
    addBitmap(bitmap);
    addBounds(srcLeft, srcTop, srcRight, srcBottom);
    addBounds(dstLeft, dstTop, dstRight, dstBottom);
    addPaint(paint);
}

void DisplayListRenderer::drawBitmapMesh(SkBitmap* bitmap, int meshWidth, int meshHeight,
        float* vertices, int* colors, SkPaint* paint) {
    addOp(DisplayList::DrawBitmapMesh);
    addBitmap(bitmap);
    addInt(meshWidth);
    addInt(meshHeight);
    addFloats(vertices, (meshWidth + 1) * (meshHeight + 1) * 2);
    if (colors) {
        addInt(1);
        addInts(colors, (meshWidth + 1) * (meshHeight + 1));
    } else {
        addInt(0);
    }
    addPaint(paint);
}

void DisplayListRenderer::drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
        const uint32_t* colors, uint32_t width, uint32_t height, int8_t numColors,
        float left, float top, float right, float bottom, SkPaint* paint) {
    addOp(DisplayList::DrawPatch);
    addBitmap(bitmap);
    addInts(xDivs, width);
    addInts(yDivs, height);
    addUInts(colors, numColors);
    addBounds(left, top, right, bottom);
    addPaint(paint);
}

void DisplayListRenderer::drawColor(int color, SkXfermode::Mode mode) {
    addOp(DisplayList::DrawColor);
    addInt(color);
    addInt(mode);
}

void DisplayListRenderer::drawRect(float left, float top, float right, float bottom,
        SkPaint* paint) {
    addOp(DisplayList::DrawRect);
    addBounds(left, top, right, bottom);
    addPaint(paint);
}

void DisplayListRenderer::drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, SkPaint* paint) {
    addOp(DisplayList::DrawRoundRect);
    addBounds(left, top, right, bottom);
    addPoint(rx, ry);
    addPaint(paint);
}

void DisplayListRenderer::drawCircle(float x, float y, float radius, SkPaint* paint) {
    addOp(DisplayList::DrawCircle);
    addPoint(x, y);
    addFloat(radius);
    addPaint(paint);
}

void DisplayListRenderer::drawOval(float left, float top, float right, float bottom,
        SkPaint* paint) {
    addOp(DisplayList::DrawOval);
    addBounds(left, top, right, bottom);
    addPaint(paint);
}

void DisplayListRenderer::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, SkPaint* paint) {
    addOp(DisplayList::DrawArc);
    addBounds(left, top, right, bottom);
    addPoint(startAngle, sweepAngle);
    addInt(useCenter ? 1 : 0);
    addPaint(paint);
}

void DisplayListRenderer::drawPath(SkPath* path, SkPaint* paint) {
    addOp(DisplayList::DrawPath);
    addPath(path);
    addPaint(paint);
}

void DisplayListRenderer::drawLines(float* points, int count, SkPaint* paint) {
    addOp(DisplayList::DrawLines);
    addFloats(points, count);
    addPaint(paint);
}

void DisplayListRenderer::drawPoints(float* points, int count, SkPaint* paint) {
    addOp(DisplayList::DrawPoints);
    addFloats(points, count);
    addPaint(paint);
}

void DisplayListRenderer::drawText(const char* text, int bytesCount, int count,
        float x, float y, SkPaint* paint, float length) {
    if (count <= 0) return;
    addOp(DisplayList::DrawText);
    addText(text, bytesCount);
    addInt(count);
    addPoint(x, y);
    // TODO: We should probably make a copy of the paint instead of modifying
    //       it; modifying the paint will change its generationID the first
    //       time, which might impact caches. More investigation needed to
    //       see if it matters.
    //       If we make a copy, then drawTextDecorations() should *not* make
    //       its own copy as it does right now.
    // Beware: this needs Glyph encoding (already done on the Paint constructor)
    paint->setAntiAlias(true);
    addPaint(paint);
    addFloat(length < 0.0f ? paint->measureText(text, bytesCount) : length);
}

void DisplayListRenderer::resetShader() {
    addOp(DisplayList::ResetShader);
}

void DisplayListRenderer::setupShader(SkiaShader* shader) {
    addOp(DisplayList::SetupShader);
    addShader(shader);
}

void DisplayListRenderer::resetColorFilter() {
    addOp(DisplayList::ResetColorFilter);
}

void DisplayListRenderer::setupColorFilter(SkiaColorFilter* filter) {
    addOp(DisplayList::SetupColorFilter);
    addColorFilter(filter);
}

void DisplayListRenderer::resetShadow() {
    addOp(DisplayList::ResetShadow);
}

void DisplayListRenderer::setupShadow(float radius, float dx, float dy, int color) {
    addOp(DisplayList::SetupShadow);
    addFloat(radius);
    addPoint(dx, dy);
    addInt(color);
}

}; // namespace uirenderer
}; // namespace android
