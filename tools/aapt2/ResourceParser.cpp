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

#include "ResourceParser.h"
#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "XmlPullParser.h"

#include "util/Util.h"

#include <sstream>

namespace aapt {

constexpr const char16_t* sXliffNamespaceUri = u"urn:oasis:names:tc:xliff:document:1.2";

static Maybe<StringPiece16> findAttribute(XmlPullParser* parser, const StringPiece16& name) {
    auto iter = parser->findAttribute(u"", name);
    if (iter != parser->endAttributes()) {
        return StringPiece16(util::trimWhitespace(iter->value));
    }
    return {};
}

static Maybe<StringPiece16> findNonEmptyAttribute(XmlPullParser* parser,
                                                  const StringPiece16& name) {
    auto iter = parser->findAttribute(u"", name);
    if (iter != parser->endAttributes()) {
        StringPiece16 trimmed = util::trimWhitespace(iter->value);
        if (!trimmed.empty()) {
            return trimmed;
        }
    }
    return {};
}

ResourceParser::ResourceParser(IDiagnostics* diag, ResourceTable* table, const Source& source,
                               const ConfigDescription& config,
                               const ResourceParserOptions& options) :
        mDiag(diag), mTable(table), mSource(source), mConfig(config), mOptions(options) {
}

/**
 * Build a string from XML that converts nested elements into Span objects.
 */
bool ResourceParser::flattenXmlSubtree(XmlPullParser* parser, std::u16string* outRawString,
                                       StyleString* outStyleString) {
    std::vector<Span> spanStack;

    outRawString->clear();
    outStyleString->spans.clear();
    util::StringBuilder builder;
    size_t depth = 1;
    while (XmlPullParser::isGoodEvent(parser->next())) {
        const XmlPullParser::Event event = parser->getEvent();
        if (event == XmlPullParser::Event::kEndElement) {
            if (!parser->getElementNamespace().empty()) {
                // We already warned and skipped the start element, so just skip here too
                continue;
            }

            depth--;
            if (depth == 0) {
                break;
            }

            spanStack.back().lastChar = builder.str().size();
            outStyleString->spans.push_back(spanStack.back());
            spanStack.pop_back();

        } else if (event == XmlPullParser::Event::kText) {
            // TODO(adamlesinski): Verify format strings.
            outRawString->append(parser->getText());
            builder.append(parser->getText());

        } else if (event == XmlPullParser::Event::kStartElement) {
            if (!parser->getElementNamespace().empty()) {
                if (parser->getElementNamespace() != sXliffNamespaceUri) {
                    // Only warn if this isn't an xliff namespace.
                    mDiag->warn(DiagMessage(mSource.withLine(parser->getLineNumber()))
                                << "skipping element '"
                                << parser->getElementName()
                                << "' with unknown namespace '"
                                << parser->getElementNamespace()
                                << "'");
                }
                continue;
            }
            depth++;

            // Build a span object out of the nested element.
            std::u16string spanName = parser->getElementName();
            const auto endAttrIter = parser->endAttributes();
            for (auto attrIter = parser->beginAttributes(); attrIter != endAttrIter; ++attrIter) {
                spanName += u";";
                spanName += attrIter->name;
                spanName += u"=";
                spanName += attrIter->value;
            }

            if (builder.str().size() > std::numeric_limits<uint32_t>::max()) {
                mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                             << "style string '" << builder.str() << "' is too long");
                return false;
            }
            spanStack.push_back(Span{ spanName, static_cast<uint32_t>(builder.str().size()) });

        } else if (event == XmlPullParser::Event::kComment) {
            // Skip
        } else {
            assert(false);
        }
    }
    assert(spanStack.empty() && "spans haven't been fully processed");

    outStyleString->str = builder.str();
    return true;
}

bool ResourceParser::parse(XmlPullParser* parser) {
    bool error = false;
    const size_t depth = parser->getDepth();
    while (XmlPullParser::nextChildNode(parser, depth)) {
        if (parser->getEvent() != XmlPullParser::Event::kStartElement) {
            // Skip comments and text.
            continue;
        }

        if (!parser->getElementNamespace().empty() || parser->getElementName() != u"resources") {
            mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                         << "root element must be <resources>");
            return false;
        }

        error |= !parseResources(parser);
        break;
    };

    if (parser->getEvent() == XmlPullParser::Event::kBadDocument) {
        mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                     << "xml parser error: " << parser->getLastError());
        return false;
    }
    return !error;
}

