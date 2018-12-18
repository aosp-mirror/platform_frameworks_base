/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef LAYOUT_VALIDATION_H_
#define LAYOUT_VALIDATION_H_

#include "dex_builder.h"

#include <string>

namespace startop {

// This visitor determines whether a layout can be compiled. Since we do not currently support all
// features, such as includes and merges, we need to pre-validate the layout before we start
// compiling.
class LayoutValidationVisitor {
 public:
  void VisitStartDocument() const {}
  void VisitEndDocument() const {}
  void VisitStartTag(const std::u16string& name);
  void VisitEndTag() const {}

  const std::string& message() const { return message_; }
  bool can_compile() const { return can_compile_; }

 private:
  std::string message_{"Okay"};
  bool can_compile_{true};
};

}  // namespace startop

#endif  // LAYOUT_VALIDATION_H_
