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

#include "compile/IdAssigner.h"
#include "process/IResourceTableConsumer.h"
#include "util/Util.h"

#include <bitset>
#include <cassert>
#include <set>

namespace aapt {

bool IdAssigner::consume(IAaptContext* context, ResourceTable* table) {
    std::bitset<256> usedTypeIds;
    std::set<uint16_t> usedEntryIds;

    for (auto& package : table->packages) {
        assert(package->id && "packages must have manually assigned IDs");

        usedTypeIds.reset();

        // Type ID 0 is invalid, reserve it.
        usedTypeIds.set(0);

        // Collect used type IDs.
        for (auto& type : package->types) {
            if (type->id) {
                usedEntryIds.clear();

                if (usedTypeIds[type->id.value()]) {
                    // This ID is already taken!
                    context->getDiagnostics()->error(DiagMessage()
                                                     << "type '" << type->type << "' in "
                                                     << "package '" << package->name << "' has "
                                                     << "duplicate ID "
                                                     << std::hex << (int) type->id.value()
                                                     << std::dec);
                    return false;
                }

                // Mark the type ID as taken.
                usedTypeIds.set(type->id.value());
            }

            // Collect used entry IDs.
            for (auto& entry : type->entries) {
                if (entry->id) {
                    // Mark entry ID as taken.
                    if (!usedEntryIds.insert(entry->id.value()).second) {
                        // This ID existed before!
                        ResourceNameRef nameRef(package->name, type->type, entry->name);
                        context->getDiagnostics()->error(DiagMessage()
                                                         << "resource '" << nameRef << "' "
                                                         << "has duplicate entry ID "
                                                         << std::hex << (int) entry->id.value()
                                                         << std::dec);
                        return false;
                    }
                }
            }

            // Assign unused entry IDs.
            const auto endUsedEntryIter = usedEntryIds.end();
            auto nextUsedEntryIter = usedEntryIds.begin();
            uint16_t nextId = 0;
            for (auto& entry : type->entries) {
                if (!entry->id) {
                    // Assign the next available entryID.
                    while (nextUsedEntryIter != endUsedEntryIter &&
                            nextId == *nextUsedEntryIter) {
                        nextId++;
                        ++nextUsedEntryIter;
                    }
                    entry->id = nextId++;
                }
            }
        }

        // Assign unused type IDs.
        size_t nextTypeId = 0;
        for (auto& type : package->types) {
            if (!type->id) {
                while (nextTypeId < usedTypeIds.size() && usedTypeIds[nextTypeId]) {
                    nextTypeId++;
                }
                type->id = static_cast<uint8_t>(nextTypeId);
                nextTypeId++;
            }
        }
    }
    return true;
}

} // namespace aapt
