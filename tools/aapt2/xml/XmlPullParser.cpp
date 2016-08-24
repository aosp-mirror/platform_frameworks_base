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

#include "util/Maybe.h"
#include "util/Util.h"
#include "xml/XmlPullParser.h"
#include "xml/XmlUtil.h"

#include <iostream>
#include <string>

namespace aapt {
namespace xml {

constexpr char kXmlNamespaceSep = 1;

XmlPullParser::XmlPullParser(std::istream& in) : mIn(in), mEmpty(), mDepth(0) {
    mParser = XML_ParserCreateNS(nullptr, kXmlNamespaceSep);
    XML_SetUserData(mParser, this);
    XML_SetElementHandler(mParser, startElementHandler, endElementHandler);
    XML_SetNamespaceDeclHandler(mParser, startNamespaceHandler, endNamespaceHandler);
    XML_SetCharacterDataHandler(mParser, characterDataHandler);
    XML_SetCommentHandler(mParser, commentDataHandler);
    mEventQueue.push(EventData{ Event::kStartDocument, 0, mDepth++ });
}

XmlPullParser::~XmlPullParser() {
    XML_ParserFree(mParser);
}

XmlPullParser::Event XmlPullParser::next() {
    const Event currentEvent = getEvent();
    if (currentEvent == Event::kBadDocument || currentEvent == Event::kEndDocument) {
        return currentEvent;
    }

    mEventQueue.pop();
    while (mEventQueue.empty()) {
        mIn.read(mBuffer, sizeof(mBuffer) / sizeof(*mBuffer));

        const bool done = mIn.eof();
        if (mIn.bad() && !done) {
            mLastError = strerror(errno);
            mEventQueue.push(EventData{ Event::kBadDocument });
            continue;
        }

        if (XML_Parse(mParser, mBuffer, mIn.gcount(), done) == XML_STATUS_ERROR) {
            mLastError = XML_ErrorString(XML_GetErrorCode(mParser));
            mEventQueue.push(EventData{ Event::kBadDocument });
            continue;
        }

        if (done) {
            mEventQueue.push(EventData{ Event::kEndDocument, 0, 0 });
        }
    }

    Event event = getEvent();

    // Record namespace prefixes and package names so that we can do our own
    // handling of references that use namespace aliases.
    if (event == Event::kStartNamespace || event == Event::kEndNamespace) {
        Maybe<ExtractedPackage> result = extractPackageFromNamespace(getNamespaceUri());
        if (event == Event::kStartNamespace) {
            if (result) {
                mPackageAliases.emplace_back(
                        PackageDecl{ getNamespacePrefix(), std::move(result.value()) });
            }
        } else {
            if (result) {
                mPackageAliases.pop_back();
            }
        }
    }

    return event;
}

XmlPullParser::Event XmlPullParser::getEvent() const {
    return mEventQueue.front().event;
}

const std::string& XmlPullParser::getLastError() const {
    return mLastError;
}

const std::u16string& XmlPullParser::getComment() const {
    return mEventQueue.front().data1;
}

size_t XmlPullParser::getLineNumber() const {
    return mEventQueue.front().lineNumber;
}

size_t XmlPullParser::getDepth() const {
    return mEventQueue.front().depth;
}

const std::u16string& XmlPullParser::getText() const {
    if (getEvent() != Event::kText) {
        return mEmpty;
    }
    return mEventQueue.front().data1;
}

const std::u16string& XmlPullParser::getNamespacePrefix() const {
    const Event currentEvent = getEvent();
    if (currentEvent != Event::kStartNamespace && currentEvent != Event::kEndNamespace) {
        return mEmpty;
    }
    return mEventQueue.front().data1;
}

const std::u16string& XmlPullParser::getNamespaceUri() const {
    const Event currentEvent = getEvent();
    if (currentEvent != Event::kStartNamespace && currentEvent != Event::kEndNamespace) {
        return mEmpty;
    }
    return mEventQueue.front().data2;
}

Maybe<ExtractedPackage> XmlPullParser::transformPackageAlias(
        const StringPiece16& alias, const StringPiece16& localPackage) const {
    if (alias.empty()) {
        return ExtractedPackage{ localPackage.toString(), false /* private */ };
    }

    const auto endIter = mPackageAliases.rend();
    for (auto iter = mPackageAliases.rbegin(); iter != endIter; ++iter) {
        if (alias == iter->prefix) {
            if (iter->package.package.empty()) {
                return ExtractedPackage{ localPackage.toString(),
                                         iter->package.privateNamespace };
            }
            return iter->package;
        }
    }
    return {};
}

const std::u16string& XmlPullParser::getElementNamespace() const {
    const Event currentEvent = getEvent();
    if (currentEvent != Event::kStartElement && currentEvent != Event::kEndElement) {
        return mEmpty;
    }
    return mEventQueue.front().data1;
}

const std::u16string& XmlPullParser::getElementName() const {
    const Event currentEvent = getEvent();
    if (currentEvent != Event::kStartElement && currentEvent != Event::kEndElement) {
        return mEmpty;
    }
    return mEventQueue.front().data2;
}

XmlPullParser::const_iterator XmlPullParser::beginAttributes() const {
    return mEventQueue.front().attributes.begin();
}

XmlPullParser::const_iterator XmlPullParser::endAttributes() const {
    return mEventQueue.front().attributes.end();
}

size_t XmlPullParser::getAttributeCount() const {
    if (getEvent() != Event::kStartElement) {
        return 0;
    }
    return mEventQueue.front().attributes.size();
}

/**
 * Extracts the namespace and name of an expanded element or attribute name.
 */
static void splitName(const char* name, std::u16string& outNs, std::u16string& outName) {
    const char* p = name;
    while (*p != 0 && *p != kXmlNamespaceSep) {
        p++;
    }

    if (*p == 0) {
        outNs = std::u16string();
        outName = util::utf8ToUtf16(name);
    } else {
        outNs = util::utf8ToUtf16(StringPiece(name, (p - name)));
        outName = util::utf8ToUtf16(p + 1);
    }
}

void XMLCALL XmlPullParser::startNamespaceHandler(void* userData, const char* prefix,
        const char* uri) {
    XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(userData);
    std::u16string namespaceUri = uri != nullptr ? util::utf8ToUtf16(uri) : std::u16string();
    parser->mNamespaceUris.push(namespaceUri);
    parser->mEventQueue.push(EventData{
            Event::kStartNamespace,
            XML_GetCurrentLineNumber(parser->mParser),
            parser->mDepth++,
            prefix != nullptr ? util::utf8ToUtf16(prefix) : std::u16string(),
            namespaceUri
    });
}

void XMLCALL XmlPullParser::startElementHandler(void* userData, const char* name,
        const char** attrs) {
    XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(userData);

    EventData data = {
            Event::kStartElement, XML_GetCurrentLineNumber(parser->mParser), parser->mDepth++
    };
    splitName(name, data.data1, data.data2);

    while (*attrs) {
        Attribute attribute;
        splitName(*attrs++, attribute.namespaceUri, attribute.name);
        attribute.value = util::utf8ToUtf16(*attrs++);

        // Insert in sorted order.
        auto iter = std::lower_bound(data.attributes.begin(), data.attributes.end(), attribute);
        data.attributes.insert(iter, std::move(attribute));
    }

    // Move the structure into the queue (no copy).
    parser->mEventQueue.push(std::move(data));
}

void XMLCALL XmlPullParser::characterDataHandler(void* userData, const char* s, int len) {
    XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(userData);

    parser->mEventQueue.push(EventData{
            Event::kText,
            XML_GetCurrentLineNumber(parser->mParser),
            parser->mDepth,
            util::utf8ToUtf16(StringPiece(s, len))
    });
}

void XMLCALL XmlPullParser::endElementHandler(void* userData, const char* name) {
    XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(userData);

    EventData data = {
            Event::kEndElement, XML_GetCurrentLineNumber(parser->mParser), --(parser->mDepth)
    };
    splitName(name, data.data1, data.data2);

    // Move the data into the queue (no copy).
    parser->mEventQueue.push(std::move(data));
}

void XMLCALL XmlPullParser::endNamespaceHandler(void* userData, const char* prefix) {
    XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(userData);

    parser->mEventQueue.push(EventData{
            Event::kEndNamespace,
            XML_GetCurrentLineNumber(parser->mParser),
            --(parser->mDepth),
            prefix != nullptr ? util::utf8ToUtf16(prefix) : std::u16string(),
            parser->mNamespaceUris.top()
    });
    parser->mNamespaceUris.pop();
}

void XMLCALL XmlPullParser::commentDataHandler(void* userData, const char* comment) {
    XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(userData);

    parser->mEventQueue.push(EventData{
            Event::kComment,
            XML_GetCurrentLineNumber(parser->mParser),
            parser->mDepth,
            util::utf8ToUtf16(comment)
    });
}

Maybe<StringPiece16> findAttribute(const XmlPullParser* parser, const StringPiece16& name) {
    auto iter = parser->findAttribute(u"", name);
    if (iter != parser->endAttributes()) {
        return StringPiece16(util::trimWhitespace(iter->value));
    }
    return {};
}

Maybe<StringPiece16> findNonEmptyAttribute(const XmlPullParser* parser, const StringPiece16& name) {
    auto iter = parser->findAttribute(u"", name);
    if (iter != parser->endAttributes()) {
        StringPiece16 trimmed = util::trimWhitespace(iter->value);
        if (!trimmed.empty()) {
            return trimmed;
        }
    }
    return {};
}

} // namespace xml
} // namespace aapt
