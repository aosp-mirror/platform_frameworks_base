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
#include "VertexBuffer.h"

namespace android {
namespace uirenderer {

class PathTessellator {
public:
    /**
     * Populates scaleX and scaleY with the 'tessellation scale' of the transform - the effective X
     * and Y scales that tessellation will take into account when generating the 1.0 pixel thick
     * ramp.
     *
     * Two instances of the same shape (size, paint, etc.) will only generate the same vertices if
     * their tessellation scales are equal.
     */
    static void extractTessellationScales(const Matrix4& transform, float* scaleX, float* scaleY);

    /**
     * Populates a VertexBuffer with a tessellated approximation of the input convex path, as a single
     * triangle strip. Note: joins are not currently supported.
     *
     * @param path The path to be approximated
     * @param paint The paint the path will be drawn with, indicating AA, painting style
     *        (stroke vs fill), stroke width, stroke cap & join style, etc.
     * @param transform The transform the path is to be drawn with, used to drive stretch-aware path
     *        vertex approximation, and correct AA ramp offsetting.
     * @param vertexBuffer The output buffer
     */
    static void tessellatePath(const SkPath& path, const SkPaint* paint,
            const mat4& transform, VertexBuffer& vertexBuffer);

    /**
     * Populates a VertexBuffer with a tessellated approximation of points as a single triangle
     * strip (with degenerate tris separating), respecting the shape defined by the paint cap.
     *
     * @param points The center vertices of the points to be drawn
     * @param count The number of floats making up the point vertices
     * @param paint The paint the points will be drawn with indicating AA, stroke width & cap
     * @param transform The transform the points will be drawn with, used to drive stretch-aware path
     *        vertex approximation, and correct AA ramp offsetting
     * @param vertexBuffer The output buffer
     */
    static void tessellatePoints(const float* points, int count, const SkPaint* paint,
            const mat4& transform, VertexBuffer& vertexBuffer);

    /**
     * Populates a VertexBuffer with a tessellated approximation of lines as a single triangle
     * strip (with degenerate tris separating).
     *
     * @param points Pairs of endpoints defining the lines to be drawn
     * @param count The number of floats making up the line vertices
     * @param paint The paint the lines will be drawn with indicating AA, stroke width & cap
     * @param transform The transform the points will be drawn with, used to drive stretch-aware path
     *        vertex approximation, and correct AA ramp offsetting
     * @param vertexBuffer The output buffer
     */
    static void tessellateLines(const float* points, int count, const SkPaint* paint,
            const mat4& transform, VertexBuffer& vertexBuffer);

    /**
     * Approximates a convex, CW outline into a Vector of 2d vertices.
     *
     * @param path The outline to be approximated
     * @param thresholdSquared The threshold of acceptable error (in pixels) when approximating
     * @param outputVertices An empty Vector which will be populated with the output
     */
    static bool approximatePathOutlineVertices(const SkPath &path, float thresholdSquared,
            Vector<Vertex> &outputVertices);

private:
    static bool approximatePathOutlineVertices(const SkPath &path, bool forceClose,
            float sqrInvScaleX, float sqrInvScaleY, float thresholdSquared,
            Vector<Vertex> &outputVertices);

/*
  endpoints a & b,
  control c
 */
    static void recursiveQuadraticBezierVertices(
            float ax, float ay,
            float bx, float by,
            float cx, float cy,
            float sqrInvScaleX, float sqrInvScaleY, float thresholdSquared,
            Vector<Vertex> &outputVertices, int depth = 0);

/*
  endpoints p1, p2
  control c1, c2
 */
    static void recursiveCubicBezierVertices(
            float p1x, float p1y,
            float c1x, float c1y,
            float p2x, float p2y,
            float c2x, float c2y,
            float sqrInvScaleX, float sqrInvScaleY, float thresholdSquared,
            Vector<Vertex> &outputVertices, int depth = 0);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PATH_TESSELLATOR_H
