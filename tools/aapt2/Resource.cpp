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
#include "util/StringPiece.h"

#include <map>
#include <string>

namespace aapt {

StringPiece16 toString(ResourceType type) {
    switch (type) {
        case ResourceType::kAnim:          return u"anim";
        case ResourceType::kAnimator:      return u"animator";
        case ResourceType::kArray:         return u"array";
        case ResourceType::kAttr:          return u"attr";
        case ResourceType::kAttrPrivate:   return u"^attr-private";
        case ResourceType::kBool:          return u"bool";
        case ResourceType::kColor:         return u"color";
        case ResourceType::kDimen:         return u"dimen";
        case ResourceType::kDrawable:      return u"drawable";
        case ResourceType::kFraction:      return u"fraction";
        case ResourceType::kId:            return u"id";
        case ResourceType::kInteger:       return u"integer";
        case ResourceType::kInterpolator:  return u"interpolator";
        case ResourceType::kLayout:        return u"layout";
        case ResourceType::kMenu:          return u"menu";
        case ResourceType::kMipmap:        return u"mipmap";
        case ResourceType::kPlurals:       return u"plurals";
        case ResourceType::kRaw:           return u"raw";
        case ResourceType::kString:        return u"string";
        case ResourceType::kStyle:         return u"style";
        case ResourceType::kStyleable:     return u"styleable";
        case ResourceType::kTransition:    return u"transition";
        case ResourceType::kXml:           return u"xml";
    }
    return {};
}

static const std::map<StringPiece16, ResourceType> sResourceTypeMap {
        { u"anim", ResourceType::kAnim },
        { u"animator", ResourceType::kAnimator },
        { u"array", ResourceType::kArray },
        { u"attr", ResourceType::kAttr },
        { u"^attr-private", ResourceType::kAttrPrivate },
        { u"bool", ResourceType::kBool },
        { u"color", ResourceType::kColor },
        { u"dimen", ResourceType::kDimen },
        { u"drawable", ResourceType::kDrawable },
        { u"fraction", ResourceType::kFraction },
        { u"id", ResourceType::kId },
        { u"integer", ResourceType::kInteger },
        { u"interpolator", ResourceType::kInterpolator },
        { u"layout", ResourceType::kLayout },
        { u"menu", ResourceType::kMenu },
        { u"mipmap", ResourceType::kMipmap },
        { u"plurals", ResourceType::kPlurals },
        { u"raw", ResourceType::kRaw },
        { u"string", ResourceType::kString },
        { u"style", ResourceType::kStyle },
        { u"styleable", ResourceType::kStyleable },
        { u"transition", ResourceType::kTransition },
        { u"xml", ResourceType::kXml },
};

const ResourceType* parseResourceType(const StringPiece16& str) {
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

} // namespace aapt
