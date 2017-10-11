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

#include "android-base/logging.h"

#include "ConfigDescription.h"
#include "ResourceTable.h"
#include "SdkConstants.h"
#include "ValueVisitor.h"

namespace aapt {

bool ShouldGenerateVersionedResource(const ResourceEntry* entry, const ConfigDescription& config,
                                     const ApiVersion sdk_version_to_generate) {
  // We assume the caller is trying to generate a version greater than the current configuration.
  CHECK(sdk_version_to_generate > config.sdkVersion);
  return sdk_version_to_generate < FindNextApiVersionForConfig(entry, config);
}

ApiVersion FindNextApiVersionForConfig(const ResourceEntry* entry,
                                       const ConfigDescription& config) {
  const auto end_iter = entry->values.end();
  auto iter = entry->values.begin();
  for (; iter != end_iter; ++iter) {
    if ((*iter)->config == config) {
      break;
    }
  }

  // The source config came from this list, so it should be here.
  CHECK(iter != entry->values.end());
  ++iter;

  // The next configuration either only varies in sdkVersion, or it is completely different
  // and therefore incompatible. If it is incompatible, we must generate the versioned resource.

  // NOTE: The ordering of configurations takes sdkVersion as higher precedence than other
  // qualifiers, so we need to iterate through the entire list to be sure there
  // are no higher sdk level versions of this resource.
  ConfigDescription temp_config(config);
  for (; iter != end_iter; ++iter) {
    temp_config.sdkVersion = (*iter)->config.sdkVersion;
    if (temp_config == (*iter)->config) {
      // The two configs are the same, return the sdkVersion.
      return (*iter)->config.sdkVersion;
    }
  }

  // Didn't find another config with a different sdk version, so return the highest possible value.
  return std::numeric_limits<ApiVersion>::max();
}

bool AutoVersioner::Consume(IAaptContext* context, ResourceTable* table) {
  for (auto& package : table->packages) {
    for (auto& type : package->types) {
      if (type->type != ResourceType::kStyle) {
        continue;
      }

      for (auto& entry : type->entries) {
        for (size_t i = 0; i < entry->values.size(); i++) {
          ResourceConfigValue* config_value = entry->values[i].get();
          if (config_value->config.sdkVersion >= SDK_LOLLIPOP_MR1) {
            // If this configuration is only used on L-MR1 then we don't need
            // to do anything since we use private attributes since that
            // version.
            continue;
          }

          if (Style* style = ValueCast<Style>(config_value->value.get())) {
            Maybe<ApiVersion> min_sdk_stripped;
            std::vector<Style::Entry> stripped;

            auto iter = style->entries.begin();
            while (iter != style->entries.end()) {
              CHECK(bool(iter->key.id)) << "IDs must be assigned and linked";

              // Find the SDK level that is higher than the configuration
              // allows.
              const ApiVersion sdk_level = FindAttributeSdkLevel(iter->key.id.value());
              if (sdk_level > std::max<ApiVersion>(config_value->config.sdkVersion, 1)) {
                // Record that we are about to strip this.
                stripped.emplace_back(std::move(*iter));

                // We use the smallest SDK level to generate the new style.
                if (min_sdk_stripped) {
                  min_sdk_stripped = std::min(min_sdk_stripped.value(), sdk_level);
                } else {
                  min_sdk_stripped = sdk_level;
                }

                // Erase this from this style.
                iter = style->entries.erase(iter);
                continue;
              }
              ++iter;
            }

            if (min_sdk_stripped && !stripped.empty()) {
              // We found attributes from a higher SDK level. Check that
              // there is no other defined resource for the version we want to
              // generate.
              if (ShouldGenerateVersionedResource(entry.get(),
                                                  config_value->config,
                                                  min_sdk_stripped.value())) {
                // Let's create a new Style for this versioned resource.
                ConfigDescription new_config(config_value->config);
                new_config.sdkVersion = static_cast<uint16_t>(min_sdk_stripped.value());

                std::unique_ptr<Style> new_style(style->Clone(&table->string_pool));
                new_style->SetComment(style->GetComment());
                new_style->SetSource(style->GetSource());

                // Move the previously stripped attributes into this style.
                new_style->entries.insert(
                    new_style->entries.end(),
                    std::make_move_iterator(stripped.begin()),
                    std::make_move_iterator(stripped.end()));

                // Insert the new Resource into the correct place.
                entry->FindOrCreateValue(new_config, {})->value = std::move(new_style);
              }
            }
          }
        }
      }
    }
  }
  return true;
}

}  // namespace aapt
