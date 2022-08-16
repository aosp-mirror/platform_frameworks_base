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

#include <functional>
#include <memory>

#include "androidfw/AssetManager2.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"

#include "NameMangler.h"
#include "Resource.h"
#include "ResourceValues.h"
#include "StringPool.h"

namespace aapt {
namespace ResourceUtils {

/**
 * Returns true if the string was parsed as a resource name
 * ([*][package:]type/name), with
 * `out_resource` set to the parsed resource name and `out_private` set to true
 * if a '*' prefix was present.
 */
bool ParseResourceName(const android::StringPiece& str, ResourceNameRef* out_resource,
                       bool* out_private = nullptr);

/*
 * Returns true if the string was parsed as a reference
 * (@[+][package:]type/name), with
 * `out_reference` set to the parsed reference.
 *
 * If '+' was present in the reference, `out_create` is set to true.
 * If '*' was present in the reference, `out_private` is set to true.
 */
bool ParseReference(const android::StringPiece& str, ResourceNameRef* out_reference,
                    bool* out_create = nullptr, bool* out_private = nullptr);

/*
 * Returns true if the string is in the form of a resource reference
 * (@[+][package:]type/name).
 */
bool IsReference(const android::StringPiece& str);

/*
 * Returns true if the string was parsed as an attribute reference
 * (?[package:][type/]name),
 * with `out_reference` set to the parsed reference.
 */
bool ParseAttributeReference(const android::StringPiece& str, ResourceNameRef* out_reference);

/**
 * Returns true if the string is in the form of an attribute
 * reference(?[package:][type/]name).
 */
bool IsAttributeReference(const android::StringPiece& str);

/**
 * Convert an android::ResTable::resource_name to an aapt::ResourceName struct.
 */
std::optional<ResourceName> ToResourceName(const android::ResTable::resource_name& name);

/**
 * Convert an android::AssetManager2::ResourceName to an aapt::ResourceName struct.
 */
std::optional<ResourceName> ToResourceName(const android::AssetManager2::ResourceName& name_in);

/**
 * Returns a boolean value if the string is equal to TRUE, true, True, FALSE,
 * false, or False.
 */
std::optional<bool> ParseBool(const android::StringPiece& str);

/**
 * Returns a uint32_t if the string is an integer.
 */
std::optional<uint32_t> ParseInt(const android::StringPiece& str);

/**
 * Returns an ID if it the string represented a valid ID.
 */
std::optional<ResourceId> ParseResourceId(const android::StringPiece& str);

/**
 * Parses an SDK version, which can be an integer, or a letter from A-Z.
 */
std::optional<int> ParseSdkVersion(const android::StringPiece& str);

/*
 * Returns a Reference, or None Maybe instance if the string `str` was parsed as
 * a
 * valid reference to a style.
 * The format for a style parent is slightly more flexible than a normal
 * reference:
 *
 * @[package:]style/<entry> or
 * ?[package:]style/<entry> or
 * <package>:[style/]<entry>
 */
std::optional<Reference> ParseStyleParentReference(const android::StringPiece& str,
                                                   std::string* out_error);

/*
 * Returns a Reference if the string `str` was parsed as a valid XML attribute
 * name.
 * The valid format for an XML attribute name is:
 *
 * package:entry
 */
std::optional<Reference> ParseXmlAttributeName(const android::StringPiece& str);

/*
 * Returns a Reference object if the string was parsed as a resource or
 * attribute reference,
 * ( @[+][package:]type/name | ?[package:]type/name ) setting outCreate to true
 * if
 * the '+' was present in the string.
 */
std::unique_ptr<Reference> TryParseReference(const android::StringPiece& str,
                                             bool* out_create = nullptr);

/*
 * Returns a BinaryPrimitve object representing @null or @empty if the string
 * was parsed as one.
 */
std::unique_ptr<Item> TryParseNullOrEmpty(const android::StringPiece& str);

// Returns a Reference representing @null.
// Due to runtime compatibility issues, this is encoded as a reference with ID 0.
// The runtime will convert this to TYPE_NULL.
std::unique_ptr<Reference> MakeNull();

// Returns a BinaryPrimitive representing @empty. This is encoded as a Res_value with
// type Res_value::TYPE_NULL and data Res_value::DATA_NULL_EMPTY.
std::unique_ptr<BinaryPrimitive> MakeEmpty();

/*
 * Returns a BinaryPrimitve object representing a color if the string was parsed
 * as one.
 */
std::unique_ptr<BinaryPrimitive> TryParseColor(const android::StringPiece& str);

/*
 * Returns a BinaryPrimitve object representing a boolean if the string was
 * parsed as one.
 */
std::unique_ptr<BinaryPrimitive> TryParseBool(const android::StringPiece& str);

// Returns a boolean BinaryPrimitive.
std::unique_ptr<BinaryPrimitive> MakeBool(bool val);

/*
 * Returns a BinaryPrimitve object representing an integer if the string was
 * parsed as one.
 */
std::unique_ptr<BinaryPrimitive> TryParseInt(const android::StringPiece& str);

// Returns an integer BinaryPrimitive.
std::unique_ptr<BinaryPrimitive> MakeInt(uint32_t value);

/*
 * Returns a BinaryPrimitve object representing a floating point number
 * (float, dimension, etc) if the string was parsed as one.
 */
std::unique_ptr<BinaryPrimitive> TryParseFloat(const android::StringPiece& str);

/*
 * Returns a BinaryPrimitve object representing an enum symbol if the string was
 * parsed as one.
 */
std::unique_ptr<BinaryPrimitive> TryParseEnumSymbol(const Attribute* enum_attr,
                                                    const android::StringPiece& str);

/*
 * Returns a BinaryPrimitve object representing a flag symbol if the string was
 * parsed as one.
 */
std::unique_ptr<BinaryPrimitive> TryParseFlagSymbol(const Attribute* enum_attr,
                                                    const android::StringPiece& str);
/*
 * Try to convert a string to an Item for the given attribute. The attribute
 * will
 * restrict what values the string can be converted to.
 * The callback function on_create_reference is called when the parsed item is a
 * reference to an ID that must be created (@+id/foo).
 */
std::unique_ptr<Item> TryParseItemForAttribute(
    const android::StringPiece& value, const Attribute* attr,
    const std::function<bool(const ResourceName&)>& on_create_reference = {});

std::unique_ptr<Item> TryParseItemForAttribute(
    const android::StringPiece& value, uint32_t type_mask,
    const std::function<bool(const ResourceName&)>& on_create_reference = {});

uint32_t AndroidTypeToAttributeTypeMask(uint16_t type);

/**
 * Returns a string path suitable for use within an APK. The path will look
 * like:
 *
 * res/type[-config]/<name>.<ext>
 *
 * Then name may be mangled if a NameMangler is supplied (can be nullptr) and
 * the package
 * requires mangling.
 */
std::string BuildResourceFileName(const ResourceFile& res_file,
                                  const NameMangler* mangler = nullptr);

// Parses the binary form of a resource value. `type` is used as a hint to know when a value is
// an ID versus a False boolean value, etc. `config` is for sorting strings in the string pool.
std::unique_ptr<Item> ParseBinaryResValue(const ResourceType& type,
                                          const android::ConfigDescription& config,
                                          const android::ResStringPool& src_pool,
                                          const android::Res_value& res_value,
                                          StringPool* dst_pool);

// A string flattened from an XML hierarchy, which maintains tags and untranslatable sections
// in parallel data structures.
struct FlattenedXmlString {
  std::string text;
  std::vector<UntranslatableSection> untranslatable_sections;
  std::vector<Span> spans;
};

// Flattens an XML hierarchy into a FlattenedXmlString, formatting the text, escaping characters,
// and removing whitespace, all while keeping the untranslatable sections and spans in sync with the
// transformations.
//
// Specifically, the StringBuilder will handle escaped characters like \t, \n, \\, \', etc.
// Single quotes *must* be escaped, unless within a pair of double-quotes.
// Pairs of double-quotes disable whitespace stripping of the enclosed text.
// Unicode escape codes (\u0049) are interpreted and the represented Unicode character is inserted.
//
// A NOTE ON WHITESPACE:
//
// When preserve_spaces is false, and when text is not enclosed within double-quotes,
// StringBuilder replaces a series of whitespace with a single space character. This happens at the
// start and end of the string as well, so leading and trailing whitespace is possible.
//
// When a Span is started or stopped, the whitespace counter is reset, meaning if whitespace
// is encountered directly after the span, it will be emitted. This leads to situations like the
// following: "This <b> is </b> spaced" -> "This  is  spaced". Without spans, this would be properly
// compressed: "This  is  spaced" -> "This is spaced".
//
// Untranslatable sections do not have the same problem:
// "This <xliff:g> is </xliff:g> not spaced" -> "This is not spaced".
//
// NOTE: This is all the way it is because AAPT1 did it this way. Maintaining backwards
// compatibility is important.
//
class StringBuilder {
 public:
  using SpanHandle = size_t;
  using UntranslatableHandle = size_t;

