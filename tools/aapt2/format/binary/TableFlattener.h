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

#ifndef AAPT_FORMAT_BINARY_TABLEFLATTENER_H
#define AAPT_FORMAT_BINARY_TABLEFLATTENER_H

#include "android-base/macros.h"

#include "Resource.h"
#include "ResourceTable.h"
#include "process/IResourceTableConsumer.h"
#include "util/BigBuffer.h"

namespace aapt {

// The percentage of used entries for a type for which using a sparse encoding is
// preferred.
constexpr const size_t kSparseEncodingThreshold = 60;

struct TableFlattenerOptions {
  // When true, types for configurations with a sparse set of entries are encoded
  // as a sparse map of entry ID and offset to actual data.
  // This is only available on platforms O+ and will only be respected when
  // minSdk is O+.
  bool use_sparse_entries = false;

  // When true, the key string pool in the final ResTable
  // is collapsed to a single entry. All resource entries
  // have name indices that point to this single value
  bool collapse_key_stringpool = false;

  // Set of resources to avoid collapsing to a single entry in key stringpool.
  std::set<ResourceName> name_collapse_exemptions;

  // Map from original resource paths to shortened resource paths.
  std::map<std::string, std::string> shortened_path_map;
};

class TableFlattener : public IResourceTableConsumer {
 public:
  explicit TableFlattener(const TableFlattenerOptions& options, BigBuffer* buffer)
      : options_(options), buffer_(buffer) {
  }

  bool Consume(IAaptContext* context, ResourceTable* table) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(TableFlattener);

  TableFlattenerOptions options_;
  BigBuffer* buffer_;
};

}  // namespace aapt

#endif /* AAPT_FORMAT_BINARY_TABLEFLATTENER_H */
