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

#include <BakedOpState.h>
#include <RecordedOp.h>
#include <unit_tests/TestUtils.h>

namespace android {
namespace uirenderer {

TEST(ResolvedRenderState, resolution) {
    Matrix4 identity;
    identity.loadIdentity();

    Matrix4 translate10x20;
    translate10x20.loadTranslate(10, 20, 0);

    SkPaint paint;
    RectOp recordedOp(Rect(30, 40, 100, 200), translate10x20, Rect(0, 0, 100, 200), &paint);
    {
        // recorded with transform, no parent transform
        auto parentSnapshot = TestUtils::makeSnapshot(identity, Rect(0, 0, 100, 200));
        ResolvedRenderState state(*parentSnapshot, recordedOp);
        EXPECT_MATRIX_APPROX_EQ(state.transform, translate10x20);
        EXPECT_EQ(state.clipRect, Rect(0, 0, 100, 200));
        EXPECT_EQ(state.clippedBounds, Rect(40, 60, 100, 200)); // translated and also clipped
    }
    {
        // recorded with transform and parent transform
        auto parentSnapshot = TestUtils::makeSnapshot(translate10x20, Rect(0, 0, 100, 200));
        ResolvedRenderState state(*parentSnapshot, recordedOp);

        Matrix4 expectedTranslate;
        expectedTranslate.loadTranslate(20, 40, 0);
        EXPECT_MATRIX_APPROX_EQ(state.transform, expectedTranslate);

        // intersection of parent & transformed child clip
        EXPECT_EQ(state.clipRect, Rect(10, 20, 100, 200));

        // translated and also clipped
        EXPECT_EQ(state.clippedBounds, Rect(50, 80, 100, 200));
    }
}

TEST(BakedOpState, constructAndReject) {
    LinearAllocator allocator;

    Matrix4 identity;
    identity.loadIdentity();

    Matrix4 translate100x0;
    translate100x0.loadTranslate(100, 0, 0);

    SkPaint paint;
    {
        RectOp rejectOp(Rect(30, 40, 100, 200), translate100x0, Rect(0, 0, 100, 200), &paint);
        auto snapshot = TestUtils::makeSnapshot(identity, Rect(0, 0, 100, 200));
        BakedOpState* bakedOp = BakedOpState::tryConstruct(allocator, *snapshot, rejectOp);

        EXPECT_EQ(bakedOp, nullptr); // rejected by clip, so not constructed
        EXPECT_LE(allocator.usedSize(), 8u); // no significant allocation space used for rejected op
    }
    {
        RectOp successOp(Rect(30, 40, 100, 200), identity, Rect(0, 0, 100, 200), &paint);
        auto snapshot = TestUtils::makeSnapshot(identity, Rect(0, 0, 100, 200));
        BakedOpState* bakedOp = BakedOpState::tryConstruct(allocator, *snapshot, successOp);

        EXPECT_NE(bakedOp, nullptr); // NOT rejected by clip, so will be constructed
        EXPECT_GT(allocator.usedSize(), 64u); // relatively large alloc for non-rejected op
    }
}

#define UNSUPPORTED_OP(Info, Type) \
        static void on##Type(Info*, const Type&, const BakedOpState&) { FAIL(); }

class Info {
public:
    int index = 0;
};

}
}
