/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "idmap2/XmlParser.h"

#include <iostream>
#include <map>
#include <memory>
#include <string>
#include <utility>

namespace android::idmap2 {

template <typename T>
ResXMLParser::ResXMLPosition get_tree_position(const T& tree) {
  ResXMLParser::ResXMLPosition pos{};
  tree.getPosition(&pos);
  return pos;
}

XmlParser::Node::Node(const ResXMLTree& tree) : Node(tree, get_tree_position(tree)) {
}
XmlParser::Node::Node(const ResXMLTree& tree, const ResXMLParser::ResXMLPosition& pos)
    : parser_(tree) {
  set_position(pos);
}

bool XmlParser::Node::operator==(const XmlParser::Node& rhs) const {
  ResXMLParser::ResXMLPosition pos = get_position();
  ResXMLParser::ResXMLPosition rhs_pos = rhs.get_position();
  return pos.curExt == rhs_pos.curExt && pos.curNode == rhs_pos.curNode &&
         pos.eventCode == rhs_pos.eventCode;
}

bool XmlParser::Node::operator!=(const XmlParser::Node& rhs) const {
  return !(*this == rhs);
}

ResXMLParser::ResXMLPosition XmlParser::Node::get_position() const {
  return get_tree_position(parser_);
}

void XmlParser::Node::set_position(const ResXMLParser::ResXMLPosition& pos) {
  parser_.setPosition(pos);
}

bool XmlParser::Node::Seek(bool inner_child) {
  if (parser_.getEventType() == XmlParser::Event::END_TAG) {
    return false;
  }

  ssize_t depth = 0;
  XmlParser::Event code;
  while ((code = parser_.next()) != XmlParser::Event::BAD_DOCUMENT &&
         code != XmlParser::Event::END_DOCUMENT) {
    if (code == XmlParser::Event::START_TAG) {
      if (++depth == (inner_child ? 1 : 0)) {
        return true;
      }
    } else if (code == XmlParser::Event::END_TAG) {
      if (--depth == (inner_child ? -1 : -2)) {
        return false;
      }
    }
  }

  return false;
}

XmlParser::Event XmlParser::Node::event() const {
  return parser_.getEventType();
}

std::string XmlParser::Node::name() const {
  size_t len;
  const String16 key16(parser_.getElementName(&len));
  return String8(key16).c_str();
}

Result<std::string> XmlParser::Node::GetAttributeStringValue(const std::string& name) const {
  auto value = GetAttributeValue(name);
  if (!value) {
    return value.GetError();
  }

  switch ((*value).dataType) {
    case Res_value::TYPE_STRING: {
      size_t len;
      const String16 value16(parser_.getStrings().stringAt((*value).data, &len));
      return std::string(String8(value16).c_str());
    }
    case Res_value::TYPE_INT_DEC:
    case Res_value::TYPE_INT_HEX:
    case Res_value::TYPE_INT_BOOLEAN: {
      return std::to_string((*value).data);
    }
    default:
      return Error(R"(Failed to convert attribute "%s" value to a string)", name.c_str());
  }
}

Result<Res_value> XmlParser::Node::GetAttributeValue(const std::string& name) const {
  size_t len;
  for (size_t i = 0; i < parser_.getAttributeCount(); i++) {
    const String16 key16(parser_.getAttributeName(i, &len));
    std::string key = String8(key16).c_str();
    if (key != name) {
      continue;
    }

    Res_value res_value{};
    if (parser_.getAttributeValue(i, &res_value) == BAD_TYPE) {
      return Error(R"(Bad value for attribute "%s")", name.c_str());
    }

    return res_value;
  }

  return Error(R"(Failed to find attribute "%s")", name.c_str());
}

Result<std::unique_ptr<const XmlParser>> XmlParser::Create(const void* data, size_t size,
                                                           bool copy_data) {
  auto parser = std::unique_ptr<const XmlParser>(new XmlParser());
  if (parser->tree_.setTo(data, size, copy_data) != NO_ERROR) {
    return Error("Malformed xml block");
  }

  // Find the beginning of the first tag.
  XmlParser::Event event;
  while ((event = parser->tree_.next()) != XmlParser::Event::BAD_DOCUMENT &&
         event != XmlParser::Event::END_DOCUMENT && event != XmlParser::Event::START_TAG) {
  }

  if (event == XmlParser::Event::END_DOCUMENT) {
    return Error("Root tag was not be found");
  }

  if (event == XmlParser::Event::BAD_DOCUMENT) {
    return Error("Bad xml document");
  }

  return parser;
}

XmlParser::~XmlParser() {
  tree_.uninit();
}

}  // namespace android::idmap2
