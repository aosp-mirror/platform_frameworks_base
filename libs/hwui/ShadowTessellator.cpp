/*
 * Copyright (C) 2013 The Android Open Source Project
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
#define ATRACE_TAG ATRACE_TAG_VIEW

#include <math.h>
#include <utils/Log.h>
#include <utils/Trace.h>

#include "AmbientShadow.h"
#include "Caches.h"
#include "ShadowTessellator.h"
#include "SpotShadow.h"

namespace android {
namespace uirenderer {

void ShadowTessellator::tessellateAmbientShadow(bool isCasterOpaque,
        const Vector3* casterPolygon, int casterVertexCount,
        const Vector3& centroid3d, const Rect& casterBounds,
        const Rect& localClip, float maxZ, VertexBuffer& shadowVertexBuffer) {
    ATRACE_CALL();

    // A bunch of parameters to tweak the shadow.
    // TODO: Allow some of these changable by debug settings or APIs.
    float heightFactor = 1.0f / 128;
    const float geomFactor = 64;

    Caches& caches = Caches::getInstance();
    if (CC_UNLIKELY(caches.propertyAmbientRatio > 0.0f)) {
        heightFactor *= caches.propertyAmbientRatio;
    }

    Rect ambientShadowBounds(casterBounds);
    ambientShadowBounds.outset(maxZ * geomFactor * heightFactor);

    if (!localClip.intersects(ambientShadowBounds)) {
#if DEBUG_SHADOW
        ALOGD("Ambient shadow is out of clip rect!");
#endif
        return;
    }

    AmbientShadow::createAmbientShadow(isCasterOpaque, casterPolygon,
            casterVertexCount, centroid3d, heightFactor, geomFactor,
            shadowVertexBuffer);
}

void ShadowTessellator::tessellateSpotShadow(bool isCasterOpaque,
        const Vector3* casterPolygon, int casterVertexCount, const Vector3& casterCentroid,
        const mat4& receiverTransform, const Vector3& lightCenter, int lightRadius,
        const Rect& casterBounds, const Rect& localClip, VertexBuffer& shadowVertexBuffer) {
    ATRACE_CALL();

    Caches& caches = Caches::getInstance();

    Vector3 adjustedLightCenter(lightCenter);
    if (CC_UNLIKELY(caches.propertyLightPosY > 0)) {
        adjustedLightCenter.y = - caches.propertyLightPosY; // negated since this shifts up
    }
    if (CC_UNLIKELY(caches.propertyLightPosZ > 0)) {
        adjustedLightCenter.z = caches.propertyLightPosZ;
    }

#if DEBUG_SHADOW
    ALOGD("light center %f %f %f",
            adjustedLightCenter.x, adjustedLightCenter.y, adjustedLightCenter.z);
#endif

    // light position (because it's in local space) needs to compensate for receiver transform
    // TODO: should apply to light orientation, not just position
    Matrix4 reverseReceiverTransform;
    reverseReceiverTransform.loadInverse(receiverTransform);
    reverseReceiverTransform.mapPoint3d(adjustedLightCenter);

    const int lightVertexCount = 8;
    if (CC_UNLIKELY(caches.propertyLightDiameter > 0)) {
        lightRadius = caches.propertyLightDiameter;
    }

    // Now light and caster are both in local space, we will check whether
    // the shadow is within the clip area.
    Rect lightRect = Rect(adjustedLightCenter.x - lightRadius, adjustedLightCenter.y - lightRadius,
            adjustedLightCenter.x + lightRadius, adjustedLightCenter.y + lightRadius);
    lightRect.unionWith(localClip);
    if (!lightRect.intersects(casterBounds)) {
#if DEBUG_SHADOW
        ALOGD("Spot shadow is out of clip rect!");
#endif
        return;
    }

    SpotShadow::createSpotShadow(isCasterOpaque, adjustedLightCenter, lightRadius,
            casterPolygon, casterVertexCount, casterCentroid, shadowVertexBuffer);

#if DEBUG_SHADOW
     if(shadowVertexBuffer.getVertexCount() <= 0) {
        ALOGD("Spot shadow generation failed %d", shadowVertexBuffer.getVertexCount());
     }
#endif
}

void ShadowTessellator::generateShadowIndices(uint16_t* shadowIndices) {
    int currentIndex = 0;
    const int rays = SHADOW_RAY_COUNT;
    // For the penumbra area.
    for (int layer = 0; layer < 2; layer ++) {
        int baseIndex = layer * rays;
        for (int i = 0; i < rays; i++) {
            shadowIndices[currentIndex++] = i + baseIndex;
            shadowIndices[currentIndex++] = rays + i + baseIndex;
        }
        // To close the loop, back to the ray 0.
        shadowIndices[currentIndex++] = 0 + baseIndex;
         // Note this is the same as the first index of next layer loop.
        shadowIndices[currentIndex++] = rays + baseIndex;
    }

#if DEBUG_SHADOW
    if (currentIndex != MAX_SHADOW_INDEX_COUNT) {
        ALOGW("vertex index count is wrong. current %d, expected %d",
                currentIndex, MAX_SHADOW_INDEX_COUNT);
    }
    for (int i = 0; i < MAX_SHADOW_INDEX_COUNT; i++) {
        ALOGD("vertex index is (%d, %d)", i, shadowIndices[i]);
    }
#endif
}

/**
 * Calculate the centroid of a 2d polygon.
 *
 * @param poly The polygon, which is represented in a Vector2 array.
 * @param polyLength The length of the polygon in terms of number of vertices.
 * @return the centroid of the polygon.
 */
