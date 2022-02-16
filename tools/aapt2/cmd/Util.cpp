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

#include "cmd/Util.h"

#include <vector>

#include "android-base/logging.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/Locale.h"

#include "ResourceUtils.h"
#include "ValueVisitor.h"
#include "split/TableSplitter.h"
#include "util/Maybe.h"
#include "util/Util.h"

using ::android::ConfigDescription;
using ::android::LocaleValue;
using ::android::StringPiece;
using ::android::base::StringPrintf;

namespace aapt {

Maybe<uint16_t> ParseTargetDensityParameter(const StringPiece& arg, IDiagnostics* diag) {
  ConfigDescription preferred_density_config;
  if (!ConfigDescription::Parse(arg, &preferred_density_config)) {
    diag->Error(DiagMessage() << "invalid density '" << arg << "' for --preferred-density option");
    return {};
  }

  // Clear the version that can be automatically added.
  preferred_density_config.sdkVersion = 0;

  if (preferred_density_config.diff(ConfigDescription::DefaultConfig()) !=
      ConfigDescription::CONFIG_DENSITY) {
    diag->Error(DiagMessage() << "invalid preferred density '" << arg << "'. "
                              << "Preferred density must only be a density value");
    return {};
  }
  return preferred_density_config.density;
}

bool ParseSplitParameter(const StringPiece& arg, IDiagnostics* diag, std::string* out_path,
                         SplitConstraints* out_split) {
  CHECK(diag != nullptr);
  CHECK(out_path != nullptr);
  CHECK(out_split != nullptr);

#ifdef _WIN32
  const char sSeparator = ';';
#else
  const char sSeparator = ':';
#endif

  std::vector<std::string> parts = util::Split(arg, sSeparator);
  if (parts.size() != 2) {
    diag->Error(DiagMessage() << "invalid split parameter '" << arg << "'");
    diag->Note(DiagMessage() << "should be --split path/to/output.apk" << sSeparator
                             << "<config>[,<config>...].");
    return false;
  }

  *out_path = parts[0];
  out_split->name = parts[1];
  for (const StringPiece& config_str : util::Tokenize(parts[1], ',')) {
    ConfigDescription config;
    if (!ConfigDescription::Parse(config_str, &config)) {
      diag->Error(DiagMessage() << "invalid config '" << config_str << "' in split parameter '"
                                << arg << "'");
      return false;
    }
    out_split->configs.insert(config);
  }
  return true;
}

std::unique_ptr<IConfigFilter> ParseConfigFilterParameters(const std::vector<std::string>& args,
                                                           IDiagnostics* diag) {
  std::unique_ptr<AxisConfigFilter> filter = util::make_unique<AxisConfigFilter>();
  for (const std::string& config_arg : args) {
    for (const StringPiece& config_str : util::Tokenize(config_arg, ',')) {
      ConfigDescription config;
      LocaleValue lv;
      if (lv.InitFromFilterString(config_str)) {
        lv.WriteTo(&config);
      } else if (!ConfigDescription::Parse(config_str, &config)) {
        diag->Error(DiagMessage() << "invalid config '" << config_str << "' for -c option");
        return {};
      }

      if (config.density != 0) {
        diag->Warn(DiagMessage() << "ignoring density '" << config << "' for -c option");
      } else {
        filter->AddConfig(config);
      }
    }
  }
  return std::move(filter);
}

// Adjust the SplitConstraints so that their SDK version is stripped if it
// is less than or equal to the minSdk. Otherwise the resources that have had
// their SDK version stripped due to minSdk won't ever match.
std::vector<SplitConstraints> AdjustSplitConstraintsForMinSdk(
    int min_sdk, const std::vector<SplitConstraints>& split_constraints) {
  std::vector<SplitConstraints> adjusted_constraints;
  adjusted_constraints.reserve(split_constraints.size());
  for (const SplitConstraints& constraints : split_constraints) {
    SplitConstraints constraint;
    for (const ConfigDescription& config : constraints.configs) {
      const ConfigDescription &configToInsert = (config.sdkVersion <= min_sdk)
          ? config.CopyWithoutSdkVersion()
          : config;
      // only add the config if it actually selects something
      if (configToInsert != ConfigDescription::DefaultConfig()) {
        constraint.configs.insert(configToInsert);
      }
    }
    constraint.name = constraints.name;
    adjusted_constraints.push_back(std::move(constraint));
  }
  return adjusted_constraints;
}

static xml::AaptAttribute CreateAttributeWithId(const ResourceId& id) {
  return xml::AaptAttribute(Attribute(), id);
}

static xml::NamespaceDecl CreateAndroidNamespaceDecl() {
  xml::NamespaceDecl decl;
  decl.prefix = "android";
  decl.uri = xml::kSchemaAndroid;
  return decl;
}

// Returns a copy of 'name' which conforms to the regex '[a-zA-Z]+[a-zA-Z0-9_]*' by
// replacing nonconforming characters with underscores.
//
// See frameworks/base/core/java/android/content/pm/PackageParser.java which
// checks this at runtime.
std::string MakePackageSafeName(const std::string &name) {
  std::string result(name);
  bool first = true;
  for (char &c : result) {
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
      first = false;
      continue;
    }
    if (!first) {
      if (c >= '0' && c <= '9') {
        continue;
      }
    }

    c = '_';
    first = false;
  }
  return result;
}

