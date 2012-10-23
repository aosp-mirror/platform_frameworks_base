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

#include <SkCamera.h>

#include <private/hwui/DrawGlInfo.h>

#include "DisplayListLogBuffer.h"
#include "DisplayListRenderer.h"
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
    "DrawBitmapData",
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
    "DrawTextOnPath",
    "DrawPosText",
    "DrawText",
    "ResetShader",
    "SetupShader",
    "ResetColorFilter",
    "SetupColorFilter",
    "ResetShadow",
    "SetupShadow",
    "ResetPaintFilter",
    "SetupPaintFilter",
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

DisplayList::DisplayList(const DisplayListRenderer& recorder) :
    mTransformMatrix(NULL), mTransformCamera(NULL), mTransformMatrix3D(NULL),
    mStaticMatrix(NULL), mAnimationMatrix(NULL) {

    initFromDisplayListRenderer(recorder);
}

DisplayList::~DisplayList() {
    clearResources();
}

void DisplayList::destroyDisplayListDeferred(DisplayList* displayList) {
    if (displayList) {
        DISPLAY_LIST_LOGD("Deferring display list destruction");
        Caches::getInstance().deleteDisplayListDeferred(displayList);
    }
}

void DisplayList::clearResources() {
    sk_free((void*) mReader.base());
    mReader.setMemory(NULL, 0);

    delete mTransformMatrix;
    delete mTransformCamera;
    delete mTransformMatrix3D;
    delete mStaticMatrix;
    delete mAnimationMatrix;

    mTransformMatrix = NULL;
    mTransformCamera = NULL;
    mTransformMatrix3D = NULL;
    mStaticMatrix = NULL;
    mAnimationMatrix = NULL;

    Caches& caches = Caches::getInstance();
    caches.unregisterFunctors(mFunctorCount);
    caches.resourceCache.lock();

    for (size_t i = 0; i < mBitmapResources.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(mBitmapResources.itemAt(i));
    }

    for (size_t i = 0; i < mOwnedBitmapResources.size(); i++) {
        SkBitmap* bitmap = mOwnedBitmapResources.itemAt(i);
        caches.resourceCache.decrementRefcountLocked(bitmap);
        caches.resourceCache.destructorLocked(bitmap);
    }

    for (size_t i = 0; i < mFilterResources.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(mFilterResources.itemAt(i));
    }

    for (size_t i = 0; i < mShaders.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(mShaders.itemAt(i));
        caches.resourceCache.destructorLocked(mShaders.itemAt(i));
    }

    for (size_t i = 0; i < mSourcePaths.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(mSourcePaths.itemAt(i));
    }

    for (size_t i = 0; i < mLayers.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(mLayers.itemAt(i));
    }

    caches.resourceCache.unlock();

    for (size_t i = 0; i < mPaints.size(); i++) {
        delete mPaints.itemAt(i);
    }

    for (size_t i = 0; i < mPaths.size(); i++) {
        SkPath* path = mPaths.itemAt(i);
        caches.pathCache.remove(path);
        delete path;
    }

    for (size_t i = 0; i < mMatrices.size(); i++) {
        delete mMatrices.itemAt(i);
    }

    mBitmapResources.clear();
    mOwnedBitmapResources.clear();
    mFilterResources.clear();
    mShaders.clear();
    mSourcePaths.clear();
    mPaints.clear();
    mPaths.clear();
    mMatrices.clear();
    mLayers.clear();
}

void DisplayList::reset() {
    clearResources();
    init();
}

void DisplayList::initFromDisplayListRenderer(const DisplayListRenderer& recorder, bool reusing) {

    if (reusing) {
        // re-using display list - clear out previous allocations
        clearResources();
    }

    init();

    const SkWriter32& writer = recorder.writeStream();
    if (writer.size() == 0) {
        return;
    }

    mSize = writer.size();
    void* buffer = sk_malloc_throw(mSize);
    writer.flatten(buffer);
    mReader.setMemory(buffer, mSize);

    mFunctorCount = recorder.getFunctorCount();

    Caches& caches = Caches::getInstance();
    caches.registerFunctors(mFunctorCount);
    caches.resourceCache.lock();

    const Vector<SkBitmap*>& bitmapResources = recorder.getBitmapResources();
    for (size_t i = 0; i < bitmapResources.size(); i++) {
        SkBitmap* resource = bitmapResources.itemAt(i);
        mBitmapResources.add(resource);
        caches.resourceCache.incrementRefcountLocked(resource);
    }

    const Vector<SkBitmap*> &ownedBitmapResources = recorder.getOwnedBitmapResources();
    for (size_t i = 0; i < ownedBitmapResources.size(); i++) {
        SkBitmap* resource = ownedBitmapResources.itemAt(i);
        mOwnedBitmapResources.add(resource);
        caches.resourceCache.incrementRefcountLocked(resource);
    }

    const Vector<SkiaColorFilter*>& filterResources = recorder.getFilterResources();
    for (size_t i = 0; i < filterResources.size(); i++) {
        SkiaColorFilter* resource = filterResources.itemAt(i);
        mFilterResources.add(resource);
        caches.resourceCache.incrementRefcountLocked(resource);
    }

    const Vector<SkiaShader*>& shaders = recorder.getShaders();
    for (size_t i = 0; i < shaders.size(); i++) {
        SkiaShader* resource = shaders.itemAt(i);
        mShaders.add(resource);
        caches.resourceCache.incrementRefcountLocked(resource);
    }

    const SortedVector<SkPath*>& sourcePaths = recorder.getSourcePaths();
    for (size_t i = 0; i < sourcePaths.size(); i++) {
        mSourcePaths.add(sourcePaths.itemAt(i));
        caches.resourceCache.incrementRefcountLocked(sourcePaths.itemAt(i));
    }

    const Vector<Layer*>& layers = recorder.getLayers();
    for (size_t i = 0; i < layers.size(); i++) {
        mLayers.add(layers.itemAt(i));
        caches.resourceCache.incrementRefcountLocked(layers.itemAt(i));
    }

    caches.resourceCache.unlock();

    const Vector<SkPaint*>& paints = recorder.getPaints();
    for (size_t i = 0; i < paints.size(); i++) {
        mPaints.add(paints.itemAt(i));
    }

    const Vector<SkPath*>& paths = recorder.getPaths();
    for (size_t i = 0; i < paths.size(); i++) {
        mPaths.add(paths.itemAt(i));
    }

    const Vector<SkMatrix*>& matrices = recorder.getMatrices();
    for (size_t i = 0; i < matrices.size(); i++) {
        mMatrices.add(matrices.itemAt(i));
    }
}

