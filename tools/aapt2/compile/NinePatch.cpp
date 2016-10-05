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
#include "util/StringPiece.h"
#include "util/Util.h"

#include <androidfw/ResourceTypes.h>
#include <sstream>
#include <string>
#include <vector>

namespace aapt {

// Colors in the format 0xAARRGGBB (the way 9-patch expects it).
constexpr static const uint32_t kColorOpaqueWhite = 0xffffffffu;
constexpr static const uint32_t kColorOpaqueBlack = 0xff000000u;
constexpr static const uint32_t kColorOpaqueRed   = 0xffff0000u;

constexpr static const uint32_t kPrimaryColor = kColorOpaqueBlack;
constexpr static const uint32_t kSecondaryColor = kColorOpaqueRed;

/**
 * Returns the alpha value encoded in the 0xAARRGBB encoded pixel.
 */
static uint32_t getAlpha(uint32_t color);

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
    virtual bool isNeutralColor(uint32_t color) const = 0;

    /**
     * Returns true if the color is either a neutral color
     * or one denoting padding, stretching, or optical bounds.
     */
    bool isValidColor(uint32_t color) const {
        switch (color) {
        case kPrimaryColor:
        case kSecondaryColor:
            return true;
        }
        return isNeutralColor(color);
    }
};

// Walks an ImageLine and records Ranges of primary and secondary colors.
// The primary color is black and is used to denote a padding or stretching range,
// depending on which border we're iterating over.
// The secondary color is red and is used to denote optical bounds.
//
// An ImageLine is a templated-interface that would look something like this if it
// were polymorphic:
//
// class ImageLine {
// public:
//      virtual int32_t getLength() const = 0;
//      virtual uint32_t getColor(int32_t idx) const = 0;
// };
//
template <typename ImageLine>
static bool fillRanges(const ImageLine* imageLine,
                       const ColorValidator* colorValidator,
                       std::vector<Range>* primaryRanges,
                       std::vector<Range>* secondaryRanges,
                       std::string* err) {
    const int32_t length = imageLine->getLength();

    uint32_t lastColor = 0xffffffffu;
    for (int32_t idx = 1; idx < length - 1; idx++) {
        const uint32_t color = imageLine->getColor(idx);
        if (!colorValidator->isValidColor(color)) {
            *err = "found an invalid color";
            return false;
        }

        if (color != lastColor) {
            // We are ending a range. Which range?
            // note: encode the x offset without the final 1 pixel border.
            if (lastColor == kPrimaryColor) {
                primaryRanges->back().end = idx - 1;
            } else if (lastColor == kSecondaryColor) {
                secondaryRanges->back().end = idx - 1;
            }

            // We are starting a range. Which range?
            // note: encode the x offset without the final 1 pixel border.
            if (color == kPrimaryColor) {
                primaryRanges->push_back(Range(idx - 1, length - 2));
            } else if (color == kSecondaryColor) {
                secondaryRanges->push_back(Range(idx - 1, length - 2));
            }
            lastColor = color;
        }
    }
    return true;
}

/**
 * Iterates over a row in an image. Implements the templated ImageLine interface.
 */
class HorizontalImageLine {
public:
    explicit HorizontalImageLine(uint8_t** rows, int32_t xOffset, int32_t yOffset,
                                 int32_t length) :
            mRows(rows), mXOffset(xOffset), mYOffset(yOffset), mLength(length) {
    }

    inline int32_t getLength() const {
        return mLength;
    }

    inline uint32_t getColor(int32_t idx) const {
        return NinePatch::packRGBA(mRows[mYOffset] + (idx + mXOffset) * 4);
    }

private:
    uint8_t** mRows;
    int32_t mXOffset, mYOffset, mLength;

    DISALLOW_COPY_AND_ASSIGN(HorizontalImageLine);
};

/**
 * Iterates over a column in an image. Implements the templated ImageLine interface.
 */
class VerticalImageLine {
public:
    explicit VerticalImageLine(uint8_t** rows, int32_t xOffset, int32_t yOffset,
                               int32_t length) :
            mRows(rows), mXOffset(xOffset), mYOffset(yOffset), mLength(length) {
    }

