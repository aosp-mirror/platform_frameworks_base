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
#include "ManifestValidator.h"
#include "Maybe.h"
#include "Source.h"
#include "Util.h"

#include <androidfw/ResourceTypes.h>

namespace aapt {

ManifestValidator::ManifestValidator(const android::ResTable& table)
: mTable(table) {
}

bool ManifestValidator::validate(const Source& source, android::ResXMLParser* parser) {
    SourceLogger logger(source);

    android::ResXMLParser::event_code_t code;
    while ((code = parser->next()) != android::ResXMLParser::END_DOCUMENT &&
            code != android::ResXMLParser::BAD_DOCUMENT) {
        if (code != android::ResXMLParser::START_TAG) {
            continue;
        }

        size_t len = 0;
        const StringPiece16 namespaceUri(parser->getElementNamespace(&len), len);
        if (!namespaceUri.empty()) {
            continue;
        }

        const StringPiece16 name(parser->getElementName(&len), len);
        if (name.empty()) {
            logger.error(parser->getLineNumber())
                    << "failed to get the element name."
                    << std::endl;
            return false;
        }

        if (name == u"manifest") {
            if (!validateManifest(source, parser)) {
                return false;
            }
        }
    }
    return true;
}

Maybe<StringPiece16> ManifestValidator::getAttributeValue(android::ResXMLParser* parser,
                                                          size_t idx) {
    android::Res_value value;
    if (parser->getAttributeValue(idx, &value) < 0) {
        return StringPiece16();
    }

    const android::ResStringPool* pool = &parser->getStrings();
    if (value.dataType == android::Res_value::TYPE_REFERENCE) {
        ssize_t strIdx = mTable.resolveReference(&value, 0x10000000u);
        if (strIdx < 0) {
            return {};
        }
        pool = mTable.getTableStringBlock(strIdx);
    }

    if (value.dataType != android::Res_value::TYPE_STRING || !pool) {
        return {};
    }
    return util::getString(*pool, value.data);
}

Maybe<StringPiece16> ManifestValidator::getAttributeInlineValue(android::ResXMLParser* parser,
                                                                size_t idx) {
    android::Res_value value;
    if (parser->getAttributeValue(idx, &value) < 0) {
        return StringPiece16();
    }

    if (value.dataType != android::Res_value::TYPE_STRING) {
        return {};
    }
    return util::getString(parser->getStrings(), value.data);
}

bool ManifestValidator::validateInlineAttribute(android::ResXMLParser* parser, size_t idx,
                                                SourceLogger& logger,
                                                const StringPiece16& charSet) {
    size_t len = 0;
    StringPiece16 element(parser->getElementName(&len), len);
    StringPiece16 attributeName(parser->getAttributeName(idx, &len), len);
    Maybe<StringPiece16> result = getAttributeInlineValue(parser, idx);
    if (!result) {
        logger.error(parser->getLineNumber())
                << "<"
                << element
                << "> must have a '"
                << attributeName
                << "' attribute with a string literal value."
                << std::endl;
        return false;
    }
    return validateAttributeImpl(element, attributeName, result.value(), charSet,
                                 parser->getLineNumber(), logger);
}

bool ManifestValidator::validateAttribute(android::ResXMLParser* parser, size_t idx,
                                          SourceLogger& logger, const StringPiece16& charSet) {
    size_t len = 0;
    StringPiece16 element(parser->getElementName(&len), len);
    StringPiece16 attributeName(parser->getAttributeName(idx, &len), len);
    Maybe<StringPiece16> result = getAttributeValue(parser, idx);
    if (!result) {
        logger.error(parser->getLineNumber())
                << "<"
                << element
                << "> must have a '"
                << attributeName
                << "' attribute that points to a string."
                << std::endl;
        return false;
    }
    return validateAttributeImpl(element, attributeName, result.value(), charSet,
                                 parser->getLineNumber(), logger);
}

bool ManifestValidator::validateAttributeImpl(const StringPiece16& element,
                                              const StringPiece16& attributeName,
                                              const StringPiece16& attributeValue,
                                              const StringPiece16& charSet, size_t lineNumber,
                                              SourceLogger& logger) {
    StringPiece16::const_iterator badIter =
            util::findNonAlphaNumericAndNotInSet(attributeValue, charSet);
    if (badIter != attributeValue.end()) {
        logger.error(lineNumber)
                << "tag <"
                << element
                << "> attribute '"
                << attributeName
                << "' has invalid character '"
                << StringPiece16(badIter, 1)
                << "'."
                << std::endl;
        return false;
    }

    if (!attributeValue.empty()) {
        StringPiece16 trimmed = util::trimWhitespace(attributeValue);
        if (attributeValue.begin() != trimmed.begin()) {
            logger.error(lineNumber)
                    << "tag <"
                    << element
                    << "> attribute '"
                    << attributeName
                    << "' can not start with whitespace."
                    << std::endl;
            return false;
        }

        if (attributeValue.end() != trimmed.end()) {
            logger.error(lineNumber)
                    << "tag <"
                    << element
                    << "> attribute '"
                    << attributeName
                    << "' can not end with whitespace."
                    << std::endl;
            return false;
        }
    }
    return true;
}

constexpr const char16_t* kPackageIdentSet = u"._";

bool ManifestValidator::validateManifest(const Source& source, android::ResXMLParser* parser) {
    bool error = false;
    SourceLogger logger(source);

    const StringPiece16 kAndroid = u"android";
    const StringPiece16 kPackage = u"package";
    const StringPiece16 kSharedUserId = u"sharedUserId";

    ssize_t idx;

    idx = parser->indexOfAttribute(nullptr, 0, kPackage.data(), kPackage.size());
    if (idx < 0) {
        logger.error(parser->getLineNumber())
                << "missing package attribute."
                << std::endl;
        error = true;
    } else {
        error |= !validateInlineAttribute(parser, idx, logger, kPackageIdentSet);
    }

    idx = parser->indexOfAttribute(kAndroid.data(), kAndroid.size(),
                                   kSharedUserId.data(), kSharedUserId.size());
    if (idx >= 0) {
        error |= !validateInlineAttribute(parser, idx, logger, kPackageIdentSet);
    }
    return !error;
}

} // namespace aapt
