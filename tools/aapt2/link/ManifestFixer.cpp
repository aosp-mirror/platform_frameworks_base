/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "link/ManifestFixer.h"

#include <unordered_set>

#include "android-base/logging.h"

#include "ResourceUtils.h"
#include "trace/TraceBuffer.h"
#include "util/Util.h"
#include "xml/XmlActionExecutor.h"
#include "xml/XmlDom.h"

using android::StringPiece;

namespace aapt {

// This is to detect whether an <intent-filter> contains deeplink.
// See https://developer.android.com/training/app-links/deep-linking.
static bool HasDeepLink(xml::Element* intent_filter_el) {
  xml::Element* action_el = intent_filter_el->FindChild({}, "action");
  xml::Element* category_el = intent_filter_el->FindChild({}, "category");
  xml::Element* data_el = intent_filter_el->FindChild({}, "data");
  if (action_el == nullptr || category_el == nullptr || data_el == nullptr) {
    return false;
  }

  // Deeplinks must specify the ACTION_VIEW intent action.
  constexpr const char* action_view = "android.intent.action.VIEW";
  if (intent_filter_el->FindChildWithAttribute({}, "action", xml::kSchemaAndroid, "name",
                                               action_view) == nullptr) {
    return false;
  }

  // Deeplinks must have scheme included in <data> tag.
  xml::Attribute* data_scheme_attr = data_el->FindAttribute(xml::kSchemaAndroid, "scheme");
  if (data_scheme_attr == nullptr || data_scheme_attr->value.empty()) {
    return false;
  }

  // Deeplinks must include BROWSABLE category.
  constexpr const char* category_browsable = "android.intent.category.BROWSABLE";
  if (intent_filter_el->FindChildWithAttribute({}, "category", xml::kSchemaAndroid, "name",
                                               category_browsable) == nullptr) {
    return false;
  }
  return true;
}

static bool VerifyDeeplinkPathAttribute(xml::Element* data_el, android::SourcePathDiagnostics* diag,
                                        const std::string& attr_name) {
  xml::Attribute* attr = data_el->FindAttribute(xml::kSchemaAndroid, attr_name);
  if (attr != nullptr && !attr->value.empty()) {
    StringPiece attr_value = attr->value;
    const char* startChar = attr_value.begin();
    if (attr_name == "pathPattern") {
      // pathPattern starts with '.' or '*' does not need leading slash.
      // Reference starts with @ does not need leading slash.
      if (*startChar == '/' || *startChar == '.' || *startChar == '*' || *startChar == '@') {
        return true;
      } else {
        diag->Error(android::DiagMessage(data_el->line_number)
                    << "attribute 'android:" << attr_name << "' in <" << data_el->name
                    << "> tag has value of '" << attr_value
                    << "', it must be in a pattern start with '.' or '*', otherwise must start "
                       "with a leading slash '/'");
        return false;
      }
    } else {
      // Reference starts with @ does not need leading slash.
      if (*startChar == '/' || *startChar == '@') {
        return true;
      } else {
        diag->Error(android::DiagMessage(data_el->line_number)
                    << "attribute 'android:" << attr_name << "' in <" << data_el->name
                    << "> tag has value of '" << attr_value
                    << "', it must start with a leading slash '/'");
        return false;
      }
    }
  }
  return true;
}

static bool VerifyDeepLinkIntentAction(xml::Element* intent_filter_el,
                                       android::SourcePathDiagnostics* diag) {
  if (!HasDeepLink(intent_filter_el)) {
    return true;
  }

  xml::Element* data_el = intent_filter_el->FindChild({}, "data");
  if (data_el != nullptr) {
    if (!VerifyDeeplinkPathAttribute(data_el, diag, "path")) {
      return false;
    }
    if (!VerifyDeeplinkPathAttribute(data_el, diag, "pathPrefix")) {
      return false;
    }
    if (!VerifyDeeplinkPathAttribute(data_el, diag, "pathPattern")) {
      return false;
    }
  }
  return true;
}

static bool RequiredNameIsNotEmpty(xml::Element* el, android::SourcePathDiagnostics* diag) {
  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "name");
  if (attr == nullptr) {
    diag->Error(android::DiagMessage(el->line_number)
                << "<" << el->name << "> is missing attribute 'android:name'");
    return false;
  }