static bool shouldStripResource(XmlPullParser* parser, const Maybe<std::u16string> productToMatch) {
    assert(parser->getEvent() == XmlPullParser::Event::kStartElement);

    if (Maybe<StringPiece16> maybeProduct = findNonEmptyAttribute(parser, u"product")) {
        if (!productToMatch) {
            if (maybeProduct.value() != u"default" && maybeProduct.value() != u"phone") {
                // We didn't specify a product and this is not a default product, so skip.
                return true;
            }
        } else {
            if (productToMatch && maybeProduct.value() != productToMatch.value()) {
                // We specified a product, but they don't match.
                return true;
            }
        }
    }
    return false;
}

/**
 * A parsed resource ready to be added to the ResourceTable.
 */
struct ParsedResource {
    ResourceName name;
    Source source;
    ResourceId id;
    SymbolState symbolState = SymbolState::kUndefined;
    std::u16string comment;
    std::unique_ptr<Value> value;
    std::list<ParsedResource> childResources;
};

// Recursively adds resources to the ResourceTable.
static bool addResourcesToTable(ResourceTable* table, const ConfigDescription& config,
                                IDiagnostics* diag, ParsedResource* res) {
    if (res->symbolState != SymbolState::kUndefined) {
        Symbol symbol;
        symbol.state = res->symbolState;
        symbol.source = res->source;
        symbol.comment = res->comment;
        if (!table->setSymbolState(res->name, res->id, symbol, diag)) {
            return false;
        }
    }

    if (!res->value) {
        return true;
    }

    // Attach the comment, source and config to the value.
    res->value->setComment(std::move(res->comment));
    res->value->setSource(std::move(res->source));

    if (!table->addResource(res->name, res->id, config, std::move(res->value), diag)) {
        return false;
    }

    bool error = false;
    for (ParsedResource& child : res->childResources) {
        error |= !addResourcesToTable(table, config, diag, &child);
    }
    return !error;
}

