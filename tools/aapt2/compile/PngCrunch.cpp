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

#include <png.h>
#include <zlib.h>

#include <algorithm>
#include <unordered_map>
#include <unordered_set>

#include "android-base/errors.h"
#include "android-base/logging.h"
#include "android-base/macros.h"

#include "trace/TraceBuffer.h"

namespace aapt {

// Custom deleter that destroys libpng read and info structs.
class PngReadStructDeleter {
 public:
  PngReadStructDeleter(png_structp read_ptr, png_infop info_ptr)
      : read_ptr_(read_ptr), info_ptr_(info_ptr) {}

  ~PngReadStructDeleter() {
    png_destroy_read_struct(&read_ptr_, &info_ptr_, nullptr);
  }

 private:
  png_structp read_ptr_;
  png_infop info_ptr_;

  DISALLOW_COPY_AND_ASSIGN(PngReadStructDeleter);
};

// Custom deleter that destroys libpng write and info structs.
class PngWriteStructDeleter {
 public:
  PngWriteStructDeleter(png_structp write_ptr, png_infop info_ptr)
      : write_ptr_(write_ptr), info_ptr_(info_ptr) {}

  ~PngWriteStructDeleter() {
    png_destroy_write_struct(&write_ptr_, &info_ptr_);
  }

 private:
  png_structp write_ptr_;
  png_infop info_ptr_;

