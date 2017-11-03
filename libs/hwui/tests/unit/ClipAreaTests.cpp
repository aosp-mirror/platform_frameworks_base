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

#include <SkPath.h>
#include <SkRegion.h>
#include <gtest/gtest.h>

#include "ClipArea.h"

#include "Matrix.h"
#include "Rect.h"
#include "utils/LinearAllocator.h"

namespace android {
namespace uirenderer {

static Rect kViewportBounds(2048, 2048);

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
    SkPath path;
    SkScalar r = 100;
    path.addCircle(r, r, r);
    area.clipPathWithTransform(path, &Matrix4::identity(), SkRegion::kIntersect_Op);
    EXPECT_FALSE(area.isEmpty());
    EXPECT_FALSE(area.isSimple());
    EXPECT_FALSE(area.isRectangleList());

    Rect clipRect(area.getClipRect());
    Rect expected(0, 0, r * 2, r * 2);
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

    Rect expected(-50, -50, 50, 50);
    area.clipRectWithTransform(expected, &Matrix4::identity(), SkRegion::kReplace_Op);
    EXPECT_EQ(expected, area.getClipRect());
}

TEST(ClipArea, serializeClip) {
    ClipArea area(createClipArea());
    LinearAllocator allocator;

    // unset clip
    EXPECT_EQ(nullptr, area.serializeClip(allocator));

    // rect clip
    area.setClip(0, 0, 200, 200);
    {
        auto serializedClip = area.serializeClip(allocator);
        ASSERT_NE(nullptr, serializedClip);
        ASSERT_EQ(ClipMode::Rectangle, serializedClip->mode);
        ASSERT_FALSE(serializedClip->intersectWithRoot) << "No replace, so no intersectWithRoot";
        EXPECT_EQ(Rect(200, 200), serializedClip->rect);
        EXPECT_EQ(serializedClip, area.serializeClip(allocator))
                << "Requery of clip on unmodified ClipArea must return same pointer.";
    }

    // rect list
    Matrix4 rotate;
    rotate.loadRotate(5.0f);
    area.clipRectWithTransform(Rect(50, 50, 150, 150), &rotate, SkRegion::kIntersect_Op);
    {
        auto serializedClip = area.serializeClip(allocator);
        ASSERT_NE(nullptr, serializedClip);
        ASSERT_EQ(ClipMode::RectangleList, serializedClip->mode);
        ASSERT_FALSE(serializedClip->intersectWithRoot) << "No replace, so no intersectWithRoot";
        auto clipRectList = reinterpret_cast<const ClipRectList*>(serializedClip);
        EXPECT_EQ(2, clipRectList->rectList.getTransformedRectanglesCount());
        EXPECT_EQ(Rect(37, 54, 145, 163), clipRectList->rect);
        EXPECT_EQ(serializedClip, area.serializeClip(allocator))
                << "Requery of clip on unmodified ClipArea must return same pointer.";
    }

    // region
    SkPath circlePath;
    circlePath.addCircle(100, 100, 100);
    area.clipPathWithTransform(circlePath, &Matrix4::identity(), SkRegion::kReplace_Op);
    {
        auto serializedClip = area.serializeClip(allocator);
        ASSERT_NE(nullptr, serializedClip);
        ASSERT_EQ(ClipMode::Region, serializedClip->mode);
        ASSERT_TRUE(serializedClip->intersectWithRoot) << "Replace op, so expect intersectWithRoot";
        auto clipRegion = reinterpret_cast<const ClipRegion*>(serializedClip);
        EXPECT_EQ(SkIRect::MakeWH(200, 200), clipRegion->region.getBounds())
                << "Clip region should be 200x200";
        EXPECT_EQ(Rect(200, 200), clipRegion->rect);
        EXPECT_EQ(serializedClip, area.serializeClip(allocator))
                << "Requery of clip on unmodified ClipArea must return same pointer.";
    }
}

TEST(ClipArea, serializeClip_pathIntersectWithRoot) {
    ClipArea area(createClipArea());
    LinearAllocator allocator;
    SkPath circlePath;
    circlePath.addCircle(100, 100, 100);
    area.clipPathWithTransform(circlePath, &Matrix4::identity(), SkRegion::kIntersect_Op);

    auto serializedClip = area.serializeClip(allocator);
    ASSERT_NE(nullptr, serializedClip);
    EXPECT_FALSE(serializedClip->intersectWithRoot) << "No replace, so no intersectWithRoot";
}

TEST(ClipArea, serializeIntersectedClip) {
    ClipArea area(createClipArea());
    LinearAllocator allocator;

    // simple state;
    EXPECT_EQ(nullptr, area.serializeIntersectedClip(allocator, nullptr, Matrix4::identity()));
    area.setClip(0, 0, 200, 200);
    {
        auto origRectClip = area.serializeClip(allocator);
        ASSERT_NE(nullptr, origRectClip);
        EXPECT_EQ(origRectClip,
                  area.serializeIntersectedClip(allocator, nullptr, Matrix4::identity()));
    }

    // rect
    {
        ClipRect recordedClip(Rect(100, 100));
        Matrix4 translateScale;
        translateScale.loadTranslate(100, 100, 0);
        translateScale.scale(2, 3, 1);
        auto resolvedClip = area.serializeIntersectedClip(allocator, &recordedClip, translateScale);
        ASSERT_NE(nullptr, resolvedClip);
        ASSERT_EQ(ClipMode::Rectangle, resolvedClip->mode);
        EXPECT_EQ(Rect(100, 100, 200, 200), resolvedClip->rect);

        EXPECT_EQ(resolvedClip,
                  area.serializeIntersectedClip(allocator, &recordedClip, translateScale))
                << "Must return previous serialization, since input is same";

        ClipRect recordedClip2(Rect(100, 100));
        EXPECT_NE(resolvedClip,
                  area.serializeIntersectedClip(allocator, &recordedClip2, translateScale))
                << "Shouldn't return previous serialization, since matrix location is different";
    }

    // rect list
    Matrix4 rotate;
    rotate.loadRotate(2.0f);
    area.clipRectWithTransform(Rect(200, 200), &rotate, SkRegion::kIntersect_Op);
    {
        ClipRect recordedClip(Rect(100, 100));
        auto resolvedClip =
                area.serializeIntersectedClip(allocator, &recordedClip, Matrix4::identity());
        ASSERT_NE(nullptr, resolvedClip);
        ASSERT_EQ(ClipMode::RectangleList, resolvedClip->mode);
        auto clipRectList = reinterpret_cast<const ClipRectList*>(resolvedClip);
        EXPECT_EQ(2, clipRectList->rectList.getTransformedRectanglesCount());
    }

    // region
    SkPath circlePath;
    circlePath.addCircle(100, 100, 100);
    area.clipPathWithTransform(circlePath, &Matrix4::identity(), SkRegion::kReplace_Op);
    {
        SkPath ovalPath;
        ovalPath.addOval(SkRect::MakeLTRB(50, 0, 150, 200));

        ClipRegion recordedClip;
        recordedClip.region.setPath(ovalPath, SkRegion(SkIRect::MakeWH(200, 200)));
        recordedClip.rect = Rect(200, 200);

        Matrix4 translate10x20;
        translate10x20.loadTranslate(10, 20, 0);
        auto resolvedClip = area.serializeIntersectedClip(
                allocator, &recordedClip,
                translate10x20);  // Note: only translate for now, others not handled correctly
        ASSERT_NE(nullptr, resolvedClip);
        ASSERT_EQ(ClipMode::Region, resolvedClip->mode);
        auto clipRegion = reinterpret_cast<const ClipRegion*>(resolvedClip);
        EXPECT_EQ(SkIRect::MakeLTRB(60, 20, 160, 200), clipRegion->region.getBounds());
    }
}

TEST(ClipArea, serializeIntersectedClip_snap) {
    ClipArea area(createClipArea());
    area.setClip(100.2, 100.4, 500.6, 500.8);
    LinearAllocator allocator;

    {
        // no recorded clip case
        auto resolvedClip = area.serializeIntersectedClip(allocator, nullptr, Matrix4::identity());
        EXPECT_EQ(Rect(100, 100, 501, 501), resolvedClip->rect);
    }
    {
        // recorded clip case
        ClipRect recordedClip(Rect(100.12, 100.74));
        Matrix4 translateScale;
        translateScale.loadTranslate(100, 100, 0);
        translateScale.scale(2, 3,
                             1);  // recorded clip will have non-int coords, even after transform
        auto resolvedClip = area.serializeIntersectedClip(allocator, &recordedClip, translateScale);
        ASSERT_NE(nullptr, resolvedClip);
        EXPECT_EQ(ClipMode::Rectangle, resolvedClip->mode);
        EXPECT_EQ(Rect(100, 100, 300, 402), resolvedClip->rect);
    }
}

TEST(ClipArea, serializeIntersectedClip_scale) {
    ClipArea area(createClipArea());
    area.setClip(0, 0, 400, 400);
    LinearAllocator allocator;

    SkPath circlePath;
    circlePath.addCircle(50, 50, 50);

    ClipRegion recordedClip;
    recordedClip.region.setPath(circlePath, SkRegion(SkIRect::MakeWH(100, 100)));
    recordedClip.rect = Rect(100, 100);

    Matrix4 translateScale;
    translateScale.loadTranslate(100, 100, 0);
    translateScale.scale(2, 2, 1);
    auto resolvedClip = area.serializeIntersectedClip(allocator, &recordedClip, translateScale);

    ASSERT_NE(nullptr, resolvedClip);
    EXPECT_EQ(ClipMode::Region, resolvedClip->mode);
    EXPECT_EQ(Rect(100, 100, 300, 300), resolvedClip->rect);
    auto clipRegion = reinterpret_cast<const ClipRegion*>(resolvedClip);
    EXPECT_EQ(SkIRect::MakeLTRB(100, 100, 300, 300), clipRegion->region.getBounds());
}

TEST(ClipArea, applyTransformToRegion_identity) {
    SkRegion region(SkIRect::MakeLTRB(1, 2, 3, 4));
    ClipArea::applyTransformToRegion(Matrix4::identity(), &region);
    EXPECT_TRUE(region.isRect());
    EXPECT_EQ(SkIRect::MakeLTRB(1, 2, 3, 4), region.getBounds());
}

TEST(ClipArea, applyTransformToRegion_translate) {
    SkRegion region(SkIRect::MakeLTRB(1, 2, 3, 4));
    Matrix4 transform;
    transform.loadTranslate(10, 20, 0);
    ClipArea::applyTransformToRegion(transform, &region);
    EXPECT_TRUE(region.isRect());
    EXPECT_EQ(SkIRect::MakeLTRB(11, 22, 13, 24), region.getBounds());
}

TEST(ClipArea, applyTransformToRegion_scale) {
    SkRegion region(SkIRect::MakeLTRB(1, 2, 3, 4));
    Matrix4 transform;
    transform.loadScale(2, 3, 1);
    ClipArea::applyTransformToRegion(transform, &region);
    EXPECT_TRUE(region.isRect());
    EXPECT_EQ(SkIRect::MakeLTRB(2, 6, 6, 12), region.getBounds());
}

TEST(ClipArea, applyTransformToRegion_translateScale) {
    SkRegion region(SkIRect::MakeLTRB(1, 2, 3, 4));
    Matrix4 transform;
    transform.translate(10, 20);
    transform.scale(2, 3, 1);
    ClipArea::applyTransformToRegion(transform, &region);
    EXPECT_TRUE(region.isRect());
    EXPECT_EQ(SkIRect::MakeLTRB(12, 26, 16, 32), region.getBounds());
}

TEST(ClipArea, applyTransformToRegion_rotate90) {
    SkRegion region(SkIRect::MakeLTRB(1, 2, 3, 4));
    Matrix4 transform;
    transform.loadRotate(90);
    ClipArea::applyTransformToRegion(transform, &region);
    EXPECT_TRUE(region.isRect());
    EXPECT_EQ(SkIRect::MakeLTRB(-4, 1, -2, 3), region.getBounds());
}

}  // namespace uirenderer
}  // namespace android
