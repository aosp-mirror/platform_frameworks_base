/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef AAPT_IO_BIGBUFFEROUTPUTSTREAM_H
#define AAPT_IO_BIGBUFFEROUTPUTSTREAM_H

#include "io/Io.h"
#include "util/BigBuffer.h"

namespace aapt {
namespace io {

class BigBufferOutputStream : public OutputStream {
 public:
  inline explicit BigBufferOutputStream(BigBuffer* buffer) : buffer_(buffer) {}
  virtual ~BigBufferOutputStream() = default;

  bool Next(void** data, size_t* size) override;

  void BackUp(size_t count) override;

  size_t ByteCount() const override;

  bool HadError() const override;

 private:
  DISALLOW_COPY_AND_ASSIGN(BigBufferOutputStream);

  BigBuffer* buffer_;
};

}  // namespace io
}  // namespace aapt

#endif  // AAPT_IO_BIGBUFFEROUTPUTSTREAM_H
