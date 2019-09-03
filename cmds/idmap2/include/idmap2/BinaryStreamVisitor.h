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

#ifndef IDMAP2_INCLUDE_IDMAP2_BINARYSTREAMVISITOR_H_
#define IDMAP2_INCLUDE_IDMAP2_BINARYSTREAMVISITOR_H_

#include <cstdint>
#include <iostream>
#include <string>

#include "idmap2/Idmap.h"

namespace android::idmap2 {

class BinaryStreamVisitor : public Visitor {
 public:
  explicit BinaryStreamVisitor(std::ostream& stream) : stream_(stream) {
  }
  ~BinaryStreamVisitor() override = default;
  void visit(const Idmap& idmap) override;
  void visit(const IdmapHeader& header) override;
  void visit(const IdmapData& data) override;
  void visit(const IdmapData::Header& header) override;
  void visit(const IdmapData::TypeEntry& type_entry) override;

 private:
  void Write16(uint16_t value);
  void Write32(uint32_t value);
  void WriteString(const StringPiece& value);
  std::ostream& stream_;
};

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_BINARYSTREAMVISITOR_H_
