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
#include <tests/common/TestUtils.h>

namespace android {
namespace uirenderer {

TEST(ResolvedRenderState, construct) {
    Matrix4 translate10x20;
    translate10x20.loadTranslate(10, 20, 0);

    SkPaint paint;
    RectOp recordedOp(Rect(30, 40, 100, 200), translate10x20, Rect(100, 200), &paint);
    {
        // recorded with transform, no parent transform
        auto parentSnapshot = TestUtils::makeSnapshot(Matrix4::identity(), Rect(100, 200));
        ResolvedRenderState state(*parentSnapshot, recordedOp, false);
        EXPECT_MATRIX_APPROX_EQ(state.transform, translate10x20);
        EXPECT_EQ(Rect(0, 0, 100, 200), state.clipRect);
        EXPECT_EQ(Rect(40, 60, 100, 200), state.clippedBounds); // translated and also clipped
        EXPECT_EQ(OpClipSideFlags::Right | OpClipSideFlags::Bottom, state.clipSideFlags);
    }
    {
        // recorded with transform and parent transform
        auto parentSnapshot = TestUtils::makeSnapshot(translate10x20, Rect(100, 200));
        ResolvedRenderState state(*parentSnapshot, recordedOp, false);

        Matrix4 expectedTranslate;
        expectedTranslate.loadTranslate(20, 40, 0);
        EXPECT_MATRIX_APPROX_EQ(expectedTranslate, state.transform);

        // intersection of parent & transformed child clip
        EXPECT_EQ(Rect(10, 20, 100, 200), state.clipRect);

        // translated and also clipped
        EXPECT_EQ(Rect(50, 80, 100, 200), state.clippedBounds);
        EXPECT_EQ(OpClipSideFlags::Right | OpClipSideFlags::Bottom, state.clipSideFlags);
    }
}

const float HAIRLINE = 0.0f;

// Note: bounds will be conservative, but not precise for non-hairline
// - use approx bounds checks for these
const float SEMI_HAIRLINE = 0.3f;

struct StrokeTestCase {
    float scale;
    float strokeWidth;
    const std::function<void(const ResolvedRenderState&)> validator;
};

const static StrokeTestCase sStrokeTestCases[] = {
    {
        1, HAIRLINE, [](const ResolvedRenderState& state) {
            EXPECT_EQ(Rect(49.5f, 49.5f, 150.5f, 150.5f), state.clippedBounds);
        }
    },
    {
        1, SEMI_HAIRLINE, [](const ResolvedRenderState& state) {
            EXPECT_TRUE(state.clippedBounds.contains(49.5f, 49.5f, 150.5f, 150.5f));
            EXPECT_TRUE(Rect(49, 49, 151, 151).contains(state.clippedBounds));
        }
    },
    {
        1, 20, [](const ResolvedRenderState& state) {
            EXPECT_EQ(Rect(40, 40, 160, 160), state.clippedBounds);
        }
    },

    // 3x3 scale:
    {
        3, HAIRLINE, [](const ResolvedRenderState& state) {
            EXPECT_EQ(Rect(149.5f, 149.5f, 200, 200), state.clippedBounds);
            EXPECT_EQ(OpClipSideFlags::Right | OpClipSideFlags::Bottom, state.clipSideFlags);
        }
    },
    {
        3, SEMI_HAIRLINE, [](const ResolvedRenderState& state) {
            EXPECT_TRUE(state.clippedBounds.contains(149.5f, 149.5f, 200, 200));
            EXPECT_TRUE(Rect(149, 149, 200, 200).contains(state.clippedBounds));
        }
    },
    {
        3, 20, [](const ResolvedRenderState& state) {
            EXPECT_TRUE(state.clippedBounds.contains(120, 120, 200, 200));
            EXPECT_TRUE(Rect(119, 119, 200, 200).contains(state.clippedBounds));
        }
    },

    // 0.5f x 0.5f scale
    {
        0.5f, HAIRLINE, [](const ResolvedRenderState& state) {
            EXPECT_EQ(Rect(24.5f, 24.5f, 75.5f, 75.5f), state.clippedBounds);
        }
    },
    {
        0.5f, SEMI_HAIRLINE, [](const ResolvedRenderState& state) {
            EXPECT_TRUE(state.clippedBounds.contains(24.5f, 24.5f, 75.5f, 75.5f));
            EXPECT_TRUE(Rect(24, 24, 76, 76).contains(state.clippedBounds));
        }
    },
    {
        0.5f, 20, [](const ResolvedRenderState& state) {
            EXPECT_TRUE(state.clippedBounds.contains(19.5f, 19.5f, 80.5f, 80.5f));
            EXPECT_TRUE(Rect(19, 19, 81, 81).contains(state.clippedBounds));
        }
    }
};

TEST(ResolvedRenderState, construct_expandForStroke) {
    // Loop over table of test cases and verify different combinations of stroke width and transform
    for (auto&& testCase : sStrokeTestCases) {
        SkPaint strokedPaint;
        strokedPaint.setAntiAlias(true);
        strokedPaint.setStyle(SkPaint::kStroke_Style);
        strokedPaint.setStrokeWidth(testCase.strokeWidth);

        RectOp recordedOp(Rect(50, 50, 150, 150),
                Matrix4::identity(), Rect(200, 200), &strokedPaint);

        Matrix4 snapshotMatrix;
        snapshotMatrix.loadScale(testCase.scale, testCase.scale, 1);
        auto parentSnapshot = TestUtils::makeSnapshot(snapshotMatrix, Rect(200, 200));

        ResolvedRenderState state(*parentSnapshot, recordedOp, true);
        testCase.validator(state);
    }
}

TEST(BakedOpState, tryConstruct) {
    LinearAllocator allocator;

    Matrix4 translate100x0;
    translate100x0.loadTranslate(100, 0, 0);

    SkPaint paint;
    {
        RectOp rejectOp(Rect(30, 40, 100, 200), translate100x0, Rect(100, 200), &paint);
        auto snapshot = TestUtils::makeSnapshot(Matrix4::identity(), Rect(100, 200));
        BakedOpState* bakedState = BakedOpState::tryConstruct(allocator, *snapshot, rejectOp);

        EXPECT_EQ(nullptr, bakedState); // rejected by clip, so not constructed
        EXPECT_GT(8u, allocator.usedSize()); // no significant allocation space used for rejected op
    }
    {
        RectOp successOp(Rect(30, 40, 100, 200), Matrix4::identity(), Rect(100, 200), &paint);
        auto snapshot = TestUtils::makeSnapshot(Matrix4::identity(), Rect(100, 200));
        BakedOpState* bakedState = BakedOpState::tryConstruct(allocator, *snapshot, successOp);

        EXPECT_NE(nullptr, bakedState); // NOT rejected by clip, so will be constructed
        EXPECT_LE(64u, allocator.usedSize()); // relatively large alloc for non-rejected op
    }
}

TEST(BakedOpState, tryShadowOpConstruct) {
    LinearAllocator allocator;
    {
        auto snapshot = TestUtils::makeSnapshot(Matrix4::identity(), Rect()); // Note: empty clip
        BakedOpState* bakedState = BakedOpState::tryShadowOpConstruct(allocator, *snapshot, (ShadowOp*)0x1234);

        EXPECT_EQ(nullptr, bakedState); // rejected by clip, so not constructed
        EXPECT_GT(8u, allocator.usedSize()); // no significant allocation space used for rejected op
    }
    {
        auto snapshot = TestUtils::makeSnapshot(Matrix4::identity(), Rect(100, 200));
        BakedOpState* bakedState = BakedOpState::tryShadowOpConstruct(allocator, *snapshot, (ShadowOp*)0x1234);

        ASSERT_NE(nullptr, bakedState); // NOT rejected by clip, so will be constructed
        EXPECT_LE(64u, allocator.usedSize()); // relatively large alloc for non-rejected op
    }
}

TEST(BakedOpState, tryStrokeableOpConstruct) {
    LinearAllocator allocator;
    {
        // check regular rejection
        SkPaint paint;
        paint.setStyle(SkPaint::kStrokeAndFill_Style);
        paint.setStrokeWidth(0.0f);
        RectOp rejectOp(Rect(0, 0, 100, 200), Matrix4::identity(), Rect(100, 200), &paint);
        auto snapshot = TestUtils::makeSnapshot(Matrix4::identity(), Rect()); // Note: empty clip
        auto bakedState = BakedOpState::tryStrokeableOpConstruct(allocator, *snapshot, rejectOp,
                BakedOpState::StrokeBehavior::StyleDefined);

        EXPECT_EQ(nullptr, bakedState);
        EXPECT_GT(8u, allocator.usedSize()); // no significant allocation space used for rejected op
    }
    {
        // check simple unscaled expansion
        SkPaint paint;
        paint.setStyle(SkPaint::kStrokeAndFill_Style);
        paint.setStrokeWidth(10.0f);
        RectOp rejectOp(Rect(50, 50, 150, 150), Matrix4::identity(), Rect(200, 200), &paint);
        auto snapshot = TestUtils::makeSnapshot(Matrix4::identity(), Rect(200, 200));
        auto bakedState = BakedOpState::tryStrokeableOpConstruct(allocator, *snapshot, rejectOp,
                BakedOpState::StrokeBehavior::StyleDefined);

        ASSERT_NE(nullptr, bakedState);
        EXPECT_EQ(Rect(45, 45, 155, 155), bakedState->computedState.clippedBounds);
        EXPECT_EQ(0, bakedState->computedState.clipSideFlags);
    }
    {
        // check simple unscaled expansion, and fill style with stroke forced
        SkPaint paint;
        paint.setStyle(SkPaint::kFill_Style);
        paint.setStrokeWidth(10.0f);
        RectOp rejectOp(Rect(50, 50, 150, 150), Matrix4::identity(), Rect(200, 200), &paint);
        auto snapshot = TestUtils::makeSnapshot(Matrix4::identity(), Rect(200, 200));
        auto bakedState = BakedOpState::tryStrokeableOpConstruct(allocator, *snapshot, rejectOp,
                BakedOpState::StrokeBehavior::Forced);

        ASSERT_NE(nullptr, bakedState);
        EXPECT_EQ(Rect(45, 45, 155, 155), bakedState->computedState.clippedBounds);
        EXPECT_EQ(0, bakedState->computedState.clipSideFlags);
    }
}

} // namespace uirenderer
} // namespace android