bool ResourceParser::parseResources(XmlPullParser* parser) {
    std::set<ResourceName> strippedResources;

    bool error = false;
    std::u16string comment;
    const size_t depth = parser->getDepth();
    while (XmlPullParser::nextChildNode(parser, depth)) {
        const XmlPullParser::Event event = parser->getEvent();
        if (event == XmlPullParser::Event::kComment) {
            comment = parser->getComment();
            continue;
        }

        if (event == XmlPullParser::Event::kText) {
            if (!util::trimWhitespace(parser->getText()).empty()) {
                mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                             << "plain text not allowed here");
                error = true;
            }
            continue;
        }

        assert(event == XmlPullParser::Event::kStartElement);

        if (!parser->getElementNamespace().empty()) {
            // Skip unknown namespace.
            continue;
        }

        std::u16string elementName = parser->getElementName();
        if (elementName == u"skip" || elementName == u"eat-comment") {
            comment = u"";
            continue;
        }

        Maybe<StringPiece16> maybeName = findNonEmptyAttribute(parser, u"name");
        if (!maybeName) {
            mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                         << "<" << elementName << "> tag must have a 'name' attribute");
            error = true;
            continue;
        }

        // Check if we should skip this product.
        const bool stripResource = shouldStripResource(parser, mOptions.product);

        if (elementName == u"item") {
            // Items simply have their type encoded in the type attribute.
            if (Maybe<StringPiece16> maybeType = findNonEmptyAttribute(parser, u"type")) {
                elementName = maybeType.value().toString();
            } else {
                mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                             << "<item> must have a 'type' attribute");
                error = true;
                continue;
            }
        }

        ParsedResource parsedResource;
        parsedResource.name.entry = maybeName.value().toString();
        parsedResource.source = mSource.withLine(parser->getLineNumber());
        parsedResource.comment = std::move(comment);

        bool result = true;
        if (elementName == u"id") {
            parsedResource.name.type = ResourceType::kId;
            parsedResource.value = util::make_unique<Id>();
        } else if (elementName == u"string") {
            parsedResource.name.type = ResourceType::kString;
            result = parseString(parser, &parsedResource);
        } else if (elementName == u"color") {
            parsedResource.name.type = ResourceType::kColor;
            result = parseColor(parser, &parsedResource);
        } else if (elementName == u"drawable") {
            parsedResource.name.type = ResourceType::kDrawable;
            result = parseColor(parser, &parsedResource);
        } else if (elementName == u"bool") {
            parsedResource.name.type = ResourceType::kBool;
            result = parsePrimitive(parser, &parsedResource);
        } else if (elementName == u"integer") {
            parsedResource.name.type = ResourceType::kInteger;
            result = parsePrimitive(parser, &parsedResource);
        } else if (elementName == u"dimen") {
            parsedResource.name.type = ResourceType::kDimen;
            result = parsePrimitive(parser, &parsedResource);
        } else if (elementName == u"style") {
            parsedResource.name.type = ResourceType::kStyle;
            result = parseStyle(parser, &parsedResource);
        } else if (elementName == u"plurals") {
            parsedResource.name.type = ResourceType::kPlurals;
            result = parsePlural(parser, &parsedResource);
        } else if (elementName == u"array") {
            parsedResource.name.type = ResourceType::kArray;
            result = parseArray(parser, &parsedResource, android::ResTable_map::TYPE_ANY);
        } else if (elementName == u"string-array") {
            parsedResource.name.type = ResourceType::kArray;
            result = parseArray(parser, &parsedResource, android::ResTable_map::TYPE_STRING);
        } else if (elementName == u"integer-array") {
            parsedResource.name.type = ResourceType::kIntegerArray;
            result = parseArray(parser, &parsedResource, android::ResTable_map::TYPE_INTEGER);
        } else if (elementName == u"declare-styleable") {
            parsedResource.name.type = ResourceType::kStyleable;
            result = parseDeclareStyleable(parser, &parsedResource);
        } else if (elementName == u"attr") {
            parsedResource.name.type = ResourceType::kAttr;
            result = parseAttr(parser, &parsedResource);
        } else if (elementName == u"public") {
            result = parsePublic(parser, &parsedResource);
        } else if (elementName == u"java-symbol" || elementName == u"symbol") {
            result = parseSymbol(parser, &parsedResource);
        } else {
            mDiag->warn(DiagMessage(mSource.withLine(parser->getLineNumber()))
                        << "unknown resource type '" << elementName << "'");
        }

        if (result) {
            // We successfully parsed the resource.

            if (stripResource) {
                // Record that we stripped out this resource name.
                // We will check that at least one variant of this resource was included.
                strippedResources.insert(parsedResource.name);
            } else {
                error |= !addResourcesToTable(mTable, mConfig, mDiag, &parsedResource);
            }
        } else {
            error = true;
        }
    }

    // Check that we included at least one variant of each stripped resource.
    for (const ResourceName& strippedResource : strippedResources) {
        if (!mTable->findResource(strippedResource)) {
            // Failed to find the resource.
            mDiag->error(DiagMessage(mSource) << "resource '" << strippedResource << "' "
                         "was filtered out but no product variant remains");
            error = true;
        }
    }

    return !error;
}

enum {
    kAllowRawString = true,
    kNoRawString = false
};

/**
 * Reads the entire XML subtree and attempts to parse it as some Item,
 * with typeMask denoting which items it can be. If allowRawValue is
 * true, a RawString is returned if the XML couldn't be parsed as
 * an Item. If allowRawValue is false, nullptr is returned in this
 * case.
 */
