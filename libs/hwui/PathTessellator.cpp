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

#define LOG_TAG "PathTessellator"
#define LOG_NDEBUG 1
#define ATRACE_TAG ATRACE_TAG_GRAPHICS

#define VERTEX_DEBUG 0

#if VERTEX_DEBUG
#define DEBUG_DUMP_ALPHA_BUFFER() \
    for (unsigned int i = 0; i < vertexBuffer.getSize(); i++) { \
        ALOGD("point %d at %f %f, alpha %f", \
        i, buffer[i].position[0], buffer[i].position[1], buffer[i].alpha); \
    }
#define DEBUG_DUMP_BUFFER() \
    for (unsigned int i = 0; i < vertexBuffer.getSize(); i++) { \
        ALOGD("point %d at %f %f", i, buffer[i].position[0], buffer[i].position[1]); \
    }
#else
#define DEBUG_DUMP_ALPHA_BUFFER()
#define DEBUG_DUMP_BUFFER()
#endif

#include <SkPath.h>
#include <SkPaint.h>

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <utils/Log.h>
#include <utils/Trace.h>

#include "PathTessellator.h"
#include "Matrix.h"
#include "Vector.h"
#include "Vertex.h"

namespace android {
namespace uirenderer {

#define THRESHOLD 0.5f
#define ROUND_CAP_THRESH 0.25f
#define PI 3.1415926535897932f

void PathTessellator::expandBoundsForStroke(SkRect& bounds, const SkPaint* paint,
        bool forceExpand) {
    if (forceExpand || paint->getStyle() != SkPaint::kFill_Style) {
        float outset = paint->getStrokeWidth() * 0.5f;
        if (outset == 0) outset = 0.5f; // account for hairline
        bounds.outset(outset, outset);
    }
}

inline static void copyVertex(Vertex* destPtr, const Vertex* srcPtr) {
    Vertex::set(destPtr, srcPtr->position[0], srcPtr->position[1]);
}

inline static void copyAlphaVertex(AlphaVertex* destPtr, const AlphaVertex* srcPtr) {
    AlphaVertex::set(destPtr, srcPtr->position[0], srcPtr->position[1], srcPtr->alpha);
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
inline static vec2 totalOffsetFromNormals(const vec2& normalA, const vec2& normalB) {
    return (normalA + normalB) / (1 + fabs(normalA.dot(normalB)));
}

/**
 * Structure used for storing useful information about the SkPaint and scale used for tessellating
 */
struct PaintInfo {
public:
    PaintInfo(const SkPaint* paint, const mat4 *transform) :
            style(paint->getStyle()), cap(paint->getStrokeCap()), isAA(paint->isAntiAlias()),
            inverseScaleX(1.0f), inverseScaleY(1.0f),
            halfStrokeWidth(paint->getStrokeWidth() * 0.5f), maxAlpha(1.0f) {
        // compute inverse scales
        if (CC_UNLIKELY(!transform->isPureTranslate())) {
            float m00 = transform->data[Matrix4::kScaleX];
            float m01 = transform->data[Matrix4::kSkewY];
            float m10 = transform->data[Matrix4::kSkewX];
            float m11 = transform->data[Matrix4::kScaleY];
            float scaleX = sqrt(m00 * m00 + m01 * m01);
            float scaleY = sqrt(m10 * m10 + m11 * m11);
            inverseScaleX = (scaleX != 0) ? (1.0f / scaleX) : 1.0f;
            inverseScaleY = (scaleY != 0) ? (1.0f / scaleY) : 1.0f;
        }

        if (isAA && halfStrokeWidth != 0 && inverseScaleX == inverseScaleY &&
                2 * halfStrokeWidth < inverseScaleX) {
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

    inline void scaleOffsetForStrokeWidth(vec2& offset) const {
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
    inline vec2 deriveAAOffset(const vec2& offset) const {
        return vec2(offset.x * 0.5f * inverseScaleX,
            offset.y * 0.5f * inverseScaleY);
    }

    /**
     * Returns the number of cap divisions beyond the minimum 2 (kButt_Cap/kSquareCap will return 0)
     * Should only be used when stroking and drawing caps
     */
    inline int capExtraDivisions() const {
        if (cap == SkPaint::kRound_Cap) {
            if (halfStrokeWidth == 0.0f) return 2;

            // ROUND_CAP_THRESH is the maximum error for polygonal approximation of the round cap
            const float errConst = (-ROUND_CAP_THRESH / halfStrokeWidth + 1);
            const float targetCosVal = 2 * errConst * errConst - 1;
            int neededDivisions = (int)(ceilf(PI / acos(targetCosVal)/2)) * 2;
            return neededDivisions;
        }
        return 0;
    }
};

void getFillVerticesFromPerimeter(const Vector<Vertex>& perimeter, VertexBuffer& vertexBuffer) {
    Vertex* buffer = vertexBuffer.alloc<Vertex>(perimeter.size());

    int currentIndex = 0;
    // zig zag between all previous points on the inside of the hull to create a
    // triangle strip that fills the hull
    int srcAindex = 0;
    int srcBindex = perimeter.size() - 1;
    while (srcAindex <= srcBindex) {
        copyVertex(&buffer[currentIndex++], &perimeter[srcAindex]);
        if (srcAindex == srcBindex) break;
        copyVertex(&buffer[currentIndex++], &perimeter[srcBindex]);
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
void getStrokeVerticesFromPerimeter(const PaintInfo& paintInfo, const Vector<Vertex>& perimeter,
        VertexBuffer& vertexBuffer) {
    Vertex* buffer = vertexBuffer.alloc<Vertex>(perimeter.size() * 2 + 2);

    int currentIndex = 0;
    const Vertex* last = &(perimeter[perimeter.size() - 1]);
    const Vertex* current = &(perimeter[0]);
    vec2 lastNormal(current->position[1] - last->position[1],
            last->position[0] - current->position[0]);
    lastNormal.normalize();
    for (unsigned int i = 0; i < perimeter.size(); i++) {
        const Vertex* next = &(perimeter[i + 1 >= perimeter.size() ? 0 : i + 1]);
        vec2 nextNormal(next->position[1] - current->position[1],
                current->position[0] - next->position[0]);
        nextNormal.normalize();

        vec2 totalOffset = totalOffsetFromNormals(lastNormal, nextNormal);
        paintInfo.scaleOffsetForStrokeWidth(totalOffset);

        Vertex::set(&buffer[currentIndex++],
                current->position[0] + totalOffset.x,
                current->position[1] + totalOffset.y);

        Vertex::set(&buffer[currentIndex++],
                current->position[0] - totalOffset.x,
                current->position[1] - totalOffset.y);

        last = current;
        current = next;
        lastNormal = nextNormal;
    }

    // wrap around to beginning
    copyVertex(&buffer[currentIndex++], &buffer[0]);
    copyVertex(&buffer[currentIndex++], &buffer[1]);

    DEBUG_DUMP_BUFFER();
}

static inline void storeBeginEnd(const PaintInfo& paintInfo, const Vertex& center,
        const vec2& normal, Vertex* buffer, int& currentIndex, bool begin) {
    vec2 strokeOffset = normal;
    paintInfo.scaleOffsetForStrokeWidth(strokeOffset);

    vec2 referencePoint(center.position[0], center.position[1]);
    if (paintInfo.cap == SkPaint::kSquare_Cap) {
        referencePoint += vec2(-strokeOffset.y, strokeOffset.x) * (begin ? -1 : 1);
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
        const Vector<Vertex>& vertices, VertexBuffer& vertexBuffer) {
    const int extra = paintInfo.capExtraDivisions();
    const int allocSize = (vertices.size() + extra) * 2;
    Vertex* buffer = vertexBuffer.alloc<Vertex>(allocSize);

    const int lastIndex = vertices.size() - 1;
    if (extra > 0) {
        // tessellate both round caps
        float beginTheta = atan2(
                    - (vertices[0].position[0] - vertices[1].position[0]),
                    vertices[0].position[1] - vertices[1].position[1]);
        float endTheta = atan2(
                    - (vertices[lastIndex].position[0] - vertices[lastIndex - 1].position[0]),
                    vertices[lastIndex].position[1] - vertices[lastIndex - 1].position[1]);
        const float dTheta = PI / (extra + 1);
        const float radialScale = 2.0f / (1 + cos(dTheta));

        int capOffset;
        for (int i = 0; i < extra; i++) {
            if (i < extra / 2) {
                capOffset = extra - 2 * i - 1;
            } else {
                capOffset = 2 * i - extra;
            }

            beginTheta += dTheta;
            vec2 beginRadialOffset(cos(beginTheta), sin(beginTheta));
            paintInfo.scaleOffsetForStrokeWidth(beginRadialOffset);
            Vertex::set(&buffer[capOffset],
                    vertices[0].position[0] + beginRadialOffset.x,
                    vertices[0].position[1] + beginRadialOffset.y);

            endTheta += dTheta;
            vec2 endRadialOffset(cos(endTheta), sin(endTheta));
            paintInfo.scaleOffsetForStrokeWidth(endRadialOffset);
            Vertex::set(&buffer[allocSize - 1 - capOffset],
                    vertices[lastIndex].position[0] + endRadialOffset.x,
                    vertices[lastIndex].position[1] + endRadialOffset.y);
        }
    }

    int currentIndex = extra;
    const Vertex* last = &(vertices[0]);
    const Vertex* current = &(vertices[1]);
    vec2 lastNormal(current->position[1] - last->position[1],
                last->position[0] - current->position[0]);
    lastNormal.normalize();

    storeBeginEnd(paintInfo, vertices[0], lastNormal, buffer, currentIndex, true);

    for (unsigned int i = 1; i < vertices.size() - 1; i++) {
        const Vertex* next = &(vertices[i + 1]);
        vec2 nextNormal(next->position[1] - current->position[1],
                current->position[0] - next->position[0]);
        nextNormal.normalize();

        vec2 strokeOffset  = totalOffsetFromNormals(lastNormal, nextNormal);
        paintInfo.scaleOffsetForStrokeWidth(strokeOffset);

        vec2 center(current->position[0], current->position[1]);
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
void getFillVerticesFromPerimeterAA(const PaintInfo& paintInfo, const Vector<Vertex>& perimeter,
        VertexBuffer& vertexBuffer) {
    AlphaVertex* buffer = vertexBuffer.alloc<AlphaVertex>(perimeter.size() * 3 + 2);

    // generate alpha points - fill Alpha vertex gaps in between each point with
    // alpha 0 vertex, offset by a scaled normal.
    int currentIndex = 0;
    const Vertex* last = &(perimeter[perimeter.size() - 1]);
    const Vertex* current = &(perimeter[0]);
    vec2 lastNormal(current->position[1] - last->position[1],
            last->position[0] - current->position[0]);
    lastNormal.normalize();
    for (unsigned int i = 0; i < perimeter.size(); i++) {
        const Vertex* next = &(perimeter[i + 1 >= perimeter.size() ? 0 : i + 1]);
        vec2 nextNormal(next->position[1] - current->position[1],
                current->position[0] - next->position[0]);
        nextNormal.normalize();

        // AA point offset from original point is that point's normal, such that each side is offset
        // by .5 pixels
        vec2 totalOffset = paintInfo.deriveAAOffset(totalOffsetFromNormals(lastNormal, nextNormal));

        AlphaVertex::set(&buffer[currentIndex++],
                current->position[0] + totalOffset.x,
                current->position[1] + totalOffset.y,
                0.0f);
        AlphaVertex::set(&buffer[currentIndex++],
                current->position[0] - totalOffset.x,
                current->position[1] - totalOffset.y,
                1.0f);

        last = current;
        current = next;
        lastNormal = nextNormal;
    }

    // wrap around to beginning
    copyAlphaVertex(&buffer[currentIndex++], &buffer[0]);
    copyAlphaVertex(&buffer[currentIndex++], &buffer[1]);

    // zig zag between all previous points on the inside of the hull to create a
    // triangle strip that fills the hull, repeating the first inner point to
    // create degenerate tris to start inside path
    int srcAindex = 0;
    int srcBindex = perimeter.size() - 1;
    while (srcAindex <= srcBindex) {
        copyAlphaVertex(&buffer[currentIndex++], &buffer[srcAindex * 2 + 1]);
        if (srcAindex == srcBindex) break;
        copyAlphaVertex(&buffer[currentIndex++], &buffer[srcBindex * 2 + 1]);
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
inline static void storeCapAA(const PaintInfo& paintInfo, const Vector<Vertex>& vertices,
        AlphaVertex* buffer, bool isFirst, vec2 normal, int offset) {
    const int extra = paintInfo.capExtraDivisions();
    const int extraOffset = (extra + 1) / 2;
    const int capIndex = isFirst
            ? 2 * offset + 6 + 2 * (extra + extraOffset)
            : offset + 2 + 2 * extraOffset;
    if (isFirst) normal *= -1;

    // TODO: this normal should be scaled by radialScale if extra != 0, see totalOffsetFromNormals()
    vec2 AAOffset = paintInfo.deriveAAOffset(normal);

    vec2 strokeOffset = normal;
    paintInfo.scaleOffsetForStrokeWidth(strokeOffset);
    vec2 outerOffset = strokeOffset + AAOffset;
    vec2 innerOffset = strokeOffset - AAOffset;

    vec2 capAAOffset;
    if (paintInfo.cap != SkPaint::kRound_Cap) {
        // if the cap is square or butt, the inside primary cap vertices will be inset in two
        // directions - both normal to the stroke, and parallel to it.
        capAAOffset = vec2(-AAOffset.y, AAOffset.x);
    }

    // determine referencePoint, the center point for the 4 primary cap vertices
    const Vertex* point = isFirst ? vertices.begin() : (vertices.end() - 1);
    vec2 referencePoint(point->position[0], point->position[1]);
    if (paintInfo.cap == SkPaint::kSquare_Cap) {
        // To account for square cap, move the primary cap vertices (that create the AA edge) by the
        // stroke offset vector (rotated to be parallel to the stroke)
        referencePoint += vec2(-strokeOffset.y, strokeOffset.x);
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

            vec2 radialOffset(cos(theta), sin(theta));

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
                copyAlphaVertex(&buffer[0], &buffer[capPerimIndex - 2]);
                copyAlphaVertex(&buffer[1], &buffer[capPerimIndex - 1]);

                capPerimIndex = 2; // start writing the rest of the round cap at index 2
            }
        }

        if (isFirst) {
            const int startCapFillIndex = capIndex + 2 * (extra - extraOffset) + 4;
            int capFillIndex = startCapFillIndex;
            for (int i = 0; i < extra + 2; i += 2) {
                copyAlphaVertex(&buffer[capFillIndex++], &buffer[1 + i]);
                // TODO: to support odd numbers of divisions, break here on the last iteration
                copyAlphaVertex(&buffer[capFillIndex++], &buffer[startCapFillIndex - 3 - i]);
            }
        } else {
            int capFillIndex = 6 * vertices.size() + 2 + 6 * extra - (extra + 2);
            for (int i = 0; i < extra + 2; i += 2) {
                copyAlphaVertex(&buffer[capFillIndex++], &buffer[capIndex + 1 + i]);
                // TODO: to support odd numbers of divisions, break here on the last iteration
                copyAlphaVertex(&buffer[capFillIndex++], &buffer[capIndex + 3 + 2 * extra - i]);
            }
        }
        return;
    }
    if (isFirst) {
        copyAlphaVertex(&buffer[0], &buffer[postCapIndex + 2]);
        copyAlphaVertex(&buffer[1], &buffer[postCapIndex + 3]);
        copyAlphaVertex(&buffer[postCapIndex + 4], &buffer[1]); // degenerate tris (the only two!)
        copyAlphaVertex(&buffer[postCapIndex + 5], &buffer[postCapIndex + 1]);
    } else {
        copyAlphaVertex(&buffer[6 * vertices.size()], &buffer[postCapIndex + 1]);
        copyAlphaVertex(&buffer[6 * vertices.size() + 1], &buffer[postCapIndex + 3]);
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
        const Vector<Vertex>& vertices, VertexBuffer& vertexBuffer) {

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
    vec2 lastNormal(current->position[1] - last->position[1],
            last->position[0] - current->position[0]);
    lastNormal.normalize();

    // TODO: use normal from bezier traversal for cap, instead of from vertices
    storeCapAA(paintInfo, vertices, buffer, true, lastNormal, offset);

    for (unsigned int i = 1; i < vertices.size() - 1; i++) {
        const Vertex* next = &(vertices[i + 1]);
        vec2 nextNormal(next->position[1] - current->position[1],
                current->position[0] - next->position[0]);
        nextNormal.normalize();

        vec2 totalOffset = totalOffsetFromNormals(lastNormal, nextNormal);
        vec2 AAOffset = paintInfo.deriveAAOffset(totalOffset);

        vec2 innerOffset = totalOffset;
        paintInfo.scaleOffsetForStrokeWidth(innerOffset);
        vec2 outerOffset = innerOffset + AAOffset;
        innerOffset -= AAOffset;

        AlphaVertex::set(&buffer[currentAAOuterIndex++],
                current->position[0] + outerOffset.x,
                current->position[1] + outerOffset.y,
                0.0f);
        AlphaVertex::set(&buffer[currentAAOuterIndex++],
                current->position[0] + innerOffset.x,
                current->position[1] + innerOffset.y,
                paintInfo.maxAlpha);

        AlphaVertex::set(&buffer[currentStrokeIndex++],
                current->position[0] + innerOffset.x,
                current->position[1] + innerOffset.y,
                paintInfo.maxAlpha);
        AlphaVertex::set(&buffer[currentStrokeIndex++],
                current->position[0] - innerOffset.x,
                current->position[1] - innerOffset.y,
                paintInfo.maxAlpha);

        AlphaVertex::set(&buffer[currentAAInnerIndex--],
                current->position[0] - innerOffset.x,
                current->position[1] - innerOffset.y,
                paintInfo.maxAlpha);
        AlphaVertex::set(&buffer[currentAAInnerIndex--],
                current->position[0] - outerOffset.x,
                current->position[1] - outerOffset.y,
                0.0f);

        current = next;
        lastNormal = nextNormal;
    }

    // TODO: use normal from bezier traversal for cap, instead of from vertices
    storeCapAA(paintInfo, vertices, buffer, false, lastNormal, offset);

    DEBUG_DUMP_ALPHA_BUFFER();
}


void getStrokeVerticesFromPerimeterAA(const PaintInfo& paintInfo, const Vector<Vertex>& perimeter,
        VertexBuffer& vertexBuffer) {
    AlphaVertex* buffer = vertexBuffer.alloc<AlphaVertex>(6 * perimeter.size() + 8);

    int offset = 2 * perimeter.size() + 3;
    int currentAAOuterIndex = 0;
    int currentStrokeIndex = offset;
    int currentAAInnerIndex = offset * 2;

    const Vertex* last = &(perimeter[perimeter.size() - 1]);
    const Vertex* current = &(perimeter[0]);
    vec2 lastNormal(current->position[1] - last->position[1],
            last->position[0] - current->position[0]);
    lastNormal.normalize();
    for (unsigned int i = 0; i < perimeter.size(); i++) {
        const Vertex* next = &(perimeter[i + 1 >= perimeter.size() ? 0 : i + 1]);
        vec2 nextNormal(next->position[1] - current->position[1],
                current->position[0] - next->position[0]);
        nextNormal.normalize();

        vec2 totalOffset = totalOffsetFromNormals(lastNormal, nextNormal);
        vec2 AAOffset = paintInfo.deriveAAOffset(totalOffset);

        vec2 innerOffset = totalOffset;
        paintInfo.scaleOffsetForStrokeWidth(innerOffset);
        vec2 outerOffset = innerOffset + AAOffset;
        innerOffset -= AAOffset;

        AlphaVertex::set(&buffer[currentAAOuterIndex++],
                current->position[0] + outerOffset.x,
                current->position[1] + outerOffset.y,
                0.0f);
        AlphaVertex::set(&buffer[currentAAOuterIndex++],
                current->position[0] + innerOffset.x,
                current->position[1] + innerOffset.y,
                paintInfo.maxAlpha);

        AlphaVertex::set(&buffer[currentStrokeIndex++],
                current->position[0] + innerOffset.x,
                current->position[1] + innerOffset.y,
                paintInfo.maxAlpha);
        AlphaVertex::set(&buffer[currentStrokeIndex++],
                current->position[0] - innerOffset.x,
                current->position[1] - innerOffset.y,
                paintInfo.maxAlpha);

        AlphaVertex::set(&buffer[currentAAInnerIndex++],
                current->position[0] - innerOffset.x,
                current->position[1] - innerOffset.y,
                paintInfo.maxAlpha);
        AlphaVertex::set(&buffer[currentAAInnerIndex++],
                current->position[0] - outerOffset.x,
                current->position[1] - outerOffset.y,
                0.0f);

        last = current;
        current = next;
        lastNormal = nextNormal;
    }

    // wrap each strip around to beginning, creating degenerate tris to bridge strips
    copyAlphaVertex(&buffer[currentAAOuterIndex++], &buffer[0]);
    copyAlphaVertex(&buffer[currentAAOuterIndex++], &buffer[1]);
    copyAlphaVertex(&buffer[currentAAOuterIndex++], &buffer[1]);

    copyAlphaVertex(&buffer[currentStrokeIndex++], &buffer[offset]);
    copyAlphaVertex(&buffer[currentStrokeIndex++], &buffer[offset + 1]);
    copyAlphaVertex(&buffer[currentStrokeIndex++], &buffer[offset + 1]);

    copyAlphaVertex(&buffer[currentAAInnerIndex++], &buffer[2 * offset]);
    copyAlphaVertex(&buffer[currentAAInnerIndex++], &buffer[2 * offset + 1]);
    // don't need to create last degenerate tri

    DEBUG_DUMP_ALPHA_BUFFER();
}

void PathTessellator::tessellatePath(const SkPath &path, const SkPaint* paint,
        const mat4 *transform, VertexBuffer& vertexBuffer) {
    ATRACE_CALL();

    const PaintInfo paintInfo(paint, transform);

    Vector<Vertex> tempVertices;
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
    bool wasClosed = approximatePathOutlineVertices(path, forceClose,
            threshInvScaleX * threshInvScaleX, threshInvScaleY * threshInvScaleY, tempVertices);

    if (!tempVertices.size()) {
        // path was empty, return without allocating vertex buffer
        return;
    }

#if VERTEX_DEBUG
    for (unsigned int i = 0; i < tempVertices.size(); i++) {
        ALOGD("orig path: point at %f %f",
                tempVertices[i].position[0], tempVertices[i].position[1]);
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
}

static void expandRectToCoverVertex(SkRect& rect, float x, float y) {
    rect.fLeft = fminf(rect.fLeft, x);
    rect.fTop = fminf(rect.fTop, y);
    rect.fRight = fmaxf(rect.fRight, x);
    rect.fBottom = fmaxf(rect.fBottom, y);
}
static void expandRectToCoverVertex(SkRect& rect, const Vertex& vertex) {
    expandRectToCoverVertex(rect, vertex.position[0], vertex.position[1]);
}

template <class TYPE>
static void instanceVertices(VertexBuffer& srcBuffer, VertexBuffer& dstBuffer,
        const float* points, int count, SkRect& bounds) {
    bounds.set(points[0], points[1], points[0], points[1]);

    int numPoints = count / 2;
    int verticesPerPoint = srcBuffer.getVertexCount();
    dstBuffer.alloc<TYPE>(numPoints * verticesPerPoint + (numPoints - 1) * 2);

    for (int i = 0; i < count; i += 2) {
        expandRectToCoverVertex(bounds, points[i + 0], points[i + 1]);
        dstBuffer.copyInto<TYPE>(srcBuffer, points[i + 0], points[i + 1]);
    }
    dstBuffer.createDegenerateSeparators<TYPE>(verticesPerPoint);
}

void PathTessellator::tessellatePoints(const float* points, int count, SkPaint* paint,
        const mat4* transform, SkRect& bounds, VertexBuffer& vertexBuffer) {
    const PaintInfo paintInfo(paint, transform);

    // determine point shape
    SkPath path;
    float radius = paintInfo.halfStrokeWidth;
    if (radius == 0.0f) radius = 0.25f;

    if (paintInfo.cap == SkPaint::kRound_Cap) {
        path.addCircle(0, 0, radius);
    } else {
        path.addRect(-radius, -radius, radius, radius);
    }

    // calculate outline
    Vector<Vertex> outlineVertices;
    approximatePathOutlineVertices(path, true,
            paintInfo.inverseScaleX * paintInfo.inverseScaleX,
            paintInfo.inverseScaleY * paintInfo.inverseScaleY, outlineVertices);

    if (!outlineVertices.size()) return;

    // tessellate, then duplicate outline across points
    int numPoints = count / 2;
    VertexBuffer tempBuffer;
    if (!paintInfo.isAA) {
        getFillVerticesFromPerimeter(outlineVertices, tempBuffer);
        instanceVertices<Vertex>(tempBuffer, vertexBuffer, points, count, bounds);
    } else {
        getFillVerticesFromPerimeterAA(paintInfo, outlineVertices, tempBuffer);
        instanceVertices<AlphaVertex>(tempBuffer, vertexBuffer, points, count, bounds);
    }

    expandBoundsForStroke(bounds, paint, true); // force-expand bounds to incorporate stroke
}

void PathTessellator::tessellateLines(const float* points, int count, SkPaint* paint,
        const mat4* transform, SkRect& bounds, VertexBuffer& vertexBuffer) {
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

    Vector<Vertex> tempVertices;
    tempVertices.push();
    tempVertices.push();
    Vertex* tempVerticesData = tempVertices.editArray();
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
        expandRectToCoverVertex(bounds, tempVerticesData[0]);
        expandRectToCoverVertex(bounds, tempVerticesData[1]);
    }

    expandBoundsForStroke(bounds, paint, true); // force-expand bounds to incorporate stroke

    // since multiple objects tessellated into buffer, separate them with degen tris
    if (paintInfo.isAA) {
        vertexBuffer.createDegenerateSeparators<AlphaVertex>(lineAllocSize);
    } else {
        vertexBuffer.createDegenerateSeparators<Vertex>(lineAllocSize);
    }
}

///////////////////////////////////////////////////////////////////////////////
// Simple path line approximation
///////////////////////////////////////////////////////////////////////////////

void pushToVector(Vector<Vertex>& vertices, float x, float y) {
    // TODO: make this not yuck
    vertices.push();
    Vertex* newVertex = &(vertices.editArray()[vertices.size() - 1]);
    Vertex::set(newVertex, x, y);
}

bool PathTessellator::approximatePathOutlineVertices(const SkPath& path, bool forceClose,
        float sqrInvScaleX, float sqrInvScaleY, Vector<Vertex>& outputVertices) {
    ATRACE_CALL();

    // TODO: to support joins other than sharp miter, join vertices should be labelled in the
    // perimeter, or resolved into more vertices. Reconsider forceClose-ing in that case.
    SkPath::Iter iter(path, forceClose);
    SkPoint pts[4];
    SkPath::Verb v;
    while (SkPath::kDone_Verb != (v = iter.next(pts))) {
            switch (v) {
            case SkPath::kMove_Verb:
                pushToVector(outputVertices, pts[0].x(), pts[0].y());
                ALOGV("Move to pos %f %f", pts[0].x(), pts[0].y());
                break;
            case SkPath::kClose_Verb:
                ALOGV("Close at pos %f %f", pts[0].x(), pts[0].y());
                break;
            case SkPath::kLine_Verb:
                ALOGV("kLine_Verb %f %f -> %f %f", pts[0].x(), pts[0].y(), pts[1].x(), pts[1].y());
                pushToVector(outputVertices, pts[1].x(), pts[1].y());
                break;
            case SkPath::kQuad_Verb:
                ALOGV("kQuad_Verb");
                recursiveQuadraticBezierVertices(
                        pts[0].x(), pts[0].y(),
                        pts[2].x(), pts[2].y(),
                        pts[1].x(), pts[1].y(),
                        sqrInvScaleX, sqrInvScaleY, outputVertices);
                break;
            case SkPath::kCubic_Verb:
                ALOGV("kCubic_Verb");
                recursiveCubicBezierVertices(
                        pts[0].x(), pts[0].y(),
                        pts[1].x(), pts[1].y(),
                        pts[3].x(), pts[3].y(),
                        pts[2].x(), pts[2].y(),
                        sqrInvScaleX, sqrInvScaleY, outputVertices);
                break;
            default:
                break;
            }
    }

    int size = outputVertices.size();
    if (size >= 2 && outputVertices[0].position[0] == outputVertices[size - 1].position[0] &&
            outputVertices[0].position[1] == outputVertices[size - 1].position[1]) {
        outputVertices.pop();
        return true;
    }
    return false;
}

///////////////////////////////////////////////////////////////////////////////
// Bezier approximation
///////////////////////////////////////////////////////////////////////////////

void PathTessellator::recursiveCubicBezierVertices(
        float p1x, float p1y, float c1x, float c1y,
        float p2x, float p2y, float c2x, float c2y,
        float sqrInvScaleX, float sqrInvScaleY, Vector<Vertex>& outputVertices) {
    float dx = p2x - p1x;
    float dy = p2y - p1y;
    float d1 = fabs((c1x - p2x) * dy - (c1y - p2y) * dx);
    float d2 = fabs((c2x - p2x) * dy - (c2y - p2y) * dx);
    float d = d1 + d2;

    // multiplying by sqrInvScaleY/X equivalent to multiplying in dimensional scale factors

    if (d * d < THRESHOLD * THRESHOLD * (dx * dx * sqrInvScaleY + dy * dy * sqrInvScaleX)) {
        // below thresh, draw line by adding endpoint
        pushToVector(outputVertices, p2x, p2y);
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
                sqrInvScaleX, sqrInvScaleY, outputVertices);
        recursiveCubicBezierVertices(
                mx, my, p2c1c2x, p2c1c2y,
                p2x, p2y, p2c2x, p2c2y,
                sqrInvScaleX, sqrInvScaleY, outputVertices);
    }
}

void PathTessellator::recursiveQuadraticBezierVertices(
        float ax, float ay,
        float bx, float by,
        float cx, float cy,
        float sqrInvScaleX, float sqrInvScaleY, Vector<Vertex>& outputVertices) {
    float dx = bx - ax;
    float dy = by - ay;
    float d = (cx - bx) * dy - (cy - by) * dx;

    if (d * d < THRESHOLD * THRESHOLD * (dx * dx * sqrInvScaleY + dy * dy * sqrInvScaleX)) {
        // below thresh, draw line by adding endpoint
        pushToVector(outputVertices, bx, by);
    } else {
        float acx = (ax + cx) * 0.5f;
        float bcx = (bx + cx) * 0.5f;
        float acy = (ay + cy) * 0.5f;
        float bcy = (by + cy) * 0.5f;

        // midpoint
        float mx = (acx + bcx) * 0.5f;
        float my = (acy + bcy) * 0.5f;

        recursiveQuadraticBezierVertices(ax, ay, mx, my, acx, acy,
                sqrInvScaleX, sqrInvScaleY, outputVertices);
        recursiveQuadraticBezierVertices(mx, my, bx, by, bcx, bcy,
                sqrInvScaleX, sqrInvScaleY, outputVertices);
    }
}

}; // namespace uirenderer
}; // namespace android
