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
#include <iterator>
#include <memory>
#include <ostream>
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
#include "idmap2/ZipFile.h"
#include "utils/String16.h"
#include "utils/String8.h"

using android::ApkAssets;
using android::ApkAssetsCookie;
using android::AssetManager2;
using android::ConfigDescription;
using android::is_valid_resid;
using android::kInvalidCookie;
using android::Res_value;
using android::ResStringPool;
using android::ResTable_config;
using android::StringPiece16;
using android::base::StringPrintf;
using android::idmap2::CommandLineOptions;
using android::idmap2::Error;
using android::idmap2::IdmapHeader;
using android::idmap2::ResourceId;
using android::idmap2::Result;
using android::idmap2::Unit;
using android::idmap2::utils::ExtractOverlayManifestInfo;
using android::util::Utf16ToUtf8;

namespace {

Result<ResourceId> WARN_UNUSED ParseResReference(const AssetManager2& am, const std::string& res,
                                                 const std::string& fallback_package) {
  static constexpr const int kBaseHex = 16;

  // first, try to parse as a hex number
  char* endptr = nullptr;
  ResourceId resid;
  resid = strtol(res.c_str(), &endptr, kBaseHex);
  if (*endptr == '\0') {
    return resid;
  }

  // next, try to parse as a package:type/name string
  resid = am.GetResourceId(res, "", fallback_package);
  if (is_valid_resid(resid)) {
    return resid;
  }

  // end of the road: res could not be parsed
  return Error("failed to obtain resource id for %s", res.c_str());
}

void PrintValue(AssetManager2* const am, const Res_value& value, const ApkAssetsCookie& cookie,
                std::string* const out) {
  switch (value.dataType) {
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
      const ResStringPool* pool = am->GetStringPoolForCookie(cookie);
      out->append("\"");
      size_t len;
      if (pool->isUTF8()) {
        const char* str = pool->string8At(value.data, &len);
        out->append(str, len);
      } else {
        const char16_t* str16 = pool->stringAt(value.data, &len);
        out->append(Utf16ToUtf8(StringPiece16(str16, len)));
      }
      out->append("\"");
    } break;
    default:
      out->append(StringPrintf("dataType=0x%02x data=0x%08x", value.dataType, value.data));
      break;
  }
}

Result<std::string> WARN_UNUSED GetValue(AssetManager2* const am, ResourceId resid) {
  Res_value value;
  ResTable_config config;
  uint32_t flags;
  ApkAssetsCookie cookie = am->GetResource(resid, true, 0, &value, &config, &flags);
  if (cookie == kInvalidCookie) {
    return Error("no resource 0x%08x in asset manager", resid);
  }

  std::string out;

  // TODO(martenkongstad): use optional parameter GetResource(..., std::string*
  // stacktrace = NULL) instead
  out.append(StringPrintf("cookie=%d ", cookie));

  out.append("config='");
  out.append(config.toString().c_str());
  out.append("' value=");

  if (value.dataType == Res_value::TYPE_REFERENCE) {
    const android::ResolvedBag* bag = am->GetBag(static_cast<uint32_t>(value.data));
    if (bag == nullptr) {
      out.append(StringPrintf("dataType=0x%02x data=0x%08x", value.dataType, value.data));
      return out;
    }
    out.append("[");
    Res_value bag_val;
    ResTable_config selected_config;
    uint32_t flags;
    uint32_t ref;
    ApkAssetsCookie bag_cookie;
    for (size_t i = 0; i < bag->entry_count; ++i) {
      const android::ResolvedBag::Entry& entry = bag->entries[i];
      bag_val = entry.value;
      bag_cookie = am->ResolveReference(entry.cookie, &bag_val, &selected_config, &flags, &ref);
      if (bag_cookie == kInvalidCookie) {
        out.append(
            StringPrintf("Error: dataType=0x%02x data=0x%08x", bag_val.dataType, bag_val.data));
        continue;
      }
      PrintValue(am, bag_val, bag_cookie, &out);
      if (i != bag->entry_count - 1) {
        out.append(", ");
      }
    }
    out.append("]");
  } else {
    PrintValue(am, value, cookie, &out);
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

  std::vector<std::unique_ptr<const ApkAssets>> apk_assets;
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
      target_path = idmap_header->GetTargetPath().to_string();
      auto target_apk = ApkAssets::Load(target_path);
      if (!target_apk) {
        return Error("failed to read target apk from %s", target_path.c_str());
      }
      apk_assets.push_back(std::move(target_apk));

      auto manifest_info = ExtractOverlayManifestInfo(idmap_header->GetOverlayPath().to_string(),
                                                      true /* assert_overlay */);
      if (!manifest_info) {
        return manifest_info.GetError();
      }
      target_package_name = (*manifest_info).target_package;
    } else if (target_path != idmap_header->GetTargetPath()) {
      return Error("different target APKs (expected target APK %s but %s has target APK %s)",
                   target_path.c_str(), idmap_path.c_str(),
                   idmap_header->GetTargetPath().to_string().c_str());
    }

    auto overlay_apk = ApkAssets::LoadOverlay(idmap_path);
    if (!overlay_apk) {
      return Error("failed to read overlay apk from %s",
                   idmap_header->GetOverlayPath().to_string().c_str());
    }
    apk_assets.push_back(std::move(overlay_apk));
  }

  // AssetManager2::SetApkAssets requires raw ApkAssets pointers, not unique_ptrs
  std::vector<const ApkAssets*> raw_pointer_apk_assets;
  std::transform(apk_assets.cbegin(), apk_assets.cend(), std::back_inserter(raw_pointer_apk_assets),
                 [](const auto& p) -> const ApkAssets* { return p.get(); });
  AssetManager2 am;
  am.SetApkAssets(raw_pointer_apk_assets);
  am.SetConfiguration(config);

  const Result<ResourceId> resid = ParseResReference(am, resid_str, target_package_name);
  if (!resid) {
    return Error(resid.GetError(), "failed to parse resource ID");
  }

  const Result<std::string> value = GetValue(&am, *resid);
  if (!value) {
    return Error(value.GetError(), "resource 0x%08x not found", *resid);
  }
  std::cout << *value << std::endl;

  return Unit{};
}
