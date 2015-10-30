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
#include "XmlPullParser.h"

#include "util/Maybe.h"
#include "util/StringPiece.h"

#include <memory>

namespace aapt {

struct ParsedResource;

struct ResourceParserOptions {
    /**
     * Optional product name by which to filter resources.
     * This is like a preprocessor definition in that we strip out resources
     * that don't match before we compile them.
     */
    Maybe<std::u16string> product;
};

/*
 * Parses an XML file for resources and adds them to a ResourceTable.
 */
class ResourceParser {
public:
    ResourceParser(IDiagnostics* diag, ResourceTable* table, const Source& source,
                   const ConfigDescription& config, const ResourceParserOptions& options = {});

    ResourceParser(const ResourceParser&) = delete; // No copy.

    bool parse(XmlPullParser* parser);

private:
    /*
     * Parses the XML subtree as a StyleString (flattened XML representation for strings
     * with formatting). If successful, `outStyleString`
     * contains the escaped and whitespace trimmed text, while `outRawString`
     * contains the unescaped text. Returns true on success.
     */
    bool flattenXmlSubtree(XmlPullParser* parser, std::u16string* outRawString,
                           StyleString* outStyleString);

    /*
     * Parses the XML subtree and returns an Item.
     * The type of Item that can be parsed is denoted by the `typeMask`.
     * If `allowRawValue` is true and the subtree can not be parsed as a regular Item, then a
     * RawString is returned. Otherwise this returns false;
     */
    std::unique_ptr<Item> parseXml(XmlPullParser* parser, const uint32_t typeMask,
                                   const bool allowRawValue);

    bool parseResources(XmlPullParser* parser);
    bool parseString(XmlPullParser* parser, ParsedResource* outResource);
    bool parseColor(XmlPullParser* parser, ParsedResource* outResource);
    bool parsePrimitive(XmlPullParser* parser, ParsedResource* outResource);
    bool parsePublic(XmlPullParser* parser, ParsedResource* outResource);
    bool parseSymbol(XmlPullParser* parser, ParsedResource* outResource);
    bool parseAttr(XmlPullParser* parser, ParsedResource* outResource);
    bool parseAttrImpl(XmlPullParser* parser, ParsedResource* outResource, bool weak);
    Maybe<Attribute::Symbol> parseEnumOrFlagItem(XmlPullParser* parser, const StringPiece16& tag);
    bool parseStyle(XmlPullParser* parser, ParsedResource* outResource);
    bool parseStyleItem(XmlPullParser* parser, Style* style);
    bool parseDeclareStyleable(XmlPullParser* parser, ParsedResource* outResource);
    bool parseArray(XmlPullParser* parser, ParsedResource* outResource, uint32_t typeMask);
    bool parsePlural(XmlPullParser* parser, ParsedResource* outResource);

    IDiagnostics* mDiag;
    ResourceTable* mTable;
    Source mSource;
    ConfigDescription mConfig;
    ResourceParserOptions mOptions;
};

} // namespace aapt

#endif // AAPT_RESOURCE_PARSER_H
