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

#include "optimize/ResourceFilter.h"

#include "ResourceTable.h"

namespace aapt {

ResourceFilter::ResourceFilter(const std::unordered_set<ResourceName>& blacklist)
    : blacklist_(blacklist) {
}

bool ResourceFilter::Consume(IAaptContext* context, ResourceTable* table) {
  for (auto& package : table->packages) {
    for (auto& type : package->types) {
      for (auto it = type->entries.begin(); it != type->entries.end(); ) {
        ResourceName resource = ResourceName({}, type->type, (*it)->name);
        if (blacklist_.find(resource) != blacklist_.end()) {
          it = type->entries.erase(it);
        } else {
          ++it;
        }
      }
    }
  }
  return true;
}

}  // namespace aapt