Vector2 ShadowTessellator::centroid2d(const Vector2* poly, int polyLength) {
    double sumx = 0;
    double sumy = 0;
    int p1 = polyLength - 1;
    double area = 0;
    for (int p2 = 0; p2 < polyLength; p2++) {
        double x1 = poly[p1].x;
        double y1 = poly[p1].y;
        double x2 = poly[p2].x;
        double y2 = poly[p2].y;
        double a = (x1 * y2 - x2 * y1);
        sumx += (x1 + x2) * a;
        sumy += (y1 + y2) * a;
        area += a;
        p1 = p2;
    }

    Vector2 centroid = poly[0];
    if (area != 0) {
        centroid = (Vector2){static_cast<float>(sumx / (3 * area)),
            static_cast<float>(sumy / (3 * area))};
    } else {
        ALOGW("Area is 0 while computing centroid!");
    }
    return centroid;
}

// Make sure p1 -> p2 is going CW around the poly.
Vector2 ShadowTessellator::calculateNormal(const Vector2& p1, const Vector2& p2) {
    Vector2 result = p2 - p1;
    if (result.x != 0 || result.y != 0) {
        result.normalize();
        // Calculate the normal , which is CCW 90 rotate to the delta.
        float tempy = result.y;
        result.y = result.x;
        result.x = -tempy;
    }
    return result;
}
/**
 * Test whether the polygon is order in clockwise.
 *
 * @param polygon the polygon as a Vector2 array
 * @param len the number of points of the polygon
 */
bool ShadowTessellator::isClockwise(const Vector2* polygon, int len) {
    if (len < 2 || polygon == NULL) {
        return true;
    }
    double sum = 0;
    double p1x = polygon[len - 1].x;
    double p1y = polygon[len - 1].y;
    for (int i = 0; i < len; i++) {

        double p2x = polygon[i].x;
        double p2y = polygon[i].y;
        sum += p1x * p2y - p2x * p1y;
        p1x = p2x;
        p1y = p2y;
    }
    return sum < 0;
}

bool ShadowTessellator::isClockwisePath(const SkPath& path) {
    SkPath::Iter iter(path, false);
    SkPoint pts[4];
    SkPath::Verb v;

    Vector<Vector2> arrayForDirection;
    while (SkPath::kDone_Verb != (v = iter.next(pts))) {
            switch (v) {
            case SkPath::kMove_Verb:
                arrayForDirection.add((Vector2){pts[0].x(), pts[0].y()});
                break;
            case SkPath::kLine_Verb:
                arrayForDirection.add((Vector2){pts[1].x(), pts[1].y()});
                break;
            case SkPath::kQuad_Verb:
                arrayForDirection.add((Vector2){pts[1].x(), pts[1].y()});
                arrayForDirection.add((Vector2){pts[2].x(), pts[2].y()});
                break;
            case SkPath::kCubic_Verb:
                arrayForDirection.add((Vector2){pts[1].x(), pts[1].y()});
                arrayForDirection.add((Vector2){pts[2].x(), pts[2].y()});
                arrayForDirection.add((Vector2){pts[3].x(), pts[3].y()});
                break;
            default:
                break;
            }
    }

    return isClockwise(arrayForDirection.array(), arrayForDirection.size());
}

void ShadowTessellator::reverseVertexArray(Vertex* polygon, int len) {
    int n = len / 2;
    for (int i = 0; i < n; i++) {
        Vertex tmp = polygon[i];
        int k = len - 1 - i;
        polygon[i] = polygon[k];
        polygon[k] = tmp;
    }
}

int ShadowTessellator::getExtraVertexNumber(const Vector2& vector1,
        const Vector2& vector2, float divisor) {
    // When there is no distance difference, there is no need for extra vertices.
    if (vector1.lengthSquared() == 0 || vector2.lengthSquared() == 0) {
        return 0;
    }
    // The formula is :
    // extraNumber = floor(acos(dot(n1, n2)) / (M_PI / EXTRA_VERTEX_PER_PI))
    // The value ranges for each step are:
    // dot( ) --- [-1, 1]
    // acos( )     --- [0, M_PI]
    // floor(...)  --- [0, EXTRA_VERTEX_PER_PI]
    float dotProduct = vector1.dot(vector2);
    // TODO: Use look up table for the dotProduct to extraVerticesNumber
    // computation, if needed.
    float angle = acosf(dotProduct);
    return (int) floor(angle / divisor);
}

void ShadowTessellator::checkOverflow(int used, int total, const char* bufferName) {
    LOG_ALWAYS_FATAL_IF(used > total, "Error: %s overflow!!! used %d, total %d",
            bufferName, used, total);
}

}; // namespace uirenderer
}; // namespace android
