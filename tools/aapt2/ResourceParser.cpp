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

#include "Logger.h"
#include "ResourceParser.h"
#include "ResourceValues.h"
#include "ScopedXmlPullParser.h"
#include "SourceXmlPullParser.h"
#include "Util.h"
#include "XliffXmlPullParser.h"

#include <sstream>

namespace aapt {

void ResourceParser::extractResourceName(const StringPiece16& str, StringPiece16* outPackage,
                                         StringPiece16* outType, StringPiece16* outEntry) {
    const char16_t* start = str.data();
    const char16_t* end = start + str.size();
    const char16_t* current = start;
    while (current != end) {
        if (outType->size() == 0 && *current == u'/') {
            outType->assign(start, current - start);
            start = current + 1;
        } else if (outPackage->size() == 0 && *current == u':') {
            outPackage->assign(start, current - start);
            start = current + 1;
        }
        current++;
    }
    outEntry->assign(start, end - start);
}

bool ResourceParser::tryParseReference(const StringPiece16& str, ResourceNameRef* outRef,
                                       bool* outCreate, bool* outPrivate) {
    StringPiece16 trimmedStr(util::trimWhitespace(str));
    if (trimmedStr.empty()) {
        return false;
    }

    if (trimmedStr.data()[0] == u'@') {
        size_t offset = 1;
        *outCreate = false;
        if (trimmedStr.data()[1] == u'+') {
            *outCreate = true;
            offset += 1;
        } else if (trimmedStr.data()[1] == u'*') {
            *outPrivate = true;
            offset += 1;
        }
        StringPiece16 package;
        StringPiece16 type;
        StringPiece16 entry;
        extractResourceName(trimmedStr.substr(offset, trimmedStr.size() - offset),
                            &package, &type, &entry);

        const ResourceType* parsedType = parseResourceType(type);
        if (!parsedType) {
            return false;
        }

        if (*outCreate && *parsedType != ResourceType::kId) {
            return false;
        }

        outRef->package = package;
        outRef->type = *parsedType;
        outRef->entry = entry;
        return true;
    }
    return false;
}

bool ResourceParser::tryParseAttributeReference(const StringPiece16& str,
                                                ResourceNameRef* outRef) {
    StringPiece16 trimmedStr(util::trimWhitespace(str));
    if (trimmedStr.empty()) {
        return false;
    }

    if (*trimmedStr.data() == u'?') {
        StringPiece16 package;
        StringPiece16 type;
        StringPiece16 entry;
        extractResourceName(trimmedStr.substr(1, trimmedStr.size() - 1), &package, &type, &entry);

        if (!type.empty() && type != u"attr") {
            return false;
        }

        outRef->package = package;
        outRef->type = ResourceType::kAttr;
        outRef->entry = entry;
        return true;
    }
    return false;
}

/*
 * Style parent's are a bit different. We accept the following formats:
 *
 * @[package:]style/<entry>
 * ?[package:]style/<entry>
 * <package>:[style/]<entry>
 * [package:style/]<entry>
 */
bool ResourceParser::parseStyleParentReference(const StringPiece16& str, Reference* outReference,
                                               std::string* outError) {
    if (str.empty()) {
        return true;
    }

    StringPiece16 name = str;

    bool hasLeadingIdentifiers = false;
    bool privateRef = false;

    // Skip over these identifiers. A style's parent is a normal reference.
    if (name.data()[0] == u'@' || name.data()[0] == u'?') {
        hasLeadingIdentifiers = true;
        name = name.substr(1, name.size() - 1);
        if (name.data()[0] == u'*') {
            privateRef = true;
            name = name.substr(1, name.size() - 1);
        }
    }

    ResourceNameRef ref;
    ref.type = ResourceType::kStyle;

    StringPiece16 typeStr;
    extractResourceName(name, &ref.package, &typeStr, &ref.entry);
    if (!typeStr.empty()) {
        // If we have a type, make sure it is a Style.
        const ResourceType* parsedType = parseResourceType(typeStr);
        if (!parsedType || *parsedType != ResourceType::kStyle) {
            std::stringstream err;
            err << "invalid resource type '" << typeStr << "' for parent of style";
            *outError = err.str();
            return false;
        }
    } else {
        // No type was defined, this should not have a leading identifier.
        if (hasLeadingIdentifiers) {
            std::stringstream err;
            err << "invalid parent reference '" << str << "'";
            *outError = err.str();
            return false;
        }
    }

    if (!hasLeadingIdentifiers && ref.package.empty() && !typeStr.empty()) {
        std::stringstream err;
        err << "invalid parent reference '" << str << "'";
        *outError = err.str();
        return false;
    }

    outReference->name = ref.toResourceName();
    outReference->privateReference = privateRef;
    return true;
}

std::unique_ptr<Reference> ResourceParser::tryParseReference(const StringPiece16& str,
                                                             bool* outCreate) {
    ResourceNameRef ref;
    bool privateRef = false;
    if (tryParseReference(str, &ref, outCreate, &privateRef)) {
        std::unique_ptr<Reference> value = util::make_unique<Reference>(ref);
        value->privateReference = privateRef;
        return value;
    }

    if (tryParseAttributeReference(str, &ref)) {
        *outCreate = false;
        return util::make_unique<Reference>(ref, Reference::Type::kAttribute);
    }
    return {};
}

std::unique_ptr<BinaryPrimitive> ResourceParser::tryParseNullOrEmpty(const StringPiece16& str) {
    StringPiece16 trimmedStr(util::trimWhitespace(str));
    android::Res_value value = {};
    if (trimmedStr == u"@null") {
        // TYPE_NULL with data set to 0 is interpreted by the runtime as an error.
        // Instead we set the data type to TYPE_REFERENCE with a value of 0.
        value.dataType = android::Res_value::TYPE_REFERENCE;
    } else if (trimmedStr == u"@empty") {
        // TYPE_NULL with value of DATA_NULL_EMPTY is handled fine by the runtime.
        value.dataType = android::Res_value::TYPE_NULL;
        value.data = android::Res_value::DATA_NULL_EMPTY;
    } else {
        return {};
    }
    return util::make_unique<BinaryPrimitive>(value);
}

std::unique_ptr<BinaryPrimitive> ResourceParser::tryParseEnumSymbol(const Attribute& enumAttr,
                                                                    const StringPiece16& str) {
    StringPiece16 trimmedStr(util::trimWhitespace(str));
    for (const auto& entry : enumAttr.symbols) {
        // Enum symbols are stored as @package:id/symbol resources,
        // so we need to match against the 'entry' part of the identifier.
        const ResourceName& enumSymbolResourceName = entry.symbol.name;
        if (trimmedStr == enumSymbolResourceName.entry) {
            android::Res_value value = {};
            value.dataType = android::Res_value::TYPE_INT_DEC;
            value.data = entry.value;
            return util::make_unique<BinaryPrimitive>(value);
        }
    }
    return {};
}

std::unique_ptr<BinaryPrimitive> ResourceParser::tryParseFlagSymbol(const Attribute& flagAttr,
                                                                    const StringPiece16& str) {
    android::Res_value flags = {};
    flags.dataType = android::Res_value::TYPE_INT_DEC;

    for (StringPiece16 part : util::tokenize(str, u'|')) {
        StringPiece16 trimmedPart = util::trimWhitespace(part);

        bool flagSet = false;
        for (const auto& entry : flagAttr.symbols) {
            // Flag symbols are stored as @package:id/symbol resources,
            // so we need to match against the 'entry' part of the identifier.
            const ResourceName& flagSymbolResourceName = entry.symbol.name;
            if (trimmedPart == flagSymbolResourceName.entry) {
                flags.data |= entry.value;
                flagSet = true;
                break;
            }
        }

        if (!flagSet) {
            return {};
        }
    }
    return util::make_unique<BinaryPrimitive>(flags);
}

static uint32_t parseHex(char16_t c, bool* outError) {
   if (c >= u'0' && c <= u'9') {
        return c - u'0';
    } else if (c >= u'a' && c <= u'f') {
        return c - u'a' + 0xa;
    } else if (c >= u'A' && c <= u'F') {
        return c - u'A' + 0xa;
    } else {
        *outError = true;
        return 0xffffffffu;
    }
}

std::unique_ptr<BinaryPrimitive> ResourceParser::tryParseColor(const StringPiece16& str) {
    StringPiece16 colorStr(util::trimWhitespace(str));
    const char16_t* start = colorStr.data();
    const size_t len = colorStr.size();
    if (len == 0 || start[0] != u'#') {
        return {};
    }

    android::Res_value value = {};
    bool error = false;
    if (len == 4) {
        value.dataType = android::Res_value::TYPE_INT_COLOR_RGB4;
        value.data = 0xff000000u;
        value.data |= parseHex(start[1], &error) << 20;
        value.data |= parseHex(start[1], &error) << 16;
        value.data |= parseHex(start[2], &error) << 12;
        value.data |= parseHex(start[2], &error) << 8;
        value.data |= parseHex(start[3], &error) << 4;
        value.data |= parseHex(start[3], &error);
    } else if (len == 5) {
        value.dataType = android::Res_value::TYPE_INT_COLOR_ARGB4;
        value.data |= parseHex(start[1], &error) << 28;
        value.data |= parseHex(start[1], &error) << 24;
        value.data |= parseHex(start[2], &error) << 20;
        value.data |= parseHex(start[2], &error) << 16;
        value.data |= parseHex(start[3], &error) << 12;
        value.data |= parseHex(start[3], &error) << 8;
        value.data |= parseHex(start[4], &error) << 4;
        value.data |= parseHex(start[4], &error);
    } else if (len == 7) {
        value.dataType = android::Res_value::TYPE_INT_COLOR_RGB8;
        value.data = 0xff000000u;
        value.data |= parseHex(start[1], &error) << 20;
        value.data |= parseHex(start[2], &error) << 16;
        value.data |= parseHex(start[3], &error) << 12;
        value.data |= parseHex(start[4], &error) << 8;
        value.data |= parseHex(start[5], &error) << 4;
        value.data |= parseHex(start[6], &error);
    } else if (len == 9) {
        value.dataType = android::Res_value::TYPE_INT_COLOR_ARGB8;
        value.data |= parseHex(start[1], &error) << 28;
        value.data |= parseHex(start[2], &error) << 24;
        value.data |= parseHex(start[3], &error) << 20;
        value.data |= parseHex(start[4], &error) << 16;
        value.data |= parseHex(start[5], &error) << 12;
        value.data |= parseHex(start[6], &error) << 8;
        value.data |= parseHex(start[7], &error) << 4;
        value.data |= parseHex(start[8], &error);
    } else {
        return {};
    }
    return error ? std::unique_ptr<BinaryPrimitive>() : util::make_unique<BinaryPrimitive>(value);
}

std::unique_ptr<BinaryPrimitive> ResourceParser::tryParseBool(const StringPiece16& str) {
    StringPiece16 trimmedStr(util::trimWhitespace(str));
    uint32_t data = 0;
    if (trimmedStr == u"true" || trimmedStr == u"TRUE") {
        data = 0xffffffffu;
    } else if (trimmedStr != u"false" && trimmedStr != u"FALSE") {
        return {};
    }
    android::Res_value value = {};
    value.dataType = android::Res_value::TYPE_INT_BOOLEAN;
    value.data = data;
    return util::make_unique<BinaryPrimitive>(value);
}

std::unique_ptr<BinaryPrimitive> ResourceParser::tryParseInt(const StringPiece16& str) {
    android::Res_value value;
    if (!android::ResTable::stringToInt(str.data(), str.size(), &value)) {
        return {};
    }
    return util::make_unique<BinaryPrimitive>(value);
}

std::unique_ptr<BinaryPrimitive> ResourceParser::tryParseFloat(const StringPiece16& str) {
    android::Res_value value;
    if (!android::ResTable::stringToFloat(str.data(), str.size(), &value)) {
        return {};
    }
    return util::make_unique<BinaryPrimitive>(value);
}

uint32_t ResourceParser::androidTypeToAttributeTypeMask(uint16_t type) {
    switch (type) {
        case android::Res_value::TYPE_NULL:
        case android::Res_value::TYPE_REFERENCE:
        case android::Res_value::TYPE_ATTRIBUTE:
        case android::Res_value::TYPE_DYNAMIC_REFERENCE:
            return android::ResTable_map::TYPE_REFERENCE;

        case android::Res_value::TYPE_STRING:
            return android::ResTable_map::TYPE_STRING;

        case android::Res_value::TYPE_FLOAT:
            return android::ResTable_map::TYPE_FLOAT;

        case android::Res_value::TYPE_DIMENSION:
            return android::ResTable_map::TYPE_DIMENSION;

        case android::Res_value::TYPE_FRACTION:
            return android::ResTable_map::TYPE_FRACTION;

        case android::Res_value::TYPE_INT_DEC:
        case android::Res_value::TYPE_INT_HEX:
            return android::ResTable_map::TYPE_INTEGER |
                    android::ResTable_map::TYPE_ENUM |
                    android::ResTable_map::TYPE_FLAGS;

        case android::Res_value::TYPE_INT_BOOLEAN:
            return android::ResTable_map::TYPE_BOOLEAN;

        case android::Res_value::TYPE_INT_COLOR_ARGB8:
        case android::Res_value::TYPE_INT_COLOR_RGB8:
        case android::Res_value::TYPE_INT_COLOR_ARGB4:
        case android::Res_value::TYPE_INT_COLOR_RGB4:
            return android::ResTable_map::TYPE_COLOR;

        default:
            return 0;
    };
}

std::unique_ptr<Item> ResourceParser::parseItemForAttribute(
        const StringPiece16& value, uint32_t typeMask,
        std::function<void(const ResourceName&)> onCreateReference) {
    std::unique_ptr<BinaryPrimitive> nullOrEmpty = tryParseNullOrEmpty(value);
    if (nullOrEmpty) {
        return std::move(nullOrEmpty);
    }

    bool create = false;
    std::unique_ptr<Reference> reference = tryParseReference(value, &create);
    if (reference) {
        if (create && onCreateReference) {
            onCreateReference(reference->name);
        }
        return std::move(reference);
    }

    if (typeMask & android::ResTable_map::TYPE_COLOR) {
        // Try parsing this as a color.
        std::unique_ptr<BinaryPrimitive> color = tryParseColor(value);
        if (color) {
            return std::move(color);
        }
    }

    if (typeMask & android::ResTable_map::TYPE_BOOLEAN) {
        // Try parsing this as a boolean.
        std::unique_ptr<BinaryPrimitive> boolean = tryParseBool(value);
        if (boolean) {
            return std::move(boolean);
        }
    }

    if (typeMask & android::ResTable_map::TYPE_INTEGER) {
        // Try parsing this as an integer.
        std::unique_ptr<BinaryPrimitive> integer = tryParseInt(value);
        if (integer) {
            return std::move(integer);
        }
    }

    const uint32_t floatMask = android::ResTable_map::TYPE_FLOAT |
            android::ResTable_map::TYPE_DIMENSION |
            android::ResTable_map::TYPE_FRACTION;
    if (typeMask & floatMask) {
        // Try parsing this as a float.
        std::unique_ptr<BinaryPrimitive> floatingPoint = tryParseFloat(value);
        if (floatingPoint) {
            if (typeMask & androidTypeToAttributeTypeMask(floatingPoint->value.dataType)) {
                return std::move(floatingPoint);
            }
        }
    }
    return {};
}

/**
 * We successively try to parse the string as a resource type that the Attribute
 * allows.
 */
std::unique_ptr<Item> ResourceParser::parseItemForAttribute(
        const StringPiece16& str, const Attribute& attr,
        std::function<void(const ResourceName&)> onCreateReference) {
    const uint32_t typeMask = attr.typeMask;
    std::unique_ptr<Item> value = parseItemForAttribute(str, typeMask, onCreateReference);
    if (value) {
        return value;
    }

    if (typeMask & android::ResTable_map::TYPE_ENUM) {
        // Try parsing this as an enum.
        std::unique_ptr<BinaryPrimitive> enumValue = tryParseEnumSymbol(attr, str);
        if (enumValue) {
            return std::move(enumValue);
        }
    }

    if (typeMask & android::ResTable_map::TYPE_FLAGS) {
        // Try parsing this as a flag.
        std::unique_ptr<BinaryPrimitive> flagValue = tryParseFlagSymbol(attr, str);
        if (flagValue) {
            return std::move(flagValue);
        }
    }
    return {};
}

ResourceParser::ResourceParser(const std::shared_ptr<ResourceTable>& table, const Source& source,
                               const ConfigDescription& config,
                               const std::shared_ptr<XmlPullParser>& parser) :
        mTable(table), mSource(source), mConfig(config), mLogger(source),
        mParser(std::make_shared<XliffXmlPullParser>(parser)) {
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
            if (parser->getElementNamespace().size() > 0) {
                mLogger.warn(parser->getLineNumber())
                        << "skipping element '"
                        << parser->getElementName()
                        << "' with unknown namespace '"
                        << parser->getElementNamespace()
                        << "'."
                        << std::endl;
                XmlPullParser::skipCurrentElement(parser);
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
                mLogger.error(parser->getLineNumber())
                        << "style string '"
                        << builder.str()
                        << "' is too long."
                        << std::endl;
                return false;
            }
            spanStack.push_back(Span{ spanName, static_cast<uint32_t>(builder.str().size()) });

        } else if (event == XmlPullParser::Event::kComment) {
            // Skip
        } else {
            mLogger.warn(parser->getLineNumber())
                    << "unknown event "
                    << event
                    << "."
                    << std::endl;
        }
    }
    assert(spanStack.empty() && "spans haven't been fully processed");

    outStyleString->str = builder.str();
    return true;
}