  DISALLOW_COPY_AND_ASSIGN(PngWriteStructDeleter);
};

// Custom warning logging method that uses IDiagnostics.
static void LogWarning(png_structp png_ptr, png_const_charp warning_msg) {
  IDiagnostics* diag = (IDiagnostics*)png_get_error_ptr(png_ptr);
  diag->Warn(DiagMessage() << warning_msg);
}

// Custom error logging method that uses IDiagnostics.
static void LogError(png_structp png_ptr, png_const_charp error_msg) {
  IDiagnostics* diag = (IDiagnostics*)png_get_error_ptr(png_ptr);
  diag->Error(DiagMessage() << error_msg);

  // Causes libpng to longjmp to the spot where setjmp was set. This is how libpng does
  // error handling. If this custom error handler method were to return, libpng would, by
  // default, print the error message to stdout and call the same png_longjmp method.
  png_longjmp(png_ptr, 1);
}

static void ReadDataFromStream(png_structp png_ptr, png_bytep buffer, png_size_t len) {
  io::InputStream* in = (io::InputStream*)png_get_io_ptr(png_ptr);

  const void* in_buffer;
  size_t in_len;
  if (!in->Next(&in_buffer, &in_len)) {
    if (in->HadError()) {
      std::stringstream error_msg_builder;
      error_msg_builder << "failed reading from input";
      if (!in->GetError().empty()) {
        error_msg_builder << ": " << in->GetError();
      }
      std::string err = error_msg_builder.str();
      png_error(png_ptr, err.c_str());
    }
    return;
  }

  const size_t bytes_read = std::min(in_len, len);
  memcpy(buffer, in_buffer, bytes_read);
  if (bytes_read != in_len) {
    in->BackUp(in_len - bytes_read);
  }
}

static void WriteDataToStream(png_structp png_ptr, png_bytep buffer, png_size_t len) {
  io::OutputStream* out = (io::OutputStream*)png_get_io_ptr(png_ptr);

  void* out_buffer;
  size_t out_len;
  while (len > 0) {
    if (!out->Next(&out_buffer, &out_len)) {
      if (out->HadError()) {
        std::stringstream err_msg_builder;
        err_msg_builder << "failed writing to output";
        if (!out->GetError().empty()) {
          err_msg_builder << ": " << out->GetError();
        }
        std::string err = out->GetError();
        png_error(png_ptr, err.c_str());
      }
      return;
    }

    const size_t bytes_written = std::min(out_len, len);
    memcpy(out_buffer, buffer, bytes_written);

    // Advance the input buffer.
    buffer += bytes_written;
    len -= bytes_written;

    // Advance the output buffer.
    out_len -= bytes_written;
  }

  // If the entire output buffer wasn't used, backup.
  if (out_len > 0) {
    out->BackUp(out_len);
  }
}

std::unique_ptr<Image> ReadPng(IAaptContext* context, const Source& source, io::InputStream* in) {
  TRACE_CALL();
  // Create a diagnostics that has the source information encoded.
  SourcePathDiagnostics source_diag(source, context->GetDiagnostics());

  // Read the first 8 bytes of the file looking for the PNG signature.
  // Bail early if it does not match.
  const png_byte* signature;
  size_t buffer_size;
  if (!in->Next((const void**)&signature, &buffer_size)) {
    if (in->HadError()) {
      source_diag.Error(DiagMessage() << "failed to read PNG signature: " << in->GetError());
    } else {
      source_diag.Error(DiagMessage() << "not enough data for PNG signature");
    }
    return {};
  }

  if (buffer_size < kPngSignatureSize || png_sig_cmp(signature, 0, kPngSignatureSize) != 0) {
    source_diag.Error(DiagMessage() << "file signature does not match PNG signature");
    return {};
  }

  // Start at the beginning of the first chunk.
  in->BackUp(buffer_size - kPngSignatureSize);

  // Create and initialize the png_struct with the default error and warning handlers.
  // The header version is also passed in to ensure that this was built against the same
  // version of libpng.
  png_structp read_ptr = png_create_read_struct(PNG_LIBPNG_VER_STRING, nullptr, nullptr, nullptr);
  if (read_ptr == nullptr) {
    source_diag.Error(DiagMessage() << "failed to create libpng read png_struct");
    return {};
  }

  // Create and initialize the memory for image header and data.
  png_infop info_ptr = png_create_info_struct(read_ptr);
  if (info_ptr == nullptr) {
    source_diag.Error(DiagMessage() << "failed to create libpng read png_info");
    png_destroy_read_struct(&read_ptr, nullptr, nullptr);
    return {};
  }

  // Automatically release PNG resources at end of scope.
  PngReadStructDeleter png_read_deleter(read_ptr, info_ptr);

  // libpng uses longjmp to jump to an error handling routine.
  // setjmp will only return true if it was jumped to, aka there was
  // an error.
  if (setjmp(png_jmpbuf(read_ptr))) {
    return {};
  }

  // Handle warnings ourselves via IDiagnostics.
  png_set_error_fn(read_ptr, (png_voidp)&source_diag, LogError, LogWarning);

  // Set up the read functions which read from our custom data sources.
  png_set_read_fn(read_ptr, (png_voidp)in, ReadDataFromStream);

  // Skip the signature that we already read.
  png_set_sig_bytes(read_ptr, kPngSignatureSize);

  // Read the chunk headers.
  png_read_info(read_ptr, info_ptr);

  // Extract image meta-data from the various chunk headers.
  uint32_t width, height;
  int bit_depth, color_type, interlace_method, compression_method, filter_method;
  png_get_IHDR(read_ptr, info_ptr, &width, &height, &bit_depth, &color_type,
               &interlace_method, &compression_method, &filter_method);

  // When the image is read, expand it so that it is in RGBA 8888 format
  // so that image handling is uniform.

  if (color_type == PNG_COLOR_TYPE_PALETTE) {
    png_set_palette_to_rgb(read_ptr);
  }

  if (color_type == PNG_COLOR_TYPE_GRAY && bit_depth < 8) {
    png_set_expand_gray_1_2_4_to_8(read_ptr);
  }

  if (png_get_valid(read_ptr, info_ptr, PNG_INFO_tRNS)) {
    png_set_tRNS_to_alpha(read_ptr);
  }

  if (bit_depth == 16) {
    png_set_strip_16(read_ptr);
  }

  if (!(color_type & PNG_COLOR_MASK_ALPHA)) {
    png_set_add_alpha(read_ptr, 0xFF, PNG_FILLER_AFTER);
  }

  if (color_type == PNG_COLOR_TYPE_GRAY ||
      color_type == PNG_COLOR_TYPE_GRAY_ALPHA) {
    png_set_gray_to_rgb(read_ptr);
  }

  if (interlace_method != PNG_INTERLACE_NONE) {
    png_set_interlace_handling(read_ptr);
  }

  // Once all the options for reading have been set, we need to flush
  // them to libpng.
  png_read_update_info(read_ptr, info_ptr);

  // 9-patch uses int32_t to index images, so we cap the image dimensions to
  // something
  // that can always be represented by 9-patch.
  if (width > std::numeric_limits<int32_t>::max() || height > std::numeric_limits<int32_t>::max()) {
    source_diag.Error(DiagMessage()
                      << "PNG image dimensions are too large: " << width << "x" << height);
    return {};
  }

  std::unique_ptr<Image> output_image = util::make_unique<Image>();
  output_image->width = static_cast<int32_t>(width);
  output_image->height = static_cast<int32_t>(height);

  const size_t row_bytes = png_get_rowbytes(read_ptr, info_ptr);
  CHECK(row_bytes == 4 * width);  // RGBA

  // Allocate one large block to hold the image.
  output_image->data = std::unique_ptr<uint8_t[]>(new uint8_t[height * row_bytes]);

  // Create an array of rows that index into the data block.
  output_image->rows = std::unique_ptr<uint8_t* []>(new uint8_t*[height]);
  for (uint32_t h = 0; h < height; h++) {
    output_image->rows[h] = output_image->data.get() + (h * row_bytes);
  }

  // Actually read the image pixels.
  png_read_image(read_ptr, output_image->rows.get());

  // Finish reading. This will read any other chunks after the image data.
  png_read_end(read_ptr, info_ptr);

  return output_image;
}

// Experimentally chosen constant to be added to the overhead of using color type
// PNG_COLOR_TYPE_PALETTE to account for the uncompressability of the palette chunk.
// Without this, many small PNGs encoded with palettes are larger after compression than
// the same PNGs encoded as RGBA.
constexpr static const size_t kPaletteOverheadConstant = 1024u * 10u;

// Pick a color type by which to encode the image, based on which color type will take
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
static int PickColorType(int32_t width, int32_t height, bool grayscale,
                         bool convertible_to_grayscale, bool has_nine_patch,
                         size_t color_palette_size, size_t alpha_palette_size) {
  const size_t palette_chunk_size = 16 + color_palette_size * 3;
  const size_t alpha_chunk_size = 16 + alpha_palette_size;
  const size_t color_alpha_data_chunk_size = 16 + 4 * width * height;
  const size_t color_data_chunk_size = 16 + 3 * width * height;
  const size_t grayscale_alpha_data_chunk_size = 16 + 2 * width * height;
  const size_t palette_data_chunk_size = 16 + width * height;

  if (grayscale) {
    if (alpha_palette_size == 0) {
      // This is the smallest the data can be.
      return PNG_COLOR_TYPE_GRAY;
    } else if (color_palette_size <= 256 && !has_nine_patch) {
      // This grayscale has alpha and can fit within a palette.
      // See if it is worth fitting into a palette.
      const size_t palette_threshold = palette_chunk_size + alpha_chunk_size +
                                       palette_data_chunk_size +
                                       kPaletteOverheadConstant;
      if (grayscale_alpha_data_chunk_size > palette_threshold) {
        return PNG_COLOR_TYPE_PALETTE;
      }
    }
    return PNG_COLOR_TYPE_GRAY_ALPHA;
  }

  if (color_palette_size <= 256 && !has_nine_patch) {
    // This image can fit inside a palette. Let's see if it is worth it.
    size_t total_size_with_palette =
        palette_data_chunk_size + palette_chunk_size;
    size_t total_size_without_palette = color_data_chunk_size;
    if (alpha_palette_size > 0) {
      total_size_with_palette += alpha_palette_size;
      total_size_without_palette = color_alpha_data_chunk_size;
    }

    if (total_size_without_palette >
        total_size_with_palette + kPaletteOverheadConstant) {
      return PNG_COLOR_TYPE_PALETTE;
    }
  }

  if (convertible_to_grayscale) {
    if (alpha_palette_size == 0) {
      return PNG_COLOR_TYPE_GRAY;
    } else {
      return PNG_COLOR_TYPE_GRAY_ALPHA;
    }
  }

  if (alpha_palette_size == 0) {
    return PNG_COLOR_TYPE_RGB;
  }
  return PNG_COLOR_TYPE_RGBA;
}

// Assigns indices to the color and alpha palettes, encodes them, and then invokes
// png_set_PLTE/png_set_tRNS.
// This must be done before writing image data.
// Image data must be transformed to use the indices assigned within the palette.
static void WritePalette(png_structp write_ptr, png_infop write_info_ptr,
                         std::unordered_map<uint32_t, int>* color_palette,
                         std::unordered_set<uint32_t>* alpha_palette) {
  CHECK(color_palette->size() <= 256);
  CHECK(alpha_palette->size() <= 256);

  // Populate the PNG palette struct and assign indices to the color palette.

  // Colors in the alpha palette should have smaller indices.
  // This will ensure that we can truncate the alpha palette if it is
  // smaller than the color palette.
  int index = 0;
  for (uint32_t color : *alpha_palette) {
    (*color_palette)[color] = index++;
  }

  // Assign the rest of the entries.
  for (auto& entry : *color_palette) {
    if (entry.second == -1) {
      entry.second = index++;
    }
  }

  // Create the PNG color palette struct.
  auto color_palette_bytes = std::unique_ptr<png_color[]>(new png_color[color_palette->size()]);

  std::unique_ptr<png_byte[]> alpha_palette_bytes;
  if (!alpha_palette->empty()) {
    alpha_palette_bytes = std::unique_ptr<png_byte[]>(new png_byte[alpha_palette->size()]);
  }

  for (const auto& entry : *color_palette) {
    const uint32_t color = entry.first;
    const int index = entry.second;
    CHECK(index >= 0);
    CHECK(static_cast<size_t>(index) < color_palette->size());

    png_colorp slot = color_palette_bytes.get() + index;
    slot->red = color >> 24;
    slot->green = color >> 16;
    slot->blue = color >> 8;

    const png_byte alpha = color & 0x000000ff;
    if (alpha != 0xff && alpha_palette_bytes) {
      CHECK(static_cast<size_t>(index) < alpha_palette->size());
      alpha_palette_bytes[index] = alpha;
    }
  }

  // The bytes get copied here, so it is safe to release color_palette_bytes at
  // the end of function
  // scope.
  png_set_PLTE(write_ptr, write_info_ptr, color_palette_bytes.get(), color_palette->size());

  if (alpha_palette_bytes) {
    png_set_tRNS(write_ptr, write_info_ptr, alpha_palette_bytes.get(), alpha_palette->size(),
                 nullptr);
  }
}

// Write the 9-patch custom PNG chunks to write_info_ptr. This must be done
// before writing image data.
static void WriteNinePatch(png_structp write_ptr, png_infop write_info_ptr,
                           const NinePatch* nine_patch) {
  // The order of the chunks is important.
  // 9-patch code in older platforms expects the 9-patch chunk to be last.

  png_unknown_chunk unknown_chunks[3];
  memset(unknown_chunks, 0, sizeof(unknown_chunks));

  size_t index = 0;
  size_t chunk_len = 0;

  std::unique_ptr<uint8_t[]> serialized_outline =
      nine_patch->SerializeRoundedRectOutline(&chunk_len);
  strcpy((char*)unknown_chunks[index].name, "npOl");
  unknown_chunks[index].size = chunk_len;
  unknown_chunks[index].data = (png_bytep)serialized_outline.get();
  unknown_chunks[index].location = PNG_HAVE_PLTE;
  index++;

  std::unique_ptr<uint8_t[]> serialized_layout_bounds;
  if (nine_patch->layout_bounds.nonZero()) {
    serialized_layout_bounds = nine_patch->SerializeLayoutBounds(&chunk_len);
    strcpy((char*)unknown_chunks[index].name, "npLb");
    unknown_chunks[index].size = chunk_len;
    unknown_chunks[index].data = (png_bytep)serialized_layout_bounds.get();
    unknown_chunks[index].location = PNG_HAVE_PLTE;
    index++;
  }

  std::unique_ptr<uint8_t[]> serialized_nine_patch = nine_patch->SerializeBase(&chunk_len);
  strcpy((char*)unknown_chunks[index].name, "npTc");
  unknown_chunks[index].size = chunk_len;
  unknown_chunks[index].data = (png_bytep)serialized_nine_patch.get();
  unknown_chunks[index].location = PNG_HAVE_PLTE;
  index++;

  // Handle all unknown chunks. We are manually setting the chunks here,
  // so we will only ever handle our custom chunks.
  png_set_keep_unknown_chunks(write_ptr, PNG_HANDLE_CHUNK_ALWAYS, nullptr, 0);

  // Set the actual chunks here. The data gets copied, so our buffers can
  // safely go out of scope.
  png_set_unknown_chunks(write_ptr, write_info_ptr, unknown_chunks, index);
}

bool WritePng(IAaptContext* context, const Image* image,
              const NinePatch* nine_patch, io::OutputStream* out,
              const PngOptions& options) {
  TRACE_CALL();
  // Create and initialize the write png_struct with the default error and
  // warning handlers.
  // The header version is also passed in to ensure that this was built against the same
  // version of libpng.
  png_structp write_ptr = png_create_write_struct(PNG_LIBPNG_VER_STRING, nullptr, nullptr, nullptr);
  if (write_ptr == nullptr) {
    context->GetDiagnostics()->Error(DiagMessage() << "failed to create libpng write png_struct");
    return false;
  }

  // Allocate memory to store image header data.
  png_infop write_info_ptr = png_create_info_struct(write_ptr);
  if (write_info_ptr == nullptr) {
    context->GetDiagnostics()->Error(DiagMessage() << "failed to create libpng write png_info");
    png_destroy_write_struct(&write_ptr, nullptr);
    return false;
  }

  // Automatically release PNG resources at end of scope.
  PngWriteStructDeleter png_write_deleter(write_ptr, write_info_ptr);

  // libpng uses longjmp to jump to error handling routines.
  // setjmp will return true only if it was jumped to, aka, there was an error.
  if (setjmp(png_jmpbuf(write_ptr))) {
    return false;
  }

  // Handle warnings with our IDiagnostics.
  png_set_error_fn(write_ptr, (png_voidp)context->GetDiagnostics(), LogError, LogWarning);

  // Set up the write functions which write to our custom data sources.
  png_set_write_fn(write_ptr, (png_voidp)out, WriteDataToStream, nullptr);

  // We want small files and can take the performance hit to achieve this goal.
  png_set_compression_level(write_ptr, Z_BEST_COMPRESSION);

  // Begin analysis of the image data.
  // Scan the entire image and determine if:
  // 1. Every pixel has R == G == B (grayscale)
  // 2. Every pixel has A == 255 (opaque)
  // 3. There are no more than 256 distinct RGBA colors (palette).
  std::unordered_map<uint32_t, int> color_palette;
  std::unordered_set<uint32_t> alpha_palette;
  bool needs_to_zero_rgb_channels_of_transparent_pixels = false;
  bool grayscale = true;
  int max_gray_deviation = 0;

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
        needs_to_zero_rgb_channels_of_transparent_pixels =
            needs_to_zero_rgb_channels_of_transparent_pixels ||
            (red != 0 || green != 0 || blue != 0);
        red = green = blue = 0;
      }

