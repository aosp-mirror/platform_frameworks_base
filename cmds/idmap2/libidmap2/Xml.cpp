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

#include "idmap2/Xml.h"

#include <map>
#include <memory>
#include <string>
#include <utility>

namespace android::idmap2 {

std::unique_ptr<const Xml> Xml::Create(const uint8_t* data, size_t size, bool copyData) {
  std::unique_ptr<Xml> xml(new Xml());
  if (xml->xml_.setTo(data, size, copyData) != NO_ERROR) {
    return nullptr;
  }
  return xml;
}

std::unique_ptr<std::map<std::string, std::string>> Xml::FindTag(const std::string& name) const {
  const String16 tag_to_find(name.c_str(), name.size());
  xml_.restart();
  ResXMLParser::event_code_t type;
  do {
    type = xml_.next();
    if (type == ResXMLParser::START_TAG) {
      size_t len;
      const String16 tag(xml_.getElementName(&len));
      if (tag == tag_to_find) {
        std::unique_ptr<std::map<std::string, std::string>> map(
            new std::map<std::string, std::string>());
        for (size_t i = 0; i < xml_.getAttributeCount(); i++) {
          const String16 key16(xml_.getAttributeName(i, &len));
          std::string key = String8(key16).c_str();

          std::string value;
          switch (xml_.getAttributeDataType(i)) {
            case Res_value::TYPE_STRING: {
              const String16 value16(xml_.getAttributeStringValue(i, &len));
              value = String8(value16).c_str();
            } break;
            case Res_value::TYPE_INT_DEC:
            case Res_value::TYPE_INT_HEX:
            case Res_value::TYPE_INT_BOOLEAN: {
              Res_value resValue;
              xml_.getAttributeValue(i, &resValue);
              value = std::to_string(resValue.data);
            } break;
            default:
              return nullptr;
          }

          map->emplace(std::make_pair(key, value));
        }
        return map;
      }
    }
  } while (type != ResXMLParser::BAD_DOCUMENT && type != ResXMLParser::END_DOCUMENT);
  return nullptr;
}

Xml::~Xml() {
  xml_.uninit();
}

}  // namespace android::idmap2
