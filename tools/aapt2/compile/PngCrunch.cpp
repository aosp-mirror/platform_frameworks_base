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

#include "compile/Png.h"

#include <android-base/errors.h>
#include <android-base/macros.h>
#include <png.h>
#include <zlib.h>
#include <algorithm>
#include <unordered_map>
#include <unordered_set>

namespace aapt {

// Size in bytes of the PNG signature.
constexpr size_t kPngSignatureSize = 8u;

/**
 * Custom deleter that destroys libpng read and info structs.
 */
class PngReadStructDeleter {
 public:
  explicit PngReadStructDeleter(png_structp readPtr, png_infop infoPtr)
      : mReadPtr(readPtr), mInfoPtr(infoPtr) {}

  ~PngReadStructDeleter() {
    png_destroy_read_struct(&mReadPtr, &mInfoPtr, nullptr);
  }

 private:
  png_structp mReadPtr;
  png_infop mInfoPtr;

  DISALLOW_COPY_AND_ASSIGN(PngReadStructDeleter);
};

/**
 * Custom deleter that destroys libpng write and info structs.
 */
class PngWriteStructDeleter {
 public:
  explicit PngWriteStructDeleter(png_structp writePtr, png_infop infoPtr)
      : mWritePtr(writePtr), mInfoPtr(infoPtr) {}

  ~PngWriteStructDeleter() { png_destroy_write_struct(&mWritePtr, &mInfoPtr); }

 private:
  png_structp mWritePtr;
  png_infop mInfoPtr;

