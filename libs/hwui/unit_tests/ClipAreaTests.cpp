/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include <gtest/gtest.h>
#include <SkPath.h>
#include <SkRegion.h>

#include "ClipArea.h"

#include "Matrix.h"
#include "Rect.h"
#include "utils/LinearAllocator.h"

namespace android {
namespace uirenderer {

static Rect kViewportBounds(0, 0, 2048, 2048);

static ClipArea createClipArea() {
    ClipArea area;
    area.setViewportDimensions(kViewportBounds.getWidth(), kViewportBounds.getHeight());
    return area;
}

TEST(TransformedRectangle, basics) {
    Rect r(0, 0, 100, 100);
    Matrix4 minus90;
    minus90.loadRotate(-90);
    minus90.mapRect(r);
    Rect r2(20, 40, 120, 60);

    Matrix4 m90;
    m90.loadRotate(90);
    TransformedRectangle tr(r, m90);
    EXPECT_TRUE(tr.canSimplyIntersectWith(tr));

    Matrix4 m0;
    TransformedRectangle tr0(r2, m0);
    EXPECT_FALSE(tr.canSimplyIntersectWith(tr0));

    Matrix4 m45;
    m45.loadRotate(45);
    TransformedRectangle tr2(r, m45);
    EXPECT_FALSE(tr2.canSimplyIntersectWith(tr));
}

TEST(RectangleList, basics) {
    RectangleList list;
    EXPECT_TRUE(list.isEmpty());

    Rect r(0, 0, 100, 100);
    Matrix4 m45;
    m45.loadRotate(45);
    list.set(r, m45);
    EXPECT_FALSE(list.isEmpty());

    Rect r2(20, 20, 200, 200);
    list.intersectWith(r2, m45);
    EXPECT_FALSE(list.isEmpty());
    EXPECT_EQ(1, list.getTransformedRectanglesCount());

    Rect r3(20, 20, 200, 200);
    Matrix4 m30;
    m30.loadRotate(30);
    list.intersectWith(r2, m30);
    EXPECT_FALSE(list.isEmpty());
    EXPECT_EQ(2, list.getTransformedRectanglesCount());

    SkRegion clip;
    clip.setRect(0, 0, 2000, 2000);
    SkRegion rgn(list.convertToRegion(clip));
    EXPECT_FALSE(rgn.isEmpty());
}

TEST(ClipArea, basics) {
    ClipArea area(createClipArea());
    EXPECT_FALSE(area.isEmpty());
}

TEST(ClipArea, paths) {
    ClipArea area(createClipArea());
    Matrix4 transform;
    transform.loadIdentity();
    SkPath path;
    SkScalar r = 100;
    path.addCircle(r, r, r);
    area.clipPathWithTransform(path, &transform, SkRegion::kIntersect_Op);
    EXPECT_FALSE(area.isEmpty());
    EXPECT_FALSE(area.isSimple());
    EXPECT_FALSE(area.isRectangleList());
    Rect clipRect(area.getClipRect());
    clipRect.dump("clipRect");
    Rect expected(0, 0, r * 2, r * 2);
    expected.dump("expected");
    EXPECT_EQ(expected, clipRect);
    SkRegion clipRegion(area.getClipRegion());
    auto skRect(clipRegion.getBounds());
    Rect regionBounds;
    regionBounds.set(skRect);
    EXPECT_EQ(expected, regionBounds);
}

TEST(ClipArea, replaceNegative) {
    ClipArea area(createClipArea());
    area.setClip(0, 0, 100, 100);

    Matrix4 transform;
    transform.loadIdentity();
    Rect expected(-50, -50, 50, 50);
    area.clipRectWithTransform(expected, &transform, SkRegion::kReplace_Op);
    EXPECT_EQ(expected, area.getClipRect());
}
}
}
