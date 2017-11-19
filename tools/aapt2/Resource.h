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

#ifndef AAPT_RESOURCE_H
#define AAPT_RESOURCE_H

#include <iomanip>
#include <limits>
#include <sstream>
#include <string>
#include <tuple>
#include <vector>

#include "androidfw/StringPiece.h"
#include "utils/JenkinsHash.h"

#include "ConfigDescription.h"
#include "Source.h"

namespace aapt {

/**
 * The various types of resource types available. Corresponds
 * to the 'type' in package:type/entry.
 */
enum class ResourceType {
  kAnim,
  kAnimator,
  kArray,
  kAttr,
  kAttrPrivate,
  kBool,
  kColor,

  // Not really a type, but it shows up in some CTS tests and
  // we need to continue respecting it.
  kConfigVarying,

  kDimen,
  kDrawable,
  kFont,
  kFraction,
  kId,
  kInteger,
  kInterpolator,
  kLayout,
  kMenu,
  kMipmap,
  kNavigation,
  kPlurals,
  kRaw,
  kString,
  kStyle,
  kStyleable,
  kTransition,
  kXml,
};

android::StringPiece to_string(ResourceType type);

/**
 * Returns a pointer to a valid ResourceType, or nullptr if the string was invalid.
 */
const ResourceType* ParseResourceType(const android::StringPiece& str);

/**
 * A resource's name. This can uniquely identify
 * a resource in the ResourceTable.
 */
struct ResourceName {
  std::string package;
  ResourceType type = ResourceType::kRaw;
  std::string entry;

  ResourceName() = default;
  ResourceName(const android::StringPiece& p, ResourceType t, const android::StringPiece& e);

  int compare(const ResourceName& other) const;

  bool is_valid() const;
  std::string to_string() const;
};

/**
 * Same as ResourceName, but uses StringPieces instead.
 * Use this if you need to avoid copying and know that
 * the lifetime of this object is shorter than that
 * of the original string.
 */
struct ResourceNameRef {
  android::StringPiece package;
  ResourceType type = ResourceType::kRaw;
  android::StringPiece entry;

  ResourceNameRef() = default;
  ResourceNameRef(const ResourceNameRef&) = default;
  ResourceNameRef(ResourceNameRef&&) = default;
  ResourceNameRef(const ResourceName& rhs);  // NOLINT(implicit)
  ResourceNameRef(const android::StringPiece& p, ResourceType t, const android::StringPiece& e);
  ResourceNameRef& operator=(const ResourceNameRef& rhs) = default;
  ResourceNameRef& operator=(ResourceNameRef&& rhs) = default;
  ResourceNameRef& operator=(const ResourceName& rhs);

  bool is_valid() const;

  ResourceName ToResourceName() const;
  std::string to_string() const;
};

constexpr const uint8_t kAppPackageId = 0x7fu;
constexpr const uint8_t kFrameworkPackageId = 0x01u;

/**
 * A binary identifier representing a resource. Internally it
 * is a 32bit integer split as follows:
 *
 * 0xPPTTEEEE
 *
 * PP: 8 bit package identifier. 0x01 is reserved for system
 *     and 0x7f is reserved for the running app.
 * TT: 8 bit type identifier. 0x00 is invalid.
 * EEEE: 16 bit entry identifier.
 */
struct ResourceId {
  uint32_t id;

  ResourceId();
  ResourceId(const ResourceId& rhs);
  ResourceId(uint32_t res_id);  // NOLINT(implicit)
  ResourceId(uint8_t p, uint8_t t, uint16_t e);

  bool is_valid() const;

  // Returns true if the ID is a valid ID or dynamic ID (package ID can be 0).
  bool is_valid_dynamic() const;

  uint8_t package_id() const;
  uint8_t type_id() const;
  uint16_t entry_id() const;

  std::string to_string() const;
};

struct SourcedResourceName {
  ResourceName name;
  size_t line;
};

struct ResourceFile {
  enum class Type {
    kUnknown,
    kPng,
    kBinaryXml,
    kProtoXml,
  };

  // Name
  ResourceName name;

  // Configuration
  ConfigDescription config;

  // Type
  Type type;

  // Source
  Source source;

  // Exported symbols
  std::vector<SourcedResourceName> exported_symbols;
};

/**
 * Useful struct used as a key to represent a unique resource in associative
 * containers.
 */
struct ResourceKey {
  ResourceName name;
  ConfigDescription config;
};

bool operator<(const ResourceKey& a, const ResourceKey& b);

/**
 * Useful struct used as a key to represent a unique resource in associative
 * containers.
 * Holds a reference to the name, so that name better live longer than this key!
 */
struct ResourceKeyRef {
  ResourceNameRef name;
  ConfigDescription config;

  ResourceKeyRef() = default;
  ResourceKeyRef(const ResourceNameRef& n, const ConfigDescription& c)
      : name(n), config(c) {}

