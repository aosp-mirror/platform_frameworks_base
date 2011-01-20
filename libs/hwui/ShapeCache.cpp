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

RoundRectShapeCache::RoundRectShapeCache(): ShapeCache<RoundRectShapeCacheEntry>() {
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

CircleShapeCache::CircleShapeCache(): ShapeCache<CircleShapeCacheEntry>() {
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

}; // namespace uirenderer
}; // namespace android
