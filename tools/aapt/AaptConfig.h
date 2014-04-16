/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef __AAPT_CONFIG_H
#define __AAPT_CONFIG_H

#include <set>
#include <utils/String8.h>

#include "ConfigDescription.h"

/**
 * Utility methods for dealing with configurations.
 */
namespace AaptConfig {

/**
 * Parse a string of the form 'fr-sw600dp-land' and fill in the
 * given ResTable_config with resulting configuration parameters.
 *
 * The resulting configuration has the appropriate sdkVersion defined
 * for backwards compatibility.
 */
bool parse(const android::String8& str, ConfigDescription* out = NULL);

/**
 * Parse a comma separated list of configuration strings. Duplicate configurations
 * will be removed.
 *
 * Example input: "fr,de-land,fr-sw600dp-land"
 */
bool parseCommaSeparatedList(const android::String8& str, std::set<ConfigDescription>* outSet);

/**
 * If the configuration uses an axis that was added after
 * the original Android release, make sure the SDK version
 * is set accordingly.
 */
void applyVersionForCompatibility(ConfigDescription* config);

// Individual axis
bool parseMcc(const char* str, android::ResTable_config* out = NULL);
bool parseMnc(const char* str, android::ResTable_config* out = NULL);
bool parseLayoutDirection(const char* str, android::ResTable_config* out = NULL);
bool parseSmallestScreenWidthDp(const char* str, android::ResTable_config* out = NULL);
bool parseScreenWidthDp(const char* str, android::ResTable_config* out = NULL);
bool parseScreenHeightDp(const char* str, android::ResTable_config* out = NULL);
bool parseScreenLayoutSize(const char* str, android::ResTable_config* out = NULL);
bool parseScreenLayoutLong(const char* str, android::ResTable_config* out = NULL);
bool parseOrientation(const char* str, android::ResTable_config* out = NULL);
bool parseUiModeType(const char* str, android::ResTable_config* out = NULL);
bool parseUiModeNight(const char* str, android::ResTable_config* out = NULL);
bool parseDensity(const char* str, android::ResTable_config* out = NULL);
bool parseTouchscreen(const char* str, android::ResTable_config* out = NULL);
bool parseKeysHidden(const char* str, android::ResTable_config* out = NULL);
bool parseKeyboard(const char* str, android::ResTable_config* out = NULL);
bool parseNavHidden(const char* str, android::ResTable_config* out = NULL);
bool parseNavigation(const char* str, android::ResTable_config* out = NULL);
bool parseScreenSize(const char* str, android::ResTable_config* out = NULL);
bool parseVersion(const char* str, android::ResTable_config* out = NULL);

android::String8 getVersion(const android::ResTable_config& config);

/**
 * Returns true if the two configurations only differ by the specified axis.
 * The axis mask is a bitmask of CONFIG_* constants.
 */
bool isSameExcept(const android::ResTable_config& a, const android::ResTable_config& b, int configMask);

} // namespace AaptConfig

#endif // __AAPT_CONFIG_H
