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

#include "compile/IdAssigner.h"

#include <map>

#include "android-base/logging.h"

#include "ResourceTable.h"
#include "process/IResourceTableConsumer.h"
#include "util/Util.h"

namespace aapt {

/**
 * Assigns the intended ID to the ResourceTablePackage, ResourceTableType, and
 * ResourceEntry,
 * as long as there is no existing ID or the ID is the same.
 */
static bool AssignId(IDiagnostics* diag, const ResourceId& id,
                     const ResourceName& name, ResourceTablePackage* pkg,
                     ResourceTableType* type, ResourceEntry* entry) {
  if (pkg->id.value() == id.package_id()) {
    if (!type->id || type->id.value() == id.type_id()) {
      type->id = id.type_id();

      if (!entry->id || entry->id.value() == id.entry_id()) {
        entry->id = id.entry_id();
        return true;
      }
    }
  }

  const ResourceId existing_id(pkg->id.value(), type->id ? type->id.value() : 0,
                               entry->id ? entry->id.value() : 0);
  diag->Error(DiagMessage() << "can't assign ID " << id << " to resource "
                            << name << " with conflicting ID " << existing_id);
  return false;
}

bool IdAssigner::Consume(IAaptContext* context, ResourceTable* table) {
  std::map<ResourceId, ResourceName> assigned_ids;

  for (auto& package : table->packages) {
    CHECK(bool(package->id)) << "packages must have manually assigned IDs";

    for (auto& type : package->types) {
      for (auto& entry : type->entries) {
        const ResourceName name(package->name, type->type, entry->name);

        if (assigned_id_map_) {
          // Assign the pre-assigned stable ID meant for this resource.
          const auto iter = assigned_id_map_->find(name);
          if (iter != assigned_id_map_->end()) {
            const ResourceId assigned_id = iter->second;
            const bool result =
                AssignId(context->GetDiagnostics(), assigned_id, name,
                         package.get(), type.get(), entry.get());
            if (!result) {
              return false;
            }
          }
        }

        if (package->id && type->id && entry->id) {
          // If the ID is set for this resource, then reserve it.
          ResourceId resource_id(package->id.value(), type->id.value(),
                                 entry->id.value());
          auto result = assigned_ids.insert({resource_id, name});
          const ResourceName& existing_name = result.first->second;
          if (!result.second) {
            context->GetDiagnostics()->Error(
                DiagMessage() << "resource " << name << " has same ID "
                              << resource_id << " as " << existing_name);
            return false;
          }
        }
      }
    }
  }

  if (assigned_id_map_) {
    // Reserve all the IDs mentioned in the stable ID map. That way we won't
    // assign
    // IDs that were listed in the map if they don't exist in the table.
    for (const auto& stable_id_entry : *assigned_id_map_) {
      const ResourceName& pre_assigned_name = stable_id_entry.first;
      const ResourceId& pre_assigned_id = stable_id_entry.second;
      auto result = assigned_ids.insert({pre_assigned_id, pre_assigned_name});
      const ResourceName& existing_name = result.first->second;
      if (!result.second && existing_name != pre_assigned_name) {
        context->GetDiagnostics()->Error(
            DiagMessage() << "stable ID " << pre_assigned_id << " for resource "
                          << pre_assigned_name
                          << " is already taken by resource " << existing_name);
        return false;
      }
    }
  }

  // Assign any resources without IDs the next available ID. Gaps will be filled
  // if possible,
  // unless those IDs have been reserved.

  const auto assigned_ids_iter_end = assigned_ids.end();
  for (auto& package : table->packages) {
    CHECK(bool(package->id)) << "packages must have manually assigned IDs";

    // Build a half filled ResourceId object, which will be used to find the
    // closest matching
    // reserved ID in the assignedId map. From that point the next available
    // type ID can be
    // found.
    ResourceId resource_id(package->id.value(), 0, 0);
    uint8_t next_expected_type_id = 1;

    // Find the closest matching ResourceId that is <= the one with only the
    // package set.
    auto next_type_iter = assigned_ids.lower_bound(resource_id);
    for (auto& type : package->types) {
      if (!type->id) {
        // We need to assign a type ID. Iterate over the reserved IDs until we
        // find
        // some type ID that is a distance of 2 greater than the last one we've
        // seen.
        // That means there is an available type ID between these reserved IDs.
        while (next_type_iter != assigned_ids_iter_end) {
          if (next_type_iter->first.package_id() != package->id.value()) {
            break;
          }

          const uint8_t type_id = next_type_iter->first.type_id();
          if (type_id > next_expected_type_id) {
            // There is a gap in the type IDs, so use the missing one.
            type->id = next_expected_type_id++;
            break;
          }

          // Set our expectation to be the next type ID after the reserved one
          // we
          // just saw.
          next_expected_type_id = type_id + 1;

          // Move to the next reserved ID.
          ++next_type_iter;
        }

        if (!type->id) {
          // We must have hit the end of the reserved IDs and not found a gap.
          // That means the next ID is available.
          type->id = next_expected_type_id++;
        }
      }

      resource_id = ResourceId(package->id.value(), type->id.value(), 0);
      uint16_t next_expected_entry_id = 0;

      // Find the closest matching ResourceId that is <= the one with only the
      // package
      // and type set.
      auto next_entry_iter = assigned_ids.lower_bound(resource_id);
      for (auto& entry : type->entries) {
        if (!entry->id) {
          // We need to assign an entry ID. Iterate over the reserved IDs until
          // we find
          // some entry ID that is a distance of 2 greater than the last one
          // we've seen.
          // That means there is an available entry ID between these reserved
          // IDs.
          while (next_entry_iter != assigned_ids_iter_end) {
            if (next_entry_iter->first.package_id() != package->id.value() ||
                next_entry_iter->first.type_id() != type->id.value()) {
              break;
            }

            const uint16_t entry_id = next_entry_iter->first.entry_id();
            if (entry_id > next_expected_entry_id) {
              // There is a gap in the entry IDs, so use the missing one.
              entry->id = next_expected_entry_id++;
              break;
            }

            // Set our expectation to be the next type ID after the reserved one
            // we
            // just saw.
            next_expected_entry_id = entry_id + 1;

            // Move to the next reserved entry ID.
            ++next_entry_iter;
          }

          if (!entry->id) {
            // We must have hit the end of the reserved IDs and not found a gap.
            // That means the next ID is available.
            entry->id = next_expected_entry_id++;
          }
        }
      }
    }
  }
  return true;
}

}  // namespace aapt
