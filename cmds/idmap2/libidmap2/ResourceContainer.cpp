/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include "idmap2/ResourceContainer.h"

#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "androidfw/ApkAssets.h"
#include "androidfw/AssetManager.h"
#include "androidfw/Util.h"
#include "idmap2/FabricatedOverlay.h"
#include "idmap2/XmlParser.h"

namespace android::idmap2 {
namespace {
#define REWRITE_PACKAGE(resid, package_id) \
  (((resid)&0x00ffffffU) | (((uint32_t)(package_id)) << 24U))

#define EXTRACT_PACKAGE(resid) ((0xff000000 & (resid)) >> 24)

constexpr ResourceId kAttrName = 0x01010003;
constexpr ResourceId kAttrResourcesMap = 0x01010609;
constexpr ResourceId kAttrTargetName = 0x0101044d;
constexpr ResourceId kAttrTargetPackage = 0x01010021;

// idmap version 0x01 naively assumes that the package to use is always the first ResTable_package
// in the resources.arsc blob. In most cases, there is only a single ResTable_package anyway, so
// this assumption tends to work out. That said, the correct thing to do is to scan
// resources.arsc for a package with a given name as read from the package manifest instead of
// relying on a hard-coded index. This however requires storing the package name in the idmap
// header, which in turn requires incrementing the idmap version. Because the initial version of
// idmap2 is compatible with idmap, this will have to wait for now.
const LoadedPackage* GetPackageAtIndex0(const LoadedArsc* loaded_arsc) {
  const std::vector<std::unique_ptr<const LoadedPackage>>& packages = loaded_arsc->GetPackages();
  if (packages.empty()) {
    return nullptr;
  }
  return loaded_arsc->GetPackageById(packages[0]->GetPackageId());
}

Result<uint32_t> CalculateCrc(const ZipAssetsProvider* zip_assets) {
  constexpr const char* kResourcesArsc = "resources.arsc";
  std::optional<uint32_t> res_crc = zip_assets->GetCrc(kResourcesArsc);
  if (!res_crc) {
    return Error("failed to get CRC for '%s'", kResourcesArsc);
  }

  constexpr const char* kManifest = "AndroidManifest.xml";
  std::optional<uint32_t> man_crc = zip_assets->GetCrc(kManifest);
  if (!man_crc) {
    return Error("failed to get CRC for '%s'", kManifest);
  }

  return *res_crc ^ *man_crc;
}

Result<XmlParser> OpenXmlParser(const std::string& entry_path, const ZipAssetsProvider* zip) {
  auto manifest = zip->Open(entry_path);
  if (manifest == nullptr) {
    return Error("failed to find %s ", entry_path.c_str());
  }

  auto size = manifest->getLength();
  auto buffer = manifest->getIncFsBuffer(true /* aligned */).convert<uint8_t>();
  if (!buffer.verify(size)) {
    return Error("failed to read entire %s", entry_path.c_str());
  }

  return XmlParser::Create(buffer.unsafe_ptr(), size, true /* copyData */);
}

Result<XmlParser> OpenXmlParser(ResourceId id, const ZipAssetsProvider* zip,
                                const AssetManager2* am) {
  const auto ref_table = am->GetDynamicRefTableForCookie(0);
  if (ref_table == nullptr) {
    return Error("failed to find dynamic ref table for cookie 0");
  }

  ref_table->lookupResourceId(&id);
  auto value = am->GetResource(id);
  if (!value.has_value()) {
    return Error("failed to find resource for id 0x%08x", id);
  }

  if (value->type != Res_value::TYPE_STRING) {
    return Error("resource for is 0x%08x is not a file", id);
  }

  auto string_pool = am->GetStringPoolForCookie(value->cookie);
  auto file = string_pool->string8ObjectAt(value->data);
  if (!file.has_value()) {
    return Error("failed to find string for index %d", value->data);
  }

  return OpenXmlParser(file->c_str(), zip);
}

Result<OverlayManifestInfo> ExtractOverlayManifestInfo(const ZipAssetsProvider* zip,
                                                       const std::string& name) {
  Result<XmlParser> xml = OpenXmlParser("AndroidManifest.xml", zip);
  if (!xml) {
    return xml.GetError();
  }

  auto manifest_it = xml->tree_iterator();
  if (manifest_it->event() != XmlParser::Event::START_TAG || manifest_it->name() != "manifest") {
    return Error("root element tag is not <manifest> in AndroidManifest.xml");
  }

  std::string package_name;
  if (auto result_str = manifest_it->GetAttributeStringValue("package")) {
    package_name = *result_str;
  } else {
    return result_str.GetError();
  }

  for (auto&& it : manifest_it) {
    if (it.event() != XmlParser::Event::START_TAG || it.name() != "overlay") {
      continue;
    }

    OverlayManifestInfo info{};
    info.package_name = package_name;
    if (auto result_str = it.GetAttributeStringValue(kAttrName, "android:name")) {
      if (*result_str != name) {
        // A value for android:name was found, but either a the name does not match the requested
        // name, or an <overlay> tag with no name was requested.
        continue;
      }
      info.name = *result_str;
    } else if (!name.empty()) {
      // This tag does not have a value for android:name, but an <overlay> tag with a specific name
      // has been requested.
      continue;
    }

    if (auto result_str = it.GetAttributeStringValue(kAttrTargetPackage, "android:targetPackage")) {
      info.target_package = *result_str;
    } else {
      return Error("android:targetPackage missing from <overlay> in AndroidManifest.xml");
    }

    if (auto result_str = it.GetAttributeStringValue(kAttrTargetName, "android:targetName")) {
      info.target_name = *result_str;
    }

    if (auto result_value = it.GetAttributeValue(kAttrResourcesMap, "android:resourcesMap")) {
      if (utils::IsReference((*result_value).dataType)) {
        info.resource_mapping = (*result_value).data;
      } else {
        return Error("android:resourcesMap is not a reference in AndroidManifest.xml");
      }
    }
    return info;
  }

  return Error("<overlay> with android:name \"%s\" missing from AndroidManifest.xml", name.c_str());
}

Result<OverlayData> CreateResourceMapping(ResourceId id, const ZipAssetsProvider* zip,
                                          const AssetManager2* overlay_am,
                                          const LoadedArsc* overlay_arsc,
                                          const LoadedPackage* overlay_package) {
  auto parser = OpenXmlParser(id, zip, overlay_am);
  if (!parser) {
    return parser.GetError();
  }

  OverlayData overlay_data{};
  const uint32_t string_pool_offset = overlay_arsc->GetStringPool()->size();
  const uint8_t package_id = overlay_package->GetPackageId();
  auto root_it = parser->tree_iterator();
  if (root_it->event() != XmlParser::Event::START_TAG || root_it->name() != "overlay") {
    return Error("root element is not <overlay> tag");
  }

  auto overlay_it_end = root_it.end();
  for (auto overlay_it = root_it.begin(); overlay_it != overlay_it_end; ++overlay_it) {
    if (overlay_it->event() == XmlParser::Event::BAD_DOCUMENT) {
      return Error("failed to parse overlay xml document");
    }

    if (overlay_it->event() != XmlParser::Event::START_TAG) {
      continue;
    }

    if (overlay_it->name() != "item") {
      return Error("unexpected tag <%s> in <overlay>", overlay_it->name().c_str());
    }

    Result<std::string> target_resource = overlay_it->GetAttributeStringValue("target");
    if (!target_resource) {
      return Error(R"(<item> tag missing expected attribute "target")");
    }

    Result<android::Res_value> overlay_resource = overlay_it->GetAttributeValue("value");
    if (!overlay_resource) {
      return Error(R"(<item> tag missing expected attribute "value")");
    }

    if (overlay_resource->dataType == Res_value::TYPE_STRING) {
      overlay_resource->data += string_pool_offset;
    }

    if (utils::IsReference(overlay_resource->dataType)) {
      // Only rewrite resources defined within the overlay package to their corresponding target
      // resource ids at runtime.
      bool rewrite_id = package_id == EXTRACT_PACKAGE(overlay_resource->data);
      overlay_data.pairs.emplace_back(OverlayData::Value{
          *target_resource, OverlayData::ResourceIdValue{overlay_resource->data, rewrite_id}});
    } else {
      overlay_data.pairs.emplace_back(
          OverlayData::Value{*target_resource, TargetValueWithConfig{
              .value = TargetValue{.data_type = overlay_resource->dataType,
                                   .data_value = overlay_resource->data},
              .config = std::string()}});
    }
  }

  const auto& string_pool = parser->get_strings();
  const uint32_t string_pool_data_length = string_pool.bytes();
  overlay_data.string_pool_data = OverlayData::InlineStringPoolData{
      .data = std::unique_ptr<uint8_t[]>(new uint8_t[string_pool_data_length]),
      .data_length = string_pool_data_length,
      .string_pool_offset = string_pool_offset,
  };

  // Overlays should not be incrementally installed, so calling unsafe_ptr is fine here.
  memcpy(overlay_data.string_pool_data->data.get(), string_pool.data().unsafe_ptr(),
         string_pool_data_length);
  return overlay_data;
}

OverlayData CreateResourceMappingLegacy(const AssetManager2* overlay_am,
                                        const LoadedPackage* overlay_package) {
  OverlayData overlay_data{};
  for (const ResourceId overlay_resid : *overlay_package) {
    if (auto name = utils::ResToTypeEntryName(*overlay_am, overlay_resid)) {
      // Disable rewriting. Overlays did not support internal references before
      // android:resourcesMap. Do not introduce new behavior.
      overlay_data.pairs.emplace_back(OverlayData::Value{
          *name, OverlayData::ResourceIdValue{overlay_resid, false /* rewrite_id */}});
    }
  }
  return overlay_data;
}

struct ResState {
  AssetManager2::ApkAssetsPtr apk_assets;
  const LoadedArsc* arsc;
  const LoadedPackage* package;
  std::unique_ptr<AssetManager2> am;
  ZipAssetsProvider* zip_assets;