  if (attr->value.empty()) {
    diag->Error(android::DiagMessage(el->line_number)
                << "attribute 'android:name' in <" << el->name << "> tag must not be empty");
    return false;
  }
  return true;
}

// This is how PackageManager builds class names from AndroidManifest.xml entries.
static bool NameIsJavaClassName(xml::Element* el, xml::Attribute* attr,
                                android::SourcePathDiagnostics* diag) {
  // We allow unqualified class names (ie: .HelloActivity)
  // Since we don't know the package name, we can just make a fake one here and
  // the test will be identical as long as the real package name is valid too.
  std::optional<std::string> fully_qualified_class_name =
      util::GetFullyQualifiedClassName("a", attr->value);

  StringPiece qualified_class_name = fully_qualified_class_name
                                         ? fully_qualified_class_name.value()
                                         : attr->value;

  if (!util::IsJavaClassName(qualified_class_name)) {
    diag->Error(android::DiagMessage(el->line_number) << "attribute 'android:name' in <" << el->name
                                                      << "> tag must be a valid Java class name");
    return false;
  }
  return true;
}

static bool OptionalNameIsJavaClassName(xml::Element* el, android::SourcePathDiagnostics* diag) {
  if (xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "name")) {
    return NameIsJavaClassName(el, attr, diag);
  }
  return true;
}

static bool RequiredNameIsJavaClassName(xml::Element* el, android::SourcePathDiagnostics* diag) {
  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "name");
  if (attr == nullptr) {
    diag->Error(android::DiagMessage(el->line_number)
                << "<" << el->name << "> is missing attribute 'android:name'");
    return false;
  }
  return NameIsJavaClassName(el, attr, diag);
}

static bool RequiredNameIsJavaPackage(xml::Element* el, android::SourcePathDiagnostics* diag) {
  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "name");
  if (attr == nullptr) {
    diag->Error(android::DiagMessage(el->line_number)
                << "<" << el->name << "> is missing attribute 'android:name'");
    return false;
  }

  if (!util::IsJavaPackageName(attr->value)) {
    diag->Error(android::DiagMessage(el->line_number) << "attribute 'android:name' in <" << el->name
                                                      << "> tag must be a valid Java package name");
    return false;
  }
  return true;
}

static xml::XmlNodeAction::ActionFuncWithDiag RequiredAndroidAttribute(const std::string& attr) {
  return [=](xml::Element* el, android::SourcePathDiagnostics* diag) -> bool {
    if (el->FindAttribute(xml::kSchemaAndroid, attr) == nullptr) {
      diag->Error(android::DiagMessage(el->line_number)
                  << "<" << el->name << "> is missing required attribute 'android:" << attr << "'");
      return false;
    }
    return true;
  };
}

static xml::XmlNodeAction::ActionFuncWithDiag RequiredOneAndroidAttribute(
    const std::string& attrName1, const std::string& attrName2) {
  return [=](xml::Element* el, android::SourcePathDiagnostics* diag) -> bool {
    xml::Attribute* attr1 = el->FindAttribute(xml::kSchemaAndroid, attrName1);
    xml::Attribute* attr2 = el->FindAttribute(xml::kSchemaAndroid, attrName2);
    if (attr1 == nullptr && attr2 == nullptr) {
      diag->Error(android::DiagMessage(el->line_number)
                  << "<" << el->name << "> is missing required attribute 'android:" << attrName1
                  << "' or 'android:" << attrName2 << "'");
      return false;
    }
    if (attr1 != nullptr && attr2 != nullptr) {
      diag->Error(android::DiagMessage(el->line_number)
                  << "<" << el->name << "> can only specify one of attribute 'android:" << attrName1
                  << "' or 'android:" << attrName2 << "'");
      return false;
    }
    return true;
  };
}

