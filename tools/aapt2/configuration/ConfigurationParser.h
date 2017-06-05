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

#include <string>
#include <unordered_map>
#include <vector>
#include <ConfigDescription.h>

#include "util/Maybe.h"

namespace aapt {

namespace configuration {

/** A mapping of group labels to group of configuration items. */
template<class T>
using Group = std::unordered_map<std::string, std::vector<T>>;

/** Output artifact configuration options. */
struct Artifact {
  /** Name to use for output of processing foo.apk -> foo.<name>.apk. */
  std::string name;
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
};

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

/**
 * Represents an individual locale. When a locale is included, it must be
 * declared from least specific to most specific, as a region does not make
 * sense without a language. If neither the language or region are specified it
 * acts as a special case for catch all. This can allow all locales to be kept,
 * or compressed.
 */
struct Locale {
  /** The ISO<?> standard locale language code. */
  Maybe<std::string> lang;
  /** The ISO<?> standard locale region code. */
  Maybe<std::string> region;

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
  Maybe<std::string> min_sdk_version;
  Maybe<std::string> target_sdk_version;
  Maybe<std::string> max_sdk_version;
  Maybe<AndroidManifest> manifest;

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

/**
 * AAPT2 XML configuration binary representation.
 */
struct Configuration {
  std::unordered_map<std::string, Artifact> artifacts;
  Maybe<std::string> artifact_format;

  Group<Abi> abi_groups;
  Group<ConfigDescription> screen_density_groups;
  Group<Locale> locale_groups;
  Group<AndroidSdk> android_sdk_groups;
  Group<DeviceFeature> device_feature_groups;
  Group<GlTexture> gl_texture_groups;
};

}  // namespace configuration

// Forward declaration of classes used in the API.
struct IDiagnostics;
namespace xml {
class Element;
}

/**
 * XML configuration file parser for the split and optimize commands.
 */
class ConfigurationParser {
 public:
  /** Returns a ConfigurationParser for the configuration in the provided file contents. */
  static ConfigurationParser ForContents(const std::string& contents) {
    ConfigurationParser parser{contents};
    return parser;
  }

  /** Returns a ConfigurationParser for the file located at the provided path. */
  static ConfigurationParser ForPath(const std::string& path) {
    // TODO: Read XML file into memory.
    return ForContents(path);
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
  Maybe<configuration::Configuration> Parse();

 protected:
  /**
   * Instantiates a new ConfigurationParser with the provided configuration file and a no-op
   * diagnostics context. The default diagnostics context can be overridden with a call to
   * WithDiagnostics(IDiagnostics *).
   */
  explicit ConfigurationParser(std::string contents);

  /** Returns the current diagnostics context to any subclasses. */
  IDiagnostics* diagnostics() {
    return diag_;
  }

  /**
   * An ActionHandler for processing XML elements in the XmlActionExecutor. Returns true if the
   * element was successfully processed, otherwise returns false.
   */
  using ActionHandler = std::function<bool(configuration::Configuration* config,
                                           xml::Element* element,
                                           IDiagnostics* diag)>;

  /** Handler for <artifact> tags. */
  static ActionHandler artifact_handler_;
  /** Handler for <artifact-format> tags. */
  static ActionHandler artifact_format_handler_;
  /** Handler for <abi-group> tags. */
  static ActionHandler abi_group_handler_;
  /** Handler for <screen-density-group> tags. */
  static ActionHandler screen_density_group_handler_;
  /** Handler for <locale-group> tags. */
  static ActionHandler locale_group_handler_;
  /** Handler for <android-sdk-group> tags. */
  static ActionHandler android_sdk_group_handler_;
  /** Handler for <gl-texture-group> tags. */
  static ActionHandler gl_texture_group_handler_;
  /** Handler for <device-feature-group> tags. */
  static ActionHandler device_feature_group_handler_;

 private:
  /** The contents of the configuration file to parse. */
  const std::string contents_;
  /** The diagnostics context to send messages to. */
  IDiagnostics* diag_;
};

}  // namespace aapt

#endif //AAPT2_CONFIGURATION_H
