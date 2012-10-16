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

#define LOG_TAG "PathRenderer"
#define LOG_NDEBUG 1
#define ATRACE_TAG ATRACE_TAG_GRAPHICS

#define VERTEX_DEBUG 0

#include <SkPath.h>
#include <SkPaint.h>

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <utils/Log.h>
#include <utils/Trace.h>

#include "PathRenderer.h"
#include "Matrix.h"
#include "Vector.h"
#include "Vertex.h"

namespace android {
namespace uirenderer {

#define THRESHOLD 0.5f

SkRect PathRenderer::computePathBounds(const SkPath& path, const SkPaint* paint) {
    SkRect bounds = path.getBounds();
    if (paint->getStyle() != SkPaint::kFill_Style) {
        float outset = paint->getStrokeWidth() * 0.5f;
        bounds.outset(outset, outset);
    }
    return bounds;
}

void computeInverseScales(const mat4 *transform, float &inverseScaleX, float& inverseScaleY) {
    if (CC_UNLIKELY(!transform->isPureTranslate())) {
        float m00 = transform->data[Matrix4::kScaleX];
        float m01 = transform->data[Matrix4::kSkewY];
        float m10 = transform->data[Matrix4::kSkewX];
        float m11 = transform->data[Matrix4::kScaleY];
        float scaleX = sqrt(m00 * m00 + m01 * m01);
        float scaleY = sqrt(m10 * m10 + m11 * m11);
        inverseScaleX = (scaleX != 0) ? (1.0f / scaleX) : 1.0f;
        inverseScaleY = (scaleY != 0) ? (1.0f / scaleY) : 1.0f;
    } else {
        inverseScaleX = 1.0f;
        inverseScaleY = 1.0f;
    }
}

inline void copyVertex(Vertex* destPtr, const Vertex* srcPtr) {
    Vertex::set(destPtr, srcPtr->position[0], srcPtr->position[1]);
}

inline void copyAlphaVertex(AlphaVertex* destPtr, const AlphaVertex* srcPtr) {
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
inline vec2 totalOffsetFromNormals(const vec2& normalA, const vec2& normalB) {
    return (normalA + normalB) / (1 + fabs(normalA.dot(normalB)));
}

inline void scaleOffsetForStrokeWidth(vec2& offset, float halfStrokeWidth,
        float inverseScaleX, float inverseScaleY) {
    if (halfStrokeWidth == 0.0f) {
        // hairline - compensate for scale
        offset.x *= 0.5f * inverseScaleX;
        offset.y *= 0.5f * inverseScaleY;
    } else {
        offset *= halfStrokeWidth;
    }
}

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

void getStrokeVerticesFromPerimeter(const Vector<Vertex>& perimeter, float halfStrokeWidth,
        VertexBuffer& vertexBuffer, float inverseScaleX, float inverseScaleY) {
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
        scaleOffsetForStrokeWidth(totalOffset, halfStrokeWidth, inverseScaleX, inverseScaleY);

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
}

void getStrokeVerticesFromUnclosedVertices(const Vector<Vertex>& vertices, float halfStrokeWidth,
        VertexBuffer& vertexBuffer, float inverseScaleX, float inverseScaleY) {
    Vertex* buffer = vertexBuffer.alloc<Vertex>(vertices.size() * 2);

    int currentIndex = 0;
    const Vertex* current = &(vertices[0]);
    vec2 lastNormal;
    for (unsigned int i = 0; i < vertices.size() - 1; i++) {
        const Vertex* next = &(vertices[i + 1]);
        vec2 nextNormal(next->position[1] - current->position[1],
                current->position[0] - next->position[0]);
        nextNormal.normalize();

        vec2 totalOffset;
        if (i == 0) {
            totalOffset = nextNormal;
        } else {
            totalOffset = totalOffsetFromNormals(lastNormal, nextNormal);
        }
        scaleOffsetForStrokeWidth(totalOffset, halfStrokeWidth, inverseScaleX, inverseScaleY);

        Vertex::set(&buffer[currentIndex++],
                current->position[0] + totalOffset.x,
                current->position[1] + totalOffset.y);

        Vertex::set(&buffer[currentIndex++],
                current->position[0] - totalOffset.x,
                current->position[1] - totalOffset.y);

        current = next;
        lastNormal = nextNormal;
    }

    vec2 totalOffset = lastNormal;
    scaleOffsetForStrokeWidth(totalOffset, halfStrokeWidth, inverseScaleX, inverseScaleY);

    Vertex::set(&buffer[currentIndex++],
            current->position[0] + totalOffset.x,
            current->position[1] + totalOffset.y);
    Vertex::set(&buffer[currentIndex++],
            current->position[0] - totalOffset.x,
            current->position[1] - totalOffset.y);
#if VERTEX_DEBUG
    for (unsigned int i = 0; i < vertexBuffer.getSize(); i++) {
        ALOGD("point at %f %f", buffer[i].position[0], buffer[i].position[1]);
    }
#endif
}

void getFillVerticesFromPerimeterAA(const Vector<Vertex>& perimeter, VertexBuffer& vertexBuffer,
         float inverseScaleX, float inverseScaleY) {
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
        vec2 totalOffset = totalOffsetFromNormals(lastNormal, nextNormal);
        totalOffset.x *= 0.5f * inverseScaleX;
        totalOffset.y *= 0.5f * inverseScaleY;

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

#if VERTEX_DEBUG
    for (unsigned int i = 0; i < vertexBuffer.getSize(); i++) {
        ALOGD("point at %f %f, alpha %f", buffer[i].position[0], buffer[i].position[1], buffer[i].alpha);
    }
#endif
}


void getStrokeVerticesFromUnclosedVerticesAA(const Vector<Vertex>& vertices, float halfStrokeWidth,
        VertexBuffer& vertexBuffer, float inverseScaleX, float inverseScaleY) {
    AlphaVertex* buffer = vertexBuffer.alloc<AlphaVertex>(6 * vertices.size() + 2);

    // avoid lines smaller than hairline since they break triangle based sampling. instead reducing
    // alpha value (TODO: support different X/Y scale)
    float maxAlpha = 1.0f;
    if (halfStrokeWidth != 0 && inverseScaleX == inverseScaleY &&
            halfStrokeWidth * inverseScaleX < 0.5f) {
        maxAlpha *= (2 * halfStrokeWidth) / inverseScaleX;
        halfStrokeWidth = 0.0f;
    }

    // there is no outer/inner here, using them for consistency with below approach
    int offset = 2 * (vertices.size() - 2);
    int currentAAOuterIndex = 2;
    int currentAAInnerIndex = 2 * offset + 5; // reversed
    int currentStrokeIndex = currentAAInnerIndex + 7;

    const Vertex* last = &(vertices[0]);
    const Vertex* current = &(vertices[1]);
    vec2 lastNormal(current->position[1] - last->position[1],
            last->position[0] - current->position[0]);
    lastNormal.normalize();

    {
        // start cap
        vec2 totalOffset = lastNormal;
        vec2 AAOffset = totalOffset;
        AAOffset.x *= 0.5f * inverseScaleX;
        AAOffset.y *= 0.5f * inverseScaleY;

        vec2 innerOffset = totalOffset;
        scaleOffsetForStrokeWidth(innerOffset, halfStrokeWidth, inverseScaleX, inverseScaleY);
        vec2 outerOffset = innerOffset + AAOffset;
        innerOffset -= AAOffset;

        // TODO: support square cap by changing this offset to incorporate halfStrokeWidth
        vec2 capAAOffset(AAOffset.y, -AAOffset.x);
        AlphaVertex::set(&buffer[0],
                last->position[0] + outerOffset.x + capAAOffset.x,
                last->position[1] + outerOffset.y + capAAOffset.y,
                0.0f);
        AlphaVertex::set(&buffer[1],
                last->position[0] + innerOffset.x - capAAOffset.x,
                last->position[1] + innerOffset.y - capAAOffset.y,
                maxAlpha);

        AlphaVertex::set(&buffer[2 * offset + 6],
                last->position[0] - outerOffset.x + capAAOffset.x,
                last->position[1] - outerOffset.y + capAAOffset.y,
                0.0f);
        AlphaVertex::set(&buffer[2 * offset + 7],
                last->position[0] - innerOffset.x - capAAOffset.x,
                last->position[1] - innerOffset.y - capAAOffset.y,
                maxAlpha);
        copyAlphaVertex(&buffer[2 * offset + 8], &buffer[0]);
        copyAlphaVertex(&buffer[2 * offset + 9], &buffer[1]);
        copyAlphaVertex(&buffer[2 * offset + 10], &buffer[1]); // degenerate tris (the only two!)
        copyAlphaVertex(&buffer[2 * offset + 11], &buffer[2 * offset + 7]);
    }

    for (unsigned int i = 1; i < vertices.size() - 1; i++) {
        const Vertex* next = &(vertices[i + 1]);
        vec2 nextNormal(next->position[1] - current->position[1],
                current->position[0] - next->position[0]);
        nextNormal.normalize();

        vec2 totalOffset = totalOffsetFromNormals(lastNormal, nextNormal);
        vec2 AAOffset = totalOffset;
        AAOffset.x *= 0.5f * inverseScaleX;
        AAOffset.y *= 0.5f * inverseScaleY;

        vec2 innerOffset = totalOffset;
        scaleOffsetForStrokeWidth(innerOffset, halfStrokeWidth, inverseScaleX, inverseScaleY);
        vec2 outerOffset = innerOffset + AAOffset;
        innerOffset -= AAOffset;

        AlphaVertex::set(&buffer[currentAAOuterIndex++],
                current->position[0] + outerOffset.x,
                current->position[1] + outerOffset.y,
                0.0f);
        AlphaVertex::set(&buffer[currentAAOuterIndex++],
                current->position[0] + innerOffset.x,
                current->position[1] + innerOffset.y,
                maxAlpha);

        AlphaVertex::set(&buffer[currentStrokeIndex++],
                current->position[0] + innerOffset.x,
                current->position[1] + innerOffset.y,
                maxAlpha);
        AlphaVertex::set(&buffer[currentStrokeIndex++],
                current->position[0] - innerOffset.x,
                current->position[1] - innerOffset.y,
                maxAlpha);

        AlphaVertex::set(&buffer[currentAAInnerIndex--],
                current->position[0] - innerOffset.x,
                current->position[1] - innerOffset.y,
                maxAlpha);
        AlphaVertex::set(&buffer[currentAAInnerIndex--],
                current->position[0] - outerOffset.x,
                current->position[1] - outerOffset.y,
                0.0f);

        last = current;
        current = next;
        lastNormal = nextNormal;
    }

    {
        // end cap
        vec2 totalOffset = lastNormal;
        vec2 AAOffset = totalOffset;
        AAOffset.x *= 0.5f * inverseScaleX;
        AAOffset.y *= 0.5f * inverseScaleY;

        vec2 innerOffset = totalOffset;
        scaleOffsetForStrokeWidth(innerOffset, halfStrokeWidth, inverseScaleX, inverseScaleY);
        vec2 outerOffset = innerOffset + AAOffset;
        innerOffset -= AAOffset;

        // TODO: support square cap by changing this offset to incorporate halfStrokeWidth
        vec2 capAAOffset(-AAOffset.y, AAOffset.x);

        AlphaVertex::set(&buffer[offset + 2],
                current->position[0] + outerOffset.x + capAAOffset.x,
                current->position[1] + outerOffset.y + capAAOffset.y,
                0.0f);
        AlphaVertex::set(&buffer[offset + 3],
                current->position[0] + innerOffset.x - capAAOffset.x,
                current->position[1] + innerOffset.y - capAAOffset.y,
                maxAlpha);

        AlphaVertex::set(&buffer[offset + 4],
                current->position[0] - outerOffset.x + capAAOffset.x,
                current->position[1] - outerOffset.y + capAAOffset.y,
                0.0f);
        AlphaVertex::set(&buffer[offset + 5],
                current->position[0] - innerOffset.x - capAAOffset.x,
                current->position[1] - innerOffset.y - capAAOffset.y,
                maxAlpha);

        copyAlphaVertex(&buffer[vertexBuffer.getSize() - 2], &buffer[offset + 3]);
        copyAlphaVertex(&buffer[vertexBuffer.getSize() - 1], &buffer[offset + 5]);
    }

#if VERTEX_DEBUG
    for (unsigned int i = 0; i < vertexBuffer.getSize(); i++) {
        ALOGD("point at %f %f, alpha %f", buffer[i].position[0], buffer[i].position[1], buffer[i].alpha);
    }
#endif
}


void getStrokeVerticesFromPerimeterAA(const Vector<Vertex>& perimeter, float halfStrokeWidth,
        VertexBuffer& vertexBuffer, float inverseScaleX, float inverseScaleY) {
    AlphaVertex* buffer = vertexBuffer.alloc<AlphaVertex>(6 * perimeter.size() + 8);

    // avoid lines smaller than hairline since they break triangle based sampling. instead reducing
    // alpha value (TODO: support different X/Y scale)
    float maxAlpha = 1.0f;
    if (halfStrokeWidth != 0 && inverseScaleX == inverseScaleY &&
            halfStrokeWidth * inverseScaleX < 0.5f) {
        maxAlpha *= (2 * halfStrokeWidth) / inverseScaleX;
        halfStrokeWidth = 0.0f;
    }

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
        vec2 AAOffset = totalOffset;
        AAOffset.x *= 0.5f * inverseScaleX;
        AAOffset.y *= 0.5f * inverseScaleY;

        vec2 innerOffset = totalOffset;
        scaleOffsetForStrokeWidth(innerOffset, halfStrokeWidth, inverseScaleX, inverseScaleY);
        vec2 outerOffset = innerOffset + AAOffset;
        innerOffset -= AAOffset;

        AlphaVertex::set(&buffer[currentAAOuterIndex++],
                current->position[0] + outerOffset.x,
                current->position[1] + outerOffset.y,
                0.0f);
        AlphaVertex::set(&buffer[currentAAOuterIndex++],
                current->position[0] + innerOffset.x,
                current->position[1] + innerOffset.y,
                maxAlpha);

        AlphaVertex::set(&buffer[currentStrokeIndex++],
                current->position[0] + innerOffset.x,
                current->position[1] + innerOffset.y,
                maxAlpha);
        AlphaVertex::set(&buffer[currentStrokeIndex++],
                current->position[0] - innerOffset.x,
                current->position[1] - innerOffset.y,
                maxAlpha);

        AlphaVertex::set(&buffer[currentAAInnerIndex++],
                current->position[0] - innerOffset.x,
                current->position[1] - innerOffset.y,
                maxAlpha);
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

#if VERTEX_DEBUG
    for (unsigned int i = 0; i < vertexBuffer.getSize(); i++) {
        ALOGD("point at %f %f, alpha %f", buffer[i].position[0], buffer[i].position[1], buffer[i].alpha);
    }
#endif
}

void PathRenderer::convexPathVertices(const SkPath &path, const SkPaint* paint,
        const mat4 *transform, VertexBuffer& vertexBuffer) {
    ATRACE_CALL();

    SkPaint::Style style = paint->getStyle();
    bool isAA = paint->isAntiAlias();

    float inverseScaleX, inverseScaleY;
    computeInverseScales(transform, inverseScaleX, inverseScaleY);

    Vector<Vertex> tempVertices;
    float threshInvScaleX = inverseScaleX;
    float threshInvScaleY = inverseScaleY;
    if (style == SkPaint::kStroke_Style) {
        // alter the bezier recursion threshold values we calculate in order to compensate for
        // expansion done after the path vertices are found
        SkRect bounds = path.getBounds();
        if (!bounds.isEmpty()) {
            threshInvScaleX *= bounds.width() / (bounds.width() + paint->getStrokeWidth());
            threshInvScaleY *= bounds.height() / (bounds.height() + paint->getStrokeWidth());
        }
    }

    // force close if we're filling the path, since fill path expects closed perimeter.
    bool forceClose = style != SkPaint::kStroke_Style;
    bool wasClosed = convexPathPerimeterVertices(path, forceClose, threshInvScaleX * threshInvScaleX,
            threshInvScaleY * threshInvScaleY, tempVertices);

    if (!tempVertices.size()) {
        // path was empty, return without allocating vertex buffer
        return;
    }

#if VERTEX_DEBUG
    for (unsigned int i = 0; i < tempVertices.size(); i++) {
        ALOGD("orig path: point at %f %f", tempVertices[i].position[0], tempVertices[i].position[1]);
    }
#endif

    if (style == SkPaint::kStroke_Style) {
        float halfStrokeWidth = paint->getStrokeWidth() * 0.5f;
        if (!isAA) {
            if (wasClosed) {
                getStrokeVerticesFromPerimeter(tempVertices, halfStrokeWidth, vertexBuffer,
                        inverseScaleX, inverseScaleY);
            } else {
                getStrokeVerticesFromUnclosedVertices(tempVertices, halfStrokeWidth, vertexBuffer,
                        inverseScaleX, inverseScaleY);
            }

        } else {
            if (wasClosed) {
                getStrokeVerticesFromPerimeterAA(tempVertices, halfStrokeWidth, vertexBuffer,
                        inverseScaleX, inverseScaleY);
            } else {
                getStrokeVerticesFromUnclosedVerticesAA(tempVertices, halfStrokeWidth, vertexBuffer,
                        inverseScaleX, inverseScaleY);
            }
        }
    } else {
        // For kStrokeAndFill style, the path should be adjusted externally, as it will be treated as a fill here.
        if (!isAA) {
            getFillVerticesFromPerimeter(tempVertices, vertexBuffer);
        } else {
            getFillVerticesFromPerimeterAA(tempVertices, vertexBuffer, inverseScaleX, inverseScaleY);
        }
    }
}


void pushToVector(Vector<Vertex>& vertices, float x, float y) {
    // TODO: make this not yuck
    vertices.push();
    Vertex* newVertex = &(vertices.editArray()[vertices.size() - 1]);
    Vertex::set(newVertex, x, y);
}

bool PathRenderer::convexPathPerimeterVertices(const SkPath& path, bool forceClose,
        float sqrInvScaleX, float sqrInvScaleY, Vector<Vertex>& outputVertices) {
    ATRACE_CALL();

    // TODO: to support joins other than sharp miter, join vertices should be labelled in the
    // perimeter, or resolved into more vertices. Reconsider forceClose-ing in that case.
    SkPath::Iter iter(path, forceClose);
    SkPoint pts[4];
    SkPath::Verb v;
    Vertex* newVertex = 0;
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
                    ALOGV("kLine_Verb %f %f -> %f %f",
                            pts[0].x(), pts[0].y(),
                            pts[1].x(), pts[1].y());

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

void PathRenderer::recursiveCubicBezierVertices(
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

void PathRenderer::recursiveQuadraticBezierVertices(
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
