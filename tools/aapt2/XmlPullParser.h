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

#include <algorithm>
#include <ostream>
#include <string>
#include <vector>

#include "StringPiece.h"

namespace aapt {

class XmlPullParser {
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

    static void skipCurrentElement(XmlPullParser* parser);
    static bool isGoodEvent(Event event);

    virtual ~XmlPullParser() {}

    /**
     * Returns the current event that is being processed.
     */
    virtual Event getEvent() const = 0;

    virtual const std::string& getLastError() const = 0;

    /**
     * Note, unlike XmlPullParser, the first call to next() will return
     * StartElement of the first element.
     */
    virtual Event next() = 0;

    //
    // These are available for all nodes.
    //

    virtual const std::u16string& getComment() const = 0;
    virtual size_t getLineNumber() const = 0;
    virtual size_t getDepth() const = 0;

    /**
     * Returns the character data for a Text event.
     */
    virtual const std::u16string& getText() const = 0;

    //
    // Namespace prefix and URI are available for StartNamespace and EndNamespace.
    //

    virtual const std::u16string& getNamespacePrefix() const = 0;
    virtual const std::u16string& getNamespaceUri() const = 0;

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
    virtual bool applyPackageAlias(std::u16string* package,
                                   const std::u16string& defaultPackage) const = 0;

    //
    // These are available for StartElement and EndElement.
    //

    virtual const std::u16string& getElementNamespace() const = 0;
    virtual const std::u16string& getElementName() const = 0;

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

    virtual const_iterator beginAttributes() const = 0;
    virtual const_iterator endAttributes() const = 0;
    virtual size_t getAttributeCount() const = 0;
    const_iterator findAttribute(StringPiece16 namespaceUri, StringPiece16 name) const;
};

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

inline void XmlPullParser::skipCurrentElement(XmlPullParser* parser) {
    int depth = 1;
    while (depth > 0) {
        switch (parser->next()) {
            case Event::kEndDocument:
            case Event::kBadDocument:
                return;
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

} // namespace aapt

#endif // AAPT_XML_PULL_PARSER_H
