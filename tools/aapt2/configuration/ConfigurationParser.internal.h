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

#ifndef AAPT2_CONFIGURATIONPARSER_INTERNAL_H
#define AAPT2_CONFIGURATIONPARSER_INTERNAL_H

#include "androidfw/ConfigDescription.h"

#include "configuration/ConfigurationParser.h"

#include <algorithm>
#include <limits>

namespace aapt {

// Forward declaration of classes used in the API.
namespace xml {
class Element;
}

namespace configuration {

template <typename T>
struct OrderedEntry {
  int32_t order;
  std::vector<T> entry;
};

/** A mapping of group label to a single configuration item. */
template <class T>
using Entry = std::unordered_map<std::string, T>;

/** A mapping of group labels to group of configuration items. */
template <class T>
using Group = Entry<OrderedEntry<T>>;

template<typename T>
bool IsGroupValid(const Group<T>& group, const std::string& name, IDiagnostics* diag) {
  std::set<int32_t> orders;
  for (const auto& p : group) {
    orders.insert(p.second.order);
  }
  bool valid = orders.size() == group.size();
  if (!valid) {
    diag->Error(DiagMessage() << name << " have overlapping version-code-order attributes");
  }
  return valid;
}

/** Retrieves an entry from the provided Group, creating a new instance if one does not exist. */
template <typename T>
std::vector<T>& GetOrCreateGroup(std::string label, Group<T>* group) {
  OrderedEntry<T>& entry = (*group)[label];
  // If this is a new entry, set the order.
  if (entry.order == 0) {
    entry.order = group->size();
  }
  return entry.entry;
}

/**
 * A ComparisonChain is a grouping of comparisons to perform when sorting groups that have a well
 * defined order of precedence. Comparisons are only made if none of the previous comparisons had a
 * definite result. A comparison has a result if at least one of the items has an entry for that
 * value and that they are not equal.
 */
class ComparisonChain {
 public:
  /**
   * Adds a new comparison of items in a group to the chain. The new comparison is only used if we
   * have not been able to determine the sort order with the previous comparisons.
   */
  template <typename T>
  ComparisonChain& Add(const Group<T>& groups, const std::optional<std::string>& lhs,
                       const std::optional<std::string>& rhs) {
    return Add(GetGroupOrder(groups, lhs), GetGroupOrder(groups, rhs));
  }

  /**
   * Adds a new comparison to the chain. The new comparison is only used if we have not been able to
   * determine the sort order with the previous comparisons.
   */
  ComparisonChain& Add(int lhs, int rhs) {
    if (!has_result_) {
      has_result_ = (lhs != rhs);
      result_ = (lhs < rhs);
    }
    return *this;
  }

  /** Returns true if the left hand side should come before the right hand side. */
  bool Compare() {
    return result_;
  }

 private:
  template <typename T>
  inline size_t GetGroupOrder(const Entry<T>& groups, const std::optional<std::string>& label) {
    if (!label) {
      return std::numeric_limits<size_t>::max();
    }
    return groups.at(label.value()).order;
  }

  bool has_result_ = false;
  bool result_ = false;
};

/** Output artifact configuration options. */
struct ConfiguredArtifact {
  /** Name to use for output of processing foo.apk -> foo.<name>.apk. */
  std::optional<std::string> name;
  /** If present, uses the ABI group with this name. */
  std::optional<std::string> abi_group;
  /** If present, uses the screen density group with this name. */
  std::optional<std::string> screen_density_group;
  /** If present, uses the locale group with this name. */
  std::optional<std::string> locale_group;
  /** If present, uses the Android SDK with this name. */
  std::optional<std::string> android_sdk;
  /** If present, uses the device feature group with this name. */
  std::optional<std::string> device_feature_group;
  /** If present, uses the OpenGL texture group with this name. */
  std::optional<std::string> gl_texture_group;

  /** Convert an artifact name template into a name string based on configuration contents. */
  std::optional<std::string> ToArtifactName(const android::StringPiece& format,
                                            const android::StringPiece& apk_name,
                                            IDiagnostics* diag) const;

  /** Convert an artifact name template into a name string based on configuration contents. */
  std::optional<std::string> Name(const android::StringPiece& apk_name, IDiagnostics* diag) const;
};

/** AAPT2 XML configuration file binary representation. */
struct PostProcessingConfiguration {
  std::vector<ConfiguredArtifact> artifacts;
  std::optional<std::string> artifact_format;

  Group<Abi> abi_groups;
  Group<android::ConfigDescription> screen_density_groups;
  Group<android::ConfigDescription> locale_groups;
  Group<DeviceFeature> device_feature_groups;
  Group<GlTexture> gl_texture_groups;
  Entry<AndroidSdk> android_sdks;

