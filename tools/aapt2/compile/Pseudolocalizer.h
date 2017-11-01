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

#ifndef AAPT_COMPILE_PSEUDOLOCALIZE_H
#define AAPT_COMPILE_PSEUDOLOCALIZE_H

#include <memory>

#include "android-base/macros.h"
#include "androidfw/StringPiece.h"

#include "ResourceValues.h"
#include "StringPool.h"

namespace aapt {

class PseudoMethodImpl {
 public:
  virtual ~PseudoMethodImpl() {}
  virtual std::string Start() { return {}; }
  virtual std::string End() { return {}; }
  virtual std::string Text(const android::StringPiece& text) = 0;
  virtual std::string Placeholder(const android::StringPiece& text) = 0;
};

class Pseudolocalizer {
 public:
  enum class Method {
    kNone,
    kAccent,
    kBidi,
  };

  explicit Pseudolocalizer(Method method);
  void SetMethod(Method method);
  std::string Start() { return impl_->Start(); }
  std::string End() { return impl_->End(); }
  std::string Text(const android::StringPiece& text);

 private:
  std::unique_ptr<PseudoMethodImpl> impl_;
  size_t last_depth_;
};

}  // namespace aapt

#endif /* AAPT_COMPILE_PSEUDOLOCALIZE_H */
