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
#define LOG_NDEBUG 1

#define VERTEX_DEBUG 0

#if VERTEX_DEBUG
#define DEBUG_DUMP_ALPHA_BUFFER() \
    for (unsigned int i = 0; i < vertexBuffer.getSize(); i++) { \
        ALOGD("point %d at %f %f, alpha %f", \
        i, buffer[i].x, buffer[i].y, buffer[i].alpha); \
    }
#define DEBUG_DUMP_BUFFER() \
    for (unsigned int i = 0; i < vertexBuffer.getSize(); i++) { \
        ALOGD("point %d at %f %f", i, buffer[i].x, buffer[i].y); \
    }
#else
#define DEBUG_DUMP_ALPHA_BUFFER()
#define DEBUG_DUMP_BUFFER()
#endif

#include "PathTessellator.h"

#include "Matrix.h"
#include "Vector.h"
#include "Vertex.h"
#include "utils/MathUtils.h"

#include <algorithm>

#include <SkPath.h>
#include <SkPaint.h>
#include <SkPoint.h>
#include <SkGeometry.h> // WARNING: Internal Skia Header

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <utils/Log.h>
#include <utils/Trace.h>

namespace android {
namespace uirenderer {

#define OUTLINE_REFINE_THRESHOLD 0.5f
#define ROUND_CAP_THRESH 0.25f
#define PI 3.1415926535897932f
#define MAX_DEPTH 15

/**
 * Extracts the x and y scale from the transform as positive values, and clamps them
 */
void PathTessellator::extractTessellationScales(const Matrix4& transform,
        float* scaleX, float* scaleY) {
    if (CC_LIKELY(transform.isPureTranslate())) {
        *scaleX = 1.0f;
        *scaleY = 1.0f;
    } else {
        float m00 = transform.data[Matrix4::kScaleX];
        float m01 = transform.data[Matrix4::kSkewY];
        float m10 = transform.data[Matrix4::kSkewX];
        float m11 = transform.data[Matrix4::kScaleY];
        *scaleX = MathUtils::clampTessellationScale(sqrt(m00 * m00 + m01 * m01));
        *scaleY = MathUtils::clampTessellationScale(sqrt(m10 * m10 + m11 * m11));
    }
}

/**
 * Produces a pseudo-normal for a vertex, given the normals of the two incoming lines. If the offset
 * from each vertex in a perimeter is calculated, the resultant lines connecting the offset vertices
 * will be offset by 1.0
 *
 * Note that we can't add and normalize the two vectors, that would result in a rectangle having an
 * offset of (sqrt(2)/2, sqrt(2)/2) at each corner, instead of (1, 1)
 *
 * NOTE: assumes angles between normals 90 degrees or less
 */
inline static Vector2 totalOffsetFromNormals(const Vector2& normalA, const Vector2& normalB) {
    return (normalA + normalB) / (1 + fabs(normalA.dot(normalB)));
}

/**
 * Structure used for storing useful information about the SkPaint and scale used for tessellating
 */
struct PaintInfo {
public:
    PaintInfo(const SkPaint* paint, const mat4& transform) :
            style(paint->getStyle()), cap(paint->getStrokeCap()), isAA(paint->isAntiAlias()),
            halfStrokeWidth(paint->getStrokeWidth() * 0.5f), maxAlpha(1.0f) {
        // compute inverse scales
        if (CC_LIKELY(transform.isPureTranslate())) {
            inverseScaleX = 1.0f;
            inverseScaleY = 1.0f;
        } else {
            float scaleX, scaleY;
            PathTessellator::extractTessellationScales(transform, &scaleX, &scaleY);
            inverseScaleX = 1.0f / scaleX;
            inverseScaleY = 1.0f / scaleY;
        }

        if (isAA && halfStrokeWidth != 0 && inverseScaleX == inverseScaleY &&
                2 * halfStrokeWidth < inverseScaleX) {
            // AA, with non-hairline stroke, width < 1 pixel. Scale alpha and treat as hairline.
            maxAlpha *= (2 * halfStrokeWidth) / inverseScaleX;
            halfStrokeWidth = 0.0f;
        }
    }

    SkPaint::Style style;
    SkPaint::Cap cap;
    bool isAA;
    float inverseScaleX;
    float inverseScaleY;
    float halfStrokeWidth;
    float maxAlpha;

    inline void scaleOffsetForStrokeWidth(Vector2& offset) const {
        if (halfStrokeWidth == 0.0f) {
            // hairline - compensate for scale
            offset.x *= 0.5f * inverseScaleX;
            offset.y *= 0.5f * inverseScaleY;
        } else {
            offset *= halfStrokeWidth;
        }
    }

    /**
     * NOTE: the input will not always be a normal, especially for sharp edges - it should be the
     * result of totalOffsetFromNormals (see documentation there)
     */
    inline Vector2 deriveAAOffset(const Vector2& offset) const {
        return (Vector2){offset.x * 0.5f * inverseScaleX, offset.y * 0.5f * inverseScaleY};
    }

    /**
     * Returns the number of cap divisions beyond the minimum 2 (kButt_Cap/kSquareCap will return 0)
     * Should only be used when stroking and drawing caps
     */
    inline int capExtraDivisions() const {
        if (cap == SkPaint::kRound_Cap) {
            // always use 2 points for hairline
            if (halfStrokeWidth == 0.0f) return 2;

            float threshold = std::min(inverseScaleX, inverseScaleY) * ROUND_CAP_THRESH;
            return MathUtils::divisionsNeededToApproximateArc(halfStrokeWidth, PI, threshold);
        }
        return 0;
    }