  static Result<ResState> Initialize(std::unique_ptr<ZipAssetsProvider> zip,
                                     package_property_t flags) {
    ResState state;
    state.zip_assets = zip.get();
    if ((state.apk_assets = ApkAssets::Load(std::move(zip), flags)) == nullptr) {
      return Error("failed to load apk asset");
    }

    if ((state.arsc = state.apk_assets->GetLoadedArsc()) == nullptr) {
      return Error("failed to retrieve loaded arsc");
    }

    if ((state.package = GetPackageAtIndex0(state.arsc)) == nullptr) {
      return Error("failed to retrieve loaded package at index 0");
    }

    state.am = std::make_unique<AssetManager2>();
    if (!state.am->SetApkAssets({state.apk_assets}, false)) {
      return Error("failed to create asset manager");
    }

    return state;
  }
};

}  // namespace

struct ApkResourceContainer : public TargetResourceContainer, public OverlayResourceContainer {
  static Result<std::unique_ptr<ApkResourceContainer>> FromPath(const std::string& path);

  // inherited from TargetResourceContainer
  Result<bool> DefinesOverlayable() const override;
  Result<const android::OverlayableInfo*> GetOverlayableInfo(ResourceId id) const override;
  Result<ResourceId> GetResourceId(const std::string& name) const override;

