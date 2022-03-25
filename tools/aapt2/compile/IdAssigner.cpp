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
#include <unordered_map>

#include "android-base/expected.h"
#include "android-base/logging.h"

#include "ResourceTable.h"
#include "process/IResourceTableConsumer.h"
#include "util/Util.h"

using android::base::expected;
using android::base::unexpected;

namespace aapt {

namespace {
template <typename T>
using Result = expected<T, std::string>;

template <typename Id, typename Key>
struct NextIdFinder {
  explicit NextIdFinder(Id start_id = 0u) : next_id_(start_id){};

  // Attempts to reserve an identifier for the specified key.
  // If the identifier is already reserved by a different key, an error message is returned.
  // Reserving identifiers must be completed before `NextId` is called for the first time.
  Result<Id> ReserveId(Key key, Id id);

  // Retrieves the next available identifier that has not been reserved.
  std::optional<Id> NextId();

 private:
  // Attempts to set `next_id_` to the next available identifier that has not been reserved.
  // Returns whether there were any available identifiers.
  std::optional<Id> SkipToNextAvailableId();

  Id next_id_;
  bool next_id_called_ = false;
  bool exhausted_ = false;
  std::map<Id, Key> pre_assigned_ids_;
  typename std::map<Id, Key>::iterator next_preassigned_id_;
};

struct TypeGroup {
  explicit TypeGroup(uint8_t package_id, uint8_t type_id)
      : package_id_(package_id), type_id_(type_id){};

  // Attempts to reserve the resource id for the specified resource name.
  // If the id is already reserved by a different name, an error message is returned.
  // Reserving identifiers must be completed before `NextId` is called for the first time.
  Result<std::monostate> ReserveId(const ResourceName& name, ResourceId id);

  // Retrieves the next available resource id that has not been reserved.
  Result<ResourceId> NextId();

 private:
  uint8_t package_id_;
  uint8_t type_id_;
  NextIdFinder<uint16_t, ResourceName> next_entry_id_;
};

struct ResourceTypeKey {
  ResourceType type;
  uint8_t id;

  bool operator<(const ResourceTypeKey& other) const {
    return (type != other.type) ? type < other.type : id < other.id;
  }

  bool operator==(const ResourceTypeKey& other) const {
    return type == other.type && id == other.id;
  }

  bool operator!=(const ResourceTypeKey& other) const {
    return !(*this == other);
  }
};

::std::ostream& operator<<(::std::ostream& out, const ResourceTypeKey& type) {
  return out << type.type;
}

struct IdAssignerContext {
  IdAssignerContext(std::string package_name, uint8_t package_id)
      : package_name_(std::move(package_name)), package_id_(package_id) {
  }

  // Attempts to reserve the resource id for the specified resource name.
  // Returns whether the id was reserved successfully.
  // Reserving identifiers must be completed before `NextId` is called for the first time.
  bool ReserveId(const ResourceName& name, ResourceId id, const Visibility& visibility,
                 IDiagnostics* diag);

  // Retrieves the next available resource id that has not been reserved.
  std::optional<ResourceId> NextId(const ResourceName& name, IDiagnostics* diag);

