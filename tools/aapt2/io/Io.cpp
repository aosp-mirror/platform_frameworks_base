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

#include "io/Io.h"

#include <cstring>

namespace aapt {
namespace io {

bool Copy(OutputStream* out, InputStream* in) {
  const void* in_buffer;
  size_t in_len;
  while (in->Next(&in_buffer, &in_len)) {
    void* out_buffer;
    size_t out_len;
    if (!out->Next(&out_buffer, &out_len)) {
      return !out->HadError();
    }

    const size_t bytes_to_copy = in_len < out_len ? in_len : out_len;
    memcpy(out_buffer, in_buffer, bytes_to_copy);
    out->BackUp(out_len - bytes_to_copy);
    in->BackUp(in_len - bytes_to_copy);
  }
  return !in->HadError();
}

}  // namespace io
}  // namespace aapt