  // inherited from OverlayResourceContainer
  Result<OverlayData> GetOverlayData(const OverlayManifestInfo& info) const override;
  Result<OverlayManifestInfo> FindOverlayInfo(const std::string& name) const override;

  // inherited from ResourceContainer
  Result<uint32_t> GetCrc() const override;
  Result<std::string> GetResourceName(ResourceId id) const override;
  const std::string& GetPath() const override;

  ~ApkResourceContainer() override = default;

 private:
  ApkResourceContainer(std::unique_ptr<ZipAssetsProvider> zip_assets, std::string path);

  Result<const ResState*> GetState() const;
  ZipAssetsProvider* GetZipAssets() const;

  mutable std::variant<std::unique_ptr<ZipAssetsProvider>, ResState> state_;
  std::string path_;
};

ApkResourceContainer::ApkResourceContainer(std::unique_ptr<ZipAssetsProvider> zip_assets,
                                           std::string path)
    : state_(std::move(zip_assets)), path_(std::move(path)) {
}

Result<std::unique_ptr<ApkResourceContainer>> ApkResourceContainer::FromPath(
    const std::string& path) {
  auto zip_assets = ZipAssetsProvider::Create(path, 0 /* flags */);
  if (zip_assets == nullptr) {
    return Error("failed to load zip assets");
  }
  return std::unique_ptr<ApkResourceContainer>(
      new ApkResourceContainer(std::move(zip_assets), path));
}

Result<const ResState*> ApkResourceContainer::GetState() const {
  if (auto state = std::get_if<ResState>(&state_); state != nullptr) {
    return state;
  }

  auto state = ResState::Initialize(std::move(std::get<std::unique_ptr<ZipAssetsProvider>>(state_)),
                                    PROPERTY_OPTIMIZE_NAME_LOOKUPS);
  if (!state) {
    return state.GetError();
  }

  state_ = std::move(*state);
  return &std::get<ResState>(state_);
}

ZipAssetsProvider* ApkResourceContainer::GetZipAssets() const {
  if (auto zip = std::get_if<std::unique_ptr<ZipAssetsProvider>>(&state_); zip != nullptr) {
    return zip->get();
  }
  return std::get<ResState>(state_).zip_assets;
}

Result<bool> ApkResourceContainer::DefinesOverlayable() const {
  auto state = GetState();
  if (!state) {
    return state.GetError();
  }
  return (*state)->package->DefinesOverlayable();
}

Result<const android::OverlayableInfo*> ApkResourceContainer::GetOverlayableInfo(
    ResourceId id) const {
  auto state = GetState();
  if (!state) {
    return state.GetError();
  }
  return (*state)->package->GetOverlayableInfo(id);
}

Result<OverlayManifestInfo> ApkResourceContainer::FindOverlayInfo(const std::string& name) const {
  return ExtractOverlayManifestInfo(GetZipAssets(), name);
}

Result<OverlayData> ApkResourceContainer::GetOverlayData(const OverlayManifestInfo& info) const {
  const auto state = GetState();
  if (!state) {
    return state.GetError();
  }

  if (info.resource_mapping != 0) {
    return CreateResourceMapping(info.resource_mapping, GetZipAssets(), (*state)->am.get(),
                                 (*state)->arsc, (*state)->package);
  }
  return CreateResourceMappingLegacy((*state)->am.get(), (*state)->package);
}

Result<uint32_t> ApkResourceContainer::GetCrc() const {
  return CalculateCrc(GetZipAssets());
}

const std::string& ApkResourceContainer::GetPath() const {
  return path_;
}

Result<ResourceId> ApkResourceContainer::GetResourceId(const std::string& name) const {
  auto state = GetState();
  if (!state) {
    return state.GetError();
  }
  auto id = (*state)->am->GetResourceId(name, "", (*state)->package->GetPackageName());
  if (!id.has_value()) {
    return Error("failed to find resource '%s'", name.c_str());
  }

  // Retrieve the compile-time resource id of the target resource.
  return REWRITE_PACKAGE(*id, (*state)->package->GetPackageId());
}

Result<std::string> ApkResourceContainer::GetResourceName(ResourceId id) const {
  auto state = GetState();
  if (!state) {
    return state.GetError();
  }
  return utils::ResToTypeEntryName(*(*state)->am, id);
}

Result<std::unique_ptr<TargetResourceContainer>> TargetResourceContainer::FromPath(
    std::string path) {
  auto result = ApkResourceContainer::FromPath(path);
  if (!result) {
    return result.GetError();
  }
  return std::unique_ptr<TargetResourceContainer>(result->release());
}

Result<std::unique_ptr<OverlayResourceContainer>> OverlayResourceContainer::FromPath(
    std::string path) {
  // Load the path as a fabricated overlay if the file magic indicates this is a fabricated overlay.
  if (android::IsFabricatedOverlay(path)) {
    auto result = FabricatedOverlayContainer::FromPath(path);
    if (!result) {
      return result.GetError();
    }
    return std::unique_ptr<OverlayResourceContainer>(result->release());
  }

  // Fallback to loading the container as an APK.
  auto result = ApkResourceContainer::FromPath(path);
  if (!result) {
    return result.GetError();
  }
  return std::unique_ptr<OverlayResourceContainer>(result->release());
}

}  // namespace android::idmap2
