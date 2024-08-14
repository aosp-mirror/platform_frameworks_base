/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "link/FlagDisabledResourceRemover.h"

#include <algorithm>

#include "ResourceTable.h"

using android::ConfigDescription;

namespace aapt {

static bool KeepResourceEntry(const std::unique_ptr<ResourceEntry>& entry) {
  if (entry->values.empty()) {
    return true;
  }
  const auto end_iter = entry->values.end();
  const auto remove_iter =
      std::stable_partition(entry->values.begin(), end_iter,
                            [](const std::unique_ptr<ResourceConfigValue>& value) -> bool {
                              return value->value->GetFlagStatus() != FlagStatus::Disabled;
                            });

  bool keep = remove_iter != entry->values.begin();

  entry->values.erase(remove_iter, end_iter);

  for (auto& value : entry->values) {
    value->value->RemoveFlagDisabledElements();
  }

  return keep;
}

bool FlagDisabledResourceRemover::Consume(IAaptContext* context, ResourceTable* table) {
  for (auto& pkg : table->packages) {
    for (auto& type : pkg->types) {
      const auto end_iter = type->entries.end();
      const auto remove_iter = std::stable_partition(
          type->entries.begin(), end_iter, [](const std::unique_ptr<ResourceEntry>& entry) -> bool {
            return KeepResourceEntry(entry);
          });

      type->entries.erase(remove_iter, end_iter);
    }
  }
  return true;
}

}  // namespace aapt