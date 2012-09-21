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

void PathRenderer::computeInverseScales(const mat4 *transform,
        float &inverseScaleX, float& inverseScaleY) {
    inverseScaleX = 1.0f;
    inverseScaleY = 1.0f;
    if (CC_UNLIKELY(!transform->isPureTranslate())) {
        float m00 = transform->data[Matrix4::kScaleX];
        float m01 = transform->data[Matrix4::kSkewY];
        float m10 = transform->data[Matrix4::kSkewX];
        float m11 = transform->data[Matrix4::kScaleY];
        float scaleX = sqrt(m00 * m00 + m01 * m01);
        float scaleY = sqrt(m10 * m10 + m11 * m11);
        inverseScaleX = (scaleX != 0) ? (inverseScaleX / scaleX) : 0;
        inverseScaleY = (scaleY != 0) ? (inverseScaleY / scaleY) : 0;
    }
}

void PathRenderer::convexPathFillVertices(const SkPath &path, const mat4 *transform,
        VertexBuffer &vertexBuffer, bool isAA) {
    ATRACE_CALL();
    float inverseScaleX;
    float inverseScaleY;
    computeInverseScales(transform, inverseScaleX, inverseScaleY);

    Vector<Vertex> tempVertices;
    float thresholdx = THRESHOLD * inverseScaleX;
    float thresholdy = THRESHOLD * inverseScaleY;
    convexPathVertices(path,
                       thresholdx * thresholdx,
                       thresholdy * thresholdy,
                       tempVertices);

#if VERTEX_DEBUG
    for (unsigned int i = 0; i < tempVertices.size(); i++) {
        ALOGD("orig path: point at %f %f",
              tempVertices[i].position[0],
              tempVertices[i].position[1]);
    }
#endif
    int currentIndex = 0;
    if (!isAA) {
        Vertex* buffer = vertexBuffer.alloc<Vertex>(tempVertices.size());

        // zig zag between all previous points on the inside of the hull to create a
        // triangle strip that fills the hull
        int srcAindex = 0;
        int srcBindex = tempVertices.size() - 1;
        while (srcAindex <= srcBindex) {
            Vertex::set(&buffer[currentIndex++],
                        tempVertices.editArray()[srcAindex].position[0],
                        tempVertices.editArray()[srcAindex].position[1]);
            if (srcAindex == srcBindex) break;
            Vertex::set(&buffer[currentIndex++],
                        tempVertices.editArray()[srcBindex].position[0],
                        tempVertices.editArray()[srcBindex].position[1]);
            srcAindex++;
            srcBindex--;
        }
        return;
    }
    AlphaVertex* buffer = vertexBuffer.alloc<AlphaVertex>(tempVertices.size() * 3 + 2);

    // generate alpha points - fill Alpha vertex gaps in between each point with
    // alpha 0 vertex, offset by a scaled normal.
    Vertex* last = &(tempVertices.editArray()[tempVertices.size()-1]);

    for (unsigned int i = 0; i<tempVertices.size(); i++) {
        Vertex* current = &(tempVertices.editArray()[i]);
        Vertex* next = &(tempVertices.editArray()[i + 1 >= tempVertices.size() ? 0 : i + 1]);

        vec2 lastNormal(current->position[1] - last->position[1],
                        last->position[0] - current->position[0]);
        lastNormal.normalize();
        vec2 nextNormal(next->position[1] - current->position[1],
                        current->position[0] - next->position[0]);
        nextNormal.normalize();

        // AA point offset from original point is that point's normal, such that
        // each side is offset by .5 pixels
        vec2 totalOffset = (lastNormal + nextNormal) / (2 * (1 + lastNormal.dot(nextNormal)));
        totalOffset.x *= inverseScaleX;
        totalOffset.y *= inverseScaleY;

        AlphaVertex::set(&buffer[currentIndex++],
                         current->position[0] + totalOffset.x,
                         current->position[1] + totalOffset.y,
                         0.0f);
        AlphaVertex::set(&buffer[currentIndex++],
                         current->position[0] - totalOffset.x,
                         current->position[1] - totalOffset.y,
                         1.0f);
        last = current;
    }

    // wrap around to beginning
    AlphaVertex::set(&buffer[currentIndex++],
                     buffer[0].position[0],
                     buffer[0].position[1], 0.0f);
    AlphaVertex::set(&buffer[currentIndex++],
                     buffer[1].position[0],
                     buffer[1].position[1], 1.0f);

    // zig zag between all previous points on the inside of the hull to create a
    // triangle strip that fills the hull, repeating the first inner point to
    // create degenerate tris to start inside path
    int srcAindex = 0;
    int srcBindex = tempVertices.size() - 1;
    while (srcAindex <= srcBindex) {
        AlphaVertex::set(&buffer[currentIndex++],
                         buffer[srcAindex * 2 + 1].position[0],
                         buffer[srcAindex * 2 + 1].position[1],
                         1.0f);
        if (srcAindex == srcBindex) break;
        AlphaVertex::set(&buffer[currentIndex++],
                         buffer[srcBindex * 2 + 1].position[0],
                         buffer[srcBindex * 2 + 1].position[1],
                         1.0f);
        srcAindex++;
        srcBindex--;
    }

#if VERTEX_DEBUG
    for (unsigned int i = 0; i < vertexBuffer.mSize; i++) {
        ALOGD("point at %f %f",
              buffer[i].position[0],
              buffer[i].position[1]);
    }
#endif
}


