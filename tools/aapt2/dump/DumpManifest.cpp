/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "DumpManifest.h"

#include <androidfw/ApkParsing.h>

#include <algorithm>
#include <array>
#include <memory>
#include <set>
#include <string_view>
#include <vector>

#include "LoadedApk.h"
#include "SdkConstants.h"
#include "ValueVisitor.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/FileStream.h"
#include "io/File.h"
#include "process/IResourceTableConsumer.h"
#include "xml/XmlDom.h"

using ::android::base::StringPrintf;
using ::android::ConfigDescription;

namespace aapt {

/**
 * These are attribute resource constants for the platform, as found in android.R.attr.
 */
enum {
  LABEL_ATTR = 0x01010001,
  ICON_ATTR = 0x01010002,
  NAME_ATTR = 0x01010003,
  PERMISSION_ATTR = 0x01010006,
  EXPORTED_ATTR = 0x01010010,
  GRANT_URI_PERMISSIONS_ATTR = 0x0101001b,
  PRIORITY_ATTR = 0x0101001c,
  RESOURCE_ATTR = 0x01010025,
  DEBUGGABLE_ATTR = 0x0101000f,
  TARGET_PACKAGE_ATTR = 0x01010021,
  VALUE_ATTR = 0x01010024,
  VERSION_CODE_ATTR = 0x0101021b,
  VERSION_NAME_ATTR = 0x0101021c,
  SCREEN_ORIENTATION_ATTR = 0x0101001e,
  MIN_SDK_VERSION_ATTR = 0x0101020c,
  MAX_SDK_VERSION_ATTR = 0x01010271,
  REQ_TOUCH_SCREEN_ATTR = 0x01010227,
  REQ_KEYBOARD_TYPE_ATTR = 0x01010228,
  REQ_HARD_KEYBOARD_ATTR = 0x01010229,
  REQ_NAVIGATION_ATTR = 0x0101022a,
  REQ_FIVE_WAY_NAV_ATTR = 0x01010232,
  TARGET_SDK_VERSION_ATTR = 0x01010270,
  TEST_ONLY_ATTR = 0x01010272,
  ANY_DENSITY_ATTR = 0x0101026c,
  GL_ES_VERSION_ATTR = 0x01010281,
  SMALL_SCREEN_ATTR = 0x01010284,
  NORMAL_SCREEN_ATTR = 0x01010285,
  LARGE_SCREEN_ATTR = 0x01010286,
  XLARGE_SCREEN_ATTR = 0x010102bf,
  REQUIRED_ATTR = 0x0101028e,
  INSTALL_LOCATION_ATTR = 0x010102b7,
  SCREEN_SIZE_ATTR = 0x010102ca,
  SCREEN_DENSITY_ATTR = 0x010102cb,
  REQUIRES_SMALLEST_WIDTH_DP_ATTR = 0x01010364,
  COMPATIBLE_WIDTH_LIMIT_DP_ATTR = 0x01010365,
  LARGEST_WIDTH_LIMIT_DP_ATTR = 0x01010366,
  PUBLIC_KEY_ATTR = 0x010103a6,
  CATEGORY_ATTR = 0x010103e8,
  BANNER_ATTR = 0x10103f2,
  ISGAME_ATTR = 0x10103f4,
  VERSION_ATTR = 0x01010519,
  CERT_DIGEST_ATTR = 0x01010548,
  REQUIRED_FEATURE_ATTR = 0x01010554,
  REQUIRED_NOT_FEATURE_ATTR = 0x01010555,
  IS_STATIC_ATTR = 0x0101055a,
  REQUIRED_SYSTEM_PROPERTY_NAME_ATTR = 0x01010565,
  REQUIRED_SYSTEM_PROPERTY_VALUE_ATTR = 0x01010566,
  COMPILE_SDK_VERSION_ATTR = 0x01010572,
  COMPILE_SDK_VERSION_CODENAME_ATTR = 0x01010573,
  VERSION_MAJOR_ATTR = 0x01010577,
  PACKAGE_TYPE_ATTR = 0x01010587,
  USES_PERMISSION_FLAGS_ATTR = 0x01010644,
};

const std::string& kAndroidNamespace = "http://schemas.android.com/apk/res/android";
constexpr int kNeverForLocation = 0x00010000;

/** Retrieves the attribute of the element with the specified attribute resource id. */
static xml::Attribute* FindAttribute(xml::Element *el, uint32_t resd_id) {
  for (auto& a : el->attributes) {
    if (a.compiled_attribute && a.compiled_attribute.value().id) {
      if (a.compiled_attribute.value().id.value() == resd_id) {
        return std::move(&a);
      }
    }
  }
  return nullptr;
}

/** Retrieves the attribute of the element that has the specified namespace and attribute name. */
static xml::Attribute* FindAttribute(xml::Element *el, const std::string &package,
                                     const std::string &name) {
  return el->FindAttribute(package, name);
}

class Architectures {
 public:
  std::set<std::string> architectures;
  std::set<std::string> alt_architectures;

  void Print(text::Printer* printer) {
    if (!architectures.empty()) {
      printer->Print("native-code:");
      for (auto& arch : architectures) {
        printer->Print(StringPrintf(" '%s'", arch.data()));
      }
      printer->Print("\n");
    }
    if (!alt_architectures.empty()) {
      printer->Print("alt-native-code:");
      for (auto& arch : alt_architectures) {
        printer->Print(StringPrintf(" '%s'", arch.data()));
      }
      printer->Print("\n");
    }
  }

  void ToProto(pb::Badging* out_badging) {
    auto out_architectures = out_badging->mutable_architectures();
    for (auto& arch : architectures) {
      out_architectures->add_architectures(arch);
    }
    for (auto& arch : alt_architectures) {
      out_architectures->add_alt_architectures(arch);
    }
  }
};

const static std::array<std::string_view, 14> printable_components{"app-widget",
                                                                   "device-admin",
                                                                   "ime",
                                                                   "wallpaper",
                                                                   "accessibility",
                                                                   "print-service",
                                                                   "payment",
                                                                   "search",
                                                                   "document-provider",
                                                                   "launcher",
                                                                   "notification-listener",
                                                                   "dream",
                                                                   "camera",
                                                                   "camera-secure"};

class Components {
 public:
  std::set<std::string, std::less<>> discovered_components;
  bool other_activities = false;
  bool other_receivers = false;
  bool other_services = false;

  void Print(text::Printer* printer) {
    for (auto& component : printable_components) {
      if (discovered_components.find(component) != discovered_components.end()) {
        printer->Print(StringPrintf("provides-component:'%s'\n", component.data()));
      }
    }
    // Print presence of main activity
    if (discovered_components.find("main") != discovered_components.end()) {
      printer->Print("main\n");
    }

    if (other_activities) {
      printer->Print("other-activities\n");
    }
    if (other_receivers) {
      printer->Print("other-receivers\n");
    }
    if (other_services) {
      printer->Print("other-services\n");
    }
  }

  void ToProto(pb::Badging* out_badging) {
    auto out_components = out_badging->mutable_components();
    for (auto& component : printable_components) {
      auto discovered = discovered_components.find(component);
      if (discovered != discovered_components.end()) {
        out_components->add_provided_components(*discovered);
      }
    }
    out_components->set_main(discovered_components.find("main") != discovered_components.end());
    out_components->set_other_activities(other_activities);
    out_components->set_other_receivers(other_receivers);
    out_components->set_other_services(other_services);
  }
};

class CommonFeatureGroup;
class FeatureGroup;
class SupportsScreen;

class ManifestExtractor {
 public:
  explicit ManifestExtractor(LoadedApk* apk, DumpManifestOptions& options);

  class Element {
   public:
    Element() = default;
    virtual ~Element() = default;

    static std::unique_ptr<Element> Inflate(ManifestExtractor* extractor, xml::Element* el,
                                            const std::string& parent_tag);

    /** Writes out the extracted contents of the element. */
    virtual void Print(text::Printer* printer) {
    }

    /** Saves extracted information into Badging proto. */
    virtual void ToProto(pb::Badging* out_badging) {
    }

    /** Adds an element to the list of children of the element. */
    void AddChild(std::unique_ptr<Element>& child) { children_.push_back(std::move(child)); }

    template <typename Predicate>
    void Filter(Predicate&& func) {
      children_.erase(std::remove_if(children_.begin(), children_.end(),
                                     [&](const auto& e) { return func(e.get()); }),
                      children_.end());
    }

    /** Retrieves the list of children of the element. */
    const std::vector<std::unique_ptr<Element>>& children() const {
      return children_;
    }

    /** Retrieves the extracted xml element tag. */
    const std::string& tag() const {
      return tag_;
    }

    /** Whether this element has special Extract/Print/ToProto logic. */
    bool is_featured() const {
      return featured_;
    }

   protected:
    ManifestExtractor* extractor() const {
      return extractor_;
    }

    /** Retrieves and stores the information extracted from the xml element. */
    virtual void Extract(xml::Element* el) { }

    /*
     * Retrieves a configuration value of the resource entry that best matches the specified
     * configuration.
     */
    static Value* BestConfigValue(ResourceEntry* entry,
                                  const ConfigDescription& match) {
      if (!entry) {
        return nullptr;
      }

      // Determine the config that best matches the desired config
      ResourceConfigValue* best_value = nullptr;
      for (auto& value : entry->values) {
        if (!value->config.match(match)) {
          continue;
        }

        if (best_value != nullptr) {
          if (!value->config.isBetterThan(best_value->config, &match)) {
            if (value->config.compare(best_value->config) != 0) {
              continue;
            }
          }
        }

        best_value = value.get();
      }

      // The entry has no values
      if (!best_value) {
        return nullptr;
      }

      return best_value->value.get();
    }

    /** Retrieves the resource assigned to the specified resource id if one exists. */
    Value* FindValueById(const ResourceTable* table, const ResourceId& res_id,
                         const ConfigDescription& config = DefaultConfig()) {
      if (table) {
        for (auto& package : table->packages) {
            for (auto& type : package->types) {
              for (auto& entry : type->entries) {
                if (entry->id && entry->id.value() == res_id.id) {
                  if (auto value = BestConfigValue(entry.get(), config)) {
                    return value;
                  }
                }
              }
          }
        }
      }
      return nullptr;
    }

    /** Attempts to resolve the reference to a non-reference value. */
    Value* ResolveReference(Reference* ref, const ConfigDescription& config = DefaultConfig()) {
      const int kMaxIterations = 40;
      int i = 0;
      while (ref && ref->id && i++ < kMaxIterations) {
        auto table = extractor_->apk_->GetResourceTable();
        if (auto value = FindValueById(table, ref->id.value(), config)) {
          if (ValueCast<Reference>(value)) {
            ref = ValueCast<Reference>(value);
          } else {
            return value;
          }
        }
      }
      return nullptr;
    }

    /**
     * Retrieves the integer value of the attribute . If the value of the attribute is a reference,
     * this will attempt to resolve the reference to an integer value.
     **/
    int32_t* GetAttributeInteger(xml::Attribute* attr,
                                 const ConfigDescription& config = DefaultConfig()) {
      if (attr != nullptr) {
        if (attr->compiled_value) {
          // Resolve references using the configuration
          Value* value = attr->compiled_value.get();
          if (ValueCast<Reference>(value)) {
            value = ResolveReference(ValueCast<Reference>(value), config);
          } else {
            value = attr->compiled_value.get();
          }
          // Retrieve the integer data if possible
          if (value != nullptr) {
            if (BinaryPrimitive* intValue = ValueCast<BinaryPrimitive>(value)) {
              return (int32_t*) &intValue->value.data;
            }
          }
        }
      }
      return nullptr;
    }

    /**
     * A version of GetAttributeInteger that returns a default integer if the attribute does not
     * exist or cannot be resolved to an integer value.
     **/
    int32_t GetAttributeIntegerDefault(xml::Attribute* attr, int32_t def,
                                       const ConfigDescription& config = DefaultConfig()) {
      auto value = GetAttributeInteger(attr, config);
      if (value) {
        return *value;
      }
      return def;
    }

    /**
     * Retrieves the string value of the attribute. If the value of the attribute is a reference,
     * this will attempt to resolve the reference to a string value.
     **/
    const std::string* GetAttributeString(xml::Attribute* attr,
                                          const ConfigDescription& config = DefaultConfig()) {
      if (attr != nullptr) {
        if (attr->compiled_value) {
          // Resolve references using the configuration
          Value* value = attr->compiled_value.get();
          if (ValueCast<Reference>(value)) {
            value = ResolveReference(ValueCast<Reference>(value), config);
          } else {
            value = attr->compiled_value.get();
          }

          // Retrieve the string data of the value if possible
          if (value != nullptr) {
            if (String* intValue = ValueCast<String>(value)) {
              return &(*intValue->value);
            } else if (RawString* rawValue = ValueCast<RawString>(value)) {
              return &(*rawValue->value);
            } else if (StyledString* styledStrValue = ValueCast<StyledString>(value)) {
              return &(styledStrValue->value->value);
            } else if (FileReference* strValue = ValueCast<FileReference>(value)) {
              return &(*strValue->path);
            }
          }
        }

        if (!attr->value.empty()) {
          return &attr->value;
        }
      }
      return nullptr;
    }

    /**
     * A version of GetAttributeString that returns a default string if the attribute does not
     * exist or cannot be resolved to an string value.
     **/
    std::string GetAttributeStringDefault(xml::Attribute* attr, std::string def,
                                          const ConfigDescription& config = DefaultConfig()) {
      auto value = GetAttributeString(attr, config);
      if (value) {
        return *value;
      }
      return def;
    }

   private:
      ManifestExtractor* extractor_;
      std::vector<std::unique_ptr<Element>> children_;
      std::string tag_;
      bool featured_ = false;
  };

  friend Element;

  /** Creates a default configuration used to retrieve resources. */
  static ConfigDescription DefaultConfig() {
    ConfigDescription config;
    config.orientation = android::ResTable_config::ORIENTATION_PORT;
    config.density = android::ResTable_config::DENSITY_MEDIUM;
    config.sdkVersion = SDK_CUR_DEVELOPMENT;  // Very high.
    config.screenWidthDp = 320;
    config.screenHeightDp = 480;
    config.smallestScreenWidthDp = 320;
    config.screenLayout |= android::ResTable_config::SCREENSIZE_NORMAL;
    return config;
  }