void DisplayList::init() {
    mSize = 0;
    mIsRenderable = true;
    mFunctorCount = 0;
    mLeft = 0;
    mTop = 0;
    mRight = 0;
    mBottom = 0;
    mClipChildren = true;
    mAlpha = 1;
    mMultipliedAlpha = 255;
    mHasOverlappingRendering = true;
    mTranslationX = 0;
    mTranslationY = 0;
    mRotation = 0;
    mRotationX = 0;
    mRotationY= 0;
    mScaleX = 1;
    mScaleY = 1;
    mPivotX = 0;
    mPivotY = 0;
    mCameraDistance = 0;
    mMatrixDirty = false;
    mMatrixFlags = 0;
    mPrevWidth = -1;
    mPrevHeight = -1;
    mWidth = 0;
    mHeight = 0;
    mPivotExplicitlySet = false;
    mCaching = false;
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
    ALOGD("%sStart display list (%p, %s, render=%d)", (char*) indent + 2, this,
            mName.string(), isRenderable());

    ALOGD("%s%s %d", indent, "Save", SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    int saveCount = renderer.getSaveCount() - 1;

    outputViewProperties(renderer, (char*) indent);
    mReader.rewind();

    while (!mReader.eof()) {
        int op = mReader.readInt();
        if (op & OP_MAY_BE_SKIPPED_MASK) {
            int skip = mReader.readInt();
            ALOGD("%sSkip %d", (char*) indent, skip);
            op &= ~OP_MAY_BE_SKIPPED_MASK;
        }

        switch (op) {
            case DrawGLFunction: {
                Functor *functor = (Functor *) getInt();
                ALOGD("%s%s %p", (char*) indent, OP_NAMES[op], functor);
            }
            break;
            case Save: {
                int rendererNum = getInt();
                ALOGD("%s%s %d", (char*) indent, OP_NAMES[op], rendererNum);
            }
            break;
            case Restore: {
                ALOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case RestoreToCount: {
                int restoreCount = saveCount + getInt();
                ALOGD("%s%s %d", (char*) indent, OP_NAMES[op], restoreCount);
            }
            break;
            case SaveLayer: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                SkPaint* paint = getPaint(renderer);
                int flags = getInt();
                ALOGD("%s%s %.2f, %.2f, %.2f, %.2f, %p, 0x%x", (char*) indent,
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
                ALOGD("%s%s %.2f, %.2f, %.2f, %.2f, %d, 0x%x", (char*) indent,
                        OP_NAMES[op], f1, f2, f3, f4, alpha, flags);
            }
            break;
            case Translate: {
                float f1 = getFloat();
                float f2 = getFloat();
                ALOGD("%s%s %.2f, %.2f", (char*) indent, OP_NAMES[op], f1, f2);
            }
            break;
            case Rotate: {
                float rotation = getFloat();
                ALOGD("%s%s %.2f", (char*) indent, OP_NAMES[op], rotation);
            }
            break;
            case Scale: {
                float sx = getFloat();
                float sy = getFloat();
                ALOGD("%s%s %.2f, %.2f", (char*) indent, OP_NAMES[op], sx, sy);
            }
            break;
            case Skew: {
                float sx = getFloat();
                float sy = getFloat();
                ALOGD("%s%s %.2f, %.2f", (char*) indent, OP_NAMES[op], sx, sy);
            }
            break;
            case SetMatrix: {
                SkMatrix* matrix = getMatrix();
                ALOGD("%s%s %p", (char*) indent, OP_NAMES[op], matrix);
            }
            break;
            case ConcatMatrix: {
                SkMatrix* matrix = getMatrix();
                ALOGD("%s%s new concat %p: [%f, %f, %f]   [%f, %f, %f]   [%f, %f, %f]",
                        (char*) indent, OP_NAMES[op], matrix, matrix->get(0), matrix->get(1),
                        matrix->get(2), matrix->get(3), matrix->get(4), matrix->get(5),
                        matrix->get(6), matrix->get(7), matrix->get(8));
            }
            break;
            case ClipRect: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                int regionOp = getInt();
                ALOGD("%s%s %.2f, %.2f, %.2f, %.2f, %d", (char*) indent, OP_NAMES[op],
                        f1, f2, f3, f4, regionOp);
            }
            break;
            case DrawDisplayList: {
                DisplayList* displayList = getDisplayList();
                int32_t flags = getInt();
                ALOGD("%s%s %p, %dx%d, 0x%x %d", (char*) indent, OP_NAMES[op],
                        displayList, mWidth, mHeight, flags, level + 1);
                renderer.outputDisplayList(displayList, level + 1);
            }
            break;
            case DrawLayer: {
                Layer* layer = (Layer*) getInt();
                float x = getFloat();
                float y = getFloat();
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %p, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
                        layer, x, y, paint);
            }
            break;
            case DrawBitmap: {
                SkBitmap* bitmap = getBitmap();
                float x = getFloat();
                float y = getFloat();
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %p, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
                        bitmap, x, y, paint);
            }
            break;
            case DrawBitmapMatrix: {
                SkBitmap* bitmap = getBitmap();
                SkMatrix* matrix = getMatrix();
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %p, %p, %p", (char*) indent, OP_NAMES[op],
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
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %p, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %p",
                        (char*) indent, OP_NAMES[op], bitmap, f1, f2, f3, f4, f5, f6, f7, f8, paint);
            }
            break;
            case DrawBitmapData: {
                SkBitmap* bitmap = getBitmapData();
                float x = getFloat();
                float y = getFloat();
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %.2f, %.2f, %p", (char*) indent, OP_NAMES[op], x, y, paint);
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
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s", (char*) indent, OP_NAMES[op]);
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
                int alpha = getInt();
                SkXfermode::Mode mode = (SkXfermode::Mode) getInt();
                ALOGD("%s%s %.2f, %.2f, %.2f, %.2f", (char*) indent, OP_NAMES[op],
                        left, top, right, bottom);
            }
            break;
            case DrawColor: {
                int color = getInt();
                int xferMode = getInt();
                ALOGD("%s%s 0x%x %d", (char*) indent, OP_NAMES[op], color, xferMode);
            }
            break;
            case DrawRect: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %.2f, %.2f, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
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
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %p",
                        (char*) indent, OP_NAMES[op], f1, f2, f3, f4, f5, f6, paint);
            }
            break;
            case DrawCircle: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %.2f, %.2f, %.2f, %p",
                        (char*) indent, OP_NAMES[op], f1, f2, f3, paint);
            }
            break;
            case DrawOval: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %.2f, %.2f, %.2f, %.2f, %p",
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
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %d, %p",
                        (char*) indent, OP_NAMES[op], f1, f2, f3, f4, f5, f6, i1, paint);
            }
            break;
            case DrawPath: {
                SkPath* path = getPath();
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %p, %p", (char*) indent, OP_NAMES[op], path, paint);
            }
            break;
            case DrawLines: {
                int count = 0;
                float* points = getFloats(count);
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case DrawPoints: {
                int count = 0;
                float* points = getFloats(count);
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case DrawTextOnPath: {
                getText(&text);
                int32_t count = getInt();
                SkPath* path = getPath();
                float hOffset = getFloat();
                float vOffset = getFloat();
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %s, %d, %d, %p", (char*) indent, OP_NAMES[op],
                    text.text(), text.length(), count, paint);
            }
            break;
            case DrawPosText: {
                getText(&text);
                int count = getInt();
                int positionsCount = 0;
                float* positions = getFloats(positionsCount);
                SkPaint* paint = getPaint(renderer);
                ALOGD("%s%s %s, %d, %d, %p", (char*) indent, OP_NAMES[op],
                        text.text(), text.length(), count, paint);
            }
            break;
            case DrawText: {
                getText(&text);
                int32_t count = getInt();
                float x = getFloat();
                float y = getFloat();
                int32_t positionsCount = 0;
                float* positions = getFloats(positionsCount);
                SkPaint* paint = getPaint(renderer);
                float length = getFloat();
                ALOGD("%s%s %s, %d, %d, %p", (char*) indent, OP_NAMES[op],
                        text.text(), text.length(), count, paint);
            }
            break;
            case ResetShader: {
                ALOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case SetupShader: {
                SkiaShader* shader = getShader();
                ALOGD("%s%s %p", (char*) indent, OP_NAMES[op], shader);
            }
            break;
            case ResetColorFilter: {
                ALOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case SetupColorFilter: {
                SkiaColorFilter *colorFilter = getColorFilter();
                ALOGD("%s%s %p", (char*) indent, OP_NAMES[op], colorFilter);
            }
            break;
            case ResetShadow: {
                ALOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case SetupShadow: {
                float radius = getFloat();
                float dx = getFloat();
                float dy = getFloat();
                int color = getInt();
                ALOGD("%s%s %.2f, %.2f, %.2f, 0x%x", (char*) indent, OP_NAMES[op],
                        radius, dx, dy, color);
            }
            break;
            case ResetPaintFilter: {
                ALOGD("%s%s", (char*) indent, OP_NAMES[op]);
            }
            break;
            case SetupPaintFilter: {
                int clearBits = getInt();
                int setBits = getInt();
                ALOGD("%s%s 0x%x, 0x%x", (char*) indent, OP_NAMES[op], clearBits, setBits);
            }
            break;
            default:
                ALOGD("Display List error: op not handled: %s%s",
                        (char*) indent, OP_NAMES[op]);
                break;
        }
    }
    ALOGD("%sDone (%p, %s)", (char*) indent + 2, this, mName.string());
}

void DisplayList::updateMatrix() {
    if (mMatrixDirty) {
        if (!mTransformMatrix) {
            mTransformMatrix = new SkMatrix();
        }
        if (mMatrixFlags == 0 || mMatrixFlags == TRANSLATION) {
            mTransformMatrix->reset();
        } else {
            if (!mPivotExplicitlySet) {
                if (mWidth != mPrevWidth || mHeight != mPrevHeight) {
                    mPrevWidth = mWidth;
                    mPrevHeight = mHeight;
                    mPivotX = mPrevWidth / 2;
                    mPivotY = mPrevHeight / 2;
                }
            }
            if ((mMatrixFlags & ROTATION_3D) == 0) {
                mTransformMatrix->setTranslate(mTranslationX, mTranslationY);
                mTransformMatrix->preRotate(mRotation, mPivotX, mPivotY);
                mTransformMatrix->preScale(mScaleX, mScaleY, mPivotX, mPivotY);
            } else {
                if (!mTransformCamera) {
                    mTransformCamera = new Sk3DView();
                    mTransformMatrix3D = new SkMatrix();
                }
                mTransformMatrix->reset();
                mTransformCamera->save();
                mTransformMatrix->preScale(mScaleX, mScaleY, mPivotX, mPivotY);
                mTransformCamera->rotateX(mRotationX);
                mTransformCamera->rotateY(mRotationY);
                mTransformCamera->rotateZ(-mRotation);
                mTransformCamera->getMatrix(mTransformMatrix3D);
                mTransformMatrix3D->preTranslate(-mPivotX, -mPivotY);
                mTransformMatrix3D->postTranslate(mPivotX + mTranslationX,
                        mPivotY + mTranslationY);
                mTransformMatrix->postConcat(*mTransformMatrix3D);
                mTransformCamera->restore();
            }
        }
        mMatrixDirty = false;
    }
}

void DisplayList::outputViewProperties(OpenGLRenderer& renderer, char* indent) {
    updateMatrix();
    if (mLeft != 0 || mTop != 0) {
        ALOGD("%s%s %d, %d", indent, "Translate (left, top)", mLeft, mTop);
    }
    if (mStaticMatrix) {
        ALOGD("%s%s %p: [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f]",
                indent, "ConcatMatrix (static)", mStaticMatrix,
                mStaticMatrix->get(0), mStaticMatrix->get(1),
                mStaticMatrix->get(2), mStaticMatrix->get(3),
                mStaticMatrix->get(4), mStaticMatrix->get(5),
                mStaticMatrix->get(6), mStaticMatrix->get(7),
                mStaticMatrix->get(8));
    }
    if (mAnimationMatrix) {
        ALOGD("%s%s %p: [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f]",
                indent, "ConcatMatrix (animation)", mAnimationMatrix,
                mAnimationMatrix->get(0), mAnimationMatrix->get(1),
                mAnimationMatrix->get(2), mAnimationMatrix->get(3),
                mAnimationMatrix->get(4), mAnimationMatrix->get(5),
                mAnimationMatrix->get(6), mAnimationMatrix->get(7),
                mAnimationMatrix->get(8));
    }
    if (mMatrixFlags != 0) {
        if (mMatrixFlags == TRANSLATION) {
            ALOGD("%s%s %f, %f", indent, "Translate", mTranslationX, mTranslationY);
        } else {
            ALOGD("%s%s %p: [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f]",
                    indent, "ConcatMatrix", mTransformMatrix,
                    mTransformMatrix->get(0), mTransformMatrix->get(1),
                    mTransformMatrix->get(2), mTransformMatrix->get(3),
                    mTransformMatrix->get(4), mTransformMatrix->get(5),
                    mTransformMatrix->get(6), mTransformMatrix->get(7),
                    mTransformMatrix->get(8));
        }
    }
    if (mAlpha < 1 && !mCaching) {
        if (!mHasOverlappingRendering) {
            ALOGD("%s%s %.2f", indent, "SetAlpha", mAlpha);
        } else {
            int flags = SkCanvas::kHasAlphaLayer_SaveFlag;
            if (mClipChildren) {
                flags |= SkCanvas::kClipToLayer_SaveFlag;
            }
            ALOGD("%s%s %.2f, %.2f, %.2f, %.2f, %d, 0x%x", indent, "SaveLayerAlpha",
                    (float) 0, (float) 0, (float) mRight - mLeft, (float) mBottom - mTop,
                    mMultipliedAlpha, flags);
        }
    }
    if (mClipChildren) {
        ALOGD("%s%s %.2f, %.2f, %.2f, %.2f", indent, "ClipRect", 0.0f, 0.0f,
                (float) mRight - mLeft, (float) mBottom - mTop);
    }
}

void DisplayList::setViewProperties(OpenGLRenderer& renderer, uint32_t level) {
#if DEBUG_DISPLAY_LIST
        uint32_t count = (level + 1) * 2;
        char indent[count + 1];
        for (uint32_t i = 0; i < count; i++) {
            indent[i] = ' ';
        }
        indent[count] = '\0';
#endif
    updateMatrix();
    if (mLeft != 0 || mTop != 0) {
        DISPLAY_LIST_LOGD("%s%s %d, %d", indent, "Translate (left, top)", mLeft, mTop);
        renderer.translate(mLeft, mTop);
    }
    if (mStaticMatrix) {
        DISPLAY_LIST_LOGD(
                "%s%s %p: [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f]",
                indent, "ConcatMatrix (static)", mStaticMatrix,
                mStaticMatrix->get(0), mStaticMatrix->get(1),
                mStaticMatrix->get(2), mStaticMatrix->get(3),
                mStaticMatrix->get(4), mStaticMatrix->get(5),
                mStaticMatrix->get(6), mStaticMatrix->get(7),
                mStaticMatrix->get(8));
        renderer.concatMatrix(mStaticMatrix);
    } else if (mAnimationMatrix) {
        DISPLAY_LIST_LOGD(
                "%s%s %p: [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f]",
                indent, "ConcatMatrix (animation)", mAnimationMatrix,
                mAnimationMatrix->get(0), mAnimationMatrix->get(1),
                mAnimationMatrix->get(2), mAnimationMatrix->get(3),
                mAnimationMatrix->get(4), mAnimationMatrix->get(5),
                mAnimationMatrix->get(6), mAnimationMatrix->get(7),
                mAnimationMatrix->get(8));
        renderer.concatMatrix(mAnimationMatrix);
    }
    if (mMatrixFlags != 0) {
        if (mMatrixFlags == TRANSLATION) {
            DISPLAY_LIST_LOGD("%s%s %f, %f", indent, "Translate", mTranslationX, mTranslationY);
            renderer.translate(mTranslationX, mTranslationY);
        } else {
            DISPLAY_LIST_LOGD(
                    "%s%s %p: [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f]",
                    indent, "ConcatMatrix", mTransformMatrix,
                    mTransformMatrix->get(0), mTransformMatrix->get(1),
                    mTransformMatrix->get(2), mTransformMatrix->get(3),
                    mTransformMatrix->get(4), mTransformMatrix->get(5),
                    mTransformMatrix->get(6), mTransformMatrix->get(7),
                    mTransformMatrix->get(8));
            renderer.concatMatrix(mTransformMatrix);
        }
    }
    if (mAlpha < 1 && !mCaching) {
        if (!mHasOverlappingRendering) {
            DISPLAY_LIST_LOGD("%s%s %.2f", indent, "SetAlpha", mAlpha);
            renderer.setAlpha(mAlpha);
        } else {
            // TODO: should be able to store the size of a DL at record time and not
            // have to pass it into this call. In fact, this information might be in the
            // location/size info that we store with the new native transform data.
            int flags = SkCanvas::kHasAlphaLayer_SaveFlag;
            if (mClipChildren) {
                flags |= SkCanvas::kClipToLayer_SaveFlag;
            }
            DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %d, 0x%x", indent, "SaveLayerAlpha",
                    (float) 0, (float) 0, (float) mRight - mLeft, (float) mBottom - mTop,
                    mMultipliedAlpha, flags);
            renderer.saveLayerAlpha(0, 0, mRight - mLeft, mBottom - mTop,
                    mMultipliedAlpha, flags);
        }
    }
    if (mClipChildren) {
        DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f", indent, "ClipRect", 0.0f, 0.0f,
                (float) mRight - mLeft, (float) mBottom - mTop);
        renderer.clipRect(0, 0, mRight - mLeft, mBottom - mTop,
                SkRegion::kIntersect_Op);
    }
}