std::unique_ptr<Item> ResourceParser::parseXml(XmlPullParser* parser, const uint32_t typeMask,
                                               const bool allowRawValue) {
    const size_t beginXmlLine = parser->getLineNumber();

    std::u16string rawValue;
    StyleString styleString;
    if (!flattenXmlSubtree(parser, &rawValue, &styleString)) {
        return {};
    }

    if (!styleString.spans.empty()) {
        // This can only be a StyledString.
        return util::make_unique<StyledString>(
                mTable->stringPool.makeRef(styleString, StringPool::Context{ 1, mConfig }));
    }

    auto onCreateReference = [&](const ResourceName& name) {
        // name.package can be empty here, as it will assume the package name of the table.
        std::unique_ptr<Id> id = util::make_unique<Id>();
        id->setSource(mSource.withLine(beginXmlLine));
        mTable->addResource(name, {}, std::move(id), mDiag);
    };

    // Process the raw value.
    std::unique_ptr<Item> processedItem = ResourceUtils::parseItemForAttribute(rawValue, typeMask,
                                                                               onCreateReference);
    if (processedItem) {
        // Fix up the reference.
        if (Reference* ref = valueCast<Reference>(processedItem.get())) {
            if (Maybe<ResourceName> transformedName =
                    parser->transformPackage(ref->name.value(), u"")) {
                ref->name = std::move(transformedName);
            }
        }
        return processedItem;
    }

    // Try making a regular string.
    if (typeMask & android::ResTable_map::TYPE_STRING) {
        // Use the trimmed, escaped string.
        return util::make_unique<String>(
                mTable->stringPool.makeRef(styleString.str, StringPool::Context{ 1, mConfig }));
    }

    if (allowRawValue) {
        // We can't parse this so return a RawString if we are allowed.
        return util::make_unique<RawString>(
                mTable->stringPool.makeRef(rawValue, StringPool::Context{ 1, mConfig }));
    }

    return {};
}

bool ResourceParser::parseString(XmlPullParser* parser, ParsedResource* outResource) {
    const Source source = mSource.withLine(parser->getLineNumber());

    // TODO(adamlesinski): Read "untranslateable" attribute.

    outResource->value = parseXml(parser, android::ResTable_map::TYPE_STRING, kNoRawString);
    if (!outResource->value) {
        mDiag->error(DiagMessage(source) << "not a valid string");
        return false;
    }
    return true;
}

bool ResourceParser::parseColor(XmlPullParser* parser, ParsedResource* outResource) {
    const Source source = mSource.withLine(parser->getLineNumber());

    outResource->value = parseXml(parser, android::ResTable_map::TYPE_COLOR, kNoRawString);
    if (!outResource->value) {
        mDiag->error(DiagMessage(source) << "invalid color");
        return false;
    }
    return true;
}

bool ResourceParser::parsePrimitive(XmlPullParser* parser, ParsedResource* outResource) {
    const Source source = mSource.withLine(parser->getLineNumber());

    uint32_t typeMask = 0;
    switch (outResource->name.type) {
    case ResourceType::kInteger:
        typeMask |= android::ResTable_map::TYPE_INTEGER;
        break;

    case ResourceType::kDimen:
        typeMask |= android::ResTable_map::TYPE_DIMENSION
                  | android::ResTable_map::TYPE_FLOAT
                  | android::ResTable_map::TYPE_FRACTION;
        break;

    case ResourceType::kBool:
        typeMask |= android::ResTable_map::TYPE_BOOLEAN;
        break;

    default:
        assert(false);
        break;
    }

    outResource->value = parseXml(parser, typeMask, kNoRawString);
    if (!outResource->value) {
        mDiag->error(DiagMessage(source) << "invalid " << outResource->name.type);
        return false;
    }
    return true;
}

bool ResourceParser::parsePublic(XmlPullParser* parser, ParsedResource* outResource) {
    const Source source = mSource.withLine(parser->getLineNumber());

    Maybe<StringPiece16> maybeType = findNonEmptyAttribute(parser, u"type");
    if (!maybeType) {
        mDiag->error(DiagMessage(source) << "<public> must have a 'type' attribute");
        return false;
    }

    const ResourceType* parsedType = parseResourceType(maybeType.value());
    if (!parsedType) {
        mDiag->error(DiagMessage(source) << "invalid resource type '" << maybeType.value()
                     << "' in <public>");
        return false;
    }

    outResource->name.type = *parsedType;

    if (Maybe<StringPiece16> maybeId = findNonEmptyAttribute(parser, u"id")) {
        android::Res_value val;
        bool result = android::ResTable::stringToInt(maybeId.value().data(),
                                                     maybeId.value().size(), &val);
        ResourceId resourceId(val.data);
        if (!result || !resourceId.isValid()) {
            mDiag->error(DiagMessage(source) << "invalid resource ID '" << maybeId.value()
                         << "' in <public>");
            return false;
        }
        outResource->id = resourceId;
    }

    if (*parsedType == ResourceType::kId) {
        // An ID marked as public is also the definition of an ID.
        outResource->value = util::make_unique<Id>();
    }

    outResource->symbolState = SymbolState::kPublic;
    return true;
}

