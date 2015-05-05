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

#ifndef AAPT_SCOPED_XML_PULL_PARSER_H
#define AAPT_SCOPED_XML_PULL_PARSER_H

#include "XmlPullParser.h"

#include <string>

namespace aapt {

/**
 * An XmlPullParser that will not read past the depth
 * of the underlying parser. When this parser is destroyed,
 * it moves the underlying parser to the same depth it
 * started with.
 *
 * You can write code like this:
 *
 *   while (XmlPullParser::isGoodEvent(parser.next())) {
 *     if (parser.getEvent() != XmlPullParser::Event::StartElement) {
 *       continue;
 *     }
 *
 *     ScopedXmlPullParser scoped(parser);
 *     if (parser.getElementName() == u"id") {
 *       // do work.
 *     } else {
 *       // do nothing, as all the sub elements will be skipped
 *       // when scoped goes out of scope.
 *     }
 *   }
 */
class ScopedXmlPullParser : public XmlPullParser {
public:
    ScopedXmlPullParser(XmlPullParser* parser);
    ScopedXmlPullParser(const ScopedXmlPullParser&) = delete;
    ScopedXmlPullParser& operator=(const ScopedXmlPullParser&) = delete;
    ~ScopedXmlPullParser();

    Event getEvent() const override;
    const std::string& getLastError() const override;
    Event next() override;

    const std::u16string& getComment() const override;
    size_t getLineNumber() const override;
    size_t getDepth() const override;

    const std::u16string& getText() const override;

    const std::u16string& getNamespacePrefix() const override;
    const std::u16string& getNamespaceUri() const override;
    bool applyPackageAlias(std::u16string* package, const std::u16string& defaultPackage)
            const override;

    const std::u16string& getElementNamespace() const override;
    const std::u16string& getElementName() const override;

    const_iterator beginAttributes() const override;
    const_iterator endAttributes() const override;
    size_t getAttributeCount() const override;

private:
    XmlPullParser* mParser;
    size_t mDepth;
    bool mDone;
};

} // namespace aapt

#endif // AAPT_SCOPED_XML_PULL_PARSER_H
