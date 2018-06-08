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

#include "link/Linkers.h"

#include <algorithm>
#include <iterator>

#include "android-base/logging.h"

#include "ResourceTable.h"

namespace aapt {

template <typename InputContainer, typename OutputIterator, typename Predicate>
OutputIterator move_if(InputContainer& input_container, OutputIterator result, Predicate pred) {
  const auto last = input_container.end();
  auto new_end = std::find_if(input_container.begin(), input_container.end(), pred);
  if (new_end == last) {
    return result;
  }

  *result = std::move(*new_end);

  auto first = new_end;
  ++first;

  for (; first != last; ++first) {
    if (bool(pred(*first))) {
      // We want to move this guy
      *result = std::move(*first);
      ++result;
    } else {
      // We want to keep this guy, but we will need to move it up the list to
      // replace missing items.
      *new_end = std::move(*first);
      ++new_end;
    }
  }

  input_container.erase(new_end, last);
  return result;
}

bool PrivateAttributeMover::Consume(IAaptContext* context, ResourceTable* table) {
  for (auto& package : table->packages) {
    ResourceTableType* type = package->FindType(ResourceType::kAttr);
    if (!type) {
      continue;
    }

    if (type->visibility_level != Visibility::Level::kPublic) {
      // No public attributes, so we can safely leave these private attributes
      // where they are.
      continue;
    }

    std::vector<std::unique_ptr<ResourceEntry>> private_attr_entries;

    move_if(type->entries, std::back_inserter(private_attr_entries),
            [](const std::unique_ptr<ResourceEntry>& entry) -> bool {
              return entry->visibility.level != Visibility::Level::kPublic;
            });

    if (private_attr_entries.empty()) {
      // No private attributes.
      continue;
    }

    ResourceTableType* priv_attr_type = package->FindOrCreateType(ResourceType::kAttrPrivate);
    CHECK(priv_attr_type->entries.empty());
    priv_attr_type->entries = std::move(private_attr_entries);
  }
  return true;
}

}  // namespace aapt
