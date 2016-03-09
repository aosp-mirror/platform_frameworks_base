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

#include "ConfigDescription.h"
#include "Source.h"

#include "util/StringPiece.h"

#include <iomanip>
#include <limits>
#include <string>
#include <tuple>
#include <vector>

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
    kDimen,
    kDrawable,
    kFraction,
    kId,
    kInteger,
    kInterpolator,
    kLayout,
    kMenu,
    kMipmap,
    kPlurals,
    kRaw,
    kString,
    kStyle,
    kStyleable,
    kTransition,
    kXml,
};

StringPiece16 toString(ResourceType type);

/**
 * Returns a pointer to a valid ResourceType, or nullptr if
 * the string was invalid.
 */
const ResourceType* parseResourceType(const StringPiece16& str);

/**
 * A resource's name. This can uniquely identify
 * a resource in the ResourceTable.
 */
struct ResourceName {
    std::u16string package;
    ResourceType type;
    std::u16string entry;

    ResourceName() : type(ResourceType::kRaw) {}
    ResourceName(const StringPiece16& p, ResourceType t, const StringPiece16& e);

    bool isValid() const;
    std::u16string toString() const;
};

/**
 * Same as ResourceName, but uses StringPieces instead.
 * Use this if you need to avoid copying and know that
 * the lifetime of this object is shorter than that
 * of the original string.
 */
struct ResourceNameRef {
    StringPiece16 package;
    ResourceType type;
    StringPiece16 entry;

    ResourceNameRef() = default;
    ResourceNameRef(const ResourceNameRef&) = default;
    ResourceNameRef(ResourceNameRef&&) = default;
    ResourceNameRef(const ResourceName& rhs);
    ResourceNameRef(const StringPiece16& p, ResourceType t, const StringPiece16& e);
    ResourceNameRef& operator=(const ResourceNameRef& rhs) = default;
    ResourceNameRef& operator=(ResourceNameRef&& rhs) = default;
    ResourceNameRef& operator=(const ResourceName& rhs);

    ResourceName toResourceName() const;
    bool isValid() const;
};

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
    ResourceId(uint32_t resId);
    ResourceId(uint8_t p, uint8_t t, uint16_t e);

    bool isValid() const;
    uint8_t packageId() const;
    uint8_t typeId() const;
    uint16_t entryId() const;
};

struct SourcedResourceName {
    ResourceName name;
    size_t line;
};

struct ResourceFile {
    // Name
    ResourceName name;

    // Configuration
    ConfigDescription config;

    // Source
    Source source;

    // Exported symbols
    std::vector<SourcedResourceName> exportedSymbols;
};

/**
 * Useful struct used as a key to represent a unique resource in associative containers.
 */
struct ResourceKey {
    ResourceName name;
    ConfigDescription config;
};

bool operator<(const ResourceKey& a, const ResourceKey& b);

/**
 * Useful struct used as a key to represent a unique resource in associative containers.
 * Holds a reference to the name, so that name better live longer than this key!
 */
struct ResourceKeyRef {
    ResourceNameRef name;
    ConfigDescription config;

    ResourceKeyRef() = default;
    ResourceKeyRef(const ResourceNameRef& n, const ConfigDescription& c) : name(n), config(c) {
    }

    /**
     * Prevent taking a reference to a temporary. This is bad.
     */
    ResourceKeyRef(ResourceName&& n, const ConfigDescription& c) = delete;
};

bool operator<(const ResourceKeyRef& a, const ResourceKeyRef& b);

//
// ResourceId implementation.
//

inline ResourceId::ResourceId() : id(0) {
}

inline ResourceId::ResourceId(const ResourceId& rhs) : id(rhs.id) {
}

inline ResourceId::ResourceId(uint32_t resId) : id(resId) {
}

inline ResourceId::ResourceId(uint8_t p, uint8_t t, uint16_t e) : id((p << 24) | (t << 16) | e) {
}

inline bool ResourceId::isValid() const {
    return (id & 0xff000000u) != 0 && (id & 0x00ff0000u) != 0;
}

