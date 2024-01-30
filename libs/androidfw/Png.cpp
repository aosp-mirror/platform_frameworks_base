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

#include "androidfw/Png.h"

#include <png.h>
#include <zlib.h>

#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "android-base/strings.h"
#include "androidfw/BigBuffer.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/Source.h"

namespace android {

constexpr bool kDebug = false;

struct PngInfo {
  ~PngInfo() {
    for (png_bytep row : rows) {
      if (row != nullptr) {
        delete[] row;
      }
    }

    delete[] xDivs;
    delete[] yDivs;
  }

  void* serialize9Patch() {
    void* serialized = Res_png_9patch::serialize(info9Patch, xDivs, yDivs, colors.data());
    reinterpret_cast<Res_png_9patch*>(serialized)->deviceToFile();
    return serialized;
  }

  uint32_t width = 0;
  uint32_t height = 0;
  std::vector<png_bytep> rows;

  bool is9Patch = false;
  Res_png_9patch info9Patch;
  int32_t* xDivs = nullptr;
  int32_t* yDivs = nullptr;
  std::vector<uint32_t> colors;

  // Layout padding.
  bool haveLayoutBounds = false;
  int32_t layoutBoundsLeft;
  int32_t layoutBoundsTop;
  int32_t layoutBoundsRight;
  int32_t layoutBoundsBottom;