  bool ValidateVersionCodeOrdering(IDiagnostics* diag) {
    bool valid = IsGroupValid(abi_groups, "abi-groups", diag);
    valid &= IsGroupValid(screen_density_groups, "screen-density-groups", diag);
    valid &= IsGroupValid(locale_groups, "locale-groups", diag);
    valid &= IsGroupValid(device_feature_groups, "device-feature-groups", diag);
    valid &= IsGroupValid(gl_texture_groups, "gl-texture-groups", diag);
    return valid;
  }

  /**
   * Sorts the configured artifacts based on the ordering of the groups in the configuration file.
   * The only exception to this rule is Android SDK versions. Larger SDK versions will have a larger
   * versionCode to ensure users get the correct APK when they upgrade their OS.
   */
  void SortArtifacts() {
    std::sort(artifacts.begin(), artifacts.end(), *this);
  }

  /** Comparator that ensures artifacts are in the preferred order for versionCode rewriting. */
  bool operator()(const ConfiguredArtifact& lhs, const ConfiguredArtifact& rhs) {
    // Split dimensions are added in the order of precedence. Items higher in the list result in
    // higher version codes.
    return ComparisonChain()
        // All splits with a minSdkVersion specified must be last to ensure the application will be
        // updated if a user upgrades the version of Android on their device.
        .Add(GetMinSdk(lhs), GetMinSdk(rhs))
        // ABI version is important, especially on x86 phones where they may begin to run in ARM
        // emulation mode on newer Android versions. This allows us to ensure that the x86 version
        // is installed on these devices rather than ARM.
        .Add(abi_groups, lhs.abi_group, rhs.abi_group)
        // The rest are in arbitrary order based on estimated usage.
        .Add(screen_density_groups, lhs.screen_density_group, rhs.screen_density_group)
        .Add(locale_groups, lhs.locale_group, rhs.locale_group)
        .Add(gl_texture_groups, lhs.gl_texture_group, rhs.gl_texture_group)
        .Add(device_feature_groups, lhs.device_feature_group, rhs.device_feature_group)
        .Compare();
  }

 private:
  /**
   * Returns the min_sdk_version from the provided artifact or 0 if none is present. This allows
   * artifacts that have an Android SDK version to have a higher versionCode than those that do not.
   */
  inline int GetMinSdk(const ConfiguredArtifact& artifact) {
    if (!artifact.android_sdk) {
      return 0;
    }
    const auto& entry = android_sdks.find(artifact.android_sdk.value());
    if (entry == android_sdks.end()) {
      return 0;
    }
    return entry->second.min_sdk_version;
  }
};

/** Parses the provided XML document returning the post processing configuration. */
std::optional<PostProcessingConfiguration> ExtractConfiguration(const std::string& contents,
                                                                const std::string& config_path,
                                                                IDiagnostics* diag);

namespace handler {

/** Handler for <artifact> tags. */
bool ArtifactTagHandler(configuration::PostProcessingConfiguration* config, xml::Element* element,
                        IDiagnostics* diag);

/** Handler for <artifact-format> tags. */
bool ArtifactFormatTagHandler(configuration::PostProcessingConfiguration* config,
                              xml::Element* element, IDiagnostics* diag);

/** Handler for <abi-group> tags. */
bool AbiGroupTagHandler(configuration::PostProcessingConfiguration* config, xml::Element* element,
                        IDiagnostics* diag);

/** Handler for <screen-density-group> tags. */
bool ScreenDensityGroupTagHandler(configuration::PostProcessingConfiguration* config,
                                  xml::Element* element, IDiagnostics* diag);

/** Handler for <locale-group> tags. */
bool LocaleGroupTagHandler(configuration::PostProcessingConfiguration* config,
                           xml::Element* element, IDiagnostics* diag);

/** Handler for <android-sdk> tags. */
bool AndroidSdkTagHandler(configuration::PostProcessingConfiguration* config, xml::Element* element,
                          IDiagnostics* diag);

/** Handler for <gl-texture-group> tags. */
bool GlTextureGroupTagHandler(configuration::PostProcessingConfiguration* config,
                              xml::Element* element, IDiagnostics* diag);

/** Handler for <device-feature-group> tags. */
bool DeviceFeatureGroupTagHandler(configuration::PostProcessingConfiguration* config,
                                  xml::Element* element, IDiagnostics* diag);

}  // namespace handler
}  // namespace configuration
}  // namespace aapt
#endif  // AAPT2_CONFIGURATIONPARSER_INTERNAL_H
