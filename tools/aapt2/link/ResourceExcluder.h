/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef AAPT_LINK_RESOURCEEXCLUDER_H
#define AAPT_LINK_RESOURCEEXCLUDER_H

#include "android-base/macros.h"

#include "process/IResourceTableConsumer.h"

using android::ConfigDescription;

namespace aapt {

// Removes excluded configs from resources.
class ResourceExcluder : public IResourceTableConsumer {
 public:
  explicit ResourceExcluder(std::vector<ConfigDescription>& excluded_configs) {
    for (auto& config: excluded_configs) {
      int diff_from_default = config.diff(ConfigDescription::DefaultConfig());
      excluded_configs_.insert(std::pair(config, diff_from_default));
    }
  }

  bool Consume(IAaptContext* context, ResourceTable* table) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceExcluder);

  std::set<std::pair<ConfigDescription, int>> excluded_configs_;
};

} // namespace aapt

#endif  // AAPT_LINK_RESOURCEEXCLUDER_H