    /**
     * Outset the bounds of point data (for line endpoints or points) to account for stroke
     * geometry.
     *
     * bounds are in pre-scaled space.
     */
    void expandBoundsForStroke(Rect* bounds) const {
        if (halfStrokeWidth == 0) {
            // hairline, outset by (0.5f + fudge factor) in post-scaling space
            bounds->outset(fabs(inverseScaleX) * (0.5f + Vertex::GeometryFudgeFactor()),
                    fabs(inverseScaleY) * (0.5f + Vertex::GeometryFudgeFactor()));
        } else {
            // non hairline, outset by half stroke width pre-scaled, and fudge factor post scaled
            bounds->outset(halfStrokeWidth + fabs(inverseScaleX) * Vertex::GeometryFudgeFactor(),
                    halfStrokeWidth + fabs(inverseScaleY) * Vertex::GeometryFudgeFactor());
        }
    }
};

void getFillVerticesFromPerimeter(const std::vector<Vertex>& perimeter,
        VertexBuffer& vertexBuffer) {
    Vertex* buffer = vertexBuffer.alloc<Vertex>(perimeter.size());

    int currentIndex = 0;
    // zig zag between all previous points on the inside of the hull to create a
    // triangle strip that fills the hull
    int srcAindex = 0;
    int srcBindex = perimeter.size() - 1;
    while (srcAindex <= srcBindex) {
        buffer[currentIndex++] = perimeter[srcAindex];
        if (srcAindex == srcBindex) break;
        buffer[currentIndex++] = perimeter[srcBindex];
        srcAindex++;
        srcBindex--;
    }
}

/*
 * Fills a vertexBuffer with non-alpha vertices, zig-zagging at each perimeter point to create a
 * tri-strip as wide as the stroke.
 *
 * Uses an additional 2 vertices at the end to wrap around, closing the tri-strip
 * (for a total of perimeter.size() * 2 + 2 vertices)
 */
void getStrokeVerticesFromPerimeter(const PaintInfo& paintInfo,
        const std::vector<Vertex>& perimeter, VertexBuffer& vertexBuffer) {
    Vertex* buffer = vertexBuffer.alloc<Vertex>(perimeter.size() * 2 + 2);

    int currentIndex = 0;
    const Vertex* last = &(perimeter[perimeter.size() - 1]);
    const Vertex* current = &(perimeter[0]);
    Vector2 lastNormal = {current->y - last->y, last->x - current->x};
    lastNormal.normalize();
    for (unsigned int i = 0; i < perimeter.size(); i++) {
        const Vertex* next = &(perimeter[i + 1 >= perimeter.size() ? 0 : i + 1]);
        Vector2 nextNormal = {next->y - current->y, current->x - next->x};
        nextNormal.normalize();

        Vector2 totalOffset = totalOffsetFromNormals(lastNormal, nextNormal);
        paintInfo.scaleOffsetForStrokeWidth(totalOffset);

        Vertex::set(&buffer[currentIndex++],
                current->x + totalOffset.x,
                current->y + totalOffset.y);

        Vertex::set(&buffer[currentIndex++],
                current->x - totalOffset.x,
                current->y - totalOffset.y);

        current = next;
        lastNormal = nextNormal;
    }

    // wrap around to beginning
    buffer[currentIndex++] = buffer[0];
    buffer[currentIndex++] = buffer[1];

    DEBUG_DUMP_BUFFER();
}

static inline void storeBeginEnd(const PaintInfo& paintInfo, const Vertex& center,
        const Vector2& normal, Vertex* buffer, int& currentIndex, bool begin) {
    Vector2 strokeOffset = normal;
    paintInfo.scaleOffsetForStrokeWidth(strokeOffset);

    Vector2 referencePoint = {center.x, center.y};
    if (paintInfo.cap == SkPaint::kSquare_Cap) {
        Vector2 rotated = {-strokeOffset.y, strokeOffset.x};
        referencePoint += rotated * (begin ? -1 : 1);
    }

    Vertex::set(&buffer[currentIndex++], referencePoint + strokeOffset);
    Vertex::set(&buffer[currentIndex++], referencePoint - strokeOffset);
}

/**
 * Fills a vertexBuffer with non-alpha vertices similar to getStrokeVerticesFromPerimeter, except:
 *
 * 1 - Doesn't need to wrap around, since the input vertices are unclosed
 *
 * 2 - can zig-zag across 'extra' vertices at either end, to create round caps
 */
void getStrokeVerticesFromUnclosedVertices(const PaintInfo& paintInfo,
        const std::vector<Vertex>& vertices, VertexBuffer& vertexBuffer) {
    const int extra = paintInfo.capExtraDivisions();
    const int allocSize = (vertices.size() + extra) * 2;
    Vertex* buffer = vertexBuffer.alloc<Vertex>(allocSize);

    const int lastIndex = vertices.size() - 1;
    if (extra > 0) {
        // tessellate both round caps
        float beginTheta = atan2(
                    - (vertices[0].x - vertices[1].x),
                    vertices[0].y - vertices[1].y);
        float endTheta = atan2(
                    - (vertices[lastIndex].x - vertices[lastIndex - 1].x),
                    vertices[lastIndex].y - vertices[lastIndex - 1].y);
        const float dTheta = PI / (extra + 1);

        int capOffset;
        for (int i = 0; i < extra; i++) {
            if (i < extra / 2) {
                capOffset = extra - 2 * i - 1;
            } else {
                capOffset = 2 * i - extra;
            }

            beginTheta += dTheta;
            Vector2 beginRadialOffset = {cosf(beginTheta), sinf(beginTheta)};
            paintInfo.scaleOffsetForStrokeWidth(beginRadialOffset);
            Vertex::set(&buffer[capOffset],
                    vertices[0].x + beginRadialOffset.x,
                    vertices[0].y + beginRadialOffset.y);

            endTheta += dTheta;
            Vector2 endRadialOffset = {cosf(endTheta), sinf(endTheta)};
            paintInfo.scaleOffsetForStrokeWidth(endRadialOffset);
            Vertex::set(&buffer[allocSize - 1 - capOffset],
                    vertices[lastIndex].x + endRadialOffset.x,
                    vertices[lastIndex].y + endRadialOffset.y);
        }
    }

    int currentIndex = extra;
    const Vertex* last = &(vertices[0]);
    const Vertex* current = &(vertices[1]);
    Vector2 lastNormal = {current->y - last->y, last->x - current->x};
    lastNormal.normalize();

    storeBeginEnd(paintInfo, vertices[0], lastNormal, buffer, currentIndex, true);

    for (unsigned int i = 1; i < vertices.size() - 1; i++) {
        const Vertex* next = &(vertices[i + 1]);
        Vector2 nextNormal = {next->y - current->y, current->x - next->x};
        nextNormal.normalize();

        Vector2 strokeOffset  = totalOffsetFromNormals(lastNormal, nextNormal);
        paintInfo.scaleOffsetForStrokeWidth(strokeOffset);

        Vector2 center = {current->x, current->y};
        Vertex::set(&buffer[currentIndex++], center + strokeOffset);
        Vertex::set(&buffer[currentIndex++], center - strokeOffset);

        current = next;
        lastNormal = nextNormal;
    }

    storeBeginEnd(paintInfo, vertices[lastIndex], lastNormal, buffer, currentIndex, false);

    DEBUG_DUMP_BUFFER();
}

/**
 * Populates a vertexBuffer with AlphaVertices to create an anti-aliased fill shape tessellation
 *
 * 1 - create the AA perimeter of unit width, by zig-zagging at each point around the perimeter of
 * the shape (using 2 * perimeter.size() vertices)
 *
 * 2 - wrap around to the beginning to complete the perimeter (2 vertices)
 *
 * 3 - zig zag back and forth inside the shape to fill it (using perimeter.size() vertices)
 */
void getFillVerticesFromPerimeterAA(const PaintInfo& paintInfo,
        const std::vector<Vertex>& perimeter, VertexBuffer& vertexBuffer,
        float maxAlpha = 1.0f) {
    AlphaVertex* buffer = vertexBuffer.alloc<AlphaVertex>(perimeter.size() * 3 + 2);

    // generate alpha points - fill Alpha vertex gaps in between each point with
    // alpha 0 vertex, offset by a scaled normal.
    int currentIndex = 0;
    const Vertex* last = &(perimeter[perimeter.size() - 1]);
    const Vertex* current = &(perimeter[0]);
    Vector2 lastNormal = {current->y - last->y, last->x - current->x};
    lastNormal.normalize();
    for (unsigned int i = 0; i < perimeter.size(); i++) {
        const Vertex* next = &(perimeter[i + 1 >= perimeter.size() ? 0 : i + 1]);
        Vector2 nextNormal = {next->y - current->y, current->x - next->x};
        nextNormal.normalize();

        // AA point offset from original point is that point's normal, such that each side is offset
        // by .5 pixels
        Vector2 totalOffset = paintInfo.deriveAAOffset(totalOffsetFromNormals(lastNormal, nextNormal));

        AlphaVertex::set(&buffer[currentIndex++],
                current->x + totalOffset.x,
                current->y + totalOffset.y,
                0.0f);
        AlphaVertex::set(&buffer[currentIndex++],
                current->x - totalOffset.x,
                current->y - totalOffset.y,
                maxAlpha);

        current = next;
        lastNormal = nextNormal;
    }

    // wrap around to beginning
    buffer[currentIndex++] = buffer[0];
    buffer[currentIndex++] = buffer[1];

    // zig zag between all previous points on the inside of the hull to create a
    // triangle strip that fills the hull, repeating the first inner point to
    // create degenerate tris to start inside path
    int srcAindex = 0;
    int srcBindex = perimeter.size() - 1;
    while (srcAindex <= srcBindex) {
        buffer[currentIndex++] = buffer[srcAindex * 2 + 1];
        if (srcAindex == srcBindex) break;
        buffer[currentIndex++] = buffer[srcBindex * 2 + 1];
        srcAindex++;
        srcBindex--;
    }

    DEBUG_DUMP_BUFFER();
}

/**
 * Stores geometry for a single, AA-perimeter (potentially rounded) cap
 *
 * For explanation of constants and general methodoloyg, see comments for
 * getStrokeVerticesFromUnclosedVerticesAA() below.
 */
inline static void storeCapAA(const PaintInfo& paintInfo, const std::vector<Vertex>& vertices,
        AlphaVertex* buffer, bool isFirst, Vector2 normal, int offset) {
    const int extra = paintInfo.capExtraDivisions();
    const int extraOffset = (extra + 1) / 2;
    const int capIndex = isFirst
            ? 2 * offset + 6 + 2 * (extra + extraOffset)
            : offset + 2 + 2 * extraOffset;
    if (isFirst) normal *= -1;

    // TODO: this normal should be scaled by radialScale if extra != 0, see totalOffsetFromNormals()
    Vector2 AAOffset = paintInfo.deriveAAOffset(normal);

    Vector2 strokeOffset = normal;
    paintInfo.scaleOffsetForStrokeWidth(strokeOffset);
    Vector2 outerOffset = strokeOffset + AAOffset;
    Vector2 innerOffset = strokeOffset - AAOffset;

    Vector2 capAAOffset = {0, 0};
    if (paintInfo.cap != SkPaint::kRound_Cap) {
        // if the cap is square or butt, the inside primary cap vertices will be inset in two
        // directions - both normal to the stroke, and parallel to it.
        capAAOffset = (Vector2){-AAOffset.y, AAOffset.x};
    }

    // determine referencePoint, the center point for the 4 primary cap vertices
    const Vertex& point = isFirst ? vertices.front() : vertices.back();
    Vector2 referencePoint = {point.x, point.y};
    if (paintInfo.cap == SkPaint::kSquare_Cap) {
        // To account for square cap, move the primary cap vertices (that create the AA edge) by the
        // stroke offset vector (rotated to be parallel to the stroke)
        Vector2 rotated = {-strokeOffset.y, strokeOffset.x};
        referencePoint += rotated;
    }

    AlphaVertex::set(&buffer[capIndex + 0],
            referencePoint.x + outerOffset.x + capAAOffset.x,
            referencePoint.y + outerOffset.y + capAAOffset.y,
            0.0f);
    AlphaVertex::set(&buffer[capIndex + 1],
            referencePoint.x + innerOffset.x - capAAOffset.x,
            referencePoint.y + innerOffset.y - capAAOffset.y,
            paintInfo.maxAlpha);

    bool isRound = paintInfo.cap == SkPaint::kRound_Cap;

    const int postCapIndex = (isRound && isFirst) ? (2 * extraOffset - 2) : capIndex + (2 * extra);
    AlphaVertex::set(&buffer[postCapIndex + 2],
            referencePoint.x - outerOffset.x + capAAOffset.x,
            referencePoint.y - outerOffset.y + capAAOffset.y,
            0.0f);
    AlphaVertex::set(&buffer[postCapIndex + 3],
            referencePoint.x - innerOffset.x - capAAOffset.x,
            referencePoint.y - innerOffset.y - capAAOffset.y,
            paintInfo.maxAlpha);

    if (isRound) {
        const float dTheta = PI / (extra + 1);
        const float radialScale = 2.0f / (1 + cos(dTheta));
        float theta = atan2(normal.y, normal.x);
        int capPerimIndex = capIndex + 2;

        for (int i = 0; i < extra; i++) {
            theta += dTheta;

            Vector2 radialOffset = {cosf(theta), sinf(theta)};

            // scale to compensate for pinching at sharp angles, see totalOffsetFromNormals()
            radialOffset *= radialScale;

            AAOffset = paintInfo.deriveAAOffset(radialOffset);
            paintInfo.scaleOffsetForStrokeWidth(radialOffset);
            AlphaVertex::set(&buffer[capPerimIndex++],
                    referencePoint.x + radialOffset.x + AAOffset.x,
                    referencePoint.y + radialOffset.y + AAOffset.y,
                    0.0f);
            AlphaVertex::set(&buffer[capPerimIndex++],
                    referencePoint.x + radialOffset.x - AAOffset.x,
                    referencePoint.y + radialOffset.y - AAOffset.y,
                    paintInfo.maxAlpha);

            if (isFirst && i == extra - extraOffset) {
                //copy most recent two points to first two points
                buffer[0] = buffer[capPerimIndex - 2];
                buffer[1] = buffer[capPerimIndex - 1];

                capPerimIndex = 2; // start writing the rest of the round cap at index 2
            }
        }

        if (isFirst) {
            const int startCapFillIndex = capIndex + 2 * (extra - extraOffset) + 4;
            int capFillIndex = startCapFillIndex;
            for (int i = 0; i < extra + 2; i += 2) {
                buffer[capFillIndex++] = buffer[1 + i];
                // TODO: to support odd numbers of divisions, break here on the last iteration
                buffer[capFillIndex++] = buffer[startCapFillIndex - 3 - i];
            }
        } else {
            int capFillIndex = 6 * vertices.size() + 2 + 6 * extra - (extra + 2);
            for (int i = 0; i < extra + 2; i += 2) {
                buffer[capFillIndex++] = buffer[capIndex + 1 + i];
                // TODO: to support odd numbers of divisions, break here on the last iteration
                buffer[capFillIndex++] = buffer[capIndex + 3 + 2 * extra - i];
            }
        }
        return;
    }
    if (isFirst) {
        buffer[0] = buffer[postCapIndex + 2];
        buffer[1] = buffer[postCapIndex + 3];
        buffer[postCapIndex + 4] = buffer[1]; // degenerate tris (the only two!)
        buffer[postCapIndex + 5] = buffer[postCapIndex + 1];
    } else {
        buffer[6 * vertices.size()] = buffer[postCapIndex + 1];
        buffer[6 * vertices.size() + 1] = buffer[postCapIndex + 3];
    }
}

/*
the geometry for an aa, capped stroke consists of the following:

       # vertices       |    function
----------------------------------------------------------------------
a) 2                    | Start AA perimeter
b) 2, 2 * roundDivOff   | First half of begin cap's perimeter
                        |
   2 * middlePts        | 'Outer' or 'Top' AA perimeter half (between caps)
                        |
a) 4                    | End cap's
b) 2, 2 * roundDivs, 2  |    AA perimeter
                        |
   2 * middlePts        | 'Inner' or 'bottom' AA perimeter half
                        |
a) 6                    | Begin cap's perimeter
b) 2, 2*(rD - rDO + 1), | Last half of begin cap's perimeter
       roundDivs, 2     |
                        |
   2 * middlePts        | Stroke's full opacity center strip
                        |
a) 2                    | end stroke
b) 2, roundDivs         |    (and end cap fill, for round)

Notes:
* rows starting with 'a)' denote the Butt or Square cap vertex use, 'b)' denote Round

* 'middlePts' is (number of points in the unclosed input vertex list, minus 2) times two

* 'roundDivs' or 'rD' is the number of extra vertices (beyond the minimum of 2) that define the
        round cap's shape, and is at least two. This will increase with cap size to sufficiently
        define the cap's level of tessellation.

* 'roundDivOffset' or 'rDO' is the point about halfway along the start cap's round perimeter, where
        the stream of vertices for the AA perimeter starts. By starting and ending the perimeter at
        this offset, the fill of the stroke is drawn from this point with minimal extra vertices.

This means the outer perimeter starts at:
    outerIndex = (2) OR (2 + 2 * roundDivOff)
the inner perimeter (since it is filled in reverse) starts at:
    innerIndex = outerIndex + (4 * middlePts) + ((4) OR (4 + 2 * roundDivs)) - 1
the stroke starts at:
    strokeIndex = innerIndex + 1 + ((6) OR (6 + 3 * roundDivs - 2 * roundDivOffset))

The total needed allocated space is either:
    2 + 4 + 6 + 2 + 3 * (2 * middlePts) = 14 + 6 * middlePts = 2 + 6 * pts
or, for rounded caps:
    (2 + 2 * rDO) + (4 + 2 * rD) + (2 * (rD - rDO + 1)
            + roundDivs + 4) + (2 + roundDivs) + 3 * (2 * middlePts)
    = 14 + 6 * middlePts + 6 * roundDivs
    = 2 + 6 * pts + 6 * roundDivs
 */
void getStrokeVerticesFromUnclosedVerticesAA(const PaintInfo& paintInfo,
        const std::vector<Vertex>& vertices, VertexBuffer& vertexBuffer) {

    const int extra = paintInfo.capExtraDivisions();
    const int allocSize = 6 * vertices.size() + 2 + 6 * extra;

    AlphaVertex* buffer = vertexBuffer.alloc<AlphaVertex>(allocSize);

    const int extraOffset = (extra + 1) / 2;
    int offset = 2 * (vertices.size() - 2);
    // there is no outer/inner here, using them for consistency with below approach
    int currentAAOuterIndex = 2 + 2 * extraOffset;
    int currentAAInnerIndex = currentAAOuterIndex + (2 * offset) + 3 + (2 * extra);
    int currentStrokeIndex = currentAAInnerIndex + 7 + (3 * extra - 2 * extraOffset);

    const Vertex* last = &(vertices[0]);
    const Vertex* current = &(vertices[1]);
    Vector2 lastNormal = {current->y - last->y, last->x - current->x};
    lastNormal.normalize();

    // TODO: use normal from bezier traversal for cap, instead of from vertices
    storeCapAA(paintInfo, vertices, buffer, true, lastNormal, offset);

    for (unsigned int i = 1; i < vertices.size() - 1; i++) {
        const Vertex* next = &(vertices[i + 1]);
        Vector2 nextNormal = {next->y - current->y, current->x - next->x};
        nextNormal.normalize();

        Vector2 totalOffset = totalOffsetFromNormals(lastNormal, nextNormal);
        Vector2 AAOffset = paintInfo.deriveAAOffset(totalOffset);

        Vector2 innerOffset = totalOffset;
        paintInfo.scaleOffsetForStrokeWidth(innerOffset);
        Vector2 outerOffset = innerOffset + AAOffset;
        innerOffset -= AAOffset;

        AlphaVertex::set(&buffer[currentAAOuterIndex++],
                current->x + outerOffset.x,
                current->y + outerOffset.y,
                0.0f);
        AlphaVertex::set(&buffer[currentAAOuterIndex++],
                current->x + innerOffset.x,
                current->y + innerOffset.y,
                paintInfo.maxAlpha);

        AlphaVertex::set(&buffer[currentStrokeIndex++],
                current->x + innerOffset.x,
                current->y + innerOffset.y,
                paintInfo.maxAlpha);
        AlphaVertex::set(&buffer[currentStrokeIndex++],
                current->x - innerOffset.x,
                current->y - innerOffset.y,
                paintInfo.maxAlpha);

        AlphaVertex::set(&buffer[currentAAInnerIndex--],
                current->x - innerOffset.x,
                current->y - innerOffset.y,
                paintInfo.maxAlpha);
        AlphaVertex::set(&buffer[currentAAInnerIndex--],
                current->x - outerOffset.x,
                current->y - outerOffset.y,
                0.0f);

        current = next;
        lastNormal = nextNormal;
    }

    // TODO: use normal from bezier traversal for cap, instead of from vertices
    storeCapAA(paintInfo, vertices, buffer, false, lastNormal, offset);

    DEBUG_DUMP_ALPHA_BUFFER();
}


void getStrokeVerticesFromPerimeterAA(const PaintInfo& paintInfo,
        const std::vector<Vertex>& perimeter, VertexBuffer& vertexBuffer) {
    AlphaVertex* buffer = vertexBuffer.alloc<AlphaVertex>(6 * perimeter.size() + 8);

    int offset = 2 * perimeter.size() + 3;
    int currentAAOuterIndex = 0;
    int currentStrokeIndex = offset;
    int currentAAInnerIndex = offset * 2;

    const Vertex* last = &(perimeter[perimeter.size() - 1]);
    const Vertex* current = &(perimeter[0]);
    Vector2 lastNormal = {current->y - last->y, last->x - current->x};
    lastNormal.normalize();
    for (unsigned int i = 0; i < perimeter.size(); i++) {
        const Vertex* next = &(perimeter[i + 1 >= perimeter.size() ? 0 : i + 1]);
        Vector2 nextNormal = {next->y - current->y, current->x - next->x};
        nextNormal.normalize();

        Vector2 totalOffset = totalOffsetFromNormals(lastNormal, nextNormal);
        Vector2 AAOffset = paintInfo.deriveAAOffset(totalOffset);

        Vector2 innerOffset = totalOffset;
        paintInfo.scaleOffsetForStrokeWidth(innerOffset);
        Vector2 outerOffset = innerOffset + AAOffset;
        innerOffset -= AAOffset;

        AlphaVertex::set(&buffer[currentAAOuterIndex++],
                current->x + outerOffset.x,
                current->y + outerOffset.y,
                0.0f);
        AlphaVertex::set(&buffer[currentAAOuterIndex++],
                current->x + innerOffset.x,
                current->y + innerOffset.y,
                paintInfo.maxAlpha);

        AlphaVertex::set(&buffer[currentStrokeIndex++],
                current->x + innerOffset.x,
                current->y + innerOffset.y,
                paintInfo.maxAlpha);
        AlphaVertex::set(&buffer[currentStrokeIndex++],
                current->x - innerOffset.x,
                current->y - innerOffset.y,
                paintInfo.maxAlpha);

        AlphaVertex::set(&buffer[currentAAInnerIndex++],
                current->x - innerOffset.x,
                current->y - innerOffset.y,
                paintInfo.maxAlpha);
        AlphaVertex::set(&buffer[currentAAInnerIndex++],
                current->x - outerOffset.x,
                current->y - outerOffset.y,
                0.0f);

        current = next;
        lastNormal = nextNormal;
    }

    // wrap each strip around to beginning, creating degenerate tris to bridge strips
    buffer[currentAAOuterIndex++] = buffer[0];
    buffer[currentAAOuterIndex++] = buffer[1];
    buffer[currentAAOuterIndex++] = buffer[1];

    buffer[currentStrokeIndex++] = buffer[offset];
    buffer[currentStrokeIndex++] = buffer[offset + 1];
    buffer[currentStrokeIndex++] = buffer[offset + 1];

    buffer[currentAAInnerIndex++] = buffer[2 * offset];
    buffer[currentAAInnerIndex++] = buffer[2 * offset + 1];
    // don't need to create last degenerate tri

    DEBUG_DUMP_ALPHA_BUFFER();
}

void PathTessellator::tessellatePath(const SkPath &path, const SkPaint* paint,
        const mat4& transform, VertexBuffer& vertexBuffer) {
    ATRACE_CALL();

    const PaintInfo paintInfo(paint, transform);

    std::vector<Vertex> tempVertices;
    float threshInvScaleX = paintInfo.inverseScaleX;
    float threshInvScaleY = paintInfo.inverseScaleY;
    if (paintInfo.style == SkPaint::kStroke_Style) {
        // alter the bezier recursion threshold values we calculate in order to compensate for
        // expansion done after the path vertices are found
        SkRect bounds = path.getBounds();
        if (!bounds.isEmpty()) {
            threshInvScaleX *= bounds.width() / (bounds.width() + paint->getStrokeWidth());
            threshInvScaleY *= bounds.height() / (bounds.height() + paint->getStrokeWidth());
        }
    }

    // force close if we're filling the path, since fill path expects closed perimeter.
    bool forceClose = paintInfo.style != SkPaint::kStroke_Style;
    PathApproximationInfo approximationInfo(threshInvScaleX, threshInvScaleY,
            OUTLINE_REFINE_THRESHOLD);
    bool wasClosed = approximatePathOutlineVertices(path, forceClose,
            approximationInfo, tempVertices);

    if (!tempVertices.size()) {
        // path was empty, return without allocating vertex buffer
        return;
    }

#if VERTEX_DEBUG
    for (unsigned int i = 0; i < tempVertices.size(); i++) {
        ALOGD("orig path: point at %f %f",
                tempVertices[i].x, tempVertices[i].y);
    }
#endif

    if (paintInfo.style == SkPaint::kStroke_Style) {
        if (!paintInfo.isAA) {
            if (wasClosed) {
                getStrokeVerticesFromPerimeter(paintInfo, tempVertices, vertexBuffer);
            } else {
                getStrokeVerticesFromUnclosedVertices(paintInfo, tempVertices, vertexBuffer);
            }

        } else {
            if (wasClosed) {
                getStrokeVerticesFromPerimeterAA(paintInfo, tempVertices, vertexBuffer);
            } else {
                getStrokeVerticesFromUnclosedVerticesAA(paintInfo, tempVertices, vertexBuffer);
            }
        }
    } else {
        // For kStrokeAndFill style, the path should be adjusted externally.
        // It will be treated as a fill here.
        if (!paintInfo.isAA) {
            getFillVerticesFromPerimeter(tempVertices, vertexBuffer);
        } else {
            getFillVerticesFromPerimeterAA(paintInfo, tempVertices, vertexBuffer);
        }
    }

    Rect bounds(path.getBounds());
    paintInfo.expandBoundsForStroke(&bounds);
    vertexBuffer.setBounds(bounds);
    vertexBuffer.setMeshFeatureFlags(paintInfo.isAA ? VertexBuffer::kAlpha : VertexBuffer::kNone);
}

template <class TYPE>
static void instanceVertices(VertexBuffer& srcBuffer, VertexBuffer& dstBuffer,
        const float* points, int count, Rect& bounds) {
    bounds.set(points[0], points[1], points[0], points[1]);

    int numPoints = count / 2;
    int verticesPerPoint = srcBuffer.getVertexCount();
    dstBuffer.alloc<TYPE>(numPoints * verticesPerPoint + (numPoints - 1) * 2);

    for (int i = 0; i < count; i += 2) {
        bounds.expandToCover(points[i + 0], points[i + 1]);
        dstBuffer.copyInto<TYPE>(srcBuffer, points[i + 0], points[i + 1]);
    }
    dstBuffer.createDegenerateSeparators<TYPE>(verticesPerPoint);
}

void PathTessellator::tessellatePoints(const float* points, int count, const SkPaint* paint,
        const mat4& transform, VertexBuffer& vertexBuffer) {
    const PaintInfo paintInfo(paint, transform);

    // determine point shape
    SkPath path;
    float radius = paintInfo.halfStrokeWidth;
    if (radius == 0.0f) radius = 0.5f;

    if (paintInfo.cap == SkPaint::kRound_Cap) {
        path.addCircle(0, 0, radius);
    } else {
        path.addRect(-radius, -radius, radius, radius);
    }

    // calculate outline
    std::vector<Vertex> outlineVertices;
    PathApproximationInfo approximationInfo(paintInfo.inverseScaleX, paintInfo.inverseScaleY,
            OUTLINE_REFINE_THRESHOLD);
    approximatePathOutlineVertices(path, true, approximationInfo, outlineVertices);

    if (!outlineVertices.size()) return;

    Rect bounds;
    // tessellate, then duplicate outline across points
    VertexBuffer tempBuffer;
    if (!paintInfo.isAA) {
        getFillVerticesFromPerimeter(outlineVertices, tempBuffer);
        instanceVertices<Vertex>(tempBuffer, vertexBuffer, points, count, bounds);
    } else {
        // note: pass maxAlpha directly, since we want fill to be alpha modulated
        getFillVerticesFromPerimeterAA(paintInfo, outlineVertices, tempBuffer, paintInfo.maxAlpha);
        instanceVertices<AlphaVertex>(tempBuffer, vertexBuffer, points, count, bounds);
    }

    // expand bounds from vertex coords to pixel data
    paintInfo.expandBoundsForStroke(&bounds);
    vertexBuffer.setBounds(bounds);
    vertexBuffer.setMeshFeatureFlags(paintInfo.isAA ? VertexBuffer::kAlpha : VertexBuffer::kNone);
}

void PathTessellator::tessellateLines(const float* points, int count, const SkPaint* paint,
        const mat4& transform, VertexBuffer& vertexBuffer) {
    ATRACE_CALL();
    const PaintInfo paintInfo(paint, transform);

    const int extra = paintInfo.capExtraDivisions();
    int numLines = count / 4;
    int lineAllocSize;
    // pre-allocate space for lines in the buffer, and degenerate tris in between
    if (paintInfo.isAA) {
        lineAllocSize = 6 * (2) + 2 + 6 * extra;
        vertexBuffer.alloc<AlphaVertex>(numLines * lineAllocSize + (numLines - 1) * 2);
    } else {
        lineAllocSize = 2 * ((2) + extra);
        vertexBuffer.alloc<Vertex>(numLines * lineAllocSize + (numLines - 1) * 2);
    }

    std::vector<Vertex> tempVertices(2);
    Vertex* tempVerticesData = &tempVertices.front();
    Rect bounds;
    bounds.set(points[0], points[1], points[0], points[1]);
    for (int i = 0; i < count; i += 4) {
        Vertex::set(&(tempVerticesData[0]), points[i + 0], points[i + 1]);
        Vertex::set(&(tempVerticesData[1]), points[i + 2], points[i + 3]);

        if (paintInfo.isAA) {
            getStrokeVerticesFromUnclosedVerticesAA(paintInfo, tempVertices, vertexBuffer);
        } else {
            getStrokeVerticesFromUnclosedVertices(paintInfo, tempVertices, vertexBuffer);
        }

        // calculate bounds
        bounds.expandToCover(tempVerticesData[0].x, tempVerticesData[0].y);
        bounds.expandToCover(tempVerticesData[1].x, tempVerticesData[1].y);
    }

    // since multiple objects tessellated into buffer, separate them with degen tris
    if (paintInfo.isAA) {
        vertexBuffer.createDegenerateSeparators<AlphaVertex>(lineAllocSize);
    } else {
        vertexBuffer.createDegenerateSeparators<Vertex>(lineAllocSize);
    }

    // expand bounds from vertex coords to pixel data
    paintInfo.expandBoundsForStroke(&bounds);
    vertexBuffer.setBounds(bounds);
    vertexBuffer.setMeshFeatureFlags(paintInfo.isAA ? VertexBuffer::kAlpha : VertexBuffer::kNone);
}

///////////////////////////////////////////////////////////////////////////////
// Simple path line approximation
///////////////////////////////////////////////////////////////////////////////

bool PathTessellator::approximatePathOutlineVertices(const SkPath& path, float threshold,
        std::vector<Vertex>& outputVertices) {
    PathApproximationInfo approximationInfo(1.0f, 1.0f, threshold);
    return approximatePathOutlineVertices(path, true, approximationInfo, outputVertices);
}

class ClockwiseEnforcer {
public:
    void addPoint(const SkPoint& point) {
        double x = point.x();
        double y = point.y();

        if (initialized) {
            sum += (x + lastX) * (y - lastY);
        } else {
            initialized = true;
        }

        lastX = x;
        lastY = y;
    }
    void reverseVectorIfNotClockwise(std::vector<Vertex>& vertices) {
        if (sum < 0) {
            // negative sum implies CounterClockwise
            const int size = vertices.size();
            for (int i = 0; i < size / 2; i++) {
                Vertex tmp = vertices[i];
                int k = size - 1 - i;
                vertices[i] = vertices[k];
                vertices[k] = tmp;
            }
        }
    }
private:
    bool initialized = false;
    double lastX = 0;
    double lastY = 0;
    double sum = 0;
};

bool PathTessellator::approximatePathOutlineVertices(const SkPath& path, bool forceClose,
        const PathApproximationInfo& approximationInfo, std::vector<Vertex>& outputVertices) {
    ATRACE_CALL();

    // TODO: to support joins other than sharp miter, join vertices should be labelled in the
    // perimeter, or resolved into more vertices. Reconsider forceClose-ing in that case.
    SkPath::Iter iter(path, forceClose);
    SkPoint pts[4];
    SkPath::Verb v;
    ClockwiseEnforcer clockwiseEnforcer;
    while (SkPath::kDone_Verb != (v = iter.next(pts))) {
            switch (v) {
            case SkPath::kMove_Verb:
                outputVertices.push_back(Vertex{pts[0].x(), pts[0].y()});
                ALOGV("Move to pos %f %f", pts[0].x(), pts[0].y());
                clockwiseEnforcer.addPoint(pts[0]);
                break;
            case SkPath::kClose_Verb:
                ALOGV("Close at pos %f %f", pts[0].x(), pts[0].y());
                clockwiseEnforcer.addPoint(pts[0]);
                break;
            case SkPath::kLine_Verb:
                ALOGV("kLine_Verb %f %f -> %f %f", pts[0].x(), pts[0].y(), pts[1].x(), pts[1].y());
                outputVertices.push_back(Vertex{pts[1].x(), pts[1].y()});
                clockwiseEnforcer.addPoint(pts[1]);
                break;
            case SkPath::kQuad_Verb:
                ALOGV("kQuad_Verb");
                recursiveQuadraticBezierVertices(
                        pts[0].x(), pts[0].y(),
                        pts[2].x(), pts[2].y(),
                        pts[1].x(), pts[1].y(),
                        approximationInfo, outputVertices);
                clockwiseEnforcer.addPoint(pts[1]);
                clockwiseEnforcer.addPoint(pts[2]);
                break;
            case SkPath::kCubic_Verb:
                ALOGV("kCubic_Verb");
                recursiveCubicBezierVertices(
                        pts[0].x(), pts[0].y(),
                        pts[1].x(), pts[1].y(),
                        pts[3].x(), pts[3].y(),
                        pts[2].x(), pts[2].y(),
                        approximationInfo, outputVertices);
                clockwiseEnforcer.addPoint(pts[1]);
                clockwiseEnforcer.addPoint(pts[2]);
                clockwiseEnforcer.addPoint(pts[3]);
                break;
            case SkPath::kConic_Verb: {
                ALOGV("kConic_Verb");
                SkAutoConicToQuads converter;
                const SkPoint* quads = converter.computeQuads(pts, iter.conicWeight(),
                        approximationInfo.thresholdForConicQuads);
                for (int i = 0; i < converter.countQuads(); ++i) {
                    const int offset = 2 * i;
                    recursiveQuadraticBezierVertices(
                            quads[offset].x(), quads[offset].y(),
                            quads[offset+2].x(), quads[offset+2].y(),
                            quads[offset+1].x(), quads[offset+1].y(),
                            approximationInfo, outputVertices);
                }
                clockwiseEnforcer.addPoint(pts[1]);
                clockwiseEnforcer.addPoint(pts[2]);
                break;
            }
            default:
                break;
            }
    }

    bool wasClosed = false;
    int size = outputVertices.size();
    if (size >= 2 && outputVertices[0].x == outputVertices[size - 1].x &&
            outputVertices[0].y == outputVertices[size - 1].y) {
        outputVertices.pop_back();
        wasClosed = true;
    }

    // ensure output vector is clockwise
    clockwiseEnforcer.reverseVectorIfNotClockwise(outputVertices);
    return wasClosed;
}

///////////////////////////////////////////////////////////////////////////////
// Bezier approximation
//
// All the inputs and outputs here are in path coordinates.
// We convert the error threshold from screen coordinates into path coordinates.
///////////////////////////////////////////////////////////////////////////////

// Get a threshold in path coordinates, by scaling the thresholdSquared from screen coordinates.
// TODO: Document the math behind this algorithm.
static inline float getThreshold(const PathApproximationInfo& info, float dx, float dy) {
    // multiplying by sqrInvScaleY/X equivalent to multiplying in dimensional scale factors
    float scale = (dx * dx * info.sqrInvScaleY + dy * dy * info.sqrInvScaleX);
    return info.thresholdSquared * scale;
}

void PathTessellator::recursiveCubicBezierVertices(
        float p1x, float p1y, float c1x, float c1y,
        float p2x, float p2y, float c2x, float c2y,
        const PathApproximationInfo& approximationInfo,
        std::vector<Vertex>& outputVertices, int depth) {
    float dx = p2x - p1x;
    float dy = p2y - p1y;
    float d1 = fabs((c1x - p2x) * dy - (c1y - p2y) * dx);
    float d2 = fabs((c2x - p2x) * dy - (c2y - p2y) * dx);
    float d = d1 + d2;

    if (depth >= MAX_DEPTH
            || d * d <= getThreshold(approximationInfo, dx, dy)) {
        // below thresh, draw line by adding endpoint
        outputVertices.push_back(Vertex{p2x, p2y});
    } else {
        float p1c1x = (p1x + c1x) * 0.5f;
        float p1c1y = (p1y + c1y) * 0.5f;
        float p2c2x = (p2x + c2x) * 0.5f;
        float p2c2y = (p2y + c2y) * 0.5f;

        float c1c2x = (c1x + c2x) * 0.5f;
        float c1c2y = (c1y + c2y) * 0.5f;

        float p1c1c2x = (p1c1x + c1c2x) * 0.5f;
        float p1c1c2y = (p1c1y + c1c2y) * 0.5f;

        float p2c1c2x = (p2c2x + c1c2x) * 0.5f;
        float p2c1c2y = (p2c2y + c1c2y) * 0.5f;

        float mx = (p1c1c2x + p2c1c2x) * 0.5f;
        float my = (p1c1c2y + p2c1c2y) * 0.5f;

        recursiveCubicBezierVertices(
                p1x, p1y, p1c1x, p1c1y,
                mx, my, p1c1c2x, p1c1c2y,
                approximationInfo, outputVertices, depth + 1);
        recursiveCubicBezierVertices(
                mx, my, p2c1c2x, p2c1c2y,
                p2x, p2y, p2c2x, p2c2y,
                approximationInfo, outputVertices, depth + 1);
    }
}

void PathTessellator::recursiveQuadraticBezierVertices(
        float ax, float ay,
        float bx, float by,
        float cx, float cy,
        const PathApproximationInfo& approximationInfo,
        std::vector<Vertex>& outputVertices, int depth) {
    float dx = bx - ax;
    float dy = by - ay;
    // d is the cross product of vector (B-A) and (C-B).
    float d = (cx - bx) * dy - (cy - by) * dx;

    if (depth >= MAX_DEPTH
            || d * d <= getThreshold(approximationInfo, dx, dy)) {
        // below thresh, draw line by adding endpoint
        outputVertices.push_back(Vertex{bx, by});
    } else {
        float acx = (ax + cx) * 0.5f;
        float bcx = (bx + cx) * 0.5f;
        float acy = (ay + cy) * 0.5f;
        float bcy = (by + cy) * 0.5f;

        // midpoint
        float mx = (acx + bcx) * 0.5f;
        float my = (acy + bcy) * 0.5f;

        recursiveQuadraticBezierVertices(ax, ay, mx, my, acx, acy,
                approximationInfo, outputVertices, depth + 1);
        recursiveQuadraticBezierVertices(mx, my, bx, by, bcx, bcy,
                approximationInfo, outputVertices, depth + 1);
    }
}

}; // namespace uirenderer
}; // namespace android