  bool Extract(android::IDiagnostics* diag);
  bool Dump(text::Printer* printer);
  bool DumpProto(pb::Badging* out_badging);

  /** Recursively visit the xml element tree and return a processed badging element tree. */
  std::unique_ptr<Element> Visit(xml::Element* element, const std::string& parent_tag);

  /** Resets target SDK to 0. */
  void ResetTargetSdk() {
    target_sdk_ = 0;
  }

  /** Raises the target sdk value if the min target is greater than the current target. */
  void RaiseTargetSdk(int32_t min_target) {
    if (min_target > target_sdk_) {
      target_sdk_ = min_target;
    }
  }

  /**
   * Retrieves the default feature group that features are added into when <uses-feature>
   * are not in a <feature-group> element.
   **/
  CommonFeatureGroup* common_feature_group() {
    return commonFeatureGroup_.get();
  }

  /**
   * Retrieves a mapping of density values to Configurations for retrieving resources that would be
   * used for that density setting.
   **/
  const std::map<uint16_t, ConfigDescription> densities() const {
    return densities_;
  }

  /**
   * Retrieves a mapping of locale BCP 47 strings to Configurations for retrieving resources that
   * would be used for that locale setting.
   **/
  const std::map<std::string, ConfigDescription> locales() const {
    return locales_;
  }

  /** Retrieves the current stack of parent during data extraction. */
  const std::vector<Element*>& parent_stack() const {
    return parent_stack_;
  }

  int32_t target_sdk() const {
    return target_sdk_;
  }

  LoadedApk* const apk_;
  DumpManifestOptions& options_;

 private:
  std::unique_ptr<xml::XmlResource> doc_;
  std::unique_ptr<CommonFeatureGroup> commonFeatureGroup_;
  std::map<std::string, ConfigDescription> locales_;
  std::map<uint16_t, ConfigDescription> densities_;
  std::vector<Element*> parent_stack_;
  int32_t target_sdk_ = 0;

  std::unique_ptr<ManifestExtractor::Element> root_element_;
  std::vector<std::unique_ptr<ManifestExtractor::Element>> implied_permissions_;
  std::vector<FeatureGroup*> feature_groups_;
  Components components_;
  Architectures architectures_;
  const SupportsScreen* supports_screen_;
};

template<typename T> T* ElementCast(ManifestExtractor::Element* element);

/** Recurs through the children of the specified root in depth-first order. */
static void ForEachChild(ManifestExtractor::Element* root,
                         std::function<void(ManifestExtractor::Element*)> f) {
  for (auto& child : root->children()) {
    f(child.get());
    ForEachChild(child.get(), f);
  }
}

/**
 * Checks the element and its recursive children for an element that makes the specified
 * conditional function return true. Returns the first element that makes the conditional function
 * return true.
 **/
static ManifestExtractor::Element* FindElement(ManifestExtractor::Element* root,
                                              std::function<bool(ManifestExtractor::Element*)> f) {
  if (f(root)) {
    return root;
  }
  const auto& children = root->children();
  for (auto it = children.rbegin(); it != children.rend(); ++it) {
    if (auto b2 = FindElement(it->get(), f)) {
      return b2;
    }
  }
  return nullptr;
}

/** Represents the <manifest> elements **/
class Manifest : public ManifestExtractor::Element {
 public:
  Manifest() = default;
  bool only_package_name;
  std::string package;
  int32_t versionCode;
  std::string versionName;
  const std::string* split = nullptr;
  const std::string* platformVersionName = nullptr;
  const std::string* platformVersionCode = nullptr;
  const int32_t* platformVersionNameInt = nullptr;
  const int32_t* platformVersionCodeInt = nullptr;
  const int32_t* compilesdkVersion = nullptr;
  const std::string* compilesdkVersionCodename = nullptr;
  const int32_t* installLocation = nullptr;

  void Extract(xml::Element* manifest) override {
    package = GetAttributeStringDefault(FindAttribute(manifest, {}, "package"), "");
    versionCode = GetAttributeIntegerDefault(FindAttribute(manifest, VERSION_CODE_ATTR), 0);
    versionName = GetAttributeStringDefault(FindAttribute(manifest, VERSION_NAME_ATTR), "");
    split = GetAttributeString(FindAttribute(manifest, {}, "split"));

    // Extract the platform build info
    platformVersionName = GetAttributeString(FindAttribute(manifest, {},
                                                           "platformBuildVersionName"));
    platformVersionCode = GetAttributeString(FindAttribute(manifest, {},
                                                           "platformBuildVersionCode"));
    platformVersionNameInt = GetAttributeInteger(FindAttribute(manifest, {},
                                                               "platformBuildVersionName"));
    platformVersionCodeInt = GetAttributeInteger(FindAttribute(manifest, {},
                                                               "platformBuildVersionCode"));

    // Extract the compile sdk info
    compilesdkVersion = GetAttributeInteger(FindAttribute(manifest, COMPILE_SDK_VERSION_ATTR));
    compilesdkVersionCodename = GetAttributeString(
        FindAttribute(manifest, COMPILE_SDK_VERSION_CODENAME_ATTR));
    installLocation = GetAttributeInteger(FindAttribute(manifest, INSTALL_LOCATION_ATTR));
  }

  void ToProto(pb::Badging* out_badging) override {
    auto out_package = out_badging->mutable_package();
    out_package->set_package(package);
    out_package->set_version_code(versionCode);
    out_package->set_version_name(versionName);
    if (compilesdkVersion) {
      out_package->set_compile_sdk_version(*compilesdkVersion);
    }
    if (compilesdkVersionCodename) {
      out_package->set_compile_sdk_version_codename(*compilesdkVersionCodename);
    }
    if (platformVersionName) {
      out_package->set_platform_version_name(*platformVersionName);
    } else if (platformVersionNameInt) {
      out_package->set_platform_version_name(std::to_string(*platformVersionNameInt));
    }
    if (platformVersionCode) {
      out_package->set_platform_version_code(*platformVersionCode);
    } else if (platformVersionCodeInt) {
      out_package->set_platform_version_code(std::to_string(*platformVersionCodeInt));
    }

    if (installLocation) {
      switch (*installLocation) {
        case 0:
          out_package->set_install_location(pb::PackageInfo_InstallLocation_AUTO);
          break;
        case 1:
          out_package->set_install_location(pb::PackageInfo_InstallLocation_INTERNAL_ONLY);
          break;
        case 2:
          out_package->set_install_location(pb::PackageInfo_InstallLocation_PREFER_EXTERNAL);
          break;
        default:
          break;
      }
    }
  }

  void Print(text::Printer* printer) override {
    if (only_package_name) {
      printer->Println(StringPrintf("package: %s", package.data()));
    } else {
      PrintFull(printer);
    }
  }

  void PrintFull(text::Printer* printer) {
    printer->Print(StringPrintf("package: name='%s' ", package.data()));
    printer->Print(StringPrintf("versionCode='%s' ",
                               (versionCode > 0) ? std::to_string(versionCode).data() : ""));
    printer->Print(StringPrintf("versionName='%s'", versionName.data()));

    if (split) {
      printer->Print(StringPrintf(" split='%s'", split->data()));
    }
    if (platformVersionName) {
      printer->Print(StringPrintf(" platformBuildVersionName='%s'", platformVersionName->data()));
    } else if (platformVersionNameInt) {
      printer->Print(StringPrintf(" platformBuildVersionName='%d'", *platformVersionNameInt));
    }
    if (platformVersionCode) {
      printer->Print(StringPrintf(" platformBuildVersionCode='%s'", platformVersionCode->data()));
    } else if (platformVersionCodeInt) {
      printer->Print(StringPrintf(" platformBuildVersionCode='%d'", *platformVersionCodeInt));
    }
    if (compilesdkVersion) {
      printer->Print(StringPrintf(" compileSdkVersion='%d'", *compilesdkVersion));
    }
    if (compilesdkVersionCodename) {
      printer->Print(StringPrintf(" compileSdkVersionCodename='%s'",
                                 compilesdkVersionCodename->data()));
    }
    printer->Print("\n");

    if (installLocation) {
      switch (*installLocation) {
        case 0:
          printer->Print("install-location:'auto'\n");
          break;
        case 1:
          printer->Print("install-location:'internalOnly'\n");
          break;
        case 2:
          printer->Print("install-location:'preferExternal'\n");
          break;
        default:
          break;
      }
    }
  }
};

/** Represents <application> elements. **/
class Application : public ManifestExtractor::Element {
 public:
  Application() = default;
  std::string label;
  std::string icon;
  std::string banner;
  int32_t is_game;
  int32_t debuggable;
  int32_t test_only;
  bool has_multi_arch;

  /** Mapping from locales to app names. */
  std::map<std::string, std::string> locale_labels;

  /** Mapping from densities to app icons. */
  std::map<uint16_t, std::string> density_icons;

  void Extract(xml::Element* element) override {
    label = GetAttributeStringDefault(FindAttribute(element, LABEL_ATTR), "");
    icon = GetAttributeStringDefault(FindAttribute(element, ICON_ATTR), "");
    test_only = GetAttributeIntegerDefault(FindAttribute(element, TEST_ONLY_ATTR), 0);
    banner = GetAttributeStringDefault(FindAttribute(element, BANNER_ATTR), "");
    is_game = GetAttributeIntegerDefault(FindAttribute(element, ISGAME_ATTR), 0);
    debuggable = GetAttributeIntegerDefault(FindAttribute(element, DEBUGGABLE_ATTR), 0);

    // We must search by name because the multiArch flag hasn't been API
    // frozen yet.
    has_multi_arch = (GetAttributeIntegerDefault(
        FindAttribute(element, kAndroidNamespace, "multiArch"), 0) != 0);

    // Retrieve the app names for every locale the app supports
    auto attr = FindAttribute(element, LABEL_ATTR);
    for (auto& config : extractor()->locales()) {
      if (auto label = GetAttributeString(attr, config.second)) {
        if (label) {
          locale_labels.insert(std::make_pair(config.first, *label));
        }
      }
    }

    // Retrieve the icons for the densities the app supports
    attr = FindAttribute(element, ICON_ATTR);
    for (auto& config : extractor()->densities()) {
      if (auto resource = GetAttributeString(attr, config.second)) {
        if (resource) {
          density_icons.insert(std::make_pair(config.first, *resource));
        }
      }
    }
  }

  void Print(text::Printer* printer) override {
    // Print the labels for every locale
    for (auto p : locale_labels) {
      if (p.first.empty()) {
        printer->Print(StringPrintf("application-label:'%s'\n",
                                    android::ResTable::normalizeForOutput(p.second.data())
                                        .c_str()));
      } else {
        printer->Print(StringPrintf("application-label-%s:'%s'\n", p.first.data(),
                                    android::ResTable::normalizeForOutput(p.second.data())
                                        .c_str()));
      }
    }

    // Print the icon paths for every density
    for (auto p : density_icons) {
      printer->Print(StringPrintf("application-icon-%d:'%s'\n", p.first, p.second.data()));
    }

    // Print the application info
    printer->Print(StringPrintf("application: label='%s' ",
                                android::ResTable::normalizeForOutput(label.data()).c_str()));
    printer->Print(StringPrintf("icon='%s'", icon.data()));
    if (!banner.empty()) {
      printer->Print(StringPrintf(" banner='%s'", banner.data()));
    }
    printer->Print("\n");

    if (test_only != 0) {
      printer->Print(StringPrintf("testOnly='%d'\n", test_only));
    }
    if (is_game != 0) {
      printer->Print("application-isGame\n");
    }
    if (debuggable != 0) {
      printer->Print("application-debuggable\n");
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    auto application = out_badging->mutable_application();
    application->set_label(android::ResTable::normalizeForOutput(label.data()));
    application->set_icon(icon);
    application->set_banner(banner);
    application->set_test_only(test_only != 0);
    application->set_game(is_game != 0);
    application->set_debuggable(debuggable != 0);

    auto out_locale_labels = application->mutable_locale_labels();
    for (auto& p : locale_labels) {
      if (!p.first.empty()) {
        (*out_locale_labels)[p.first] = p.second;
      }
    }
    auto out_density_icons = application->mutable_density_icons();
    for (auto& p : density_icons) {
      (*out_density_icons)[p.first] = p.second;
    }
  }
};

/** Represents <uses-sdk> elements. **/
class UsesSdkBadging : public ManifestExtractor::Element {
 public:
  UsesSdkBadging() = default;
  const int32_t* min_sdk = nullptr;
  const std::string* min_sdk_name = nullptr;
  const int32_t* max_sdk = nullptr;
  const int32_t* target_sdk = nullptr;
  const std::string* target_sdk_name = nullptr;

  void Extract(xml::Element* element) override {
    min_sdk = GetAttributeInteger(FindAttribute(element, MIN_SDK_VERSION_ATTR));
    min_sdk_name = GetAttributeString(FindAttribute(element, MIN_SDK_VERSION_ATTR));
    max_sdk = GetAttributeInteger(FindAttribute(element, MAX_SDK_VERSION_ATTR));
    target_sdk = GetAttributeInteger(FindAttribute(element, TARGET_SDK_VERSION_ATTR));
    target_sdk_name = GetAttributeString(FindAttribute(element, TARGET_SDK_VERSION_ATTR));

    // Resets target SDK first. This is required if APK contains multiple <uses-sdk> elements,
    // we only need to take the latest values.
    extractor()->ResetTargetSdk();

    // Detect the target sdk of the element
    if  ((min_sdk_name && *min_sdk_name == "Donut")
        || (target_sdk_name && *target_sdk_name == "Donut")) {
      extractor()->RaiseTargetSdk(SDK_DONUT);
    }
    if (min_sdk) {
      extractor()->RaiseTargetSdk(*min_sdk);
    }
    if (target_sdk) {
      extractor()->RaiseTargetSdk(*target_sdk);
    } else if (target_sdk_name) {
      extractor()->RaiseTargetSdk(SDK_CUR_DEVELOPMENT);
    }
  }

