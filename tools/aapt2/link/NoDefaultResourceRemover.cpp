/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "androidfw/Locale.h"

#include "link/NoDefaultResourceRemover.h"

#include <algorithm>

#include "ResourceTable.h"

using android::ConfigDescription;

namespace aapt {

static bool KeepResource(const std::unique_ptr<ResourceEntry>& entry, int minSdk) {
  if (entry->visibility.level == Visibility::Level::kPublic) {
    // Removing a public API without the developer knowing is bad, so just leave this here for now.
    return true;
  }

  if (entry->HasDefaultValue()) {
    // There is a default value, no removal needed.
    return true;
  }

  // There is no default value defined, check if removal is required.
  bool defaultRequired = false;
  for (const auto& config_value : entry->values) {
    const int config = ConfigDescription::DefaultConfig().diff(config_value->config);
    // If a resource defines a value for a locale-only configuration, the default configuration is
    // required.
    if (config == ConfigDescription::CONFIG_LOCALE) {
      defaultRequired = true;
    }
    // If a resource defines a version-only config, the config value can be used as a default if
    // the version is at most the minimum sdk version
    else if (config == ConfigDescription::CONFIG_VERSION
        && config_value->config.sdkVersion <= minSdk) {
      return true;
    }
    // If a resource defines a value for a density only configuration, then that value could be used
    // as a default and the entry should not be removed
    else if (config == ConfigDescription::CONFIG_DENSITY
        || (config == (ConfigDescription::CONFIG_DENSITY | ConfigDescription::CONFIG_VERSION)
            && config_value->config.sdkVersion <= minSdk)) {
      return true;
    }
  }

  return !defaultRequired;
}

bool NoDefaultResourceRemover::Consume(IAaptContext* context, ResourceTable* table) {
  for (auto& pkg : table->packages) {
    for (auto& type : pkg->types) {
      // Gather the entries without defaults that must be removed
      const int minSdk = context->GetMinSdkVersion();
      const auto end_iter = type->entries.end();
      const auto remove_iter = std::stable_partition(type->entries.begin(), end_iter,
          [&minSdk](const std::unique_ptr<ResourceEntry>& entry) -> bool {
        return KeepResource(entry, minSdk);
      });

      for (auto iter = remove_iter; iter != end_iter; ++iter) {
        const ResourceName name(pkg->name, type->type, (*iter)->name);
        IDiagnostics* diag = context->GetDiagnostics();
        diag->Warn(DiagMessage() << "removing resource " << name
                                 << " without required default value");
        if (context->IsVerbose()) {
          diag->Note(DiagMessage() << "  did you forget to remove all definitions?");
          for (const auto& config_value : (*iter)->values) {
            if (config_value->value != nullptr) {
              diag->Note(DiagMessage(config_value->value->GetSource()) << "defined here");
            }
          }
        }
      }

      type->entries.erase(remove_iter, end_iter);
    }
  }
  return true;
}

}  // namespace aapt
