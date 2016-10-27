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

#ifndef AAPT_PROGUARD_RULES_H
#define AAPT_PROGUARD_RULES_H

#include <map>
#include <ostream>
#include <set>
#include <string>

#include "Resource.h"
#include "Source.h"
#include "xml/XmlDom.h"

namespace aapt {
namespace proguard {

class KeepSet {
 public:
  inline void AddClass(const Source& source, const std::string& class_name) {
    keep_set_[class_name].insert(source);
  }

  inline void AddMethod(const Source& source, const std::string& method_name) {
    keep_method_set_[method_name].insert(source);
  }

 private:
  friend bool WriteKeepSet(std::ostream* out, const KeepSet& keep_set);

  std::map<std::string, std::set<Source>> keep_set_;
  std::map<std::string, std::set<Source>> keep_method_set_;
};

bool CollectProguardRulesForManifest(const Source& source,
                                     xml::XmlResource* res, KeepSet* keep_set,
                                     bool main_dex_only = false);
bool CollectProguardRules(const Source& source, xml::XmlResource* res,
                          KeepSet* keep_set);

bool WriteKeepSet(std::ostream* out, const KeepSet& keep_set);

}  // namespace proguard
}  // namespace aapt

#endif  // AAPT_PROGUARD_RULES_H