bool ResourceParser::parseSymbol(XmlPullParser* parser, ParsedResource* outResource) {
    const Source source = mSource.withLine(parser->getLineNumber());

    Maybe<StringPiece16> maybeType = findNonEmptyAttribute(parser, u"type");
    if (!maybeType) {
        mDiag->error(DiagMessage(source) << "<" << parser->getElementName() << "> must have a "
                     "'type' attribute");
        return false;
    }

    const ResourceType* parsedType = parseResourceType(maybeType.value());
    if (!parsedType) {
        mDiag->error(DiagMessage(source) << "invalid resource type '" << maybeType.value()
                     << "' in <" << parser->getElementName() << ">");
        return false;
    }

    outResource->name.type = *parsedType;
    outResource->symbolState = SymbolState::kPrivate;
    return true;
}

static uint32_t parseFormatType(const StringPiece16& piece) {
    if (piece == u"reference")      return android::ResTable_map::TYPE_REFERENCE;
    else if (piece == u"string")    return android::ResTable_map::TYPE_STRING;
    else if (piece == u"integer")   return android::ResTable_map::TYPE_INTEGER;
    else if (piece == u"boolean")   return android::ResTable_map::TYPE_BOOLEAN;
    else if (piece == u"color")     return android::ResTable_map::TYPE_COLOR;
    else if (piece == u"float")     return android::ResTable_map::TYPE_FLOAT;
    else if (piece == u"dimension") return android::ResTable_map::TYPE_DIMENSION;
    else if (piece == u"fraction")  return android::ResTable_map::TYPE_FRACTION;
    else if (piece == u"enum")      return android::ResTable_map::TYPE_ENUM;
    else if (piece == u"flags")     return android::ResTable_map::TYPE_FLAGS;
    return 0;
}

static uint32_t parseFormatAttribute(const StringPiece16& str) {
    uint32_t mask = 0;
    for (StringPiece16 part : util::tokenize(str, u'|')) {
        StringPiece16 trimmedPart = util::trimWhitespace(part);
        uint32_t type = parseFormatType(trimmedPart);
        if (type == 0) {
            return 0;
        }
        mask |= type;
    }
    return mask;
}


bool ResourceParser::parseAttr(XmlPullParser* parser, ParsedResource* outResource) {
    outResource->source = mSource.withLine(parser->getLineNumber());
    return parseAttrImpl(parser, outResource, false);
}