  // Round rect outline description.
  int32_t outlineInsetsLeft;
  int32_t outlineInsetsTop;
  int32_t outlineInsetsRight;
  int32_t outlineInsetsBottom;
  float outlineRadius;
  uint8_t outlineAlpha;
};

static void readDataFromStream(png_structp readPtr, png_bytep data, png_size_t length) {
  std::istream* input = reinterpret_cast<std::istream*>(png_get_io_ptr(readPtr));
  if (!input->read(reinterpret_cast<char*>(data), length)) {
    png_error(readPtr, strerror(errno));
  }
}

static void writeDataToStream(png_structp writePtr, png_bytep data, png_size_t length) {
  BigBuffer* outBuffer = reinterpret_cast<BigBuffer*>(png_get_io_ptr(writePtr));
  png_bytep buf = outBuffer->NextBlock<png_byte>(length);
  memcpy(buf, data, length);
}

static void flushDataToStream(png_structp /*writePtr*/) {
}

static void logWarning(png_structp readPtr, png_const_charp warningMessage) {
  IDiagnostics* diag = reinterpret_cast<IDiagnostics*>(png_get_error_ptr(readPtr));
  diag->Warn(DiagMessage() << warningMessage);
}

static bool readPng(IDiagnostics* diag, png_structp readPtr, png_infop infoPtr, PngInfo* outInfo) {
  if (setjmp(png_jmpbuf(readPtr))) {
    diag->Error(DiagMessage() << "failed reading png");
    return false;
  }

  png_set_sig_bytes(readPtr, kPngSignatureSize);
  png_read_info(readPtr, infoPtr);

  int colorType, bitDepth, interlaceType, compressionType;
  png_get_IHDR(readPtr, infoPtr, &outInfo->width, &outInfo->height, &bitDepth, &colorType,
               &interlaceType, &compressionType, nullptr);

  if (colorType == PNG_COLOR_TYPE_PALETTE) {
    png_set_palette_to_rgb(readPtr);
  }

  if (colorType == PNG_COLOR_TYPE_GRAY && bitDepth < 8) {
    png_set_expand_gray_1_2_4_to_8(readPtr);
  }

  if (png_get_valid(readPtr, infoPtr, PNG_INFO_tRNS)) {
    png_set_tRNS_to_alpha(readPtr);
  }

  if (bitDepth == 16) {
    png_set_strip_16(readPtr);
  }

  if (!(colorType & PNG_COLOR_MASK_ALPHA)) {
    png_set_add_alpha(readPtr, 0xFF, PNG_FILLER_AFTER);
  }

  if (colorType == PNG_COLOR_TYPE_GRAY || colorType == PNG_COLOR_TYPE_GRAY_ALPHA) {
    png_set_gray_to_rgb(readPtr);
  }

  png_set_interlace_handling(readPtr);
  png_read_update_info(readPtr, infoPtr);

  const uint32_t rowBytes = png_get_rowbytes(readPtr, infoPtr);
  outInfo->rows.resize(outInfo->height);
  for (size_t i = 0; i < outInfo->height; i++) {
    outInfo->rows[i] = new png_byte[rowBytes];
  }

  png_read_image(readPtr, outInfo->rows.data());
  png_read_end(readPtr, infoPtr);
  return true;
}

static void checkNinePatchSerialization(Res_png_9patch* inPatch, void* data) {
  size_t patchSize = inPatch->serializedSize();
  void* newData = malloc(patchSize);
  memcpy(newData, data, patchSize);
  Res_png_9patch* outPatch = inPatch->deserialize(newData);
  outPatch->fileToDevice();
  // deserialization is done in place, so outPatch == newData
  assert(outPatch == newData);
  assert(outPatch->numXDivs == inPatch->numXDivs);
  assert(outPatch->numYDivs == inPatch->numYDivs);
  assert(outPatch->paddingLeft == inPatch->paddingLeft);
  assert(outPatch->paddingRight == inPatch->paddingRight);
  assert(outPatch->paddingTop == inPatch->paddingTop);
  assert(outPatch->paddingBottom == inPatch->paddingBottom);
  /*    for (int i = 0; i < outPatch->numXDivs; i++) {
          assert(outPatch->getXDivs()[i] == inPatch->getXDivs()[i]);
      }
      for (int i = 0; i < outPatch->numYDivs; i++) {
          assert(outPatch->getYDivs()[i] == inPatch->getYDivs()[i]);
      }
      for (int i = 0; i < outPatch->numColors; i++) {
          assert(outPatch->getColors()[i] == inPatch->getColors()[i]);
      }*/
  free(newData);
}

/*static void dump_image(int w, int h, const png_byte* const* rows, int
color_type) {
    int i, j, rr, gg, bb, aa;

    int bpp;
    if (color_type == PNG_COLOR_TYPE_PALETTE || color_type ==
PNG_COLOR_TYPE_GRAY) {
        bpp = 1;
    } else if (color_type == PNG_COLOR_TYPE_GRAY_ALPHA) {
        bpp = 2;
    } else if (color_type == PNG_COLOR_TYPE_RGB || color_type ==
PNG_COLOR_TYPE_RGB_ALPHA) {
        // We use a padding byte even when there is no alpha
        bpp = 4;
    } else {
        printf("Unknown color type %d.\n", color_type);
    }

    for (j = 0; j < h; j++) {
        const png_byte* row = rows[j];
        for (i = 0; i < w; i++) {
            rr = row[0];
            gg = row[1];
            bb = row[2];
            aa = row[3];
            row += bpp;

            if (i == 0) {
                printf("Row %d:", j);
            }
            switch (bpp) {
            case 1:
                printf(" (%d)", rr);
                break;
            case 2:
                printf(" (%d %d", rr, gg);
                break;
            case 3:
                printf(" (%d %d %d)", rr, gg, bb);
                break;
            case 4:
                printf(" (%d %d %d %d)", rr, gg, bb, aa);
                break;
            }
            if (i == (w - 1)) {
                printf("\n");
            }
        }
    }
}*/

#ifdef MAX
#undef MAX
#endif
#ifdef ABS
#undef ABS
#endif

#define MAX(a, b) ((a) > (b) ? (a) : (b))
#define ABS(a) ((a) < 0 ? -(a) : (a))

static void analyze_image(IDiagnostics* diag, const PngInfo& imageInfo, int grayscaleTolerance,
                          png_colorp rgbPalette, png_bytep alphaPalette, int* paletteEntries,
                          bool* hasTransparency, int* colorType, png_bytepp outRows) {
  int w = imageInfo.width;
  int h = imageInfo.height;
  int i, j, rr, gg, bb, aa, idx;
  uint32_t colors[256], col;
  int num_colors = 0;
  int maxGrayDeviation = 0;

  bool isOpaque = true;
  bool isPalette = true;
  bool isGrayscale = true;

  // Scan the entire image and determine if:
  // 1. Every pixel has R == G == B (grayscale)
  // 2. Every pixel has A == 255 (opaque)
  // 3. There are no more than 256 distinct RGBA colors

  if (kDebug) {
    printf("Initial image data:\n");
    // dump_image(w, h, imageInfo.rows.data(), PNG_COLOR_TYPE_RGB_ALPHA);
  }

  for (j = 0; j < h; j++) {
    const png_byte* row = imageInfo.rows[j];
    png_bytep out = outRows[j];
    for (i = 0; i < w; i++) {
      rr = *row++;
      gg = *row++;
      bb = *row++;
      aa = *row++;

      int odev = maxGrayDeviation;
      maxGrayDeviation = MAX(ABS(rr - gg), maxGrayDeviation);
      maxGrayDeviation = MAX(ABS(gg - bb), maxGrayDeviation);
      maxGrayDeviation = MAX(ABS(bb - rr), maxGrayDeviation);
      if (maxGrayDeviation > odev) {
        if (kDebug) {
          printf("New max dev. = %d at pixel (%d, %d) = (%d %d %d %d)\n", maxGrayDeviation, i, j,
                 rr, gg, bb, aa);
        }
      }

      // Check if image is really grayscale
      if (isGrayscale) {
        if (rr != gg || rr != bb) {
          if (kDebug) {
            printf("Found a non-gray pixel at %d, %d = (%d %d %d %d)\n", i, j, rr, gg, bb, aa);
          }
          isGrayscale = false;
        }
      }

      // Check if image is really opaque
      if (isOpaque) {
        if (aa != 0xff) {
          if (kDebug) {
            printf("Found a non-opaque pixel at %d, %d = (%d %d %d %d)\n", i, j, rr, gg, bb, aa);
          }
          isOpaque = false;
        }
      }

      // Check if image is really <= 256 colors
      if (isPalette) {
        col = (uint32_t)((rr << 24) | (gg << 16) | (bb << 8) | aa);
        bool match = false;
        for (idx = 0; idx < num_colors; idx++) {
          if (colors[idx] == col) {
            match = true;
            break;
          }
        }

        // Write the palette index for the pixel to outRows optimistically
        // We might overwrite it later if we decide to encode as gray or
        // gray + alpha
        *out++ = idx;
        if (!match) {
          if (num_colors == 256) {
            if (kDebug) {
              printf("Found 257th color at %d, %d\n", i, j);
            }
            isPalette = false;
          } else {
            colors[num_colors++] = col;
          }
        }
      }
    }
  }

  *paletteEntries = 0;
  *hasTransparency = !isOpaque;
  int bpp = isOpaque ? 3 : 4;
  int paletteSize = w * h + bpp * num_colors;

  if (kDebug) {
    printf("isGrayscale = %s\n", isGrayscale ? "true" : "false");
    printf("isOpaque = %s\n", isOpaque ? "true" : "false");
    printf("isPalette = %s\n", isPalette ? "true" : "false");
    printf("Size w/ palette = %d, gray+alpha = %d, rgb(a) = %d\n", paletteSize, 2 * w * h,
           bpp * w * h);
    printf("Max gray deviation = %d, tolerance = %d\n", maxGrayDeviation, grayscaleTolerance);
  }

  // Choose the best color type for the image.
  // 1. Opaque gray - use COLOR_TYPE_GRAY at 1 byte/pixel
  // 2. Gray + alpha - use COLOR_TYPE_PALETTE if the number of distinct
  // combinations
  //     is sufficiently small, otherwise use COLOR_TYPE_GRAY_ALPHA
  // 3. RGB(A) - use COLOR_TYPE_PALETTE if the number of distinct colors is
  // sufficiently
  //     small, otherwise use COLOR_TYPE_RGB{_ALPHA}
  if (isGrayscale) {
    if (isOpaque) {
      *colorType = PNG_COLOR_TYPE_GRAY;  // 1 byte/pixel
    } else {
      // Use a simple heuristic to determine whether using a palette will
      // save space versus using gray + alpha for each pixel.
      // This doesn't take into account chunk overhead, filtering, LZ
      // compression, etc.
      if (isPalette && (paletteSize < 2 * w * h)) {
        *colorType = PNG_COLOR_TYPE_PALETTE;  // 1 byte/pixel + 4 bytes/color
      } else {
        *colorType = PNG_COLOR_TYPE_GRAY_ALPHA;  // 2 bytes per pixel
      }
    }
  } else if (isPalette && (paletteSize < bpp * w * h)) {
    *colorType = PNG_COLOR_TYPE_PALETTE;
  } else {
    if (maxGrayDeviation <= grayscaleTolerance) {
      diag->Note(DiagMessage() << "forcing image to gray (max deviation = " << maxGrayDeviation
                               << ")");
      *colorType = isOpaque ? PNG_COLOR_TYPE_GRAY : PNG_COLOR_TYPE_GRAY_ALPHA;
    } else {
      *colorType = isOpaque ? PNG_COLOR_TYPE_RGB : PNG_COLOR_TYPE_RGB_ALPHA;
    }
  }

  // Perform postprocessing of the image or palette data based on the final
  // color type chosen

  if (*colorType == PNG_COLOR_TYPE_PALETTE) {
    // Create separate RGB and Alpha palettes and set the number of colors
    *paletteEntries = num_colors;

    // Create the RGB and alpha palettes
    for (int idx = 0; idx < num_colors; idx++) {
      col = colors[idx];
      rgbPalette[idx].red = (png_byte)((col >> 24) & 0xff);
      rgbPalette[idx].green = (png_byte)((col >> 16) & 0xff);
      rgbPalette[idx].blue = (png_byte)((col >> 8) & 0xff);
      alphaPalette[idx] = (png_byte)(col & 0xff);
    }
  } else if (*colorType == PNG_COLOR_TYPE_GRAY || *colorType == PNG_COLOR_TYPE_GRAY_ALPHA) {
    // If the image is gray or gray + alpha, compact the pixels into outRows
    for (j = 0; j < h; j++) {
      const png_byte* row = imageInfo.rows[j];
      png_bytep out = outRows[j];
      for (i = 0; i < w; i++) {
        rr = *row++;
        gg = *row++;
        bb = *row++;
        aa = *row++;

        if (isGrayscale) {
          *out++ = rr;
        } else {
          *out++ = (png_byte)(rr * 0.2126f + gg * 0.7152f + bb * 0.0722f);
        }
        if (!isOpaque) {
          *out++ = aa;
        }
      }
    }
  }
}

static bool writePng(IDiagnostics* diag, png_structp writePtr, png_infop infoPtr, PngInfo* info,
                     int grayScaleTolerance) {
  if (setjmp(png_jmpbuf(writePtr))) {
    diag->Error(DiagMessage() << "failed to write png");
    return false;
  }

  uint32_t width, height;
  int colorType, bitDepth, interlaceType, compressionType;

  png_unknown_chunk unknowns[3];
  unknowns[0].data = nullptr;
  unknowns[1].data = nullptr;
  unknowns[2].data = nullptr;

  png_bytepp outRows = (png_bytepp)malloc((int)info->height * sizeof(png_bytep));
  if (outRows == (png_bytepp)0) {
    printf("Can't allocate output buffer!\n");
    exit(1);
  }
  for (uint32_t i = 0; i < info->height; i++) {
    outRows[i] = (png_bytep)malloc(2 * (int)info->width);
    if (outRows[i] == (png_bytep)0) {
      printf("Can't allocate output buffer!\n");
      exit(1);
    }
  }

  png_set_compression_level(writePtr, Z_BEST_COMPRESSION);

  if (kDebug) {
    diag->Note(DiagMessage() << "writing image: w = " << info->width << ", h = " << info->height);
  }

  png_color rgbPalette[256];
  png_byte alphaPalette[256];
  bool hasTransparency;
  int paletteEntries;

  analyze_image(diag, *info, grayScaleTolerance, rgbPalette, alphaPalette, &paletteEntries,
                &hasTransparency, &colorType, outRows);

  // If the image is a 9-patch, we need to preserve it as a ARGB file to make
  // sure the pixels will not be pre-dithered/clamped until we decide they are
  if (info->is9Patch && (colorType == PNG_COLOR_TYPE_RGB || colorType == PNG_COLOR_TYPE_GRAY ||
                         colorType == PNG_COLOR_TYPE_PALETTE)) {
    colorType = PNG_COLOR_TYPE_RGB_ALPHA;
  }

  if (kDebug) {
    switch (colorType) {
      case PNG_COLOR_TYPE_PALETTE:
        diag->Note(DiagMessage() << "has " << paletteEntries << " colors"
                                 << (hasTransparency ? " (with alpha)" : "")
                                 << ", using PNG_COLOR_TYPE_PALLETTE");
        break;
      case PNG_COLOR_TYPE_GRAY:
        diag->Note(DiagMessage() << "is opaque gray, using PNG_COLOR_TYPE_GRAY");
        break;
      case PNG_COLOR_TYPE_GRAY_ALPHA:
        diag->Note(DiagMessage() << "is gray + alpha, using PNG_COLOR_TYPE_GRAY_ALPHA");
        break;
      case PNG_COLOR_TYPE_RGB:
        diag->Note(DiagMessage() << "is opaque RGB, using PNG_COLOR_TYPE_RGB");
        break;
      case PNG_COLOR_TYPE_RGB_ALPHA:
        diag->Note(DiagMessage() << "is RGB + alpha, using PNG_COLOR_TYPE_RGB_ALPHA");
        break;
    }
  }

  png_set_IHDR(writePtr, infoPtr, info->width, info->height, 8, colorType, PNG_INTERLACE_NONE,
               PNG_COMPRESSION_TYPE_DEFAULT, PNG_FILTER_TYPE_DEFAULT);

  if (colorType == PNG_COLOR_TYPE_PALETTE) {
    png_set_PLTE(writePtr, infoPtr, rgbPalette, paletteEntries);
    if (hasTransparency) {
      png_set_tRNS(writePtr, infoPtr, alphaPalette, paletteEntries, (png_color_16p)0);
    }
    png_set_filter(writePtr, 0, PNG_NO_FILTERS);
  } else {
    png_set_filter(writePtr, 0, PNG_ALL_FILTERS);
  }

  if (info->is9Patch) {
    int chunkCount = 2 + (info->haveLayoutBounds ? 1 : 0);
    int pIndex = info->haveLayoutBounds ? 2 : 1;
    int bIndex = 1;
    int oIndex = 0;

    // Chunks ordered thusly because older platforms depend on the base 9 patch
    // data being last
    png_bytep chunkNames =
        info->haveLayoutBounds ? (png_bytep) "npOl\0npLb\0npTc\0" : (png_bytep) "npOl\0npTc";

    // base 9 patch data
    if (kDebug) {
      diag->Note(DiagMessage() << "adding 9-patch info..");
    }
    memcpy((char*)unknowns[pIndex].name, "npTc", 5);
    unknowns[pIndex].data = (png_byte*)info->serialize9Patch();
    unknowns[pIndex].size = info->info9Patch.serializedSize();
    // TODO: remove the check below when everything works
    checkNinePatchSerialization(&info->info9Patch, unknowns[pIndex].data);

    // automatically generated 9 patch outline data
    int chunkSize = sizeof(png_uint_32) * 6;
    memcpy((char*)unknowns[oIndex].name, "npOl", 5);
    unknowns[oIndex].data = (png_byte*)calloc(chunkSize, 1);
    png_byte outputData[chunkSize];
    memcpy(&outputData, &info->outlineInsetsLeft, 4 * sizeof(png_uint_32));
    ((float*)outputData)[4] = info->outlineRadius;
    ((png_uint_32*)outputData)[5] = info->outlineAlpha;
    memcpy(unknowns[oIndex].data, &outputData, chunkSize);
    unknowns[oIndex].size = chunkSize;

    // optional optical inset / layout bounds data
    if (info->haveLayoutBounds) {
      int chunkSize = sizeof(png_uint_32) * 4;
      memcpy((char*)unknowns[bIndex].name, "npLb", 5);
      unknowns[bIndex].data = (png_byte*)calloc(chunkSize, 1);
      memcpy(unknowns[bIndex].data, &info->layoutBoundsLeft, chunkSize);
      unknowns[bIndex].size = chunkSize;
    }

    for (int i = 0; i < chunkCount; i++) {
      unknowns[i].location = PNG_HAVE_PLTE;
    }
    png_set_keep_unknown_chunks(writePtr, PNG_HANDLE_CHUNK_ALWAYS, chunkNames, chunkCount);
    png_set_unknown_chunks(writePtr, infoPtr, unknowns, chunkCount);

#if PNG_LIBPNG_VER < 10600
    // Deal with unknown chunk location bug in 1.5.x and earlier.
    png_set_unknown_chunk_location(writePtr, infoPtr, 0, PNG_HAVE_PLTE);
    if (info->haveLayoutBounds) {
      png_set_unknown_chunk_location(writePtr, infoPtr, 1, PNG_HAVE_PLTE);
    }
#endif
  }

  png_write_info(writePtr, infoPtr);

  png_bytepp rows;
  if (colorType == PNG_COLOR_TYPE_RGB || colorType == PNG_COLOR_TYPE_RGB_ALPHA) {
    if (colorType == PNG_COLOR_TYPE_RGB) {
      png_set_filler(writePtr, 0, PNG_FILLER_AFTER);
    }
    rows = info->rows.data();
  } else {
    rows = outRows;
  }
  png_write_image(writePtr, rows);

  if (kDebug) {
    printf("Final image data:\n");
    // dump_image(info->width, info->height, rows, colorType);
  }

  png_write_end(writePtr, infoPtr);

  for (uint32_t i = 0; i < info->height; i++) {
    free(outRows[i]);
  }
  free(outRows);
  free(unknowns[0].data);
  free(unknowns[1].data);
  free(unknowns[2].data);

  png_get_IHDR(writePtr, infoPtr, &width, &height, &bitDepth, &colorType, &interlaceType,
               &compressionType, nullptr);

  if (kDebug) {
    diag->Note(DiagMessage() << "image written: w = " << width << ", h = " << height
                             << ", d = " << bitDepth << ", colors = " << colorType
                             << ", inter = " << interlaceType << ", comp = " << compressionType);
  }
  return true;
}

constexpr uint32_t kColorWhite = 0xffffffffu;
constexpr uint32_t kColorTick = 0xff000000u;
constexpr uint32_t kColorLayoutBoundsTick = 0xff0000ffu;

enum class TickType { kNone, kTick, kLayoutBounds, kBoth };

static TickType tickType(png_bytep p, bool transparent, const char** outError) {
  png_uint_32 color = p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24);