/**
 * Changes to replay(), specifically those involving opcode or parameter changes, should be mimicked
 * in the output() function, since that function processes the same list of opcodes for the
 * purposes of logging display list info for a given view.
 */
status_t DisplayList::replay(OpenGLRenderer& renderer, Rect& dirty, int32_t flags, uint32_t level) {
    status_t drawGlStatus = DrawGlInfo::kStatusDone;
    TextContainer text;
    mReader.rewind();

#if DEBUG_DISPLAY_LIST
    uint32_t count = (level + 1) * 2;
    char indent[count + 1];
    for (uint32_t i = 0; i < count; i++) {
        indent[i] = ' ';
    }
    indent[count] = '\0';
    Rect* clipRect = renderer.getClipRect();
    DISPLAY_LIST_LOGD("%sStart display list (%p, %s), clipRect: %.0f, %.f, %.0f, %.0f",
            (char*) indent + 2, this, mName.string(), clipRect->left, clipRect->top,
            clipRect->right, clipRect->bottom);
#endif

    renderer.startMark(mName.string());

    int restoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    DISPLAY_LIST_LOGD("%s%s %d %d", indent, "Save",
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag, restoreTo);
    setViewProperties(renderer, level);

    if (renderer.quickRejectNoScissor(0, 0, mWidth, mHeight)) {
        DISPLAY_LIST_LOGD("%s%s %d", (char*) indent, "RestoreToCount", restoreTo);
        renderer.restoreToCount(restoreTo);
        renderer.endMark();
        return drawGlStatus;
    }

    DisplayListLogBuffer& logBuffer = DisplayListLogBuffer::getInstance();
    int saveCount = renderer.getSaveCount() - 1;

    while (!mReader.eof()) {
        int op = mReader.readInt();
        if (op & OP_MAY_BE_SKIPPED_MASK) {
            int32_t skip = mReader.readInt();
            if (CC_LIKELY(flags & kReplayFlag_ClipChildren)) {
                mReader.skip(skip);
                DISPLAY_LIST_LOGD("%s%s skipping %d bytes", (char*) indent,
                        OP_NAMES[op & ~OP_MAY_BE_SKIPPED_MASK], skip);
                continue;
            } else {
                op &= ~OP_MAY_BE_SKIPPED_MASK;
            }
        }
        logBuffer.writeCommand(level, op);

#if DEBUG_DISPLAY_LIST_OPS_AS_EVENTS
        Caches::getInstance().eventMark(strlen(OP_NAMES[op]), OP_NAMES[op]);
#endif

        switch (op) {
            case DrawGLFunction: {
                Functor *functor = (Functor *) getInt();
                DISPLAY_LIST_LOGD("%s%s %p", (char*) indent, OP_NAMES[op], functor);
                renderer.startMark("GL functor");
                drawGlStatus |= renderer.callDrawGLFunction(functor, dirty);
                renderer.endMark();
            }
            break;
            case Save: {
                int32_t rendererNum = getInt();
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
                int32_t restoreCount = saveCount + getInt();
                DISPLAY_LIST_LOGD("%s%s %d", (char*) indent, OP_NAMES[op], restoreCount);
                renderer.restoreToCount(restoreCount);
            }
            break;
            case SaveLayer: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                SkPaint* paint = getPaint(renderer);
                int32_t flags = getInt();
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
                int32_t alpha = getInt();
                int32_t flags = getInt();
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
                DISPLAY_LIST_LOGD(
                        "%s%s %p: [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f] [%.2f, %.2f, %.2f]",
                        (char*) indent, OP_NAMES[op], matrix,
                        matrix->get(0), matrix->get(1), matrix->get(2),
                        matrix->get(3), matrix->get(4), matrix->get(5),
                        matrix->get(6), matrix->get(7), matrix->get(8));
                renderer.concatMatrix(matrix);
            }
            break;
            case ClipRect: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                int32_t regionOp = getInt();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %d", (char*) indent, OP_NAMES[op],
                        f1, f2, f3, f4, regionOp);
                renderer.clipRect(f1, f2, f3, f4, (SkRegion::Op) regionOp);
            }
            break;
            case DrawDisplayList: {
                DisplayList* displayList = getDisplayList();
                int32_t flags = getInt();
                DISPLAY_LIST_LOGD("%s%s %p, %dx%d, 0x%x %d", (char*) indent, OP_NAMES[op],
                        displayList, mWidth, mHeight, flags, level + 1);
                drawGlStatus |= renderer.drawDisplayList(displayList, dirty, flags, level + 1);
            }
            break;
            case DrawLayer: {
                int oldAlpha = -1;
                Layer* layer = (Layer*) getInt();
                float x = getFloat();
                float y = getFloat();
                SkPaint* paint = getPaint(renderer);
                if (mCaching && mMultipliedAlpha < 255) {
                    oldAlpha = layer->getAlpha();
                    layer->setAlpha(mMultipliedAlpha);
                }
                DISPLAY_LIST_LOGD("%s%s %p, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
                        layer, x, y, paint);
                drawGlStatus |= renderer.drawLayer(layer, x, y, paint);
                if (oldAlpha >= 0) {
                    layer->setAlpha(oldAlpha);
                }
            }
            break;
            case DrawBitmap: {
                int oldAlpha = -1;
                SkBitmap* bitmap = getBitmap();
                float x = getFloat();
                float y = getFloat();
                SkPaint* paint = getPaint(renderer);
                if (mCaching && mMultipliedAlpha < 255) {
                    oldAlpha = paint->getAlpha();
                    paint->setAlpha(mMultipliedAlpha);
                }
                DISPLAY_LIST_LOGD("%s%s %p, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
                        bitmap, x, y, paint);
                drawGlStatus |= renderer.drawBitmap(bitmap, x, y, paint);
                if (oldAlpha >= 0) {
                    paint->setAlpha(oldAlpha);
                }
            }
            break;
            case DrawBitmapMatrix: {
                SkBitmap* bitmap = getBitmap();
                SkMatrix* matrix = getMatrix();
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s %p, %p, %p", (char*) indent, OP_NAMES[op],
                        bitmap, matrix, paint);
                drawGlStatus |= renderer.drawBitmap(bitmap, matrix, paint);
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
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s %p, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %p",
                        (char*) indent, OP_NAMES[op], bitmap,
                        f1, f2, f3, f4, f5, f6, f7, f8,paint);
                drawGlStatus |= renderer.drawBitmap(bitmap, f1, f2, f3, f4, f5, f6, f7, f8, paint);
            }
            break;
            case DrawBitmapData: {
                SkBitmap* bitmap = getBitmapData();
                float x = getFloat();
                float y = getFloat();
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s %p, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
                        bitmap, x, y, paint);
                drawGlStatus |= renderer.drawBitmap(bitmap, x, y, paint);
            }
            break;
            case DrawBitmapMesh: {
                int32_t verticesCount = 0;
                uint32_t colorsCount = 0;

                SkBitmap* bitmap = getBitmap();
                uint32_t meshWidth = getInt();
                uint32_t meshHeight = getInt();
                float* vertices = getFloats(verticesCount);
                bool hasColors = getInt();
                int32_t* colors = hasColors ? getInts(colorsCount) : NULL;
                SkPaint* paint = getPaint(renderer);

                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                drawGlStatus |= renderer.drawBitmapMesh(bitmap, meshWidth, meshHeight, vertices,
                        colors, paint);
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

                int alpha = getInt();
                SkXfermode::Mode mode = (SkXfermode::Mode) getInt();

                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                drawGlStatus |= renderer.drawPatch(bitmap, xDivs, yDivs, colors,
                        xDivsCount, yDivsCount, numColors, left, top, right, bottom,
                        alpha, mode);
            }
            break;
            case DrawColor: {
                int32_t color = getInt();
                int32_t xferMode = getInt();
                DISPLAY_LIST_LOGD("%s%s 0x%x %d", (char*) indent, OP_NAMES[op], color, xferMode);
                drawGlStatus |= renderer.drawColor(color, (SkXfermode::Mode) xferMode);
            }
            break;
            case DrawRect: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %p", (char*) indent, OP_NAMES[op],
                        f1, f2, f3, f4, paint);
                drawGlStatus |= renderer.drawRect(f1, f2, f3, f4, paint);
            }
            break;
            case DrawRoundRect: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                float f5 = getFloat();
                float f6 = getFloat();
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %p",
                        (char*) indent, OP_NAMES[op], f1, f2, f3, f4, f5, f6, paint);
                drawGlStatus |= renderer.drawRoundRect(f1, f2, f3, f4, f5, f6, paint);
            }
            break;
            case DrawCircle: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %p",
                        (char*) indent, OP_NAMES[op], f1, f2, f3, paint);
                drawGlStatus |= renderer.drawCircle(f1, f2, f3, paint);
            }
            break;
            case DrawOval: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %p",
                        (char*) indent, OP_NAMES[op], f1, f2, f3, f4, paint);
                drawGlStatus |= renderer.drawOval(f1, f2, f3, f4, paint);
            }
            break;
            case DrawArc: {
                float f1 = getFloat();
                float f2 = getFloat();
                float f3 = getFloat();
                float f4 = getFloat();
                float f5 = getFloat();
                float f6 = getFloat();
                int32_t i1 = getInt();
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %d, %p",
                        (char*) indent, OP_NAMES[op], f1, f2, f3, f4, f5, f6, i1, paint);
                drawGlStatus |= renderer.drawArc(f1, f2, f3, f4, f5, f6, i1 == 1, paint);
            }
            break;
            case DrawPath: {
                SkPath* path = getPath();
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s %p, %p", (char*) indent, OP_NAMES[op], path, paint);
                drawGlStatus |= renderer.drawPath(path, paint);
            }
            break;
            case DrawLines: {
                int32_t count = 0;
                float* points = getFloats(count);
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                drawGlStatus |= renderer.drawLines(points, count, paint);
            }
            break;
            case DrawPoints: {
                int32_t count = 0;
                float* points = getFloats(count);
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                drawGlStatus |= renderer.drawPoints(points, count, paint);
            }
            break;
            case DrawTextOnPath: {
                getText(&text);
                int32_t count = getInt();
                SkPath* path = getPath();
                float hOffset = getFloat();
                float vOffset = getFloat();
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s %s, %d, %d, %p", (char*) indent, OP_NAMES[op],
                    text.text(), text.length(), count, paint);
                drawGlStatus |= renderer.drawTextOnPath(text.text(), text.length(), count, path,
                        hOffset, vOffset, paint);
            }
            break;
            case DrawPosText: {
                getText(&text);
                int32_t count = getInt();
                int32_t positionsCount = 0;
                float* positions = getFloats(positionsCount);
                SkPaint* paint = getPaint(renderer);
                DISPLAY_LIST_LOGD("%s%s %s, %d, %d, %p", (char*) indent,
                        OP_NAMES[op], text.text(), text.length(), count, paint);
                drawGlStatus |= renderer.drawPosText(text.text(), text.length(), count,
                        positions, paint);
            }
            break;
            case DrawText: {
                getText(&text);
                int32_t count = getInt();
                float x = getFloat();
                float y = getFloat();
                int32_t positionsCount = 0;
                float* positions = getFloats(positionsCount);
                SkPaint* paint = getPaint(renderer);
                float length = getFloat();
                DISPLAY_LIST_LOGD("%s%s %s, %d, %d, %.2f, %.2f, %p, %.2f", (char*) indent,
                        OP_NAMES[op], text.text(), text.length(), count, x, y, paint, length);
                drawGlStatus |= renderer.drawText(text.text(), text.length(), count,
                        x, y, positions, paint, length);
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
                int32_t color = getInt();
                DISPLAY_LIST_LOGD("%s%s %.2f, %.2f, %.2f, 0x%x", (char*) indent, OP_NAMES[op],
                        radius, dx, dy, color);
                renderer.setupShadow(radius, dx, dy, color);
            }
            break;
            case ResetPaintFilter: {
                DISPLAY_LIST_LOGD("%s%s", (char*) indent, OP_NAMES[op]);
                renderer.resetPaintFilter();
            }
            break;
            case SetupPaintFilter: {
                int32_t clearBits = getInt();
                int32_t setBits = getInt();
                DISPLAY_LIST_LOGD("%s%s 0x%x, 0x%x", (char*) indent, OP_NAMES[op],
                        clearBits, setBits);
                renderer.setupPaintFilter(clearBits, setBits);
            }
            break;
            default:
                DISPLAY_LIST_LOGD("Display List error: op not handled: %s%s",
                        (char*) indent, OP_NAMES[op]);
                break;
        }
    }

    DISPLAY_LIST_LOGD("%s%s %d", (char*) indent, "RestoreToCount", restoreTo);
    renderer.restoreToCount(restoreTo);
    renderer.endMark();

    DISPLAY_LIST_LOGD("%sDone (%p, %s), returning %d", (char*) indent + 2, this, mName.string(),
            drawGlStatus);
    return drawGlStatus;
}