bool ResourceParser::parse() {
    while (XmlPullParser::isGoodEvent(mParser->next())) {
        if (mParser->getEvent() != XmlPullParser::Event::kStartElement) {
            continue;
        }

        ScopedXmlPullParser parser(mParser.get());
        if (!parser.getElementNamespace().empty() ||
                parser.getElementName() != u"resources") {
            mLogger.error(parser.getLineNumber())
                    << "root element must be <resources> in the global namespace."
                    << std::endl;
            return false;
        }

        if (!parseResources(&parser)) {
            return false;
        }
    }

    if (mParser->getEvent() == XmlPullParser::Event::kBadDocument) {
        mLogger.error(mParser->getLineNumber())
                << mParser->getLastError()
                << std::endl;
        return false;
    }
    return true;
}

bool ResourceParser::parseResources(XmlPullParser* parser) {
    bool success = true;

    std::u16string comment;
    while (XmlPullParser::isGoodEvent(parser->next())) {
        const XmlPullParser::Event event = parser->getEvent();
        if (event == XmlPullParser::Event::kComment) {
            comment = parser->getComment();
            continue;
        }

        if (event == XmlPullParser::Event::kText) {
            if (!util::trimWhitespace(parser->getText()).empty()) {
                comment = u"";
            }
            continue;
        }

        if (event != XmlPullParser::Event::kStartElement) {
            continue;
        }

        ScopedXmlPullParser childParser(parser);

        if (!childParser.getElementNamespace().empty()) {
            // Skip unknown namespace.
            continue;
        }

        StringPiece16 name = childParser.getElementName();
        if (name == u"skip" || name == u"eat-comment") {
            continue;
        }

        if (name == u"private-symbols") {
            // Handle differently.
            mLogger.note(childParser.getLineNumber())
                    << "got a <private-symbols> tag."
                    << std::endl;
            continue;
        }

        const auto endAttrIter = childParser.endAttributes();
        auto attrIter = childParser.findAttribute(u"", u"name");
        if (attrIter == endAttrIter || attrIter->value.empty()) {
            mLogger.error(childParser.getLineNumber())
                    << "<" << name << "> tag must have a 'name' attribute."
                    << std::endl;
            success = false;
            continue;
        }

        // Copy because our iterator will go out of scope when
        // we parse more XML.
        std::u16string attributeName = attrIter->value;

        if (name == u"item") {
            // Items simply have their type encoded in the type attribute.
            auto typeIter = childParser.findAttribute(u"", u"type");
            if (typeIter == endAttrIter || typeIter->value.empty()) {
                mLogger.error(childParser.getLineNumber())
                        << "<item> must have a 'type' attribute."
                        << std::endl;
                success = false;
                continue;
            }
            name = typeIter->value;
        }

        if (name == u"id") {
            success &= mTable->addResource(ResourceNameRef{ {}, ResourceType::kId, attributeName },
                                           {}, mSource.line(childParser.getLineNumber()),
                                           util::make_unique<Id>());
        } else if (name == u"string") {
            success &= parseString(&childParser,
                                   ResourceNameRef{ {}, ResourceType::kString, attributeName });
        } else if (name == u"color") {
            success &= parseColor(&childParser,
                                  ResourceNameRef{ {}, ResourceType::kColor, attributeName });
        } else if (name == u"drawable") {
            success &= parseColor(&childParser,
                                  ResourceNameRef{ {}, ResourceType::kDrawable, attributeName });
        } else if (name == u"bool") {
            success &= parsePrimitive(&childParser,
                                      ResourceNameRef{ {}, ResourceType::kBool, attributeName });
        } else if (name == u"integer") {
            success &= parsePrimitive(
                    &childParser,
                    ResourceNameRef{ {}, ResourceType::kInteger, attributeName });
        } else if (name == u"dimen") {
            success &= parsePrimitive(&childParser,
                                      ResourceNameRef{ {}, ResourceType::kDimen, attributeName });
        } else if (name == u"fraction") {
//          success &= parsePrimitive(
//                  &childParser,
//                  ResourceNameRef{ {}, ResourceType::kFraction, attributeName });
        } else if (name == u"style") {
            success &= parseStyle(&childParser,
                                  ResourceNameRef{ {}, ResourceType::kStyle, attributeName });
        } else if (name == u"plurals") {
            success &= parsePlural(&childParser,
                                   ResourceNameRef{ {}, ResourceType::kPlurals, attributeName });
        } else if (name == u"array") {
            success &= parseArray(&childParser,
                                  ResourceNameRef{ {}, ResourceType::kArray, attributeName },
                                  android::ResTable_map::TYPE_ANY);
        } else if (name == u"string-array") {
            success &= parseArray(&childParser,
                                  ResourceNameRef{ {}, ResourceType::kArray, attributeName },
                                  android::ResTable_map::TYPE_STRING);
        } else if (name == u"integer-array") {
            success &= parseArray(&childParser,
                                  ResourceNameRef{ {}, ResourceType::kArray, attributeName },
                                  android::ResTable_map::TYPE_INTEGER);
        } else if (name == u"public") {
            success &= parsePublic(&childParser, attributeName);
        } else if (name == u"declare-styleable") {
            success &= parseDeclareStyleable(
                    &childParser,
                    ResourceNameRef{ {}, ResourceType::kStyleable, attributeName });
        } else if (name == u"attr") {
            success &= parseAttr(&childParser,
                                 ResourceNameRef{ {}, ResourceType::kAttr, attributeName });
        } else if (name == u"bag") {
        } else if (name == u"public-padding") {
        } else if (name == u"java-symbol") {
        } else if (name == u"add-resource") {
       }
    }

    if (parser->getEvent() == XmlPullParser::Event::kBadDocument) {
        mLogger.error(parser->getLineNumber())
                << parser->getLastError()
                << std::endl;
        return false;
    }
    return success;
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

    StringPool& pool = mTable->getValueStringPool();

    if (!styleString.spans.empty()) {
        // This can only be a StyledString.
        return util::make_unique<StyledString>(
                pool.makeRef(styleString, StringPool::Context{ 1, mConfig }));
    }

    auto onCreateReference = [&](const ResourceName& name) {
        // name.package can be empty here, as it will assume the package name of the table.
        mTable->addResource(name, {}, mSource.line(beginXmlLine), util::make_unique<Id>());
    };

    // Process the raw value.
    std::unique_ptr<Item> processedItem = parseItemForAttribute(rawValue, typeMask,
                                                                onCreateReference);
    if (processedItem) {
        // Fix up the reference.
        visitFunc<Reference>(*processedItem, [&](Reference& ref) {
            if (!ref.name.package.empty()) {
                // The package name was set, so lookup its alias.
                parser->applyPackageAlias(&ref.name.package, mTable->getPackage());
            } else {
                // The package name was left empty, so it assumes the default package
                // without alias lookup.
                ref.name.package = mTable->getPackage();
            }
        });
        return processedItem;
    }

    // Try making a regular string.
    if (typeMask & android::ResTable_map::TYPE_STRING) {
        // Use the trimmed, escaped string.
        return util::make_unique<String>(
                pool.makeRef(styleString.str, StringPool::Context{ 1, mConfig }));
    }

    // We can't parse this so return a RawString if we are allowed.
    if (allowRawValue) {
        return util::make_unique<RawString>(
                pool.makeRef(rawValue, StringPool::Context{ 1, mConfig }));
    }
    return {};
}

