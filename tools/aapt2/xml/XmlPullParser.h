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

#ifndef AAPT_XML_PULL_PARSER_H
#define AAPT_XML_PULL_PARSER_H

#include "Resource.h"
#include "process/IResourceTableConsumer.h"
#include "util/Maybe.h"
#include "util/StringPiece.h"
#include "xml/XmlUtil.h"

#include <algorithm>
#include <expat.h>
#include <istream>
#include <ostream>
#include <queue>
#include <stack>
#include <string>
#include <vector>

namespace aapt {
namespace xml {

class XmlPullParser : public IPackageDeclStack {
public:
    enum class Event {
        kBadDocument,
        kStartDocument,
        kEndDocument,

        kStartNamespace,
        kEndNamespace,
        kStartElement,
        kEndElement,
        kText,
        kComment,
    };

    /**
     * Skips to the next direct descendant node of the given startDepth,
     * skipping namespace nodes.
     *
     * When nextChildNode returns true, you can expect Comments, Text, and StartElement events.
     */
    static bool nextChildNode(XmlPullParser* parser, size_t startDepth);
    static bool skipCurrentElement(XmlPullParser* parser);
    static bool isGoodEvent(Event event);

    XmlPullParser(std::istream& in);
    ~XmlPullParser();

    /**
     * Returns the current event that is being processed.
     */
    Event getEvent() const;

    const std::string& getLastError() const;

    /**
     * Note, unlike XmlPullParser, the first call to next() will return
     * StartElement of the first element.
     */
    Event next();

    //
    // These are available for all nodes.
    //

    const std::u16string& getComment() const;
    size_t getLineNumber() const;
    size_t getDepth() const;

    /**
     * Returns the character data for a Text event.
     */
    const std::u16string& getText() const;

    //
    // Namespace prefix and URI are available for StartNamespace and EndNamespace.
    //

    const std::u16string& getNamespacePrefix() const;
    const std::u16string& getNamespaceUri() const;

    //
    // These are available for StartElement and EndElement.
    //

    const std::u16string& getElementNamespace() const;
    const std::u16string& getElementName() const;

    /*
     * Uses the current stack of namespaces to resolve the package. Eg:
     * xmlns:app = "http://schemas.android.com/apk/res/com.android.app"
     * ...
     * android:text="@app:string/message"
     *
     * In this case, 'app' will be converted to 'com.android.app'.
     *
     * If xmlns:app="http://schemas.android.com/apk/res-auto", then
     * 'package' will be set to 'defaultPackage'.
     */
    Maybe<ExtractedPackage> transformPackageAlias(
            const StringPiece16& alias, const StringPiece16& localPackage) const override;

    //
    // Remaining methods are for retrieving information about attributes
    // associated with a StartElement.
    //
    // Attributes must be in sorted order (according to the less than operator
    // of struct Attribute).
    //

    struct Attribute {
        std::u16string namespaceUri;
        std::u16string name;
        std::u16string value;

        int compare(const Attribute& rhs) const;
        bool operator<(const Attribute& rhs) const;
        bool operator==(const Attribute& rhs) const;
        bool operator!=(const Attribute& rhs) const;
    };

    using const_iterator = std::vector<Attribute>::const_iterator;

    const_iterator beginAttributes() const;
    const_iterator endAttributes() const;
    size_t getAttributeCount() const;
    const_iterator findAttribute(StringPiece16 namespaceUri, StringPiece16 name) const;

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

    struct PackageDecl {
        std::u16string prefix;
        ExtractedPackage package;
    };
    std::vector<PackageDecl> mPackageAliases;
};

/**
 * Finds the attribute in the current element within the global namespace.
 */
Maybe<StringPiece16> findAttribute(const XmlPullParser* parser, const StringPiece16& name);

/**
 * Finds the attribute in the current element within the global namespace. The attribute's value
 * must not be the empty string.
 */
Maybe<StringPiece16> findNonEmptyAttribute(const XmlPullParser* parser, const StringPiece16& name);

//
// Implementation
//

inline ::std::ostream& operator<<(::std::ostream& out, XmlPullParser::Event event) {
    switch (event) {
        case XmlPullParser::Event::kBadDocument: return out << "BadDocument";
        case XmlPullParser::Event::kStartDocument: return out << "StartDocument";
        case XmlPullParser::Event::kEndDocument: return out << "EndDocument";
        case XmlPullParser::Event::kStartNamespace: return out << "StartNamespace";
        case XmlPullParser::Event::kEndNamespace: return out << "EndNamespace";
        case XmlPullParser::Event::kStartElement: return out << "StartElement";
        case XmlPullParser::Event::kEndElement: return out << "EndElement";
        case XmlPullParser::Event::kText: return out << "Text";
        case XmlPullParser::Event::kComment: return out << "Comment";
    }
    return out;
}

inline bool XmlPullParser::nextChildNode(XmlPullParser* parser, size_t startDepth) {
    Event event;

    // First get back to the start depth.
    while (isGoodEvent(event = parser->next()) && parser->getDepth() > startDepth + 1) {}

    // Now look for the first good node.
    while ((event != Event::kEndElement || parser->getDepth() > startDepth) && isGoodEvent(event)) {
        switch (event) {
        case Event::kText:
        case Event::kComment:
        case Event::kStartElement:
            return true;
        default:
            break;
        }
        event = parser->next();
    }
    return false;
}

inline bool XmlPullParser::skipCurrentElement(XmlPullParser* parser) {
    int depth = 1;
    while (depth > 0) {
        switch (parser->next()) {
            case Event::kEndDocument:
                return true;
            case Event::kBadDocument:
                return false;
            case Event::kStartElement:
                depth++;
                break;
            case Event::kEndElement:
                depth--;
                break;
            default:
                break;
        }
    }
    return true;
}

inline bool XmlPullParser::isGoodEvent(XmlPullParser::Event event) {
    return event != Event::kBadDocument && event != Event::kEndDocument;
}

inline int XmlPullParser::Attribute::compare(const Attribute& rhs) const {
    int cmp = namespaceUri.compare(rhs.namespaceUri);
    if (cmp != 0) return cmp;
    return name.compare(rhs.name);
}

inline bool XmlPullParser::Attribute::operator<(const Attribute& rhs) const {
    return compare(rhs) < 0;
}

inline bool XmlPullParser::Attribute::operator==(const Attribute& rhs) const {
    return compare(rhs) == 0;
}

inline bool XmlPullParser::Attribute::operator!=(const Attribute& rhs) const {
    return compare(rhs) != 0;
}

inline XmlPullParser::const_iterator XmlPullParser::findAttribute(StringPiece16 namespaceUri,
                                                                  StringPiece16 name) const {
    const auto endIter = endAttributes();
    const auto iter = std::lower_bound(beginAttributes(), endIter,
            std::pair<StringPiece16, StringPiece16>(namespaceUri, name),
            [](const Attribute& attr, const std::pair<StringPiece16, StringPiece16>& rhs) -> bool {
                int cmp = attr.namespaceUri.compare(0, attr.namespaceUri.size(),
                        rhs.first.data(), rhs.first.size());
                if (cmp < 0) return true;
                if (cmp > 0) return false;
                cmp = attr.name.compare(0, attr.name.size(), rhs.second.data(), rhs.second.size());
                if (cmp < 0) return true;
                return false;
            }
    );

    if (iter != endIter && namespaceUri == iter->namespaceUri && name == iter->name) {
        return iter;
    }
    return endIter;
}

} // namespace xml
} // namespace aapt

#endif // AAPT_XML_PULL_PARSER_H
