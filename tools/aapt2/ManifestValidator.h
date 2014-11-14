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

#ifndef AAPT_MANIFEST_VALIDATOR_H
#define AAPT_MANIFEST_VALIDATOR_H

#include "Logger.h"
#include "Maybe.h"
#include "Source.h"
#include "StringPiece.h"

#include <androidfw/ResourceTypes.h>

namespace aapt {

class ManifestValidator {
public:
    ManifestValidator(const android::ResTable& table);
    ManifestValidator(const ManifestValidator&) = delete;

    bool validate(const Source& source, android::ResXMLParser* parser);

private:
    bool validateManifest(const Source& source, android::ResXMLParser* parser);

    Maybe<StringPiece16> getAttributeInlineValue(android::ResXMLParser* parser, size_t idx);
    Maybe<StringPiece16> getAttributeValue(android::ResXMLParser* parser, size_t idx);

    bool validateInlineAttribute(android::ResXMLParser* parser, size_t idx,
                                 SourceLogger& logger, const StringPiece16& charSet);
    bool validateAttribute(android::ResXMLParser* parser, size_t idx, SourceLogger& logger,
                           const StringPiece16& charSet);
    bool validateAttributeImpl(const StringPiece16& element, const StringPiece16& attributeName,
                               const StringPiece16& attributeValue, const StringPiece16& charSet,
                               size_t lineNumber, SourceLogger& logger);

    const android::ResTable& mTable;
};

} // namespace aapt

#endif // AAPT_MANIFEST_VALIDATOR_H
