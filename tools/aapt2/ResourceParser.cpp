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
#include "util/Util.h"
#include "ValueVisitor.h"
#include "XmlPullParser.h"

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
                               const ConfigDescription& config) :
        mDiag(diag), mTable(table), mSource(source), mConfig(config) {
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

bool ResourceParser::parseResources(XmlPullParser* parser) {
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

        // Copy because our iterator will go out of scope when
        // we parse more XML.
        std::u16string name = maybeName.value().toString();

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

        if (elementName == u"id") {
            error |= !mTable->addResource(ResourceNameRef{ {}, ResourceType::kId, name },
                                          {}, mSource.withLine(parser->getLineNumber()),
                                          util::make_unique<Id>(), mDiag);

        } else if (elementName == u"string") {
            error |= !parseString(parser, ResourceNameRef{ {}, ResourceType::kString, name });
        } else if (elementName == u"color") {
            error |= !parseColor(parser, ResourceNameRef{ {}, ResourceType::kColor, name });
        } else if (elementName == u"drawable") {
            error |= !parseColor(parser, ResourceNameRef{ {}, ResourceType::kDrawable, name });
        } else if (elementName == u"bool") {
            error |= !parsePrimitive(parser, ResourceNameRef{ {}, ResourceType::kBool, name });
        } else if (elementName == u"integer") {
            error |= !parsePrimitive(parser, ResourceNameRef{ {}, ResourceType::kInteger, name });
        } else if (elementName == u"dimen") {
            error |= !parsePrimitive(parser, ResourceNameRef{ {}, ResourceType::kDimen, name });
        } else if (elementName == u"style") {
            error |= !parseStyle(parser, ResourceNameRef{ {}, ResourceType::kStyle, name });
        } else if (elementName == u"plurals") {
            error |= !parsePlural(parser, ResourceNameRef{ {}, ResourceType::kPlurals, name });
        } else if (elementName == u"array") {
            error |= !parseArray(parser, ResourceNameRef{ {}, ResourceType::kArray, name },
                                 android::ResTable_map::TYPE_ANY);
        } else if (elementName == u"string-array") {
            error |= !parseArray(parser, ResourceNameRef{ {}, ResourceType::kArray, name },
                                 android::ResTable_map::TYPE_STRING);
        } else if (elementName == u"integer-array") {
            error |= !parseArray(parser, ResourceNameRef{ {}, ResourceType::kArray, name },
                                 android::ResTable_map::TYPE_INTEGER);
        } else if (elementName == u"public") {
            error |= !parsePublic(parser, name);
        } else if (elementName == u"declare-styleable") {
            error |= !parseDeclareStyleable(parser,
                                            ResourceNameRef{ {}, ResourceType::kStyleable, name });
        } else if (elementName == u"attr") {
            error |= !parseAttr(parser, ResourceNameRef{ {}, ResourceType::kAttr, name });
        } else {
            mDiag->warn(DiagMessage(mSource.withLine(parser->getLineNumber()))
                        << "unknown resource type '" << elementName << "'");
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
std::unique_ptr<Item> ResourceParser::parseXml(XmlPullParser* parser, uint32_t typeMask,
                                               bool allowRawValue) {
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
        mTable->addResource(name, {}, mSource.withLine(beginXmlLine), util::make_unique<Id>(),
                            mDiag);
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

    // We can't parse this so return a RawString if we are allowed.
    if (allowRawValue) {
        return util::make_unique<RawString>(
                mTable->stringPool.makeRef(rawValue, StringPool::Context{ 1, mConfig }));
    }
    return {};
}

bool ResourceParser::parseString(XmlPullParser* parser, const ResourceNameRef& resourceName) {
    const Source source = mSource.withLine(parser->getLineNumber());

    // TODO(adamlesinski): Read "untranslateable" attribute.

    if (Maybe<StringPiece16> maybeProduct = findAttribute(parser, u"product")) {
        if (maybeProduct.value() != u"default" && maybeProduct.value() != u"phone") {
            // TODO(adamlesinski): Actually match product.
            return true;
        }
    }

    std::unique_ptr<Item> processedItem = parseXml(parser, android::ResTable_map::TYPE_STRING,
                                                   kNoRawString);
    if (!processedItem) {
        mDiag->error(DiagMessage(source) << "not a valid string");
        return false;
    }
    return mTable->addResource(resourceName, mConfig, source, std::move(processedItem),
                               mDiag);
}

bool ResourceParser::parseColor(XmlPullParser* parser, const ResourceNameRef& resourceName) {
    const Source source = mSource.withLine(parser->getLineNumber());

    std::unique_ptr<Item> item = parseXml(parser, android::ResTable_map::TYPE_COLOR, kNoRawString);
    if (!item) {
        mDiag->error(DiagMessage(source) << "invalid color");
        return false;
    }
    return mTable->addResource(resourceName, mConfig, source, std::move(item),
                               mDiag);
}

bool ResourceParser::parsePrimitive(XmlPullParser* parser, const ResourceNameRef& resourceName) {
    const Source source = mSource.withLine(parser->getLineNumber());

    uint32_t typeMask = 0;
    switch (resourceName.type) {
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

    std::unique_ptr<Item> item = parseXml(parser, typeMask, kNoRawString);
    if (!item) {
        mDiag->error(DiagMessage(source) << "invalid " << resourceName.type);
        return false;
    }
    return mTable->addResource(resourceName, mConfig, source, std::move(item),
                               mDiag);
}

bool ResourceParser::parsePublic(XmlPullParser* parser, const StringPiece16& name) {
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

    ResourceNameRef resourceName { {}, *parsedType, name };
    ResourceId resourceId;

    if (Maybe<StringPiece16> maybeId = findNonEmptyAttribute(parser, u"id")) {
        android::Res_value val;
        bool result = android::ResTable::stringToInt(maybeId.value().data(),
                                                     maybeId.value().size(), &val);
        resourceId.id = val.data;
        if (!result || !resourceId.isValid()) {
            mDiag->error(DiagMessage(source) << "invalid resource ID '" << maybeId.value()
                         << "' in <public>");
            return false;
        }
    }

    if (*parsedType == ResourceType::kId) {
        // An ID marked as public is also the definition of an ID.
        mTable->addResource(resourceName, {}, source, util::make_unique<Id>(),
                            mDiag);
    }
    return mTable->markPublic(resourceName, resourceId, source, mDiag);
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

bool ResourceParser::parseAttr(XmlPullParser* parser, const ResourceNameRef& resourceName) {
    const Source source = mSource.withLine(parser->getLineNumber());
    ResourceName actualName = resourceName.toResourceName();
    std::unique_ptr<Attribute> attr = parseAttrImpl(parser, &actualName, false);
    if (!attr) {
        return false;
    }
    return mTable->addResource(actualName, mConfig, source, std::move(attr),
                               mDiag);
}

std::unique_ptr<Attribute> ResourceParser::parseAttrImpl(XmlPullParser* parser,
                                                         ResourceName* resourceName,
                                                         bool weak) {
    uint32_t typeMask = 0;

    Maybe<StringPiece16> maybeFormat = findAttribute(parser, u"format");
    if (maybeFormat) {
        typeMask = parseFormatAttribute(maybeFormat.value());
        if (typeMask == 0) {
            mDiag->error(DiagMessage(mSource.withLine(parser->getLineNumber()))
                         << "invalid attribute format '" << maybeFormat.value() << "'");
            return {};
        }
    }

    // If this is a declaration, the package name may be in the name. Separate these out.
    // Eg. <attr name="android:text" />
    // No format attribute is allowed.
    if (weak && !maybeFormat) {
        StringPiece16 package, type, name;
        ResourceUtils::extractResourceName(resourceName->entry, &package, &type, &name);
        if (type.empty() && !package.empty()) {
            resourceName->package = package.toString();
            resourceName->entry = name.toString();
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
                if (mTable->addResource(s.value().symbol.name.value(), mConfig,
                                        mSource.withLine(parser->getLineNumber()),
                                        util::make_unique<Id>(),
                                        mDiag)) {
                    items.push_back(std::move(s.value()));
                } else {
                    error = true;
                }
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
        return {};
    }

    std::unique_ptr<Attribute> attr = util::make_unique<Attribute>(weak);
    attr->symbols.swap(items);
    attr->typeMask = typeMask ? typeMask : uint32_t(android::ResTable_map::TYPE_ANY);
    return attr;
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
            Reference(ResourceName{ {}, ResourceType::kId, maybeName.value().toString() }),
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

bool ResourceParser::parseStyle(XmlPullParser* parser, const ResourceNameRef& resourceName) {
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
        std::u16string styleName = resourceName.entry.toString();
        size_t pos = styleName.find_last_of(u'.');
        if (pos != std::string::npos) {
            style->parentInferred = true;
            style->parent = Reference(ResourceName{
                {}, ResourceType::kStyle, styleName.substr(0, pos) });
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
    return mTable->addResource(resourceName, mConfig, source, std::move(style),
                               mDiag);
}

bool ResourceParser::parseArray(XmlPullParser* parser, const ResourceNameRef& resourceName,
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
    return mTable->addResource(resourceName, mConfig, source, std::move(array),
                               mDiag);
}

bool ResourceParser::parsePlural(XmlPullParser* parser, const ResourceNameRef& resourceName) {
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
    return mTable->addResource(resourceName, mConfig, source, std::move(plural), mDiag);
}

bool ResourceParser::parseDeclareStyleable(XmlPullParser* parser,
                                           const ResourceNameRef& resourceName) {
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

            // Copy because our iterator will be invalidated.
            ResourceName attrResourceName = { {}, ResourceType::kAttr, attrIter->value };

            std::unique_ptr<Attribute> attr = parseAttrImpl(parser, &attrResourceName, true);
            if (!attr) {
                error = true;
                continue;
            }

            styleable->entries.emplace_back(attrResourceName);

            // Add the attribute to the resource table. Since it is weakly defined,
            // it won't collide.
            error |= !mTable->addResource(attrResourceName, mConfig,
                                          mSource.withLine(parser->getLineNumber()),
                                          std::move(attr), mDiag);

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
    return mTable->addResource(resourceName, mConfig, source, std::move(styleable), mDiag);
}

} // namespace aapt
