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

#ifndef AAPT_TEST_BUILDERS_H
#define AAPT_TEST_BUILDERS_H

#include <memory>

#include "android-base/macros.h"
#include "androidfw/ConfigDescription.h"

#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "configuration/ConfigurationParser.h"
#include "configuration/ConfigurationParser.internal.h"
#include "process/IResourceTableConsumer.h"
#include "test/Common.h"
#include "util/Maybe.h"
#include "xml/XmlDom.h"

namespace aapt {
namespace test {

class ResourceTableBuilder {
 public:
  ResourceTableBuilder() = default;

  ResourceTableBuilder& SetPackageId(const android::StringPiece& package_name, uint8_t id);
  ResourceTableBuilder& AddSimple(const android::StringPiece& name, const ResourceId& id = {});
  ResourceTableBuilder& AddSimple(const android::StringPiece& name,
                                  const android::ConfigDescription& config,
                                  const ResourceId& id = {});
  ResourceTableBuilder& AddReference(const android::StringPiece& name,
                                     const android::StringPiece& ref);
  ResourceTableBuilder& AddReference(const android::StringPiece& name, const ResourceId& id,
                                     const android::StringPiece& ref);
  ResourceTableBuilder& AddString(const android::StringPiece& name,
                                  const android::StringPiece& str);
  ResourceTableBuilder& AddString(const android::StringPiece& name, const ResourceId& id,
                                  const android::StringPiece& str);
  ResourceTableBuilder& AddString(const android::StringPiece& name, const ResourceId& id,
                                  const android::ConfigDescription& config,
                                  const android::StringPiece& str);
  ResourceTableBuilder& AddFileReference(const android::StringPiece& name,
                                         const android::StringPiece& path,
                                         io::IFile* file = nullptr);
  ResourceTableBuilder& AddFileReference(const android::StringPiece& name, const ResourceId& id,
                                         const android::StringPiece& path,
                                         io::IFile* file = nullptr);
  ResourceTableBuilder& AddFileReference(const android::StringPiece& name,
                                         const android::StringPiece& path,
                                         const android::ConfigDescription& config,
                                         io::IFile* file = nullptr);
  ResourceTableBuilder& AddValue(const android::StringPiece& name, std::unique_ptr<Value> value);
  ResourceTableBuilder& AddValue(const android::StringPiece& name, const ResourceId& id,
                                 std::unique_ptr<Value> value);
  ResourceTableBuilder& AddValue(const android::StringPiece& name,
                                 const android::ConfigDescription& config,
                                 const ResourceId& id, std::unique_ptr<Value> value);
  ResourceTableBuilder& SetSymbolState(const android::StringPiece& name, const ResourceId& id,
                                       Visibility::Level level, bool allow_new = false);
  ResourceTableBuilder& SetOverlayable(const android::StringPiece& name,
                                       const OverlayableItem& overlayable);

  StringPool* string_pool();
  std::unique_ptr<ResourceTable> Build();

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceTableBuilder);

  std::unique_ptr<ResourceTable> table_ = util::make_unique<ResourceTable>();
};

std::unique_ptr<Reference> BuildReference(const android::StringPiece& ref,
                                          const Maybe<ResourceId>& id = {});
std::unique_ptr<BinaryPrimitive> BuildPrimitive(uint8_t type, uint32_t data);

template <typename T>
class ValueBuilder {
 public:
  template <typename... Args>
  explicit ValueBuilder(Args&&... args) : value_(new T{std::forward<Args>(args)...}) {
  }

  template <typename... Args>
  ValueBuilder& SetSource(Args&&... args) {
    value_->SetSource(Source{std::forward<Args>(args)...});
    return *this;
  }

  ValueBuilder& SetComment(const android::StringPiece& str) {
    value_->SetComment(str);
    return *this;
  }

  std::unique_ptr<Value> Build() {
    return std::move(value_);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ValueBuilder);