    inline int32_t getLength() const {
        return mLength;
    }

    inline uint32_t getColor(int32_t idx) const {
        return NinePatch::packRGBA(mRows[mYOffset + idx] + (mXOffset * 4));
    }

private:
    uint8_t** mRows;
    int32_t mXOffset, mYOffset, mLength;

    DISALLOW_COPY_AND_ASSIGN(VerticalImageLine);
};

class DiagonalImageLine {
public:
    explicit DiagonalImageLine(uint8_t** rows, int32_t xOffset, int32_t yOffset,
                               int32_t xStep, int32_t yStep, int32_t length) :
            mRows(rows), mXOffset(xOffset), mYOffset(yOffset), mXStep(xStep), mYStep(yStep),
            mLength(length) {
    }

    inline int32_t getLength() const {
        return mLength;
    }

    inline uint32_t getColor(int32_t idx) const {
        return NinePatch::packRGBA(
                mRows[mYOffset + (idx * mYStep)] + ((idx + mXOffset) * mXStep) * 4);
    }

private:
    uint8_t** mRows;
    int32_t mXOffset, mYOffset, mXStep, mYStep, mLength;

    DISALLOW_COPY_AND_ASSIGN(DiagonalImageLine);
};

class TransparentNeutralColorValidator : public ColorValidator {
public:
    bool isNeutralColor(uint32_t color) const override {
        return getAlpha(color) == 0;
    }
};

class WhiteNeutralColorValidator : public ColorValidator {
public:
    bool isNeutralColor(uint32_t color) const override {
        return color == kColorOpaqueWhite;
    }
};

inline static uint32_t getAlpha(uint32_t color) {
    return (color & 0xff000000u) >> 24;
}

static bool populateBounds(const std::vector<Range>& padding,
                           const std::vector<Range>& layoutBounds,
                           const std::vector<Range>& stretchRegions,
                           const int32_t length,
                           int32_t* paddingStart, int32_t* paddingEnd,
                           int32_t* layoutStart, int32_t* layoutEnd,
                           const StringPiece& edgeName,
                           std::string* err) {
    if (padding.size() > 1) {
        std::stringstream errStream;
        errStream << "too many padding sections on " << edgeName << " border";
        *err = errStream.str();
        return false;
    }

    *paddingStart = 0;
    *paddingEnd = 0;
    if (!padding.empty()) {
        const Range& range = padding.front();
        *paddingStart = range.start;
        *paddingEnd = length - range.end;
    } else if (!stretchRegions.empty()) {
        // No padding was defined. Compute the padding from the first and last
        // stretch regions.
        *paddingStart = stretchRegions.front().start;
        *paddingEnd = length - stretchRegions.back().end;
    }

    if (layoutBounds.size() > 2) {
        std::stringstream errStream;
        errStream << "too many layout bounds sections on " << edgeName << " border";
        *err = errStream.str();
        return false;
    }

    *layoutStart = 0;
    *layoutEnd = 0;
    if (layoutBounds.size() >= 1) {
        const Range& range = layoutBounds.front();
        // If there is only one layout bound segment, it might not start at 0, but then it should
        // end at length.
        if (range.start != 0 && range.end != length) {
            std::stringstream errStream;
            errStream << "layout bounds on " << edgeName << " border must start at edge";
            *err = errStream.str();
            return false;
        }
        *layoutStart = range.end;

        if (layoutBounds.size() >= 2) {
            const Range& range = layoutBounds.back();
            if (range.end != length) {
                std::stringstream errStream;
                errStream << "layout bounds on " << edgeName << " border must start at edge";
                *err = errStream.str();
                return false;
            }
            *layoutEnd = length - range.start;
        }
    }
    return true;
}

