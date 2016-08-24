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

#include "ResourceTable.h"
#include "link/Linkers.h"

#include <algorithm>
#include <iterator>

namespace aapt {

template <typename InputContainer, typename OutputIterator, typename Predicate>
OutputIterator moveIf(InputContainer& inputContainer, OutputIterator result,
                      Predicate pred) {
    const auto last = inputContainer.end();
    auto newEnd = std::find_if(inputContainer.begin(), inputContainer.end(), pred);
    if (newEnd == last) {
        return result;
    }

    *result = std::move(*newEnd);

    auto first = newEnd;
    ++first;

    for (; first != last; ++first) {
        if (bool(pred(*first))) {
            // We want to move this guy
            *result = std::move(*first);
            ++result;
        } else {
            // We want to keep this guy, but we will need to move it up the list to replace
            // missing items.
            *newEnd = std::move(*first);
            ++newEnd;
        }
    }

    inputContainer.erase(newEnd, last);
    return result;
}

bool PrivateAttributeMover::consume(IAaptContext* context, ResourceTable* table) {
    for (auto& package : table->packages) {
        ResourceTableType* type = package->findType(ResourceType::kAttr);
        if (!type) {
            continue;
        }

        if (type->symbolStatus.state != SymbolState::kPublic) {
            // No public attributes, so we can safely leave these private attributes where they are.
            return true;
        }

        ResourceTableType* privAttrType = package->findOrCreateType(ResourceType::kAttrPrivate);
        assert(privAttrType->entries.empty());

        moveIf(type->entries, std::back_inserter(privAttrType->entries),
               [](const std::unique_ptr<ResourceEntry>& entry) -> bool {
                   return entry->symbolStatus.state != SymbolState::kPublic;
               });
        break;
    }
    return true;
}

} // namespace aapt