  void Print(text::Printer* printer) override {
    if (min_sdk) {
      printer->Print(StringPrintf("minSdkVersion:'%d'\n", *min_sdk));
    } else if (min_sdk_name) {
      printer->Print(StringPrintf("minSdkVersion:'%s'\n", min_sdk_name->data()));
    }
    if (max_sdk) {
      printer->Print(StringPrintf("maxSdkVersion:'%d'\n", *max_sdk));
    }
    if (target_sdk) {
      printer->Print(StringPrintf("targetSdkVersion:'%d'\n", *target_sdk));
    } else if (target_sdk_name) {
      printer->Print(StringPrintf("targetSdkVersion:'%s'\n", target_sdk_name->data()));
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    auto out_sdks = out_badging->mutable_uses_sdk();
    if (min_sdk) {
      out_sdks->set_min_sdk_version(*min_sdk);
    } else if (min_sdk_name) {
      out_sdks->set_min_sdk_version_name(*min_sdk_name);
    }
    if (max_sdk) {
      out_sdks->set_max_sdk_version(*max_sdk);
    }
    if (target_sdk) {
      out_sdks->set_target_sdk_version(*target_sdk);
    } else if (target_sdk_name) {
      out_sdks->set_target_sdk_version_name(*target_sdk_name);
    }
  }
};

/** Represents <uses-configuration> elements. **/
class UsesConfiguarion : public ManifestExtractor::Element {
 public:
  UsesConfiguarion() = default;
  int32_t req_touch_screen = 0;
  int32_t req_keyboard_type = 0;
  int32_t req_hard_keyboard = 0;
  int32_t req_navigation = 0;
  int32_t req_five_way_nav = 0;

  void Extract(xml::Element* element) override {
    req_touch_screen = GetAttributeIntegerDefault(
        FindAttribute(element, REQ_TOUCH_SCREEN_ATTR), 0);
    req_keyboard_type = GetAttributeIntegerDefault(
        FindAttribute(element, REQ_KEYBOARD_TYPE_ATTR), 0);
    req_hard_keyboard = GetAttributeIntegerDefault(
        FindAttribute(element, REQ_HARD_KEYBOARD_ATTR), 0);
    req_navigation = GetAttributeIntegerDefault(
        FindAttribute(element, REQ_NAVIGATION_ATTR), 0);
    req_five_way_nav = GetAttributeIntegerDefault(
        FindAttribute(element, REQ_FIVE_WAY_NAV_ATTR), 0);
  }

  void Print(text::Printer* printer) override {
    printer->Print("uses-configuration:");
    if (req_touch_screen != 0) {
      printer->Print(StringPrintf(" reqTouchScreen='%d'", req_touch_screen));
    }
    if (req_keyboard_type != 0) {
      printer->Print(StringPrintf(" reqKeyboardType='%d'", req_keyboard_type));
    }
    if (req_hard_keyboard != 0) {
      printer->Print(StringPrintf(" reqHardKeyboard='%d'", req_hard_keyboard));
    }
    if (req_navigation != 0) {
      printer->Print(StringPrintf(" reqNavigation='%d'", req_navigation));
    }
    if (req_five_way_nav != 0) {
      printer->Print(StringPrintf(" reqFiveWayNav='%d'", req_five_way_nav));
    }
    printer->Print("\n");
  }

  void ToProto(pb::Badging* out_badging) override {
    auto out_configuration = out_badging->add_uses_configurations();
    out_configuration->set_req_touch_screen(req_touch_screen);
    out_configuration->set_req_keyboard_type(req_keyboard_type);
    out_configuration->set_req_hard_keyboard(req_hard_keyboard);
    out_configuration->set_req_navigation(req_navigation);
    out_configuration->set_req_five_way_nav(req_five_way_nav);
  }
};

/** Represents <supports-screen> elements. **/
class SupportsScreen : public ManifestExtractor::Element {
 public:
  SupportsScreen() = default;
  int32_t small_screen = 1;
  int32_t normal_screen = 1;
  int32_t large_screen  = 1;
  int32_t xlarge_screen = 1;
  int32_t any_density = 1;
  int32_t requires_smallest_width_dp = 0;
  int32_t compatible_width_limit_dp = 0;
  int32_t largest_width_limit_dp = 0;

  void Extract(xml::Element* element) override {
    small_screen = GetAttributeIntegerDefault(FindAttribute(element, SMALL_SCREEN_ATTR), 1);
    normal_screen = GetAttributeIntegerDefault(FindAttribute(element, NORMAL_SCREEN_ATTR), 1);
    large_screen = GetAttributeIntegerDefault(FindAttribute(element, LARGE_SCREEN_ATTR), 1);
    xlarge_screen = GetAttributeIntegerDefault(FindAttribute(element, XLARGE_SCREEN_ATTR), 1);
    any_density = GetAttributeIntegerDefault(FindAttribute(element, ANY_DENSITY_ATTR), 1);

    requires_smallest_width_dp = GetAttributeIntegerDefault(
        FindAttribute(element, REQUIRES_SMALLEST_WIDTH_DP_ATTR), 0);
    compatible_width_limit_dp = GetAttributeIntegerDefault(
        FindAttribute(element, COMPATIBLE_WIDTH_LIMIT_DP_ATTR), 0);
    largest_width_limit_dp = GetAttributeIntegerDefault(
        FindAttribute(element, LARGEST_WIDTH_LIMIT_DP_ATTR), 0);

    // For modern apps, if screen size buckets haven't been specified
    // but the new width ranges have, then infer the buckets from them.
    if (small_screen > 0 && normal_screen > 0 && large_screen > 0 && xlarge_screen > 0
        && requires_smallest_width_dp > 0) {
      int32_t compat_width = (compatible_width_limit_dp > 0) ? compatible_width_limit_dp
                                                             : requires_smallest_width_dp;
      small_screen = (requires_smallest_width_dp <= 240 && compat_width >= 240) ? -1 : 0;
      normal_screen = (requires_smallest_width_dp <= 320 && compat_width >= 320) ? -1 : 0;
      large_screen = (requires_smallest_width_dp <= 480 && compat_width >= 480) ? -1 : 0;
      xlarge_screen = (requires_smallest_width_dp <= 720 && compat_width >= 720) ? -1 : 0;
    }
  }

  void PrintScreens(text::Printer* printer, int32_t target_sdk) const {
    // Print the formatted screen info
    printer->Print("supports-screens:");
    if (IsSmallScreenSupported(target_sdk)) {
      printer->Print(" 'small'");
    }
    if (normal_screen != 0) {
      printer->Print(" 'normal'");
    }
    if (IsLargeScreenSupported(target_sdk)) {
      printer->Print(" 'large'");
    }
    if (IsXLargeScreenSupported(target_sdk)) {
      printer->Print(" 'xlarge'");
    }
    printer->Print("\n");
    printer->Print(StringPrintf("supports-any-density: '%s'\n",
                                (IsAnyDensitySupported(target_sdk)) ? "true" : "false"));
    if (requires_smallest_width_dp > 0) {
      printer->Print(StringPrintf("requires-smallest-width:'%d'\n", requires_smallest_width_dp));
    }
    if (compatible_width_limit_dp > 0) {
      printer->Print(StringPrintf("compatible-width-limit:'%d'\n", compatible_width_limit_dp));
    }
    if (largest_width_limit_dp > 0) {
      printer->Print(StringPrintf("largest-width-limit:'%d'\n", largest_width_limit_dp));
    }
  }

  void ToProtoScreens(pb::Badging* out_badging, int32_t target_sdk) const {
    auto supports_screen = out_badging->mutable_supports_screen();
    if (IsSmallScreenSupported(target_sdk)) {
      supports_screen->add_screens(pb::SupportsScreen_ScreenType_SMALL);
    }
    if (normal_screen != 0) {
      supports_screen->add_screens(pb::SupportsScreen_ScreenType_NORMAL);
    }
    if (IsLargeScreenSupported(target_sdk)) {
      supports_screen->add_screens(pb::SupportsScreen_ScreenType_LARGE);
    }
    if (IsXLargeScreenSupported(target_sdk)) {
      supports_screen->add_screens(pb::SupportsScreen_ScreenType_XLARGE);
    }
    supports_screen->set_supports_any_densities(IsAnyDensitySupported(target_sdk));
    supports_screen->set_requires_smallest_width_dp(requires_smallest_width_dp);
    supports_screen->set_compatible_width_limit_dp(compatible_width_limit_dp);
    supports_screen->set_largest_width_limit_dp(largest_width_limit_dp);
  }

 private:
  // Determine default values for any unspecified screen sizes,
  // based on the target SDK of the package.  As of 4 (donut)
  // the screen size support was introduced, so all default to
  // enabled.
  bool IsSmallScreenSupported(int32_t target_sdk) const {
    if (small_screen > 0) {
      return target_sdk >= SDK_DONUT;
    }
    return small_screen != 0;
  }

  bool IsLargeScreenSupported(int32_t target_sdk) const {
    if (large_screen > 0) {
      return target_sdk >= SDK_DONUT;
    }
    return large_screen != 0;
  }

  bool IsXLargeScreenSupported(int32_t target_sdk) const {
    if (xlarge_screen > 0) {
      return target_sdk >= SDK_GINGERBREAD;
    }
    return xlarge_screen != 0;
  }

  bool IsAnyDensitySupported(int32_t target_sdk) const {
    if (any_density > 0) {
      return target_sdk >= SDK_DONUT || requires_smallest_width_dp > 0 ||
             compatible_width_limit_dp > 0;
    }
    return any_density != 0;
  }
};

/** Represents <feature-group> elements. **/
class FeatureGroup : public ManifestExtractor::Element {
 public:
  FeatureGroup() = default;
  std::string label;
  int32_t open_gles_version = 0;

  void Extract(xml::Element* element) override {
    label = GetAttributeStringDefault(FindAttribute(element, LABEL_ATTR), "");
  }

  virtual void PrintGroup(text::Printer* printer) {
    printer->Print(StringPrintf("feature-group: label='%s'\n", label.data()));
    if (open_gles_version > 0) {
      printer->Print(StringPrintf("  uses-gl-es: '0x%x'\n", open_gles_version));
    }

    for (auto feature : features_) {
      printer->Print(StringPrintf("  uses-feature%s: name='%s'",
                                 (feature.second.required ? "" : "-not-required"),
                                 feature.first.data()));
      if (feature.second.version > 0) {
        printer->Print(StringPrintf(" version='%d'", feature.second.version));
      }
      printer->Print("\n");
    }
  }

  virtual void GroupToProto(pb::Badging* out_badging) {
    auto feature_group = out_badging->add_feature_groups();
    feature_group->set_label(label);
    feature_group->set_open_gles_version(open_gles_version);
    for (auto& feature : features_) {
      auto out_feature = feature_group->add_features();
      out_feature->set_name(feature.first);
      out_feature->set_required(feature.second.required);
      out_feature->set_version(feature.second.version);
    }
  }

  /** Adds a feature to the feature group. */
  void AddFeature(const std::string& name, bool required = true, int32_t version = -1) {
    features_.insert_or_assign(name, Feature{required, version});
    if (required) {
      if (name == "android.hardware.camera.autofocus" ||
          name == "android.hardware.camera.flash") {
        AddFeature("android.hardware.camera", true);
      } else if (name == "android.hardware.location.gps" ||
                 name == "android.hardware.location.network") {
        AddFeature("android.hardware.location", true);
      } else if (name == "android.hardware.faketouch.multitouch") {
        AddFeature("android.hardware.faketouch", true);
      } else if (name == "android.hardware.faketouch.multitouch.distinct" ||
                 name == "android.hardware.faketouch.multitouch.jazzhands") {
        AddFeature("android.hardware.faketouch.multitouch", true);
        AddFeature("android.hardware.faketouch", true);
      } else if (name == "android.hardware.touchscreen.multitouch") {
        AddFeature("android.hardware.touchscreen", true);
      } else if (name == "android.hardware.touchscreen.multitouch.distinct" ||
                 name == "android.hardware.touchscreen.multitouch.jazzhands") {
        AddFeature("android.hardware.touchscreen.multitouch", true);
        AddFeature("android.hardware.touchscreen", true);
      } else if (name == "android.hardware.opengles.aep") {
        const int kOpenGLESVersion31 = 0x00030001;
        if (kOpenGLESVersion31 > open_gles_version) {
          open_gles_version = kOpenGLESVersion31;
        }
      }
    }
  }

  /** Returns true if the feature group has the given feature. */
  virtual bool HasFeature(const std::string& name) {
    return features_.find(name) != features_.end();
  }

  /** Merges the features of another feature group into this group. */
  void Merge(FeatureGroup* group) {
    open_gles_version = std::max(open_gles_version, group->open_gles_version);
    for (auto& feature : group->features_) {
      features_.insert(feature);
    }
  }

 protected:
  struct Feature {
   public:
    bool required = false;
    int32_t version = -1;
  };

  /* Mapping of feature names to their properties. */
  std::map<std::string, Feature> features_;
};

/**
 * Represents the default feature group for the application if no <feature-group> elements are
 * present in the manifest.
 **/
class CommonFeatureGroup : public FeatureGroup {
 public:
  CommonFeatureGroup() = default;
  void PrintGroup(text::Printer* printer) override {
    FeatureGroup::PrintGroup(printer);

    // Also print the implied features
    for (auto feature : implied_features_) {
      if (features_.find(feature.first) == features_.end()) {
        const char* sdk23 = feature.second.implied_from_sdk_k23 ? "-sdk-23" : "";
        printer->Print(StringPrintf("  uses-feature%s: name='%s'\n", sdk23, feature.first.data()));
        printer->Print(StringPrintf("  uses-implied-feature%s: name='%s' reason='", sdk23,
                                    feature.first.data()));

        // Print the reasons as a sentence
        size_t count = 0;
        for (auto reason : feature.second.reasons) {
          printer->Print(reason);
          if (count + 2 < feature.second.reasons.size()) {
            printer->Print(", ");
          } else if (count + 1 < feature.second.reasons.size()) {
            printer->Print(", and ");
          }
          count++;
        }
        printer->Print("'\n");
      }
    }
  }