  DISALLOW_COPY_AND_ASSIGN(PngWriteStructDeleter);
};

// Custom warning logging method that uses IDiagnostics.
static void logWarning(png_structp pngPtr, png_const_charp warningMsg) {
  IDiagnostics* diag = (IDiagnostics*)png_get_error_ptr(pngPtr);
  diag->warn(DiagMessage() << warningMsg);
}

// Custom error logging method that uses IDiagnostics.
static void logError(png_structp pngPtr, png_const_charp errorMsg) {
  IDiagnostics* diag = (IDiagnostics*)png_get_error_ptr(pngPtr);
  diag->error(DiagMessage() << errorMsg);
}

static void readDataFromStream(png_structp pngPtr, png_bytep buffer,
                               png_size_t len) {
  io::InputStream* in = (io::InputStream*)png_get_io_ptr(pngPtr);

  const void* inBuffer;
  int inLen;
  if (!in->Next(&inBuffer, &inLen)) {
    if (in->HadError()) {
      std::string err = in->GetError();
      png_error(pngPtr, err.c_str());
    }
    return;
  }

  const size_t bytesRead = std::min(static_cast<size_t>(inLen), len);
  memcpy(buffer, inBuffer, bytesRead);
  if (bytesRead != static_cast<size_t>(inLen)) {
    in->BackUp(inLen - static_cast<int>(bytesRead));
  }
}

static void writeDataToStream(png_structp pngPtr, png_bytep buffer,
                              png_size_t len) {
  io::OutputStream* out = (io::OutputStream*)png_get_io_ptr(pngPtr);

  void* outBuffer;
  int outLen;
  while (len > 0) {
    if (!out->Next(&outBuffer, &outLen)) {
      if (out->HadError()) {
        std::string err = out->GetError();
        png_error(pngPtr, err.c_str());
      }
      return;
    }

    const size_t bytesWritten = std::min(static_cast<size_t>(outLen), len);
    memcpy(outBuffer, buffer, bytesWritten);

    // Advance the input buffer.
    buffer += bytesWritten;
    len -= bytesWritten;

    // Advance the output buffer.
    outLen -= static_cast<int>(bytesWritten);
  }

  // If the entire output buffer wasn't used, backup.
  if (outLen > 0) {
    out->BackUp(outLen);
  }
}

std::unique_ptr<Image> readPng(IAaptContext* context, io::InputStream* in) {
  // Read the first 8 bytes of the file looking for the PNG signature.
  // Bail early if it does not match.
  const png_byte* signature;
  int bufferSize;
  if (!in->Next((const void**)&signature, &bufferSize)) {
    context->getDiagnostics()->error(
        DiagMessage() << android::base::SystemErrorCodeToString(errno));
    return {};
  }

  if (static_cast<size_t>(bufferSize) < kPngSignatureSize ||
      png_sig_cmp(signature, 0, kPngSignatureSize) != 0) {
    context->getDiagnostics()->error(
        DiagMessage() << "file signature does not match PNG signature");
    return {};
  }

  // Start at the beginning of the first chunk.
  in->BackUp(bufferSize - static_cast<int>(kPngSignatureSize));

  // Create and initialize the png_struct with the default error and warning
  // handlers.
  // The header version is also passed in to ensure that this was built against
  // the same
  // version of libpng.
  png_structp readPtr =
      png_create_read_struct(PNG_LIBPNG_VER_STRING, nullptr, nullptr, nullptr);
  if (readPtr == nullptr) {
    context->getDiagnostics()->error(
        DiagMessage() << "failed to create libpng read png_struct");
    return {};
  }

  // Create and initialize the memory for image header and data.
  png_infop infoPtr = png_create_info_struct(readPtr);
  if (infoPtr == nullptr) {
    context->getDiagnostics()->error(
        DiagMessage() << "failed to create libpng read png_info");
    png_destroy_read_struct(&readPtr, nullptr, nullptr);
    return {};
  }

  // Automatically release PNG resources at end of scope.
  PngReadStructDeleter pngReadDeleter(readPtr, infoPtr);

  // libpng uses longjmp to jump to an error handling routine.
  // setjmp will only return true if it was jumped to, aka there was
  // an error.
  if (setjmp(png_jmpbuf(readPtr))) {
    return {};
  }

  // Handle warnings ourselves via IDiagnostics.
  png_set_error_fn(readPtr, (png_voidp)context->getDiagnostics(), logError,
                   logWarning);

  // Set up the read functions which read from our custom data sources.
  png_set_read_fn(readPtr, (png_voidp)in, readDataFromStream);

  // Skip the signature that we already read.
  png_set_sig_bytes(readPtr, kPngSignatureSize);

  // Read the chunk headers.
  png_read_info(readPtr, infoPtr);

  // Extract image meta-data from the various chunk headers.
  uint32_t width, height;
  int bitDepth, colorType, interlaceMethod, compressionMethod, filterMethod;
  png_get_IHDR(readPtr, infoPtr, &width, &height, &bitDepth, &colorType,
               &interlaceMethod, &compressionMethod, &filterMethod);

  // When the image is read, expand it so that it is in RGBA 8888 format
  // so that image handling is uniform.

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

  if (colorType == PNG_COLOR_TYPE_GRAY ||
      colorType == PNG_COLOR_TYPE_GRAY_ALPHA) {
    png_set_gray_to_rgb(readPtr);
  }

  if (interlaceMethod != PNG_INTERLACE_NONE) {
    png_set_interlace_handling(readPtr);
  }

  // Once all the options for reading have been set, we need to flush
  // them to libpng.
  png_read_update_info(readPtr, infoPtr);

  // 9-patch uses int32_t to index images, so we cap the image dimensions to
  // something
  // that can always be represented by 9-patch.
  if (width > std::numeric_limits<int32_t>::max() ||
      height > std::numeric_limits<int32_t>::max()) {
    context->getDiagnostics()->error(DiagMessage()
                                     << "PNG image dimensions are too large: "
                                     << width << "x" << height);
    return {};
  }

  std::unique_ptr<Image> outputImage = util::make_unique<Image>();
  outputImage->width = static_cast<int32_t>(width);
  outputImage->height = static_cast<int32_t>(height);

  const size_t rowBytes = png_get_rowbytes(readPtr, infoPtr);
  assert(rowBytes == 4 * width);  // RGBA

  // Allocate one large block to hold the image.
  outputImage->data =
      std::unique_ptr<uint8_t[]>(new uint8_t[height * rowBytes]);

  // Create an array of rows that index into the data block.
  outputImage->rows = std::unique_ptr<uint8_t* []>(new uint8_t*[height]);
  for (uint32_t h = 0; h < height; h++) {
    outputImage->rows[h] = outputImage->data.get() + (h * rowBytes);
  }

  // Actually read the image pixels.
  png_read_image(readPtr, outputImage->rows.get());

  // Finish reading. This will read any other chunks after the image data.
  png_read_end(readPtr, infoPtr);

  return outputImage;
}

/**
 * Experimentally chosen constant to be added to the overhead of using color
 * type
 * PNG_COLOR_TYPE_PALETTE to account for the uncompressability of the palette
 * chunk.
 * Without this, many small PNGs encoded with palettes are larger after
 * compression than
 * the same PNGs encoded as RGBA.
 */
constexpr static const size_t kPaletteOverheadConstant = 1024u * 10u;

// Pick a color type by which to encode the image, based on which color type
// will take
// the least amount of disk space.
//
// 9-patch images traditionally have not been encoded with palettes.
// The original rationale was to avoid dithering until after scaling,
// but I don't think this would be an issue with palettes. Either way,
// our naive size estimation tends to be wrong for small images like 9-patches
// and using palettes balloons the size of the resulting 9-patch.
// In order to not regress in size, restrict 9-patch to not use palettes.

// The options are:
//
// - RGB
// - RGBA
// - RGB + cheap alpha
// - Color palette
// - Color palette + cheap alpha
// - Color palette + alpha palette
// - Grayscale
// - Grayscale + cheap alpha
// - Grayscale + alpha
//
static int pickColorType(int32_t width, int32_t height, bool grayScale,
                         bool convertibleToGrayScale, bool hasNinePatch,
                         size_t colorPaletteSize, size_t alphaPaletteSize) {
  const size_t paletteChunkSize = 16 + colorPaletteSize * 3;
  const size_t alphaChunkSize = 16 + alphaPaletteSize;
  const size_t colorAlphaDataChunkSize = 16 + 4 * width * height;
  const size_t colorDataChunkSize = 16 + 3 * width * height;
  const size_t grayScaleAlphaDataChunkSize = 16 + 2 * width * height;
  const size_t paletteDataChunkSize = 16 + width * height;

  if (grayScale) {
    if (alphaPaletteSize == 0) {
      // This is the smallest the data can be.
      return PNG_COLOR_TYPE_GRAY;
    } else if (colorPaletteSize <= 256 && !hasNinePatch) {
      // This grayscale has alpha and can fit within a palette.
      // See if it is worth fitting into a palette.
      const size_t paletteThreshold = paletteChunkSize + alphaChunkSize +
                                      paletteDataChunkSize +
                                      kPaletteOverheadConstant;
      if (grayScaleAlphaDataChunkSize > paletteThreshold) {
        return PNG_COLOR_TYPE_PALETTE;
      }
    }
    return PNG_COLOR_TYPE_GRAY_ALPHA;
  }

  if (colorPaletteSize <= 256 && !hasNinePatch) {
    // This image can fit inside a palette. Let's see if it is worth it.
    size_t totalSizeWithPalette = paletteDataChunkSize + paletteChunkSize;
    size_t totalSizeWithoutPalette = colorDataChunkSize;
    if (alphaPaletteSize > 0) {
      totalSizeWithPalette += alphaPaletteSize;
      totalSizeWithoutPalette = colorAlphaDataChunkSize;
    }

    if (totalSizeWithoutPalette >
        totalSizeWithPalette + kPaletteOverheadConstant) {
      return PNG_COLOR_TYPE_PALETTE;
    }
  }

  if (convertibleToGrayScale) {
    if (alphaPaletteSize == 0) {
      return PNG_COLOR_TYPE_GRAY;
    } else {
      return PNG_COLOR_TYPE_GRAY_ALPHA;
    }
  }

  if (alphaPaletteSize == 0) {
    return PNG_COLOR_TYPE_RGB;
  }
  return PNG_COLOR_TYPE_RGBA;
}

// Assigns indices to the color and alpha palettes, encodes them, and then
// invokes
// png_set_PLTE/png_set_tRNS.
// This must be done before writing image data.
// Image data must be transformed to use the indices assigned within the
// palette.
static void writePalette(png_structp writePtr, png_infop writeInfoPtr,
                         std::unordered_map<uint32_t, int>* colorPalette,
                         std::unordered_set<uint32_t>* alphaPalette) {
  assert(colorPalette->size() <= 256);
  assert(alphaPalette->size() <= 256);

  // Populate the PNG palette struct and assign indices to the color
  // palette.

  // Colors in the alpha palette should have smaller indices.
  // This will ensure that we can truncate the alpha palette if it is
  // smaller than the color palette.
  int index = 0;
  for (uint32_t color : *alphaPalette) {
    (*colorPalette)[color] = index++;
  }

  // Assign the rest of the entries.
  for (auto& entry : *colorPalette) {
    if (entry.second == -1) {
      entry.second = index++;
    }
  }

  // Create the PNG color palette struct.
  auto colorPaletteBytes =
      std::unique_ptr<png_color[]>(new png_color[colorPalette->size()]);

  std::unique_ptr<png_byte[]> alphaPaletteBytes;
  if (!alphaPalette->empty()) {
    alphaPaletteBytes =
        std::unique_ptr<png_byte[]>(new png_byte[alphaPalette->size()]);
  }

  for (const auto& entry : *colorPalette) {
    const uint32_t color = entry.first;
    const int index = entry.second;
    assert(index >= 0);
    assert(static_cast<size_t>(index) < colorPalette->size());

    png_colorp slot = colorPaletteBytes.get() + index;
    slot->red = color >> 24;
    slot->green = color >> 16;
    slot->blue = color >> 8;

    const png_byte alpha = color & 0x000000ff;
    if (alpha != 0xff && alphaPaletteBytes) {
      assert(static_cast<size_t>(index) < alphaPalette->size());
      alphaPaletteBytes[index] = alpha;
    }
  }

  // The bytes get copied here, so it is safe to release colorPaletteBytes at
  // the end of function
  // scope.
  png_set_PLTE(writePtr, writeInfoPtr, colorPaletteBytes.get(),
               colorPalette->size());

  if (alphaPaletteBytes) {
    png_set_tRNS(writePtr, writeInfoPtr, alphaPaletteBytes.get(),
                 alphaPalette->size(), nullptr);
  }
}

// Write the 9-patch custom PNG chunks to writeInfoPtr. This must be done before
// writing image data.
static void writeNinePatch(png_structp writePtr, png_infop writeInfoPtr,
                           const NinePatch* ninePatch) {
  // The order of the chunks is important.
  // 9-patch code in older platforms expects the 9-patch chunk to
  // be last.

  png_unknown_chunk unknownChunks[3];
  memset(unknownChunks, 0, sizeof(unknownChunks));

  size_t index = 0;
  size_t chunkLen = 0;

  std::unique_ptr<uint8_t[]> serializedOutline =
      ninePatch->serializeRoundedRectOutline(&chunkLen);
  strcpy((char*)unknownChunks[index].name, "npOl");
  unknownChunks[index].size = chunkLen;
  unknownChunks[index].data = (png_bytep)serializedOutline.get();
  unknownChunks[index].location = PNG_HAVE_PLTE;
  index++;

  std::unique_ptr<uint8_t[]> serializedLayoutBounds;
  if (ninePatch->layoutBounds.nonZero()) {
    serializedLayoutBounds = ninePatch->serializeLayoutBounds(&chunkLen);
    strcpy((char*)unknownChunks[index].name, "npLb");
    unknownChunks[index].size = chunkLen;
    unknownChunks[index].data = (png_bytep)serializedLayoutBounds.get();
    unknownChunks[index].location = PNG_HAVE_PLTE;
    index++;
  }

  std::unique_ptr<uint8_t[]> serializedNinePatch =
      ninePatch->serializeBase(&chunkLen);
  strcpy((char*)unknownChunks[index].name, "npTc");
  unknownChunks[index].size = chunkLen;
  unknownChunks[index].data = (png_bytep)serializedNinePatch.get();
  unknownChunks[index].location = PNG_HAVE_PLTE;
  index++;

  // Handle all unknown chunks. We are manually setting the chunks here,
  // so we will only ever handle our custom chunks.
  png_set_keep_unknown_chunks(writePtr, PNG_HANDLE_CHUNK_ALWAYS, nullptr, 0);

  // Set the actual chunks here. The data gets copied, so our buffers can
  // safely go out of scope.
  png_set_unknown_chunks(writePtr, writeInfoPtr, unknownChunks, index);
}

bool writePng(IAaptContext* context, const Image* image,
              const NinePatch* ninePatch, io::OutputStream* out,
              const PngOptions& options) {
  // Create and initialize the write png_struct with the default error and
  // warning handlers.
  // The header version is also passed in to ensure that this was built against
  // the same
  // version of libpng.
  png_structp writePtr =
      png_create_write_struct(PNG_LIBPNG_VER_STRING, nullptr, nullptr, nullptr);
  if (writePtr == nullptr) {
    context->getDiagnostics()->error(
        DiagMessage() << "failed to create libpng write png_struct");
    return false;
  }

  // Allocate memory to store image header data.
  png_infop writeInfoPtr = png_create_info_struct(writePtr);
  if (writeInfoPtr == nullptr) {
    context->getDiagnostics()->error(
        DiagMessage() << "failed to create libpng write png_info");
    png_destroy_write_struct(&writePtr, nullptr);
    return false;
  }

  // Automatically release PNG resources at end of scope.
  PngWriteStructDeleter pngWriteDeleter(writePtr, writeInfoPtr);

  // libpng uses longjmp to jump to error handling routines.
  // setjmp will return true only if it was jumped to, aka, there was an error.
  if (setjmp(png_jmpbuf(writePtr))) {
    return false;
  }

  // Handle warnings with our IDiagnostics.
  png_set_error_fn(writePtr, (png_voidp)context->getDiagnostics(), logError,
                   logWarning);

  // Set up the write functions which write to our custom data sources.
  png_set_write_fn(writePtr, (png_voidp)out, writeDataToStream, nullptr);

  // We want small files and can take the performance hit to achieve this goal.
  png_set_compression_level(writePtr, Z_BEST_COMPRESSION);

  // Begin analysis of the image data.
  // Scan the entire image and determine if:
  // 1. Every pixel has R == G == B (grayscale)
  // 2. Every pixel has A == 255 (opaque)
  // 3. There are no more than 256 distinct RGBA colors (palette).
  std::unordered_map<uint32_t, int> colorPalette;
  std::unordered_set<uint32_t> alphaPalette;
  bool needsToZeroRGBChannelsOfTransparentPixels = false;
  bool grayScale = true;
  int maxGrayDeviation = 0;

  for (int32_t y = 0; y < image->height; y++) {
    const uint8_t* row = image->rows[y];
    for (int32_t x = 0; x < image->width; x++) {
      int red = *row++;
      int green = *row++;
      int blue = *row++;
      int alpha = *row++;

      if (alpha == 0) {
        // The color is completely transparent.
        // For purposes of palettes and grayscale optimization,
        // treat all channels as 0x00.
        needsToZeroRGBChannelsOfTransparentPixels =
            needsToZeroRGBChannelsOfTransparentPixels ||
            (red != 0 || green != 0 || blue != 0);
        red = green = blue = 0;
      }

      // Insert the color into the color palette.
      const uint32_t color = red << 24 | green << 16 | blue << 8 | alpha;
      colorPalette[color] = -1;

      // If the pixel has non-opaque alpha, insert it into the
      // alpha palette.
      if (alpha != 0xff) {
        alphaPalette.insert(color);
      }

      // Check if the image is indeed grayscale.
      if (grayScale) {
        if (red != green || red != blue) {
          grayScale = false;
        }
      }

      // Calculate the gray scale deviation so that it can be compared
      // with the threshold.
      maxGrayDeviation = std::max(std::abs(red - green), maxGrayDeviation);
      maxGrayDeviation = std::max(std::abs(green - blue), maxGrayDeviation);
      maxGrayDeviation = std::max(std::abs(blue - red), maxGrayDeviation);
    }
  }

  if (context->verbose()) {
    DiagMessage msg;
    msg << " paletteSize=" << colorPalette.size()
        << " alphaPaletteSize=" << alphaPalette.size()
        << " maxGrayDeviation=" << maxGrayDeviation
        << " grayScale=" << (grayScale ? "true" : "false");
    context->getDiagnostics()->note(msg);
  }

  const bool convertibleToGrayScale =
      maxGrayDeviation <= options.grayScaleTolerance;

  const int newColorType = pickColorType(
      image->width, image->height, grayScale, convertibleToGrayScale,
      ninePatch != nullptr, colorPalette.size(), alphaPalette.size());

  if (context->verbose()) {
    DiagMessage msg;
    msg << "encoding PNG ";
    if (ninePatch) {
      msg << "(with 9-patch) as ";
    }
    switch (newColorType) {
      case PNG_COLOR_TYPE_GRAY:
        msg << "GRAY";
        break;
      case PNG_COLOR_TYPE_GRAY_ALPHA:
        msg << "GRAY + ALPHA";
        break;
      case PNG_COLOR_TYPE_RGB:
        msg << "RGB";
        break;
      case PNG_COLOR_TYPE_RGB_ALPHA:
        msg << "RGBA";
        break;
      case PNG_COLOR_TYPE_PALETTE:
        msg << "PALETTE";
        break;
      default:
        msg << "unknown type " << newColorType;
        break;
    }
    context->getDiagnostics()->note(msg);
  }

  png_set_IHDR(writePtr, writeInfoPtr, image->width, image->height, 8,
               newColorType, PNG_INTERLACE_NONE, PNG_COMPRESSION_TYPE_DEFAULT,
               PNG_FILTER_TYPE_DEFAULT);

  if (newColorType & PNG_COLOR_MASK_PALETTE) {
    // Assigns indices to the palette, and writes the encoded palette to the
    // libpng writePtr.
    writePalette(writePtr, writeInfoPtr, &colorPalette, &alphaPalette);
    png_set_filter(writePtr, 0, PNG_NO_FILTERS);
  } else {
    png_set_filter(writePtr, 0, PNG_ALL_FILTERS);
  }

  if (ninePatch) {
    writeNinePatch(writePtr, writeInfoPtr, ninePatch);
  }

  // Flush our updates to the header.
  png_write_info(writePtr, writeInfoPtr);

  // Write out each row of image data according to its encoding.
  if (newColorType == PNG_COLOR_TYPE_PALETTE) {
    // 1 byte/pixel.
    auto outRow = std::unique_ptr<png_byte[]>(new png_byte[image->width]);

    for (int32_t y = 0; y < image->height; y++) {
      png_const_bytep inRow = image->rows[y];
      for (int32_t x = 0; x < image->width; x++) {
        int rr = *inRow++;
        int gg = *inRow++;
        int bb = *inRow++;
        int aa = *inRow++;
        if (aa == 0) {
          // Zero out color channels when transparent.
          rr = gg = bb = 0;
        }

        const uint32_t color = rr << 24 | gg << 16 | bb << 8 | aa;
        const int idx = colorPalette[color];
        assert(idx != -1);
        outRow[x] = static_cast<png_byte>(idx);
      }
      png_write_row(writePtr, outRow.get());
    }
  } else if (newColorType == PNG_COLOR_TYPE_GRAY ||
             newColorType == PNG_COLOR_TYPE_GRAY_ALPHA) {
    const size_t bpp = newColorType == PNG_COLOR_TYPE_GRAY ? 1 : 2;
    auto outRow = std::unique_ptr<png_byte[]>(new png_byte[image->width * bpp]);

    for (int32_t y = 0; y < image->height; y++) {
      png_const_bytep inRow = image->rows[y];
      for (int32_t x = 0; x < image->width; x++) {
        int rr = inRow[x * 4];
        int gg = inRow[x * 4 + 1];
        int bb = inRow[x * 4 + 2];
        int aa = inRow[x * 4 + 3];
        if (aa == 0) {
          // Zero out the gray channel when transparent.
          rr = gg = bb = 0;
        }

        if (grayScale) {
          // The image was already grayscale, red == green == blue.
          outRow[x * bpp] = inRow[x * 4];
        } else {
          // The image is convertible to grayscale, use linear-luminance of
          // sRGB colorspace:
          // https://en.wikipedia.org/wiki/Grayscale#Colorimetric_.28luminance-preserving.29_conversion_to_grayscale
          outRow[x * bpp] =
              (png_byte)(rr * 0.2126f + gg * 0.7152f + bb * 0.0722f);
        }

        if (bpp == 2) {
          // Write out alpha if we have it.
          outRow[x * bpp + 1] = aa;
        }
      }
      png_write_row(writePtr, outRow.get());
    }
  } else if (newColorType == PNG_COLOR_TYPE_RGB ||
             newColorType == PNG_COLOR_TYPE_RGBA) {
    const size_t bpp = newColorType == PNG_COLOR_TYPE_RGB ? 3 : 4;
    if (needsToZeroRGBChannelsOfTransparentPixels) {
      // The source RGBA data can't be used as-is, because we need to zero out
      // the RGB
      // values of transparent pixels.
      auto outRow =
          std::unique_ptr<png_byte[]>(new png_byte[image->width * bpp]);

      for (int32_t y = 0; y < image->height; y++) {
        png_const_bytep inRow = image->rows[y];
        for (int32_t x = 0; x < image->width; x++) {
          int rr = *inRow++;
          int gg = *inRow++;
          int bb = *inRow++;
          int aa = *inRow++;
          if (aa == 0) {
            // Zero out the RGB channels when transparent.
            rr = gg = bb = 0;
          }
          outRow[x * bpp] = rr;
          outRow[x * bpp + 1] = gg;
          outRow[x * bpp + 2] = bb;
          if (bpp == 4) {
            outRow[x * bpp + 3] = aa;
          }
        }
        png_write_row(writePtr, outRow.get());
      }
    } else {
      // The source image can be used as-is, just tell libpng whether or not to
      // ignore
      // the alpha channel.
      if (newColorType == PNG_COLOR_TYPE_RGB) {
        // Delete the extraneous alpha values that we appended to our buffer
        // when reading the original values.
        png_set_filler(writePtr, 0, PNG_FILLER_AFTER);
      }
      png_write_image(writePtr, image->rows.get());
    }
  } else {
    assert(false && "unreachable");
  }

  png_write_end(writePtr, writeInfoPtr);
  return true;
}

}  // namespace aapt
