/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <Snapshot.h>

#include <tests/common/TestUtils.h>

using namespace android::uirenderer;

TEST(Snapshot, serializeIntersectedClip) {
    auto actualRoot = TestUtils::makeSnapshot(Matrix4::identity(), Rect(0, 0, 100, 100));
    auto root = TestUtils::makeSnapshot(Matrix4::identity(), Rect(10, 10, 90, 90));
    auto child = TestUtils::makeSnapshot(Matrix4::identity(), Rect(50, 50, 90, 90));
    root->previous = actualRoot.get();
    child->previous = root.get();

    LinearAllocator allocator;
    ClipRect rect(Rect(0, 0, 75, 75));
    {
        auto intersectWithChild = child->serializeIntersectedClip(allocator,
                &rect, Matrix4::identity());
        ASSERT_NE(nullptr, intersectWithChild);
        EXPECT_EQ(Rect(50, 50, 75, 75), intersectWithChild->rect) << "Expect intersect with child";
    }

    rect.intersectWithRoot = true;
    {
        auto intersectWithRoot = child->serializeIntersectedClip(allocator,
                &rect, Matrix4::identity());
        ASSERT_NE(nullptr, intersectWithRoot);
        EXPECT_EQ(Rect(10, 10, 75, 75), intersectWithRoot->rect) << "Expect intersect with root";
    }
}

TEST(Snapshot, applyClip) {
    auto actualRoot = TestUtils::makeSnapshot(Matrix4::identity(), Rect(0, 0, 100, 100));
    auto root = TestUtils::makeSnapshot(Matrix4::identity(), Rect(10, 10, 90, 90));
    root->previous = actualRoot.get();

    ClipRect rect(Rect(0, 0, 75, 75));
    {
        auto child = TestUtils::makeSnapshot(Matrix4::identity(), Rect(50, 50, 90, 90));
        child->previous = root.get();
        child->applyClip(&rect, Matrix4::identity());

        EXPECT_TRUE(child->getClipArea().isSimple());
        EXPECT_EQ(Rect(50, 50, 75, 75), child->getRenderTargetClip());
    }

    {
        rect.intersectWithRoot = true;
        auto child = TestUtils::makeSnapshot(Matrix4::identity(), Rect(50, 50, 90, 90));
        child->previous = root.get();
        child->applyClip(&rect, Matrix4::identity());

        EXPECT_TRUE(child->getClipArea().isSimple());
        EXPECT_EQ(Rect(10, 10, 75, 75), child->getRenderTargetClip());
    }
}
