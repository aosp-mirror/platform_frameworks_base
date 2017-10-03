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

#include "configuration/ConfigurationParser.h"

#include <algorithm>
#include <functional>
#include <map>
#include <memory>
#include <utility>

#include "android-base/file.h"
#include "android-base/logging.h"

#include "ConfigDescription.h"
#include "Diagnostics.h"
#include "ResourceUtils.h"
#include "io/File.h"
#include "io/FileSystem.h"
#include "io/StringInputStream.h"
#include "util/Files.h"
#include "util/Maybe.h"
#include "util/Util.h"
#include "xml/XmlActionExecutor.h"
#include "xml/XmlDom.h"
#include "xml/XmlUtil.h"

namespace aapt {

namespace {

using ::aapt::configuration::Abi;
using ::aapt::configuration::AndroidManifest;
using ::aapt::configuration::AndroidSdk;
using ::aapt::configuration::Artifact;
using ::aapt::configuration::PostProcessingConfiguration;
using ::aapt::configuration::GlTexture;
using ::aapt::configuration::Group;
using ::aapt::configuration::Locale;
using ::aapt::io::IFile;
using ::aapt::io::RegularFile;
using ::aapt::io::StringInputStream;
using ::aapt::util::TrimWhitespace;
using ::aapt::xml::Element;
using ::aapt::xml::NodeCast;
using ::aapt::xml::XmlActionExecutor;
using ::aapt::xml::XmlActionExecutorPolicy;
using ::aapt::xml::XmlNodeAction;
using ::android::base::ReadFileToString;
using ::android::StringPiece;

const std::unordered_map<std::string, Abi> kStringToAbiMap = {
    {"armeabi", Abi::kArmeV6}, {"armeabi-v7a", Abi::kArmV7a},  {"arm64-v8a", Abi::kArm64V8a},
    {"x86", Abi::kX86},        {"x86_64", Abi::kX86_64},       {"mips", Abi::kMips},
    {"mips64", Abi::kMips64},  {"universal", Abi::kUniversal},
};
const std::map<Abi, std::string> kAbiToStringMap = {
    {Abi::kArmeV6, "armeabi"}, {Abi::kArmV7a, "armeabi-v7a"},  {Abi::kArm64V8a, "arm64-v8a"},
    {Abi::kX86, "x86"},        {Abi::kX86_64, "x86_64"},       {Abi::kMips, "mips"},
    {Abi::kMips64, "mips64"},  {Abi::kUniversal, "universal"},
};

constexpr const char* kAaptXmlNs = "http://schemas.android.com/tools/aapt";

/** A default noop diagnostics context. */
class NoopDiagnostics : public IDiagnostics {
 public:
  void Log(Level level, DiagMessageActual& actualMsg) override {}
};
NoopDiagnostics noop_;

std::string GetLabel(const Element* element, IDiagnostics* diag) {
  std::string label;
  for (const auto& attr : element->attributes) {
    if (attr.name == "label") {
      label = attr.value;
      break;
    }
  }

  if (label.empty()) {
    diag->Error(DiagMessage() << "No label found for element " << element->name);
  }
  return label;
}

/** XML node visitor that removes all of the namespace URIs from the node and all children. */
class NamespaceVisitor : public xml::Visitor {
 public:
  void Visit(xml::Element* node) override {
    node->namespace_uri.clear();
    VisitChildren(node);
  }
};

}  // namespace

namespace configuration {

const std::string& AbiToString(Abi abi) {
  return kAbiToStringMap.find(abi)->second;
}

/**
 * Attempts to replace the placeholder in the name string with the provided value. Returns true on
 * success, or false if the either the placeholder is not found in the name, or the value is not
 * present and the placeholder was.
 */
static bool ReplacePlaceholder(const StringPiece& placeholder, const Maybe<StringPiece>& value,
                               std::string* name, IDiagnostics* diag) {
  size_t offset = name->find(placeholder.data());
  bool found = (offset != std::string::npos);

  // Make sure the placeholder was present if the desired value is present.
  if (!found) {
    if (value) {
      diag->Error(DiagMessage() << "Missing placeholder for artifact: " << placeholder);
      return false;
    }
    return true;
  }

  DCHECK(found) << "Missing return path for placeholder not found";

  // Make sure the placeholder was not present if the desired value was not present.
  if (!value) {
    diag->Error(DiagMessage() << "Placeholder present but no value for artifact: " << placeholder);
    return false;
  }

  name->replace(offset, placeholder.length(), value.value().data());

  // Make sure there was only one instance of the placeholder.
  if (name->find(placeholder.data()) != std::string::npos) {
    diag->Error(DiagMessage() << "Placeholder present multiple times: " << placeholder);
    return false;
  }
  return true;
}

/**
 * Returns the common artifact base name from a template string.
 */
Maybe<std::string> ToBaseName(std::string result, const StringPiece& apk_name, IDiagnostics* diag) {
  const StringPiece ext = file::GetExtension(apk_name);
  size_t end_index = apk_name.to_string().rfind(ext.to_string());
  const std::string base_name =
      (end_index != std::string::npos) ? std::string{apk_name.begin(), end_index} : "";

  // Base name is optional.
  if (result.find("${basename}") != std::string::npos) {
    Maybe<StringPiece> maybe_base_name =
        base_name.empty() ? Maybe<StringPiece>{} : Maybe<StringPiece>{base_name};
    if (!ReplacePlaceholder("${basename}", maybe_base_name, &result, diag)) {
      return {};
    }
  }

  // Extension is optional.
  if (result.find("${ext}") != std::string::npos) {
    // Make sure we disregard the '.' in the extension when replacing the placeholder.
    if (!ReplacePlaceholder("${ext}", {ext.substr(1)}, &result, diag)) {
      return {};
    }
  } else {
    // If no extension is specified, and the name template does not end in the current extension,
    // add the existing extension.
    if (!util::EndsWith(result, ext)) {
      result.append(ext.to_string());
    }
  }

  return result;
}

Maybe<std::string> Artifact::ToArtifactName(const StringPiece& format, const StringPiece& apk_name,
                                            IDiagnostics* diag) const {
  Maybe<std::string> base = ToBaseName(format.to_string(), apk_name, diag);
  if (!base) {
    return {};
  }
  std::string result = std::move(base.value());

  if (!ReplacePlaceholder("${abi}", abi_group, &result, diag)) {
    return {};
  }

  if (!ReplacePlaceholder("${density}", screen_density_group, &result, diag)) {
    return {};
  }

  if (!ReplacePlaceholder("${locale}", locale_group, &result, diag)) {
    return {};
  }

  if (!ReplacePlaceholder("${sdk}", android_sdk_group, &result, diag)) {
    return {};
  }

  if (!ReplacePlaceholder("${feature}", device_feature_group, &result, diag)) {
    return {};
  }

  if (!ReplacePlaceholder("${gl}", gl_texture_group, &result, diag)) {
    return {};
  }

  return result;
}

Maybe<std::string> Artifact::Name(const StringPiece& apk_name, IDiagnostics* diag) const {
  if (!name) {
    return {};
  }

  return ToBaseName(name.value(), apk_name, diag);
}

bool PostProcessingConfiguration::AllArtifactNames(const StringPiece& apk_name,
                                                   std::vector<std::string>* artifact_names,
                                                   IDiagnostics* diag) const {
  for (const auto& artifact : artifacts) {
    Maybe<std::string> name;
    if (artifact.name) {
      name = artifact.Name(apk_name, diag);
    } else {
      if (!artifact_format) {
        diag->Error(DiagMessage() << "No global artifact template and an artifact name is missing");
        return false;
      }
      name = artifact.ToArtifactName(artifact_format.value(), apk_name, diag);
    }

    if (!name) {
      return false;
    }

    artifact_names->push_back(std::move(name.value()));
  }

  return true;
}

}  // namespace configuration

/** Returns a ConfigurationParser for the file located at the provided path. */
Maybe<ConfigurationParser> ConfigurationParser::ForPath(const std::string& path) {
  std::string contents;
  if (!ReadFileToString(path, &contents, true)) {
    return {};
  }
  return ConfigurationParser(contents);
}

ConfigurationParser::ConfigurationParser(std::string contents)
    : contents_(std::move(contents)),
      diag_(&noop_) {
}

Maybe<PostProcessingConfiguration> ConfigurationParser::Parse() {
  StringInputStream in(contents_);
  std::unique_ptr<xml::XmlResource> doc = xml::Inflate(&in, diag_, Source("config.xml"));
  if (!doc) {
    return {};
  }

  // Strip any namespaces from the XML as the XmlActionExecutor ignores anything with a namespace.
  Element* root = doc->root.get();
  if (root == nullptr) {
    diag_->Error(DiagMessage() << "Could not find the root element in the XML document");
    return {};
  }

  std::string& xml_ns = root->namespace_uri;
  if (!xml_ns.empty()) {
    if (xml_ns != kAaptXmlNs) {
      diag_->Error(DiagMessage() << "Unknown namespace found on root element: " << xml_ns);
      return {};
    }

    xml_ns.clear();
    NamespaceVisitor visitor;
    root->Accept(&visitor);
  }

  XmlActionExecutor executor;
  XmlNodeAction& root_action = executor["post-process"];
  XmlNodeAction& artifacts_action = root_action["artifacts"];
  XmlNodeAction& groups_action = root_action["groups"];

  PostProcessingConfiguration config;

  // Helper to bind a static method to an action handler in the DOM executor.
  auto bind_handler =
      [&config](std::function<bool(PostProcessingConfiguration*, Element*, IDiagnostics*)> h)
      -> XmlNodeAction::ActionFuncWithDiag {
    return std::bind(h, &config, std::placeholders::_1, std::placeholders::_2);
  };

  // Parse the artifact elements.
  artifacts_action["artifact"].Action(bind_handler(artifact_handler_));
  artifacts_action["artifact-format"].Action(bind_handler(artifact_format_handler_));

  // Parse the different configuration groups.
  groups_action["abi-group"].Action(bind_handler(abi_group_handler_));
  groups_action["screen-density-group"].Action(bind_handler(screen_density_group_handler_));
  groups_action["locale-group"].Action(bind_handler(locale_group_handler_));
  groups_action["android-sdk-group"].Action(bind_handler(android_sdk_group_handler_));
  groups_action["gl-texture-group"].Action(bind_handler(gl_texture_group_handler_));
  groups_action["device-feature-group"].Action(bind_handler(device_feature_group_handler_));

  if (!executor.Execute(XmlActionExecutorPolicy::kNone, diag_, doc.get())) {
    diag_->Error(DiagMessage() << "Could not process XML document");
    return {};
  }

  // TODO: Validate all references in the configuration are valid. It should be safe to assume from
  // this point on that any references from one section to another will be present.

  // TODO: Automatically arrange artifacts so that they match Play Store multi-APK requirements.
  // see: https://developer.android.com/google/play/publishing/multiple-apks.html
  //
  // For now, make sure the version codes are unique.
  std::vector<Artifact>& artifacts = config.artifacts;
  std::sort(artifacts.begin(), artifacts.end());
  if (std::adjacent_find(artifacts.begin(), artifacts.end()) != artifacts.end()) {
    diag_->Error(DiagMessage() << "Configuration has duplicate versions");
    return {};
  }

  return {config};
}

ConfigurationParser::ActionHandler ConfigurationParser::artifact_handler_ =
    [](PostProcessingConfiguration* config, Element* root_element, IDiagnostics* diag) -> bool {
  // This will be incremented later so the first version will always be different to the base APK.
  int current_version = (config->artifacts.empty()) ? 0 : config->artifacts.back().version;

  Artifact artifact{};
  Maybe<int> version;
  for (const auto& attr : root_element->attributes) {
    if (attr.name == "name") {
      artifact.name = attr.value;
    } else if (attr.name == "version") {
      version = std::stoi(attr.value);
    } else if (attr.name == "abi-group") {
      artifact.abi_group = {attr.value};
    } else if (attr.name == "screen-density-group") {
      artifact.screen_density_group = {attr.value};
    } else if (attr.name == "locale-group") {
      artifact.locale_group = {attr.value};
    } else if (attr.name == "android-sdk-group") {
      artifact.android_sdk_group = {attr.value};
    } else if (attr.name == "gl-texture-group") {
      artifact.gl_texture_group = {attr.value};
    } else if (attr.name == "device-feature-group") {
      artifact.device_feature_group = {attr.value};
    } else {
      diag->Note(DiagMessage() << "Unknown artifact attribute: " << attr.name << " = "
                               << attr.value);
    }
  }

  artifact.version = (version) ? version.value() : current_version + 1;

  config->artifacts.push_back(artifact);
  return true;
};

ConfigurationParser::ActionHandler ConfigurationParser::artifact_format_handler_ =
    [](PostProcessingConfiguration* config, Element* root_element, IDiagnostics* diag) -> bool {
  for (auto& node : root_element->children) {
    xml::Text* t;
    if ((t = NodeCast<xml::Text>(node.get())) != nullptr) {
      config->artifact_format = TrimWhitespace(t->text).to_string();
      break;
    }
  }
  return true;
};

ConfigurationParser::ActionHandler ConfigurationParser::abi_group_handler_ =
    [](PostProcessingConfiguration* config, Element* root_element, IDiagnostics* diag) -> bool {
  std::string label = GetLabel(root_element, diag);
  if (label.empty()) {
    return false;
  }

  auto& group = config->abi_groups[label];
  bool valid = true;

  for (auto* child : root_element->GetChildElements()) {
    if (child->name != "abi") {
      diag->Error(DiagMessage() << "Unexpected element in ABI group: " << child->name);
      valid = false;
    } else {
      for (auto& node : child->children) {
        xml::Text* t;
        if ((t = NodeCast<xml::Text>(node.get())) != nullptr) {
          group.push_back(kStringToAbiMap.at(TrimWhitespace(t->text).to_string()));
          break;
        }
      }
    }
  }

  return valid;
};

ConfigurationParser::ActionHandler ConfigurationParser::screen_density_group_handler_ =
    [](PostProcessingConfiguration* config, Element* root_element, IDiagnostics* diag) -> bool {
  std::string label = GetLabel(root_element, diag);
  if (label.empty()) {
    return false;
  }

  auto& group = config->screen_density_groups[label];
  bool valid = true;

  for (auto* child : root_element->GetChildElements()) {
    if (child->name != "screen-density") {
      diag->Error(DiagMessage() << "Unexpected root_element in screen density group: "
                                << child->name);
      valid = false;
    } else {
      for (auto& node : child->children) {
        xml::Text* t;
        if ((t = NodeCast<xml::Text>(node.get())) != nullptr) {
          ConfigDescription config_descriptor;
          const android::StringPiece& text = TrimWhitespace(t->text);
          bool parsed = ConfigDescription::Parse(text, &config_descriptor);
          if (parsed &&
              (config_descriptor.CopyWithoutSdkVersion().diff(ConfigDescription::DefaultConfig()) ==
               android::ResTable_config::CONFIG_DENSITY)) {
            // Copy the density with the minimum SDK version stripped out.
            group.push_back(config_descriptor.CopyWithoutSdkVersion());
          } else {
            diag->Error(DiagMessage()
                        << "Could not parse config descriptor for screen-density: " << text);
            valid = false;
          }
          break;
        }
      }
    }
  }

  return valid;
};

ConfigurationParser::ActionHandler ConfigurationParser::locale_group_handler_ =
    [](PostProcessingConfiguration* config, Element* root_element, IDiagnostics* diag) -> bool {
  std::string label = GetLabel(root_element, diag);
  if (label.empty()) {
    return false;
  }

  auto& group = config->locale_groups[label];
  bool valid = true;

  for (auto* child : root_element->GetChildElements()) {
    if (child->name != "locale") {
      diag->Error(DiagMessage() << "Unexpected root_element in screen density group: "
                                << child->name);
      valid = false;
    } else {
      for (auto& node : child->children) {
        xml::Text* t;
        if ((t = NodeCast<xml::Text>(node.get())) != nullptr) {
          ConfigDescription config_descriptor;
          const android::StringPiece& text = TrimWhitespace(t->text);
          bool parsed = ConfigDescription::Parse(text, &config_descriptor);
          if (parsed &&
              (config_descriptor.CopyWithoutSdkVersion().diff(ConfigDescription::DefaultConfig()) ==
               android::ResTable_config::CONFIG_LOCALE)) {
            // Copy the locale with the minimum SDK version stripped out.
            group.push_back(config_descriptor.CopyWithoutSdkVersion());
          } else {
            diag->Error(DiagMessage()
                        << "Could not parse config descriptor for screen-density: " << text);
            valid = false;
          }
          break;
        }
      }
    }
  }

  return valid;
};

ConfigurationParser::ActionHandler ConfigurationParser::android_sdk_group_handler_ =
    [](PostProcessingConfiguration* config, Element* root_element, IDiagnostics* diag) -> bool {
  std::string label = GetLabel(root_element, diag);
  if (label.empty()) {
    return false;
  }

  bool valid = true;
  bool found = false;

  for (auto* child : root_element->GetChildElements()) {
    if (child->name != "android-sdk") {
      diag->Error(DiagMessage() << "Unexpected root_element in ABI group: " << child->name);
      valid = false;
    } else {
      AndroidSdk entry;
      for (const auto& attr : child->attributes) {
        if (attr.name == "minSdkVersion") {
          entry.min_sdk_version = ResourceUtils::ParseSdkVersion(attr.value);
        } else if (attr.name == "targetSdkVersion") {
          entry.target_sdk_version = ResourceUtils::ParseSdkVersion(attr.value);
        } else if (attr.name == "maxSdkVersion") {
          entry.max_sdk_version = ResourceUtils::ParseSdkVersion(attr.value);
        } else {
          diag->Warn(DiagMessage() << "Unknown attribute: " << attr.name << " = " << attr.value);
        }
      }

      // TODO: Fill in the manifest details when they are finalised.
      for (auto node : child->GetChildElements()) {
        if (node->name == "manifest") {
          if (entry.manifest) {
            diag->Warn(DiagMessage() << "Found multiple manifest tags. Ignoring duplicates.");
            continue;
          }
          entry.manifest = {AndroidManifest()};
        }
      }

      config->android_sdk_groups[label] = entry;
      if (found) {
        valid = false;
      }
      found = true;
    }
  }

  return valid;
};

ConfigurationParser::ActionHandler ConfigurationParser::gl_texture_group_handler_ =
    [](PostProcessingConfiguration* config, Element* root_element, IDiagnostics* diag) -> bool {
  std::string label = GetLabel(root_element, diag);
  if (label.empty()) {
    return false;
  }

  auto& group = config->gl_texture_groups[label];
  bool valid = true;

  GlTexture result;
  for (auto* child : root_element->GetChildElements()) {
    if (child->name != "gl-texture") {
      diag->Error(DiagMessage() << "Unexpected element in GL texture group: " << child->name);
      valid = false;
    } else {
      for (const auto& attr : child->attributes) {
        if (attr.name == "name") {
          result.name = attr.value;
          break;
        }
      }

      for (auto* element : child->GetChildElements()) {
        if (element->name != "texture-path") {
          diag->Error(DiagMessage() << "Unexpected element in gl-texture element: " << child->name);
          valid = false;
          continue;
        }
        for (auto& node : element->children) {
          xml::Text* t;
          if ((t = NodeCast<xml::Text>(node.get())) != nullptr) {
            result.texture_paths.push_back(TrimWhitespace(t->text).to_string());
          }
        }
      }
    }
    group.push_back(result);
  }

  return valid;
};

ConfigurationParser::ActionHandler ConfigurationParser::device_feature_group_handler_ =
    [](PostProcessingConfiguration* config, Element* root_element, IDiagnostics* diag) -> bool {
  std::string label = GetLabel(root_element, diag);
  if (label.empty()) {
    return false;
  }

  auto& group = config->device_feature_groups[label];
  bool valid = true;

  for (auto* child : root_element->GetChildElements()) {
    if (child->name != "supports-feature") {
      diag->Error(DiagMessage() << "Unexpected root_element in device feature group: "
                                << child->name);
      valid = false;
    } else {
      for (auto& node : child->children) {
        xml::Text* t;
        if ((t = NodeCast<xml::Text>(node.get())) != nullptr) {
          group.push_back(TrimWhitespace(t->text).to_string());
          break;
        }
      }
    }
  }

  return valid;
};

}  // namespace aapt