bool ResourceParser::parseString(XmlPullParser* parser, const ResourceNameRef& resourceName) {
    const SourceLine source = mSource.line(parser->getLineNumber());

    // Mark the string as untranslateable if needed.
    const auto endAttrIter = parser->endAttributes();
    auto attrIter = parser->findAttribute(u"", u"untranslateable");
    // bool untranslateable = attrIter != endAttrIter;
    // TODO(adamlesinski): Do something with this (mark the string).

    // Deal with the product.
    attrIter = parser->findAttribute(u"", u"product");
    if (attrIter != endAttrIter) {
        if (attrIter->value != u"default" && attrIter->value != u"phone") {
            // TODO(adamlesinski): Match products.
            return true;
        }
    }

    std::unique_ptr<Item> processedItem = parseXml(parser, android::ResTable_map::TYPE_STRING,
                                                   kNoRawString);
    if (!processedItem) {
        mLogger.error(source.line)
                << "not a valid string."
                << std::endl;
        return false;
    }

    return mTable->addResource(resourceName, mConfig, source, std::move(processedItem));
}

bool ResourceParser::parseColor(XmlPullParser* parser, const ResourceNameRef& resourceName) {
    const SourceLine source = mSource.line(parser->getLineNumber());

    std::unique_ptr<Item> item = parseXml(parser, android::ResTable_map::TYPE_COLOR, kNoRawString);
    if (!item) {
        mLogger.error(source.line) << "invalid color." << std::endl;
        return false;
    }
    return mTable->addResource(resourceName, mConfig, source, std::move(item));
}

