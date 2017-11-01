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

#include "compile/Image.h"

#include "test/Test.h"

namespace aapt {

// Pixels are in RGBA_8888 packing.

#define RED "\xff\x00\x00\xff"
#define BLUE "\x00\x00\xff\xff"
#define GREEN "\xff\x00\x00\xff"
#define GR_70 "\xff\x00\x00\xb3"
#define GR_50 "\xff\x00\x00\x80"
#define GR_20 "\xff\x00\x00\x33"
#define BLACK "\x00\x00\x00\xff"
#define WHITE "\xff\xff\xff\xff"
#define TRANS "\x00\x00\x00\x00"

static uint8_t* k2x2[] = {
    (uint8_t*)WHITE WHITE, (uint8_t*)WHITE WHITE,
};

static uint8_t* kMixedNeutralColor3x3[] = {
    (uint8_t*)WHITE BLACK TRANS, (uint8_t*)TRANS RED TRANS,
    (uint8_t*)WHITE WHITE WHITE,
};

static uint8_t* kTransparentNeutralColor3x3[] = {
    (uint8_t*)TRANS BLACK TRANS, (uint8_t*)BLACK RED BLACK,
    (uint8_t*)TRANS BLACK TRANS,
};

static uint8_t* kSingleStretch7x6[] = {
    (uint8_t*)WHITE WHITE BLACK BLACK BLACK WHITE WHITE,
    (uint8_t*)WHITE RED RED RED RED RED WHITE,
    (uint8_t*)BLACK RED RED RED RED RED WHITE,
    (uint8_t*)BLACK RED RED RED RED RED WHITE,
    (uint8_t*)WHITE RED RED RED RED RED WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE WHITE WHITE,
};

static uint8_t* kMultipleStretch10x7[] = {
    (uint8_t*)WHITE WHITE BLACK WHITE BLACK BLACK WHITE BLACK WHITE WHITE,
    (uint8_t*)BLACK RED BLUE RED BLUE BLUE RED BLUE RED WHITE,
    (uint8_t*)BLACK RED BLUE RED BLUE BLUE RED BLUE RED WHITE,
    (uint8_t*)WHITE RED BLUE RED BLUE BLUE RED BLUE RED WHITE,
    (uint8_t*)BLACK RED BLUE RED BLUE BLUE RED BLUE RED WHITE,
    (uint8_t*)BLACK RED BLUE RED BLUE BLUE RED BLUE RED WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE,
};

static uint8_t* kPadding6x5[] = {
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE BLACK,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE WHITE,
    (uint8_t*)WHITE WHITE BLACK BLACK WHITE WHITE,
};

static uint8_t* kLayoutBoundsWrongEdge3x3[] = {
    (uint8_t*)WHITE RED WHITE, (uint8_t*)RED WHITE WHITE,
    (uint8_t*)WHITE WHITE WHITE,
};

static uint8_t* kLayoutBoundsNotEdgeAligned5x5[] = {
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE RED,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE,
    (uint8_t*)WHITE WHITE RED WHITE WHITE,
};

static uint8_t* kLayoutBounds5x5[] = {
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE RED,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE RED,
    (uint8_t*)WHITE RED WHITE RED WHITE,
};

static uint8_t* kAsymmetricLayoutBounds5x5[] = {
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE RED,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE,
    (uint8_t*)WHITE RED WHITE WHITE WHITE,
};

static uint8_t* kPaddingAndLayoutBounds5x5[] = {
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE RED,
    (uint8_t*)WHITE WHITE WHITE WHITE BLACK,
    (uint8_t*)WHITE WHITE WHITE WHITE RED,
    (uint8_t*)WHITE RED BLACK RED WHITE,
};

static uint8_t* kColorfulImage5x5[] = {
    (uint8_t*)WHITE BLACK WHITE BLACK WHITE,
    (uint8_t*)BLACK RED BLUE GREEN WHITE,
    (uint8_t*)BLACK RED GREEN GREEN WHITE,
    (uint8_t*)WHITE TRANS BLUE GREEN WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE,
};

static uint8_t* kOutlineOpaque10x10[] = {
    (uint8_t*)WHITE BLACK BLACK BLACK BLACK BLACK BLACK BLACK BLACK WHITE,
    (uint8_t*)WHITE TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS WHITE,
    (uint8_t*)WHITE TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS WHITE,
    (uint8_t*)WHITE TRANS TRANS GREEN GREEN GREEN GREEN TRANS TRANS WHITE,
    (uint8_t*)WHITE TRANS TRANS GREEN GREEN GREEN GREEN TRANS TRANS WHITE,
    (uint8_t*)WHITE TRANS TRANS GREEN GREEN GREEN GREEN TRANS TRANS WHITE,
    (uint8_t*)WHITE TRANS TRANS GREEN GREEN GREEN GREEN TRANS TRANS WHITE,
    (uint8_t*)WHITE TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS WHITE,
    (uint8_t*)WHITE TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE,
};

static uint8_t* kOutlineTranslucent10x10[] = {
    (uint8_t*)WHITE BLACK BLACK BLACK BLACK BLACK BLACK BLACK BLACK WHITE,
    (uint8_t*)WHITE TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS WHITE,
    (uint8_t*)WHITE TRANS TRANS GR_20 GR_20 GR_20 GR_20 TRANS TRANS WHITE,
    (uint8_t*)WHITE TRANS TRANS GR_50 GR_50 GR_50 GR_50 TRANS TRANS WHITE,
    (uint8_t*)WHITE TRANS GR_20 GR_50 GR_70 GR_70 GR_50 GR_20 TRANS WHITE,
    (uint8_t*)WHITE TRANS GR_20 GR_50 GR_70 GR_70 GR_50 GR_20 TRANS WHITE,
    (uint8_t*)WHITE TRANS TRANS GR_50 GR_50 GR_50 GR_50 TRANS TRANS WHITE,
    (uint8_t*)WHITE TRANS TRANS GR_20 GR_20 GR_20 GR_20 TRANS TRANS WHITE,
    (uint8_t*)WHITE TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE,
};

static uint8_t* kOutlineOffsetTranslucent12x10[] = {
    (uint8_t*)
        WHITE WHITE WHITE BLACK BLACK BLACK BLACK BLACK BLACK BLACK BLACK WHITE,
    (uint8_t*)
        WHITE TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS WHITE,
    (uint8_t*)
        WHITE TRANS TRANS TRANS TRANS GR_20 GR_20 GR_20 GR_20 TRANS TRANS WHITE,
    (uint8_t*)
        WHITE TRANS TRANS TRANS TRANS GR_50 GR_50 GR_50 GR_50 TRANS TRANS WHITE,
    (uint8_t*)
        WHITE TRANS TRANS TRANS GR_20 GR_50 GR_70 GR_70 GR_50 GR_20 TRANS WHITE,
    (uint8_t*)
        WHITE TRANS TRANS TRANS GR_20 GR_50 GR_70 GR_70 GR_50 GR_20 TRANS WHITE,
    (uint8_t*)
        WHITE TRANS TRANS TRANS TRANS GR_50 GR_50 GR_50 GR_50 TRANS TRANS WHITE,
    (uint8_t*)
        WHITE TRANS TRANS TRANS TRANS GR_20 GR_20 GR_20 GR_20 TRANS TRANS WHITE,
    (uint8_t*)
        WHITE TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS WHITE,
    (uint8_t*)
        WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE WHITE,
};

static uint8_t* kOutlineRadius5x5[] = {
    (uint8_t*)WHITE BLACK BLACK BLACK WHITE,
    (uint8_t*)BLACK TRANS GREEN TRANS WHITE,
    (uint8_t*)BLACK GREEN GREEN GREEN WHITE,
    (uint8_t*)BLACK TRANS GREEN TRANS WHITE,
    (uint8_t*)WHITE WHITE WHITE WHITE WHITE,
};

static uint8_t* kStretchAndPadding5x5[] = {
    (uint8_t*)WHITE WHITE BLACK WHITE WHITE, (uint8_t*)WHITE RED RED RED WHITE,
    (uint8_t*)BLACK RED RED RED BLACK,       (uint8_t*)WHITE RED RED RED WHITE,
    (uint8_t*)WHITE WHITE BLACK WHITE WHITE,
};

TEST(NinePatchTest, Minimum3x3) {
  std::string err;
  EXPECT_EQ(nullptr, NinePatch::Create(k2x2, 2, 2, &err));
  EXPECT_FALSE(err.empty());
}

TEST(NinePatchTest, MixedNeutralColors) {
  std::string err;
  EXPECT_EQ(nullptr, NinePatch::Create(kMixedNeutralColor3x3, 3, 3, &err));
  EXPECT_FALSE(err.empty());
}

TEST(NinePatchTest, TransparentNeutralColor) {
  std::string err;
  EXPECT_NE(nullptr,
            NinePatch::Create(kTransparentNeutralColor3x3, 3, 3, &err));
}

TEST(NinePatchTest, SingleStretchRegion) {
  std::string err;
  std::unique_ptr<NinePatch> nine_patch =
      NinePatch::Create(kSingleStretch7x6, 7, 6, &err);
  ASSERT_NE(nullptr, nine_patch);

  ASSERT_EQ(1u, nine_patch->horizontal_stretch_regions.size());
  ASSERT_EQ(1u, nine_patch->vertical_stretch_regions.size());

  EXPECT_EQ(Range(1, 4), nine_patch->horizontal_stretch_regions.front());
  EXPECT_EQ(Range(1, 3), nine_patch->vertical_stretch_regions.front());
}

TEST(NinePatchTest, MultipleStretchRegions) {
  std::string err;
  std::unique_ptr<NinePatch> nine_patch =
      NinePatch::Create(kMultipleStretch10x7, 10, 7, &err);
  ASSERT_NE(nullptr, nine_patch);

  ASSERT_EQ(3u, nine_patch->horizontal_stretch_regions.size());
  ASSERT_EQ(2u, nine_patch->vertical_stretch_regions.size());

  EXPECT_EQ(Range(1, 2), nine_patch->horizontal_stretch_regions[0]);
  EXPECT_EQ(Range(3, 5), nine_patch->horizontal_stretch_regions[1]);
  EXPECT_EQ(Range(6, 7), nine_patch->horizontal_stretch_regions[2]);

  EXPECT_EQ(Range(0, 2), nine_patch->vertical_stretch_regions[0]);
  EXPECT_EQ(Range(3, 5), nine_patch->vertical_stretch_regions[1]);
}

TEST(NinePatchTest, InferPaddingFromStretchRegions) {
  std::string err;
  std::unique_ptr<NinePatch> nine_patch =
      NinePatch::Create(kMultipleStretch10x7, 10, 7, &err);
  ASSERT_NE(nullptr, nine_patch);
  EXPECT_EQ(Bounds(1, 0, 1, 0), nine_patch->padding);
}

TEST(NinePatchTest, Padding) {
  std::string err;
  std::unique_ptr<NinePatch> nine_patch =
      NinePatch::Create(kPadding6x5, 6, 5, &err);
  ASSERT_NE(nullptr, nine_patch);
  EXPECT_EQ(Bounds(1, 1, 1, 1), nine_patch->padding);
}

TEST(NinePatchTest, LayoutBoundsAreOnWrongEdge) {
  std::string err;
  EXPECT_EQ(nullptr, NinePatch::Create(kLayoutBoundsWrongEdge3x3, 3, 3, &err));
  EXPECT_FALSE(err.empty());
}

TEST(NinePatchTest, LayoutBoundsMustTouchEdges) {
  std::string err;
  EXPECT_EQ(nullptr,
            NinePatch::Create(kLayoutBoundsNotEdgeAligned5x5, 5, 5, &err));
  EXPECT_FALSE(err.empty());
}

TEST(NinePatchTest, LayoutBounds) {
  std::string err;
  std::unique_ptr<NinePatch> nine_patch =
      NinePatch::Create(kLayoutBounds5x5, 5, 5, &err);
  ASSERT_NE(nullptr, nine_patch);
  EXPECT_EQ(Bounds(1, 1, 1, 1), nine_patch->layout_bounds);

  nine_patch = NinePatch::Create(kAsymmetricLayoutBounds5x5, 5, 5, &err);
  ASSERT_NE(nullptr, nine_patch);
  EXPECT_EQ(Bounds(1, 1, 0, 0), nine_patch->layout_bounds);
}

TEST(NinePatchTest, PaddingAndLayoutBounds) {
  std::string err;
  std::unique_ptr<NinePatch> nine_patch =
      NinePatch::Create(kPaddingAndLayoutBounds5x5, 5, 5, &err);
  ASSERT_NE(nullptr, nine_patch);
  EXPECT_EQ(Bounds(1, 1, 1, 1), nine_patch->padding);
  EXPECT_EQ(Bounds(1, 1, 1, 1), nine_patch->layout_bounds);
}

TEST(NinePatchTest, RegionColorsAreCorrect) {
  std::string err;
  std::unique_ptr<NinePatch> nine_patch =
      NinePatch::Create(kColorfulImage5x5, 5, 5, &err);
  ASSERT_NE(nullptr, nine_patch);

  std::vector<uint32_t> expected_colors = {
      NinePatch::PackRGBA((uint8_t*)RED),
      (uint32_t)android::Res_png_9patch::NO_COLOR,
      NinePatch::PackRGBA((uint8_t*)GREEN),
      (uint32_t)android::Res_png_9patch::TRANSPARENT_COLOR,
      NinePatch::PackRGBA((uint8_t*)BLUE),
      NinePatch::PackRGBA((uint8_t*)GREEN),
  };
  EXPECT_EQ(expected_colors, nine_patch->region_colors);
}

TEST(NinePatchTest, OutlineFromOpaqueImage) {
  std::string err;
  std::unique_ptr<NinePatch> nine_patch =
      NinePatch::Create(kOutlineOpaque10x10, 10, 10, &err);
  ASSERT_NE(nullptr, nine_patch);
  EXPECT_EQ(Bounds(2, 2, 2, 2), nine_patch->outline);
  EXPECT_EQ(0x000000ffu, nine_patch->outline_alpha);
  EXPECT_EQ(0.0f, nine_patch->outline_radius);
}

TEST(NinePatchTest, OutlineFromTranslucentImage) {
  std::string err;
  std::unique_ptr<NinePatch> nine_patch =
      NinePatch::Create(kOutlineTranslucent10x10, 10, 10, &err);
  ASSERT_NE(nullptr, nine_patch);
  EXPECT_EQ(Bounds(3, 3, 3, 3), nine_patch->outline);
  EXPECT_EQ(0x000000b3u, nine_patch->outline_alpha);
  EXPECT_EQ(0.0f, nine_patch->outline_radius);
}

TEST(NinePatchTest, OutlineFromOffCenterImage) {
  std::string err;
  std::unique_ptr<NinePatch> nine_patch =
      NinePatch::Create(kOutlineOffsetTranslucent12x10, 12, 10, &err);
  ASSERT_NE(nullptr, nine_patch);

  // TODO(adamlesinski): The old AAPT algorithm searches from the outside to the
  // middle for each inset. If the outline is shifted, the search may not find a
  // closer bounds.
  // This check should be:
  //   EXPECT_EQ(Bounds(5, 3, 3, 3), ninePatch->outline);
  // but until I know what behavior I'm breaking, I will leave it at the
  // incorrect:
  EXPECT_EQ(Bounds(4, 3, 3, 3), nine_patch->outline);

  EXPECT_EQ(0x000000b3u, nine_patch->outline_alpha);
  EXPECT_EQ(0.0f, nine_patch->outline_radius);
}

TEST(NinePatchTest, OutlineRadius) {
  std::string err;
  std::unique_ptr<NinePatch> nine_patch =
      NinePatch::Create(kOutlineRadius5x5, 5, 5, &err);
  ASSERT_NE(nullptr, nine_patch);
  EXPECT_EQ(Bounds(0, 0, 0, 0), nine_patch->outline);
  EXPECT_EQ(3.4142f, nine_patch->outline_radius);
}

::testing::AssertionResult BigEndianOne(uint8_t* cursor) {
  if (cursor[0] == 0 && cursor[1] == 0 && cursor[2] == 0 && cursor[3] == 1) {
    return ::testing::AssertionSuccess();
  }
  return ::testing::AssertionFailure() << "Not BigEndian 1";
}

TEST(NinePatchTest, SerializePngEndianness) {
  std::string err;
  std::unique_ptr<NinePatch> nine_patch =
      NinePatch::Create(kStretchAndPadding5x5, 5, 5, &err);
  ASSERT_NE(nullptr, nine_patch);

  size_t len;
  std::unique_ptr<uint8_t[]> data = nine_patch->SerializeBase(&len);
  ASSERT_NE(nullptr, data);
  ASSERT_NE(0u, len);

  // Skip past wasDeserialized + numXDivs + numYDivs + numColors + xDivsOffset +
  // yDivsOffset
  // (12 bytes)
  uint8_t* cursor = data.get() + 12;

  // Check that padding is big-endian. Expecting value 1.
  EXPECT_TRUE(BigEndianOne(cursor));
  EXPECT_TRUE(BigEndianOne(cursor + 4));
  EXPECT_TRUE(BigEndianOne(cursor + 8));
  EXPECT_TRUE(BigEndianOne(cursor + 12));
}

}  // namespace aapt
