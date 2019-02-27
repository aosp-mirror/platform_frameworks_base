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

#include <memory>
#include <string>

#include "androidfw/StringPiece.h"
#include "androidfw/Util.h"

#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"
#include "idmap2/Xml.h"
#include "idmap2/ZipFile.h"

using android::StringPiece16;
using android::idmap2::Result;
using android::idmap2::Xml;
using android::idmap2::ZipFile;
using android::util::Utf16ToUtf8;

namespace android::idmap2::utils {

Result<std::string> ResToTypeEntryName(const AssetManager2& am, ResourceId resid) {
  AssetManager2::ResourceName name;
  if (!am.GetResourceName(resid, &name)) {
    return Error("no resource 0x%08x in asset manager", resid);
  }
  std::string out;
  if (name.type != nullptr) {
    out.append(name.type, name.type_len);
  } else {
    out += Utf16ToUtf8(StringPiece16(name.type16, name.type_len));
  }
  out.append("/");
  if (name.entry != nullptr) {
    out.append(name.entry, name.entry_len);
  } else {
    out += Utf16ToUtf8(StringPiece16(name.entry16, name.entry_len));
  }
  return out;
}

Result<OverlayManifestInfo> ExtractOverlayManifestInfo(const std::string& path,
                                                       bool assert_overlay) {
  std::unique_ptr<const ZipFile> zip = ZipFile::Open(path);
  if (!zip) {
    return Error("failed to open %s as a zip file", path.c_str());
  }

  std::unique_ptr<const MemoryChunk> entry = zip->Uncompress("AndroidManifest.xml");
  if (!entry) {
    return Error("failed to uncompress AndroidManifest.xml from %s", path.c_str());
  }

  std::unique_ptr<const Xml> xml = Xml::Create(entry->buf, entry->size);
  if (!xml) {
    return Error("failed to parse AndroidManifest.xml from %s", path.c_str());
  }

  OverlayManifestInfo info{};
  const auto tag = xml->FindTag("overlay");
  if (!tag) {
    if (assert_overlay) {
      return Error("<overlay> missing from AndroidManifest.xml of %s", path.c_str());
    }
    return info;
  }

  auto iter = tag->find("targetPackage");
  if (iter == tag->end()) {
    if (assert_overlay) {
      return Error("android:targetPackage missing from <overlay> of %s", path.c_str());
    }
  } else {
    info.target_package = iter->second;
  }

  iter = tag->find("targetName");
  if (iter != tag->end()) {
    info.target_name = iter->second;
  }

  iter = tag->find("isStatic");
  if (iter != tag->end()) {
    info.is_static = std::stoul(iter->second) != 0U;
  }

  iter = tag->find("priority");
  if (iter != tag->end()) {
    info.priority = std::stoi(iter->second);
  }

  return info;
}

}  // namespace android::idmap2::utils