  virtual void GroupToProto(pb::Badging* out_badging) override {
    FeatureGroup::GroupToProto(out_badging);
    auto feature_group =
        out_badging->mutable_feature_groups(out_badging->feature_groups_size() - 1);
    for (auto& feature : implied_features_) {
      if (features_.find(feature.first) == features_.end()) {
        auto out_feature = feature_group->add_features();
        out_feature->set_name(feature.first);
        auto implied_data = out_feature->mutable_implied_data();
        implied_data->set_from_sdk_23_permission(feature.second.implied_from_sdk_k23);
        for (auto& reason : feature.second.reasons) {
          implied_data->add_reasons(reason);
        }
      }
    }
  }

  /** Returns true if the feature group has the given feature. */
  bool HasFeature(const std::string& name) override {
    return FeatureGroup::HasFeature(name)
        || implied_features_.find(name) != implied_features_.end();
  }

  /** Adds a feature to a set of implied features not explicitly requested in the manifest. */
  void addImpliedFeature(const std::string& name, const std::string& reason, bool sdk23 = false) {
    auto entry = implied_features_.find(name);
    if (entry == implied_features_.end()) {
      implied_features_.insert(std::make_pair(name, ImpliedFeature(sdk23)));
      entry = implied_features_.find(name);
    }

    // A non-sdk 23 implied feature takes precedence.
    if (entry->second.implied_from_sdk_k23 && !sdk23) {
      entry->second.implied_from_sdk_k23 = false;
    }

    entry->second.reasons.insert(reason);
  }

  /**
   * Adds a feature to a set of implied features for all features that are implied by the presence
   * of the permission.
   **/
  void addImpliedFeaturesForPermission(int32_t targetSdk, const std::string& name, bool sdk23) {
    if (name == "android.permission.CAMERA") {
      addImpliedFeature("android.hardware.camera",
                        StringPrintf("requested %s permission", name.data()),
                        sdk23);

    } else if (name == "android.permission.ACCESS_FINE_LOCATION") {
      if (targetSdk < SDK_LOLLIPOP) {
        addImpliedFeature("android.hardware.location.gps",
                          StringPrintf("requested %s permission", name.data()),
                          sdk23);
        addImpliedFeature("android.hardware.location.gps",
                          StringPrintf("targetSdkVersion < %d", SDK_LOLLIPOP),
                          sdk23);
      }
      addImpliedFeature("android.hardware.location",
                        StringPrintf("requested %s permission", name.data()),
                        sdk23);

    } else if (name == "android.permission.ACCESS_COARSE_LOCATION") {
      if (targetSdk < SDK_LOLLIPOP) {
        addImpliedFeature("android.hardware.location.network",
                          StringPrintf("requested %s permission", name.data()),
                          sdk23);
        addImpliedFeature("android.hardware.location.network",
                          StringPrintf("targetSdkVersion < %d", SDK_LOLLIPOP),
                          sdk23);
      }
      addImpliedFeature("android.hardware.location",
                        StringPrintf("requested %s permission", name.data()),
                        sdk23);

    } else if (name == "android.permission.ACCESS_MOCK_LOCATION" ||
        name == "android.permission.ACCESS_LOCATION_EXTRA_COMMANDS" ||
        name == "android.permission.INSTALL_LOCATION_PROVIDER") {
      addImpliedFeature("android.hardware.location",
                        StringPrintf("requested %s permission", name.data()),
                        sdk23);

    } else if (name == "android.permission.BLUETOOTH" ||
        name == "android.permission.BLUETOOTH_ADMIN") {
      if (targetSdk > SDK_DONUT) {
        addImpliedFeature("android.hardware.bluetooth",
                          StringPrintf("requested %s permission", name.data()),
                          sdk23);
        addImpliedFeature("android.hardware.bluetooth",
                          StringPrintf("targetSdkVersion > %d", SDK_DONUT),
                          sdk23);
      }

    } else if (name == "android.permission.RECORD_AUDIO") {
      addImpliedFeature("android.hardware.microphone",
                        StringPrintf("requested %s permission", name.data()),
                        sdk23);

    } else if (name == "android.permission.ACCESS_WIFI_STATE" ||
        name == "android.permission.CHANGE_WIFI_STATE" ||
        name == "android.permission.CHANGE_WIFI_MULTICAST_STATE") {
      addImpliedFeature("android.hardware.wifi",
                        StringPrintf("requested %s permission", name.data()),
                        sdk23);

    } else if (name == "android.permission.CALL_PHONE" ||
        name == "android.permission.CALL_PRIVILEGED" ||
        name == "android.permission.MODIFY_PHONE_STATE" ||
        name == "android.permission.PROCESS_OUTGOING_CALLS" ||
        name == "android.permission.READ_SMS" ||
        name == "android.permission.RECEIVE_SMS" ||
        name == "android.permission.RECEIVE_MMS" ||
        name == "android.permission.RECEIVE_WAP_PUSH" ||
        name == "android.permission.SEND_SMS" ||
        name == "android.permission.WRITE_APN_SETTINGS" ||
        name == "android.permission.WRITE_SMS") {
      addImpliedFeature("android.hardware.telephony",
                        "requested a telephony permission",
                        sdk23);
    }
  }

 private:
  /**
   * Represents a feature that has been automatically added due to a pre-requisite or for some
   * other reason.
   */
  struct ImpliedFeature {
    explicit ImpliedFeature(bool sdk23 = false) : implied_from_sdk_k23(sdk23) {}

    /** List of human-readable reasons for why this feature was implied. */
    std::set<std::string> reasons;

    // Was this implied by a permission from SDK 23 (<uses-permission-sdk-23 />)
    bool implied_from_sdk_k23;
  };

  /* Mapping of implied feature names to their properties. */
  std::map<std::string, ImpliedFeature> implied_features_;
};

/** Represents <uses-feature> elements. **/
class UsesFeature : public ManifestExtractor::Element {
 public:
  UsesFeature() = default;
  void Extract(xml::Element* element) override {
    const std::string* name = GetAttributeString(FindAttribute(element, NAME_ATTR));
    int32_t* gl = GetAttributeInteger(FindAttribute(element, GL_ES_VERSION_ATTR));
    bool required = GetAttributeIntegerDefault(
        FindAttribute(element, REQUIRED_ATTR), true) != 0;
    int32_t version = GetAttributeIntegerDefault(
        FindAttribute(element, kAndroidNamespace, "version"), 0);

    // Add the feature to the parent feature group element if one exists; otherwise, add it to the
    // common feature group
    FeatureGroup* feature_group = ElementCast<FeatureGroup>(extractor()->parent_stack()[0]);
    if (!feature_group) {
      feature_group = extractor()->common_feature_group();
    } else {
      // All features in side of <feature-group> elements are required.
      required = true;
    }

    if (name) {
      feature_group->AddFeature(*name, required, version);
    } else if (gl) {
      feature_group->open_gles_version = std::max(feature_group->open_gles_version, *gl);
    }
  }
};

/** Represents <uses-permission> elements. **/
class UsesPermission : public ManifestExtractor::Element {
 public:
  UsesPermission() = default;
  bool implied;
  std::string name;
  std::vector<std::string> requiredFeatures;
  std::vector<std::string> requiredNotFeatures;
  int32_t required = true;
  int32_t maxSdkVersion = -1;
  int32_t usesPermissionFlags = 0;
  std::string impliedReason;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
    std::string feature =
        GetAttributeStringDefault(FindAttribute(element, REQUIRED_FEATURE_ATTR), "");
    if (!feature.empty()) {
      requiredFeatures.push_back(feature);
    }
    feature = GetAttributeStringDefault(FindAttribute(element, REQUIRED_NOT_FEATURE_ATTR), "");
    if (!feature.empty()) {
      requiredNotFeatures.push_back(feature);
    }

    required = GetAttributeIntegerDefault(FindAttribute(element, REQUIRED_ATTR), 1);
    maxSdkVersion = GetAttributeIntegerDefault(
        FindAttribute(element, MAX_SDK_VERSION_ATTR), -1);
    usesPermissionFlags = GetAttributeIntegerDefault(
        FindAttribute(element, USES_PERMISSION_FLAGS_ATTR), 0);

    if (!name.empty()) {
      CommonFeatureGroup* common = extractor()->common_feature_group();
      common->addImpliedFeaturesForPermission(extractor()->target_sdk(), name, false);
    }
  }

  void Print(text::Printer* printer) override {
    if (!name.empty()) {
      printer->Print(StringPrintf("uses-permission: name='%s'", name.data()));
      if (maxSdkVersion >= 0) {
        printer->Print(StringPrintf(" maxSdkVersion='%d'", maxSdkVersion));
      }
      if ((usesPermissionFlags & kNeverForLocation) != 0) {
        printer->Print(StringPrintf(" usesPermissionFlags='neverForLocation'"));
      }
      printer->Print("\n");
      for (const std::string& requiredFeature : requiredFeatures) {
        printer->Print(StringPrintf("  required-feature='%s'\n", requiredFeature.data()));
      }
      for (const std::string& requiredNotFeature : requiredNotFeatures) {
        printer->Print(StringPrintf("  required-not-feature='%s'\n", requiredNotFeature.data()));
      }
      if (required == 0) {
        printer->Print(StringPrintf("optional-permission: name='%s'", name.data()));
        if (maxSdkVersion >= 0) {
          printer->Print(StringPrintf(" maxSdkVersion='%d'", maxSdkVersion));
        }
        if ((usesPermissionFlags & kNeverForLocation) != 0) {
          printer->Print(StringPrintf(" usesPermissionFlags='neverForLocation'"));
        }
        printer->Print("\n");
      }
    }
    if (implied) {
      printer->Print(StringPrintf("uses-implied-permission: name='%s'", name.data()));
      if (maxSdkVersion >= 0) {
        printer->Print(StringPrintf(" maxSdkVersion='%d'", maxSdkVersion));
      }
      if ((usesPermissionFlags & kNeverForLocation) != 0) {
        printer->Print(StringPrintf(" usesPermissionFlags='neverForLocation'"));
      }
      printer->Print(StringPrintf(" reason='%s'\n", impliedReason.data()));
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    if (!name.empty()) {
      auto permission = out_badging->add_uses_permissions();
      permission->set_name(name);
      if (maxSdkVersion > 0) {
        permission->set_max_sdk_version(maxSdkVersion);
      }
      if ((usesPermissionFlags & kNeverForLocation) != 0) {
        permission->mutable_permission_flags()->set_never_for_location(true);
      }
      for (auto& requiredFeature : requiredFeatures) {
        permission->add_required_features(requiredFeature);
      }
      for (auto& requiredNotFeature : requiredNotFeatures) {
        permission->add_required_not_features(requiredNotFeature);
      }
      permission->set_required(required != 0);
      permission->set_implied(implied);
    }
  }
};

/** Represents <required-feature> elements. **/
class RequiredFeature : public ManifestExtractor::Element {
 public:
  RequiredFeature() = default;
  std::string name;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
    auto parent_stack = extractor()->parent_stack();
    if (!name.empty() && ElementCast<UsesPermission>(parent_stack[0])) {
      UsesPermission* uses_permission = ElementCast<UsesPermission>(parent_stack[0]);
      uses_permission->requiredFeatures.push_back(name);
    }
  }
};

/** Represents <required-not-feature> elements. **/
class RequiredNotFeature : public ManifestExtractor::Element {
 public:
  RequiredNotFeature() = default;
  std::string name;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
    auto parent_stack = extractor()->parent_stack();
    if (!name.empty() && ElementCast<UsesPermission>(parent_stack[0])) {
      UsesPermission* uses_permission = ElementCast<UsesPermission>(parent_stack[0]);
      uses_permission->requiredNotFeatures.push_back(name);
    }
  }
};

/** Represents <uses-permission-sdk-23> elements. **/
class UsesPermissionSdk23 : public ManifestExtractor::Element {
 public:
  UsesPermissionSdk23() = default;
  const std::string* name = nullptr;
  const int32_t* maxSdkVersion = nullptr;

  void Extract(xml::Element* element) override {
    name = GetAttributeString(FindAttribute(element, NAME_ATTR));
    maxSdkVersion = GetAttributeInteger(FindAttribute(element, MAX_SDK_VERSION_ATTR));

    if (name) {
      CommonFeatureGroup* common = extractor()->common_feature_group();
      common->addImpliedFeaturesForPermission(extractor()->target_sdk(), *name, true);
    }
  }

  void Print(text::Printer* printer) override {
    if (name) {
      printer->Print(StringPrintf("uses-permission-sdk-23: name='%s'", name->data()));
      if (maxSdkVersion) {
        printer->Print(StringPrintf(" maxSdkVersion='%d'", *maxSdkVersion));
      }
      printer->Print("\n");
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    if (name) {
      auto permission = out_badging->add_uses_permissions();
      permission->set_sdk23_and_above(true);
      permission->set_name(*name);
      if (maxSdkVersion) {
        permission->set_max_sdk_version(*maxSdkVersion);
      }
    }
  }
};

/** Represents <permission> elements. These elements are only printing when dumping permissions. **/
class Permission : public ManifestExtractor::Element {
 public:
  Permission() = default;
  std::string name;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
  }

  void Print(text::Printer* printer) override {
    if (extractor()->options_.only_permissions && !name.empty()) {
      printer->Print(StringPrintf("permission: %s\n", name.data()));
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    if (!name.empty()) {
      out_badging->add_permissions()->set_name(name);
    }
  }
};

/** Represents <activity> elements. **/
class Activity : public ManifestExtractor::Element {
 public:
  Activity() = default;
  std::string name;
  std::string icon;
  std::string label;
  std::string banner;

  bool has_component_ = false;
  bool has_launcher_category = false;
  bool has_leanback_launcher_category = false;
  bool has_main_action = false;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
    label = GetAttributeStringDefault(FindAttribute(element, LABEL_ATTR), "");
    icon = GetAttributeStringDefault(FindAttribute(element, ICON_ATTR), "");
    banner = GetAttributeStringDefault(FindAttribute(element, BANNER_ATTR), "");

