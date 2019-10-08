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

#include "Resource.h"

#include <map>
#include <sstream>
#include <string>

#include "android-base/stringprintf.h"

using ::android::StringPiece;
using ::android::base::StringPrintf;

namespace aapt {

std::string ResourceId::to_string() const {
  return StringPrintf("0x%08x", id);
}

std::string ResourceName::to_string() const {
  return ResourceNameRef(*this).to_string();
}

std::string ResourceNameRef::to_string() const {
  std::ostringstream str_stream;
  if (!package.empty()) {
    str_stream << package << ":";
  }
  str_stream << type << "/" << entry;
  return str_stream.str();
}

StringPiece to_string(ResourceType type) {
  switch (type) {
    case ResourceType::kAnim:
      return "anim";
    case ResourceType::kAnimator:
      return "animator";
    case ResourceType::kArray:
      return "array";
    case ResourceType::kAttr:
      return "attr";
    case ResourceType::kAttrPrivate:
      return "^attr-private";
    case ResourceType::kBool:
      return "bool";
    case ResourceType::kColor:
      return "color";
    case ResourceType::kConfigVarying:
      return "configVarying";
    case ResourceType::kDimen:
      return "dimen";
    case ResourceType::kDrawable:
      return "drawable";
    case ResourceType::kFont:
      return "font";
    case ResourceType::kFraction:
      return "fraction";
    case ResourceType::kId:
      return "id";
    case ResourceType::kInteger:
      return "integer";
    case ResourceType::kInterpolator:
      return "interpolator";
    case ResourceType::kLayout:
      return "layout";
    case ResourceType::kMenu:
      return "menu";
    case ResourceType::kMipmap:
      return "mipmap";
    case ResourceType::kNavigation:
      return "navigation";
    case ResourceType::kPlurals:
      return "plurals";
    case ResourceType::kRaw:
      return "raw";
    case ResourceType::kString:
      return "string";
    case ResourceType::kStyle:
      return "style";
    case ResourceType::kStyleable:
      return "styleable";
    case ResourceType::kTransition:
      return "transition";
    case ResourceType::kUnknown:
      return "unknown";
    case ResourceType::kXml:
      return "xml";
  }
  return {};
}

static const std::map<StringPiece, ResourceType> sResourceTypeMap{
    {"anim", ResourceType::kAnim},
    {"animator", ResourceType::kAnimator},
    {"array", ResourceType::kArray},
    {"attr", ResourceType::kAttr},
    {"^attr-private", ResourceType::kAttrPrivate},
    {"bool", ResourceType::kBool},
    {"color", ResourceType::kColor},
    {"configVarying", ResourceType::kConfigVarying},
    {"dimen", ResourceType::kDimen},
    {"drawable", ResourceType::kDrawable},
    {"font", ResourceType::kFont},
    {"fraction", ResourceType::kFraction},
    {"id", ResourceType::kId},
    {"integer", ResourceType::kInteger},
    {"interpolator", ResourceType::kInterpolator},
    {"layout", ResourceType::kLayout},
    {"menu", ResourceType::kMenu},
    {"mipmap", ResourceType::kMipmap},
    {"navigation", ResourceType::kNavigation},
    {"plurals", ResourceType::kPlurals},
    {"raw", ResourceType::kRaw},
    {"string", ResourceType::kString},
    {"style", ResourceType::kStyle},
    {"styleable", ResourceType::kStyleable},
    {"transition", ResourceType::kTransition},
    {"xml", ResourceType::kXml},
};

const ResourceType* ParseResourceType(const StringPiece& str) {
  auto iter = sResourceTypeMap.find(str);
  if (iter == std::end(sResourceTypeMap)) {
    return nullptr;
  }
  return &iter->second;
}

bool operator<(const ResourceKey& a, const ResourceKey& b) {
  return std::tie(a.name, a.config) < std::tie(b.name, b.config);
}

bool operator<(const ResourceKeyRef& a, const ResourceKeyRef& b) {
  return std::tie(a.name, a.config) < std::tie(b.name, b.config);
}

}  // namespace aapt