static int32_t calculateSegmentCount(const std::vector<Range>& stretchRegions, int32_t length) {
    if (stretchRegions.size() == 0) {
        return 0;
    }

    const bool startIsFixed = stretchRegions.front().start != 0;
    const bool endIsFixed = stretchRegions.back().end != length;
    int32_t modifier = 0;
    if (startIsFixed && endIsFixed) {
        modifier = 1;
    } else if (!startIsFixed && !endIsFixed) {
        modifier = -1;
    }
    return static_cast<int32_t>(stretchRegions.size()) * 2 + modifier;
}

static uint32_t getRegionColor(uint8_t** rows, const Bounds& region) {
    // Sample the first pixel to compare against.
    const uint32_t expectedColor = NinePatch::packRGBA(rows[region.top] + region.left * 4);
    for (int32_t y = region.top; y < region.bottom; y++) {
        const uint8_t* row = rows[y];
        for (int32_t x = region.left; x < region.right; x++) {
            const uint32_t color = NinePatch::packRGBA(row + x * 4);
            if (getAlpha(color) == 0) {
                // The color is transparent.
                // If the expectedColor is not transparent, NO_COLOR.
                if (getAlpha(expectedColor) != 0) {
                    return android::Res_png_9patch::NO_COLOR;
                }
            } else if (color != expectedColor) {
                return android::Res_png_9patch::NO_COLOR;
            }
        }
    }

    if (getAlpha(expectedColor) == 0) {
        return android::Res_png_9patch::TRANSPARENT_COLOR;
    }
    return expectedColor;
}

// Fills outColors with each 9-patch section's colour. If the whole section is transparent,
// it gets the special TRANSPARENT colour. If the whole section is the same colour, it is assigned
// that colour. Otherwise it gets the special NO_COLOR colour.
//
// Note that the rows contain the 9-patch 1px border, and the indices in the stretch regions are
// already offset to exclude the border. This means that each time the rows are accessed,
// the indices must be offset by 1.
//
// width and height also include the 9-patch 1px border.
static void calculateRegionColors(uint8_t** rows,
                                  const std::vector<Range>& horizontalStretchRegions,
                                  const std::vector<Range>& verticalStretchRegions,
                                  const int32_t width, const int32_t height,
                                  std::vector<uint32_t>* outColors) {
    int32_t nextTop = 0;
    Bounds bounds;
    auto rowIter = verticalStretchRegions.begin();
    while (nextTop != height) {
        if (rowIter != verticalStretchRegions.end()) {
            if (nextTop != rowIter->start) {
                // This is a fixed segment.
                // Offset the bounds by 1 to accommodate the border.
                bounds.top = nextTop + 1;
                bounds.bottom = rowIter->start + 1;
                nextTop = rowIter->start;
            } else {
                // This is a stretchy segment.
                // Offset the bounds by 1 to accommodate the border.
                bounds.top = rowIter->start + 1;
                bounds.bottom = rowIter->end + 1;
                nextTop = rowIter->end;
                ++rowIter;
            }
        } else {
            // This is the end, fixed section.
            // Offset the bounds by 1 to accommodate the border.
            bounds.top = nextTop + 1;
            bounds.bottom = height + 1;
            nextTop = height;
         }

        int32_t nextLeft = 0;
        auto colIter = horizontalStretchRegions.begin();
        while (nextLeft != width) {
            if (colIter != horizontalStretchRegions.end()) {
                if (nextLeft != colIter->start) {
                    // This is a fixed segment.
                    // Offset the bounds by 1 to accommodate the border.
                    bounds.left = nextLeft + 1;
                    bounds.right = colIter->start + 1;
                    nextLeft = colIter->start;
                } else {
                    // This is a stretchy segment.
                    // Offset the bounds by 1 to accommodate the border.
                    bounds.left = colIter->start + 1;
                    bounds.right = colIter->end + 1;
                    nextLeft = colIter->end;
                    ++colIter;
                }
            } else {
                // This is the end, fixed section.
                // Offset the bounds by 1 to accommodate the border.
                bounds.left = nextLeft + 1;
                bounds.right = width + 1;
                nextLeft = width;
            }
            outColors->push_back(getRegionColor(rows, bounds));
        }
    }
}