  if (transparent) {
    if (p[3] == 0) {
      return TickType::kNone;
    }
    if (color == kColorLayoutBoundsTick) {
      return TickType::kLayoutBounds;
    }
    if (color == kColorTick) {
      return TickType::kTick;
    }

    // Error cases
    if (p[3] != 0xff) {
      *outError =
          "Frame pixels must be either solid or transparent "
          "(not intermediate alphas)";
      return TickType::kNone;
    }

    if (p[0] != 0 || p[1] != 0 || p[2] != 0) {
      *outError = "Ticks in transparent frame must be black or red";
    }
    return TickType::kTick;
  }

  if (p[3] != 0xFF) {
    *outError = "White frame must be a solid color (no alpha)";
  }
  if (color == kColorWhite) {
    return TickType::kNone;
  }
  if (color == kColorTick) {
    return TickType::kTick;
  }
  if (color == kColorLayoutBoundsTick) {
    return TickType::kLayoutBounds;
  }

  if (p[0] != 0 || p[1] != 0 || p[2] != 0) {
    *outError = "Ticks in white frame must be black or red";
    return TickType::kNone;
  }
  return TickType::kTick;
}

enum class TickState { kStart, kInside1, kOutside1 };

static bool getHorizontalTicks(png_bytep row, int width, bool transparent, bool required,
                               int32_t* outLeft, int32_t* outRight, const char** outError,
                               uint8_t* outDivs, bool multipleAllowed) {
  *outLeft = *outRight = -1;
  TickState state = TickState::kStart;
  bool found = false;

  for (int i = 1; i < width - 1; i++) {
    if (tickType(row + i * 4, transparent, outError) == TickType::kTick) {
      if (state == TickState::kStart || (state == TickState::kOutside1 && multipleAllowed)) {
        *outLeft = i - 1;
        *outRight = width - 2;
        found = true;
        if (outDivs != NULL) {
          *outDivs += 2;
        }
        state = TickState::kInside1;
      } else if (state == TickState::kOutside1) {
        *outError = "Can't have more than one marked region along edge";
        *outLeft = i;
        return false;
      }
    } else if (!*outError) {
      if (state == TickState::kInside1) {
        // We're done with this div.  Move on to the next.
        *outRight = i - 1;
        outRight += 2;
        outLeft += 2;
        state = TickState::kOutside1;
      }
    } else {
      *outLeft = i;
      return false;
    }
  }

  if (required && !found) {
    *outError = "No marked region found along edge";
    *outLeft = -1;
    return false;
  }
  return true;
}

