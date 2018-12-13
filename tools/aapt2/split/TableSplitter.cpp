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

#include "split/TableSplitter.h"

#include <algorithm>
#include <map>
#include <set>
#include <unordered_set>
#include <unordered_map>
#include <vector>

#include "android-base/logging.h"
#include "androidfw/ConfigDescription.h"

#include "ResourceTable.h"
#include "util/Util.h"

using ::android::ConfigDescription;

namespace aapt {

using ConfigClaimedMap = std::unordered_map<ResourceConfigValue*, bool>;
using ConfigDensityGroups = std::map<ConfigDescription, std::vector<ResourceConfigValue*>>;

static ConfigDescription CopyWithoutDensity(const ConfigDescription& config) {
  ConfigDescription without_density = config;
  without_density.density = 0;
  return without_density;
}

/**
 * Selects values that match exactly the constraints given.
 */
class SplitValueSelector {
 public:
  explicit SplitValueSelector(const SplitConstraints& constraints) {
    for (const ConfigDescription& config : constraints.configs) {
      if (config.density == 0) {
        density_independent_configs_.insert(config);
      } else {
        density_dependent_config_to_density_map_[CopyWithoutDensity(config)] = config.density;
      }
    }
  }

  std::vector<ResourceConfigValue*> SelectValues(
      const ConfigDensityGroups& density_groups,
      ConfigClaimedMap* claimed_values) {
    std::vector<ResourceConfigValue*> selected;

    // Select the regular values.
    for (auto& entry : *claimed_values) {
      // Check if the entry has a density.
      ResourceConfigValue* config_value = entry.first;
      if (config_value->config.density == 0 && !entry.second) {
        // This is still available.
        if (density_independent_configs_.find(config_value->config) !=
            density_independent_configs_.end()) {
          selected.push_back(config_value);

          // Mark the entry as taken.
          entry.second = true;
        }
      }
    }

    // Now examine the densities
    for (auto& entry : density_groups) {
      // We do not care if the value is claimed, since density values can be
      // in multiple splits.
      const ConfigDescription& config = entry.first;
      const std::vector<ResourceConfigValue*>& related_values = entry.second;
      auto density_value_iter =
          density_dependent_config_to_density_map_.find(config);
      if (density_value_iter !=
          density_dependent_config_to_density_map_.end()) {
        // Select the best one!
        ConfigDescription target_density = config;
        target_density.density = density_value_iter->second;

        ResourceConfigValue* best_value = nullptr;
        for (ResourceConfigValue* this_value : related_values) {
          if (!best_value || this_value->config.isBetterThan(best_value->config, &target_density)) {
            best_value = this_value;
          }
        }
        CHECK(best_value != nullptr);

        // When we select one of these, they are all claimed such that the base
        // doesn't include any anymore.
        (*claimed_values)[best_value] = true;
        selected.push_back(best_value);
      }
    }
    return selected;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(SplitValueSelector);

  std::set<ConfigDescription> density_independent_configs_;
  std::map<ConfigDescription, uint16_t>
      density_dependent_config_to_density_map_;
};

/**
 * Marking non-preferred densities as claimed will make sure the base doesn't include them, leaving
 * only the preferred density behind.
 */
static void MarkNonPreferredDensitiesAsClaimed(
    const std::vector<uint16_t>& preferred_densities, const ConfigDensityGroups& density_groups,
    ConfigClaimedMap* config_claimed_map) {
  for (auto& entry : density_groups) {
    const ConfigDescription& config = entry.first;
    const std::vector<ResourceConfigValue*>& related_values = entry.second;

    // There can be multiple best values if there are multiple preferred densities.
    std::unordered_set<ResourceConfigValue*> best_values;

    // For each preferred density, find the value that is the best.
    for (uint16_t preferred_density : preferred_densities) {
      ConfigDescription target_density = config;
      target_density.density = preferred_density;
      ResourceConfigValue* best_value = nullptr;
      for (ResourceConfigValue* this_value : related_values) {
        if (!best_value || this_value->config.isBetterThan(best_value->config, &target_density)) {
          best_value = this_value;
        }
      }
      CHECK(best_value != nullptr);
      best_values.insert(best_value);
    }

    // Claim all the values that aren't the best so that they will be removed from the base.
    for (ResourceConfigValue* this_value : related_values) {
      if (best_values.find(this_value) == best_values.end()) {
        (*config_claimed_map)[this_value] = true;
      }
    }
  }
}
bool TableSplitter::VerifySplitConstraints(IAaptContext* context) {
  bool error = false;
  for (size_t i = 0; i < split_constraints_.size(); i++) {
    if (split_constraints_[i].configs.size() == 0) {
      // For now, treat this as a warning. We may consider aborting processing.
      context->GetDiagnostics()->Warn(DiagMessage()
                                       << "no configurations for constraint '"
                                       << split_constraints_[i].name << "'");
    }
    for (size_t j = i + 1; j < split_constraints_.size(); j++) {
      for (const ConfigDescription& config : split_constraints_[i].configs) {
        if (split_constraints_[j].configs.find(config) != split_constraints_[j].configs.end()) {
          context->GetDiagnostics()->Error(DiagMessage()
                                           << "config '" << config
                                           << "' appears in multiple splits, "
                                           << "target split ambiguous");
          error = true;
        }
      }
    }
  }
  return !error;
}

void TableSplitter::SplitTable(ResourceTable* original_table) {
  const size_t split_count = split_constraints_.size();
  for (auto& pkg : original_table->packages) {
    // Initialize all packages for splits.
    for (size_t idx = 0; idx < split_count; idx++) {
      ResourceTable* split_table = splits_[idx].get();
      split_table->CreatePackage(pkg->name, pkg->id);
    }

    for (auto& type : pkg->types) {
      if (type->type == ResourceType::kMipmap) {
        // Always keep mipmaps.
        continue;
      }

      for (auto& entry : type->entries) {
        if (options_.config_filter) {
          // First eliminate any resource that we definitely don't want.
          for (std::unique_ptr<ResourceConfigValue>& config_value : entry->values) {
            if (!options_.config_filter->Match(config_value->config)) {
              // null out the entry. We will clean up and remove nulls at the end for performance
              // reasons.
              config_value.reset();
            }
          }
        }

        // Organize the values into two separate buckets. Those that are density-dependent and those
        // that are density-independent. One density technically matches all density, it's just that
        // some densities match better. So we need to be aware of the full set of densities to make
        // this decision.
        ConfigDensityGroups density_groups;
        ConfigClaimedMap config_claimed_map;
        for (const std::unique_ptr<ResourceConfigValue>& config_value : entry->values) {
          if (config_value) {
            config_claimed_map[config_value.get()] = false;

            if (config_value->config.density != 0) {
              // Create a bucket for this density-dependent config.
              density_groups[CopyWithoutDensity(config_value->config)]
                  .push_back(config_value.get());
            }
          }
        }

        // First we check all the splits. If it doesn't match one of the splits, we leave it in the
        // base.
        for (size_t idx = 0; idx < split_count; idx++) {
          const SplitConstraints& split_constraint = split_constraints_[idx];
          ResourceTable* split_table = splits_[idx].get();

          // Select the values we want from this entry for this split.
          SplitValueSelector selector(split_constraint);
          std::vector<ResourceConfigValue*> selected_values =
              selector.SelectValues(density_groups, &config_claimed_map);

          // No need to do any work if we selected nothing.
          if (!selected_values.empty()) {
            // Create the same resource structure in the split. We do this lazily because we might
            // not have actual values for each type/entry.
            ResourceTablePackage* split_pkg = split_table->FindPackage(pkg->name);
            ResourceTableType* split_type = split_pkg->FindOrCreateType(type->type);
            if (!split_type->id) {
              split_type->id = type->id;
              split_type->visibility_level = type->visibility_level;
            }

            ResourceEntry* split_entry = split_type->FindOrCreateEntry(entry->name);
            if (!split_entry->id) {
              split_entry->id = entry->id;
              split_entry->visibility = entry->visibility;
              split_entry->overlayable_item = entry->overlayable_item;
            }

            // Copy the selected values into the new Split Entry.
            for (ResourceConfigValue* config_value : selected_values) {
              ResourceConfigValue* new_config_value =
                  split_entry->FindOrCreateValue(config_value->config, config_value->product);
              new_config_value->value = std::unique_ptr<Value>(
                  config_value->value->Clone(&split_table->string_pool));
            }
          }
        }

        if (!options_.preferred_densities.empty()) {
          MarkNonPreferredDensitiesAsClaimed(options_.preferred_densities,
                                             density_groups,
                                             &config_claimed_map);
        }

        // All splits are handled, now check to see what wasn't claimed and remove whatever exists
        // in other splits.
        for (std::unique_ptr<ResourceConfigValue>& config_value : entry->values) {
          if (config_value && config_claimed_map[config_value.get()]) {
            // Claimed, remove from base.
            config_value.reset();
          }
        }

        // Now erase all nullptrs.
        entry->values.erase(
            std::remove(entry->values.begin(), entry->values.end(), nullptr),
            entry->values.end());
      }
    }
  }
}

}  // namespace aapt
