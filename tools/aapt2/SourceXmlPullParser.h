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

#ifndef AAPT_SOURCE_XML_PULL_PARSER_H
#define AAPT_SOURCE_XML_PULL_PARSER_H

#include "XmlPullParser.h"

#include <istream>
#include <libexpat/expat.h>
#include <queue>
#include <stack>
#include <string>
#include <vector>

namespace aapt {

class SourceXmlPullParser : public XmlPullParser {
public:
    SourceXmlPullParser(std::istream& in);
    SourceXmlPullParser(const SourceXmlPullParser& rhs) = delete;
    ~SourceXmlPullParser();

    Event getEvent() const override;
    const std::string& getLastError() const override ;
    Event next() override ;

    const std::u16string& getComment() const override;
    size_t getLineNumber() const override;
    size_t getDepth() const override;

    const std::u16string& getText() const override;

    const std::u16string& getNamespacePrefix() const override;
    const std::u16string& getNamespaceUri() const override;
    bool applyPackageAlias(std::u16string* package,
                           const std::u16string& defaultPackage) const override;


    const std::u16string& getElementNamespace() const override;
    const std::u16string& getElementName() const override;

    const_iterator beginAttributes() const override;
    const_iterator endAttributes() const override;
    size_t getAttributeCount() const override;

private:
    static void XMLCALL startNamespaceHandler(void* userData, const char* prefix, const char* uri);
    static void XMLCALL startElementHandler(void* userData, const char* name, const char** attrs);
    static void XMLCALL characterDataHandler(void* userData, const char* s, int len);
    static void XMLCALL endElementHandler(void* userData, const char* name);
    static void XMLCALL endNamespaceHandler(void* userData, const char* prefix);
    static void XMLCALL commentDataHandler(void* userData, const char* comment);

    struct EventData {
        Event event;
        size_t lineNumber;
        size_t depth;
        std::u16string data1;
        std::u16string data2;
        std::u16string comment;
        std::vector<Attribute> attributes;
    };

    std::istream& mIn;
    XML_Parser mParser;
    char mBuffer[16384];
    std::queue<EventData> mEventQueue;
    std::string mLastError;
    const std::u16string mEmpty;
    size_t mDepth;
    std::stack<std::u16string> mNamespaceUris;
    std::vector<std::pair<std::u16string, std::u16string>> mPackageAliases;
};

} // namespace aapt

#endif // AAPT_SOURCE_XML_PULL_PARSER_H