// Calculates the insets of a row/column of pixels based on where the largest alpha value begins
// (on both sides).
template <typename ImageLine>
static void findOutlineInsets(const ImageLine* imageLine, int32_t* outStart, int32_t* outEnd) {
    *outStart = 0;
    *outEnd = 0;

    const int32_t length = imageLine->getLength();
    if (length < 3) {
        return;
    }

    // If the length is odd, we want both sides to process the center pixel,
    // so we use two different midpoints (to account for < and <= in the different loops).
    const int32_t mid2 = length / 2;
    const int32_t mid1 = mid2 + (length % 2);

    uint32_t maxAlpha = 0;
    for (int32_t i = 0; i < mid1 && maxAlpha != 0xff; i++) {
        uint32_t alpha = getAlpha(imageLine->getColor(i));
        if (alpha > maxAlpha) {
            maxAlpha = alpha;
            *outStart = i;
        }
    }

    maxAlpha = 0;
    for (int32_t i = length - 1; i >= mid2 && maxAlpha != 0xff; i--) {
        uint32_t alpha = getAlpha(imageLine->getColor(i));
        if (alpha > maxAlpha) {
            maxAlpha = alpha;
            *outEnd = length - (i + 1);
        }
    }
    return;
}

template <typename ImageLine>
static uint32_t findMaxAlpha(const ImageLine* imageLine) {
    const int32_t length = imageLine->getLength();
    uint32_t maxAlpha = 0;
    for (int32_t idx = 0; idx < length && maxAlpha != 0xff; idx++) {
        uint32_t alpha = getAlpha(imageLine->getColor(idx));
        if (alpha > maxAlpha) {
            maxAlpha = alpha;
        }
    }
    return maxAlpha;
}

// Pack the pixels in as 0xAARRGGBB (as 9-patch expects it).
uint32_t NinePatch::packRGBA(const uint8_t* pixel) {
    return (pixel[3] << 24) | (pixel[0] << 16) | (pixel[1] << 8) | pixel[2];
}

