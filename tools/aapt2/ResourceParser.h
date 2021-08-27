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

#include <memory>

#include "android-base/macros.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/StringPiece.h"

#include "Diagnostics.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "StringPool.h"
#include "util/Maybe.h"
#include "xml/XmlPullParser.h"

namespace aapt {

struct ParsedResource;

struct ResourceParserOptions {
  /**
   * Whether the default setting for this parser is to allow translation.
   */
  bool translatable = true;

  /**
   * Whether positional arguments in formatted strings are treated as errors or
   * warnings.
   */
  bool error_on_positional_arguments = true;

  /**
   * If true, apply the same visibility rules for styleables as are used for
   * all other resources.  Otherwise, all styleables will be made public.
   */
  bool preserve_visibility_of_styleables = false;

  // If visibility was forced, we need to use it when creating a new resource and also error if we
  // try to parse the <public>, <public-group>, <java-symbol> or <symbol> tags.
  Maybe<Visibility::Level> visibility;
};

struct FlattenedXmlSubTree {
  std::string raw_value;
  StyleString style_string;
  std::vector<UntranslatableSection> untranslatable_sections;
  xml::IPackageDeclStack* namespace_resolver;
  Source source;
};

/*
 * Parses an XML file for resources and adds them to a ResourceTable.
 */
class ResourceParser {
 public:
  ResourceParser(IDiagnostics* diag, ResourceTable* table, const Source& source,
                 const android::ConfigDescription& config,
                 const ResourceParserOptions& options = {});
  bool Parse(xml::XmlPullParser* parser);

  static std::unique_ptr<Item> ParseXml(const FlattenedXmlSubTree& xmlsub_tree, uint32_t type_mask,
                                        bool allow_raw_value, ResourceTable& table,
                                        const android::ConfigDescription& config,
                                        IDiagnostics& diag);

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceParser);

  std::optional<FlattenedXmlSubTree> CreateFlattenSubTree(xml::XmlPullParser* parser);

  // Parses the XML subtree as a StyleString (flattened XML representation for strings with
  // formatting). If parsing fails, false is returned and the out parameters are left in an
  // unspecified state. Otherwise,
  // `out_style_string` contains the escaped and whitespace trimmed text.
  // `out_raw_string` contains the un-escaped text.
  // `out_untranslatable_sections` contains the sections of the string that should not be
  // translated.
  bool FlattenXmlSubtree(xml::XmlPullParser* parser, std::string* out_raw_string,
                         StyleString* out_style_string,
                         std::vector<UntranslatableSection>* out_untranslatable_sections);

  /*
   * Parses the XML subtree and returns an Item.
   * The type of Item that can be parsed is denoted by the `type_mask`.
   * If `allow_raw_value` is true and the subtree can not be parsed as a regular
   * Item, then a
   * RawString is returned. Otherwise this returns false;
   */
  std::unique_ptr<Item> ParseXml(xml::XmlPullParser* parser, const uint32_t type_mask,
                                 const bool allow_raw_value);

  bool ParseResources(xml::XmlPullParser* parser);
  bool ParseResource(xml::XmlPullParser* parser, ParsedResource* out_resource);

  bool ParseItem(xml::XmlPullParser* parser, ParsedResource* out_resource, uint32_t format);
  bool ParseString(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseMacro(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParsePublic(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParsePublicGroup(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseStagingPublicGroup(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseStagingPublicGroupFinal(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseSymbolImpl(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseSymbol(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseOverlayable(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseAddResource(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseAttr(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseAttrImpl(xml::XmlPullParser* parser, ParsedResource* out_resource, bool weak);
  Maybe<Attribute::Symbol> ParseEnumOrFlagItem(xml::XmlPullParser* parser,
                                               const android::StringPiece& tag);
  bool ParseStyle(ResourceType type, xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseStyleItem(xml::XmlPullParser* parser, Style* style);
  bool ParseDeclareStyleable(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseArray(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseIntegerArray(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseStringArray(xml::XmlPullParser* parser, ParsedResource* out_resource);
  bool ParseArrayImpl(xml::XmlPullParser* parser, ParsedResource* out_resource, uint32_t typeMask);
  bool ParsePlural(xml::XmlPullParser* parser, ParsedResource* out_resource);

  IDiagnostics* diag_;
  ResourceTable* table_;
  Source source_;
  android::ConfigDescription config_;
  ResourceParserOptions options_;
};

}  // namespace aapt

#endif  // AAPT_RESOURCE_PARSER_H
