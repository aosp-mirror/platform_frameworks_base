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

#include "link/NoDefaultResourceRemover.h"

#include <algorithm>

#include "ResourceTable.h"

namespace aapt {

static bool IsDefaultConfigRequired(const ConfigDescription& config) {
  // We don't want to be overzealous with resource removal, so have strict requirements.
  // If a resource defines a value for a locale-only configuration, the default configuration is
  // required.
  if (ConfigDescription::DefaultConfig().diff(config) == ConfigDescription::CONFIG_LOCALE) {
    return true;
  }
  return false;
}

static bool KeepResource(const std::unique_ptr<ResourceEntry>& entry) {
  if (entry->visibility.level == Visibility::Level::kPublic) {
    // Removing a public API without the developer knowing is bad, so just leave this here for now.
    return true;
  }

  if (entry->HasDefaultValue()) {
    // There is a default value, no removal needed.
    return true;
  }

  // There is no default value defined, check if removal is required.
  for (const auto& config_value : entry->values) {
    if (IsDefaultConfigRequired(config_value->config)) {
      return false;
    }
  }
  return true;
}

bool NoDefaultResourceRemover::Consume(IAaptContext* context, ResourceTable* table) {
  const ConfigDescription default_config = ConfigDescription::DefaultConfig();
  for (auto& pkg : table->packages) {
    for (auto& type : pkg->types) {
      const auto end_iter = type->entries.end();
      const auto new_end_iter =
          std::stable_partition(type->entries.begin(), end_iter, KeepResource);
      for (auto iter = new_end_iter; iter != end_iter; ++iter) {
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

      type->entries.erase(new_end_iter, type->entries.end());
    }
  }
  return true;
}

}  // namespace aapt