///////////////////////////////////////////////////////////////////////////////
// Base structure
///////////////////////////////////////////////////////////////////////////////

DisplayListRenderer::DisplayListRenderer():
        mCaches(Caches::getInstance()), mWriter(MIN_WRITER_SIZE),
        mTranslateX(0.0f), mTranslateY(0.0f), mHasTranslate(false),
        mHasDrawOps(false), mFunctorCount(0) {
}

DisplayListRenderer::~DisplayListRenderer() {
    reset();
}

void DisplayListRenderer::reset() {
    mWriter.reset();

    mCaches.resourceCache.lock();

    for (size_t i = 0; i < mBitmapResources.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mBitmapResources.itemAt(i));
    }

    for (size_t i = 0; i < mOwnedBitmapResources.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mOwnedBitmapResources.itemAt(i));
    }

    for (size_t i = 0; i < mFilterResources.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mFilterResources.itemAt(i));
    }

    for (size_t i = 0; i < mShaders.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mShaders.itemAt(i));
    }

    for (size_t i = 0; i < mSourcePaths.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mSourcePaths.itemAt(i));
    }

    for (size_t i = 0; i < mLayers.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mLayers.itemAt(i));
    }

    mCaches.resourceCache.unlock();

    mBitmapResources.clear();
    mOwnedBitmapResources.clear();
    mFilterResources.clear();
    mSourcePaths.clear();

    mShaders.clear();
    mShaderMap.clear();

    mPaints.clear();
    mPaintMap.clear();

    mPaths.clear();
    mPathMap.clear();

    mMatrices.clear();

    mLayers.clear();

    mHasDrawOps = false;
    mFunctorCount = 0;
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