static bool getVerticalTicks(png_bytepp rows, int offset, int height, bool transparent,
                             bool required, int32_t* outTop, int32_t* outBottom,
                             const char** outError, uint8_t* outDivs, bool multipleAllowed) {
  *outTop = *outBottom = -1;
  TickState state = TickState::kStart;
  bool found = false;

  for (int i = 1; i < height - 1; i++) {
    if (tickType(rows[i] + offset, transparent, outError) == TickType::kTick) {
      if (state == TickState::kStart || (state == TickState::kOutside1 && multipleAllowed)) {
        *outTop = i - 1;
        *outBottom = height - 2;
        found = true;
        if (outDivs != NULL) {
          *outDivs += 2;
        }
        state = TickState::kInside1;
      } else if (state == TickState::kOutside1) {
        *outError = "Can't have more than one marked region along edge";
        *outTop = i;
        return false;
      }
    } else if (!*outError) {
      if (state == TickState::kInside1) {
        // We're done with this div.  Move on to the next.
        *outBottom = i - 1;
        outTop += 2;
        outBottom += 2;
        state = TickState::kOutside1;
      }
    } else {
      *outTop = i;
      return false;
    }
  }

  if (required && !found) {
    *outError = "No marked region found along edge";
    *outTop = -1;
    return false;
  }
  return true;
}

