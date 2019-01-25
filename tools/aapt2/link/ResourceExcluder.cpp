/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "link/ResourceExcluder.h"

#include <algorithm>

#include "DominatorTree.h"
#include "ResourceTable.h"

using android::ConfigDescription;

namespace aapt {

namespace {

void RemoveIfExcluded(std::set<std::pair<ConfigDescription, int>>& excluded_configs_,
                      IAaptContext* context,
                      ResourceEntry* entry,
                      ResourceConfigValue* value) {
  const ConfigDescription& config = value->config;

  // If this entry is a default, ignore
  if (config == ConfigDescription::DefaultConfig()) {
    return;
  }

  for (auto& excluded_pair : excluded_configs_) {

    const ConfigDescription& excluded_config = excluded_pair.first;
    const int& excluded_diff = excluded_pair.second;

    // Check whether config contains all flags in excluded config
    int node_diff = config.diff(excluded_config);
    int masked_diff = excluded_diff & node_diff;

    if (masked_diff == 0) {
      if (context->IsVerbose()) {
        context->GetDiagnostics()->Note(
            DiagMessage(value->value->GetSource())
                << "excluded resource \""
                << entry->name
                << "\" with config "
                << config.toString());
      }
      value->value = {};
      return;
    }
  }
}

}  // namespace

bool ResourceExcluder::Consume(IAaptContext* context, ResourceTable* table) {
  for (auto& package : table->packages) {
    for (auto& type : package->types) {
      for (auto& entry : type->entries) {
        for (auto& value : entry->values) {
          RemoveIfExcluded(excluded_configs_, context, entry.get(), value.get());
        }

        // Erase the values that were removed.
        entry->values.erase(
            std::remove_if(
                entry->values.begin(), entry->values.end(),
                [](const std::unique_ptr<ResourceConfigValue>& val) -> bool {
                  return val == nullptr || val->value == nullptr;
                }),
            entry->values.end());
      }
    }
  }
  return true;
}

}  // namespace aapt