std::unique_ptr<xml::XmlResource> GenerateSplitManifest(const AppInfo& app_info,
                                                        const SplitConstraints& constraints) {
  const ResourceId kVersionCode(0x0101021b);
  const ResourceId kVersionCodeMajor(0x01010576);
  const ResourceId kRevisionCode(0x010104d5);
  const ResourceId kHasCode(0x0101000c);

  std::unique_ptr<xml::Element> manifest_el = util::make_unique<xml::Element>();
  manifest_el->namespace_decls.push_back(CreateAndroidNamespaceDecl());
  manifest_el->name = "manifest";
  manifest_el->attributes.push_back(xml::Attribute{"", "package", app_info.package});

  if (app_info.version_code) {
    const uint32_t version_code = app_info.version_code.value();
    manifest_el->attributes.push_back(xml::Attribute{
        xml::kSchemaAndroid, "versionCode", std::to_string(version_code),
        CreateAttributeWithId(kVersionCode),
        util::make_unique<BinaryPrimitive>(android::Res_value::TYPE_INT_DEC, version_code)});
  }

  if (app_info.version_code_major) {
    const uint32_t version_code_major = app_info.version_code_major.value();
    manifest_el->attributes.push_back(xml::Attribute{
        xml::kSchemaAndroid, "versionCodeMajor", std::to_string(version_code_major),
        CreateAttributeWithId(kVersionCodeMajor),
        util::make_unique<BinaryPrimitive>(android::Res_value::TYPE_INT_DEC, version_code_major)});
  }

  if (app_info.revision_code) {
    const uint32_t revision_code = app_info.revision_code.value();
    manifest_el->attributes.push_back(xml::Attribute{
        xml::kSchemaAndroid, "revisionCode", std::to_string(revision_code),
        CreateAttributeWithId(kRevisionCode),
        util::make_unique<BinaryPrimitive>(android::Res_value::TYPE_INT_DEC, revision_code)});
  }

  std::stringstream split_name;
  if (app_info.split_name) {
    split_name << app_info.split_name.value() << ".";
  }
  std::vector<std::string> sanitized_config_names;
  for (const auto &config : constraints.configs) {
    sanitized_config_names.push_back(MakePackageSafeName(config.toString().string()));
  }
  split_name << "config." << util::Joiner(sanitized_config_names, "_");

  manifest_el->attributes.push_back(xml::Attribute{"", "split", split_name.str()});

  if (app_info.split_name) {
    manifest_el->attributes.push_back(
        xml::Attribute{"", "configForSplit", app_info.split_name.value()});
  }

  // Splits may contain more configurations than originally desired (fall-back densities, etc.).
  // This makes programmatic discovery of split targeting difficult. Encode the original
  // split constraints intended for this split.
  std::stringstream target_config_str;
  target_config_str << util::Joiner(constraints.configs, ",");
  manifest_el->attributes.push_back(xml::Attribute{"", "targetConfig", target_config_str.str()});

  std::unique_ptr<xml::Element> application_el = util::make_unique<xml::Element>();
  application_el->name = "application";
  application_el->attributes.push_back(
      xml::Attribute{xml::kSchemaAndroid, "hasCode", "false", CreateAttributeWithId(kHasCode),
                     util::make_unique<BinaryPrimitive>(android::Res_value::TYPE_INT_BOOLEAN, 0u)});

  manifest_el->AppendChild(std::move(application_el));

  std::unique_ptr<xml::XmlResource> doc = util::make_unique<xml::XmlResource>();
  doc->root = std::move(manifest_el);
  return doc;
}

