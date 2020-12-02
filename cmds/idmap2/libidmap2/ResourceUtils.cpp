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

#include "idmap2/ResourceUtils.h"

#include <memory>
#include <string>

#include "androidfw/StringPiece.h"
#include "androidfw/Util.h"
#include "idmap2/Result.h"
#include "idmap2/XmlParser.h"
#include "idmap2/ZipFile.h"

using android::StringPiece16;
using android::idmap2::Result;
using android::idmap2::XmlParser;
using android::idmap2::ZipFile;
using android::util::Utf16ToUtf8;

namespace android::idmap2::utils {

bool IsReference(uint8_t data_type) {
  return data_type == Res_value::TYPE_REFERENCE || data_type == Res_value::TYPE_DYNAMIC_REFERENCE;
}

StringPiece DataTypeToString(uint8_t data_type) {
  switch (data_type) {
    case Res_value::TYPE_NULL:
      return "null";
    case Res_value::TYPE_REFERENCE:
      return "reference";
    case Res_value::TYPE_ATTRIBUTE:
      return "attribute";
    case Res_value::TYPE_STRING:
      return "string";
    case Res_value::TYPE_FLOAT:
      return "float";
    case Res_value::TYPE_DIMENSION:
      return "dimension";
    case Res_value::TYPE_FRACTION:
      return "fraction";
    case Res_value::TYPE_DYNAMIC_REFERENCE:
      return "reference (dynamic)";
    case Res_value::TYPE_DYNAMIC_ATTRIBUTE:
      return "attribute (dynamic)";
    case Res_value::TYPE_INT_DEC:
    case Res_value::TYPE_INT_HEX:
      return "integer";
    case Res_value::TYPE_INT_BOOLEAN:
      return "boolean";
    case Res_value::TYPE_INT_COLOR_ARGB8:
    case Res_value::TYPE_INT_COLOR_RGB8:
    case Res_value::TYPE_INT_COLOR_RGB4:
      return "color";
    default:
      return "unknown";
  }
}

Result<std::string> ResToTypeEntryName(const AssetManager2& am, uint32_t resid) {
  const auto name = am.GetResourceName(resid);
  if (!name.has_value()) {
    return Error("no resource 0x%08x in asset manager", resid);
  }
  std::string out;
  if (name->type != nullptr) {
    out.append(name->type, name->type_len);
  } else {
    out += Utf16ToUtf8(StringPiece16(name->type16, name->type_len));
  }
  out.append("/");
  if (name->entry != nullptr) {
    out.append(name->entry, name->entry_len);
  } else {
    out += Utf16ToUtf8(StringPiece16(name->entry16, name->entry_len));
  }
  return out;
}

Result<OverlayManifestInfo> ExtractOverlayManifestInfo(const std::string& path,
                                                       const std::string& name) {
  std::unique_ptr<const ZipFile> zip = ZipFile::Open(path);
  if (!zip) {
    return Error("failed to open %s as a zip file", path.c_str());
  }

  std::unique_ptr<const MemoryChunk> entry = zip->Uncompress("AndroidManifest.xml");
  if (!entry) {
    return Error("failed to uncompress AndroidManifest.xml from %s", path.c_str());
  }

  Result<std::unique_ptr<const XmlParser>> xml = XmlParser::Create(entry->buf, entry->size);
  if (!xml) {
    return Error("failed to parse AndroidManifest.xml from %s", path.c_str());
  }

  auto manifest_it = (*xml)->tree_iterator();
  if (manifest_it->event() != XmlParser::Event::START_TAG || manifest_it->name() != "manifest") {
    return Error("root element tag is not <manifest> in AndroidManifest.xml of %s", path.c_str());
  }

  for (auto&& it : manifest_it) {
    if (it.event() != XmlParser::Event::START_TAG || it.name() != "overlay") {
      continue;
    }

    OverlayManifestInfo info{};
    if (auto result_str = it.GetAttributeStringValue("name")) {
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

    if (auto result_str = it.GetAttributeStringValue("targetPackage")) {
      info.target_package = *result_str;
    } else {
      return Error("android:targetPackage missing from <overlay> of %s: %s", path.c_str(),
                   result_str.GetErrorMessage().c_str());
    }

    if (auto result_str = it.GetAttributeStringValue("targetName")) {
      info.target_name = *result_str;
    }

    if (auto result_value = it.GetAttributeValue("resourcesMap")) {
      if (IsReference((*result_value).dataType)) {
        info.resource_mapping = (*result_value).data;
      } else {
        return Error("android:resourcesMap is not a reference in AndroidManifest.xml of %s",
                     path.c_str());
      }
    }
    return info;
  }

  return Error("<overlay> with android:name \"%s\" missing from AndroidManifest.xml of %s",
               name.c_str(), path.c_str());
}

}  // namespace android::idmap2::utils
