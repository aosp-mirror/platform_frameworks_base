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

#include "Diagnostics.h"
#include "Source.h"
#include "compile/Image.h"
#include "io/Io.h"
#include "process/IResourceTableConsumer.h"
#include "util/BigBuffer.h"

#include <android-base/macros.h>
#include <iostream>
#include <string>

namespace aapt {

struct PngOptions {
  int grayScaleTolerance = 0;
};

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

  int64_t ByteCount() const override {
    return static_cast<int64_t>(mWindowStart);
  }

  bool HadError() const override { return mError; }

 private:
  bool consumeWindow(const void** buffer, int* len);

  StringPiece mData;
  size_t mWindowStart = 0;
  size_t mWindowEnd = 0;
  bool mError = false;

  DISALLOW_COPY_AND_ASSIGN(PngChunkFilter);
};

/**
 * Reads a PNG from the InputStream into memory as an RGBA Image.
 */
std::unique_ptr<Image> readPng(IAaptContext* context, io::InputStream* in);

/**
 * Writes the RGBA Image, with optional 9-patch meta-data, into the OutputStream
 * as a PNG.
 */
bool writePng(IAaptContext* context, const Image* image,
              const NinePatch* ninePatch, io::OutputStream* out,
              const PngOptions& options);

}  // namespace aapt

#endif  // AAPT_PNG_H