    // Retrieve the package name from the manifest
    std::string package;
    for (auto& parent : extractor()->parent_stack()) {
      if (auto manifest = ElementCast<Manifest>(parent)) {
        package = manifest->package;
        break;
      }
    }

    // Fully qualify the activity name
    ssize_t idx = name.find('.');
    if (idx == 0) {
      name = package + name;
    } else if (idx < 0) {
      name = package + "." + name;
    }

    auto orientation = GetAttributeInteger(FindAttribute(element, SCREEN_ORIENTATION_ATTR));
    if (orientation) {
      CommonFeatureGroup* common = extractor()->common_feature_group();
      int orien = *orientation;
      if (orien == 0 || orien == 6 || orien == 8) {
        // Requests landscape, sensorLandscape, or reverseLandscape.
        common->addImpliedFeature("android.hardware.screen.landscape",
                                  "one or more activities have specified a landscape orientation",
                                  false);
      } else if (orien == 1 || orien == 7 || orien == 9) {
        // Requests portrait, sensorPortrait, or reversePortrait.
        common->addImpliedFeature("android.hardware.screen.portrait",
                                  "one or more activities have specified a portrait orientation",
                                  false);
      }
    }
  }

  void Print(text::Printer* printer) override {
    // Print whether the activity has the HOME category and a the MAIN action
    if (has_main_action && has_launcher_category) {
      printer->Print("launchable-activity:");
      if (!name.empty()) {
        printer->Print(StringPrintf(" name='%s' ", name.data()));
      }
      printer->Print(StringPrintf(" label='%s' icon='%s'\n",
                                  android::ResTable::normalizeForOutput(label.data()).c_str(),
                                  icon.data()));
    }

    // Print wether the activity has the HOME category and a the MAIN action
    if (has_leanback_launcher_category) {
      printer->Print("leanback-launchable-activity:");
      if (!name.empty()) {
        printer->Print(StringPrintf(" name='%s' ", name.data()));
      }
      printer->Print(StringPrintf(" label='%s' icon='%s' banner='%s'\n",
                                  android::ResTable::normalizeForOutput(label.data()).c_str(),
                                  icon.data(), banner.data()));
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    if (has_main_action && has_launcher_category) {
      auto activity = out_badging->mutable_launchable_activity();
      activity->set_name(name);
      activity->set_label(android::ResTable::normalizeForOutput(label.data()));
      activity->set_icon(icon);
    }
    if (has_leanback_launcher_category) {
      auto activity = out_badging->mutable_leanback_launchable_activity();
      activity->set_name(name);
      activity->set_label(android::ResTable::normalizeForOutput(label.data()));
      activity->set_icon(icon);
      activity->set_banner(banner);
    }
  }
};

/** Represents <intent-filter> elements. */
class IntentFilter : public ManifestExtractor::Element {
 public:
  IntentFilter() = default;
};

/** Represents <category> elements. */
class Category : public ManifestExtractor::Element {
 public:
  Category() = default;
  std::string component = "";

  void Extract(xml::Element* element) override {
    const std::string* category = GetAttributeString(FindAttribute(element, NAME_ATTR));

    auto parent_stack = extractor()->parent_stack();
    if (category && ElementCast<IntentFilter>(parent_stack[0])
        && ElementCast<Activity>(parent_stack[1])) {
      Activity* activity = ElementCast<Activity>(parent_stack[1]);

      if (*category == "android.intent.category.LAUNCHER") {
        activity->has_launcher_category = true;
      } else if (*category == "android.intent.category.LEANBACK_LAUNCHER") {
        activity->has_leanback_launcher_category = true;
      } else if (*category == "android.intent.category.HOME") {
        component = "launcher";
      }
    }
  }
};

/**
 * Represents <provider> elements. The elements may have an <intent-filter> which may have <action>
 * elements nested within.
 **/
class Provider : public ManifestExtractor::Element {
 public:
  Provider() = default;
  bool has_required_saf_attributes = false;

  void Extract(xml::Element* element) override {
    const int32_t* exported = GetAttributeInteger(FindAttribute(element, EXPORTED_ATTR));
    const int32_t* grant_uri_permissions = GetAttributeInteger(
        FindAttribute(element, GRANT_URI_PERMISSIONS_ATTR));
    const std::string* permission = GetAttributeString(
        FindAttribute(element, PERMISSION_ATTR));

    has_required_saf_attributes = ((exported && *exported != 0)
        && (grant_uri_permissions && *grant_uri_permissions != 0)
        && (permission && *permission == "android.permission.MANAGE_DOCUMENTS"));
  }
};

/** Represents <receiver> elements. **/
class Receiver : public ManifestExtractor::Element {
 public:
  Receiver() = default;
  const std::string* permission = nullptr;
  bool has_component = false;

  void Extract(xml::Element* element) override {
    permission = GetAttributeString(FindAttribute(element, PERMISSION_ATTR));
  }
};

/**Represents <service> elements. **/
class Service : public ManifestExtractor::Element {
 public:
  Service() = default;
  const std::string* permission = nullptr;
  bool has_component = false;

  void Extract(xml::Element* element) override {
    permission = GetAttributeString(FindAttribute(element, PERMISSION_ATTR));
  }
};

/** Represents <uses-library> elements. **/
class UsesLibrary : public ManifestExtractor::Element {
 public:
  UsesLibrary() = default;
  std::string name;
  int required;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
    required = GetAttributeIntegerDefault(FindAttribute(element, REQUIRED_ATTR), 1);
  }

  void Print(text::Printer* printer) override {
    if (!name.empty()) {
      printer->Print(StringPrintf("uses-library%s:'%s'\n",
                                 (required == 0) ? "-not-required" : "", name.data()));
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    if (!name.empty()) {
      auto uses_library = out_badging->add_uses_libraries();
      uses_library->set_name(name);
      uses_library->set_required(required != 0);
    }
  }
};

/** Represents <static-library> elements. **/
class StaticLibrary : public ManifestExtractor::Element {
 public:
  StaticLibrary() = default;
  std::string name;
  int version;
  int versionMajor;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
    version = GetAttributeIntegerDefault(FindAttribute(element, VERSION_ATTR), 0);
    versionMajor = GetAttributeIntegerDefault(FindAttribute(element, VERSION_MAJOR_ATTR), 0);
  }

  void Print(text::Printer* printer) override {
    printer->Print(StringPrintf(
      "static-library: name='%s' version='%d' versionMajor='%d'\n",
      name.data(), version, versionMajor));
  }

  void ToProto(pb::Badging* out_badging) override {
    auto static_library = out_badging->mutable_static_library();
    static_library->set_name(name);
    static_library->set_version(version);
    static_library->set_version_major(versionMajor);
  }
};

/** Represents <uses-static-library> elements. **/
class UsesStaticLibrary : public ManifestExtractor::Element {
 public:
  UsesStaticLibrary() = default;
  std::string name;
  int version;
  int versionMajor;
  std::vector<std::string> certDigests;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
    version = GetAttributeIntegerDefault(FindAttribute(element, VERSION_ATTR), 0);
    versionMajor = GetAttributeIntegerDefault(FindAttribute(element, VERSION_MAJOR_ATTR), 0);
    AddCertDigest(element);
  }

  void AddCertDigest(xml::Element* element) {
    std::string digest = GetAttributeStringDefault(FindAttribute(element, CERT_DIGEST_ATTR), "");
    // We allow ":" delimiters in the SHA declaration as this is the format
    // emitted by the certtool making it easy for developers to copy/paste.
    digest.erase(std::remove(digest.begin(), digest.end(), ':'), digest.end());
    if (!digest.empty()) {
      certDigests.push_back(digest);
    }
  }

  void Print(text::Printer* printer) override {
    printer->Print(StringPrintf(
      "uses-static-library: name='%s' version='%d' versionMajor='%d'",
      name.data(), version, versionMajor));
    for (size_t i = 0; i < certDigests.size(); i++) {
      printer->Print(StringPrintf(" certDigest='%s'", certDigests[i].data()));
    }
    printer->Print("\n");
  }

  void ToProto(pb::Badging* out_badging) override {
    auto uses_static_library = out_badging->add_uses_static_libraries();
    uses_static_library->set_name(name);
    uses_static_library->set_version(version);
    uses_static_library->set_version_major(versionMajor);
    for (auto& cert : certDigests) {
      uses_static_library->add_certificates(cert);
    }
  }
};

/** Represents <sdk-library> elements. **/
class SdkLibrary : public ManifestExtractor::Element {
 public:
  SdkLibrary() = default;
  std::string name;
  int versionMajor;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
    versionMajor = GetAttributeIntegerDefault(FindAttribute(element, VERSION_MAJOR_ATTR), 0);
  }

  void Print(text::Printer* printer) override {
    printer->Print(
        StringPrintf("sdk-library: name='%s' versionMajor='%d'\n", name.data(), versionMajor));
  }

  void ToProto(pb::Badging* out_badging) override {
    auto sdk_library = out_badging->mutable_sdk_library();
    sdk_library->set_name(name);
    sdk_library->set_version_major(versionMajor);
  }
};

/** Represents <uses-sdk-library> elements. **/
class UsesSdkLibrary : public ManifestExtractor::Element {
 public:
  UsesSdkLibrary() = default;
  std::string name;
  int versionMajor;
  std::vector<std::string> certDigests;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
    versionMajor = GetAttributeIntegerDefault(FindAttribute(element, VERSION_MAJOR_ATTR), 0);
    AddCertDigest(element);
  }

  void AddCertDigest(xml::Element* element) {
    std::string digest = GetAttributeStringDefault(FindAttribute(element, CERT_DIGEST_ATTR), "");
    // We allow ":" delimiters in the SHA declaration as this is the format
    // emitted by the certtool making it easy for developers to copy/paste.
    digest.erase(std::remove(digest.begin(), digest.end(), ':'), digest.end());
    if (!digest.empty()) {
      certDigests.push_back(digest);
    }
  }

  void Print(text::Printer* printer) override {
    printer->Print(
        StringPrintf("uses-sdk-library: name='%s' versionMajor='%d'", name.data(), versionMajor));
    for (size_t i = 0; i < certDigests.size(); i++) {
      printer->Print(StringPrintf(" certDigest='%s'", certDigests[i].data()));
    }
    printer->Print("\n");
  }

  void ToProto(pb::Badging* out_badging) override {
    auto uses_sdk_library = out_badging->add_uses_sdk_libraries();
    uses_sdk_library->set_name(name);
    uses_sdk_library->set_version_major(versionMajor);
    for (auto& cert : certDigests) {
      uses_sdk_library->add_certificates(cert);
    }
  }
};

/** Represents <uses-native-library> elements. **/
class UsesNativeLibrary : public ManifestExtractor::Element {
 public:
  UsesNativeLibrary() = default;
  std::string name;
  int required;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
    required = GetAttributeIntegerDefault(FindAttribute(element, REQUIRED_ATTR), 1);
  }

  void Print(text::Printer* printer) override {
    if (!name.empty()) {
      printer->Print(StringPrintf("uses-native-library%s:'%s'\n",
                                 (required == 0) ? "-not-required" : "", name.data()));
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    if (!name.empty()) {
      auto uses_native_library = out_badging->add_uses_native_libraries();
      uses_native_library->set_name(name);
      uses_native_library->set_required(required != 0);
    }
  }
};

/**
 * Represents <meta-data> elements. These tags are only printed when a flag is passed in to
 * explicitly enable meta data printing.
 **/
class MetaData : public ManifestExtractor::Element {
 public:
  MetaData() = default;
  std::string name;
  std::string value;
  const int* value_int;
  std::string resource;
  const int* resource_int;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
    value = GetAttributeStringDefault(FindAttribute(element, VALUE_ATTR), "");
    value_int = GetAttributeInteger(FindAttribute(element, VALUE_ATTR));
    resource = GetAttributeStringDefault(FindAttribute(element, RESOURCE_ATTR), "");
    resource_int = GetAttributeInteger(FindAttribute(element, RESOURCE_ATTR));
  }

  void Print(text::Printer* printer) override {
    if (extractor()->options_.include_meta_data && !name.empty()) {
      printer->Print(StringPrintf("meta-data: name='%s'", name.data()));
      if (!value.empty()) {
        printer->Print(StringPrintf(" value='%s'", value.data()));
      } else if (value_int) {
        printer->Print(StringPrintf(" value='%d'", *value_int));
      } else {
        if (!resource.empty()) {
          printer->Print(StringPrintf(" resource='%s'", resource.data()));
        } else if (resource_int) {
          printer->Print(StringPrintf(" resource='%d'", *resource_int));
        }
      }
      printer->Print("\n");
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    if (!name.empty()) {
      auto metadata = out_badging->add_metadata();
      metadata->set_name(name);
      if (!value.empty()) {
        metadata->set_value_string(value);
      } else if (value_int) {
        metadata->set_value_int(*value_int);
      } else {
        if (!resource.empty()) {
          metadata->set_resource_string(resource);
        } else if (resource_int) {
          metadata->set_resource_int(*resource_int);
        }
      }
    }
  }
};

/**
 * Represents <action> elements. Detects the presence of certain activity, provider, receiver, and
 * service components.
 **/
class Action : public ManifestExtractor::Element {
 public:
  Action() = default;
  std::string component = "";