bool ResourceParser::parseAttrImpl(XmlPullParser* parser, ParsedResource* outResource, bool weak) {
    uint32_t typeMask = 0;

    Maybe<StringPiece16> maybeFormat = findAttribute(parser, u"format");
    if (maybeFormat) {
        typeMask = parseFormatAttribute(maybeFormat.value());
        if (typeMask == 0) {
            mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                         << "invalid attribute format '" << maybeFormat.value() << "'");
            return false;
        }
    }

    // If this is a declaration, the package name may be in the name. Separate these out.
    // Eg. <attr name="android:text" />
    // No format attribute is allowed.
    if (weak && !maybeFormat) {
        StringPiece16 package, type, name;
        ResourceUtils::extractResourceName(outResource->name.entry, &package, &type, &name);
        if (type.empty() && !package.empty()) {
            outResource->name.package = package.toString();
            outResource->name.entry = name.toString();
        }
    }

    std::vector<Attribute::Symbol> items;

    std::u16string comment;
    bool error = false;
    const size_t depth = parser->getDepth();
    while (XmlPullParser::nextChildNode(parser, depth)) {
        if (parser->getEvent() != XmlPullParser::Event::kStartElement) {
            // Skip comments and text.
            continue;
        }

        const std::u16string& elementNamespace = parser->getElementNamespace();
        const std::u16string& elementName = parser->getElementName();
        if (elementNamespace == u"" && (elementName == u"flag" || elementName == u"enum")) {
            if (elementName == u"enum") {
                if (typeMask & android::ResTable_map::TYPE_FLAGS) {
                    mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                                 << "can not define an <enum>; already defined a <flag>");
                    error = true;
                    continue;
                }
                typeMask |= android::ResTable_map::TYPE_ENUM;
            } else if (elementName == u"flag") {
                if (typeMask & android::ResTable_map::TYPE_ENUM) {
                    mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                                 << "can not define a <flag>; already defined an <enum>");
                    error = true;
                    continue;
                }
                typeMask |= android::ResTable_map::TYPE_FLAGS;
            }

            if (Maybe<Attribute::Symbol> s = parseEnumOrFlagItem(parser, elementName)) {
                ParsedResource childResource;
                childResource.name = s.value().symbol.name.value();
                childResource.source = mSource.withLine(parser->getLineNumber());
                childResource.value = util::make_unique<Id>();
                outResource->childResources.push_back(std::move(childResource));
                items.push_back(std::move(s.value()));
            } else {
                error = true;
            }
        } else if (elementName == u"skip" || elementName == u"eat-comment") {
            comment = u"";

        } else {
            mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                         << ":" << elementName << ">");
            error = true;
        }
    }

    if (error) {
        return false;
    }

    std::unique_ptr<Attribute> attr = util::make_unique<Attribute>(weak);
    attr->symbols.swap(items);
    attr->typeMask = typeMask ? typeMask : uint32_t(android::ResTable_map::TYPE_ANY);
    outResource->value = std::move(attr);
    return true;
}

Maybe<Attribute::Symbol> ResourceParser::parseEnumOrFlagItem(XmlPullParser* parser,
                                                             const StringPiece16& tag) {
    const Source source = mSource.withLine(parser->getLineNumber());

    Maybe<StringPiece16> maybeName = findNonEmptyAttribute(parser, u"name");
    if (!maybeName) {
        mDiag->error(DiagMessage(source) << "no attribute 'name' found for tag <" << tag << ">");
        return {};
    }

    Maybe<StringPiece16> maybeValue = findNonEmptyAttribute(parser, u"value");
    if (!maybeValue) {
        mDiag->error(DiagMessage(source) << "no attribute 'value' found for tag <" << tag << ">");
        return {};
    }

    android::Res_value val;
    if (!android::ResTable::stringToInt(maybeValue.value().data(),
                                        maybeValue.value().size(), &val)) {
        mDiag->error(DiagMessage(source) << "invalid value '" << maybeValue.value()
                     << "' for <" << tag << ">; must be an integer");
        return {};
    }

    return Attribute::Symbol{
            Reference(ResourceName({}, ResourceType::kId, maybeName.value().toString())),
            val.data };
}

static Maybe<ResourceName> parseXmlAttributeName(StringPiece16 str) {
    str = util::trimWhitespace(str);
    const char16_t* const start = str.data();
    const char16_t* const end = start + str.size();
    const char16_t* p = start;

    StringPiece16 package;
    StringPiece16 name;
    while (p != end) {
        if (*p == u':') {
            package = StringPiece16(start, p - start);
            name = StringPiece16(p + 1, end - (p + 1));
            break;
        }
        p++;
    }

    return ResourceName{ package.toString(), ResourceType::kAttr,
        name.empty() ? str.toString() : name.toString() };
}


bool ResourceParser::parseStyleItem(XmlPullParser* parser, Style* style) {
    const Source source = mSource.withLine(parser->getLineNumber());

    Maybe<StringPiece16> maybeName = findNonEmptyAttribute(parser, u"name");
    if (!maybeName) {
        mDiag->error(DiagMessage(source) << "<item> must have a 'name' attribute");
        return false;
    }

    Maybe<ResourceName> maybeKey = parseXmlAttributeName(maybeName.value());
    if (!maybeKey) {
        mDiag->error(DiagMessage(source) << "invalid attribute name '" << maybeName.value() << "'");
        return false;
    }

    if (Maybe<ResourceName> transformedName = parser->transformPackage(maybeKey.value(), u"")) {
        maybeKey = std::move(transformedName);
    }

    std::unique_ptr<Item> value = parseXml(parser, 0, kAllowRawString);
    if (!value) {
        mDiag->error(DiagMessage(source) << "could not parse style item");
        return false;
    }

    style->entries.push_back(Style::Entry{ Reference(maybeKey.value()), std::move(value) });
    return true;
}

