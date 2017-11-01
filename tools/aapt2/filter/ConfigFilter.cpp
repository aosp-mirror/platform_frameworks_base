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

#include "filter/ConfigFilter.h"

#include "androidfw/ResourceTypes.h"

#include "ConfigDescription.h"

namespace aapt {

void AxisConfigFilter::AddConfig(ConfigDescription config) {
  uint32_t diff_mask = ConfigDescription::DefaultConfig().diff(config);

  // Ignore the version
  diff_mask &= ~android::ResTable_config::CONFIG_VERSION;

  // Ignore any densities. Those are best handled in --preferred-density
  if ((diff_mask & android::ResTable_config::CONFIG_DENSITY) != 0) {
    config.density = 0;
    diff_mask &= ~android::ResTable_config::CONFIG_DENSITY;
  }

  configs_.insert(std::make_pair(config, diff_mask));
  config_mask_ |= diff_mask;
}

// Returns true if the locale script of the config should be considered matching
// the locale script of entry.
//
// If both the scripts are empty, the scripts are considered matching for
// backward compatibility reasons.
//
// If only one script is empty, we try to compute it based on the provided
// language and country. If we could not compute it, we assume it's either a
// new language we don't know about, or a private use language. We return true
// since we don't know any better and they might as well be a match.
//
// Finally, when we have two scripts (one of which could be computed), we return
// true if and only if they are an exact match.
static bool ScriptsMatch(const ConfigDescription& config, const ConfigDescription& entry) {
  const char* config_script = config.localeScript;
  const char* entry_script = entry.localeScript;
  if (config_script[0] == '\0' && entry_script[0] == '\0') {
    return true;  // both scripts are empty. We match for backward compatibility reasons.
  }

  char script_buffer[sizeof(config.localeScript)];
  if (config_script[0] == '\0') {
    android::localeDataComputeScript(script_buffer, config.language, config.country);
    if (script_buffer[0] == '\0') {  // We can't compute the script, so we match.
      return true;
    }
    config_script = script_buffer;
  } else if (entry_script[0] == '\0') {
    android::localeDataComputeScript(script_buffer, entry.language, entry.country);
    if (script_buffer[0] == '\0') {  // We can't compute the script, so we match.
      return true;
    }
    entry_script = script_buffer;
  }
  return memcmp(config_script, entry_script, sizeof(config.localeScript)) == 0;
}

bool AxisConfigFilter::Match(const ConfigDescription& config) const {
  const uint32_t mask = ConfigDescription::DefaultConfig().diff(config);
  if ((config_mask_ & mask) == 0) {
    // The two configurations don't have any common axis.
    return true;
  }

  uint32_t matched_axis = 0;
  for (const auto& entry : configs_) {
    const ConfigDescription& target = entry.first;
    const uint32_t diff_mask = entry.second;
    uint32_t diff = target.diff(config);
    if ((diff & diff_mask) == 0) {
      // Mark the axis that was matched.
      matched_axis |= diff_mask;
    } else if ((diff & diff_mask) == android::ResTable_config::CONFIG_LOCALE) {
      // If the locales differ, but the languages are the same and
      // the locale we are matching only has a language specified,
      // we match.
      //
      // Exception: we won't match if a script is specified for at least
      // one of the locales and it's different from the other locale's
      // script. (We will compute the other script if at least one of the
      // scripts were explicitly set. In cases we can't compute an script,
      // we match.)
      if (config.language[0] != '\0' && config.country[0] == '\0' &&
          config.localeVariant[0] == '\0' && config.language[0] == entry.first.language[0] &&
          config.language[1] == entry.first.language[1] && ScriptsMatch(config, entry.first)) {
        matched_axis |= android::ResTable_config::CONFIG_LOCALE;
      }
    } else if ((diff & diff_mask) ==
               android::ResTable_config::CONFIG_SMALLEST_SCREEN_SIZE) {
      // Special case if the smallest screen width doesn't match. We check that
      // the
      // config being matched has a smaller screen width than the filter
      // specified.
      if (config.smallestScreenWidthDp != 0 &&
          config.smallestScreenWidthDp < target.smallestScreenWidthDp) {
        matched_axis |= android::ResTable_config::CONFIG_SMALLEST_SCREEN_SIZE;
      }
    }
  }
  return matched_axis == (config_mask_ & mask);
}

}  // namespace aapt