static bool getHorizontalLayoutBoundsTicks(png_bytep row, int width, bool transparent,
                                           bool /* required */, int32_t* outLeft, int32_t* outRight,
                                           const char** outError) {
  *outLeft = *outRight = 0;

  // Look for left tick
  if (tickType(row + 4, transparent, outError) == TickType::kLayoutBounds) {
    // Starting with a layout padding tick
    int i = 1;
    while (i < width - 1) {
      (*outLeft)++;
      i++;
      if (tickType(row + i * 4, transparent, outError) != TickType::kLayoutBounds) {
        break;
      }
    }
  }

  // Look for right tick
  if (tickType(row + (width - 2) * 4, transparent, outError) == TickType::kLayoutBounds) {
    // Ending with a layout padding tick
    int i = width - 2;
    while (i > 1) {
      (*outRight)++;
      i--;
      if (tickType(row + i * 4, transparent, outError) != TickType::kLayoutBounds) {
        break;
      }
    }
  }
  return true;
}

static bool getVerticalLayoutBoundsTicks(png_bytepp rows, int offset, int height, bool transparent,
                                         bool /* required */, int32_t* outTop, int32_t* outBottom,
                                         const char** outError) {
  *outTop = *outBottom = 0;

  // Look for top tick
  if (tickType(rows[1] + offset, transparent, outError) == TickType::kLayoutBounds) {
    // Starting with a layout padding tick
    int i = 1;
    while (i < height - 1) {
      (*outTop)++;
      i++;
      if (tickType(rows[i] + offset, transparent, outError) != TickType::kLayoutBounds) {
        break;
      }
    }
  }

  // Look for bottom tick
  if (tickType(rows[height - 2] + offset, transparent, outError) == TickType::kLayoutBounds) {
    // Ending with a layout padding tick
    int i = height - 2;
    while (i > 1) {
      (*outBottom)++;
      i--;
      if (tickType(rows[i] + offset, transparent, outError) != TickType::kLayoutBounds) {
        break;
      }
    }
  }
  return true;
}