bool ResourceParser::parseStyle(XmlPullParser* parser, ParsedResource* outResource) {
    const Source source = mSource.withLine(parser->getLineNumber());
    std::unique_ptr<Style> style = util::make_unique<Style>();

    Maybe<StringPiece16> maybeParent = findAttribute(parser, u"parent");
    if (maybeParent) {
        // If the parent is empty, we don't have a parent, but we also don't infer either.
        if (!maybeParent.value().empty()) {
            std::string errStr;
            style->parent = ResourceUtils::parseStyleParentReference(maybeParent.value(), &errStr);
            if (!style->parent) {
                mDiag->error(DiagMessage(source) << errStr);
                return false;
            }

            if (Maybe<ResourceName> transformedName =
                    parser->transformPackage(style->parent.value().name.value(), u"")) {
                style->parent.value().name = std::move(transformedName);
            }
        }

    } else {
        // No parent was specified, so try inferring it from the style name.
        std::u16string styleName = outResource->name.entry;
        size_t pos = styleName.find_last_of(u'.');
        if (pos != std::string::npos) {
            style->parentInferred = true;
            style->parent = Reference(
                    ResourceName({}, ResourceType::kStyle, styleName.substr(0, pos)));
        }
    }

    bool error = false;
    std::u16string comment;
    const size_t depth = parser->getDepth();
    while (XmlPullParser::nextChildNode(parser, depth)) {
        if (parser->getEvent() != XmlPullParser::Event::kStartElement) {
            // Skip text and comments.
            continue;
        }

        const std::u16string& elementNamespace = parser->getElementNamespace();
        const std::u16string& elementName = parser->getElementName();
        if (elementNamespace == u"" && elementName == u"item") {
            error |= !parseStyleItem(parser, style.get());

        } else if (elementNamespace.empty() &&
                (elementName == u"skip" || elementName == u"eat-comment")) {
            comment = u"";

        } else {
            mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                         << ":" << elementName << ">");
            error = true;
        }
    }

    if (error) {
        return false;
    }

    outResource->value = std::move(style);
    return true;
}

bool ResourceParser::parseArray(XmlPullParser* parser, ParsedResource* outResource,
                                uint32_t typeMask) {
    const Source source = mSource.withLine(parser->getLineNumber());
    std::unique_ptr<Array> array = util::make_unique<Array>();

    std::u16string comment;
    bool error = false;
    const size_t depth = parser->getDepth();
    while (XmlPullParser::nextChildNode(parser, depth)) {
        if (parser->getEvent() != XmlPullParser::Event::kStartElement) {
            // Skip text and comments.
            continue;
        }

        const Source itemSource = mSource.withLine(parser->getLineNumber());
        const std::u16string& elementNamespace = parser->getElementNamespace();
        const std::u16string& elementName = parser->getElementName();
        if (elementNamespace.empty() && elementName == u"item") {
            std::unique_ptr<Item> item = parseXml(parser, typeMask, kNoRawString);
            if (!item) {
                mDiag->error(DiagMessage(itemSource) << "could not parse array item");
                error = true;
                continue;
            }
            array->items.emplace_back(std::move(item));

        } else if (elementNamespace.empty() &&
                (elementName == u"skip" || elementName == u"eat-comment")) {
            comment = u"";

        } else {
            mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                         << "unknown tag <" << elementNamespace << ":" << elementName << ">");
            error = true;
        }
    }

    if (error) {
        return false;
    }

    outResource->value = std::move(array);
    return true;
}

