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

#include <android-base/file.h>
#include <android-base/logging.h>

#include "ConfigDescription.h"
#include "Diagnostics.h"
#include "io/File.h"
#include "io/FileSystem.h"
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
using ::aapt::util::TrimWhitespace;
using ::aapt::xml::Element;
using ::aapt::xml::FindRootElement;
using ::aapt::xml::NodeCast;
using ::aapt::xml::XmlActionExecutor;
using ::aapt::xml::XmlActionExecutorPolicy;
using ::aapt::xml::XmlNodeAction;
using ::android::base::ReadFileToString;

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
static bool ReplacePlaceholder(const std::string& placeholder, const Maybe<std::string>& value,
                               std::string* name, IDiagnostics* diag) {
  size_t offset = name->find(placeholder);
  if (value) {
    if (offset == std::string::npos) {
      diag->Error(DiagMessage() << "Missing placeholder for artifact: " << placeholder);
      return false;
    }
    name->replace(offset, placeholder.length(), value.value());
    return true;
  }

  // Make sure the placeholder was not present if the desired value was not present.
  bool result = (offset == std::string::npos);
  if (!result) {
    diag->Error(DiagMessage() << "Placeholder present but no value for artifact: " << placeholder);
  }
  return result;
}

Maybe<std::string> Artifact::ToArtifactName(const std::string& format, IDiagnostics* diag) const {
  std::string result = format;

  if (!ReplacePlaceholder("{abi}", abi_group, &result, diag)) {
    return {};
  }

  if (!ReplacePlaceholder("{density}", screen_density_group, &result, diag)) {
    return {};
  }

  if (!ReplacePlaceholder("{locale}", locale_group, &result, diag)) {
    return {};
  }

  if (!ReplacePlaceholder("{sdk}", android_sdk_group, &result, diag)) {
    return {};
  }

  if (!ReplacePlaceholder("{feature}", device_feature_group, &result, diag)) {
    return {};
  }

  if (!ReplacePlaceholder("{gl}", gl_texture_group, &result, diag)) {
    return {};
  }

  return result;
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
  std::istringstream in(contents_);

  auto doc = xml::Inflate(&in, diag_, Source("config.xml"));
  if (!doc) {
    return {};
  }

  // Strip any namespaces from the XML as the XmlActionExecutor ignores anything with a namespace.
  auto* root = FindRootElement(doc.get());
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

  return {config};
}

ConfigurationParser::ActionHandler ConfigurationParser::artifact_handler_ =
    [](PostProcessingConfiguration* config, Element* root_element, IDiagnostics* diag) -> bool {
  Artifact artifact{};
  for (const auto& attr : root_element->attributes) {
    if (attr.name == "name") {
      artifact.name = attr.value;
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
          if (ConfigDescription::Parse(text, &config_descriptor)) {
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
      Locale entry;
      for (const auto& attr : child->attributes) {
        if (attr.name == "lang") {
          entry.lang = {attr.value};
        } else if (attr.name == "region") {
          entry.region = {attr.value};
        } else {
          diag->Warn(DiagMessage() << "Unknown attribute: " << attr.name << " = " << attr.value);
        }
      }
      group.push_back(entry);
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

  auto& group = config->android_sdk_groups[label];
  bool valid = true;

  for (auto* child : root_element->GetChildElements()) {
    if (child->name != "android-sdk") {
      diag->Error(DiagMessage() << "Unexpected root_element in ABI group: " << child->name);
      valid = false;
    } else {
      AndroidSdk entry;
      for (const auto& attr : child->attributes) {
        if (attr.name == "minSdkVersion") {
          entry.min_sdk_version = {attr.value};
        } else if (attr.name == "targetSdkVersion") {
          entry.target_sdk_version = {attr.value};
        } else if (attr.name == "maxSdkVersion") {
          entry.max_sdk_version = {attr.value};
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

      group.push_back(entry);
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