static void findMaxOpacity(png_bytepp rows, int startX, int startY, int endX, int endY, int dX,
                           int dY, int* outInset) {
  uint8_t maxOpacity = 0;
  int inset = 0;
  *outInset = 0;
  for (int x = startX, y = startY; x != endX && y != endY; x += dX, y += dY, inset++) {
    png_byte* color = rows[y] + x * 4;
    uint8_t opacity = color[3];
    if (opacity > maxOpacity) {
      maxOpacity = opacity;
      *outInset = inset;
    }
    if (opacity == 0xff) return;
  }
}

static uint8_t maxAlphaOverRow(png_bytep row, int startX, int endX) {
  uint8_t maxAlpha = 0;
  for (int x = startX; x < endX; x++) {
    uint8_t alpha = (row + x * 4)[3];
    if (alpha > maxAlpha) maxAlpha = alpha;
  }
  return maxAlpha;
}

static uint8_t maxAlphaOverCol(png_bytepp rows, int offsetX, int startY, int endY) {
  uint8_t maxAlpha = 0;
  for (int y = startY; y < endY; y++) {
    uint8_t alpha = (rows[y] + offsetX * 4)[3];
    if (alpha > maxAlpha) maxAlpha = alpha;
  }
  return maxAlpha;
}

static void getOutline(PngInfo* image) {
  int midX = image->width / 2;
  int midY = image->height / 2;
  int endX = image->width - 2;
  int endY = image->height - 2;

  // find left and right extent of nine patch content on center row
  if (image->width > 4) {
    findMaxOpacity(image->rows.data(), 1, midY, midX, -1, 1, 0, &image->outlineInsetsLeft);
    findMaxOpacity(image->rows.data(), endX, midY, midX, -1, -1, 0, &image->outlineInsetsRight);
  } else {
    image->outlineInsetsLeft = 0;
    image->outlineInsetsRight = 0;
  }

  // find top and bottom extent of nine patch content on center column
  if (image->height > 4) {
    findMaxOpacity(image->rows.data(), midX, 1, -1, midY, 0, 1, &image->outlineInsetsTop);
    findMaxOpacity(image->rows.data(), midX, endY, -1, midY, 0, -1, &image->outlineInsetsBottom);
  } else {
    image->outlineInsetsTop = 0;
    image->outlineInsetsBottom = 0;
  }

  int innerStartX = 1 + image->outlineInsetsLeft;
  int innerStartY = 1 + image->outlineInsetsTop;
  int innerEndX = endX - image->outlineInsetsRight;
  int innerEndY = endY - image->outlineInsetsBottom;
  int innerMidX = (innerEndX + innerStartX) / 2;
  int innerMidY = (innerEndY + innerStartY) / 2;

  // assuming the image is a round rect, compute the radius by marching
  // diagonally from the top left corner towards the center
  image->outlineAlpha =
      std::max(maxAlphaOverRow(image->rows[innerMidY], innerStartX, innerEndX),
               maxAlphaOverCol(image->rows.data(), innerMidX, innerStartY, innerStartY));

  int diagonalInset = 0;
  findMaxOpacity(image->rows.data(), innerStartX, innerStartY, innerMidX, innerMidY, 1, 1,
                 &diagonalInset);

  /* Determine source radius based upon inset:
   *     sqrt(r^2 + r^2) = sqrt(i^2 + i^2) + r
   *     sqrt(2) * r = sqrt(2) * i + r
   *     (sqrt(2) - 1) * r = sqrt(2) * i
   *     r = sqrt(2) / (sqrt(2) - 1) * i
   */
  image->outlineRadius = 3.4142f * diagonalInset;

  if (kDebug) {
    printf("outline insets %d %d %d %d, rad %f, alpha %x\n", image->outlineInsetsLeft,
           image->outlineInsetsTop, image->outlineInsetsRight, image->outlineInsetsBottom,
           image->outlineRadius, image->outlineAlpha);
  }
}

static uint32_t getColor(png_bytepp rows, int left, int top, int right, int bottom) {
  png_bytep color = rows[top] + left * 4;

  if (left > right || top > bottom) {
    return Res_png_9patch::TRANSPARENT_COLOR;
  }

  while (top <= bottom) {
    for (int i = left; i <= right; i++) {
      png_bytep p = rows[top] + i * 4;
      if (color[3] == 0) {
        if (p[3] != 0) {
          return Res_png_9patch::NO_COLOR;
        }
      } else if (p[0] != color[0] || p[1] != color[1] || p[2] != color[2] || p[3] != color[3]) {
        return Res_png_9patch::NO_COLOR;
      }
    }
    top++;
  }

  if (color[3] == 0) {
    return Res_png_9patch::TRANSPARENT_COLOR;
  }
  return (color[3] << 24) | (color[0] << 16) | (color[1] << 8) | color[2];
}