bool ResourceParser::parsePlural(XmlPullParser* parser, ParsedResource* outResource) {
    const Source source = mSource.withLine(parser->getLineNumber());
    std::unique_ptr<Plural> plural = util::make_unique<Plural>();

    std::u16string comment;
    bool error = false;
    const size_t depth = parser->getDepth();
    while (XmlPullParser::nextChildNode(parser, depth)) {
        if (parser->getEvent() != XmlPullParser::Event::kStartElement) {
            // Skip text and comments.
            continue;
        }

        const std::u16string& elementNamespace = parser->getElementNamespace();
        const std::u16string& elementName = parser->getElementName();
        if (elementNamespace.empty() && elementName == u"item") {
            const auto endAttrIter = parser->endAttributes();
            auto attrIter = parser->findAttribute(u"", u"quantity");
            if (attrIter == endAttrIter || attrIter->value.empty()) {
                mDiag->error(DiagMessage(source) << "<item> in <plurals> requires attribute "
                             << "'quantity'");
                error = true;
                continue;
            }

            StringPiece16 trimmedQuantity = util::trimWhitespace(attrIter->value);
            size_t index = 0;
            if (trimmedQuantity == u"zero") {
                index = Plural::Zero;
            } else if (trimmedQuantity == u"one") {
                index = Plural::One;
            } else if (trimmedQuantity == u"two") {
                index = Plural::Two;
            } else if (trimmedQuantity == u"few") {
                index = Plural::Few;
            } else if (trimmedQuantity == u"many") {
                index = Plural::Many;
            } else if (trimmedQuantity == u"other") {
                index = Plural::Other;
            } else {
                mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                             << "<item> in <plural> has invalid value '" << trimmedQuantity
                             << "' for attribute 'quantity'");
                error = true;
                continue;
            }

            if (plural->values[index]) {
                mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                             << "duplicate quantity '" << trimmedQuantity << "'");
                error = true;
                continue;
            }

            if (!(plural->values[index] = parseXml(parser, android::ResTable_map::TYPE_STRING,
                                                   kNoRawString))) {
                error = true;
            }
        } else if (elementNamespace.empty() &&
                (elementName == u"skip" || elementName == u"eat-comment")) {
            comment = u"";
        } else {
            mDiag->error(DiagMessage(source) << "unknown tag <" << elementNamespace << ":"
                         << elementName << ">");
            error = true;
        }
    }

    if (error) {
        return false;
    }

    outResource->value = std::move(plural);
    return true;
}

bool ResourceParser::parseDeclareStyleable(XmlPullParser* parser, ParsedResource* outResource) {
    const Source source = mSource.withLine(parser->getLineNumber());
    std::unique_ptr<Styleable> styleable = util::make_unique<Styleable>();

    std::u16string comment;
    bool error = false;
    const size_t depth = parser->getDepth();
    while (XmlPullParser::nextChildNode(parser, depth)) {
        if (parser->getEvent() != XmlPullParser::Event::kStartElement) {
            // Ignore text and comments.
            continue;
        }

        const std::u16string& elementNamespace = parser->getElementNamespace();
        const std::u16string& elementName = parser->getElementName();
        if (elementNamespace.empty() && elementName == u"attr") {
            const auto endAttrIter = parser->endAttributes();
            auto attrIter = parser->findAttribute(u"", u"name");
            if (attrIter == endAttrIter || attrIter->value.empty()) {
                mDiag->error(DiagMessage(source) << "<attr> tag must have a 'name' attribute");
                error = true;
                continue;
            }

            ParsedResource childResource;
            childResource.name = ResourceName({}, ResourceType::kAttr, attrIter->value);
            childResource.source = mSource.withLine(parser->getLineNumber());

            if (!parseAttrImpl(parser, &childResource, true)) {
                error = true;
                continue;
            }

            styleable->entries.push_back(Reference(childResource.name));
            outResource->childResources.push_back(std::move(childResource));

        } else if (elementNamespace.empty() &&
                (elementName == u"skip" || elementName == u"eat-comment")) {
            comment = u"";

        } else {
            mDiag->error(DiagMessage(source) << "unknown tag <" << elementNamespace << ":"
                         << elementName << ">");
            error = true;
        }
    }

    if (error) {
        return false;
    }

    outResource->value = std::move(styleable);
    return true;
}

} // namespace aapt
