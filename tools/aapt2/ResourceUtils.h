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

#ifndef AAPT_RESOURCEUTILS_H
#define AAPT_RESOURCEUTILS_H

#include "NameMangler.h"
#include "Resource.h"
#include "ResourceValues.h"
#include "util/StringPiece.h"

#include <functional>
#include <memory>

namespace aapt {
namespace ResourceUtils {

/*
 * Extracts the package, type, and name from a string of the format:
 *
 *      [package:]type/name
 *
 * where the package can be empty. Validation must be performed on each
 * individual extracted piece to verify that the pieces are valid.
 * Returns false if there was no package but a ':' was present.
 */
bool extractResourceName(const StringPiece& str, StringPiece* outPackage,
                         StringPiece* outType, StringPiece* outEntry);

/**
 * Returns true if the string was parsed as a resource name ([*][package:]type/name), with
 * `outResource` set to the parsed resource name and `outPrivate` set to true if a '*' prefix
 * was present.
 */
bool parseResourceName(const StringPiece& str, ResourceNameRef* outResource,
                       bool* outPrivate = nullptr);

/*
 * Returns true if the string was parsed as a reference (@[+][package:]type/name), with
 * `outReference` set to the parsed reference.
 *
 * If '+' was present in the reference, `outCreate` is set to true.
 * If '*' was present in the reference, `outPrivate` is set to true.
 */
bool parseReference(const StringPiece& str, ResourceNameRef* outReference,
                    bool* outCreate = nullptr, bool* outPrivate = nullptr);

/*
 * Returns true if the string is in the form of a resource reference (@[+][package:]type/name).
 */
bool isReference(const StringPiece& str);

/*
 * Returns true if the string was parsed as an attribute reference (?[package:][type/]name),
 * with `outReference` set to the parsed reference.
 */
bool parseAttributeReference(const StringPiece& str, ResourceNameRef* outReference);

/**
 * Returns true if the string is in the form of an attribute reference(?[package:][type/]name).
 */
bool isAttributeReference(const StringPiece& str);

/**
 * Convert an android::ResTable::resource_name to an aapt::ResourceName struct.
 */
Maybe<ResourceName> toResourceName(const android::ResTable::resource_name& name);

/**
 * Returns a boolean value if the string is equal to TRUE, true, True, FALSE, false, or False.
 */
Maybe<bool> parseBool(const StringPiece& str);

/**
 * Returns a uint32_t if the string is an integer.
 */
Maybe<uint32_t> parseInt(const StringPiece& str);

/**
 * Returns an ID if it the string represented a valid ID.
 */
Maybe<ResourceId> parseResourceId(const StringPiece& str);

/**
 * Parses an SDK version, which can be an integer, or a letter from A-Z.
 */
Maybe<int> parseSdkVersion(const StringPiece& str);

/*
 * Returns a Reference, or None Maybe instance if the string `str` was parsed as a
 * valid reference to a style.
 * The format for a style parent is slightly more flexible than a normal reference:
 *
 * @[package:]style/<entry> or
 * ?[package:]style/<entry> or
 * <package>:[style/]<entry>
 */
Maybe<Reference> parseStyleParentReference(const StringPiece& str, std::string* outError);

/*
 * Returns a Reference object if the string was parsed as a resource or attribute reference,
 * ( @[+][package:]type/name | ?[package:]type/name ) setting outCreate to true if
 * the '+' was present in the string.
 */
std::unique_ptr<Reference> tryParseReference(const StringPiece& str, bool* outCreate = nullptr);

/*
 * Returns a BinaryPrimitve object representing @null or @empty if the string was parsed
 * as one.
 */
std::unique_ptr<BinaryPrimitive> tryParseNullOrEmpty(const StringPiece& str);

/*
 * Returns a BinaryPrimitve object representing a color if the string was parsed
 * as one.
 */
std::unique_ptr<BinaryPrimitive> tryParseColor(const StringPiece& str);

/*
 * Returns a BinaryPrimitve object representing a boolean if the string was parsed
 * as one.
 */
std::unique_ptr<BinaryPrimitive> tryParseBool(const StringPiece& str);

/*
 * Returns a BinaryPrimitve object representing an integer if the string was parsed
 * as one.
 */
std::unique_ptr<BinaryPrimitive> tryParseInt(const StringPiece& str);

/*
 * Returns a BinaryPrimitve object representing a floating point number
 * (float, dimension, etc) if the string was parsed as one.
 */
std::unique_ptr<BinaryPrimitive> tryParseFloat(const StringPiece& str);

/*
 * Returns a BinaryPrimitve object representing an enum symbol if the string was parsed
 * as one.
 */
std::unique_ptr<BinaryPrimitive> tryParseEnumSymbol(const Attribute* enumAttr,
                                                    const StringPiece& str);

/*
 * Returns a BinaryPrimitve object representing a flag symbol if the string was parsed
 * as one.
 */
std::unique_ptr<BinaryPrimitive> tryParseFlagSymbol(const Attribute* enumAttr,
                                                    const StringPiece& str);
/*
 * Try to convert a string to an Item for the given attribute. The attribute will
 * restrict what values the string can be converted to.
 * The callback function onCreateReference is called when the parsed item is a
 * reference to an ID that must be created (@+id/foo).
 */
std::unique_ptr<Item> tryParseItemForAttribute(
        const StringPiece& value, const Attribute* attr,
        std::function<void(const ResourceName&)> onCreateReference = {});

std::unique_ptr<Item> tryParseItemForAttribute(
        const StringPiece& value, uint32_t typeMask,
        std::function<void(const ResourceName&)> onCreateReference = {});

uint32_t androidTypeToAttributeTypeMask(uint16_t type);

/**
 * Returns a string path suitable for use within an APK. The path will look like:
 *
 * res/type[-config]/<name>.<ext>
 *
 * Then name may be mangled if a NameMangler is supplied (can be nullptr) and the package
 * requires mangling.
 */
std::string buildResourceFileName(const ResourceFile& resFile, const NameMangler* mangler);

} // namespace ResourceUtils
} // namespace aapt

#endif /* AAPT_RESOURCEUTILS_H */
