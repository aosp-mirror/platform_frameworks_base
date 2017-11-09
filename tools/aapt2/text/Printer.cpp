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

#include "text/Printer.h"

#include <algorithm>

#include "io/Util.h"

using ::aapt::io::OutputStream;
using ::android::StringPiece;

namespace aapt {
namespace text {

Printer& Printer::Println(const StringPiece& str) {
  Print(str);
  return Print("\n");
}

Printer& Printer::Println() {
  return Print("\n");
}

Printer& Printer::Print(const StringPiece& str) {
  if (error_) {
    return *this;
  }

  auto remaining_str_begin = str.begin();
  const auto remaining_str_end = str.end();
  while (remaining_str_end != remaining_str_begin) {
    // Find the next new-line.
    const auto new_line_iter = std::find(remaining_str_begin, remaining_str_end, '\n');

    // We will copy the string up until the next new-line (or end of string).
    const StringPiece str_to_copy = str.substr(remaining_str_begin, new_line_iter);
    if (!str_to_copy.empty()) {
      if (needs_indent_) {
        for (int i = 0; i < indent_level_; i++) {
          if (!io::Copy(out_, "  ")) {
            error_ = true;
            return *this;
          }
        }
        needs_indent_ = false;
      }

      if (!io::Copy(out_, str_to_copy)) {
        error_ = true;
        return *this;
      }
    }

    // If we found a new-line.
    if (new_line_iter != remaining_str_end) {
      if (!io::Copy(out_, "\n")) {
        error_ = true;
        return *this;
      }
      needs_indent_ = true;
      // Ok to increment iterator here because we know that the '\n' character is one byte.
      remaining_str_begin = new_line_iter + 1;
    } else {
      remaining_str_begin = new_line_iter;
    }
  }
  return *this;
}

void Printer::Indent() {
  ++indent_level_;
}

void Printer::Undent() {
  --indent_level_;
}

}  // namespace text
}  // namespace aapt
