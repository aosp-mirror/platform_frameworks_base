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

#pragma once

#include <string>

#include "BigBuffer.h"
#include "IDiagnostics.h"
#include "Image.h"
#include "Source.h"
#include "Streams.h"
#include "android-base/macros.h"

namespace android {
// Size in bytes of the PNG signature.
constexpr size_t kPngSignatureSize = 8u;

struct PngOptions {
  int grayscale_tolerance = 0;
};

/**
 * Deprecated. Removing once new PNG crunching code is proved to be correct.
 */
class Png {
 public:
  explicit Png(IDiagnostics* diag) : mDiag(diag) {
  }

  bool process(const Source& source, std::istream* input, BigBuffer* outBuffer,
               const PngOptions& options);

 private:
  DISALLOW_COPY_AND_ASSIGN(Png);

  IDiagnostics* mDiag;
};

/**
 * An InputStream that filters out unimportant PNG chunks.
 */
class PngChunkFilter : public InputStream {
 public:
  explicit PngChunkFilter(StringPiece data);
  virtual ~PngChunkFilter() = default;

  bool Next(const void** buffer, size_t* len) override;
  void BackUp(size_t count) override;

  bool CanRewind() const override {
    return true;
  }
  bool Rewind() override;
  size_t ByteCount() const override {
    return window_start_;
  }

  bool HadError() const override {
    return !error_msg_.empty();
  }
  std::string GetError() const override {
    return error_msg_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(PngChunkFilter);

  bool ConsumeWindow(const void** buffer, size_t* len);

  StringPiece data_;
  size_t window_start_ = 0;
  size_t window_end_ = 0;
  std::string error_msg_;
};
/**
 * Reads a PNG from the InputStream into memory as an RGBA Image.
 */
std::unique_ptr<Image> ReadPng(InputStream* in, IDiagnostics* diag);

/**
 * Writes the RGBA Image, with optional 9-patch meta-data, into the OutputStream
 * as a PNG.
 */
bool WritePng(const Image* image, const NinePatch* nine_patch, OutputStream* out,
              const PngOptions& options, IDiagnostics* diag, bool verbose);
}  // namespace android