bool ResourceParser::parsePrimitive(XmlPullParser* parser, const ResourceNameRef& resourceName) {
    const SourceLine source = mSource.line(parser->getLineNumber());

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
        mLogger.error(source.line)
                << "invalid "
                << resourceName.type
                << "."
                << std::endl;
        return false;
    }

    return mTable->addResource(resourceName, mConfig, source, std::move(item));
}

bool ResourceParser::parsePublic(XmlPullParser* parser, const StringPiece16& name) {
    const SourceLine source = mSource.line(parser->getLineNumber());

    const auto endAttrIter = parser->endAttributes();
    const auto typeAttrIter = parser->findAttribute(u"", u"type");
    if (typeAttrIter == endAttrIter || typeAttrIter->value.empty()) {
        mLogger.error(source.line)
                << "<public> must have a 'type' attribute."
                << std::endl;
        return false;
    }

    const ResourceType* parsedType = parseResourceType(typeAttrIter->value);
    if (!parsedType) {
        mLogger.error(source.line)
                << "invalid resource type '"
                << typeAttrIter->value
                << "' in <public>."
                << std::endl;
        return false;
    }

    ResourceNameRef resourceName { {}, *parsedType, name };
    ResourceId resourceId;

    const auto idAttrIter = parser->findAttribute(u"", u"id");
    if (idAttrIter != endAttrIter && !idAttrIter->value.empty()) {
        android::Res_value val;
        bool result = android::ResTable::stringToInt(idAttrIter->value.data(),
                                                     idAttrIter->value.size(), &val);
        resourceId.id = val.data;
        if (!result || !resourceId.isValid()) {
            mLogger.error(source.line)
                    << "invalid resource ID '"
                    << idAttrIter->value
                    << "' in <public>."
                    << std::endl;
            return false;
        }
    }

    if (*parsedType == ResourceType::kId) {
        // An ID marked as public is also the definition of an ID.
        mTable->addResource(resourceName, {}, source, util::make_unique<Id>());
    }

    return mTable->markPublic(resourceName, resourceId, source);
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
    const SourceLine source = mSource.line(parser->getLineNumber());
    ResourceName actualName = resourceName.toResourceName();
    std::unique_ptr<Attribute> attr = parseAttrImpl(parser, &actualName, false);
    if (!attr) {
        return false;
    }
    return mTable->addResource(actualName, mConfig, source, std::move(attr));
}

