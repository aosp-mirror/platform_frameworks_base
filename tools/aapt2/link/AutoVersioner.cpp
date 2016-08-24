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

#include "ConfigDescription.h"
#include "ResourceTable.h"
#include "SdkConstants.h"
#include "ValueVisitor.h"
#include "link/Linkers.h"

#include <algorithm>
#include <cassert>

namespace aapt {

bool shouldGenerateVersionedResource(const ResourceEntry* entry, const ConfigDescription& config,
                                     const int sdkVersionToGenerate) {
    assert(sdkVersionToGenerate > config.sdkVersion);
    const auto endIter = entry->values.end();
    auto iter = entry->values.begin();
    for (; iter != endIter; ++iter) {
        if ((*iter)->config == config) {
            break;
        }
    }

    // The source config came from this list, so it should be here.
    assert(iter != entry->values.end());
    ++iter;

    // The next configuration either only varies in sdkVersion, or it is completely different
    // and therefore incompatible. If it is incompatible, we must generate the versioned resource.

    // NOTE: The ordering of configurations takes sdkVersion as higher precedence than other
    // qualifiers, so we need to iterate through the entire list to be sure there
    // are no higher sdk level versions of this resource.
    ConfigDescription tempConfig(config);
    for (; iter != endIter; ++iter) {
        tempConfig.sdkVersion = (*iter)->config.sdkVersion;
        if (tempConfig == (*iter)->config) {
            // The two configs are the same, check the sdk version.
            return sdkVersionToGenerate < (*iter)->config.sdkVersion;
        }
    }

    // No match was found, so we should generate the versioned resource.
    return true;
}

bool AutoVersioner::consume(IAaptContext* context, ResourceTable* table) {
    for (auto& package : table->packages) {
        for (auto& type : package->types) {
            if (type->type != ResourceType::kStyle) {
                continue;
            }

            for (auto& entry : type->entries) {
                for (size_t i = 0; i < entry->values.size(); i++) {
                    ResourceConfigValue* configValue = entry->values[i].get();
                    if (configValue->config.sdkVersion >= SDK_LOLLIPOP_MR1) {
                        // If this configuration is only used on L-MR1 then we don't need
                        // to do anything since we use private attributes since that version.
                        continue;
                    }

                    if (Style* style = valueCast<Style>(configValue->value.get())) {
                        Maybe<size_t> minSdkStripped;
                        std::vector<Style::Entry> stripped;

                        auto iter = style->entries.begin();
                        while (iter != style->entries.end()) {
                            assert(iter->key.id && "IDs must be assigned and linked");

                            // Find the SDK level that is higher than the configuration allows.
                            const size_t sdkLevel = findAttributeSdkLevel(iter->key.id.value());
                            if (sdkLevel > std::max<size_t>(configValue->config.sdkVersion, 1)) {
                                // Record that we are about to strip this.
                                stripped.emplace_back(std::move(*iter));

                                // We use the smallest SDK level to generate the new style.
                                if (minSdkStripped) {
                                    minSdkStripped = std::min(minSdkStripped.value(), sdkLevel);
                                } else {
                                    minSdkStripped = sdkLevel;
                                }

                                // Erase this from this style.
                                iter = style->entries.erase(iter);
                                continue;
                            }
                            ++iter;
                        }

                        if (minSdkStripped && !stripped.empty()) {
                            // We found attributes from a higher SDK level. Check that
                            // there is no other defined resource for the version we want to
                            // generate.
                            if (shouldGenerateVersionedResource(entry.get(),
                                                                configValue->config,
                                                                minSdkStripped.value())) {
                                // Let's create a new Style for this versioned resource.
                                ConfigDescription newConfig(configValue->config);
                                newConfig.sdkVersion = minSdkStripped.value();

                                std::unique_ptr<Style> newStyle(style->clone(&table->stringPool));
                                newStyle->setComment(style->getComment());
                                newStyle->setSource(style->getSource());

                                // Move the previously stripped attributes into this style.
                                newStyle->entries.insert(newStyle->entries.end(),
                                                         std::make_move_iterator(stripped.begin()),
                                                         std::make_move_iterator(stripped.end()));

                                // Insert the new Resource into the correct place.
                                entry->findOrCreateValue(newConfig, {})->value =
                                        std::move(newStyle);
                            }
                        }
                    }
                }
            }
        }
    }
    return true;
}

} // namespace aapt
