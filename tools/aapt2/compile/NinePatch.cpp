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

#include <sstream>
#include <string>
#include <vector>

#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"

#include "util/Util.h"

using android::StringPiece;

namespace aapt {

// Colors in the format 0xAARRGGBB (the way 9-patch expects it).
constexpr static const uint32_t kColorOpaqueWhite = 0xffffffffu;
constexpr static const uint32_t kColorOpaqueBlack = 0xff000000u;
constexpr static const uint32_t kColorOpaqueRed = 0xffff0000u;

constexpr static const uint32_t kPrimaryColor = kColorOpaqueBlack;
constexpr static const uint32_t kSecondaryColor = kColorOpaqueRed;

/**
 * Returns the alpha value encoded in the 0xAARRGBB encoded pixel.
 */
static uint32_t get_alpha(uint32_t color);

/**
 * Determines whether a color on an ImageLine is valid.
 * A 9patch image may use a transparent color as neutral,
 * or a fully opaque white color as neutral, based on the
 * pixel color at (0,0) of the image. One or the other is fine,
 * but we need to ensure consistency throughout the image.
 */
class ColorValidator {
 public:
  virtual ~ColorValidator() = default;

  /**
   * Returns true if the color specified is a neutral color
   * (no padding, stretching, or optical bounds).
   */
  virtual bool IsNeutralColor(uint32_t color) const = 0;

  /**
   * Returns true if the color is either a neutral color
   * or one denoting padding, stretching, or optical bounds.
   */
  bool IsValidColor(uint32_t color) const {
    switch (color) {
      case kPrimaryColor:
      case kSecondaryColor:
        return true;
    }
    return IsNeutralColor(color);
  }
};

// Walks an ImageLine and records Ranges of primary and secondary colors.
// The primary color is black and is used to denote a padding or stretching
// range,
// depending on which border we're iterating over.
// The secondary color is red and is used to denote optical bounds.
//
// An ImageLine is a templated-interface that would look something like this if
// it
// were polymorphic:
//
// class ImageLine {
// public:
//      virtual int32_t GetLength() const = 0;
//      virtual uint32_t GetColor(int32_t idx) const = 0;
// };
//
template <typename ImageLine>
static bool FillRanges(const ImageLine* image_line,
                       const ColorValidator* color_validator,
                       std::vector<Range>* primary_ranges,
                       std::vector<Range>* secondary_ranges,
                       std::string* out_err) {
  const int32_t length = image_line->GetLength();

  uint32_t last_color = 0xffffffffu;
  for (int32_t idx = 1; idx < length - 1; idx++) {
    const uint32_t color = image_line->GetColor(idx);
    if (!color_validator->IsValidColor(color)) {
      *out_err = "found an invalid color";
      return false;
    }

    if (color != last_color) {
      // We are ending a range. Which range?
      // note: encode the x offset without the final 1 pixel border.
      if (last_color == kPrimaryColor) {
        primary_ranges->back().end = idx - 1;
      } else if (last_color == kSecondaryColor) {
        secondary_ranges->back().end = idx - 1;
      }

      // We are starting a range. Which range?
      // note: encode the x offset without the final 1 pixel border.
      if (color == kPrimaryColor) {
        primary_ranges->push_back(Range(idx - 1, length - 2));
      } else if (color == kSecondaryColor) {
        secondary_ranges->push_back(Range(idx - 1, length - 2));
      }
      last_color = color;
    }
  }
  return true;
}

/**
 * Iterates over a row in an image. Implements the templated ImageLine
 * interface.
 */
class HorizontalImageLine {
 public:
  explicit HorizontalImageLine(uint8_t** rows, int32_t xoffset, int32_t yoffset,
                               int32_t length)
      : rows_(rows), xoffset_(xoffset), yoffset_(yoffset), length_(length) {}

  inline int32_t GetLength() const { return length_; }

  inline uint32_t GetColor(int32_t idx) const {
    return NinePatch::PackRGBA(rows_[yoffset_] + (idx + xoffset_) * 4);
  }

 private:
  uint8_t** rows_;
  int32_t xoffset_, yoffset_, length_;

