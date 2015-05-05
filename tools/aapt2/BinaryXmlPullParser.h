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

#ifndef AAPT_BINARY_XML_PULL_PARSER_H
#define AAPT_BINARY_XML_PULL_PARSER_H

#include "XmlPullParser.h"

#include <androidfw/ResourceTypes.h>
#include <memory>
#include <string>
#include <vector>

namespace aapt {

/**
 * Wraps a ResTable into the canonical XmlPullParser interface.
 */
class BinaryXmlPullParser : public XmlPullParser {
public:
    BinaryXmlPullParser(const std::shared_ptr<android::ResXMLTree>& parser);
    BinaryXmlPullParser(const BinaryXmlPullParser& rhs) = delete;

    Event getEvent() const override;
    const std::string& getLastError() const override;
    Event next() override;

    const std::u16string& getComment() const override;
    size_t getLineNumber() const override;
    size_t getDepth() const override;

    const std::u16string& getText() const override;

    const std::u16string& getNamespacePrefix() const override;
    const std::u16string& getNamespaceUri() const override;
    bool applyPackageAlias(std::u16string* package, const std::u16string& defaultpackage)
            const override;

    const std::u16string& getElementNamespace() const override;
    const std::u16string& getElementName() const override;

    const_iterator beginAttributes() const override;
    const_iterator endAttributes() const override;
    size_t getAttributeCount() const override;

private:
    void copyAttributes();

    std::shared_ptr<android::ResXMLTree> mParser;
    std::u16string mStr1;
    std::u16string mStr2;
    std::vector<Attribute> mAttributes;
    Event mEvent;
    bool mHasComment;
    const std::u16string sEmpty;
    const std::string sEmpty8;
    size_t mDepth;
    std::vector<std::pair<std::u16string, std::u16string>> mPackageAliases;
};

} // namespace aapt

#endif // AAPT_BINARY_XML_PULL_PARSER_H