  // Creates a StringBuilder. If preserve_spaces is true, whitespace removal is not performed, and
  // single quotations can be used without escaping them.
  explicit StringBuilder(bool preserve_spaces = false);

  // Appends a chunk of text.
  StringBuilder& AppendText(const std::string& text);

  // Starts a Span (tag) with the given name. The name is expected to be of the form:
  //  "tag_name;attr1=value;attr2=value;"
  // Which is how Spans are encoded in the ResStringPool.
  // To end the span, pass back the SpanHandle received from this method to the EndSpan() method.
  SpanHandle StartSpan(const std::string& name);

  // Ends a Span (tag). Pass in the matching SpanHandle previously obtained from StartSpan().
  void EndSpan(SpanHandle handle);

  // Starts an Untranslatable section.
  // To end the section, pass back the UntranslatableHandle received from this method to
  // the EndUntranslatable() method.
  UntranslatableHandle StartUntranslatable();

  // Ends an Untranslatable section. Pass in the matching UntranslatableHandle previously obtained
  // from StartUntranslatable().
  void EndUntranslatable(UntranslatableHandle handle);

  // Returns the flattened XML string, with all spans and untranslatable sections encoded as
  // parallel data structures.
  FlattenedXmlString GetFlattenedString() const;

  // Returns just the flattened XML text, with no spans or untranslatable sections.
  std::string to_string() const;

  // Returns true if there was no error.
  explicit operator bool() const;

  std::string GetError() const;

 private:
  DISALLOW_COPY_AND_ASSIGN(StringBuilder);

  void ResetTextState();

  std::string error_;
  FlattenedXmlString xml_string_;
  uint32_t utf16_len_ = 0u;
  bool preserve_spaces_;
  bool quote_;
  bool last_codepoint_was_space_ = false;
};

}  // namespace ResourceUtils
}  // namespace aapt

#endif /* AAPT_RESOURCEUTILS_H */