  DISALLOW_COPY_AND_ASSIGN(HorizontalImageLine);
};

/**
 * Iterates over a column in an image. Implements the templated ImageLine
 * interface.
 */
class VerticalImageLine {
 public:
  explicit VerticalImageLine(uint8_t** rows, int32_t xoffset, int32_t yoffset,
                             int32_t length)
      : rows_(rows), xoffset_(xoffset), yoffset_(yoffset), length_(length) {}

  inline int32_t GetLength() const { return length_; }

  inline uint32_t GetColor(int32_t idx) const {
    return NinePatch::PackRGBA(rows_[yoffset_ + idx] + (xoffset_ * 4));
  }

 private:
  uint8_t** rows_;
  int32_t xoffset_, yoffset_, length_;

  DISALLOW_COPY_AND_ASSIGN(VerticalImageLine);
};

class DiagonalImageLine {
 public:
  explicit DiagonalImageLine(uint8_t** rows, int32_t xoffset, int32_t yoffset,
                             int32_t xstep, int32_t ystep, int32_t length)
      : rows_(rows),
        xoffset_(xoffset),
        yoffset_(yoffset),
        xstep_(xstep),
        ystep_(ystep),
        length_(length) {}

  inline int32_t GetLength() const { return length_; }

  inline uint32_t GetColor(int32_t idx) const {
    return NinePatch::PackRGBA(rows_[yoffset_ + (idx * ystep_)] +
                               ((idx + xoffset_) * xstep_) * 4);
  }

 private:
  uint8_t** rows_;
  int32_t xoffset_, yoffset_, xstep_, ystep_, length_;

