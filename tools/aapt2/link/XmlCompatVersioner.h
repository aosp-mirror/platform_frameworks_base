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

#ifndef AAPT_LINKER_XMLCOMPATVERSIONER_H
#define AAPT_LINKER_XMLCOMPATVERSIONER_H

#include <set>
#include <unordered_map>
#include <vector>

#include "android-base/macros.h"

#include "Resource.h"
#include "SdkConstants.h"
#include "process/IResourceTableConsumer.h"
#include "util/Util.h"
#include "xml/XmlDom.h"

namespace aapt {

class IDegradeRule;

struct DegradeResult {
  xml::Attribute attr;
  ApiVersion attr_api_version;
};

class IDegradeRule {
 public:
  IDegradeRule() = default;
  virtual ~IDegradeRule() = default;

  virtual std::vector<DegradeResult> Degrade(const xml::Element& src_el,
                                             const xml::Attribute& src_attr,
                                             StringPool* out_string_pool) const = 0;

 private:
  DISALLOW_COPY_AND_ASSIGN(IDegradeRule);
};

class XmlCompatVersioner {
 public:
  using Rules = std::unordered_map<ResourceId, std::unique_ptr<IDegradeRule>>;

  XmlCompatVersioner(const Rules* rules);

  std::vector<std::unique_ptr<xml::XmlResource>> Process(IAaptContext* context,
                                                         xml::XmlResource* doc,
                                                         util::Range<ApiVersion> api_range);

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlCompatVersioner);

  std::unique_ptr<xml::XmlResource> ProcessDoc(ApiVersion target_api, ApiVersion max_api,
                                               xml::XmlResource* doc,
                                               std::set<ApiVersion>* out_apis_referenced);
  void ProcessRule(const xml::Element& src_el, const xml::Attribute& src_attr,
                   const ApiVersion& src_attr_version, const IDegradeRule* rule,
                   const util::Range<ApiVersion>& api_range, bool generated, xml::Element* dst_el,
                   std::set<ApiVersion>* out_apis_referenced, StringPool* out_string_pool);

  const Rules* rules_;
};

struct ReplacementAttr {
  std::string name;
  ResourceId id;
  Attribute attr;
};

class DegradeToManyRule : public IDegradeRule {
 public:
  DegradeToManyRule(std::vector<ReplacementAttr> attrs);
  virtual ~DegradeToManyRule() = default;

  std::vector<DegradeResult> Degrade(const xml::Element& src_el, const xml::Attribute& src_attr,
                                     StringPool* out_string_pool) const override;

 private:
  DISALLOW_COPY_AND_ASSIGN(DegradeToManyRule);

  std::vector<ReplacementAttr> attrs_;
};

}  // namespace aapt

#endif  // AAPT_LINKER_XMLCOMPATVERSIONER_H