static bool AutoGenerateIsFeatureSplit(xml::Element* el, android::SourcePathDiagnostics* diag) {
  constexpr const char* kFeatureSplit = "featureSplit";
  constexpr const char* kIsFeatureSplit = "isFeatureSplit";

  xml::Attribute* attr = el->FindAttribute({}, kFeatureSplit);
  if (attr != nullptr) {
    // Rewrite the featureSplit attribute to be "split". This is what the
    // platform recognizes.
    attr->name = "split";

    // Now inject the android:isFeatureSplit="true" attribute.
    xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, kIsFeatureSplit);
    if (attr != nullptr) {
      if (!ResourceUtils::ParseBool(attr->value).value_or(false)) {
        // The isFeatureSplit attribute is false, which conflicts with the use
        // of "featureSplit".
        diag->Error(android::DiagMessage(el->line_number)
                    << "attribute 'featureSplit' used in <manifest> but 'android:isFeatureSplit' "
                       "is not 'true'");
        return false;
      }

      // The attribute is already there and set to true, nothing to do.
    } else {
      el->attributes.push_back(xml::Attribute{xml::kSchemaAndroid, kIsFeatureSplit, "true"});
    }
  }
  return true;
}

static bool AutoGenerateIsSplitRequired(xml::Element* el, android::SourcePathDiagnostics* diag) {
  constexpr const char* kRequiredSplitTypes = "requiredSplitTypes";
  constexpr const char* kIsSplitRequired = "isSplitRequired";

  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, kRequiredSplitTypes);
  if (attr != nullptr) {
    // Now inject the android:isSplitRequired="true" attribute.
    xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, kIsSplitRequired);
    if (attr != nullptr) {
      if (!ResourceUtils::ParseBool(attr->value).value_or(false)) {
        // The isFeatureSplit attribute is false, which conflicts with the use
        // of "featureSplit".
        diag->Error(android::DiagMessage(el->line_number)
                    << "attribute 'requiredSplitTypes' used in <manifest> but "
                       "'android:isSplitRequired' is not 'true'");
        return false;
      }
      // The attribute is already there and set to true, nothing to do.
    } else {
      el->attributes.push_back(xml::Attribute{xml::kSchemaAndroid, kIsSplitRequired, "true"});
    }
  }
  return true;
}

static bool VerifyManifest(xml::Element* el, xml::XmlActionExecutorPolicy policy,
                           android::SourcePathDiagnostics* diag) {
  xml::Attribute* attr = el->FindAttribute({}, "package");
  if (!attr) {
    diag->Error(android::DiagMessage(el->line_number)
                << "<manifest> tag is missing 'package' attribute");
    return false;
  } else if (ResourceUtils::IsReference(attr->value)) {
    diag->Error(android::DiagMessage(el->line_number)
                << "attribute 'package' in <manifest> tag must not be a reference");
    return false;
  } else if (!util::IsAndroidPackageName(attr->value)) {
    android::DiagMessage error_msg(el->line_number);
    error_msg << "attribute 'package' in <manifest> tag is not a valid Android package name: '"
              << attr->value << "'";
    if (policy == xml::XmlActionExecutorPolicy::kAllowListWarning) {
      // Treat the error only as a warning.
      diag->Warn(error_msg);
    } else {
      diag->Error(error_msg);
      return false;
    }
  }

  attr = el->FindAttribute({}, "split");
  if (attr) {
    if (!util::IsJavaPackageName(attr->value)) {
      diag->Error(android::DiagMessage(el->line_number)
                  << "attribute 'split' in <manifest> tag is not a "
                     "valid split name");
      return false;
    }
  }
  return true;
}