  std::unique_ptr<Value> value_;
};

class AttributeBuilder {
 public:
  AttributeBuilder();
  AttributeBuilder& SetTypeMask(uint32_t typeMask);
  AttributeBuilder& SetWeak(bool weak);
  AttributeBuilder& AddItem(const android::StringPiece& name, uint32_t value);
  std::unique_ptr<Attribute> Build();

 private:
  DISALLOW_COPY_AND_ASSIGN(AttributeBuilder);

  std::unique_ptr<Attribute> attr_;
};

class StyleBuilder {
 public:
  StyleBuilder() = default;
  StyleBuilder& SetParent(const android::StringPiece& str);
  StyleBuilder& AddItem(const android::StringPiece& str, std::unique_ptr<Item> value);
  StyleBuilder& AddItem(const android::StringPiece& str, const ResourceId& id,
                        std::unique_ptr<Item> value);
  std::unique_ptr<Style> Build();

 private:
  DISALLOW_COPY_AND_ASSIGN(StyleBuilder);

  std::unique_ptr<Style> style_ = util::make_unique<Style>();
};

class StyleableBuilder {
 public:
  StyleableBuilder() = default;
  StyleableBuilder& AddItem(const android::StringPiece& str, const Maybe<ResourceId>& id = {});
  std::unique_ptr<Styleable> Build();

 private:
  DISALLOW_COPY_AND_ASSIGN(StyleableBuilder);

  std::unique_ptr<Styleable> styleable_ = util::make_unique<Styleable>();
};

std::unique_ptr<xml::XmlResource> BuildXmlDom(const android::StringPiece& str);
std::unique_ptr<xml::XmlResource> BuildXmlDomForPackageName(IAaptContext* context,
                                                            const android::StringPiece& str);

class ArtifactBuilder {
 public:
  ArtifactBuilder() = default;

  ArtifactBuilder& SetName(const std::string& name);
  ArtifactBuilder& SetVersion(int version);
  ArtifactBuilder& AddAbi(configuration::Abi abi);
  ArtifactBuilder& AddDensity(const android::ConfigDescription& density);
  ArtifactBuilder& AddLocale(const android::ConfigDescription& locale);
  ArtifactBuilder& SetAndroidSdk(int min_sdk);
  configuration::OutputArtifact Build();

 private:
  DISALLOW_COPY_AND_ASSIGN(ArtifactBuilder);

  configuration::OutputArtifact artifact_;
};

class PostProcessingConfigurationBuilder {
 public:
  PostProcessingConfigurationBuilder() = default;

  PostProcessingConfigurationBuilder& AddAbiGroup(const std::string& label,
                                                  std::vector<configuration::Abi> abis = {});
  PostProcessingConfigurationBuilder& AddDensityGroup(const std::string& label,
                                                      std::vector<std::string> densities = {});
  PostProcessingConfigurationBuilder& AddLocaleGroup(const std::string& label,
                                                     std::vector<std::string> locales = {});
  PostProcessingConfigurationBuilder& AddDeviceFeatureGroup(const std::string& label);
  PostProcessingConfigurationBuilder& AddGlTextureGroup(const std::string& label);
  PostProcessingConfigurationBuilder& AddAndroidSdk(std::string label, int min_sdk);
  PostProcessingConfigurationBuilder& AddArtifact(configuration::ConfiguredArtifact artrifact);

  configuration::PostProcessingConfiguration Build();

 private:
  template <typename T>
  inline PostProcessingConfigurationBuilder& AddGroup(const std::string& label,
                                                      configuration::Group<T>* group,
                                                      std::vector<T> to_add = {}) {
    auto& values = GetOrCreateGroup(label, group);
    values.insert(std::begin(values), std::begin(to_add), std::end(to_add));
    return *this;
  }

  configuration::PostProcessingConfiguration config_;
};

class ConfigDescriptionBuilder {
 public:
  ConfigDescriptionBuilder() = default;

