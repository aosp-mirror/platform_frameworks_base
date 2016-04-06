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

#include "ConfigDescription.h"
#include "ResourceTable.h"
#include "split/TableSplitter.h"

#include <algorithm>
#include <map>
#include <set>
#include <unordered_map>
#include <vector>

namespace aapt {

using ConfigClaimedMap = std::unordered_map<ResourceConfigValue*, bool>;
using ConfigDensityGroups = std::map<ConfigDescription, std::vector<ResourceConfigValue*>>;

static ConfigDescription copyWithoutDensity(const ConfigDescription& config) {
    ConfigDescription withoutDensity = config;
    withoutDensity.density = 0;
    return withoutDensity;
}

/**
 * Selects values that match exactly the constraints given.
 */
class SplitValueSelector {
public:
    SplitValueSelector(const SplitConstraints& constraints) {
        for (const ConfigDescription& config : constraints.configs) {
            if (config.density == 0) {
                mDensityIndependentConfigs.insert(config);
            } else {
                mDensityDependentConfigToDensityMap[copyWithoutDensity(config)] = config.density;
            }
        }
    }

    std::vector<ResourceConfigValue*> selectValues(const ConfigDensityGroups& densityGroups,
                                                   ConfigClaimedMap* claimedValues) {
        std::vector<ResourceConfigValue*> selected;

        // Select the regular values.
        for (auto& entry : *claimedValues) {
            // Check if the entry has a density.
            ResourceConfigValue* configValue = entry.first;
            if (configValue->config.density == 0 && !entry.second) {
                // This is still available.
                if (mDensityIndependentConfigs.find(configValue->config) !=
                        mDensityIndependentConfigs.end()) {
                    selected.push_back(configValue);

                    // Mark the entry as taken.
                    entry.second = true;
                }
            }
        }

        // Now examine the densities
        for (auto& entry : densityGroups) {
            // We do not care if the value is claimed, since density values can be
            // in multiple splits.
            const ConfigDescription& config = entry.first;
            const std::vector<ResourceConfigValue*>& relatedValues = entry.second;

            auto densityValueIter = mDensityDependentConfigToDensityMap.find(config);
            if (densityValueIter != mDensityDependentConfigToDensityMap.end()) {
                // Select the best one!
                ConfigDescription targetDensity = config;
                targetDensity.density = densityValueIter->second;

                ResourceConfigValue* bestValue = nullptr;
                for (ResourceConfigValue* thisValue : relatedValues) {
                    if (!bestValue ||
                            thisValue->config.isBetterThan(bestValue->config, &targetDensity)) {
                        bestValue = thisValue;
                    }

                    // When we select one of these, they are all claimed such that the base
                    // doesn't include any anymore.
                    (*claimedValues)[thisValue] = true;
                }
                assert(bestValue);
                selected.push_back(bestValue);
            }
        }
        return selected;
    }

private:
    std::set<ConfigDescription> mDensityIndependentConfigs;
    std::map<ConfigDescription, uint16_t> mDensityDependentConfigToDensityMap;
};

/**
 * Marking non-preferred densities as claimed will make sure the base doesn't include them,
 * leaving only the preferred density behind.
 */
static void markNonPreferredDensitiesAsClaimed(uint16_t preferredDensity,
                                               const ConfigDensityGroups& densityGroups,
                                               ConfigClaimedMap* configClaimedMap) {
    for (auto& entry : densityGroups) {
        const ConfigDescription& config = entry.first;
        const std::vector<ResourceConfigValue*>& relatedValues = entry.second;

        ConfigDescription targetDensity = config;
        targetDensity.density = preferredDensity;
        ResourceConfigValue* bestValue = nullptr;
        for (ResourceConfigValue* thisValue : relatedValues) {
            if (!bestValue) {
                bestValue = thisValue;
            } else if (thisValue->config.isBetterThan(bestValue->config, &targetDensity)) {
                // Claim the previous value so that it is not included in the base.
                (*configClaimedMap)[bestValue] = true;
                bestValue = thisValue;
            } else {
                // Claim this value so that it is not included in the base.
                (*configClaimedMap)[thisValue] = true;
            }
        }
        assert(bestValue);
    }
}

bool TableSplitter::verifySplitConstraints(IAaptContext* context) {
    bool error = false;
    for (size_t i = 0; i < mSplitConstraints.size(); i++) {
        for (size_t j = i + 1; j < mSplitConstraints.size(); j++) {
            for (const ConfigDescription& config : mSplitConstraints[i].configs) {
                if (mSplitConstraints[j].configs.find(config) !=
                        mSplitConstraints[j].configs.end()) {
                    context->getDiagnostics()->error(DiagMessage() << "config '" << config
                                                     << "' appears in multiple splits, "
                                                     << "target split ambiguous");
                    error = true;
                }
            }
        }
    }
    return !error;
}

void TableSplitter::splitTable(ResourceTable* originalTable) {
    const size_t splitCount = mSplitConstraints.size();
    for (auto& pkg : originalTable->packages) {
        // Initialize all packages for splits.
        for (size_t idx = 0; idx < splitCount; idx++) {
            ResourceTable* splitTable = mSplits[idx].get();
            splitTable->createPackage(pkg->name, pkg->id);
        }

        for (auto& type : pkg->types) {
            if (type->type == ResourceType::kMipmap) {
                // Always keep mipmaps.
                continue;
            }

            for (auto& entry : type->entries) {
                if (mConfigFilter) {
                    // First eliminate any resource that we definitely don't want.
                    for (std::unique_ptr<ResourceConfigValue>& configValue : entry->values) {
                        if (!mConfigFilter->match(configValue->config)) {
                            // null out the entry. We will clean up and remove nulls at the end
                            // for performance reasons.
                            configValue.reset();
                        }
                    }
                }

                // Organize the values into two separate buckets. Those that are density-dependent
                // and those that are density-independent.
                // One density technically matches all density, it's just that some densities
                // match better. So we need to be aware of the full set of densities to make this
                // decision.
                ConfigDensityGroups densityGroups;
                ConfigClaimedMap configClaimedMap;
                for (const std::unique_ptr<ResourceConfigValue>& configValue : entry->values) {
                    if (configValue) {
                        configClaimedMap[configValue.get()] = false;

                        if (configValue->config.density != 0) {
                            // Create a bucket for this density-dependent config.
                            densityGroups[copyWithoutDensity(configValue->config)]
                                          .push_back(configValue.get());
                        }
                    }
                }

                // First we check all the splits. If it doesn't match one of the splits, we
                // leave it in the base.
                for (size_t idx = 0; idx < splitCount; idx++) {
                    const SplitConstraints& splitConstraint = mSplitConstraints[idx];
                    ResourceTable* splitTable = mSplits[idx].get();

                    // Select the values we want from this entry for this split.
                    SplitValueSelector selector(splitConstraint);
                    std::vector<ResourceConfigValue*> selectedValues =
                            selector.selectValues(densityGroups, &configClaimedMap);

                    // No need to do any work if we selected nothing.
                    if (!selectedValues.empty()) {
                        // Create the same resource structure in the split. We do this lazily
                        // because we might not have actual values for each type/entry.
                        ResourceTablePackage* splitPkg = splitTable->findPackage(pkg->name);
                        ResourceTableType* splitType = splitPkg->findOrCreateType(type->type);
                        if (!splitType->id) {
                            splitType->id = type->id;
                            splitType->symbolStatus = type->symbolStatus;
                        }

                        ResourceEntry* splitEntry = splitType->findOrCreateEntry(entry->name);
                        if (!splitEntry->id) {
                            splitEntry->id = entry->id;
                            splitEntry->symbolStatus = entry->symbolStatus;
                        }

                        // Copy the selected values into the new Split Entry.
                        for (ResourceConfigValue* configValue : selectedValues) {
                            ResourceConfigValue* newConfigValue = splitEntry->findOrCreateValue(
                                    configValue->config, configValue->product);
                            newConfigValue->value = std::unique_ptr<Value>(
                                    configValue->value->clone(&splitTable->stringPool));
                        }
                    }
                }

                if (mPreferredDensity) {
                    markNonPreferredDensitiesAsClaimed(mPreferredDensity.value(),
                                                       densityGroups,
                                                       &configClaimedMap);
                }

                // All splits are handled, now check to see what wasn't claimed and remove
                // whatever exists in other splits.
                for (std::unique_ptr<ResourceConfigValue>& configValue : entry->values) {
                    if (configValue && configClaimedMap[configValue.get()]) {
                        // Claimed, remove from base.
                        configValue.reset();
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

} // namespace aapt
