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

#ifndef AAPT_FILTER_CONFIGFILTER_H
#define AAPT_FILTER_CONFIGFILTER_H

#include <set>
#include <utility>

#include "androidfw/ConfigDescription.h"

namespace aapt {

/**
 * Matches ConfigDescriptions based on some pattern.
 */
class IConfigFilter {
 public:
  virtual ~IConfigFilter() = default;

  /**
   * Returns true if the filter matches the configuration, false otherwise.
   */
  virtual bool Match(const android::ConfigDescription& config) const = 0;
};

/**
 * Implements config axis matching. An axis is one component of a configuration, like screen density
 * or locale. If an axis is specified in the filter, and the axis is specified in the configuration
 * to match, they must be compatible. Otherwise the configuration to match is accepted.
 *
 * Used when handling "-c" options.
 */
class AxisConfigFilter : public IConfigFilter {
 public:
  void AddConfig(android::ConfigDescription config);

  bool Match(const android::ConfigDescription& config) const override;

 private:
  std::set<std::pair<android::ConfigDescription, uint32_t>> configs_;
  uint32_t config_mask_ = 0;
};

}  // namespace aapt

#endif /* AAPT_FILTER_CONFIGFILTER_H */
