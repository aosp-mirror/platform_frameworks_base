/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include "ShapeCache.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Rounded rects
///////////////////////////////////////////////////////////////////////////////

RoundRectShapeCache::RoundRectShapeCache(): ShapeCache<RoundRectShapeCacheEntry>(
        "round rect", PROPERTY_SHAPE_CACHE_SIZE, DEFAULT_SHAPE_CACHE_SIZE) {
}

PathTexture* RoundRectShapeCache::getRoundRect(float width, float height,
        float rx, float ry, SkPaint* paint) {
    RoundRectShapeCacheEntry entry(width, height, rx, ry, paint);
    PathTexture* texture = get(entry);

    if (!texture) {
        SkPath path;
        SkRect r;
        r.set(0.0f, 0.0f, width, height);
        path.addRoundRect(r, rx, ry, SkPath::kCW_Direction);

        texture = addTexture(entry, &path, paint);
    }

    return texture;
}

///////////////////////////////////////////////////////////////////////////////
// Circles
///////////////////////////////////////////////////////////////////////////////

CircleShapeCache::CircleShapeCache(): ShapeCache<CircleShapeCacheEntry>(
        "circle", PROPERTY_SHAPE_CACHE_SIZE, DEFAULT_SHAPE_CACHE_SIZE) {
}

PathTexture* CircleShapeCache::getCircle(float radius, SkPaint* paint) {
    CircleShapeCacheEntry entry(radius, paint);
    PathTexture* texture = get(entry);

    if (!texture) {
        SkPath path;
        path.addCircle(radius, radius, radius, SkPath::kCW_Direction);

        texture = addTexture(entry, &path, paint);
    }

    return texture;
}

///////////////////////////////////////////////////////////////////////////////
// Ovals
///////////////////////////////////////////////////////////////////////////////

OvalShapeCache::OvalShapeCache(): ShapeCache<OvalShapeCacheEntry>(
        "oval", PROPERTY_SHAPE_CACHE_SIZE, DEFAULT_SHAPE_CACHE_SIZE) {
}

PathTexture* OvalShapeCache::getOval(float width, float height, SkPaint* paint) {
    OvalShapeCacheEntry entry(width, height, paint);
    PathTexture* texture = get(entry);

    if (!texture) {
        SkPath path;
        SkRect r;
        r.set(0.0f, 0.0f, width, height);
        path.addOval(r, SkPath::kCW_Direction);

        texture = addTexture(entry, &path, paint);
    }

    return texture;
}

///////////////////////////////////////////////////////////////////////////////
// Rects
///////////////////////////////////////////////////////////////////////////////

RectShapeCache::RectShapeCache(): ShapeCache<RectShapeCacheEntry>(
        "rect", PROPERTY_SHAPE_CACHE_SIZE, DEFAULT_SHAPE_CACHE_SIZE) {
}

PathTexture* RectShapeCache::getRect(float width, float height, SkPaint* paint) {
    RectShapeCacheEntry entry(width, height, paint);
    PathTexture* texture = get(entry);

    if (!texture) {
        SkRect bounds;
        bounds.set(0.0f, 0.0f, width, height);

        float left, top, offset;
        uint32_t rectWidth, rectHeight;
        computeBounds(bounds, paint, left, top, offset, rectWidth, rectHeight);

        if (!checkTextureSize(rectWidth, rectHeight)) return NULL;

        purgeCache(rectWidth, rectHeight);

        SkBitmap bitmap;
        initBitmap(bitmap, rectWidth, rectHeight);

        SkPaint pathPaint(*paint);
        initPaint(pathPaint);

        SkCanvas canvas(bitmap);
        canvas.translate(-left + offset, -top + offset);
        canvas.drawRect(bounds, pathPaint);

        texture = createTexture(0, 0, offset, rectWidth, rectHeight, 0);
        addTexture(entry, &bitmap, texture);
    }

    return texture;
}

///////////////////////////////////////////////////////////////////////////////
// Arcs
///////////////////////////////////////////////////////////////////////////////

ArcShapeCache::ArcShapeCache(): ShapeCache<ArcShapeCacheEntry>(
        "arc", PROPERTY_SHAPE_CACHE_SIZE, DEFAULT_SHAPE_CACHE_SIZE) {
}

PathTexture* ArcShapeCache::getArc(float width, float height,
        float startAngle, float sweepAngle, bool useCenter, SkPaint* paint) {
    ArcShapeCacheEntry entry(width, height, startAngle, sweepAngle, useCenter, paint);
    PathTexture* texture = get(entry);

    if (!texture) {
        SkPath path;
        SkRect r;
        r.set(0.0f, 0.0f, width, height);
        if (useCenter) {
            path.moveTo(r.centerX(), r.centerY());
        }
        path.arcTo(r, startAngle, sweepAngle, !useCenter);
        if (useCenter) {
            path.close();
        }

        texture = addTexture(entry, &path, paint);
    }

    return texture;
}

}; // namespace uirenderer
}; // namespace android