std::unique_ptr<NinePatch> NinePatch::create(uint8_t** rows,
                                             const int32_t width, const int32_t height,
                                             std::string* err) {
    if (width < 3 || height < 3) {
        *err = "image must be at least 3x3 (1x1 image with 1 pixel border)";
        return {};
    }

    std::vector<Range> horizontalPadding;
    std::vector<Range> horizontalOpticalBounds;
    std::vector<Range> verticalPadding;
    std::vector<Range> verticalOpticalBounds;
    std::vector<Range> unexpectedRanges;
    std::unique_ptr<ColorValidator> colorValidator;

    if (rows[0][3] == 0) {
        colorValidator = util::make_unique<TransparentNeutralColorValidator>();
    } else if (packRGBA(rows[0]) == kColorOpaqueWhite) {
        colorValidator = util::make_unique<WhiteNeutralColorValidator>();
    } else {
        *err = "top-left corner pixel must be either opaque white or transparent";
        return {};
    }

    // Private constructor, can't use make_unique.
    auto ninePatch = std::unique_ptr<NinePatch>(new NinePatch());

    HorizontalImageLine topRow(rows, 0, 0, width);
    if (!fillRanges(&topRow, colorValidator.get(), &ninePatch->horizontalStretchRegions,
                    &unexpectedRanges, err)) {
        return {};
    }

    if (!unexpectedRanges.empty()) {
        const Range& range = unexpectedRanges[0];
        std::stringstream errStream;
        errStream << "found unexpected optical bounds (red pixel) on top border "
                << "at x=" << range.start + 1;
        *err = errStream.str();
        return {};
    }

    VerticalImageLine leftCol(rows, 0, 0, height);
    if (!fillRanges(&leftCol, colorValidator.get(), &ninePatch->verticalStretchRegions,
                    &unexpectedRanges, err)) {
        return {};
    }

    if (!unexpectedRanges.empty()) {
        const Range& range = unexpectedRanges[0];
        std::stringstream errStream;
        errStream << "found unexpected optical bounds (red pixel) on left border "
                << "at y=" << range.start + 1;
        return {};
    }

    HorizontalImageLine bottomRow(rows, 0, height - 1, width);
    if (!fillRanges(&bottomRow, colorValidator.get(), &horizontalPadding,
                    &horizontalOpticalBounds, err)) {
        return {};
    }

    if (!populateBounds(horizontalPadding, horizontalOpticalBounds,
                        ninePatch->horizontalStretchRegions, width - 2,
                        &ninePatch->padding.left, &ninePatch->padding.right,
                        &ninePatch->layoutBounds.left, &ninePatch->layoutBounds.right,
                        "bottom", err)) {
        return {};
    }

    VerticalImageLine rightCol(rows, width - 1, 0, height);
    if (!fillRanges(&rightCol, colorValidator.get(), &verticalPadding,
                    &verticalOpticalBounds, err)) {
        return {};
    }

    if (!populateBounds(verticalPadding, verticalOpticalBounds,
                        ninePatch->verticalStretchRegions, height - 2,
                        &ninePatch->padding.top, &ninePatch->padding.bottom,
                        &ninePatch->layoutBounds.top, &ninePatch->layoutBounds.bottom,
                        "right", err)) {
        return {};
    }

    // Fill the region colors of the 9-patch.
    const int32_t numRows = calculateSegmentCount(ninePatch->horizontalStretchRegions, width - 2);
    const int32_t numCols = calculateSegmentCount(ninePatch->verticalStretchRegions, height - 2);
    if ((int64_t) numRows * (int64_t) numCols > 0x7f) {
        *err = "too many regions in 9-patch";
        return {};
    }

    ninePatch->regionColors.reserve(numRows * numCols);
    calculateRegionColors(rows, ninePatch->horizontalStretchRegions,
                          ninePatch->verticalStretchRegions,
                          width - 2, height - 2,
                          &ninePatch->regionColors);

    // Compute the outline based on opacity.

    // Find left and right extent of 9-patch content on center row.
    HorizontalImageLine midRow(rows, 1, height / 2, width - 2);
    findOutlineInsets(&midRow, &ninePatch->outline.left, &ninePatch->outline.right);

    // Find top and bottom extent of 9-patch content on center column.
    VerticalImageLine midCol(rows, width / 2, 1, height - 2);
    findOutlineInsets(&midCol, &ninePatch->outline.top, &ninePatch->outline.bottom);

    const int32_t outlineWidth = (width - 2) - ninePatch->outline.left - ninePatch->outline.right;
    const int32_t outlineHeight = (height - 2) - ninePatch->outline.top - ninePatch->outline.bottom;

    // Find the largest alpha value within the outline area.
    HorizontalImageLine outlineMidRow(rows,
                                      1 + ninePatch->outline.left,
                                      1 + ninePatch->outline.top + (outlineHeight / 2),
                                      outlineWidth);
    VerticalImageLine outlineMidCol(rows,
                                    1 + ninePatch->outline.left + (outlineWidth / 2),
                                    1 + ninePatch->outline.top,
                                    outlineHeight);
    ninePatch->outlineAlpha = std::max(findMaxAlpha(&outlineMidRow), findMaxAlpha(&outlineMidCol));

    // Assuming the image is a round rect, compute the radius by marching
    // diagonally from the top left corner towards the center.
    DiagonalImageLine diagonal(rows, 1 + ninePatch->outline.left, 1 + ninePatch->outline.top,
                               1, 1, std::min(outlineWidth, outlineHeight));
    int32_t topLeft, bottomRight;
    findOutlineInsets(&diagonal, &topLeft, &bottomRight);

    /* Determine source radius based upon inset:
     *     sqrt(r^2 + r^2) = sqrt(i^2 + i^2) + r
     *     sqrt(2) * r = sqrt(2) * i + r
     *     (sqrt(2) - 1) * r = sqrt(2) * i
     *     r = sqrt(2) / (sqrt(2) - 1) * i
     */
    ninePatch->outlineRadius = 3.4142f * topLeft;
    return ninePatch;
}

