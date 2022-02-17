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

#ifndef AAPT2_CONFIGURATION_H
#define AAPT2_CONFIGURATION_H

#include <optional>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

#include "androidfw/ConfigDescription.h"

#include "Diagnostics.h"

namespace aapt {

namespace configuration {

/** Enumeration of currently supported ABIs. */
enum class Abi {
  kArmeV6,
  kArmV7a,
  kArm64V8a,
  kX86,
  kX86_64,
  kMips,
  kMips64,
  kUniversal
};

/** Helper method to convert an ABI to a string representing the path within the APK. */
const android::StringPiece& AbiToString(Abi abi);

/**
 * Represents an individual locale. When a locale is included, it must be
 * declared from least specific to most specific, as a region does not make
 * sense without a language. If neither the language or region are specified it
 * acts as a special case for catch all. This can allow all locales to be kept,
 * or compressed.
 */
struct Locale {
  /** The ISO<?> standard locale language code. */
  std::optional<std::string> lang;
  /** The ISO<?> standard locale region code. */
  std::optional<std::string> region;

  inline friend bool operator==(const Locale& lhs, const Locale& rhs) {
    return lhs.lang == rhs.lang && lhs.region == rhs.region;
  }
};

// TODO: Encapsulate manifest modifications from the configuration file.
struct AndroidManifest {
  inline friend bool operator==(const AndroidManifest& lhs, const AndroidManifest& rhs) {
    return true;  // nothing to compare yet.
  }
};

struct AndroidSdk {
  std::string label;
  int min_sdk_version;  // min_sdk_version is mandatory if splitting by SDK.
  std::optional<int> target_sdk_version;
  std::optional<int> max_sdk_version;
  std::optional<AndroidManifest> manifest;

  static AndroidSdk ForMinSdk(int min_sdk) {
    AndroidSdk sdk;
    sdk.min_sdk_version = min_sdk;
    return sdk;
  }

  inline friend bool operator==(const AndroidSdk& lhs, const AndroidSdk& rhs) {
    return lhs.min_sdk_version == rhs.min_sdk_version &&
        lhs.target_sdk_version == rhs.target_sdk_version &&
        lhs.max_sdk_version == rhs.max_sdk_version &&
        lhs.manifest == rhs.manifest;
  }
};

// TODO: Make device features more than just an arbitrary string?
using DeviceFeature = std::string;

/** Represents a mapping of texture paths to a GL texture format. */
struct GlTexture {
  std::string name;
  std::vector<std::string> texture_paths;

  inline friend bool operator==(const GlTexture& lhs, const GlTexture& rhs) {
    return lhs.name == rhs.name && lhs.texture_paths == rhs.texture_paths;
  }
};

/** An artifact with all the details pulled from the PostProcessingConfiguration. */
struct OutputArtifact {
  std::string name;
  int version;
  std::vector<Abi> abis;
  std::vector<android::ConfigDescription> screen_densities;
  std::vector<android::ConfigDescription> locales;
  std::optional<AndroidSdk> android_sdk;
  std::vector<DeviceFeature> features;
  std::vector<GlTexture> textures;

  inline int GetMinSdk(int default_value = -1) const {
    if (!android_sdk) {
      return default_value;
    }
    return android_sdk.value().min_sdk_version;
  }
};

}  // namespace configuration

// Forward declaration of classes used in the API.
struct IDiagnostics;

/**
 * XML configuration file parser for the split and optimize commands.
 */
class ConfigurationParser {
 public:

  /** Returns a ConfigurationParser for the file located at the provided path. */
  static std::optional<ConfigurationParser> ForPath(const std::string& path);

  /** Returns a ConfigurationParser for the configuration in the provided file contents. */
  static ConfigurationParser ForContents(const std::string& contents, const std::string& path) {
    ConfigurationParser parser{contents, path};
    return parser;
  }

  /** Sets the diagnostics context to use when parsing. */
  ConfigurationParser& WithDiagnostics(IDiagnostics* diagnostics) {
    diag_ = diagnostics;
    return *this;
  }

  /**
   * Parses the configuration file and returns the results. If the configuration could not be parsed
   * the result is empty and any errors will be displayed with the provided diagnostics context.
   */
  std::optional<std::vector<configuration::OutputArtifact>> Parse(
      const android::StringPiece& apk_path);

 protected:
  /**
   * Instantiates a new ConfigurationParser with the provided configuration file and a no-op
   * diagnostics context. The default diagnostics context can be overridden with a call to
   * WithDiagnostics(IDiagnostics *).
   */
  ConfigurationParser(std::string contents, const std::string& config_path);

  /** Returns the current diagnostics context to any subclasses. */
  IDiagnostics* diagnostics() {
    return diag_;
  }

 private:
  /** The contents of the configuration file to parse. */
  const std::string contents_;
  /** Path to the input configuration. */
  const std::string config_path_;
  /** The diagnostics context to send messages to. */
  IDiagnostics* diag_;
};

}  // namespace aapt

#endif  // AAPT2_CONFIGURATION_H