// The coreApp attribute in <manifest> is not a regular AAPT attribute, so type
// checking on it is manual.
static bool FixCoreAppAttribute(xml::Element* el, android::SourcePathDiagnostics* diag) {
  if (xml::Attribute* attr = el->FindAttribute("", "coreApp")) {
    std::unique_ptr<BinaryPrimitive> result = ResourceUtils::TryParseBool(attr->value);
    if (!result) {
      diag->Error(android::DiagMessage(el->line_number) << "attribute coreApp must be a boolean");
      return false;
    }
    attr->compiled_value = std::move(result);
  }
  return true;
}

// Checks that <uses-feature> has android:glEsVersion or android:name, not both (or neither).
static bool VerifyUsesFeature(xml::Element* el, android::SourcePathDiagnostics* diag) {
  bool has_name = false;
  if (xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "name")) {
    if (attr->value.empty()) {
      diag->Error(android::DiagMessage(el->line_number)
                  << "android:name in <uses-feature> must not be empty");
      return false;
    }
    has_name = true;
  }

  bool has_gl_es_version = false;
  if (el->FindAttribute(xml::kSchemaAndroid, "glEsVersion")) {
    if (has_name) {
      diag->Error(android::DiagMessage(el->line_number)
                  << "cannot define both android:name and android:glEsVersion in <uses-feature>");
      return false;
    }
    has_gl_es_version = true;
  }

  if (!has_name && !has_gl_es_version) {
    diag->Error(android::DiagMessage(el->line_number)
                << "<uses-feature> must have either android:name or android:glEsVersion attribute");
    return false;
  }
  return true;
}

// Ensure that 'ns_decls' contains a declaration for 'uri', using 'prefix' as
// the xmlns prefix if possible.
static void EnsureNamespaceIsDeclared(const std::string& prefix, const std::string& uri,
                                      std::vector<xml::NamespaceDecl>* ns_decls) {
  if (std::find_if(ns_decls->begin(), ns_decls->end(), [&](const xml::NamespaceDecl& ns_decl) {
        return ns_decl.uri == uri;
      }) != ns_decls->end()) {
    return;
  }

  std::set<std::string> used_prefixes;
  for (const auto& ns_decl : *ns_decls) {
    used_prefixes.insert(ns_decl.prefix);
  }

  // Make multiple attempts in the unlikely event that 'prefix' is already taken.
  std::string disambiguator;
  for (int i = 0; i < used_prefixes.size() + 1; i++) {
    std::string attempted_prefix = prefix + disambiguator;
    if (used_prefixes.find(attempted_prefix) == used_prefixes.end()) {
      ns_decls->push_back(xml::NamespaceDecl{attempted_prefix, uri});
      return;
    }
    disambiguator = std::to_string(i);
  }
}