      // Insert the color into the color palette.
      const uint32_t color = red << 24 | green << 16 | blue << 8 | alpha;
      color_palette[color] = -1;

      // If the pixel has non-opaque alpha, insert it into the
      // alpha palette.
      if (alpha != 0xff) {
        alpha_palette.insert(color);
      }

      // Check if the image is indeed grayscale.
      if (grayscale) {
        if (red != green || red != blue) {
          grayscale = false;
        }
      }

      // Calculate the gray scale deviation so that it can be compared
      // with the threshold.
      max_gray_deviation = std::max(std::abs(red - green), max_gray_deviation);
      max_gray_deviation = std::max(std::abs(green - blue), max_gray_deviation);
      max_gray_deviation = std::max(std::abs(blue - red), max_gray_deviation);
    }
  }

  if (context->IsVerbose()) {
    DiagMessage msg;
    msg << " paletteSize=" << color_palette.size()
        << " alphaPaletteSize=" << alpha_palette.size()
        << " maxGrayDeviation=" << max_gray_deviation
        << " grayScale=" << (grayscale ? "true" : "false");
    context->GetDiagnostics()->Note(msg);
  }

  const bool convertible_to_grayscale = max_gray_deviation <= options.grayscale_tolerance;

  const int new_color_type = PickColorType(
      image->width, image->height, grayscale, convertible_to_grayscale,
      nine_patch != nullptr, color_palette.size(), alpha_palette.size());

  if (context->IsVerbose()) {
    DiagMessage msg;
    msg << "encoding PNG ";
    if (nine_patch) {
      msg << "(with 9-patch) as ";
    }
    switch (new_color_type) {
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
        msg << "unknown type " << new_color_type;
        break;
    }
    context->GetDiagnostics()->Note(msg);
  }

  png_set_IHDR(write_ptr, write_info_ptr, image->width, image->height, 8,
               new_color_type, PNG_INTERLACE_NONE, PNG_COMPRESSION_TYPE_DEFAULT,
               PNG_FILTER_TYPE_DEFAULT);

  if (new_color_type & PNG_COLOR_MASK_PALETTE) {
    // Assigns indices to the palette, and writes the encoded palette to the
    // libpng writePtr.
    WritePalette(write_ptr, write_info_ptr, &color_palette, &alpha_palette);
    png_set_filter(write_ptr, 0, PNG_NO_FILTERS);
  } else {
    png_set_filter(write_ptr, 0, PNG_ALL_FILTERS);
  }

  if (nine_patch) {
    WriteNinePatch(write_ptr, write_info_ptr, nine_patch);
  }

  // Flush our updates to the header.
  png_write_info(write_ptr, write_info_ptr);

  // Write out each row of image data according to its encoding.
  if (new_color_type == PNG_COLOR_TYPE_PALETTE) {
    // 1 byte/pixel.
    auto out_row = std::unique_ptr<png_byte[]>(new png_byte[image->width]);

    for (int32_t y = 0; y < image->height; y++) {
      png_const_bytep in_row = image->rows[y];
      for (int32_t x = 0; x < image->width; x++) {
        int rr = *in_row++;
        int gg = *in_row++;
        int bb = *in_row++;
        int aa = *in_row++;
        if (aa == 0) {
          // Zero out color channels when transparent.
          rr = gg = bb = 0;
        }

        const uint32_t color = rr << 24 | gg << 16 | bb << 8 | aa;
        const int idx = color_palette[color];
        CHECK(idx != -1);
        out_row[x] = static_cast<png_byte>(idx);
      }
      png_write_row(write_ptr, out_row.get());
    }
  } else if (new_color_type == PNG_COLOR_TYPE_GRAY ||
             new_color_type == PNG_COLOR_TYPE_GRAY_ALPHA) {
    const size_t bpp = new_color_type == PNG_COLOR_TYPE_GRAY ? 1 : 2;
    auto out_row =
        std::unique_ptr<png_byte[]>(new png_byte[image->width * bpp]);

    for (int32_t y = 0; y < image->height; y++) {
      png_const_bytep in_row = image->rows[y];
      for (int32_t x = 0; x < image->width; x++) {
        int rr = in_row[x * 4];
        int gg = in_row[x * 4 + 1];
        int bb = in_row[x * 4 + 2];
        int aa = in_row[x * 4 + 3];
        if (aa == 0) {
          // Zero out the gray channel when transparent.
          rr = gg = bb = 0;
        }

        if (grayscale) {
          // The image was already grayscale, red == green == blue.
          out_row[x * bpp] = in_row[x * 4];
        } else {
          // The image is convertible to grayscale, use linear-luminance of
          // sRGB colorspace:
          // https://en.wikipedia.org/wiki/Grayscale#Colorimetric_.28luminance-preserving.29_conversion_to_grayscale
          out_row[x * bpp] =
              (png_byte)(rr * 0.2126f + gg * 0.7152f + bb * 0.0722f);
        }

        if (bpp == 2) {
          // Write out alpha if we have it.
          out_row[x * bpp + 1] = aa;
        }
      }
      png_write_row(write_ptr, out_row.get());
    }
  } else if (new_color_type == PNG_COLOR_TYPE_RGB || new_color_type == PNG_COLOR_TYPE_RGBA) {
    const size_t bpp = new_color_type == PNG_COLOR_TYPE_RGB ? 3 : 4;
    if (needs_to_zero_rgb_channels_of_transparent_pixels) {
      // The source RGBA data can't be used as-is, because we need to zero out
      // the RGB values of transparent pixels.
      auto out_row = std::unique_ptr<png_byte[]>(new png_byte[image->width * bpp]);

      for (int32_t y = 0; y < image->height; y++) {
        png_const_bytep in_row = image->rows[y];
        for (int32_t x = 0; x < image->width; x++) {
          int rr = *in_row++;
          int gg = *in_row++;
          int bb = *in_row++;
          int aa = *in_row++;
          if (aa == 0) {
            // Zero out the RGB channels when transparent.
            rr = gg = bb = 0;
          }
          out_row[x * bpp] = rr;
          out_row[x * bpp + 1] = gg;
          out_row[x * bpp + 2] = bb;
          if (bpp == 4) {
            out_row[x * bpp + 3] = aa;
          }
        }
        png_write_row(write_ptr, out_row.get());
      }
    } else {
      // The source image can be used as-is, just tell libpng whether or not to
      // ignore the alpha channel.
      if (new_color_type == PNG_COLOR_TYPE_RGB) {
        // Delete the extraneous alpha values that we appended to our buffer
        // when reading the original values.
        png_set_filler(write_ptr, 0, PNG_FILLER_AFTER);
      }
      png_write_image(write_ptr, image->rows.get());
    }
  } else {
    LOG(FATAL) << "unreachable";
  }

  png_write_end(write_ptr, write_info_ptr);
  return true;
}

}  // namespace aapt
