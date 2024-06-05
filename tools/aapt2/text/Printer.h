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

#ifndef AAPT_TEXT_PRINTER_H
#define AAPT_TEXT_PRINTER_H

#include "android-base/macros.h"
#include "androidfw/Streams.h"
#include "androidfw/StringPiece.h"

namespace aapt {
namespace text {

// An indenting Printer that helps write formatted text to the OutputStream.
class Printer {
 public:
  explicit Printer(android::OutputStream* out) : out_(out) {
  }

  Printer& Print(android::StringPiece str);
  Printer& Println(android::StringPiece str);
  Printer& Println();

  void Indent();
  void Undent();

 private:
  DISALLOW_COPY_AND_ASSIGN(Printer);

  android::OutputStream* out_;
  int indent_level_ = 0;
  bool needs_indent_ = false;
  bool error_ = false;
};

}  // namespace text
}  // namespace aapt

#endif  // AAPT_TEXT_PRINTER_H