bool ManifestFixer::BuildRules(xml::XmlActionExecutor* executor, android::IDiagnostics* diag) {
  // First verify some options.
  if (options_.rename_manifest_package) {
    if (!util::IsJavaPackageName(options_.rename_manifest_package.value())) {
      diag->Error(android::DiagMessage() << "invalid manifest package override '"
                                         << options_.rename_manifest_package.value() << "'");
      return false;
    }
  }

  if (options_.rename_instrumentation_target_package) {
    if (!util::IsJavaPackageName(options_.rename_instrumentation_target_package.value())) {
      diag->Error(android::DiagMessage()
                  << "invalid instrumentation target package override '"
                  << options_.rename_instrumentation_target_package.value() << "'");
      return false;
    }
  }

  if (options_.rename_overlay_target_package) {
    if (!util::IsJavaPackageName(options_.rename_overlay_target_package.value())) {
      diag->Error(android::DiagMessage() << "invalid overlay target package override '"
                                         << options_.rename_overlay_target_package.value() << "'");
      return false;
    }
  }

  // Common <intent-filter> actions.
  xml::XmlNodeAction intent_filter_action;
  intent_filter_action.Action(VerifyDeepLinkIntentAction);
  intent_filter_action["action"].Action(RequiredNameIsNotEmpty);
  intent_filter_action["category"].Action(RequiredNameIsNotEmpty);
  intent_filter_action["data"];
  intent_filter_action["uri-relative-filter-group"];
  intent_filter_action["uri-relative-filter-group"]["data"];

  // Common <meta-data> actions.
  xml::XmlNodeAction meta_data_action;

  // Common <property> actions.
  xml::XmlNodeAction property_action;
  property_action.Action(RequiredOneAndroidAttribute("resource", "value"));

  // Common <uses-feature> actions.
  xml::XmlNodeAction uses_feature_action;
  uses_feature_action.Action(VerifyUsesFeature);

  // Common component actions.
  xml::XmlNodeAction component_action;
  component_action.Action(RequiredNameIsJavaClassName);
  component_action["intent-filter"] = intent_filter_action;
  component_action["preferred"] = intent_filter_action;
  component_action["meta-data"] = meta_data_action;
  component_action["property"] = property_action;

  // Manifest actions.
  xml::XmlNodeAction& manifest_action = (*executor)["manifest"];
  manifest_action.Action(AutoGenerateIsFeatureSplit);
  manifest_action.Action(AutoGenerateIsSplitRequired);
  manifest_action.Action(VerifyManifest);
  manifest_action.Action(FixCoreAppAttribute);
  manifest_action.Action([&](xml::Element* el) -> bool {
    EnsureNamespaceIsDeclared("android", xml::kSchemaAndroid, &el->namespace_decls);

    if (options_.version_name_default) {
      if (options_.replace_version) {
        el->RemoveAttribute(xml::kSchemaAndroid, "versionName");
      }
      if (el->FindAttribute(xml::kSchemaAndroid, "versionName") == nullptr) {
        el->attributes.push_back(
            xml::Attribute{xml::kSchemaAndroid, "versionName",
                           options_.version_name_default.value()});
      }
    }

    if (options_.version_code_default) {
      if (options_.replace_version) {
        el->RemoveAttribute(xml::kSchemaAndroid, "versionCode");
      }
      if (el->FindAttribute(xml::kSchemaAndroid, "versionCode") == nullptr) {
        el->attributes.push_back(
            xml::Attribute{xml::kSchemaAndroid, "versionCode",
                           options_.version_code_default.value()});
      }
    }

    if (options_.version_code_major_default) {
      if (options_.replace_version) {
        el->RemoveAttribute(xml::kSchemaAndroid, "versionCodeMajor");
      }
      if (el->FindAttribute(xml::kSchemaAndroid, "versionCodeMajor") == nullptr) {
        el->attributes.push_back(
            xml::Attribute{xml::kSchemaAndroid, "versionCodeMajor",
                           options_.version_code_major_default.value()});
      }
    }

    if (options_.revision_code_default) {
      if (options_.replace_version) {
        el->RemoveAttribute(xml::kSchemaAndroid, "revisionCode");
      }
      if (el->FindAttribute(xml::kSchemaAndroid, "revisionCode") == nullptr) {
        el->attributes.push_back(xml::Attribute{xml::kSchemaAndroid, "revisionCode",
                                                options_.revision_code_default.value()});
      }
    }

    if (options_.non_updatable_system) {
      if (el->FindAttribute(xml::kSchemaAndroid, "versionCode") == nullptr) {
        el->RemoveAttribute("", "updatableSystem");
        el->attributes.push_back(xml::Attribute{"", "updatableSystem", "false"});
      } else {
        diag->Note(android::DiagMessage(el->line_number)
                   << "Ignoring --non-updatable-system because the manifest has a versionCode");
      }
    }

    return true;
  });

  // Meta tags.
  manifest_action["eat-comment"];

  // Uses-sdk actions.
  manifest_action["uses-sdk"].Action([&](xml::Element* el) -> bool {
    if (options_.min_sdk_version_default &&
        el->FindAttribute(xml::kSchemaAndroid, "minSdkVersion") == nullptr) {
      // There was no minSdkVersion defined and we have a default to assign.
      el->attributes.push_back(
          xml::Attribute{xml::kSchemaAndroid, "minSdkVersion",
                         options_.min_sdk_version_default.value()});
    }

    if (options_.target_sdk_version_default &&
        el->FindAttribute(xml::kSchemaAndroid, "targetSdkVersion") == nullptr) {
      // There was no targetSdkVersion defined and we have a default to assign.
      el->attributes.push_back(
          xml::Attribute{xml::kSchemaAndroid, "targetSdkVersion",
                         options_.target_sdk_version_default.value()});
    }
    return true;
  });
  manifest_action["uses-sdk"]["extension-sdk"];

  // Instrumentation actions.
  manifest_action["instrumentation"].Action(RequiredNameIsJavaClassName);
  manifest_action["instrumentation"].Action([&](xml::Element* el) -> bool {
    if (!options_.rename_instrumentation_target_package) {
      return true;
    }

    if (xml::Attribute* attr =
            el->FindAttribute(xml::kSchemaAndroid, "targetPackage")) {
      attr->value = options_.rename_instrumentation_target_package.value();
    }
    return true;
  });
  manifest_action["instrumentation"]["meta-data"] = meta_data_action;

  manifest_action["attribution"];
  manifest_action["attribution"]["inherit-from"];
  manifest_action["original-package"];
  manifest_action["overlay"].Action([&](xml::Element* el) -> bool {
    if (options_.rename_overlay_target_package) {
      if (xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "targetPackage")) {
        attr->value = options_.rename_overlay_target_package.value();
      }
    }
    if (options_.rename_overlay_category) {
      if (xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "category")) {
        attr->value = options_.rename_overlay_category.value();
      } else {
        el->attributes.push_back(xml::Attribute{xml::kSchemaAndroid, "category",
                                                options_.rename_overlay_category.value()});
      }
    }
    return true;
  });
  manifest_action["protected-broadcast"];
  manifest_action["adopt-permissions"];
  manifest_action["uses-permission"];
  manifest_action["uses-permission"]["required-feature"].Action(RequiredNameIsNotEmpty);
  manifest_action["uses-permission"]["required-not-feature"].Action(RequiredNameIsNotEmpty);
  manifest_action["uses-permission-sdk-23"];
  manifest_action["permission"];
  manifest_action["permission"]["meta-data"] = meta_data_action;
  manifest_action["permission-tree"];
  manifest_action["permission-group"];
  manifest_action["uses-configuration"];
  manifest_action["supports-screens"];
  manifest_action["uses-feature"] = uses_feature_action;
  manifest_action["feature-group"]["uses-feature"] = uses_feature_action;
  manifest_action["compatible-screens"];
  manifest_action["compatible-screens"]["screen"];
  manifest_action["supports-gl-texture"];
  manifest_action["restrict-update"];
  manifest_action["install-constraints"]["fingerprint-prefix"];
  manifest_action["package-verifier"];
  manifest_action["meta-data"] = meta_data_action;
  manifest_action["uses-split"].Action(RequiredNameIsJavaPackage);
  manifest_action["queries"]["package"].Action(RequiredNameIsJavaPackage);
  manifest_action["queries"]["intent"] = intent_filter_action;
  manifest_action["queries"]["provider"].Action(RequiredAndroidAttribute("authorities"));
  // TODO: more complicated component name tag

  manifest_action["key-sets"]["key-set"]["public-key"];
  manifest_action["key-sets"]["upgrade-key-set"];

  // Application actions.
  xml::XmlNodeAction& application_action = manifest_action["application"];
  application_action.Action(OptionalNameIsJavaClassName);

  application_action["uses-library"].Action(RequiredNameIsNotEmpty);
  application_action["uses-native-library"].Action(RequiredNameIsNotEmpty);
  application_action["library"].Action(RequiredNameIsNotEmpty);
  application_action["profileable"];
  application_action["property"] = property_action;

  xml::XmlNodeAction& static_library_action = application_action["static-library"];
  static_library_action.Action(RequiredNameIsJavaPackage);
  static_library_action.Action(RequiredAndroidAttribute("version"));

  xml::XmlNodeAction& uses_static_library_action = application_action["uses-static-library"];
  uses_static_library_action.Action(RequiredNameIsJavaPackage);
  uses_static_library_action.Action(RequiredAndroidAttribute("version"));
  uses_static_library_action.Action(RequiredAndroidAttribute("certDigest"));
  uses_static_library_action["additional-certificate"];

  xml::XmlNodeAction& sdk_library_action = application_action["sdk-library"];
  sdk_library_action.Action(RequiredNameIsJavaPackage);
  sdk_library_action.Action(RequiredAndroidAttribute("versionMajor"));

  xml::XmlNodeAction& uses_sdk_library_action = application_action["uses-sdk-library"];
  uses_sdk_library_action.Action(RequiredNameIsJavaPackage);
  uses_sdk_library_action.Action(RequiredAndroidAttribute("versionMajor"));
  uses_sdk_library_action.Action(RequiredAndroidAttribute("certDigest"));
  uses_sdk_library_action["additional-certificate"];

  xml::XmlNodeAction& uses_package_action = application_action["uses-package"];
  uses_package_action.Action(RequiredNameIsJavaPackage);
  uses_package_action["additional-certificate"];

  if (options_.debug_mode) {
    application_action.Action([&](xml::Element* el) -> bool {
      xml::Attribute *attr = el->FindOrCreateAttribute(xml::kSchemaAndroid, "debuggable");
      attr->value = "true";
      return true;
    });
  }

  application_action["meta-data"] = meta_data_action;

  application_action["processes"];
  application_action["processes"]["deny-permission"];
  application_action["processes"]["allow-permission"];
  application_action["processes"]["process"]["deny-permission"];
  application_action["processes"]["process"]["allow-permission"];

  application_action["activity"] = component_action;
  application_action["activity"]["layout"];

  application_action["activity-alias"] = component_action;
  application_action["service"] = component_action;
  application_action["receiver"] = component_action;
  application_action["apex-system-service"] = component_action;

  // Provider actions.
  application_action["provider"] = component_action;
  application_action["provider"]["grant-uri-permission"];
  application_action["provider"]["path-permission"];

  manifest_action["package"] = manifest_action;

  return true;
}