inline uint8_t ResourceId::packageId() const {
    return static_cast<uint8_t>(id >> 24);
}

inline uint8_t ResourceId::typeId() const {
    return static_cast<uint8_t>(id >> 16);
}

inline uint16_t ResourceId::entryId() const {
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

inline ::std::ostream& operator<<(::std::ostream& out, const ResourceId& resId) {
    std::ios_base::fmtflags oldFlags = out.flags();
    char oldFill = out.fill();
    out << "0x" << std::internal << std::setfill('0') << std::setw(8)
        << std::hex << resId.id;
    out.flags(oldFlags);
    out.fill(oldFill);
    return out;
}

//
// ResourceType implementation.
//

inline ::std::ostream& operator<<(::std::ostream& out, const ResourceType& val) {
    return out << toString(val);
}

//
// ResourceName implementation.
//

inline ResourceName::ResourceName(const StringPiece16& p, ResourceType t, const StringPiece16& e) :
        package(p.toString()), type(t), entry(e.toString()) {
}

inline bool ResourceName::isValid() const {
    return !package.empty() && !entry.empty();
}

inline bool operator<(const ResourceName& lhs, const ResourceName& rhs) {
    return std::tie(lhs.package, lhs.type, lhs.entry)
            < std::tie(rhs.package, rhs.type, rhs.entry);
}

inline bool operator==(const ResourceName& lhs, const ResourceName& rhs) {
    return std::tie(lhs.package, lhs.type, lhs.entry)
            == std::tie(rhs.package, rhs.type, rhs.entry);
}

inline bool operator!=(const ResourceName& lhs, const ResourceName& rhs) {
    return std::tie(lhs.package, lhs.type, lhs.entry)
            != std::tie(rhs.package, rhs.type, rhs.entry);
}

inline std::u16string ResourceName::toString() const {
    std::u16string result;
    if (!package.empty()) {
        result = package + u":";
    }
    return result + aapt::toString(type).toString() + u"/" + entry;
}

inline ::std::ostream& operator<<(::std::ostream& out, const ResourceName& name) {
    if (!name.package.empty()) {
        out << name.package << ":";
    }
    return out << name.type << "/" << name.entry;
}


//
// ResourceNameRef implementation.
//

inline ResourceNameRef::ResourceNameRef(const ResourceName& rhs) :
        package(rhs.package), type(rhs.type), entry(rhs.entry) {
}

inline ResourceNameRef::ResourceNameRef(const StringPiece16& p, ResourceType t,
                                        const StringPiece16& e) :
        package(p), type(t), entry(e) {
}

inline ResourceNameRef& ResourceNameRef::operator=(const ResourceName& rhs) {
    package = rhs.package;
    type = rhs.type;
    entry = rhs.entry;
    return *this;
}

inline ResourceName ResourceNameRef::toResourceName() const {
    return { package.toString(), type, entry.toString() };
}

inline bool ResourceNameRef::isValid() const {
    return !package.empty() && !entry.empty();
}

inline bool operator<(const ResourceNameRef& lhs, const ResourceNameRef& rhs) {
    return std::tie(lhs.package, lhs.type, lhs.entry)
            < std::tie(rhs.package, rhs.type, rhs.entry);
}

inline bool operator==(const ResourceNameRef& lhs, const ResourceNameRef& rhs) {
    return std::tie(lhs.package, lhs.type, lhs.entry)
            == std::tie(rhs.package, rhs.type, rhs.entry);
}

inline bool operator!=(const ResourceNameRef& lhs, const ResourceNameRef& rhs) {
    return std::tie(lhs.package, lhs.type, lhs.entry)
            != std::tie(rhs.package, rhs.type, rhs.entry);
}

inline ::std::ostream& operator<<(::std::ostream& out, const ResourceNameRef& name) {
    if (!name.package.empty()) {
        out << name.package << ":";
    }
    return out << name.type << "/" << name.entry;
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

} // namespace aapt

#endif // AAPT_RESOURCE_H
