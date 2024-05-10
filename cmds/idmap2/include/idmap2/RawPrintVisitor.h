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

#ifndef IDMAP2_INCLUDE_IDMAP2_RAWPRINTVISITOR_H_
#define IDMAP2_INCLUDE_IDMAP2_RAWPRINTVISITOR_H_

#include <ostream>
#include <memory>
#include <string>

#include "androidfw/AssetManager2.h"
#include "idmap2/Idmap.h"

namespace android {

class ApkAssets;

namespace idmap2 {

class RawPrintVisitor : public Visitor {
 public:
  explicit RawPrintVisitor(std::ostream& stream) : stream_(stream), offset_(0) {
  }
  ~RawPrintVisitor() override = default;
  void visit(const Idmap& idmap) override;
  void visit(const IdmapHeader& header) override;
  void visit(const IdmapData& data) override;
  void visit(const IdmapData::Header& header) override;

 private:
  void print(uint8_t value, const char* fmt, ...);
  void print(uint16_t value, const char* fmt, ...);
  void print(uint32_t value, const char* fmt, ...);
  void print(const std::string& value, bool print_value, const char* fmt, ...);
  void align();
  void pad(size_t padding);

  std::ostream& stream_;
  size_t offset_;
  std::unique_ptr<TargetResourceContainer> target_;
  std::unique_ptr<OverlayResourceContainer> overlay_;
};

}  // namespace idmap2
}  // namespace android

#endif  // IDMAP2_INCLUDE_IDMAP2_RAWPRINTVISITOR_H_