 private:
  std::string package_name_;
  uint8_t package_id_;
  std::map<ResourceTypeKey, TypeGroup> types_;
  std::map<ResourceType, uint8_t> non_staged_type_ids_;
  NextIdFinder<uint8_t, ResourceTypeKey> type_id_finder_ =
      NextIdFinder<uint8_t, ResourceTypeKey>(1);
};

}  // namespace

bool IdAssigner::Consume(IAaptContext* context, ResourceTable* table) {
  IdAssignerContext assigned_ids(context->GetCompilationPackage(), context->GetPackageId());
  for (auto& package : table->packages) {
    for (auto& type : package->types) {
      for (auto& entry : type->entries) {
        const ResourceName name(package->name, type->type, entry->name);
        if (entry->id && !assigned_ids.ReserveId(name, entry->id.value(), entry->visibility,
                                                 context->GetDiagnostics())) {
          return false;
        }

        auto v = entry->visibility;
        v.staged_api = true;
        if (entry->staged_id && !assigned_ids.ReserveId(name, entry->staged_id.value().id, v,
                                                        context->GetDiagnostics())) {
          return false;
        }

        if (assigned_id_map_) {
          // Assign the pre-assigned stable ID meant for this resource.
          const auto iter = assigned_id_map_->find(name);
          if (iter != assigned_id_map_->end()) {
            const ResourceId assigned_id = iter->second;
            if (!assigned_ids.ReserveId(name, assigned_id, entry->visibility,
                                        context->GetDiagnostics())) {
              return false;
            }
            entry->id = assigned_id;
          }
        }
      }
    }
  }

  if (assigned_id_map_) {
    // Reserve all the IDs mentioned in the stable ID map. That way we won't assig IDs that were
    // listed in the map if they don't exist in the table.
    for (const auto& stable_id_entry : *assigned_id_map_) {
      const ResourceName& pre_assigned_name = stable_id_entry.first;
      const ResourceId& pre_assigned_id = stable_id_entry.second;
      if (!assigned_ids.ReserveId(pre_assigned_name, pre_assigned_id, {},
                                  context->GetDiagnostics())) {
        return false;
      }
    }
  }

  // Assign any resources without IDs the next available ID. Gaps will be filled if possible,
  // unless those IDs have been reserved.
  for (auto& package : table->packages) {
    for (auto& type : package->types) {
      for (auto& entry : type->entries) {
        const ResourceName name(package->name, type->type, entry->name);
        if (entry->id) {
          continue;
        }
        auto id = assigned_ids.NextId(name, context->GetDiagnostics());
        if (!id.has_value()) {
          return false;
        }
        entry->id = id.value();
      }
    }
  }
  return true;
}

namespace {
template <typename Id, typename Key>
Result<Id> NextIdFinder<Id, Key>::ReserveId(Key key, Id id) {
  CHECK(!next_id_called_) << "ReserveId cannot be called after NextId";
  auto assign_result = pre_assigned_ids_.emplace(id, key);
  if (!assign_result.second && assign_result.first->second != key) {
    std::stringstream error;
    error << "ID is already assigned to " << assign_result.first->second;
    return unexpected(error.str());
  }
  return id;
}

template <typename Id, typename Key>
std::optional<Id> NextIdFinder<Id, Key>::NextId() {
  if (!next_id_called_) {
    next_id_called_ = true;
    next_preassigned_id_ = pre_assigned_ids_.begin();
  }
  return SkipToNextAvailableId();
}

template <typename Id, typename Key>
std::optional<Id> NextIdFinder<Id, Key>::SkipToNextAvailableId() {
  if (exhausted_) {
    return {};
  }
  while (next_preassigned_id_ != pre_assigned_ids_.end()) {
    if (next_preassigned_id_->first == next_id_) {
      if (next_id_ == std::numeric_limits<Id>::max()) {
        // The last identifier was reserved so there are no more available identifiers.
        exhausted_ = true;
        return {};
      }
      ++next_id_;
      ++next_preassigned_id_;
      continue;
    }
    CHECK(next_preassigned_id_->first > next_id_) << "Preassigned IDs are not in sorted order";
    break;
  }
  if (next_id_ == std::numeric_limits<Id>::max()) {
    // There are no more identifiers after this one, but this one is still available so return it.
    exhausted_ = true;
  }
  return next_id_++;
}

Result<std::monostate> TypeGroup::ReserveId(const ResourceName& name, ResourceId id) {
  if (type_id_ != id.type_id()) {
    // Currently there cannot be multiple type ids for a single type.
    std::stringstream error;
    error << "type '" << name.type << "' already has ID " << std::hex << (int)type_id_;
    return unexpected(error.str());
  }

  auto assign_result = next_entry_id_.ReserveId(name, id.entry_id());
  if (!assign_result.has_value()) {
    std::stringstream error;
    error << "entry " << assign_result.error();
    return unexpected(error.str());
  }
  return {};
}

Result<ResourceId> TypeGroup::NextId() {
  auto entry_id = next_entry_id_.NextId();
  if (!entry_id.has_value()) {
    std::stringstream error;
    error << "resource type ID has exceeded the maximum number of resource entries ("
          << (std::numeric_limits<uint16_t>::max() + 1u) << ")";
    return unexpected(error.str());
  }
  return ResourceId(package_id_, type_id_, entry_id.value());
}

bool IdAssignerContext::ReserveId(const ResourceName& name, ResourceId id,
                                  const Visibility& visibility, IDiagnostics* diag) {
  if (package_id_ != id.package_id()) {
    diag->Error(DiagMessage() << "can't assign ID " << id << " to resource " << name
                              << " because package already has ID " << std::hex
                              << (int)id.package_id());
    return false;
  }

  auto key = ResourceTypeKey{name.type.type, id.type_id()};
  auto type = types_.find(key);
  if (type == types_.end()) {
    // The type has not been assigned an id yet. Ensure that the specified id is not being used by
    // another type.
    auto assign_result = type_id_finder_.ReserveId(key, id.type_id());
    if (!assign_result.has_value()) {
      diag->Error(DiagMessage() << "can't assign ID " << id << " to resource " << name
                                << " because type " << assign_result.error());
      return false;
    }
    type = types_.emplace(key, TypeGroup(package_id_, id.type_id())).first;
  }

  if (!visibility.staged_api) {
    // Ensure that non-staged resources can only exist in one type ID.
    auto non_staged_type = non_staged_type_ids_.emplace(name.type.type, id.type_id());
    if (!non_staged_type.second && non_staged_type.first->second != id.type_id()) {
      diag->Error(DiagMessage() << "can't assign ID " << id << " to resource " << name
                                << " because type already has ID " << std::hex
                                << (int)id.type_id());
      return false;
    }
  }

  auto assign_result = type->second.ReserveId(name, id);
  if (!assign_result.has_value()) {
    diag->Error(DiagMessage() << "can't assign ID " << id << " to resource " << name << " because "
                              << assign_result.error());
    return false;
  }

  return true;
}

std::optional<ResourceId> IdAssignerContext::NextId(const ResourceName& name, IDiagnostics* diag) {
  // The package name is not known during the compile stage.
  // Resources without a package name are considered a part of the app being linked.
  CHECK(name.package.empty() || name.package == package_name_);

  // Find the type id for non-staged resources of this type.
  auto non_staged_type = non_staged_type_ids_.find(name.type.type);
  if (non_staged_type == non_staged_type_ids_.end()) {
    auto next_type_id = type_id_finder_.NextId();
    CHECK(next_type_id.has_value()) << "resource type IDs allocated have exceeded maximum (256)";
    non_staged_type = non_staged_type_ids_.emplace(name.type.type, *next_type_id).first;
  }

  ResourceTypeKey key{name.type.type, non_staged_type->second};
  auto type = types_.find(key);
  if (type == types_.end()) {
    type = types_.emplace(key, TypeGroup(package_id_, key.id)).first;
  }

  auto assign_result = type->second.NextId();
  if (!assign_result.has_value()) {
    diag->Error(DiagMessage() << "can't assign resource ID to resource " << name << " because "
                              << assign_result.error());
    return {};
  }
  return assign_result.value();
}
}  // namespace

}  // namespace aapt