static void FullyQualifyClassName(StringPiece package, StringPiece attr_ns, StringPiece attr_name,
                                  xml::Element* el) {
  xml::Attribute* attr = el->FindAttribute(attr_ns, attr_name);
  if (attr != nullptr) {
    if (std::optional<std::string> new_value =
            util::GetFullyQualifiedClassName(package, attr->value)) {
      attr->value = std::move(new_value.value());
    }
  }
}

static bool RenameManifestPackage(StringPiece package_override, xml::Element* manifest_el) {
  xml::Attribute* attr = manifest_el->FindAttribute({}, "package");

  // We've already verified that the manifest element is present, with a package
  // name specified.
  CHECK(attr != nullptr);

  std::string original_package = std::move(attr->value);
  attr->value.assign(package_override);

  xml::Element* application_el = manifest_el->FindChild({}, "application");
  if (application_el != nullptr) {
    FullyQualifyClassName(original_package, xml::kSchemaAndroid, "name", application_el);
    FullyQualifyClassName(original_package, xml::kSchemaAndroid, "backupAgent", application_el);

    for (xml::Element* child_el : application_el->GetChildElements()) {
      if (child_el->namespace_uri.empty()) {
        if (child_el->name == "activity" || child_el->name == "activity-alias" ||
            child_el->name == "provider" || child_el->name == "receiver" ||
            child_el->name == "service") {
          FullyQualifyClassName(original_package, xml::kSchemaAndroid, "name", child_el);
          continue;
        }

        if (child_el->name == "activity-alias") {
          FullyQualifyClassName(original_package, xml::kSchemaAndroid, "targetActivity", child_el);
          continue;
        }

        if (child_el->name == "processes") {
          for (xml::Element* grand_child_el : child_el->GetChildElements()) {
            if (grand_child_el->name == "process") {
              FullyQualifyClassName(original_package, xml::kSchemaAndroid, "name", grand_child_el);
            }
          }
          continue;
        }
      }
    }
  }
  return true;
}

