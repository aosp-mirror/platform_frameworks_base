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

#ifndef ANDROID_HWUI_PATH_RENDERER_H
#define ANDROID_HWUI_PATH_RENDERER_H

#include <utils/Vector.h>

#include "Vertex.h"

namespace android {
namespace uirenderer {

class Matrix4;
typedef Matrix4 mat4;

class VertexBuffer {
public:
    VertexBuffer():
        mBuffer(0),
        mSize(0),
        mCleanupMethod(0)
    {}

    ~VertexBuffer()
    {
        if (mCleanupMethod)
            mCleanupMethod(mBuffer);
    }

    template <class TYPE>
    TYPE* alloc(int size)
    {
        mSize = size;
        mBuffer = (void*)new TYPE[size];
        mCleanupMethod = &(cleanup<TYPE>);

        return (TYPE*)mBuffer;
    }

    void* getBuffer() { return mBuffer; }
    unsigned int getSize() { return mSize; }

private:
    template <class TYPE>
    static void cleanup(void* buffer)
    {
        delete[] (TYPE*)buffer;
    }

    void* mBuffer;
    unsigned int mSize;
    void (*mCleanupMethod)(void*);
};

class PathRenderer {
public:
    static void computeInverseScales(
        const mat4 *transform, float &inverseScaleX, float& inverseScaleY);

    static void convexPathFillVertices(
        const SkPath &path, const mat4 *transform,
        VertexBuffer &vertexBuffer, bool isAA);

private:
    static void convexPathVertices(
        const SkPath &path,
        float thresholdx, float thresholdy,
        Vector<Vertex> &outputVertices);

/*
  endpoints a & b,
  control c
 */
    static void recursiveQuadraticBezierVertices(
        float ax, float ay,
        float bx, float by,
        float cx, float cy,
        float thresholdx, float thresholdy,
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
        float thresholdx, float thresholdy,
        Vector<Vertex> &outputVertices);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PATH_RENDERER_H