std::unique_ptr<Attribute> ResourceParser::parseAttrImpl(XmlPullParser* parser,
                                                         ResourceName* resourceName,
                                                         bool weak) {
    uint32_t typeMask = 0;

    const auto endAttrIter = parser->endAttributes();
    const auto formatAttrIter = parser->findAttribute(u"", u"format");
    if (formatAttrIter != endAttrIter) {
        typeMask = parseFormatAttribute(formatAttrIter->value);
        if (typeMask == 0) {
            mLogger.error(parser->getLineNumber())
                    << "invalid attribute format '"
                    << formatAttrIter->value
                    << "'."
                    << std::endl;
            return {};
        }
    }

    // If this is a declaration, the package name may be in the name. Separate these out.
    // Eg. <attr name="android:text" />
    // No format attribute is allowed.
    if (weak && formatAttrIter == endAttrIter) {
        StringPiece16 package, type, name;
        extractResourceName(resourceName->entry, &package, &type, &name);
        if (type.empty() && !package.empty()) {
            resourceName->package = package.toString();
            resourceName->entry = name.toString();
        }
    }

    std::vector<Attribute::Symbol> items;

    bool error = false;
    while (XmlPullParser::isGoodEvent(parser->next())) {
        if (parser->getEvent() != XmlPullParser::Event::kStartElement) {
            continue;
        }

        ScopedXmlPullParser childParser(parser);

        const std::u16string& name = childParser.getElementName();
        if (!childParser.getElementNamespace().empty()
                || (name != u"flag" && name != u"enum")) {
            mLogger.error(childParser.getLineNumber())
                    << "unexpected tag <"
                    << name
                    << "> in <attr>."
                    << std::endl;
            error = true;
            continue;
        }

        if (name == u"enum") {
            if (typeMask & android::ResTable_map::TYPE_FLAGS) {
                mLogger.error(childParser.getLineNumber())
                        << "can not define an <enum>; already defined a <flag>."
                        << std::endl;
                error = true;
                continue;
            }
            typeMask |= android::ResTable_map::TYPE_ENUM;
        } else if (name == u"flag") {
            if (typeMask & android::ResTable_map::TYPE_ENUM) {
                mLogger.error(childParser.getLineNumber())
                        << "can not define a <flag>; already defined an <enum>."
                        << std::endl;
                error = true;
                continue;
            }
            typeMask |= android::ResTable_map::TYPE_FLAGS;
        }

        Attribute::Symbol item;
        if (parseEnumOrFlagItem(&childParser, name, &item)) {
            if (!mTable->addResource(item.symbol.name, mConfig,
                                     mSource.line(childParser.getLineNumber()),
                                     util::make_unique<Id>())) {
                error = true;
            } else {
                items.push_back(std::move(item));
            }
        } else {
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

bool ResourceParser::parseEnumOrFlagItem(XmlPullParser* parser, const StringPiece16& tag,
                                         Attribute::Symbol* outSymbol) {
    const auto attrIterEnd = parser->endAttributes();
    const auto nameAttrIter = parser->findAttribute(u"", u"name");
    if (nameAttrIter == attrIterEnd || nameAttrIter->value.empty()) {
        mLogger.error(parser->getLineNumber())
                << "no attribute 'name' found for tag <" << tag << ">."
                << std::endl;
        return false;
    }

    const auto valueAttrIter = parser->findAttribute(u"", u"value");
    if (valueAttrIter == attrIterEnd || valueAttrIter->value.empty()) {
        mLogger.error(parser->getLineNumber())
                << "no attribute 'value' found for tag <" << tag << ">."
                << std::endl;
        return false;
    }

    android::Res_value val;
    if (!android::ResTable::stringToInt(valueAttrIter->value.data(),
                                        valueAttrIter->value.size(), &val)) {
        mLogger.error(parser->getLineNumber())
                << "invalid value '"
                << valueAttrIter->value
                << "' for <" << tag << ">; must be an integer."
                << std::endl;
        return false;
    }

    outSymbol->symbol.name = ResourceName {
            mTable->getPackage(), ResourceType::kId, nameAttrIter->value };
    outSymbol->value = val.data;
    return true;
}

static bool parseXmlAttributeName(StringPiece16 str, ResourceName* outName) {
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

    outName->package = package.toString();
    outName->type = ResourceType::kAttr;
    if (name.size() == 0) {
        outName->entry = str.toString();
    } else {
        outName->entry = name.toString();
    }
    return true;
}

bool ResourceParser::parseUntypedItem(XmlPullParser* parser, Style& style) {
    const auto endAttrIter = parser->endAttributes();
    const auto nameAttrIter = parser->findAttribute(u"", u"name");
    if (nameAttrIter == endAttrIter || nameAttrIter->value.empty()) {
        mLogger.error(parser->getLineNumber())
                << "<item> must have a 'name' attribute."
                << std::endl;
        return false;
    }

    ResourceName key;
    if (!parseXmlAttributeName(nameAttrIter->value, &key)) {
        mLogger.error(parser->getLineNumber())
                << "invalid attribute name '"
                << nameAttrIter->value
                << "'."
                << std::endl;
        return false;
    }

    if (!key.package.empty()) {
        // We have a package name set, so lookup its alias.
        parser->applyPackageAlias(&key.package, mTable->getPackage());
    } else {
        // The package name was omitted, so use the default package name with
        // no alias lookup.
        key.package = mTable->getPackage();
    }

    std::unique_ptr<Item> value = parseXml(parser, 0, kAllowRawString);
    if (!value) {
        return false;
    }

    style.entries.push_back(Style::Entry{ Reference(key), std::move(value) });
    return true;
}

bool ResourceParser::parseStyle(XmlPullParser* parser, const ResourceNameRef& resourceName) {
    const SourceLine source = mSource.line(parser->getLineNumber());
    std::unique_ptr<Style> style = util::make_unique<Style>();

    const auto endAttrIter = parser->endAttributes();
    const auto parentAttrIter = parser->findAttribute(u"", u"parent");
    if (parentAttrIter != endAttrIter) {
        std::string errStr;
        if (!parseStyleParentReference(parentAttrIter->value, &style->parent, &errStr)) {
            mLogger.error(source.line) << errStr << "." << std::endl;
            return false;
        }

        if (!style->parent.name.package.empty()) {
            // Try to interpret the package name as an alias. These take precedence.
            parser->applyPackageAlias(&style->parent.name.package, mTable->getPackage());
        } else {
            // If no package is specified, this can not be an alias and is the local package.
            style->parent.name.package = mTable->getPackage();
        }
    } else {
        // No parent was specified, so try inferring it from the style name.
        std::u16string styleName = resourceName.entry.toString();
        size_t pos = styleName.find_last_of(u'.');
        if (pos != std::string::npos) {
            style->parentInferred = true;
            style->parent.name.package = mTable->getPackage();
            style->parent.name.type = ResourceType::kStyle;
            style->parent.name.entry = styleName.substr(0, pos);
        }
    }

    bool success = true;
    while (XmlPullParser::isGoodEvent(parser->next())) {
        if (parser->getEvent() != XmlPullParser::Event::kStartElement) {
            continue;
        }

        ScopedXmlPullParser childParser(parser);
        const std::u16string& name = childParser.getElementName();
        if (name == u"item") {
            success &= parseUntypedItem(&childParser, *style);
        } else {
            mLogger.error(childParser.getLineNumber())
                    << "unexpected tag <"
                    << name
                    << "> in <style> resource."
                    << std::endl;
            success = false;
        }
    }

    if (!success) {
        return false;
    }

    return mTable->addResource(resourceName, mConfig, source, std::move(style));
}

bool ResourceParser::parseArray(XmlPullParser* parser, const ResourceNameRef& resourceName,
                                uint32_t typeMask) {
    const SourceLine source = mSource.line(parser->getLineNumber());
    std::unique_ptr<Array> array = util::make_unique<Array>();

    bool error = false;
    while (XmlPullParser::isGoodEvent(parser->next())) {
        if (parser->getEvent() != XmlPullParser::Event::kStartElement) {
            continue;
        }

        ScopedXmlPullParser childParser(parser);

        if (childParser.getElementName() != u"item") {
            mLogger.error(childParser.getLineNumber())
                    << "unexpected tag <"
                    << childParser.getElementName()
                    << "> in <array> resource."
                    << std::endl;
            error = true;
            continue;
        }

        std::unique_ptr<Item> item = parseXml(&childParser, typeMask, kNoRawString);
        if (!item) {
            error = true;
            continue;
        }
        array->items.emplace_back(std::move(item));
    }

    if (error) {
        return false;
    }

    return mTable->addResource(resourceName, mConfig, source, std::move(array));
}

bool ResourceParser::parsePlural(XmlPullParser* parser, const ResourceNameRef& resourceName) {
    const SourceLine source = mSource.line(parser->getLineNumber());
    std::unique_ptr<Plural> plural = util::make_unique<Plural>();

    bool success = true;
    while (XmlPullParser::isGoodEvent(parser->next())) {
        if (parser->getEvent() != XmlPullParser::Event::kStartElement) {
            continue;
        }

        ScopedXmlPullParser childParser(parser);

        if (!childParser.getElementNamespace().empty() ||
                childParser.getElementName() != u"item") {
            success = false;
            continue;
        }

        const auto endAttrIter = childParser.endAttributes();
        auto attrIter = childParser.findAttribute(u"", u"quantity");
        if (attrIter == endAttrIter || attrIter->value.empty()) {
            mLogger.error(childParser.getLineNumber())
                    << "<item> in <plurals> requires attribute 'quantity'."
                    << std::endl;
            success = false;
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
            mLogger.error(childParser.getLineNumber())
                    << "<item> in <plural> has invalid value '"
                    << trimmedQuantity
                    << "' for attribute 'quantity'."
                    << std::endl;
            success = false;
            continue;
        }

        if (plural->values[index]) {
            mLogger.error(childParser.getLineNumber())
                    << "duplicate quantity '"
                    << trimmedQuantity
                    << "'."
                    << std::endl;
            success = false;
            continue;
        }

        if (!(plural->values[index] = parseXml(&childParser, android::ResTable_map::TYPE_STRING,
                                               kNoRawString))) {
            success = false;
        }
    }

    if (!success) {
        return false;
    }

    return mTable->addResource(resourceName, mConfig, source, std::move(plural));
}

bool ResourceParser::parseDeclareStyleable(XmlPullParser* parser,
                                           const ResourceNameRef& resourceName) {
    const SourceLine source = mSource.line(parser->getLineNumber());
    std::unique_ptr<Styleable> styleable = util::make_unique<Styleable>();

    bool success = true;
    while (XmlPullParser::isGoodEvent(parser->next())) {
        if (parser->getEvent() != XmlPullParser::Event::kStartElement) {
            continue;
        }

        ScopedXmlPullParser childParser(parser);

        const std::u16string& elementName = childParser.getElementName();
        if (elementName == u"attr") {
            const auto endAttrIter = childParser.endAttributes();
            auto attrIter = childParser.findAttribute(u"", u"name");
            if (attrIter == endAttrIter || attrIter->value.empty()) {
                mLogger.error(childParser.getLineNumber())
                        << "<attr> tag must have a 'name' attribute."
                        << std::endl;
                success = false;
                continue;
            }

            // Copy because our iterator will be invalidated.
            ResourceName attrResourceName = {
                    mTable->getPackage(),
                    ResourceType::kAttr,
                    attrIter->value
            };

            std::unique_ptr<Attribute> attr = parseAttrImpl(&childParser, &attrResourceName, true);
            if (!attr) {
                success = false;
                continue;
            }

            styleable->entries.emplace_back(attrResourceName);

            // The package may have been corrected to another package. If that is so,
            // we don't add the declaration.
            if (attrResourceName.package == mTable->getPackage()) {
                success &= mTable->addResource(attrResourceName, mConfig,
                                               mSource.line(childParser.getLineNumber()),
                                               std::move(attr));
            }

        } else if (elementName != u"eat-comment" && elementName != u"skip") {
            mLogger.error(childParser.getLineNumber())
                    << "<"
                    << elementName
                    << "> is not allowed inside <declare-styleable>."
                    << std::endl;
            success = false;
        }
    }

    if (!success) {
        return false;
    }

    return mTable->addResource(resourceName, mConfig, source, std::move(styleable));
}

} // namespace aapt
