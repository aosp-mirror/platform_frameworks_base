/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_HWUI_PATH_TESSELLATOR_H
#define ANDROID_HWUI_PATH_TESSELLATOR_H

#include <utils/Vector.h>

#include "Matrix.h"
#include "Rect.h"
#include "Vertex.h"

namespace android {
namespace uirenderer {

class VertexBuffer {
public:
    VertexBuffer():
        mBuffer(0),
        mVertexCount(0),
        mCleanupMethod(NULL)
    {}

    ~VertexBuffer() {
        if (mCleanupMethod) mCleanupMethod(mBuffer);
    }

    /**
       This should be the only method used by the PathTessellator. Subsequent calls to alloc will
       allocate space within the first allocation (useful if you want to eventually allocate
       multiple regions within a single VertexBuffer, such as with PathTessellator::tesselateLines()
     */
    template <class TYPE>
    TYPE* alloc(int vertexCount) {
        if (mVertexCount) {
            TYPE* reallocBuffer = (TYPE*)mReallocBuffer;
            // already have allocated the buffer, re-allocate space within
            if (mReallocBuffer != mBuffer) {
                // not first re-allocation, leave space for degenerate triangles to separate strips
                reallocBuffer += 2;
            }
            mReallocBuffer = reallocBuffer + vertexCount;
            return reallocBuffer;
        }
        mVertexCount = vertexCount;
        mReallocBuffer = mBuffer = (void*)new TYPE[vertexCount];
        mCleanupMethod = &(cleanup<TYPE>);

        return (TYPE*)mBuffer;
    }

    template <class TYPE>
    void copyInto(const VertexBuffer& srcBuffer, float xOffset, float yOffset) {
        int verticesToCopy = srcBuffer.getVertexCount();

        TYPE* dst = alloc<TYPE>(verticesToCopy);
        TYPE* src = (TYPE*)srcBuffer.getBuffer();

        for (int i = 0; i < verticesToCopy; i++) {
            TYPE::copyWithOffset(&dst[i], src[i], xOffset, yOffset);
        }
    }

    void* getBuffer() const { return mBuffer; } // shouldn't be const, since not a const ptr?
    unsigned int getVertexCount() const { return mVertexCount; }

    template <class TYPE>
    void createDegenerateSeparators(int allocSize) {
        TYPE* end = (TYPE*)mBuffer + mVertexCount;
        for (TYPE* degen = (TYPE*)mBuffer + allocSize; degen < end; degen += 2 + allocSize) {
            memcpy(degen, degen - 1, sizeof(TYPE));
            memcpy(degen + 1, degen + 2, sizeof(TYPE));
        }
    }

private:
    template <class TYPE>
    static void cleanup(void* buffer) {
        delete[] (TYPE*)buffer;
    }

    void* mBuffer;
    unsigned int mVertexCount;

    void* mReallocBuffer; // used for multi-allocation

    void (*mCleanupMethod)(void*);
};

class PathTessellator {
public:
    static void expandBoundsForStroke(SkRect& bounds, const SkPaint* paint, bool forceExpand);

    static void tessellatePath(const SkPath& path, const SkPaint* paint,
            const mat4 *transform, VertexBuffer& vertexBuffer);

    static void tessellatePoints(const float* points, int count, SkPaint* paint,
            const mat4* transform, SkRect& bounds, VertexBuffer& vertexBuffer);

    static void tessellateLines(const float* points, int count, SkPaint* paint,
            const mat4* transform, SkRect& bounds, VertexBuffer& vertexBuffer);

private:
    static bool approximatePathOutlineVertices(const SkPath &path, bool forceClose,
        float sqrInvScaleX, float sqrInvScaleY, Vector<Vertex> &outputVertices);

/*
  endpoints a & b,
  control c
 */
    static void recursiveQuadraticBezierVertices(
            float ax, float ay,
            float bx, float by,
            float cx, float cy,
            float sqrInvScaleX, float sqrInvScaleY,
            Vector<Vertex> &outputVertices);

/*
  endpoints p1, p2
  control c1, c2
 */
    static void recursiveCubicBezierVertices(
            float p1x, float p1y,
            float c1x, float c1y,
            float p2x, float p2y,
            float c2x, float c2y,
            float sqrInvScaleX, float sqrInvScaleY,
            Vector<Vertex> &outputVertices);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PATH_TESSELLATOR_H