  /**
   * Prevent taking a reference to a temporary. This is bad.
   */
  ResourceKeyRef(ResourceName&& n, const ConfigDescription& c) = delete;
};

bool operator<(const ResourceKeyRef& a, const ResourceKeyRef& b);

//
// ResourceId implementation.
//

inline ResourceId::ResourceId() : id(0) {}

inline ResourceId::ResourceId(const ResourceId& rhs) : id(rhs.id) {}

inline ResourceId::ResourceId(uint32_t res_id) : id(res_id) {}

inline ResourceId::ResourceId(uint8_t p, uint8_t t, uint16_t e)
    : id((p << 24) | (t << 16) | e) {}

inline bool ResourceId::is_valid() const {
  return (id & 0xff000000u) != 0 && (id & 0x00ff0000u) != 0;
}

inline bool ResourceId::is_valid_dynamic() const {
  return (id & 0x00ff0000u) != 0;
}

inline uint8_t ResourceId::package_id() const {
  return static_cast<uint8_t>(id >> 24);
}

inline uint8_t ResourceId::type_id() const {
  return static_cast<uint8_t>(id >> 16);
}

inline uint16_t ResourceId::entry_id() const {
  return static_cast<uint16_t>(id);
}

inline bool operator<(const ResourceId& lhs, const ResourceId& rhs) {
  return lhs.id < rhs.id;
}

inline bool operator>(const ResourceId& lhs, const ResourceId& rhs) {
  return lhs.id > rhs.id;
}

inline bool operator==(const ResourceId& lhs, const ResourceId& rhs) {
  return lhs.id == rhs.id;
}

inline bool operator!=(const ResourceId& lhs, const ResourceId& rhs) {
  return lhs.id != rhs.id;
}

inline ::std::ostream& operator<<(::std::ostream& out, const ResourceId& res_id) {
  return out << res_id.to_string();
}

// For generic code to call 'using std::to_string; to_string(T);'.
inline std::string to_string(const ResourceId& id) {
  return id.to_string();
}

//
// ResourceType implementation.
//

inline ::std::ostream& operator<<(::std::ostream& out, const ResourceType& val) {
  return out << to_string(val);
}

//
// ResourceName implementation.
//

inline ResourceName::ResourceName(const android::StringPiece& p, ResourceType t,
                                  const android::StringPiece& e)
    : package(p.to_string()), type(t), entry(e.to_string()) {}

inline int ResourceName::compare(const ResourceName& other) const {
  int cmp = package.compare(other.package);
  if (cmp != 0) return cmp;
  cmp = static_cast<int>(type) - static_cast<int>(other.type);
  if (cmp != 0) return cmp;
  cmp = entry.compare(other.entry);
  return cmp;
}

inline bool ResourceName::is_valid() const {
  return !package.empty() && !entry.empty();
}

inline bool operator<(const ResourceName& lhs, const ResourceName& rhs) {
  return std::tie(lhs.package, lhs.type, lhs.entry) <
         std::tie(rhs.package, rhs.type, rhs.entry);
}

inline bool operator==(const ResourceName& lhs, const ResourceName& rhs) {
  return std::tie(lhs.package, lhs.type, lhs.entry) ==
         std::tie(rhs.package, rhs.type, rhs.entry);
}

inline bool operator!=(const ResourceName& lhs, const ResourceName& rhs) {
  return std::tie(lhs.package, lhs.type, lhs.entry) !=
         std::tie(rhs.package, rhs.type, rhs.entry);
}

inline ::std::ostream& operator<<(::std::ostream& out, const ResourceName& name) {
  return out << name.to_string();
}

//
// ResourceNameRef implementation.
//

inline ResourceNameRef::ResourceNameRef(const ResourceName& rhs)
    : package(rhs.package), type(rhs.type), entry(rhs.entry) {}

inline ResourceNameRef::ResourceNameRef(const android::StringPiece& p, ResourceType t,
                                        const android::StringPiece& e)
    : package(p), type(t), entry(e) {}

inline ResourceNameRef& ResourceNameRef::operator=(const ResourceName& rhs) {
  package = rhs.package;
  type = rhs.type;
  entry = rhs.entry;
  return *this;
}

inline ResourceName ResourceNameRef::ToResourceName() const {
  return ResourceName(package, type, entry);
}

inline bool ResourceNameRef::is_valid() const {
  return !package.empty() && !entry.empty();
}

inline bool operator<(const ResourceNameRef& lhs, const ResourceNameRef& rhs) {
  return std::tie(lhs.package, lhs.type, lhs.entry) <
         std::tie(rhs.package, rhs.type, rhs.entry);
}

inline bool operator==(const ResourceNameRef& lhs, const ResourceNameRef& rhs) {
  return std::tie(lhs.package, lhs.type, lhs.entry) ==
         std::tie(rhs.package, rhs.type, rhs.entry);
}

inline bool operator!=(const ResourceNameRef& lhs, const ResourceNameRef& rhs) {
  return std::tie(lhs.package, lhs.type, lhs.entry) !=
         std::tie(rhs.package, rhs.type, rhs.entry);
}

inline ::std::ostream& operator<<(::std::ostream& out, const ResourceNameRef& name) {
  return out << name.to_string();
}

inline bool operator<(const ResourceName& lhs, const ResourceNameRef& b) {
  return ResourceNameRef(lhs) < b;
}

inline bool operator!=(const ResourceName& lhs, const ResourceNameRef& rhs) {
  return ResourceNameRef(lhs) != rhs;
}

inline bool operator==(const SourcedResourceName& lhs, const SourcedResourceName& rhs) {
  return lhs.name == rhs.name && lhs.line == rhs.line;
}

}  // namespace aapt

namespace std {

template <>
struct hash<aapt::ResourceName> {
  size_t operator()(const aapt::ResourceName& name) const {
    android::hash_t h = 0;
    h = android::JenkinsHashMix(h, static_cast<uint32_t>(hash<string>()(name.package)));
    h = android::JenkinsHashMix(h, static_cast<uint32_t>(name.type));
    h = android::JenkinsHashMix(h, static_cast<uint32_t>(hash<string>()(name.entry)));
    return static_cast<size_t>(h);
  }
};

template <>
struct hash<aapt::ResourceId> {
  size_t operator()(const aapt::ResourceId& id) const {
    return id.id;
  }
};

}  // namespace std

#endif  // AAPT_RESOURCE_H