std::unique_ptr<uint8_t[]> NinePatch::serializeBase(size_t* outLen) const {
    android::Res_png_9patch data;
    data.numXDivs = static_cast<uint8_t>(horizontalStretchRegions.size()) * 2;
    data.numYDivs = static_cast<uint8_t>(verticalStretchRegions.size()) * 2;
    data.numColors = static_cast<uint8_t>(regionColors.size());
    data.paddingLeft = padding.left;
    data.paddingRight = padding.right;
    data.paddingTop = padding.top;
    data.paddingBottom = padding.bottom;

    auto buffer = std::unique_ptr<uint8_t[]>(new uint8_t[data.serializedSize()]);
    android::Res_png_9patch::serialize(data,
                                       (const int32_t*) horizontalStretchRegions.data(),
                                       (const int32_t*) verticalStretchRegions.data(),
                                       regionColors.data(),
                                       buffer.get());
    // Convert to file endianness.
    reinterpret_cast<android::Res_png_9patch*>(buffer.get())->deviceToFile();

    *outLen = data.serializedSize();
    return buffer;
}

std::unique_ptr<uint8_t[]> NinePatch::serializeLayoutBounds(size_t* outLen) const {
    size_t chunkLen = sizeof(uint32_t) * 4;
    auto buffer = std::unique_ptr<uint8_t[]>(new uint8_t[chunkLen]);
    uint8_t* cursor = buffer.get();

    memcpy(cursor, &layoutBounds.left, sizeof(layoutBounds.left));
    cursor += sizeof(layoutBounds.left);

    memcpy(cursor, &layoutBounds.top, sizeof(layoutBounds.top));
    cursor += sizeof(layoutBounds.top);

    memcpy(cursor, &layoutBounds.right, sizeof(layoutBounds.right));
    cursor += sizeof(layoutBounds.right);

    memcpy(cursor, &layoutBounds.bottom, sizeof(layoutBounds.bottom));
    cursor += sizeof(layoutBounds.bottom);

    *outLen = chunkLen;
    return buffer;
}

std::unique_ptr<uint8_t[]> NinePatch::serializeRoundedRectOutline(size_t* outLen) const {
    size_t chunkLen = sizeof(uint32_t) * 6;
    auto buffer = std::unique_ptr<uint8_t[]>(new uint8_t[chunkLen]);
    uint8_t* cursor = buffer.get();

    memcpy(cursor, &outline.left, sizeof(outline.left));
    cursor += sizeof(outline.left);

    memcpy(cursor, &outline.top, sizeof(outline.top));
    cursor += sizeof(outline.top);

    memcpy(cursor, &outline.right, sizeof(outline.right));
    cursor += sizeof(outline.right);

    memcpy(cursor, &outline.bottom, sizeof(outline.bottom));
    cursor += sizeof(outline.bottom);

    *((float*) cursor) = outlineRadius;
    cursor += sizeof(outlineRadius);

    *((uint32_t*) cursor) = outlineAlpha;

    *outLen = chunkLen;
    return buffer;
}

::std::ostream& operator<<(::std::ostream& out, const Range& range) {
    return out << "[" << range.start << ", " << range.end << ")";
}

::std::ostream& operator<<(::std::ostream& out, const Bounds& bounds) {
    return out << "l=" << bounds.left
            << " t=" << bounds.top
            << " r=" << bounds.right
            << " b=" << bounds.bottom;
}

::std::ostream& operator<<(::std::ostream& out, const NinePatch& ninePatch) {
    return out << "horizontalStretch:" << util::joiner(ninePatch.horizontalStretchRegions, " ")
            << " verticalStretch:" << util::joiner(ninePatch.verticalStretchRegions, " ")
            << " padding: " << ninePatch.padding
            << ", bounds: " << ninePatch.layoutBounds
            << ", outline: " << ninePatch.outline
            << " rad=" << ninePatch.outlineRadius
            << " alpha=" << ninePatch.outlineAlpha;
}

} // namespace aapt