static Maybe<std::string> ExtractCompiledString(const xml::Attribute& attr,
                                                std::string* out_error) {
  if (attr.compiled_value != nullptr) {
    const String* compiled_str = ValueCast<String>(attr.compiled_value.get());
    if (compiled_str != nullptr) {
      if (!compiled_str->value->empty()) {
        return *compiled_str->value;
      } else {
        *out_error = "compiled value is an empty string";
        return {};
      }
    }
    *out_error = "compiled value is not a string";
    return {};
  }

  // Fallback to the plain text value if there is one.
  if (!attr.value.empty()) {
    return attr.value;
  }
  *out_error = "value is an empty string";
  return {};
}

static Maybe<uint32_t> ExtractCompiledInt(const xml::Attribute& attr, std::string* out_error) {
  if (attr.compiled_value != nullptr) {
    const BinaryPrimitive* compiled_prim = ValueCast<BinaryPrimitive>(attr.compiled_value.get());
    if (compiled_prim != nullptr) {
      if (compiled_prim->value.dataType >= android::Res_value::TYPE_FIRST_INT &&
          compiled_prim->value.dataType <= android::Res_value::TYPE_LAST_INT) {
        return compiled_prim->value.data;
      }
    }
    *out_error = "compiled value is not an integer";
    return {};
  }

  // Fallback to the plain text value if there is one.
  Maybe<uint32_t> integer = ResourceUtils::ParseInt(attr.value);
  if (integer) {
    return integer;
  }
  std::stringstream error_msg;
  error_msg << "'" << attr.value << "' is not a valid integer";
  *out_error = error_msg.str();
  return {};
}

static Maybe<int> ExtractSdkVersion(const xml::Attribute& attr, std::string* out_error) {
  if (attr.compiled_value != nullptr) {
    const BinaryPrimitive* compiled_prim = ValueCast<BinaryPrimitive>(attr.compiled_value.get());
    if (compiled_prim != nullptr) {
      if (compiled_prim->value.dataType >= android::Res_value::TYPE_FIRST_INT &&
          compiled_prim->value.dataType <= android::Res_value::TYPE_LAST_INT) {
        return compiled_prim->value.data;
      }
      *out_error = "compiled value is not an integer or string";
      return {};
    }

    const String* compiled_str = ValueCast<String>(attr.compiled_value.get());
    if (compiled_str != nullptr) {
      Maybe<int> sdk_version = ResourceUtils::ParseSdkVersion(*compiled_str->value);
      if (sdk_version) {
        return sdk_version;
      }

      *out_error = "compiled string value is not a valid SDK version";
      return {};
    }
    *out_error = "compiled value is not an integer or string";
    return {};
  }

  // Fallback to the plain text value if there is one.
  Maybe<int> sdk_version = ResourceUtils::ParseSdkVersion(attr.value);
  if (sdk_version) {
    return sdk_version;
  }
  std::stringstream error_msg;
  error_msg << "'" << attr.value << "' is not a valid SDK version";
  *out_error = error_msg.str();
  return {};
}