  void Extract(xml::Element* element) override {
    auto parent_stack = extractor()->parent_stack();
    std::string action = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");

    if (ElementCast<IntentFilter>(parent_stack[0])) {
      if (ElementCast<Activity>(parent_stack[1])) {
        // Detects the presence of a particular type of activity.
        Activity* activity = ElementCast<Activity>(parent_stack[1]);
        static const auto map = std::map<std::string, std::string>({
            {"android.intent.action.MAIN", "main"},
            {"android.media.action.VIDEO_CAMERA", "camera"},
            {"android.media.action.STILL_IMAGE_CAMERA", "camera"},
            {"android.media.action.STILL_IMAGE_CAMERA_SECURE", "camera-secure"},
        });

        auto entry = map.find(action);
        if (entry != map.end()) {
          component = entry->second;
          activity->has_component_ = true;
        }

        if (action == "android.intent.action.MAIN") {
          activity->has_main_action = true;
        }

      } else if (ElementCast<Receiver>(parent_stack[1])) {
        // Detects the presence of a particular type of receiver. If the action requires a
        // permission, then the receiver element is checked for the permission.
        Receiver* receiver = ElementCast<Receiver>(parent_stack[1]);
        auto map = std::map<std::string, std::string>({
            { "android.appwidget.action.APPWIDGET_UPDATE" , "app-widget" },
            { "android.app.action.DEVICE_ADMIN_ENABLED" , "device-admin" },
        });

        auto permissions = std::map<std::string, std::string>({
            { "android.app.action.DEVICE_ADMIN_ENABLED" , "android.permission.BIND_DEVICE_ADMIN" },
        });

        auto entry = map.find(action);
        auto permission = permissions.find(action);
        if (entry != map.end() && (permission == permissions.end()
            || (receiver->permission && permission->second == *receiver->permission))) {
          receiver->has_component = true;
          component = entry->second;
        }

      } else if (ElementCast<Service>(parent_stack[1])) {
        // Detects the presence of a particular type of service. If the action requires a
        // permission, then the service element is checked for the permission.
        Service* service = ElementCast<Service>(parent_stack[1]);
        auto map = std::map<std::string, std::string>({
            { "android.view.InputMethod" , "ime" },
            { "android.service.wallpaper.WallpaperService" , "wallpaper" },
            { "android.accessibilityservice.AccessibilityService" , "accessibility" },
            { "android.printservice.PrintService" , "print-service" },
            { "android.nfc.cardemulation.action.HOST_APDU_SERVICE" , "host-apdu" },
            { "android.nfc.cardemulation.action.OFF_HOST_APDU_SERVICE" , "offhost-apdu" },
            { "android.service.notification.NotificationListenerService" ,"notification-listener" },
            { "android.service.dreams.DreamService" , "dream" },
        });

        auto permissions = std::map<std::string, std::string>({
            { "android.accessibilityservice.AccessibilityService" ,
              "android.permission.BIND_ACCESSIBILITY_SERVICE" },
            { "android.printservice.PrintService" , "android.permission.BIND_PRINT_SERVICE" },
            { "android.nfc.cardemulation.action.HOST_APDU_SERVICE" ,
              "android.permission.BIND_NFC_SERVICE" },
            { "android.nfc.cardemulation.action.OFF_HOST_APDU_SERVICE" ,
              "android.permission.BIND_NFC_SERVICE" },
            { "android.service.notification.NotificationListenerService" ,
              "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" },
            { "android.service.dreams.DreamService" , "android.permission.BIND_DREAM_SERVICE" },
        });

        auto entry = map.find(action);
        auto permission = permissions.find(action);
        if (entry != map.end() && (permission == permissions.end()
            || (service->permission && permission->second == *service->permission))) {
          service->has_component= true;
          component = entry->second;
        }

      } else if (ElementCast<Provider>(parent_stack[1])) {
        // Detects the presence of a particular type of receiver. If the provider requires a
        // permission, then the provider element is checked for the permission.
        // Detect whether this action
        Provider* provider = ElementCast<Provider>(parent_stack[1]);
        if (action == "android.content.action.DOCUMENTS_PROVIDER"
            && provider->has_required_saf_attributes) {
          component = "document-provider";
        }
      }
    }

    // Represents a searchable interface
    if (action == "android.intent.action.SEARCH") {
      component = "search";
    }
  }
};

/**
 * Represents <supports-input> elements. The element may have <input-type> elements nested within.
 **/
class SupportsInput : public ManifestExtractor::Element {
 public:
  SupportsInput() = default;
  std::vector<std::string> inputs;

  void Print(text::Printer* printer) override {
    const size_t size = inputs.size();
    if (size > 0) {
      printer->Print("supports-input: '");
      for (size_t i = 0; i < size; i++) {
        printer->Print(StringPrintf("value='%s' ", inputs[i].data()));
      }
      printer->Print("\n");
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    auto supports_input = out_badging->mutable_supports_input();
    for (auto& input : inputs) {
      supports_input->add_inputs(input);
    }
  }
};

/** Represents <input-type> elements. **/
class InputType : public ManifestExtractor::Element {
 public:
  InputType() = default;
  void Extract(xml::Element* element) override {
    auto name = GetAttributeString(FindAttribute(element, NAME_ATTR));
    auto parent_stack = extractor()->parent_stack();

    // Add the input to the set of supported inputs
    if (name && ElementCast<SupportsInput>(parent_stack[0])) {
      SupportsInput* supports = ElementCast<SupportsInput>(parent_stack[0]);
      supports->inputs.push_back(*name);
    }
  }
};

/** Represents <install-constraints> elements. **/
class InstallConstraints : public ManifestExtractor::Element {
 public:
  InstallConstraints() = default;
  std::vector<std::string> fingerprint_prefixes;

  void Extract(xml::Element* element) override {
    for (xml::Element* child : element->GetChildElements()) {
      if (child->name == "fingerprint-prefix") {
        xml::Attribute* attr = child->FindAttribute(kAndroidNamespace, "value");
        if (attr) {
          fingerprint_prefixes.push_back(attr->value);
        }
      }
    }
  }

  void Print(text::Printer* printer) override {
    if (!fingerprint_prefixes.empty()) {
      printer->Print(StringPrintf("install-constraints:\n"));
      for (const auto& prefix : fingerprint_prefixes) {
        printer->Print(StringPrintf("  fingerprint-prefix='%s'\n", prefix.c_str()));
      }
    }
  }
};

/** Represents <original-package> elements. **/
class OriginalPackage : public ManifestExtractor::Element {
 public:
  OriginalPackage() = default;
  const std::string* name = nullptr;

  void Extract(xml::Element* element) override {
    name = GetAttributeString(FindAttribute(element, NAME_ATTR));
  }

  void Print(text::Printer* printer) override {
    if (name) {
      printer->Print(StringPrintf("original-package:'%s'\n", name->data()));
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    if (name) {
      out_badging->mutable_package()->set_original_package(*name);
    }
  }
};


/** Represents <overlay> elements. **/
class Overlay : public ManifestExtractor::Element {
 public:
  Overlay() = default;
  const std::string* target_package = nullptr;
  int priority;
  bool is_static;
  const std::string* required_property_name = nullptr;
  const std::string* required_property_value = nullptr;

  void Extract(xml::Element* element) override {
    target_package = GetAttributeString(FindAttribute(element, TARGET_PACKAGE_ATTR));
    priority = GetAttributeIntegerDefault(FindAttribute(element, PRIORITY_ATTR), 0);
    is_static = GetAttributeIntegerDefault(FindAttribute(element, IS_STATIC_ATTR), false) != 0;
    required_property_name = GetAttributeString(
        FindAttribute(element, REQUIRED_SYSTEM_PROPERTY_NAME_ATTR));
    required_property_value = GetAttributeString(
        FindAttribute(element, REQUIRED_SYSTEM_PROPERTY_VALUE_ATTR));
  }

  void Print(text::Printer* printer) override {
    printer->Print(StringPrintf("overlay:"));
    if (target_package) {
      printer->Print(StringPrintf(" targetPackage='%s'", target_package->c_str()));
    }
    printer->Print(StringPrintf(" priority='%d'", priority));
    printer->Print(StringPrintf(" isStatic='%s'", is_static ? "true" : "false"));
    if (required_property_name) {
      printer->Print(StringPrintf(" requiredPropertyName='%s'", required_property_name->c_str()));
    }
    if (required_property_value) {
      printer->Print(StringPrintf(" requiredPropertyValue='%s'", required_property_value->c_str()));
    }
    printer->Print("\n");
  }

  void ToProto(pb::Badging* out_badging) override {
    auto overlay = out_badging->mutable_overlay();
    if (target_package) {
      overlay->set_target_package(*target_package);
    }
    overlay->set_priority(priority);
    overlay->set_static_(is_static);
    if (required_property_name) {
      overlay->set_required_property_name(*required_property_name);
    }
    if (required_property_value) {
      overlay->set_required_property_value(*required_property_value);
    }
  }
};

/** * Represents <package-verifier> elements. **/
class PackageVerifier : public ManifestExtractor::Element {
 public:
  PackageVerifier() = default;
  const std::string* name = nullptr;
  const std::string* public_key = nullptr;

  void Extract(xml::Element* element) override {
    name = GetAttributeString(FindAttribute(element, NAME_ATTR));
    public_key = GetAttributeString(FindAttribute(element, PUBLIC_KEY_ATTR));
  }

  void Print(text::Printer* printer) override {
    if (name && public_key) {
      printer->Print(StringPrintf("package-verifier: name='%s' publicKey='%s'\n",
                                 name->data(), public_key->data()));
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    auto package_verifier = out_badging->mutable_package_verifier();
    if (name && public_key) {
      package_verifier->set_name(*name);
      package_verifier->set_public_key(*public_key);
    }
  }
};

/** Represents <uses-package> elements. **/
class UsesPackage : public ManifestExtractor::Element {
 public:
  UsesPackage() = default;
  const std::string* packageType = nullptr;
  const std::string* name = nullptr;
  int version;
  int versionMajor;
  std::vector<std::string> certDigests;

  void Extract(xml::Element* element) override {
    packageType = GetAttributeString(FindAttribute(element, PACKAGE_TYPE_ATTR));
    name = GetAttributeString(FindAttribute(element, NAME_ATTR));
    version = GetAttributeIntegerDefault(FindAttribute(element, VERSION_ATTR), 0);
    versionMajor = GetAttributeIntegerDefault(FindAttribute(element, VERSION_MAJOR_ATTR), 0);
    AddCertDigest(element);
  }

  void AddCertDigest(xml::Element* element) {
    std::string digest = GetAttributeStringDefault(FindAttribute(element, CERT_DIGEST_ATTR), "");
    // We allow ":" delimiters in the SHA declaration as this is the format
    // emitted by the certtool making it easy for developers to copy/paste.
    digest.erase(std::remove(digest.begin(), digest.end(), ':'), digest.end());
    if (!digest.empty()) {
      certDigests.push_back(digest);
    }
  }

  void Print(text::Printer* printer) override {
    if (name) {
      if (packageType) {
        printer->Print(StringPrintf(
          "uses-typed-package: type='%s' name='%s' version='%d' versionMajor='%d'",
          packageType->data(), name->data(), version, versionMajor));
        for (size_t i = 0; i < certDigests.size(); i++) {
          printer->Print(StringPrintf(" certDigest='%s'", certDigests[i].data()));
        }
        printer->Print("\n");
      } else {
        printer->Print(StringPrintf("uses-package:'%s'\n", name->data()));
      }
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    if (name) {
      auto uses_package = out_badging->add_uses_packages();
      uses_package->set_name(*name);
      if (packageType) {
        uses_package->set_package_type(*packageType);
        uses_package->set_version(version);
        uses_package->set_version_major(versionMajor);
        for (auto& cert : certDigests) {
          uses_package->add_certificates(cert);
        }
      }
    }
  }
};

/** Represents <additional-certificate> elements. **/
class AdditionalCertificate : public ManifestExtractor::Element {
 public:
  AdditionalCertificate() = default;

  void Extract(xml::Element* element) override {
    auto parent_stack = extractor()->parent_stack();
    if (parent_stack.size() > 0) {
      if (ElementCast<UsesPackage>(parent_stack[0])) {
        UsesPackage* uses = ElementCast<UsesPackage>(parent_stack[0]);
        uses->AddCertDigest(element);
      } else if (ElementCast<UsesStaticLibrary>(parent_stack[0])) {
        UsesStaticLibrary* uses = ElementCast<UsesStaticLibrary>(parent_stack[0]);
        uses->AddCertDigest(element);
      }
    }
  }
};

/** Represents <screen> elements found in <compatible-screens> elements. */
class Screen : public ManifestExtractor::Element {
 public:
  Screen() = default;
  const int32_t* size = nullptr;
  const int32_t* density = nullptr;

  void Extract(xml::Element* element) override {
    size = GetAttributeInteger(FindAttribute(element, SCREEN_SIZE_ATTR));
    density = GetAttributeInteger(FindAttribute(element, SCREEN_DENSITY_ATTR));
  }

  void ToProto(pb::Badging* out_badging) override {
    if (size && density) {
      auto screen = out_badging->mutable_compatible_screens()->add_screens();
      screen->set_density(*density);
      screen->set_size(*size);
    }
  }
};

/**
 * Represents <compatible-screens> elements. These elements have <screen> elements nested within
 * that each denote a supported screen size and screen density.
 **/
class CompatibleScreens : public ManifestExtractor::Element {
 public:
  CompatibleScreens() = default;
  void Print(text::Printer* printer) override {
    printer->Print("compatible-screens:");

    bool first = true;
    ForEachChild(this, [&printer, &first](ManifestExtractor::Element* el){
      if (auto screen = ElementCast<Screen>(el)) {
        if (first) {
          first = false;
        } else {
          printer->Print(",");
        }

        if (screen->size && screen->density) {
          printer->Print(StringPrintf("'%d/%d'", *screen->size, *screen->density));
        }
      }
    });
    printer->Print("\n");
  }
};

/** Represents <supports-gl-texture> elements. **/
class SupportsGlTexture : public ManifestExtractor::Element {
 public:
  SupportsGlTexture() = default;
  const std::string* name = nullptr;

  void Extract(xml::Element* element) override {
    name = GetAttributeString(FindAttribute(element, NAME_ATTR));
  }

  void Print(text::Printer* printer) override {
    if (name) {
      printer->Print(StringPrintf("supports-gl-texture:'%s'\n", name->data()));
    }
  }

  void ToProto(pb::Badging* out_badging) override {
    if (name) {
      out_badging->mutable_supports_gl_texture()->add_name(*name);
    }
  }
};

/** Represents <property> elements. **/
class Property : public ManifestExtractor::Element {
 public:
  Property() = default;
  std::string name;
  std::string value;
  const int* value_int;
  std::string resource;
  const int* resource_int;

