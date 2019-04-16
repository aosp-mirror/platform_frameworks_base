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

#ifndef IDMAP2_INCLUDE_IDMAP2_PRETTYPRINTVISITOR_H_
#define IDMAP2_INCLUDE_IDMAP2_PRETTYPRINTVISITOR_H_

#include <iostream>
#include <memory>

#include "androidfw/AssetManager2.h"
#include "idmap2/Idmap.h"

namespace android {

class ApkAssets;

namespace idmap2 {

class PrettyPrintVisitor : public Visitor {
 public:
  explicit PrettyPrintVisitor(std::ostream& stream) : stream_(stream) {
  }
  virtual void visit(const Idmap& idmap);
  virtual void visit(const IdmapHeader& header);
  virtual void visit(const IdmapData& data);
  virtual void visit(const IdmapData::Header& header);
  virtual void visit(const IdmapData::TypeEntry& type_entry);

 private:
  std::ostream& stream_;
  std::unique_ptr<const ApkAssets> target_apk_;
  AssetManager2 target_am_;
  PackageId last_seen_package_id_;
};

}  // namespace idmap2
}  // namespace android

#endif  // IDMAP2_INCLUDE_IDMAP2_PRETTYPRINTVISITOR_H_