bool ManifestFixer::Consume(IAaptContext* context, xml::XmlResource* doc) {
  TRACE_CALL();
  xml::Element* root = xml::FindRootElement(doc->root.get());
  if (!root || !root->namespace_uri.empty() || root->name != "manifest") {
    context->GetDiagnostics()->Error(android::DiagMessage(doc->file.source)
                                     << "root tag must be <manifest>");
    return false;
  }

  if ((options_.min_sdk_version_default || options_.target_sdk_version_default) &&
      root->FindChild({}, "uses-sdk") == nullptr) {
    // Auto insert a <uses-sdk> element. This must be inserted before the
    // <application> tag. The device runtime PackageParser will make SDK version
    // decisions while parsing <application>.
    std::unique_ptr<xml::Element> uses_sdk = util::make_unique<xml::Element>();
    uses_sdk->name = "uses-sdk";
    root->InsertChild(0, std::move(uses_sdk));
  }

  if (!options_.no_compile_sdk_metadata && options_.compile_sdk_version) {
    xml::Attribute* attr = root->FindOrCreateAttribute(xml::kSchemaAndroid, "compileSdkVersion");

    // Make sure we un-compile the value if it was set to something else.
    attr->compiled_value = {};
    attr->value = options_.compile_sdk_version.value();

    attr = root->FindOrCreateAttribute("", "platformBuildVersionCode");

    // Make sure we un-compile the value if it was set to something else.
    attr->compiled_value = {};
    attr->value = options_.compile_sdk_version.value();
  }

  if (!options_.no_compile_sdk_metadata && options_.compile_sdk_version_codename) {
    xml::Attribute* attr =
        root->FindOrCreateAttribute(xml::kSchemaAndroid, "compileSdkVersionCodename");

    // Make sure we un-compile the value if it was set to something else.
    attr->compiled_value = {};
    attr->value = options_.compile_sdk_version_codename.value();

    attr = root->FindOrCreateAttribute("", "platformBuildVersionName");

    // Make sure we un-compile the value if it was set to something else.
    attr->compiled_value = {};
    attr->value = options_.compile_sdk_version_codename.value();
  }

  if (!options_.fingerprint_prefixes.empty()) {
    xml::Element* install_constraints_el = root->FindChild({}, "install-constraints");
    if (install_constraints_el == nullptr) {
      std::unique_ptr<xml::Element> install_constraints = std::make_unique<xml::Element>();
      install_constraints->name = "install-constraints";
      install_constraints_el = install_constraints.get();
      root->AppendChild(std::move(install_constraints));
    }
    for (const std::string& prefix : options_.fingerprint_prefixes) {
      std::unique_ptr<xml::Element> prefix_el = std::make_unique<xml::Element>();
      prefix_el->name = "fingerprint-prefix";
      xml::Attribute* attr = prefix_el->FindOrCreateAttribute(xml::kSchemaAndroid, "value");
      attr->value = prefix;
      install_constraints_el->AppendChild(std::move(prefix_el));
    }
  }

  xml::XmlActionExecutor executor;
  if (!BuildRules(&executor, context->GetDiagnostics())) {
    return false;
  }

  xml::XmlActionExecutorPolicy policy = options_.warn_validation
                                            ? xml::XmlActionExecutorPolicy::kAllowListWarning
                                            : xml::XmlActionExecutorPolicy::kAllowList;
  if (!executor.Execute(policy, context->GetDiagnostics(), doc)) {
    return false;
  }

  if (options_.rename_manifest_package) {
    // Rename manifest package outside of the XmlActionExecutor.
    // We need to extract the old package name and FullyQualify all class
    // names.
    if (!RenameManifestPackage(options_.rename_manifest_package.value(), root)) {
      return false;
    }
  }
  return true;
}

}  // namespace aapt