  void Extract(xml::Element* element) override {
    name = GetAttributeStringDefault(FindAttribute(element, NAME_ATTR), "");
    value = GetAttributeStringDefault(FindAttribute(element, VALUE_ATTR), "");
    value_int = GetAttributeInteger(FindAttribute(element, VALUE_ATTR));
    resource = GetAttributeStringDefault(FindAttribute(element, RESOURCE_ATTR), "");
    resource_int = GetAttributeInteger(FindAttribute(element, RESOURCE_ATTR));
  }

  void Print(text::Printer* printer) override {
    printer->Print(StringPrintf("property: name='%s' ", name.data()));
    if (!value.empty()) {
      printer->Print(StringPrintf("value='%s' ", value.data()));
    } else if (value_int) {
      printer->Print(StringPrintf("value='%d' ", *value_int));
    } else {
      if (!resource.empty()) {
        printer->Print(StringPrintf("resource='%s' ", resource.data()));
      } else if (resource_int) {
        printer->Print(StringPrintf("resource='%d' ", *resource_int));
      }
    }
    printer->Print("\n");
  }

  void ToProto(pb::Badging* out_badging) override {
    if (!name.empty()) {
      auto property = out_badging->add_properties();
      property->set_name(name);
      if (!value.empty()) {
        property->set_value_string(value);
      } else if (value_int) {
        property->set_value_int(*value_int);
      } else {
        if (!resource.empty()) {
          property->set_resource_string(resource);
        } else if (resource_int) {
          property->set_resource_int(*resource_int);
        }
      }
    }
  }
};

/** Recursively prints the extracted badging element. */
static void Print(ManifestExtractor::Element* el, text::Printer* printer) {
  el->Print(printer);
  for (auto &child : el->children()) {
    Print(child.get(), printer);
  }
}

/** Recursively serializes extracted badging elements to proto. */
static void ToProto(ManifestExtractor::Element* el, pb::Badging* out_badging) {
  el->ToProto(out_badging);
  for (auto& child : el->children()) {
    ToProto(child.get(), out_badging);
  }
}

// Define this constructor after the CommonFeatureGroup class definition to avoid errors with using
// std::unique_ptr on an incomplete type.
ManifestExtractor::ManifestExtractor(LoadedApk* apk, DumpManifestOptions& options)
    : apk_(apk), options_(options), commonFeatureGroup_(util::make_unique<CommonFeatureGroup>()) {
}

bool ManifestExtractor::Extract(android::IDiagnostics* diag) {
  // Load the manifest
  doc_ = apk_->LoadXml("AndroidManifest.xml", diag);
  if (doc_ == nullptr) {
    diag->Error(android::DiagMessage() << "failed to find AndroidManifest.xml");
    return false;
  }

  xml::Element* element = doc_->root.get();
  if (element->name != "manifest") {
    diag->Error(android::DiagMessage() << "manifest does not start with <manifest> tag");
    return false;
  }

  // Print only the <uses-permission>, <uses-permission-sdk23>, and <permission> elements if
  // printing only permission elements is requested
  if (options_.only_permissions) {
    root_element_ = ManifestExtractor::Element::Inflate(this, element, "");

    if (auto manifest = ElementCast<Manifest>(root_element_.get())) {
      manifest->only_package_name = true;

      for (xml::Element* child : element->GetChildElements()) {
        if (child->name == "uses-permission" || child->name == "uses-permission-sdk-23"
            || child->name == "permission") {
          // Inflate the element and its descendants
          auto permission_element = Visit(child, "manifest");
          manifest->AddChild(permission_element);
        }
      }
      return true;
    }
    return false;
  }

  // Collect information about the resource configurations
  if (apk_->GetResourceTable()) {
    for (auto &package : apk_->GetResourceTable()->packages) {
      for (auto &type : package->types) {
        for (auto &entry : type->entries) {
          for (auto &value : entry->values) {
            std::string locale_str = value->config.GetBcp47LanguageTag();

            // Collect all the unique locales of the apk
            if (locales_.find(locale_str) == locales_.end()) {
              ConfigDescription config = ManifestExtractor::DefaultConfig();
              config.setBcp47Locale(locale_str.data());
              locales_.insert(std::make_pair(locale_str, config));
            }

            // Collect all the unique density of the apk
            uint16_t density = (value->config.density == 0) ? (uint16_t) 160
                                                            : value->config.density;
            if (densities_.find(density) == densities_.end()) {
              ConfigDescription config = ManifestExtractor::DefaultConfig();
              config.density = density;
              densities_.insert(std::make_pair(density, config));
            }
          }
        }
      }
    }
  }

  // Extract badging information
  root_element_ = Visit(element, "");

  // Filter out all "uses-sdk" tags besides the very last tag. The android runtime only uses the
  // attribute values from the last defined tag.
  std::vector<UsesSdkBadging*> filtered_uses_sdk_tags;
  for (const auto& child : root_element_->children()) {
    if (auto uses_sdk = ElementCast<UsesSdkBadging>(child.get())) {
      filtered_uses_sdk_tags.emplace_back(uses_sdk);
    }
  }
  if (filtered_uses_sdk_tags.size() >= 2U) {
    filtered_uses_sdk_tags.pop_back();
    root_element_->Filter([&](const ManifestExtractor::Element* e) {
      return std::find(filtered_uses_sdk_tags.begin(), filtered_uses_sdk_tags.end(), e) !=
             filtered_uses_sdk_tags.end();
    });
  }

  /** Recursively checks the extracted elements for the specified permission. **/
  auto FindPermission = [&](ManifestExtractor::Element* root,
                            const std::string& name) -> ManifestExtractor::Element* {
    return FindElement(root, [&](ManifestExtractor::Element* el) -> bool {
      if (UsesPermission* permission = ElementCast<UsesPermission>(el)) {
        return permission->name == name;
      }
      return false;
    });
  };

  auto AddImpliedPermission = [&](const std::string& name, const std::string& reason,
                                  int32_t max_sdk_version) -> void {
    auto permission = util::make_unique<UsesPermission>();
    permission->name = name;
    permission->maxSdkVersion = max_sdk_version;
    permission->implied = true;
    permission->impliedReason = reason;
    implied_permissions_.push_back(std::move(permission));
  };

  // Implied permissions
  // Pre-1.6 implicitly granted permission compatibility logic
  bool insert_write_external = false;
  auto write_external_permission = ElementCast<UsesPermission>(
      FindPermission(root_element_.get(), "android.permission.WRITE_EXTERNAL_STORAGE"));

  if (target_sdk() < SDK_DONUT) {
    if (!write_external_permission) {
      AddImpliedPermission("android.permission.WRITE_EXTERNAL_STORAGE", "targetSdkVersion < 4", -1);
      insert_write_external = true;
    }

    if (!FindPermission(root_element_.get(), "android.permission.READ_PHONE_STATE")) {
      AddImpliedPermission("android.permission.READ_PHONE_STATE", "targetSdkVersion < 4", -1);
    }
  }

  // If the application has requested WRITE_EXTERNAL_STORAGE, we will
  // force them to always take READ_EXTERNAL_STORAGE as well.  We always
  // do this (regardless of target API version) because we can't have
  // an app with write permission but not read permission.
  auto read_external =
      FindPermission(root_element_.get(), "android.permission.READ_EXTERNAL_STORAGE");
  if (!read_external && (insert_write_external || write_external_permission)) {
    AddImpliedPermission(
        "android.permission.READ_EXTERNAL_STORAGE", "requested WRITE_EXTERNAL_STORAGE",
        (write_external_permission) ? write_external_permission->maxSdkVersion : -1);
  }

  // Pre-JellyBean call log permission compatibility.
  if (target_sdk() < SDK_JELLY_BEAN) {
    if (!FindPermission(root_element_.get(), "android.permission.READ_CALL_LOG") &&
        FindPermission(root_element_.get(), "android.permission.READ_CONTACTS")) {
      AddImpliedPermission("android.permission.READ_CALL_LOG",
                           "targetSdkVersion < 16 and requested READ_CONTACTS", -1);
    }

    if (!FindPermission(root_element_.get(), "android.permission.WRITE_CALL_LOG") &&
        FindPermission(root_element_.get(), "android.permission.WRITE_CONTACTS")) {
      AddImpliedPermission("android.permission.WRITE_CALL_LOG",
                           "targetSdkVersion < 16 and requested WRITE_CONTACTS", -1);
    }
  }

  // If the app hasn't declared the touchscreen as a feature requirement (either
  // directly or implied, required or not), then the faketouch feature is implied.
  if (!common_feature_group()->HasFeature("android.hardware.touchscreen")) {
    common_feature_group()->addImpliedFeature("android.hardware.faketouch",
                                              "default feature for all apps", false);
  }

  // Only print the common feature group if no feature group is defined
  std::vector<FeatureGroup*> feature_groups;
  ForEachChild(root_element_.get(), [&feature_groups](ManifestExtractor::Element* el) -> void {
    if (auto feature_group = ElementCast<FeatureGroup>(el)) {
      feature_groups.push_back(feature_group);
    }
  });

  if (feature_groups.empty()) {
    feature_groups_.push_back(common_feature_group());
  } else {
    // Merge the common feature group into the feature group
    for (auto& feature_group : feature_groups) {
      feature_group->Merge(common_feature_group());
      feature_groups_.push_back(feature_group);
    }
  };

  // Collect the component types of the application
  ForEachChild(root_element_.get(), [&](ManifestExtractor::Element* el) -> void {
    if (ElementCast<Action>(el)) {
      auto action = ElementCast<Action>(el);
      if (!action->component.empty()) {
        components_.discovered_components.insert(action->component);
        return;
      }
    }

    if (ElementCast<Category>(el)) {
      auto category = ElementCast<Category>(el);
      if (!category->component.empty()) {
        components_.discovered_components.insert(category->component);
        return;
      }
    }
  });

  // Check for the payment component
  ForEachChild(root_element_.get(), [this, &diag](ManifestExtractor::Element* el) -> void {
    if (auto service = ElementCast<Service>(el)) {
      auto host_apdu_action = ElementCast<Action>(FindElement(service,
        [&](ManifestExtractor::Element* el) -> bool {
          if (auto action = ElementCast<Action>(el)) {
            return (action->component == "host-apdu");
          }
          return false;
      }));

      auto offhost_apdu_action = ElementCast<Action>(FindElement(service,
        [&](ManifestExtractor::Element* el) -> bool {
           if (auto action = ElementCast<Action>(el)) {
             return (action->component == "offhost-apdu");
           }
           return false;
      }));

      ForEachChild(service,
                   [this, &diag, &host_apdu_action,
                    &offhost_apdu_action](ManifestExtractor::Element* el) -> void {
                     if (auto meta_data = ElementCast<MetaData>(el)) {
                       if ((meta_data->name == "android.nfc.cardemulation.host_apdu_service" &&
                            host_apdu_action) ||
                           (meta_data->name == "android.nfc.cardemulation.off_host_apdu_service" &&
                            offhost_apdu_action)) {
                         // Attempt to load the resource file
                         if (meta_data->resource.empty()) {
                           return;
                         }
                         auto resource = this->apk_->LoadXml(meta_data->resource, diag);
                         if (!resource) {
                           return;
                         }

                         // Look for the payment category on an <aid-group> element
                         auto& root = resource.get()->root;
                         if ((host_apdu_action && root->name == "host-apdu-service") ||
                             (offhost_apdu_action && root->name == "offhost-apdu-service")) {
                           for (auto& child : root->GetChildElements()) {
                             if (child->name == "aid-group") {
                               auto category = FindAttribute(child, CATEGORY_ATTR);
                               if (category && category->value == "payment") {
                                 this->components_.discovered_components.insert("payment");
                                 return;
                               }
                             }
                           }
                         }
                       }
                     }
                   });
    }
  });

  // Print presence of activities, receivers, and services with no special components
  FindElement(root_element_.get(), [&](ManifestExtractor::Element* el) -> bool {
    if (auto activity = ElementCast<Activity>(el)) {
      if (!activity->has_component_) {
        components_.other_activities = true;
        return true;
      }
    }
    return false;
  });

  FindElement(root_element_.get(), [&](ManifestExtractor::Element* el) -> bool {
    if (auto receiver = ElementCast<Receiver>(el)) {
      if (!receiver->has_component) {
        components_.other_receivers = true;
        return true;
      }
    }
    return false;
  });

  FindElement(root_element_.get(), [&](ManifestExtractor::Element* el) -> bool {
    if (auto service = ElementCast<Service>(el)) {
      if (!service->has_component) {
        components_.other_services = true;
        return true;
      }
    }
    return false;
  });

  // Gather the supported screens
  const static SupportsScreen default_screens{};
  SupportsScreen* screen = ElementCast<SupportsScreen>(
      FindElement(root_element_.get(), [&](ManifestExtractor::Element* el) -> bool {
        return ElementCast<SupportsScreen>(el) != nullptr;
      }));
  supports_screen_ = screen ? screen : &default_screens;

  bool has_renderscript_bitcode = false;
  auto it = apk_->GetFileCollection()->Iterator();
  while (it->HasNext()) {
    if (it->Next()->GetSource().path.ends_with(".bc")) {
      has_renderscript_bitcode = true;
      break;
    }
  }

  // Gather the supported architectures_ of the app
  std::set<std::string> architectures_from_apk;
  it = apk_->GetFileCollection()->Iterator();
  while (it->HasNext()) {
    auto file_path = it->Next()->GetSource().path.c_str();

    const char* last_slash =
        android::util::ValidLibraryPathLastSlash(file_path, has_renderscript_bitcode);
    if (last_slash) {
      architectures_from_apk.insert(std::string(file_path + APK_LIB_LEN, last_slash));
    }
  }

  // Determine if the application has multiArch supports
  auto has_multi_arch =
      FindElement(root_element_.get(), [&](ManifestExtractor::Element* el) -> bool {
        if (auto application = ElementCast<Application>(el)) {
          return application->has_multi_arch;
        }
        return false;
      });

  bool output_alt_native_code = false;
  // A multiArch package is one that contains 64-bit and
  // 32-bit versions of native code and expects 3rd-party
  // apps to load these native code libraries. Since most
  // 64-bit systems also support 32-bit apps, the apps
  // loading this multiArch package's code may be either
  if (has_multi_arch) {
    // If this is a multiArch package, report the 64-bit
    // version only. Then as a separate entry, report the
    // rest.
    //
    // If we report the 32-bit architecture, this APK will
    // be installed on a 32-bit device, causing a large waste
    // of bandwidth and disk space. This assumes that
    // the developer of the multiArch package has also
    // made a version that is 32-bit only.
    const std::string kIntel64 = "x86_64";
    const std::string kArm64 = "arm64-v8a";

    auto arch = architectures_from_apk.find(kIntel64);
    if (arch == architectures_from_apk.end()) {
      arch = architectures_from_apk.find(kArm64);
    }

    if (arch != architectures_from_apk.end()) {
      architectures_.architectures.insert(*arch);
      architectures_from_apk.erase(arch);
      output_alt_native_code = true;
    }
  }
  for (auto& arch : architectures_from_apk) {
    if (output_alt_native_code) {
      architectures_.alt_architectures.insert(arch);
    } else {
      architectures_.architectures.insert(arch);
    }
  }
  return true;
}

bool ManifestExtractor::Dump(text::Printer* printer) {
  Print(root_element_.get(), printer);
  if (options_.only_permissions) {
    return true;
  }

  for (auto& implied_permission : implied_permissions_) {
    implied_permission->Print(printer);
  }
  for (auto& feature_group : feature_groups_) {
    feature_group->PrintGroup(printer);
  }
  components_.Print(printer);
  supports_screen_->PrintScreens(printer, target_sdk_);

  // Print all the unique locales of the apk
  printer->Print("locales:");
  for (auto& config : locales_) {
    if (config.first.empty()) {
      printer->Print(" '--_--'");
    } else {
      printer->Print(StringPrintf(" '%s'", config.first.data()));
    }
  }
  printer->Print("\n");

  // Print all the densities locales of the apk
  printer->Print("densities:");
  for (auto& config : densities_) {
    printer->Print(StringPrintf(" '%d'", config.first));
  }
  printer->Print("\n");

  architectures_.Print(printer);
  return true;
}

bool ManifestExtractor::DumpProto(pb::Badging* out_badging) {
  ToProto(root_element_.get(), out_badging);
  for (auto& implied_permission : implied_permissions_) {
    implied_permission->ToProto(out_badging);
  }
  for (auto& feature_group : feature_groups_) {
    feature_group->GroupToProto(out_badging);
  }
  components_.ToProto(out_badging);
  supports_screen_->ToProtoScreens(out_badging, target_sdk_);

  for (auto& config : locales_) {
    if (config.first.empty()) {
      out_badging->add_locales("--_--");
    } else {
      out_badging->add_locales(config.first);
    }
  }
  for (auto& config : densities_) {
    out_badging->add_densities(config.first);
  }

  architectures_.ToProto(out_badging);
  return true;
}

template <typename T>
constexpr const char* GetExpectedTagForType() {
  // This array does not appear at runtime, as GetExpectedTagForType function is used by compiler
  // to inject proper 'expected_tag' into ElementCast.
  std::array<std::pair<const char*, bool>, 38> tags = {
      std::make_pair("action", std::is_same<Action, T>::value),
      std::make_pair("activity", std::is_same<Activity, T>::value),
      std::make_pair("additional-certificate", std::is_same<AdditionalCertificate, T>::value),
      std::make_pair("application", std::is_same<Application, T>::value),
      std::make_pair("category", std::is_same<Category, T>::value),
      std::make_pair("compatible-screens", std::is_same<CompatibleScreens, T>::value),
      std::make_pair("feature-group", std::is_same<FeatureGroup, T>::value),
      std::make_pair("input-type", std::is_same<InputType, T>::value),
      std::make_pair("install-constraints", std::is_same<InstallConstraints, T>::value),
      std::make_pair("intent-filter", std::is_same<IntentFilter, T>::value),
      std::make_pair("meta-data", std::is_same<MetaData, T>::value),
      std::make_pair("manifest", std::is_same<Manifest, T>::value),
      std::make_pair("original-package", std::is_same<OriginalPackage, T>::value),
      std::make_pair("overlay", std::is_same<Overlay, T>::value),
      std::make_pair("package-verifier", std::is_same<PackageVerifier, T>::value),
      std::make_pair("permission", std::is_same<Permission, T>::value),
      std::make_pair("property", std::is_same<Property, T>::value),
      std::make_pair("provider", std::is_same<Provider, T>::value),
      std::make_pair("receiver", std::is_same<Receiver, T>::value),
      std::make_pair("required-feature", std::is_same<RequiredFeature, T>::value),
      std::make_pair("required-not-feature", std::is_same<RequiredNotFeature, T>::value),
      std::make_pair("screen", std::is_same<Screen, T>::value),
      std::make_pair("service", std::is_same<Service, T>::value),
      std::make_pair("sdk-library", std::is_same<SdkLibrary, T>::value),
      std::make_pair("static-library", std::is_same<StaticLibrary, T>::value),
      std::make_pair("supports-gl-texture", std::is_same<SupportsGlTexture, T>::value),
      std::make_pair("supports-input", std::is_same<SupportsInput, T>::value),
      std::make_pair("supports-screens", std::is_same<SupportsScreen, T>::value),
      std::make_pair("uses-configuration", std::is_same<UsesConfiguarion, T>::value),
      std::make_pair("uses-feature", std::is_same<UsesFeature, T>::value),
      std::make_pair("uses-library", std::is_same<UsesLibrary, T>::value),
      std::make_pair("uses-native-library", std::is_same<UsesNativeLibrary, T>::value),
      std::make_pair("uses-package", std::is_same<UsesPackage, T>::value),
      std::make_pair("uses-permission", std::is_same<UsesPermission, T>::value),
      std::make_pair("uses-permission-sdk-23", std::is_same<UsesPermissionSdk23, T>::value),
      std::make_pair("uses-sdk", std::is_same<UsesSdkBadging, T>::value),
      std::make_pair("uses-sdk-library", std::is_same<UsesSdkLibrary, T>::value),
      std::make_pair("uses-static-library", std::is_same<UsesStaticLibrary, T>::value),
  };
  for (const auto& pair : tags) {
    if (pair.second) {
      return pair.first;
    }
  }
  return nullptr;
}

/**
 * Returns the element casted to the type if the element is of that type. Otherwise, returns a null
 * pointer.
 **/
template<typename T>
T* ElementCast(ManifestExtractor::Element* element) {
  constexpr const char* expected_tag = GetExpectedTagForType<T>();
  if (element != nullptr && expected_tag != nullptr && element->is_featured() &&
      element->tag() == expected_tag) {
    return static_cast<T*>(element);
  }
  return nullptr;
}

template<typename T>
std::unique_ptr<T> CreateType() {
  return std::move(util::make_unique<T>());
}

std::unique_ptr<ManifestExtractor::Element> ManifestExtractor::Element::Inflate(
    ManifestExtractor* extractor, xml::Element* el, const std::string& parent_tag) {
  static const std::unordered_map<std::string_view,
                                  std::function<std::unique_ptr<ManifestExtractor::Element>()>>
      kTagCheck = {
          {"action", &CreateType<Action>},
          {"activity", &CreateType<Activity>},
          {"additional-certificate", &CreateType<AdditionalCertificate>},
          {"application", &CreateType<Application>},
          {"category", &CreateType<Category>},
          {"compatible-screens", &CreateType<CompatibleScreens>},
          {"feature-group", &CreateType<FeatureGroup>},
          {"input-type", &CreateType<InputType>},
          {"install-constraints", &CreateType<InstallConstraints>},
          {"intent-filter", &CreateType<IntentFilter>},
          {"manifest", &CreateType<Manifest>},
          {"meta-data", &CreateType<MetaData>},
          {"original-package", &CreateType<OriginalPackage>},
          {"overlay", &CreateType<Overlay>},
          {"package-verifier", &CreateType<PackageVerifier>},
          {"permission", &CreateType<Permission>},
          {"property", &CreateType<Property>},
          {"provider", &CreateType<Provider>},
          {"receiver", &CreateType<Receiver>},
          {"required-feature", &CreateType<RequiredFeature>},
          {"required-not-feature", &CreateType<RequiredNotFeature>},
          {"screen", &CreateType<Screen>},
          {"service", &CreateType<Service>},
          {"sdk-library", &CreateType<SdkLibrary>},
          {"static-library", &CreateType<StaticLibrary>},
          {"supports-gl-texture", &CreateType<SupportsGlTexture>},
          {"supports-input", &CreateType<SupportsInput>},
          {"supports-screens", &CreateType<SupportsScreen>},
          {"uses-configuration", &CreateType<UsesConfiguarion>},
          {"uses-feature", &CreateType<UsesFeature>},
          {"uses-library", &CreateType<UsesLibrary>},
          {"uses-native-library", &CreateType<UsesNativeLibrary>},
          {"uses-package", &CreateType<UsesPackage>},
          {"uses-permission", &CreateType<UsesPermission>},
          {"uses-permission-sdk-23", &CreateType<UsesPermissionSdk23>},
          {"uses-sdk", &CreateType<UsesSdkBadging>},
          {"uses-sdk-library", &CreateType<UsesSdkLibrary>},
          {"uses-static-library", &CreateType<UsesStaticLibrary>},
      };
  static constexpr std::array<std::pair<std::string_view, std::string_view>, 53>
      kValidChildParentTags = {
          std::make_pair("action", "intent-filter"),
          std::make_pair("activity", "application"),
          std::make_pair("additional-certificate", "uses-package"),
          std::make_pair("additional-certificate", "uses-static-library"),
          std::make_pair("application", "manifest"),
          std::make_pair("category", "intent-filter"),
          std::make_pair("compatible-screens", "manifest"),
          std::make_pair("feature-group", "manifest"),
          std::make_pair("input-type", "supports-input"),
          std::make_pair("intent-filter", "activity"),
          std::make_pair("intent-filter", "activity-alias"),
          std::make_pair("intent-filter", "service"),
          std::make_pair("intent-filter", "receiver"),
          std::make_pair("intent-filter", "provider"),
          std::make_pair("manifest", ""),
          std::make_pair("meta-data", "activity"),
          std::make_pair("meta-data", "activity-alias"),
          std::make_pair("meta-data", "application"),
          std::make_pair("meta-data", "service"),
          std::make_pair("meta-data", "receiver"),
          std::make_pair("meta-data", "provider"),
          std::make_pair("original-package", "manifest"),
          std::make_pair("overlay", "manifest"),
          std::make_pair("package-verifier", "manifest"),
          std::make_pair("permission", "manifest"),
          std::make_pair("property", "activity"),
          std::make_pair("property", "activity-alias"),
          std::make_pair("property", "application"),
          std::make_pair("property", "service"),
          std::make_pair("property", "receiver"),
          std::make_pair("property", "provider"),
          std::make_pair("provider", "application"),
          std::make_pair("receiver", "application"),
          std::make_pair("required-feature", "uses-permission"),
          std::make_pair("required-not-feature", "uses-permission"),
          std::make_pair("screen", "compatible-screens"),
          std::make_pair("service", "application"),
          std::make_pair("sdk-library", "application"),
          std::make_pair("static-library", "application"),
          std::make_pair("supports-gl-texture", "manifest"),
          std::make_pair("supports-input", "manifest"),
          std::make_pair("supports-screens", "manifest"),
          std::make_pair("uses-configuration", "manifest"),
          std::make_pair("uses-feature", "feature-group"),
          std::make_pair("uses-feature", "manifest"),
          std::make_pair("uses-library", "application"),
          std::make_pair("uses-native-library", "application"),
          std::make_pair("uses-package", "application"),
          std::make_pair("uses-permission", "manifest"),
          std::make_pair("uses-permission-sdk-23", "manifest"),
          std::make_pair("uses-sdk", "manifest"),
          std::make_pair("uses-sdk-library", "application"),
          std::make_pair("uses-static-library", "application"),
      };
  bool is_valid_tag = std::find(kValidChildParentTags.begin(), kValidChildParentTags.end(),
                                std::make_pair<std::string_view, std::string_view>(
                                    el->name, parent_tag)) != kValidChildParentTags.end();
  // Attempt to map the xml tag to a element inflater
  std::unique_ptr<ManifestExtractor::Element> element;
  auto check = kTagCheck.find(el->name);
  if (check != kTagCheck.end() && is_valid_tag) {
    element = check->second();
    element->featured_ = true;
  } else {
    element = util::make_unique<ManifestExtractor::Element>();
  }

  element->extractor_ = extractor;
  element->tag_ = el->name;
  element->Extract(el);
  return element;
}

std::unique_ptr<ManifestExtractor::Element> ManifestExtractor::Visit(
    xml::Element* el, const std::string& parent_tag) {
  auto element = ManifestExtractor::Element::Inflate(this, el, parent_tag);
  parent_stack_.insert(parent_stack_.begin(), element.get());

  // Process the element and recursively visit the children
  for (xml::Element* child : el->GetChildElements()) {
    auto v = Visit(child, el->name);
    element->AddChild(v);
  }

  parent_stack_.erase(parent_stack_.begin());
  return element;
}

int DumpManifest(LoadedApk* apk, DumpManifestOptions& options, text::Printer* printer,
                 android::IDiagnostics* diag) {
  ManifestExtractor extractor(apk, options);
  if (!extractor.Extract(diag)) {
    return 1;
  }
  return extractor.Dump(printer) ? 0 : 1;
}

int DumpBadgingProto(LoadedApk* apk, pb::Badging* out_badging, android::IDiagnostics* diag) {
  DumpManifestOptions options{/* include_meta_data= */ true,
                              /* only_permissions= */ false};
  ManifestExtractor extractor(apk, options);
  if (!extractor.Extract(diag)) {
    return 1;
  }
  return extractor.DumpProto(out_badging) ? 0 : 1;
}

} // namespace aapt
