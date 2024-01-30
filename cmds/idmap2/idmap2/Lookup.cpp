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

#include <algorithm>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "androidfw/ApkAssets.h"
#include "androidfw/AssetManager2.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/ResourceUtils.h"
#include "androidfw/StringPiece.h"
#include "androidfw/Util.h"
#include "idmap2/CommandLineOptions.h"
#include "idmap2/Idmap.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"
#include "idmap2/SysTrace.h"
#include "idmap2/XmlParser.h"
#include "utils/String16.h"
#include "utils/String8.h"

using android::ApkAssets;
using android::ApkAssetsCookie;
using android::AssetManager2;
using android::ConfigDescription;
using android::Res_value;
using android::ResStringPool;
using android::StringPiece16;
using android::base::StringPrintf;
using android::idmap2::CommandLineOptions;
using android::idmap2::Error;
using android::idmap2::IdmapHeader;
using android::idmap2::OverlayResourceContainer;
using android::idmap2::ResourceId;
using android::idmap2::Result;
using android::idmap2::Unit;

namespace {

Result<ResourceId> WARN_UNUSED ParseResReference(const AssetManager2& am, const std::string& res,
                                                 const std::string& fallback_package) {
  static constexpr const int kBaseHex = 16;

  // first, try to parse as a hex number
  char* endptr = nullptr;
  const ResourceId parsed_resid = strtol(res.c_str(), &endptr, kBaseHex);
  if (*endptr == '\0') {
    return parsed_resid;
  }

  // next, try to parse as a package:type/name string
  if (auto resid = am.GetResourceId(res, "", fallback_package); resid.ok()) {
    return *resid;
  }

  // end of the road: res could not be parsed
  return Error("failed to obtain resource id for %s", res.c_str());
}

void PrintValue(AssetManager2* const am, const AssetManager2::SelectedValue& value,
                std::string* const out) {
  switch (value.type) {
    case Res_value::TYPE_INT_DEC:
      out->append(StringPrintf("%d", value.data));
      break;
    case Res_value::TYPE_INT_HEX:
      out->append(StringPrintf("0x%08x", value.data));
      break;
    case Res_value::TYPE_INT_BOOLEAN:
      out->append(value.data != 0 ? "true" : "false");
      break;
    case Res_value::TYPE_STRING: {
      const ResStringPool* pool = am->GetStringPoolForCookie(value.cookie);
      out->append("\"");
      if (auto str = pool->string8ObjectAt(value.data); str.ok()) {
        out->append(str->c_str());
      }
    } break;
    default:
      out->append(StringPrintf("dataType=0x%02x data=0x%08x", value.type, value.data));
      break;
  }
}

Result<std::string> WARN_UNUSED GetValue(AssetManager2* const am, ResourceId resid) {
  auto value = am->GetResource(resid);
  if (!value.has_value()) {
    return Error("no resource 0x%08x in asset manager", resid);
  }

  std::string out;

  // TODO(martenkongstad): use optional parameter GetResource(..., std::string*
  // stacktrace = NULL) instead
  out.append(StringPrintf("cookie=%d ", value->cookie));

  out.append("config='");
  out.append(value->config.toString().c_str());
  out.append("' value=");

  if (value->type == Res_value::TYPE_REFERENCE) {
    auto bag_result = am->GetBag(static_cast<uint32_t>(value->data));
    if (!bag_result.has_value()) {
      out.append(StringPrintf("dataType=0x%02x data=0x%08x", value->type, value->data));
      return out;
    }

    out.append("[");
    const android::ResolvedBag* bag = bag_result.value();
    for (size_t i = 0; i < bag->entry_count; ++i) {
      AssetManager2::SelectedValue entry(bag, bag->entries[i]);
      if (am->ResolveReference(entry).has_value()) {
        out.append(StringPrintf("Error: dataType=0x%02x data=0x%08x", entry.type, entry.data));
        continue;
      }
      PrintValue(am, entry, &out);
      if (i != bag->entry_count - 1) {
        out.append(", ");
      }
    }
    out.append("]");
  } else {
    PrintValue(am, *value, &out);
  }

  return out;
}

}  // namespace

Result<Unit> Lookup(const std::vector<std::string>& args) {
  SYSTRACE << "Lookup " << args;
  std::vector<std::string> idmap_paths;
  std::string config_str;
  std::string resid_str;

  const CommandLineOptions opts =
      CommandLineOptions("idmap2 lookup")
          .MandatoryOption("--idmap-path", "input: path to idmap file to load", &idmap_paths)
          .MandatoryOption("--config", "configuration to use", &config_str)
          .MandatoryOption("--resid",
                           "Resource ID (in the target package; '0xpptteeee' or "
                           "'[package:]type/name') to look up",
                           &resid_str);

  const auto opts_ok = opts.Parse(args);
  if (!opts_ok) {
    return opts_ok.GetError();
  }

  ConfigDescription config;
  if (!ConfigDescription::Parse(config_str, &config)) {
    return Error("failed to parse config");
  }

  std::vector<AssetManager2::ApkAssetsPtr> apk_assets;
  std::string target_path;
  std::string target_package_name;
  for (size_t i = 0; i < idmap_paths.size(); i++) {
    const auto& idmap_path = idmap_paths[i];
    std::fstream fin(idmap_path);
    auto idmap_header = IdmapHeader::FromBinaryStream(fin);
    fin.close();
    if (!idmap_header) {
      return Error("failed to read idmap from %s", idmap_path.c_str());
    }

    if (i == 0) {
      target_path = idmap_header->GetTargetPath();
      auto target_apk = ApkAssets::Load(target_path);
      if (!target_apk) {
        return Error("failed to read target apk from %s", target_path.c_str());
      }
      apk_assets.push_back(std::move(target_apk));

      auto overlay = OverlayResourceContainer::FromPath(idmap_header->GetOverlayPath());
      if (!overlay) {
        return overlay.GetError();
      }

      auto manifest_info = (*overlay)->FindOverlayInfo(idmap_header->GetOverlayName());
      if (!manifest_info) {
        return manifest_info.GetError();
      }

      target_package_name = (*manifest_info).target_package;
    } else if (target_path != idmap_header->GetTargetPath()) {
      return Error("different target APKs (expected target APK %s but %s has target APK %s)",
                   target_path.c_str(), idmap_path.c_str(), idmap_header->GetTargetPath().c_str());
    }

    auto overlay_apk = ApkAssets::LoadOverlay(idmap_path);
    if (!overlay_apk) {
      return Error("failed to read overlay apk from %s", idmap_header->GetOverlayPath().c_str());
    }
    apk_assets.push_back(std::move(overlay_apk));
  }

  {
    // Make sure |apk_assets| vector outlives the asset manager as it doesn't own the assets.
    AssetManager2 am(apk_assets, config);

    const Result<ResourceId> resid = ParseResReference(am, resid_str, target_package_name);
    if (!resid) {
      return Error(resid.GetError(), "failed to parse resource ID");
    }

    const Result<std::string> value = GetValue(&am, *resid);
    if (!value) {
      return Error(value.GetError(), "resource 0x%08x not found", *resid);
    }
    std::cout << *value << '\n';
  }

  return Unit{};
}
