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

#ifndef AAPT_PNG_H
#define AAPT_PNG_H

#include <iostream>
#include <string>

#include "android-base/macros.h"

#include "Diagnostics.h"
#include "Source.h"
#include "compile/Image.h"
#include "io/Io.h"
#include "process/IResourceTableConsumer.h"
#include "util/BigBuffer.h"

namespace aapt {

struct PngOptions {
  int grayscale_tolerance = 0;
};

/**
 * Deprecated. Removing once new PNG crunching code is proved to be correct.
 */
class Png {
 public:
  explicit Png(IDiagnostics* diag) : mDiag(diag) {}

  bool process(const Source& source, std::istream* input, BigBuffer* outBuffer,
               const PngOptions& options);

 private:
  IDiagnostics* mDiag;

  DISALLOW_COPY_AND_ASSIGN(Png);
};

/**
 * An InputStream that filters out unimportant PNG chunks.
 */
class PngChunkFilter : public io::InputStream {
 public:
  explicit PngChunkFilter(const StringPiece& data);

  bool Next(const void** buffer, int* len) override;
  void BackUp(int count) override;
  bool Skip(int count) override;

  google::protobuf::int64 ByteCount() const override {
    return static_cast<google::protobuf::int64>(window_start_);
  }

  bool HadError() const override { return error_; }

 private:
  bool ConsumeWindow(const void** buffer, int* len);

  StringPiece data_;
  size_t window_start_ = 0;
  size_t window_end_ = 0;
  bool error_ = false;

  DISALLOW_COPY_AND_ASSIGN(PngChunkFilter);
};

/**
 * Reads a PNG from the InputStream into memory as an RGBA Image.
 */
std::unique_ptr<Image> ReadPng(IAaptContext* context, io::InputStream* in);

/**
 * Writes the RGBA Image, with optional 9-patch meta-data, into the OutputStream
 * as a PNG.
 */
bool WritePng(IAaptContext* context, const Image* image,
              const NinePatch* nine_patch, io::OutputStream* out,
              const PngOptions& options);

}  // namespace aapt

#endif  // AAPT_PNG_H
