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

#ifndef AAPT_IO_IO_H
#define AAPT_IO_IO_H

#include <string>

#include "google/protobuf/io/zero_copy_stream_impl_lite.h"

namespace aapt {
namespace io {

/**
 * InputStream interface that inherits from protobuf's ZeroCopyInputStream,
 * but adds error handling methods to better report issues.
 *
 * The code style here matches the protobuf style.
 */
class InputStream : public ::google::protobuf::io::ZeroCopyInputStream {
 public:
  virtual std::string GetError() const { return {}; }

  virtual bool HadError() const = 0;
};

/**
 * OutputStream interface that inherits from protobuf's ZeroCopyOutputStream,
 * but adds error handling methods to better report issues.
 *
 * The code style here matches the protobuf style.
 */
class OutputStream : public ::google::protobuf::io::ZeroCopyOutputStream {
 public:
  virtual std::string GetError() const { return {}; }

  virtual bool HadError() const = 0;
};

/**
 * Copies the data from in to out. Returns true if there was no error.
 * If there was an error, check the individual streams' HadError/GetError
 * methods.
 */
bool Copy(OutputStream* out, InputStream* in);

}  // namespace io
}  // namespace aapt

#endif /* AAPT_IO_IO_H */