bool DisplayListRenderer::isDeferred() {
    return true;
}

void DisplayListRenderer::setViewport(int width, int height) {
    mOrthoMatrix.loadOrtho(0, width, height, 0, -1, 1);

    mWidth = width;
    mHeight = height;
}

status_t DisplayListRenderer::prepareDirty(float left, float top,
        float right, float bottom, bool opaque) {
    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSaveCount = 1;

    mSnapshot->setClip(0.0f, 0.0f, mWidth, mHeight);
    mDirtyClip = opaque;

    mRestoreSaveCount = -1;

    return DrawGlInfo::kStatusDone; // No invalidate needed at record-time
}

void DisplayListRenderer::finish() {
    insertRestoreToCount();
    insertTranlate();
}

void DisplayListRenderer::interrupt() {
}

void DisplayListRenderer::resume() {
}

status_t DisplayListRenderer::callDrawGLFunction(Functor *functor, Rect& dirty) {
    // Ignore dirty during recording, it matters only when we replay
    addOp(DisplayList::DrawGLFunction);
    addInt((int) functor);
    mFunctorCount++;
    return DrawGlInfo::kStatusDone; // No invalidate needed at record-time
}

int DisplayListRenderer::save(int flags) {
    addOp(DisplayList::Save);
    addInt(flags);
    return OpenGLRenderer::save(flags);
}