void PathRenderer::convexPathVertices(const SkPath &path, float thresholdx, float thresholdy,
        Vector<Vertex> &outputVertices) {
    ATRACE_CALL();

    SkPath::Iter iter(path, true);
    SkPoint pos;
    SkPoint pts[4];
    SkPath::Verb v;
    Vertex* newVertex = 0;
    while (SkPath::kDone_Verb != (v = iter.next(pts))) {
            switch (v) {
                case SkPath::kMove_Verb:
                    pos = pts[0];
                    ALOGV("Move to pos %f %f", pts[0].x(), pts[0].y());
                    break;
                case SkPath::kClose_Verb:
                    ALOGV("Close at pos %f %f", pts[0].x(), pts[0].y());
                    break;
                case SkPath::kLine_Verb:
                    ALOGV("kLine_Verb %f %f -> %f %f",
                          pts[0].x(), pts[0].y(),
                          pts[1].x(), pts[1].y());

                    // TODO: make this not yuck
                    outputVertices.push();
                    newVertex = &(outputVertices.editArray()[outputVertices.size()-1]);
                    Vertex::set(newVertex, pts[1].x(), pts[1].y());
                    break;
                case SkPath::kQuad_Verb:
                    ALOGV("kQuad_Verb");
                    recursiveQuadraticBezierVertices(
                        pts[0].x(), pts[0].y(),
                        pts[2].x(), pts[2].y(),
                        pts[1].x(), pts[1].y(),
                        thresholdx, thresholdy,
                        outputVertices);
                    break;
                case SkPath::kCubic_Verb:
                    ALOGV("kCubic_Verb");
                    recursiveCubicBezierVertices(
                        pts[0].x(), pts[0].y(),
                        pts[1].x(), pts[1].y(),
                        pts[3].x(), pts[3].y(),
                        pts[2].x(), pts[2].y(),
                        thresholdx, thresholdy, outputVertices);
                    break;
                default:
                    break;
            }
    }
}

void PathRenderer::recursiveCubicBezierVertices(
        float p1x, float p1y, float c1x, float c1y,
        float p2x, float p2y, float c2x, float c2y,
        float thresholdx, float thresholdy, Vector<Vertex> &outputVertices) {
    float dx = p2x - p1x;
    float dy = p2y - p1y;
    float d1 = fabs((c1x - p2x) * dy - (c1y - p2y) * dx);
    float d2 = fabs((c2x - p2x) * dy - (c2y - p2y) * dx);
    float d = d1 + d2;

    if (d * d < (thresholdx * (dx * dx) + thresholdy * (dy * dy))) {
        // below thresh, draw line by adding endpoint
        // TODO: make this not yuck
        outputVertices.push();
        Vertex* newVertex = &(outputVertices.editArray()[outputVertices.size()-1]);
        Vertex::set(newVertex, p2x, p2y);
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
                thresholdx, thresholdy,
                outputVertices);
        recursiveCubicBezierVertices(
                mx, my, p2c1c2x, p2c1c2y,
                p2x, p2y, p2c2x, p2c2y,
                thresholdx, thresholdy,
                outputVertices);
    }
}

void PathRenderer::recursiveQuadraticBezierVertices(
        float ax, float ay,
        float bx, float by,
        float cx, float cy,
        float thresholdx, float thresholdy, Vector<Vertex> &outputVertices) {
    float dx = bx - ax;
    float dy = by - ay;
    float d = (cx - bx) * dy - (cy - by) * dx;

    if (d * d < (thresholdx * (dx * dx) + thresholdy * (dy * dy))) {
        // below thresh, draw line by adding endpoint
        // TODO: make this not yuck
        outputVertices.push();
        Vertex* newVertex = &(outputVertices.editArray()[outputVertices.size()-1]);
        Vertex::set(newVertex, bx, by);
    } else {
        float acx = (ax + cx) * 0.5f;
        float bcx = (bx + cx) * 0.5f;
        float acy = (ay + cy) * 0.5f;
        float bcy = (by + cy) * 0.5f;

        // midpoint
        float mx = (acx + bcx) * 0.5f;
        float my = (acy + bcy) * 0.5f;

        recursiveQuadraticBezierVertices(ax, ay, mx, my, acx, acy,
                thresholdx, thresholdy, outputVertices);
        recursiveQuadraticBezierVertices(mx, my, bx, by, bcx, bcy,
                thresholdx, thresholdy, outputVertices);
    }
}

}; // namespace uirenderer
}; // namespace android
