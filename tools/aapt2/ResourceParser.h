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

#ifndef AAPT_RESOURCE_PARSER_H
#define AAPT_RESOURCE_PARSER_H

#include "ConfigDescription.h"
#include "Logger.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "StringPiece.h"
#include "StringPool.h"
#include "XmlPullParser.h"

#include <istream>
#include <memory>

namespace aapt {

/*
 * Parses an XML file for resources and adds them to a ResourceTable.
 */
class ResourceParser {
public:
    /*
     * Extracts the package, type, and name from a string of the format:
     *
     *      [package:]type/name
     *
     * where the package can be empty. Validation must be performed on each
     * individual extracted piece to verify that the pieces are valid.
     */
    static void extractResourceName(const StringPiece16& str, StringPiece16* outPackage,
                                    StringPiece16* outType, StringPiece16* outEntry);

    /*
     * Returns true if the string was parsed as a reference (@[+][package:]type/name), with
     * `outReference` set to the parsed reference.
     *
     * If '+' was present in the reference, `outCreate` is set to true.
     * If '*' was present in the reference, `outPrivate` is set to true.
     */
    static bool tryParseReference(const StringPiece16& str, ResourceNameRef* outReference,
                                  bool* outCreate, bool* outPrivate);

    /*
     * Returns true if the string was parsed as an attribute reference (?[package:]type/name),
     * with `outReference` set to the parsed reference.
     */
    static bool tryParseAttributeReference(const StringPiece16& str,
                                           ResourceNameRef* outReference);

    /*
     * Returns true if the string `str` was parsed as a valid reference to a style.
     * The format for a style parent is slightly more flexible than a normal reference:
     *
     * @[package:]style/<entry> or
     * ?[package:]style/<entry> or
     * <package>:[style/]<entry>
     */
    static bool parseStyleParentReference(const StringPiece16& str, Reference* outReference,
                                          std::string* outError);

    /*
     * Returns a Reference object if the string was parsed as a resource or attribute reference,
     * ( @[+][package:]type/name | ?[package:]type/name ) setting outCreate to true if
     * the '+' was present in the string.
     */
    static std::unique_ptr<Reference> tryParseReference(const StringPiece16& str,
                                                        bool* outCreate);

    /*
     * Returns a BinaryPrimitve object representing @null or @empty if the string was parsed
     * as one.
     */
    static std::unique_ptr<BinaryPrimitive> tryParseNullOrEmpty(const StringPiece16& str);

    /*
     * Returns a BinaryPrimitve object representing a color if the string was parsed
     * as one.
     */
    static std::unique_ptr<BinaryPrimitive> tryParseColor(const StringPiece16& str);

    /*
     * Returns a BinaryPrimitve object representing a boolean if the string was parsed
     * as one.
     */
    static std::unique_ptr<BinaryPrimitive> tryParseBool(const StringPiece16& str);

    /*
     * Returns a BinaryPrimitve object representing an integer if the string was parsed
     * as one.
     */
    static std::unique_ptr<BinaryPrimitive> tryParseInt(const StringPiece16& str);

    /*
     * Returns a BinaryPrimitve object representing a floating point number
     * (float, dimension, etc) if the string was parsed as one.
     */
    static std::unique_ptr<BinaryPrimitive> tryParseFloat(const StringPiece16& str);

    /*
     * Returns a BinaryPrimitve object representing an enum symbol if the string was parsed
     * as one.
     */
    static std::unique_ptr<BinaryPrimitive> tryParseEnumSymbol(const Attribute& enumAttr,
                                                               const StringPiece16& str);

    /*
     * Returns a BinaryPrimitve object representing a flag symbol if the string was parsed
     * as one.
     */
    static std::unique_ptr<BinaryPrimitive> tryParseFlagSymbol(const Attribute& enumAttr,
                                                               const StringPiece16& str);
    /*
     * Try to convert a string to an Item for the given attribute. The attribute will
     * restrict what values the string can be converted to.
     * The callback function onCreateReference is called when the parsed item is a
     * reference to an ID that must be created (@+id/foo).
     */
    static std::unique_ptr<Item> parseItemForAttribute(
            const StringPiece16& value, const Attribute& attr,
            std::function<void(const ResourceName&)> onCreateReference = {});

    static std::unique_ptr<Item> parseItemForAttribute(
            const StringPiece16& value, uint32_t typeMask,
            std::function<void(const ResourceName&)> onCreateReference = {});

    static uint32_t androidTypeToAttributeTypeMask(uint16_t type);

    ResourceParser(const std::shared_ptr<ResourceTable>& table, const Source& source,
                   const ConfigDescription& config, const std::shared_ptr<XmlPullParser>& parser);

    ResourceParser(const ResourceParser&) = delete; // No copy.

    bool parse();

private:
    /*
     * Parses the XML subtree as a StyleString (flattened XML representation for strings
     * with formatting). If successful, `outStyleString`
     * contains the escaped and whitespace trimmed text, while `outRawString`
     * contains the unescaped text. Returns true on success.
     */
    bool flattenXmlSubtree(XmlPullParser* parser, std::u16string* outRawString,\
                           StyleString* outStyleString);

    /*
     * Parses the XML subtree and converts it to an Item. The type of Item that can be
     * parsed is denoted by the `typeMask`. If `allowRawValue` is true and the subtree
     * can not be parsed as a regular Item, then a RawString is returned. Otherwise
     * this returns nullptr.
     */
    std::unique_ptr<Item> parseXml(XmlPullParser* parser, uint32_t typeMask, bool allowRawValue);

    bool parseResources(XmlPullParser* parser);
    bool parseString(XmlPullParser* parser, const ResourceNameRef& resourceName);
    bool parseColor(XmlPullParser* parser, const ResourceNameRef& resourceName);
    bool parsePrimitive(XmlPullParser* parser, const ResourceNameRef& resourceName);
    bool parsePublic(XmlPullParser* parser, const StringPiece16& name);
    bool parseAttr(XmlPullParser* parser, const ResourceNameRef& resourceName);
    std::unique_ptr<Attribute> parseAttrImpl(XmlPullParser* parser,
                                             ResourceName* resourceName,
                                             bool weak);
    bool parseEnumOrFlagItem(XmlPullParser* parser, const StringPiece16& tag,
                             Attribute::Symbol* outSymbol);
    bool parseStyle(XmlPullParser* parser, const ResourceNameRef& resourceName);
    bool parseUntypedItem(XmlPullParser* parser, Style& style);
    bool parseDeclareStyleable(XmlPullParser* parser, const ResourceNameRef& resourceName);
    bool parseArray(XmlPullParser* parser, const ResourceNameRef& resourceName, uint32_t typeMask);
    bool parsePlural(XmlPullParser* parser, const ResourceNameRef& resourceName);

    std::shared_ptr<ResourceTable> mTable;
    Source mSource;
    ConfigDescription mConfig;
    SourceLogger mLogger;
    std::shared_ptr<XmlPullParser> mParser;
};

} // namespace aapt

#endif // AAPT_RESOURCE_PARSER_H