static bool do9Patch(PngInfo* image, std::string* outError) {
  image->is9Patch = true;

  int W = image->width;
  int H = image->height;
  int i, j;

  const int maxSizeXDivs = W * sizeof(int32_t);
  const int maxSizeYDivs = H * sizeof(int32_t);
  int32_t* xDivs = image->xDivs = new int32_t[W];
  int32_t* yDivs = image->yDivs = new int32_t[H];
  uint8_t numXDivs = 0;
  uint8_t numYDivs = 0;

  int8_t numColors;
  int numRows;
  int numCols;
  int top;
  int left;
  int right;
  int bottom;
  memset(xDivs, -1, maxSizeXDivs);
  memset(yDivs, -1, maxSizeYDivs);
  image->info9Patch.paddingLeft = image->info9Patch.paddingRight = -1;
  image->info9Patch.paddingTop = image->info9Patch.paddingBottom = -1;
  image->layoutBoundsLeft = image->layoutBoundsRight = 0;
  image->layoutBoundsTop = image->layoutBoundsBottom = 0;

  png_bytep p = image->rows[0];
  bool transparent = p[3] == 0;
  bool hasColor = false;

  const char* errorMsg = nullptr;
  int errorPixel = -1;
  const char* errorEdge = nullptr;

  int colorIndex = 0;
  std::vector<png_bytep> newRows;

  // Validate size...
  if (W < 3 || H < 3) {
    errorMsg = "Image must be at least 3x3 (1x1 without frame) pixels";
    goto getout;
  }

  // Validate frame...
  if (!transparent && (p[0] != 0xFF || p[1] != 0xFF || p[2] != 0xFF || p[3] != 0xFF)) {
    errorMsg = "Must have one-pixel frame that is either transparent or white";
    goto getout;
  }

  // Find left and right of sizing areas...
  if (!getHorizontalTicks(p, W, transparent, true, &xDivs[0], &xDivs[1], &errorMsg, &numXDivs,
                          true)) {
    errorPixel = xDivs[0];
    errorEdge = "top";
    goto getout;
  }

  // Find top and bottom of sizing areas...
  if (!getVerticalTicks(image->rows.data(), 0, H, transparent, true, &yDivs[0], &yDivs[1],
                        &errorMsg, &numYDivs, true)) {
    errorPixel = yDivs[0];
    errorEdge = "left";
    goto getout;
  }

  // Copy patch size data into image...
  image->info9Patch.numXDivs = numXDivs;
  image->info9Patch.numYDivs = numYDivs;

  // Find left and right of padding area...
  if (!getHorizontalTicks(image->rows[H - 1], W, transparent, false, &image->info9Patch.paddingLeft,
                          &image->info9Patch.paddingRight, &errorMsg, nullptr, false)) {
    errorPixel = image->info9Patch.paddingLeft;
    errorEdge = "bottom";
    goto getout;
  }

  // Find top and bottom of padding area...
  if (!getVerticalTicks(image->rows.data(), (W - 1) * 4, H, transparent, false,
                        &image->info9Patch.paddingTop, &image->info9Patch.paddingBottom, &errorMsg,
                        nullptr, false)) {
    errorPixel = image->info9Patch.paddingTop;
    errorEdge = "right";
    goto getout;
  }

  // Find left and right of layout padding...
  getHorizontalLayoutBoundsTicks(image->rows[H - 1], W, transparent, false,
                                 &image->layoutBoundsLeft, &image->layoutBoundsRight, &errorMsg);

  getVerticalLayoutBoundsTicks(image->rows.data(), (W - 1) * 4, H, transparent, false,
                               &image->layoutBoundsTop, &image->layoutBoundsBottom, &errorMsg);

  image->haveLayoutBounds = image->layoutBoundsLeft != 0 || image->layoutBoundsRight != 0 ||
                            image->layoutBoundsTop != 0 || image->layoutBoundsBottom != 0;

  if (image->haveLayoutBounds) {
    if (kDebug) {
      printf("layoutBounds=%d %d %d %d\n", image->layoutBoundsLeft, image->layoutBoundsTop,
             image->layoutBoundsRight, image->layoutBoundsBottom);
    }
  }

  // use opacity of pixels to estimate the round rect outline
  getOutline(image);

  // If padding is not yet specified, take values from size.
  if (image->info9Patch.paddingLeft < 0) {
    image->info9Patch.paddingLeft = xDivs[0];
    image->info9Patch.paddingRight = W - 2 - xDivs[1];
  } else {
    // Adjust value to be correct!
    image->info9Patch.paddingRight = W - 2 - image->info9Patch.paddingRight;
  }
  if (image->info9Patch.paddingTop < 0) {
    image->info9Patch.paddingTop = yDivs[0];
    image->info9Patch.paddingBottom = H - 2 - yDivs[1];
  } else {
    // Adjust value to be correct!
    image->info9Patch.paddingBottom = H - 2 - image->info9Patch.paddingBottom;
  }

  /*    if (kDebug) {
          printf("Size ticks for %s: x0=%d, x1=%d, y0=%d, y1=%d\n", imageName,
                  xDivs[0], xDivs[1],
                  yDivs[0], yDivs[1]);
          printf("padding ticks for %s: l=%d, r=%d, t=%d, b=%d\n", imageName,
                  image->info9Patch.paddingLeft, image->info9Patch.paddingRight,
                  image->info9Patch.paddingTop,
     image->info9Patch.paddingBottom);
      }*/

  // Remove frame from image.
  newRows.resize(H - 2);
  for (i = 0; i < H - 2; i++) {
    newRows[i] = image->rows[i + 1];
    memmove(newRows[i], newRows[i] + 4, (W - 2) * 4);
  }
  image->rows.swap(newRows);

  image->width -= 2;
  W = image->width;
  image->height -= 2;
  H = image->height;

  // Figure out the number of rows and columns in the N-patch
  numCols = numXDivs + 1;
  if (xDivs[0] == 0) {  // Column 1 is strechable
    numCols--;
  }
  if (xDivs[numXDivs - 1] == W) {
    numCols--;
  }
  numRows = numYDivs + 1;
  if (yDivs[0] == 0) {  // Row 1 is strechable
    numRows--;
  }
  if (yDivs[numYDivs - 1] == H) {
    numRows--;
  }

  // Make sure the amount of rows and columns will fit in the number of
  // colors we can use in the 9-patch format.
  if (numRows * numCols > 0x7F) {
    errorMsg = "Too many rows and columns in 9-patch perimeter";
    goto getout;
  }

  numColors = numRows * numCols;
  image->info9Patch.numColors = numColors;
  image->colors.resize(numColors);

  // Fill in color information for each patch.

  uint32_t c;
  top = 0;

  // The first row always starts with the top being at y=0 and the bottom
  // being either yDivs[1] (if yDivs[0]=0) of yDivs[0].  In the former case
  // the first row is stretchable along the Y axis, otherwise it is fixed.
  // The last row always ends with the bottom being bitmap.height and the top
  // being either yDivs[numYDivs-2] (if yDivs[numYDivs-1]=bitmap.height) or
  // yDivs[numYDivs-1]. In the former case the last row is stretchable along
  // the Y axis, otherwise it is fixed.
  //
  // The first and last columns are similarly treated with respect to the X
  // axis.
  //
  // The above is to help explain some of the special casing that goes on the
  // code below.

  // The initial yDiv and whether the first row is considered stretchable or
  // not depends on whether yDiv[0] was zero or not.
  for (j = (yDivs[0] == 0 ? 1 : 0); j <= numYDivs && top < H; j++) {
    if (j == numYDivs) {
      bottom = H;
    } else {
      bottom = yDivs[j];
    }
    left = 0;
    // The initial xDiv and whether the first column is considered
    // stretchable or not depends on whether xDiv[0] was zero or not.
    for (i = xDivs[0] == 0 ? 1 : 0; i <= numXDivs && left < W; i++) {
      if (i == numXDivs) {
        right = W;
      } else {
        right = xDivs[i];
      }
      c = getColor(image->rows.data(), left, top, right - 1, bottom - 1);
      image->colors[colorIndex++] = c;
      if (kDebug) {
        if (c != Res_png_9patch::NO_COLOR) {
          hasColor = true;
        }
      }
      left = right;
    }
    top = bottom;
  }

  assert(colorIndex == numColors);

  if (kDebug && hasColor) {
    for (i = 0; i < numColors; i++) {
      if (i == 0) printf("Colors:\n");
      printf(" #%08x", image->colors[i]);
      if (i == numColors - 1) printf("\n");
    }
  }
getout:
  if (errorMsg) {
    std::stringstream err;
    err << "9-patch malformed: " << errorMsg;
    if (errorEdge) {
      err << "." << std::endl;
      if (errorPixel >= 0) {
        err << "Found at pixel #" << errorPixel << " along " << errorEdge << " edge";
      } else {
        err << "Found along " << errorEdge << " edge";
      }
    }
    *outError = err.str();
    return false;
  }
  return true;
}

