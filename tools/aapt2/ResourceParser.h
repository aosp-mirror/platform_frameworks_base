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
#include "Diagnostics.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "StringPool.h"
#include "util/Maybe.h"
#include "util/StringPiece.h"
#include "xml/XmlPullParser.h"

#include <memory>

namespace aapt {

struct ParsedResource;

struct ResourceParserOptions {
    /**
     * Whether the default setting for this parser is to allow translation.
     */
    bool translatable = true;

    /**
     * Whether positional arguments in formatted strings are treated as errors or warnings.
     */
    bool errorOnPositionalArguments = true;
};

/*
 * Parses an XML file for resources and adds them to a ResourceTable.
 */
class ResourceParser {
public:
    ResourceParser(IDiagnostics* diag, ResourceTable* table, const Source& source,
                   const ConfigDescription& config, const ResourceParserOptions& options = {});

    ResourceParser(const ResourceParser&) = delete; // No copy.

    bool parse(xml::XmlPullParser* parser);

private:
    /*
     * Parses the XML subtree as a StyleString (flattened XML representation for strings
     * with formatting). If successful, `outStyleString`
     * contains the escaped and whitespace trimmed text, while `outRawString`
     * contains the unescaped text. Returns true on success.
     */
    bool flattenXmlSubtree(xml::XmlPullParser* parser, std::u16string* outRawString,
                           StyleString* outStyleString);

    /*
     * Parses the XML subtree and returns an Item.
     * The type of Item that can be parsed is denoted by the `typeMask`.
     * If `allowRawValue` is true and the subtree can not be parsed as a regular Item, then a
     * RawString is returned. Otherwise this returns false;
     */
    std::unique_ptr<Item> parseXml(xml::XmlPullParser* parser, const uint32_t typeMask,
                                   const bool allowRawValue);

    bool parseResources(xml::XmlPullParser* parser);
    bool parseResource(xml::XmlPullParser* parser, ParsedResource* outResource);

    bool parseItem(xml::XmlPullParser* parser, ParsedResource* outResource, uint32_t format);
    bool parseString(xml::XmlPullParser* parser, ParsedResource* outResource);

    bool parsePublic(xml::XmlPullParser* parser, ParsedResource* outResource);
    bool parsePublicGroup(xml::XmlPullParser* parser, ParsedResource* outResource);
    bool parseSymbolImpl(xml::XmlPullParser* parser, ParsedResource* outResource);
    bool parseSymbol(xml::XmlPullParser* parser, ParsedResource* outResource);
    bool parseAddResource(xml::XmlPullParser* parser, ParsedResource* outResource);
    bool parseAttr(xml::XmlPullParser* parser, ParsedResource* outResource);
    bool parseAttrImpl(xml::XmlPullParser* parser, ParsedResource* outResource, bool weak);
    Maybe<Attribute::Symbol> parseEnumOrFlagItem(xml::XmlPullParser* parser,
                                                 const StringPiece16& tag);
    bool parseStyle(xml::XmlPullParser* parser, ParsedResource* outResource);
    bool parseStyleItem(xml::XmlPullParser* parser, Style* style);
    bool parseDeclareStyleable(xml::XmlPullParser* parser, ParsedResource* outResource);
    bool parseArray(xml::XmlPullParser* parser, ParsedResource* outResource);
    bool parseIntegerArray(xml::XmlPullParser* parser, ParsedResource* outResource);
    bool parseStringArray(xml::XmlPullParser* parser, ParsedResource* outResource);
    bool parseArrayImpl(xml::XmlPullParser* parser, ParsedResource* outResource, uint32_t typeMask);
    bool parsePlural(xml::XmlPullParser* parser, ParsedResource* outResource);

    IDiagnostics* mDiag;
    ResourceTable* mTable;
    Source mSource;
    ConfigDescription mConfig;
    ResourceParserOptions mOptions;
};

} // namespace aapt

#endif // AAPT_RESOURCE_PARSER_H