  ConfigDescriptionBuilder& setMcc(uint16_t mcc) {
    config_.mcc = mcc;
    return *this;
  }
  ConfigDescriptionBuilder& setMnc(uint16_t mnc) {
    config_.mnc = mnc;
    return *this;
  }
  ConfigDescriptionBuilder& setLanguage(uint16_t language) {
    config_.language[0] = language >> 8;
    config_.language[1] = language & 0xff;
    return *this;
  }
  ConfigDescriptionBuilder& setCountry(uint16_t country) {
    config_.country[0] = country >> 8;
    config_.country[1] = country & 0xff;
    return *this;
  }
  ConfigDescriptionBuilder& setOrientation(uint8_t orientation) {
    config_.orientation = orientation;
    return *this;
  }
  ConfigDescriptionBuilder& setTouchscreen(uint8_t touchscreen) {
    config_.touchscreen = touchscreen;
    return *this;
  }
  ConfigDescriptionBuilder& setDensity(uint16_t density) {
    config_.density = density;
    return *this;
  }
  ConfigDescriptionBuilder& setKeyboard(uint8_t keyboard) {
    config_.keyboard = keyboard;
    return *this;
  }
  ConfigDescriptionBuilder& setNavigation(uint8_t navigation) {
    config_.navigation = navigation;
    return *this;
  }
  ConfigDescriptionBuilder& setInputFlags(uint8_t inputFlags) {
    config_.inputFlags = inputFlags;
    return *this;
  }
  ConfigDescriptionBuilder& setInputPad0(uint8_t inputPad0) {
    config_.inputPad0 = inputPad0;
    return *this;
  }
  ConfigDescriptionBuilder& setScreenWidth(uint16_t screenWidth) {
    config_.screenWidth = screenWidth;
    return *this;
  }
  ConfigDescriptionBuilder& setScreenHeight(uint16_t screenHeight) {
    config_.screenHeight = screenHeight;
    return *this;
  }
  ConfigDescriptionBuilder& setSdkVersion(uint16_t sdkVersion) {
    config_.sdkVersion = sdkVersion;
    return *this;
  }
  ConfigDescriptionBuilder& setMinorVersion(uint16_t minorVersion) {
    config_.minorVersion = minorVersion;
    return *this;
  }
  ConfigDescriptionBuilder& setScreenLayout(uint8_t screenLayout) {
    config_.screenLayout = screenLayout;
    return *this;
  }
  ConfigDescriptionBuilder& setUiMode(uint8_t uiMode) {
    config_.uiMode = uiMode;
    return *this;
  }
  ConfigDescriptionBuilder& setSmallestScreenWidthDp(uint16_t smallestScreenWidthDp) {
    config_.smallestScreenWidthDp = smallestScreenWidthDp;
    return *this;
  }
  ConfigDescriptionBuilder& setScreenWidthDp(uint16_t screenWidthDp) {
    config_.screenWidthDp = screenWidthDp;
    return *this;
  }
  ConfigDescriptionBuilder& setScreenHeightDp(uint16_t screenHeightDp) {
    config_.screenHeightDp = screenHeightDp;
    return *this;
  }
  ConfigDescriptionBuilder& setScreenLayout2(uint8_t screenLayout2) {
    config_.screenLayout2 = screenLayout2;
    return *this;
  }
  ConfigDescriptionBuilder& setColorMode(uint8_t colorMode) {
    config_.colorMode = colorMode;
    return *this;
  }
  ConfigDescriptionBuilder& setScreenConfigPad2(uint16_t screenConfigPad2) {
    config_.screenConfigPad2 = screenConfigPad2;
    return *this;
  }
  android::ConfigDescription Build() {
    return config_;
  }

 private:
  android::ConfigDescription config_;
};

}  // namespace test
}  // namespace aapt

#endif /* AAPT_TEST_BUILDERS_H */