Maybe<AppInfo> ExtractAppInfoFromBinaryManifest(const xml::XmlResource& xml_res,
                                                IDiagnostics* diag) {
  // Make sure the first element is <manifest> with package attribute.
  const xml::Element* manifest_el = xml_res.root.get();
  if (manifest_el == nullptr) {
    return {};
  }

  AppInfo app_info;

  if (!manifest_el->namespace_uri.empty() || manifest_el->name != "manifest") {
    diag->Error(DiagMessage(xml_res.file.source) << "root tag must be <manifest>");
    return {};
  }

  const xml::Attribute* package_attr = manifest_el->FindAttribute({}, "package");
  if (!package_attr) {
    diag->Error(DiagMessage(xml_res.file.source) << "<manifest> must have a 'package' attribute");
    return {};
  }

  std::string error_msg;
  Maybe<std::string> maybe_package = ExtractCompiledString(*package_attr, &error_msg);
  if (!maybe_package) {
    diag->Error(DiagMessage(xml_res.file.source.WithLine(manifest_el->line_number))
                << "invalid package name: " << error_msg);
    return {};
  }
  app_info.package = maybe_package.value();

  if (const xml::Attribute* version_code_attr =
          manifest_el->FindAttribute(xml::kSchemaAndroid, "versionCode")) {
    Maybe<uint32_t> maybe_code = ExtractCompiledInt(*version_code_attr, &error_msg);
    if (!maybe_code) {
      diag->Error(DiagMessage(xml_res.file.source.WithLine(manifest_el->line_number))
                  << "invalid android:versionCode: " << error_msg);
      return {};
    }
    app_info.version_code = maybe_code.value();
  }

  if (const xml::Attribute* version_code_major_attr =
      manifest_el->FindAttribute(xml::kSchemaAndroid, "versionCodeMajor")) {
    Maybe<uint32_t> maybe_code = ExtractCompiledInt(*version_code_major_attr, &error_msg);
    if (!maybe_code) {
      diag->Error(DiagMessage(xml_res.file.source.WithLine(manifest_el->line_number))
                      << "invalid android:versionCodeMajor: " << error_msg);
      return {};
    }
    app_info.version_code_major = maybe_code.value();
  }

  if (const xml::Attribute* revision_code_attr =
          manifest_el->FindAttribute(xml::kSchemaAndroid, "revisionCode")) {
    Maybe<uint32_t> maybe_code = ExtractCompiledInt(*revision_code_attr, &error_msg);
    if (!maybe_code) {
      diag->Error(DiagMessage(xml_res.file.source.WithLine(manifest_el->line_number))
                  << "invalid android:revisionCode: " << error_msg);
      return {};
    }
    app_info.revision_code = maybe_code.value();
  }

  if (const xml::Attribute* split_name_attr = manifest_el->FindAttribute({}, "split")) {
    Maybe<std::string> maybe_split_name = ExtractCompiledString(*split_name_attr, &error_msg);
    if (!maybe_split_name) {
      diag->Error(DiagMessage(xml_res.file.source.WithLine(manifest_el->line_number))
                  << "invalid split name: " << error_msg);
      return {};
    }
    app_info.split_name = maybe_split_name.value();
  }

  if (const xml::Element* uses_sdk_el = manifest_el->FindChild({}, "uses-sdk")) {
    if (const xml::Attribute* min_sdk =
            uses_sdk_el->FindAttribute(xml::kSchemaAndroid, "minSdkVersion")) {
      Maybe<int> maybe_sdk = ExtractSdkVersion(*min_sdk, &error_msg);
      if (!maybe_sdk) {
        diag->Error(DiagMessage(xml_res.file.source.WithLine(uses_sdk_el->line_number))
                    << "invalid android:minSdkVersion: " << error_msg);
        return {};
      }
      app_info.min_sdk_version = maybe_sdk.value();
    }
  }
  return app_info;
}

void SetLongVersionCode(xml::Element* manifest, uint64_t version) {
  // Write the low bits of the version code to android:versionCode
  auto version_code = manifest->FindOrCreateAttribute(xml::kSchemaAndroid, "versionCode");
  version_code->value = StringPrintf("0x%08x", (uint32_t) (version & 0xffffffff));
  version_code->compiled_value = ResourceUtils::TryParseInt(version_code->value);

  auto version_high = (uint32_t) (version >> 32);
  if (version_high != 0) {
    // Write the high bits of the version code to android:versionCodeMajor
    auto version_major = manifest->FindOrCreateAttribute(xml::kSchemaAndroid, "versionCodeMajor");
    version_major->value = StringPrintf("0x%08x", version_high);
    version_major->compiled_value = ResourceUtils::TryParseInt(version_major->value);
  } else {
    manifest->RemoveAttribute(xml::kSchemaAndroid, "versionCodeMajor");
  }
}

std::regex GetRegularExpression(const std::string &input) {
  // Standard ECMAScript grammar.
  std::regex case_insensitive(
      input, std::regex_constants::ECMAScript);
  return case_insensitive;
}

}  // namespace aapt
