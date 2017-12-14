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

namespace aapt {
namespace configuration {

/** A mapping of group labels to group of configuration items. */
template <class T>
using Group = std::unordered_map<std::string, std::vector<T>>;

/** A mapping of group label to a single configuration item. */
template <class T>
using Entry = std::unordered_map<std::string, T>;

/** Output artifact configuration options. */
struct ConfiguredArtifact {
  /** Name to use for output of processing foo.apk -> foo.<name>.apk. */
  Maybe<std::string> name;
  /**
   * Value to add to the base Android manifest versionCode. If it is not present in the
   * configuration file, it is set to the previous artifact + 1. If the first artifact does not have
   * a value, artifacts are a 1 based index.
   */
  int version;
  /** If present, uses the ABI group with this name. */
  Maybe<std::string> abi_group;
  /** If present, uses the screen density group with this name. */
  Maybe<std::string> screen_density_group;
  /** If present, uses the locale group with this name. */
  Maybe<std::string> locale_group;
  /** If present, uses the Android SDK group with this name. */
  Maybe<std::string> android_sdk_group;
  /** If present, uses the device feature group with this name. */
  Maybe<std::string> device_feature_group;
  /** If present, uses the OpenGL texture group with this name. */
  Maybe<std::string> gl_texture_group;

  /** Convert an artifact name template into a name string based on configuration contents. */
  Maybe<std::string> ToArtifactName(const android::StringPiece& format,
                                    const android::StringPiece& apk_name, IDiagnostics* diag) const;

  /** Convert an artifact name template into a name string based on configuration contents. */
  Maybe<std::string> Name(const android::StringPiece& apk_name, IDiagnostics* diag) const;

  bool operator<(const ConfiguredArtifact& rhs) const {
    // TODO(safarmer): Order by play store multi-APK requirements.
    return version < rhs.version;
  }

  bool operator==(const ConfiguredArtifact& rhs) const {
    return version == rhs.version;
  }
};

/** AAPT2 XML configuration file binary representation. */
struct PostProcessingConfiguration {
  // TODO: Support named artifacts?
  std::vector<ConfiguredArtifact> artifacts;
  Maybe<std::string> artifact_format;

  Group<Abi> abi_groups;
  Group<ConfigDescription> screen_density_groups;
  Group<ConfigDescription> locale_groups;
  Entry<AndroidSdk> android_sdk_groups;
  Group<DeviceFeature> device_feature_groups;
  Group<GlTexture> gl_texture_groups;
};

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

/** Handler for <android-sdk-group> tags. */
bool AndroidSdkGroupTagHandler(configuration::PostProcessingConfiguration* config,
                               xml::Element* element, IDiagnostics* diag);

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
