/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef AAPT_SPLIT_UTIL_H
#define AAPT_SPLIT_UTIL_H

#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <regex>
#include <set>
#include <string>
#include <unordered_set>

#include "AppInfo.h"
#include "SdkConstants.h"
#include "androidfw/IDiagnostics.h"
#include "androidfw/StringPiece.h"
#include "filter/ConfigFilter.h"
#include "process/IResourceTableConsumer.h"
#include "split/TableSplitter.h"
#include "xml/XmlDom.h"

namespace aapt {

struct FeatureFlagProperties {
  bool read_only;
  std::optional<bool> enabled;

  FeatureFlagProperties(bool ro, std::optional<bool> e) : read_only(ro), enabled(e) {
  }

  bool operator==(const FeatureFlagProperties&) const = default;
};

using FeatureFlagValues = std::map<std::string, FeatureFlagProperties, std::less<>>;

// Parses a configuration density (ex. hdpi, xxhdpi, 234dpi, anydpi, etc).
// Returns Nothing and logs a human friendly error message if the string was not legal.
std::optional<uint16_t> ParseTargetDensityParameter(android::StringPiece arg,
                                                    android::IDiagnostics* diag);

// Parses a string of the form 'path/to/output.apk:<config>[,<config>...]' and fills in
// `out_path` with the path and `out_split` with the set of ConfigDescriptions.
// Returns false and logs a human friendly error message if the string was not legal.
bool ParseSplitParameter(android::StringPiece arg, android::IDiagnostics* diag,
                         std::string* out_path, SplitConstraints* out_split);

// Parses a set of config filter strings of the form 'en,fr-rFR' and returns an IConfigFilter.
// Returns nullptr and logs a human friendly error message if the string was not legal.
std::unique_ptr<IConfigFilter> ParseConfigFilterParameters(const std::vector<std::string>& args,
                                                           android::IDiagnostics* diag);

// Parses a feature flags parameter, which can contain one or more pairs of flag names and optional
// values, and fills in `out_feature_flag_values` with the parsed values. The pairs in the argument
// are separated by ',' and the name is separated from the value by '=' if there is a value given.
// Example arg: "flag1=true,flag2=false,flag3=,flag4" where flag3 and flag4 have no given value.
bool ParseFeatureFlagsParameter(android::StringPiece arg, android::IDiagnostics* diag,
                                FeatureFlagValues* out_feature_flag_values);

// Adjust the SplitConstraints so that their SDK version is stripped if it
// is less than or equal to the min_sdk. Otherwise the resources that have had
// their SDK version stripped due to min_sdk won't ever match.
std::vector<SplitConstraints> AdjustSplitConstraintsForMinSdk(
    int min_sdk, const std::vector<SplitConstraints>& split_constraints);

// Generates a split AndroidManifest.xml given the split constraints and app info. The resulting
// XmlResource does not need to be linked via XmlReferenceLinker.
// This will never fail/return nullptr.
std::unique_ptr<xml::XmlResource> GenerateSplitManifest(const AppInfo& app_info,
                                                        const SplitConstraints& constraints);

// Extracts relevant info from the AndroidManifest.xml.
std::optional<AppInfo> ExtractAppInfoFromBinaryManifest(const xml::XmlResource& xml_res,
                                                        android::IDiagnostics* diag);

// Returns a copy of 'name' which conforms to the regex '[a-zA-Z]+[a-zA-Z0-9_]*' by
// replacing nonconforming characters with underscores.
//
// See frameworks/base/core/java/android/content/pm/PackageParser.java which
// checks this at runtime.
std::string MakePackageSafeName(const std::string &name);

// Sets the versionCode and versionCodeMajor attributes to the version code. Attempts to encode the
// version code using the versionCode attribute only, and encodes using both versionCode and
// versionCodeMajor if the version code requires more than 32 bits.
void SetLongVersionCode(xml::Element* manifest, uint64_t version_code);

// Returns a case insensitive regular expression based on the input.
std::regex GetRegularExpression(const std::string &input);

bool ParseResourceConfig(const std::string& content, IAaptContext* context,
                         std::unordered_set<ResourceName>& out_resource_exclude_list,
                         std::set<ResourceName>& out_name_collapse_exemptions,
                         std::set<ResourceName>& out_path_shorten_exemptions);

}  // namespace aapt

#endif /* AAPT_SPLIT_UTIL_H */