void DisplayListRenderer::restore() {
    if (mRestoreSaveCount < 0) {
        restoreToCount(getSaveCount() - 1);
        return;
    }

    mRestoreSaveCount--;
    insertTranlate();
    OpenGLRenderer::restore();
}

void DisplayListRenderer::restoreToCount(int saveCount) {
    mRestoreSaveCount = saveCount;
    insertTranlate();
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
    mHasTranslate = true;
    mTranslateX += dx;
    mTranslateY += dy;
    insertRestoreToCount();
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

status_t DisplayListRenderer::drawDisplayList(DisplayList* displayList,
        Rect& dirty, int32_t flags, uint32_t level) {
    // dirty is an out parameter and should not be recorded,
    // it matters only when replaying the display list

    addOp(DisplayList::DrawDisplayList);
    addDisplayList(displayList);
    addInt(flags);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawLayer(Layer* layer, float x, float y, SkPaint* paint) {
    addOp(DisplayList::DrawLayer);
    addLayer(layer);
    addPoint(x, y);
    addPaint(paint);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float left, float top, SkPaint* paint) {
    const bool reject = quickRejectNoScissor(left, top,
            left + bitmap->width(), top + bitmap->height());
    uint32_t* location = addOp(DisplayList::DrawBitmap, reject);
    addBitmap(bitmap);
    addPoint(left, top);
    addPaint(paint);
    addSkip(location);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmap(SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint) {
    Rect r(0.0f, 0.0f, bitmap->width(), bitmap->height());
    const mat4 transform(*matrix);
    transform.mapRect(r);

    const bool reject = quickRejectNoScissor(r.left, r.top, r.right, r.bottom);
    uint32_t* location = addOp(DisplayList::DrawBitmapMatrix, reject);
    addBitmap(bitmap);
    addMatrix(matrix);
    addPaint(paint);
    addSkip(location);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop,
        float dstRight, float dstBottom, SkPaint* paint) {
    const bool reject = quickRejectNoScissor(dstLeft, dstTop, dstRight, dstBottom);
    uint32_t* location = addOp(DisplayList::DrawBitmapRect, reject);
    addBitmap(bitmap);
    addBounds(srcLeft, srcTop, srcRight, srcBottom);
    addBounds(dstLeft, dstTop, dstRight, dstBottom);
    addPaint(paint);
    addSkip(location);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmapData(SkBitmap* bitmap, float left, float top,
        SkPaint* paint) {
    const bool reject = quickRejectNoScissor(left, top,
            left + bitmap->width(), top + bitmap->height());
    uint32_t* location = addOp(DisplayList::DrawBitmapData, reject);
    addBitmapData(bitmap);
    addPoint(left, top);
    addPaint(paint);
    addSkip(location);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmapMesh(SkBitmap* bitmap, int meshWidth, int meshHeight,
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
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPatch(SkBitmap* bitmap, const int32_t* xDivs,
        const int32_t* yDivs, const uint32_t* colors, uint32_t width, uint32_t height,
        int8_t numColors, float left, float top, float right, float bottom, SkPaint* paint) {
    int alpha;
    SkXfermode::Mode mode;
    OpenGLRenderer::getAlphaAndModeDirect(paint, &alpha, &mode);

    const bool reject = quickRejectNoScissor(left, top, right, bottom);
    uint32_t* location = addOp(DisplayList::DrawPatch, reject);
    addBitmap(bitmap);
    addInts(xDivs, width);
    addInts(yDivs, height);
    addUInts(colors, numColors);
    addBounds(left, top, right, bottom);
    addInt(alpha);
    addInt(mode);
    addSkip(location);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawColor(int color, SkXfermode::Mode mode) {
    addOp(DisplayList::DrawColor);
    addInt(color);
    addInt(mode);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawRect(float left, float top, float right, float bottom,
        SkPaint* paint) {
    const bool reject = paint->getStyle() == SkPaint::kFill_Style &&
            quickRejectNoScissor(left, top, right, bottom);
    uint32_t* location = addOp(DisplayList::DrawRect, reject);
    addBounds(left, top, right, bottom);
    addPaint(paint);
    addSkip(location);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawRoundRect(float left, float top, float right, float bottom,
        float rx, float ry, SkPaint* paint) {
    const bool reject = paint->getStyle() == SkPaint::kFill_Style &&
            quickRejectNoScissor(left, top, right, bottom);
    uint32_t* location = addOp(DisplayList::DrawRoundRect, reject);
    addBounds(left, top, right, bottom);
    addPoint(rx, ry);
    addPaint(paint);
    addSkip(location);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawCircle(float x, float y, float radius, SkPaint* paint) {
    addOp(DisplayList::DrawCircle);
    addPoint(x, y);
    addFloat(radius);
    addPaint(paint);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawOval(float left, float top, float right, float bottom,
        SkPaint* paint) {
    addOp(DisplayList::DrawOval);
    addBounds(left, top, right, bottom);
    addPaint(paint);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, SkPaint* paint) {
    addOp(DisplayList::DrawArc);
    addBounds(left, top, right, bottom);
    addPoint(startAngle, sweepAngle);
    addInt(useCenter ? 1 : 0);
    addPaint(paint);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPath(SkPath* path, SkPaint* paint) {
    float left, top, offset;
    uint32_t width, height;
    computePathBounds(path, paint, left, top, offset, width, height);

    left -= offset;
    top -= offset;

    const bool reject = quickRejectNoScissor(left, top, left + width, top + height);
    uint32_t* location = addOp(DisplayList::DrawPath, reject);
    addPath(path);
    addPaint(paint);
    addSkip(location);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawLines(float* points, int count, SkPaint* paint) {
    addOp(DisplayList::DrawLines);
    addFloats(points, count);
    addPaint(paint);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPoints(float* points, int count, SkPaint* paint) {
    addOp(DisplayList::DrawPoints);
    addFloats(points, count);
    addPaint(paint);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawTextOnPath(const char* text, int bytesCount, int count,
        SkPath* path, float hOffset, float vOffset, SkPaint* paint) {
    if (!text || count <= 0) return DrawGlInfo::kStatusDone;
    addOp(DisplayList::DrawTextOnPath);
    addText(text, bytesCount);
    addInt(count);
    addPath(path);
    addFloat(hOffset);
    addFloat(vOffset);
    paint->setAntiAlias(true);
    SkPaint* addedPaint = addPaint(paint);
    FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(addedPaint);
    fontRenderer.precache(addedPaint, text, count);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPosText(const char* text, int bytesCount, int count,
        const float* positions, SkPaint* paint) {
    if (!text || count <= 0) return DrawGlInfo::kStatusDone;
    addOp(DisplayList::DrawPosText);
    addText(text, bytesCount);
    addInt(count);
    addFloats(positions, count * 2);
    paint->setAntiAlias(true);
    SkPaint* addedPaint = addPaint(paint);
    FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(addedPaint);
    fontRenderer.precache(addedPaint, text, count);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawText(const char* text, int bytesCount, int count,
        float x, float y, const float* positions, SkPaint* paint, float length) {
    if (!text || count <= 0) return DrawGlInfo::kStatusDone;

    // TODO: We should probably make a copy of the paint instead of modifying
    //       it; modifying the paint will change its generationID the first
    //       time, which might impact caches. More investigation needed to
    //       see if it matters.
    //       If we make a copy, then drawTextDecorations() should *not* make
    //       its own copy as it does right now.
    // Beware: this needs Glyph encoding (already done on the Paint constructor)
    paint->setAntiAlias(true);
    if (length < 0.0f) length = paint->measureText(text, bytesCount);

    bool reject = false;
    if (CC_LIKELY(paint->getTextAlign() == SkPaint::kLeft_Align)) {
        SkPaint::FontMetrics metrics;
        paint->getFontMetrics(&metrics, 0.0f);
        reject = quickRejectNoScissor(x, y + metrics.fTop, x + length, y + metrics.fBottom);
    }

    uint32_t* location = addOp(DisplayList::DrawText, reject);
    addText(text, bytesCount);
    addInt(count);
    addFloat(x);
    addFloat(y);
    addFloats(positions, count * 2);
    SkPaint* addedPaint = addPaint(paint);
    if (!reject) {
        FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(addedPaint);
        fontRenderer.precache(addedPaint, text, count);
    }
    addFloat(length);
    addSkip(location);
    return DrawGlInfo::kStatusDone;
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

void DisplayListRenderer::resetPaintFilter() {
    addOp(DisplayList::ResetPaintFilter);
}

void DisplayListRenderer::setupPaintFilter(int clearBits, int setBits) {
    addOp(DisplayList::SetupPaintFilter);
    addInt(clearBits);
    addInt(setBits);
}

}; // namespace uirenderer
}; // namespace android
