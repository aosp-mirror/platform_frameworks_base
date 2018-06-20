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

#ifndef AAPT_SPLIT_TABLESPLITTER_H
#define AAPT_SPLIT_TABLESPLITTER_H

#include <set>
#include <vector>
#include "android-base/macros.h"
#include "androidfw/ConfigDescription.h"

#include "ResourceTable.h"
#include "filter/ConfigFilter.h"
#include "process/IResourceTableConsumer.h"

namespace aapt {

struct SplitConstraints {
  std::set<android::ConfigDescription> configs;
  std::string name;
};

struct TableSplitterOptions {
  /**
   * The preferred densities to keep in the table, stripping out all others.
   * If empty, no stripping is done.
   */
  std::vector<uint16_t> preferred_densities;

  /**
   * Configuration filter that determines which resource configuration values
   * end up in the final table.
   */
  IConfigFilter* config_filter = nullptr;
};

class TableSplitter {
 public:
  TableSplitter(const std::vector<SplitConstraints>& splits,
                const TableSplitterOptions& options)
      : split_constraints_(splits), options_(options) {
    for (size_t i = 0; i < split_constraints_.size(); i++) {
      splits_.push_back(util::make_unique<ResourceTable>());
    }
  }

  bool VerifySplitConstraints(IAaptContext* context);

  void SplitTable(ResourceTable* original_table);

  std::vector<std::unique_ptr<ResourceTable>>& splits() { return splits_; }

 private:
  std::vector<SplitConstraints> split_constraints_;
  std::vector<std::unique_ptr<ResourceTable>> splits_;
  TableSplitterOptions options_;

  DISALLOW_COPY_AND_ASSIGN(TableSplitter);
};
}

#endif /* AAPT_SPLIT_TABLESPLITTER_H */
