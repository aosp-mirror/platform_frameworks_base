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
#include "ResourceTable.h"
#include "ValueVisitor.h"
#include "androidfw/Source.h"
#include "androidfw/Streams.h"
#include "androidfw/StringPiece.h"
#include "process/IResourceTableConsumer.h"
#include "xml/XmlDom.h"

namespace aapt {
namespace proguard {

struct UsageLocation {
  ResourceName name;
  android::Source source;
};

struct NameAndSignature {
  std::string name;
  std::string signature;
};

class KeepSet {
 public:
  KeepSet() = default;

  explicit KeepSet(bool conditional_keep_rules) : conditional_keep_rules_(conditional_keep_rules) {
  }

  inline void AddManifestClass(const UsageLocation& file, const std::string& class_name) {
    manifest_class_set_[class_name].insert(file);
  }

  inline void AddConditionalClass(const UsageLocation& file,
                                  const NameAndSignature& class_and_signature) {
    conditional_class_set_[class_and_signature].insert(file);
  }

  inline void AddMethod(const UsageLocation& file, const NameAndSignature& name_and_signature) {
    method_set_[name_and_signature].insert(file);
  }

  inline void AddReference(const UsageLocation& file, const ResourceName& resource_name) {
    reference_set_[resource_name].insert(file);
  }

 private:
  friend void WriteKeepSet(const KeepSet& keep_set, android::OutputStream* out, bool minimal_keep,
                           bool no_location_reference);

  friend bool CollectLocations(const UsageLocation& location, const KeepSet& keep_set,
                               std::set<UsageLocation>* locations);

  bool conditional_keep_rules_ = false;
  std::map<std::string, std::set<UsageLocation>> manifest_class_set_;
  std::map<NameAndSignature, std::set<UsageLocation>> method_set_;
  std::map<NameAndSignature, std::set<UsageLocation>> conditional_class_set_;
  std::map<ResourceName, std::set<UsageLocation>> reference_set_;
};

bool CollectProguardRulesForManifest(xml::XmlResource* res, KeepSet* keep_set,
                                     bool main_dex_only = false);

bool CollectProguardRules(IAaptContext* context, xml::XmlResource* res, KeepSet* keep_set);

bool CollectResourceReferences(IAaptContext* context, ResourceTable* table, KeepSet* keep_set);

void WriteKeepSet(const KeepSet& keep_set, android::OutputStream* out, bool minimal_keep,
                  bool no_location_reference);

bool CollectLocations(const UsageLocation& location, const KeepSet& keep_set,
                      std::set<UsageLocation>* locations);

//
// UsageLocation implementation.
//

inline bool operator==(const UsageLocation& lhs, const UsageLocation& rhs) {
  // The "source" member is ignored because we only need "name" for outputting
  // keep rules; "source" is used for comments.
  return lhs.name == rhs.name;
}

inline bool operator<(const UsageLocation& lhs, const UsageLocation& rhs) {
  return lhs.name.compare(rhs.name) < 0;
}

//
// NameAndSignature implementation.
//

inline bool operator<(const NameAndSignature& lhs, const NameAndSignature& rhs) {
  if (lhs.name < rhs.name) {
    return true;
  }
  if (lhs.name == rhs.name) {
    return lhs.signature < rhs.signature;
  }
  return false;
}

}  // namespace proguard
}  // namespace aapt

#endif  // AAPT_PROGUARD_RULES_H
