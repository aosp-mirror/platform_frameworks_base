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

#ifndef AAPT_COMPILE_IMAGE_H
#define AAPT_COMPILE_IMAGE_H

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "android-base/macros.h"

namespace aapt {

/**
 * An in-memory image, loaded from disk, with pixels in RGBA_8888 format.
 */
class Image {
 public:
  explicit Image() = default;

  /**
   * A `height` sized array of pointers, where each element points to a
   * `width` sized row of RGBA_8888 pixels.
   */
  std::unique_ptr<uint8_t* []> rows;

  /**
   * The width of the image in RGBA_8888 pixels. This is int32_t because of
   * 9-patch data
   * format limitations.
   */
  int32_t width = 0;

  /**
   * The height of the image in RGBA_8888 pixels. This is int32_t because of
   * 9-patch data
   * format limitations.
   */
  int32_t height = 0;

  /**
   * Buffer to the raw image data stored sequentially.
   * Use `rows` to access the data on a row-by-row basis.
   */
  std::unique_ptr<uint8_t[]> data;

 private:
  DISALLOW_COPY_AND_ASSIGN(Image);
};

/**
 * A range of pixel values, starting at 'start' and ending before 'end'
 * exclusive. Or rather [a, b).
 */
struct Range {
  int32_t start = 0;
  int32_t end = 0;

  explicit Range() = default;
  inline explicit Range(int32_t s, int32_t e) : start(s), end(e) {}
};

inline bool operator==(const Range& left, const Range& right) {
  return left.start == right.start && left.end == right.end;
}

/**
 * Inset lengths from all edges of a rectangle. `left` and `top` are measured
 * from the left and top
 * edges, while `right` and `bottom` are measured from the right and bottom
 * edges, respectively.
 */
struct Bounds {
  int32_t left = 0;
  int32_t top = 0;
  int32_t right = 0;
  int32_t bottom = 0;

  explicit Bounds() = default;
  inline explicit Bounds(int32_t l, int32_t t, int32_t r, int32_t b)
      : left(l), top(t), right(r), bottom(b) {}

  bool nonZero() const;
};

inline bool Bounds::nonZero() const {
  return left != 0 || top != 0 || right != 0 || bottom != 0;
}

inline bool operator==(const Bounds& left, const Bounds& right) {
  return left.left == right.left && left.top == right.top &&
         left.right == right.right && left.bottom == right.bottom;
}

/**
 * Contains 9-patch data from a source image. All measurements exclude the 1px
 * border of the
 * source 9-patch image.
 */
class NinePatch {
 public:
  static std::unique_ptr<NinePatch> Create(uint8_t** rows, const int32_t width,
                                           const int32_t height,
                                           std::string* err_out);

  /**
   * Packs the RGBA_8888 data pointed to by pixel into a uint32_t
   * with format 0xAARRGGBB (the way 9-patch expects it).
   */
  static uint32_t PackRGBA(const uint8_t* pixel);

  /**
   * 9-patch content padding/insets. All positions are relative to the 9-patch
   * NOT including the 1px thick source border.
   */
  Bounds padding;

  /**
   * Optical layout bounds/insets. This overrides the padding for
   * layout purposes. All positions are relative to the 9-patch
   * NOT including the 1px thick source border.
   * See
   * https://developer.android.com/about/versions/android-4.3.html#OpticalBounds
   */
  Bounds layout_bounds;

  /**
   * Outline of the image, calculated based on opacity.
   */
  Bounds outline;

  /**
   * The computed radius of the outline. If non-zero, the outline is a
   * rounded-rect.
   */
  float outline_radius = 0.0f;

  /**
   * The largest alpha value within the outline.
   */
  uint32_t outline_alpha = 0x000000ffu;

  /**
   * Horizontal regions of the image that are stretchable.
   * All positions are relative to the 9-patch
   * NOT including the 1px thick source border.
   */
  std::vector<Range> horizontal_stretch_regions;

  /**
   * Vertical regions of the image that are stretchable.
   * All positions are relative to the 9-patch
   * NOT including the 1px thick source border.
   */
  std::vector<Range> vertical_stretch_regions;

  /**
   * The colors within each region, fixed or stretchable.
   * For w*h regions, the color of region (x,y) is addressable
   * via index y*w + x.
   */
  std::vector<uint32_t> region_colors;

  /**
   * Returns serialized data containing the original basic 9-patch meta data.
   * Optical layout bounds and round rect outline data must be serialized
   * separately using SerializeOpticalLayoutBounds() and
   * SerializeRoundedRectOutline().
   */
  std::unique_ptr<uint8_t[]> SerializeBase(size_t* out_len) const;

  /**
   * Serializes the layout bounds.
   */
  std::unique_ptr<uint8_t[]> SerializeLayoutBounds(size_t* out_len) const;

  /**
   * Serializes the rounded-rect outline.
   */
  std::unique_ptr<uint8_t[]> SerializeRoundedRectOutline(size_t* out_len) const;

 private:
  explicit NinePatch() = default;

  DISALLOW_COPY_AND_ASSIGN(NinePatch);
};

::std::ostream& operator<<(::std::ostream& out, const Range& range);
::std::ostream& operator<<(::std::ostream& out, const Bounds& bounds);
::std::ostream& operator<<(::std::ostream& out, const NinePatch& nine_patch);

}  // namespace aapt

#endif /* AAPT_COMPILE_IMAGE_H */