bool Png::process(const Source& source, std::istream* input, BigBuffer* outBuffer,
                  const PngOptions& options) {
  png_byte signature[kPngSignatureSize];

  // Read the PNG signature first.
  if (!input->read(reinterpret_cast<char*>(signature), kPngSignatureSize)) {
    mDiag->Error(DiagMessage() << strerror(errno));
    return false;
  }

  // If the PNG signature doesn't match, bail early.
  if (png_sig_cmp(signature, 0, kPngSignatureSize) != 0) {
    mDiag->Error(DiagMessage() << "not a valid png file");
    return false;
  }

  bool result = false;
  png_structp readPtr = nullptr;
  png_infop infoPtr = nullptr;
  png_structp writePtr = nullptr;
  png_infop writeInfoPtr = nullptr;
  PngInfo pngInfo = {};

  readPtr = png_create_read_struct(PNG_LIBPNG_VER_STRING, 0, nullptr, nullptr);
  if (!readPtr) {
    mDiag->Error(DiagMessage() << "failed to allocate read ptr");
    goto bail;
  }

  infoPtr = png_create_info_struct(readPtr);
  if (!infoPtr) {
    mDiag->Error(DiagMessage() << "failed to allocate info ptr");
    goto bail;
  }

  png_set_error_fn(readPtr, reinterpret_cast<png_voidp>(mDiag), nullptr, logWarning);

  // Set the read function to read from std::istream.
  png_set_read_fn(readPtr, (png_voidp)input, readDataFromStream);

  if (!readPng(mDiag, readPtr, infoPtr, &pngInfo)) {
    goto bail;
  }

  if (android::base::EndsWith(source.path, ".9.png")) {
    std::string errorMsg;
    if (!do9Patch(&pngInfo, &errorMsg)) {
      mDiag->Error(DiagMessage() << errorMsg);
      goto bail;
    }
  }

  writePtr = png_create_write_struct(PNG_LIBPNG_VER_STRING, 0, nullptr, nullptr);
  if (!writePtr) {
    mDiag->Error(DiagMessage() << "failed to allocate write ptr");
    goto bail;
  }

  writeInfoPtr = png_create_info_struct(writePtr);
  if (!writeInfoPtr) {
    mDiag->Error(DiagMessage() << "failed to allocate write info ptr");
    goto bail;
  }

  png_set_error_fn(writePtr, nullptr, nullptr, logWarning);

  // Set the write function to write to std::ostream.
  png_set_write_fn(writePtr, (png_voidp)outBuffer, writeDataToStream, flushDataToStream);

  if (!writePng(mDiag, writePtr, writeInfoPtr, &pngInfo, options.grayscale_tolerance)) {
    goto bail;
  }

  result = true;
bail:
  if (readPtr) {
    png_destroy_read_struct(&readPtr, &infoPtr, nullptr);
  }

  if (writePtr) {
    png_destroy_write_struct(&writePtr, &writeInfoPtr);
  }
  return result;
}

}  // namespace android
