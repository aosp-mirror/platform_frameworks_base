/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "link/XmlCompatVersioner.h"

#include <algorithm>

#include "util/Util.h"

namespace aapt {

static xml::Attribute CopyAttr(const xml::Attribute& src, StringPool* out_string_pool) {
  xml::Attribute dst{src.namespace_uri, src.name, src.value, src.compiled_attribute};
  if (src.compiled_value != nullptr) {
    dst.compiled_value.reset(src.compiled_value->Clone(out_string_pool));
  }
  return dst;
}

// Returns false if the attribute is not copied because an existing attribute takes precedence
// (came from a rule).
static bool CopyAttribute(const xml::Attribute& src_attr, bool generated, xml::Element* dst_el,
                          StringPool* out_string_pool) {
  xml::Attribute* dst_attr = dst_el->FindAttribute(src_attr.namespace_uri, src_attr.name);
  if (dst_attr != nullptr) {
    if (generated) {
      // Generated attributes always take precedence.
      dst_attr->value = src_attr.value;
      dst_attr->compiled_attribute = src_attr.compiled_attribute;
      if (src_attr.compiled_value != nullptr) {
        dst_attr->compiled_value.reset(src_attr.compiled_value->Clone(out_string_pool));
      }
      return true;
    }
    return false;
  }
  dst_el->attributes.push_back(CopyAttr(src_attr, out_string_pool));
  return true;
}

void XmlCompatVersioner::ProcessRule(const xml::Element& src_el, const xml::Attribute& src_attr,
                                     const ApiVersion& src_attr_version, const IDegradeRule* rule,
                                     const util::Range<ApiVersion>& api_range, bool generated,
                                     xml::Element* dst_el,
                                     std::set<ApiVersion>* out_apis_referenced,
                                     StringPool* out_string_pool) {
  if (src_attr_version <= api_range.start) {
    // The API is compatible, so don't check the rule and just copy.
    if (!CopyAttribute(src_attr, generated, dst_el, out_string_pool)) {
      // TODO(adamlesinski): Log a warning that an attribute was overridden?
    }
    return;
  }

  if (api_range.start >= SDK_LOLLIPOP_MR1) {
    // Since LOLLIPOP MR1, the framework can handle silently ignoring unknown public attributes,
    // so we don't need to erase/version them.
    // Copy.
    if (!CopyAttribute(src_attr, generated, dst_el, out_string_pool)) {
      // TODO(adamlesinski): Log a warning that an attribute was overridden?
    }
  } else {
    // We are going to erase this attribute from this XML resource version, but check if
    // we even need to move it anywhere. A developer may have effectively overwritten it with
    // a similarly versioned XML resource.
    if (src_attr_version < api_range.end) {
      // There is room for another versioned XML resource between this XML resource and the next
      // versioned XML resource defined by the developer.
      out_apis_referenced->insert(std::min<ApiVersion>(src_attr_version, SDK_LOLLIPOP_MR1));
    }
  }

  if (rule != nullptr) {
    for (const DegradeResult& result : rule->Degrade(src_el, src_attr, out_string_pool)) {
      const ResourceId attr_resid = result.attr.compiled_attribute.value().id.value();
      const ApiVersion attr_version = FindAttributeSdkLevel(attr_resid);

      auto iter = rules_->find(attr_resid);
      ProcessRule(src_el, result.attr, attr_version,
                  iter != rules_->end() ? iter->second.get() : nullptr, api_range,
                  true /*generated*/, dst_el, out_apis_referenced, out_string_pool);
    }
  }
}

XmlCompatVersioner::XmlCompatVersioner(const Rules* rules) : rules_(rules) {
}

std::unique_ptr<xml::XmlResource> XmlCompatVersioner::ProcessDoc(
    ApiVersion target_api, ApiVersion max_api, xml::XmlResource* doc,
    std::set<ApiVersion>* out_apis_referenced) {
  const util::Range<ApiVersion> api_range{target_api, max_api};

  std::unique_ptr<xml::XmlResource> cloned_doc = util::make_unique<xml::XmlResource>(doc->file);
  cloned_doc->file.config.sdkVersion = static_cast<uint16_t>(target_api);

  cloned_doc->root = doc->root->CloneElement([&](const xml::Element& el, xml::Element* out_el) {
    for (const auto& attr : el.attributes) {
      if (!attr.compiled_attribute) {
        // Just copy if this isn't a compiled attribute.
        out_el->attributes.push_back(CopyAttr(attr, &cloned_doc->string_pool));
        continue;
      }

      const ResourceId attr_resid = attr.compiled_attribute.value().id.value();
      const ApiVersion attr_version = FindAttributeSdkLevel(attr_resid);

      auto rule = rules_->find(attr_resid);
      ProcessRule(el, attr, attr_version, rule != rules_->end() ? rule->second.get() : nullptr,
                  api_range, false /*generated*/, out_el, out_apis_referenced,
                  &cloned_doc->string_pool);
    }
  });
  return cloned_doc;
}

std::vector<std::unique_ptr<xml::XmlResource>> XmlCompatVersioner::Process(
    IAaptContext* context, xml::XmlResource* doc, util::Range<ApiVersion> api_range) {
  // Adjust the API range so that it falls after this document and after minSdkVersion.
  api_range.start = std::max(api_range.start, context->GetMinSdkVersion());
  api_range.start = std::max(api_range.start, static_cast<ApiVersion>(doc->file.config.sdkVersion));

  std::vector<std::unique_ptr<xml::XmlResource>> versioned_docs;
  std::set<ApiVersion> apis_referenced;
  versioned_docs.push_back(ProcessDoc(api_range.start, api_range.end, doc, &apis_referenced));

  // Adjust the sdkVersion of the first XML document back to its original (this only really
  // makes a difference if the sdk version was below the minSdk to start).
  versioned_docs.back()->file.config.sdkVersion = doc->file.config.sdkVersion;

  // Iterate from smallest to largest API version.
  for (ApiVersion api : apis_referenced) {
    std::set<ApiVersion> dummy;
    versioned_docs.push_back(ProcessDoc(api, api_range.end, doc, &dummy));
  }
  return versioned_docs;
}

DegradeToManyRule::DegradeToManyRule(std::vector<ReplacementAttr> attrs)
    : attrs_(std::move(attrs)) {
}

static inline std::unique_ptr<Item> CloneIfNotNull(const std::unique_ptr<Item>& src,
                                                   StringPool* out_string_pool) {
  if (src == nullptr) {
    return {};
  }
  return std::unique_ptr<Item>(src->Clone(out_string_pool));
}

std::vector<DegradeResult> DegradeToManyRule::Degrade(const xml::Element& src_el,
                                                      const xml::Attribute& src_attr,
                                                      StringPool* out_string_pool) const {
  std::vector<DegradeResult> result;
  result.reserve(attrs_.size());
  for (const ReplacementAttr& attr : attrs_) {
    result.push_back(
        DegradeResult{xml::Attribute{xml::kSchemaAndroid, attr.name, src_attr.value,
                                     xml::AaptAttribute{attr.attr, attr.id},
                                     CloneIfNotNull(src_attr.compiled_value, out_string_pool)},
                      FindAttributeSdkLevel(attr.id)});
  }
  return result;
}

}  // namespace aapt