  DISALLOW_COPY_AND_ASSIGN(DiagonalImageLine);
};

class TransparentNeutralColorValidator : public ColorValidator {
 public:
  bool IsNeutralColor(uint32_t color) const override {
    return get_alpha(color) == 0;
  }
};

class WhiteNeutralColorValidator : public ColorValidator {
 public:
  bool IsNeutralColor(uint32_t color) const override {
    return color == kColorOpaqueWhite;
  }
};

inline static uint32_t get_alpha(uint32_t color) {
  return (color & 0xff000000u) >> 24;
}

static bool PopulateBounds(const std::vector<Range>& padding,
                           const std::vector<Range>& layout_bounds,
                           const std::vector<Range>& stretch_regions,
                           const int32_t length, int32_t* padding_start,
                           int32_t* padding_end, int32_t* layout_start,
                           int32_t* layout_end, const StringPiece& edge_name,
                           std::string* out_err) {
  if (padding.size() > 1) {
    std::stringstream err_stream;
    err_stream << "too many padding sections on " << edge_name << " border";
    *out_err = err_stream.str();
    return false;
  }

  *padding_start = 0;
  *padding_end = 0;
  if (!padding.empty()) {
    const Range& range = padding.front();
    *padding_start = range.start;
    *padding_end = length - range.end;
  } else if (!stretch_regions.empty()) {
    // No padding was defined. Compute the padding from the first and last
    // stretch regions.
    *padding_start = stretch_regions.front().start;
    *padding_end = length - stretch_regions.back().end;
  }

  if (layout_bounds.size() > 2) {
    std::stringstream err_stream;
    err_stream << "too many layout bounds sections on " << edge_name
               << " border";
    *out_err = err_stream.str();
    return false;
  }

  *layout_start = 0;
  *layout_end = 0;
  if (layout_bounds.size() >= 1) {
    const Range& range = layout_bounds.front();
    // If there is only one layout bound segment, it might not start at 0, but
    // then it should
    // end at length.
    if (range.start != 0 && range.end != length) {
      std::stringstream err_stream;
      err_stream << "layout bounds on " << edge_name
                 << " border must start at edge";
      *out_err = err_stream.str();
      return false;
    }
    *layout_start = range.end;

    if (layout_bounds.size() >= 2) {
      const Range& range = layout_bounds.back();
      if (range.end != length) {
        std::stringstream err_stream;
        err_stream << "layout bounds on " << edge_name
                   << " border must start at edge";
        *out_err = err_stream.str();
        return false;
      }
      *layout_end = length - range.start;
    }
  }
  return true;
}

static int32_t CalculateSegmentCount(const std::vector<Range>& stretch_regions,
                                     int32_t length) {
  if (stretch_regions.size() == 0) {
    return 0;
  }

  const bool start_is_fixed = stretch_regions.front().start != 0;
  const bool end_is_fixed = stretch_regions.back().end != length;
  int32_t modifier = 0;
  if (start_is_fixed && end_is_fixed) {
    modifier = 1;
  } else if (!start_is_fixed && !end_is_fixed) {
    modifier = -1;
  }
  return static_cast<int32_t>(stretch_regions.size()) * 2 + modifier;
}

static uint32_t GetRegionColor(uint8_t** rows, const Bounds& region) {
  // Sample the first pixel to compare against.
  const uint32_t expected_color =
      NinePatch::PackRGBA(rows[region.top] + region.left * 4);
  for (int32_t y = region.top; y < region.bottom; y++) {
    const uint8_t* row = rows[y];
    for (int32_t x = region.left; x < region.right; x++) {
      const uint32_t color = NinePatch::PackRGBA(row + x * 4);
      if (get_alpha(color) == 0) {
        // The color is transparent.
        // If the expectedColor is not transparent, NO_COLOR.
        if (get_alpha(expected_color) != 0) {
          return android::Res_png_9patch::NO_COLOR;
        }
      } else if (color != expected_color) {
        return android::Res_png_9patch::NO_COLOR;
      }
    }
  }

  if (get_alpha(expected_color) == 0) {
    return android::Res_png_9patch::TRANSPARENT_COLOR;
  }
  return expected_color;
}

// Fills out_colors with each 9-patch section's color. If the whole section is
// transparent,
// it gets the special TRANSPARENT color. If the whole section is the same
// color, it is assigned
// that color. Otherwise it gets the special NO_COLOR color.
//
// Note that the rows contain the 9-patch 1px border, and the indices in the
// stretch regions are
// already offset to exclude the border. This means that each time the rows are
// accessed,
// the indices must be offset by 1.
//
// width and height also include the 9-patch 1px border.
static void CalculateRegionColors(
    uint8_t** rows, const std::vector<Range>& horizontal_stretch_regions,
    const std::vector<Range>& vertical_stretch_regions, const int32_t width,
    const int32_t height, std::vector<uint32_t>* out_colors) {
  int32_t next_top = 0;
  Bounds bounds;
  auto row_iter = vertical_stretch_regions.begin();
  while (next_top != height) {
    if (row_iter != vertical_stretch_regions.end()) {
      if (next_top != row_iter->start) {
        // This is a fixed segment.
        // Offset the bounds by 1 to accommodate the border.
        bounds.top = next_top + 1;
        bounds.bottom = row_iter->start + 1;
        next_top = row_iter->start;
      } else {
        // This is a stretchy segment.
        // Offset the bounds by 1 to accommodate the border.
        bounds.top = row_iter->start + 1;
        bounds.bottom = row_iter->end + 1;
        next_top = row_iter->end;
        ++row_iter;
      }
    } else {
      // This is the end, fixed section.
      // Offset the bounds by 1 to accommodate the border.
      bounds.top = next_top + 1;
      bounds.bottom = height + 1;
      next_top = height;
    }

    int32_t next_left = 0;
    auto col_iter = horizontal_stretch_regions.begin();
    while (next_left != width) {
      if (col_iter != horizontal_stretch_regions.end()) {
        if (next_left != col_iter->start) {
          // This is a fixed segment.
          // Offset the bounds by 1 to accommodate the border.
          bounds.left = next_left + 1;
          bounds.right = col_iter->start + 1;
          next_left = col_iter->start;
        } else {
          // This is a stretchy segment.
          // Offset the bounds by 1 to accommodate the border.
          bounds.left = col_iter->start + 1;
          bounds.right = col_iter->end + 1;
          next_left = col_iter->end;
          ++col_iter;
        }
      } else {
        // This is the end, fixed section.
        // Offset the bounds by 1 to accommodate the border.
        bounds.left = next_left + 1;
        bounds.right = width + 1;
        next_left = width;
      }
      out_colors->push_back(GetRegionColor(rows, bounds));
    }
  }
}

// Calculates the insets of a row/column of pixels based on where the largest
// alpha value begins
// (on both sides).
template <typename ImageLine>
static void FindOutlineInsets(const ImageLine* image_line, int32_t* out_start,
                              int32_t* out_end) {
  *out_start = 0;
  *out_end = 0;

  const int32_t length = image_line->GetLength();
  if (length < 3) {
    return;
  }

  // If the length is odd, we want both sides to process the center pixel,
  // so we use two different midpoints (to account for < and <= in the different
  // loops).
  const int32_t mid2 = length / 2;
  const int32_t mid1 = mid2 + (length % 2);

  uint32_t max_alpha = 0;
  for (int32_t i = 0; i < mid1 && max_alpha != 0xff; i++) {
    uint32_t alpha = get_alpha(image_line->GetColor(i));
    if (alpha > max_alpha) {
      max_alpha = alpha;
      *out_start = i;
    }
  }

  max_alpha = 0;
  for (int32_t i = length - 1; i >= mid2 && max_alpha != 0xff; i--) {
    uint32_t alpha = get_alpha(image_line->GetColor(i));
    if (alpha > max_alpha) {
      max_alpha = alpha;
      *out_end = length - (i + 1);
    }
  }
  return;
}

template <typename ImageLine>
static uint32_t FindMaxAlpha(const ImageLine* image_line) {
  const int32_t length = image_line->GetLength();
  uint32_t max_alpha = 0;
  for (int32_t idx = 0; idx < length && max_alpha != 0xff; idx++) {
    uint32_t alpha = get_alpha(image_line->GetColor(idx));
    if (alpha > max_alpha) {
      max_alpha = alpha;
    }
  }
  return max_alpha;
}

// Pack the pixels in as 0xAARRGGBB (as 9-patch expects it).
uint32_t NinePatch::PackRGBA(const uint8_t* pixel) {
  return (pixel[3] << 24) | (pixel[0] << 16) | (pixel[1] << 8) | pixel[2];
}

std::unique_ptr<NinePatch> NinePatch::Create(uint8_t** rows,
                                             const int32_t width,
                                             const int32_t height,
                                             std::string* out_err) {
  if (width < 3 || height < 3) {
    *out_err = "image must be at least 3x3 (1x1 image with 1 pixel border)";
    return {};
  }

  std::vector<Range> horizontal_padding;
  std::vector<Range> horizontal_layout_bounds;
  std::vector<Range> vertical_padding;
  std::vector<Range> vertical_layout_bounds;
  std::vector<Range> unexpected_ranges;
  std::unique_ptr<ColorValidator> color_validator;

  if (rows[0][3] == 0) {
    color_validator = util::make_unique<TransparentNeutralColorValidator>();
  } else if (PackRGBA(rows[0]) == kColorOpaqueWhite) {
    color_validator = util::make_unique<WhiteNeutralColorValidator>();
  } else {
    *out_err =
        "top-left corner pixel must be either opaque white or transparent";
    return {};
  }

  // Private constructor, can't use make_unique.
  auto nine_patch = std::unique_ptr<NinePatch>(new NinePatch());

  HorizontalImageLine top_row(rows, 0, 0, width);
  if (!FillRanges(&top_row, color_validator.get(),
                  &nine_patch->horizontal_stretch_regions, &unexpected_ranges,
                  out_err)) {
    return {};
  }

  if (!unexpected_ranges.empty()) {
    const Range& range = unexpected_ranges[0];
    std::stringstream err_stream;
    err_stream << "found unexpected optical bounds (red pixel) on top border "
               << "at x=" << range.start + 1;
    *out_err = err_stream.str();
    return {};
  }

  VerticalImageLine left_col(rows, 0, 0, height);
  if (!FillRanges(&left_col, color_validator.get(),
                  &nine_patch->vertical_stretch_regions, &unexpected_ranges,
                  out_err)) {
    return {};
  }

  if (!unexpected_ranges.empty()) {
    const Range& range = unexpected_ranges[0];
    std::stringstream err_stream;
    err_stream << "found unexpected optical bounds (red pixel) on left border "
               << "at y=" << range.start + 1;
    return {};
  }

  HorizontalImageLine bottom_row(rows, 0, height - 1, width);
  if (!FillRanges(&bottom_row, color_validator.get(), &horizontal_padding,
                  &horizontal_layout_bounds, out_err)) {
    return {};
  }

  if (!PopulateBounds(horizontal_padding, horizontal_layout_bounds,
                      nine_patch->horizontal_stretch_regions, width - 2,
                      &nine_patch->padding.left, &nine_patch->padding.right,
                      &nine_patch->layout_bounds.left,
                      &nine_patch->layout_bounds.right, "bottom", out_err)) {
    return {};
  }

  VerticalImageLine right_col(rows, width - 1, 0, height);
  if (!FillRanges(&right_col, color_validator.get(), &vertical_padding,
                  &vertical_layout_bounds, out_err)) {
    return {};
  }

  if (!PopulateBounds(vertical_padding, vertical_layout_bounds,
                      nine_patch->vertical_stretch_regions, height - 2,
                      &nine_patch->padding.top, &nine_patch->padding.bottom,
                      &nine_patch->layout_bounds.top,
                      &nine_patch->layout_bounds.bottom, "right", out_err)) {
    return {};
  }

  // Fill the region colors of the 9-patch.
  const int32_t num_rows =
      CalculateSegmentCount(nine_patch->horizontal_stretch_regions, width - 2);
  const int32_t num_cols =
      CalculateSegmentCount(nine_patch->vertical_stretch_regions, height - 2);
  if ((int64_t)num_rows * (int64_t)num_cols > 0x7f) {
    *out_err = "too many regions in 9-patch";
    return {};
  }

  nine_patch->region_colors.reserve(num_rows * num_cols);
  CalculateRegionColors(rows, nine_patch->horizontal_stretch_regions,
                        nine_patch->vertical_stretch_regions, width - 2,
                        height - 2, &nine_patch->region_colors);

  // Compute the outline based on opacity.

  // Find left and right extent of 9-patch content on center row.
  HorizontalImageLine mid_row(rows, 1, height / 2, width - 2);
  FindOutlineInsets(&mid_row, &nine_patch->outline.left,
                    &nine_patch->outline.right);

  // Find top and bottom extent of 9-patch content on center column.
  VerticalImageLine mid_col(rows, width / 2, 1, height - 2);
  FindOutlineInsets(&mid_col, &nine_patch->outline.top,
                    &nine_patch->outline.bottom);

  const int32_t outline_width =
      (width - 2) - nine_patch->outline.left - nine_patch->outline.right;
  const int32_t outline_height =
      (height - 2) - nine_patch->outline.top - nine_patch->outline.bottom;

  // Find the largest alpha value within the outline area.
  HorizontalImageLine outline_mid_row(
      rows, 1 + nine_patch->outline.left,
      1 + nine_patch->outline.top + (outline_height / 2), outline_width);
  VerticalImageLine outline_mid_col(
      rows, 1 + nine_patch->outline.left + (outline_width / 2),
      1 + nine_patch->outline.top, outline_height);
  nine_patch->outline_alpha =
      std::max(FindMaxAlpha(&outline_mid_row), FindMaxAlpha(&outline_mid_col));

  // Assuming the image is a round rect, compute the radius by marching
  // diagonally from the top left corner towards the center.
  DiagonalImageLine diagonal(rows, 1 + nine_patch->outline.left,
                             1 + nine_patch->outline.top, 1, 1,
                             std::min(outline_width, outline_height));
  int32_t top_left, bottom_right;
  FindOutlineInsets(&diagonal, &top_left, &bottom_right);

  /* Determine source radius based upon inset:
   *     sqrt(r^2 + r^2) = sqrt(i^2 + i^2) + r
   *     sqrt(2) * r = sqrt(2) * i + r
   *     (sqrt(2) - 1) * r = sqrt(2) * i
   *     r = sqrt(2) / (sqrt(2) - 1) * i
   */
  nine_patch->outline_radius = 3.4142f * top_left;
  return nine_patch;
}

std::unique_ptr<uint8_t[]> NinePatch::SerializeBase(size_t* outLen) const {
  android::Res_png_9patch data;
  data.numXDivs = static_cast<uint8_t>(horizontal_stretch_regions.size()) * 2;
  data.numYDivs = static_cast<uint8_t>(vertical_stretch_regions.size()) * 2;
  data.numColors = static_cast<uint8_t>(region_colors.size());
  data.paddingLeft = padding.left;
  data.paddingRight = padding.right;
  data.paddingTop = padding.top;
  data.paddingBottom = padding.bottom;

  auto buffer = std::unique_ptr<uint8_t[]>(new uint8_t[data.serializedSize()]);
  android::Res_png_9patch::serialize(
      data, (const int32_t*)horizontal_stretch_regions.data(),
      (const int32_t*)vertical_stretch_regions.data(), region_colors.data(),
      buffer.get());
  // Convert to file endianness.
  reinterpret_cast<android::Res_png_9patch*>(buffer.get())->deviceToFile();

  *outLen = data.serializedSize();
  return buffer;
}

std::unique_ptr<uint8_t[]> NinePatch::SerializeLayoutBounds(
    size_t* out_len) const {
  size_t chunk_len = sizeof(uint32_t) * 4;
  auto buffer = std::unique_ptr<uint8_t[]>(new uint8_t[chunk_len]);
  uint8_t* cursor = buffer.get();

  memcpy(cursor, &layout_bounds.left, sizeof(layout_bounds.left));
  cursor += sizeof(layout_bounds.left);

  memcpy(cursor, &layout_bounds.top, sizeof(layout_bounds.top));
  cursor += sizeof(layout_bounds.top);

  memcpy(cursor, &layout_bounds.right, sizeof(layout_bounds.right));
  cursor += sizeof(layout_bounds.right);

  memcpy(cursor, &layout_bounds.bottom, sizeof(layout_bounds.bottom));
  cursor += sizeof(layout_bounds.bottom);

  *out_len = chunk_len;
  return buffer;
}

std::unique_ptr<uint8_t[]> NinePatch::SerializeRoundedRectOutline(
    size_t* out_len) const {
  size_t chunk_len = sizeof(uint32_t) * 6;
  auto buffer = std::unique_ptr<uint8_t[]>(new uint8_t[chunk_len]);
  uint8_t* cursor = buffer.get();

  memcpy(cursor, &outline.left, sizeof(outline.left));
  cursor += sizeof(outline.left);

  memcpy(cursor, &outline.top, sizeof(outline.top));
  cursor += sizeof(outline.top);

  memcpy(cursor, &outline.right, sizeof(outline.right));
  cursor += sizeof(outline.right);

  memcpy(cursor, &outline.bottom, sizeof(outline.bottom));
  cursor += sizeof(outline.bottom);

  *((float*)cursor) = outline_radius;
  cursor += sizeof(outline_radius);

  *((uint32_t*)cursor) = outline_alpha;

  *out_len = chunk_len;
  return buffer;
}

::std::ostream& operator<<(::std::ostream& out, const Range& range) {
  return out << "[" << range.start << ", " << range.end << ")";
}

::std::ostream& operator<<(::std::ostream& out, const Bounds& bounds) {
  return out << "l=" << bounds.left << " t=" << bounds.top
             << " r=" << bounds.right << " b=" << bounds.bottom;
}

::std::ostream& operator<<(::std::ostream& out, const NinePatch& nine_patch) {
  return out << "horizontalStretch:"
             << util::Joiner(nine_patch.horizontal_stretch_regions, " ")
             << " verticalStretch:"
             << util::Joiner(nine_patch.vertical_stretch_regions, " ")
             << " padding: " << nine_patch.padding
             << ", bounds: " << nine_patch.layout_bounds
             << ", outline: " << nine_patch.outline
             << " rad=" << nine_patch.outline_radius
             << " alpha=" << nine_patch.outline_alpha;
}

}  // namespace aapt
