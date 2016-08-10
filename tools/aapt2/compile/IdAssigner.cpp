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

#include <cassert>
#include <map>

namespace aapt {

/**
 * Assigns the intended ID to the ResourceTablePackage, ResourceTableType, and ResourceEntry,
 * as long as there is no existing ID or the ID is the same.
 */
static bool assignId(IDiagnostics* diag, const ResourceId& id, const ResourceName& name,
                     ResourceTablePackage* pkg, ResourceTableType* type, ResourceEntry* entry) {
    if (pkg->id.value() == id.packageId()) {
        if (!type->id || type->id.value() == id.typeId()) {
            type->id = id.typeId();

            if (!entry->id || entry->id.value() == id.entryId()) {
                entry->id = id.entryId();
                return true;
            }
        }
    }

    const ResourceId existingId(pkg->id.value(),
                                type->id ? type->id.value() : 0,
                                entry->id ? entry->id.value() : 0);
    diag->error(DiagMessage() << "can't assign ID " << id
                << " to resource " << name
                << " with conflicting ID " << existingId);
    return false;
}

bool IdAssigner::consume(IAaptContext* context, ResourceTable* table) {
    std::map<ResourceId, ResourceName> assignedIds;

    for (auto& package : table->packages) {
        assert(package->id && "packages must have manually assigned IDs");

        for (auto& type : package->types) {
            for (auto& entry : type->entries) {
                const ResourceName name(package->name, type->type, entry->name);

                if (mAssignedIdMap) {
                    // Assign the pre-assigned stable ID meant for this resource.
                    const auto iter = mAssignedIdMap->find(name);
                    if (iter != mAssignedIdMap->end()) {
                        const ResourceId assignedId = iter->second;
                        const bool result = assignId(context->getDiagnostics(), assignedId, name,
                                                     package.get(), type.get(), entry.get());
                        if (!result) {
                            return false;
                        }
                    }
                }

                if (package->id && type->id && entry->id) {
                    // If the ID is set for this resource, then reserve it.
                    ResourceId resourceId(package->id.value(), type->id.value(), entry->id.value());
                    auto result = assignedIds.insert({ resourceId, name });
                    const ResourceName& existingName = result.first->second;
                    if (!result.second) {
                        context->getDiagnostics()->error(DiagMessage() << "resource " << name
                                                         << " has same ID "
                                                         << resourceId
                                                         << " as " << existingName);
                        return false;
                    }
                }
            }
        }
    }

    if (mAssignedIdMap) {
        // Reserve all the IDs mentioned in the stable ID map. That way we won't assign
        // IDs that were listed in the map if they don't exist in the table.
        for (const auto& stableIdEntry : *mAssignedIdMap) {
            const ResourceName& preAssignedName = stableIdEntry.first;
            const ResourceId& preAssignedId = stableIdEntry.second;
            auto result = assignedIds.insert({ preAssignedId, preAssignedName });
            const ResourceName& existingName = result.first->second;
            if (!result.second && existingName != preAssignedName) {
                context->getDiagnostics()->error(DiagMessage() << "stable ID " << preAssignedId
                                                 << " for resource " << preAssignedName
                                                 << " is already taken by resource "
                                                 << existingName);
                return false;
            }
        }
    }

    // Assign any resources without IDs the next available ID. Gaps will be filled if possible,
    // unless those IDs have been reserved.

    const auto assignedIdsIterEnd = assignedIds.end();
    for (auto& package : table->packages) {
        assert(package->id && "packages must have manually assigned IDs");

        // Build a half filled ResourceId object, which will be used to find the closest matching
        // reserved ID in the assignedId map. From that point the next available type ID can be
        // found.
        ResourceId resourceId(package->id.value(), 0, 0);
        uint8_t nextExpectedTypeId = 1;

        // Find the closest matching ResourceId that is <= the one with only the package set.
        auto nextTypeIter = assignedIds.lower_bound(resourceId);
        for (auto& type : package->types) {
            if (!type->id) {
                // We need to assign a type ID. Iterate over the reserved IDs until we find
                // some type ID that is a distance of 2 greater than the last one we've seen.
                // That means there is an available type ID between these reserved IDs.
                while (nextTypeIter != assignedIdsIterEnd) {
                    if (nextTypeIter->first.packageId() != package->id.value()) {
                        break;
                    }

                    const uint8_t typeId = nextTypeIter->first.typeId();
                    if (typeId > nextExpectedTypeId) {
                        // There is a gap in the type IDs, so use the missing one.
                        type->id = nextExpectedTypeId++;
                        break;
                    }

                    // Set our expectation to be the next type ID after the reserved one we
                    // just saw.
                    nextExpectedTypeId = typeId + 1;

                    // Move to the next reserved ID.
                    ++nextTypeIter;
                }

                if (!type->id) {
                    // We must have hit the end of the reserved IDs and not found a gap.
                    // That means the next ID is available.
                    type->id = nextExpectedTypeId++;
                }
            }

            resourceId = ResourceId(package->id.value(), type->id.value(), 0);
            uint16_t nextExpectedEntryId = 0;

            // Find the closest matching ResourceId that is <= the one with only the package
            // and type set.
            auto nextEntryIter = assignedIds.lower_bound(resourceId);
            for (auto& entry : type->entries) {
                if (!entry->id) {
                    // We need to assign an entry ID. Iterate over the reserved IDs until we find
                    // some entry ID that is a distance of 2 greater than the last one we've seen.
                    // That means there is an available entry ID between these reserved IDs.
                    while (nextEntryIter != assignedIdsIterEnd) {
                        if (nextEntryIter->first.packageId() != package->id.value() ||
                                nextEntryIter->first.typeId() != type->id.value()) {
                            break;
                        }

                        const uint16_t entryId = nextEntryIter->first.entryId();
                        if (entryId > nextExpectedEntryId) {
                            // There is a gap in the entry IDs, so use the missing one.
                            entry->id = nextExpectedEntryId++;
                            break;
                        }

                        // Set our expectation to be the next type ID after the reserved one we
                        // just saw.
                        nextExpectedEntryId = entryId + 1;

                        // Move to the next reserved entry ID.
                        ++nextEntryIter;
                    }

                    if (!entry->id) {
                        // We must have hit the end of the reserved IDs and not found a gap.
                        // That means the next ID is available.
                        entry->id = nextExpectedEntryId++;
                    }
                }
            }
        }
    }
    return true;
}

} // namespace aapt
