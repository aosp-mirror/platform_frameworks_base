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

#include <iostream>
#include <string>

#include "Maybe.h"
#include "SourceXmlPullParser.h"
#include "Util.h"

namespace aapt {

constexpr char kXmlNamespaceSep = 1;

SourceXmlPullParser::SourceXmlPullParser(std::istream& in) : mIn(in), mEmpty(), mDepth(0) {
    mParser = XML_ParserCreateNS(nullptr, kXmlNamespaceSep);
    XML_SetUserData(mParser, this);
    XML_SetElementHandler(mParser, startElementHandler, endElementHandler);
    XML_SetNamespaceDeclHandler(mParser, startNamespaceHandler, endNamespaceHandler);
    XML_SetCharacterDataHandler(mParser, characterDataHandler);
    XML_SetCommentHandler(mParser, commentDataHandler);
    mEventQueue.push(EventData{ Event::kStartDocument, 0, mDepth++ });
}

SourceXmlPullParser::~SourceXmlPullParser() {
    XML_ParserFree(mParser);
}

SourceXmlPullParser::Event SourceXmlPullParser::next() {
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
        Maybe<std::u16string> result = util::extractPackageFromNamespace(getNamespaceUri());
        if (event == Event::kStartNamespace) {
            if (result) {
                mPackageAliases.emplace_back(getNamespacePrefix(), result.value());
            }
        } else {
            if (result) {
                assert(mPackageAliases.back().second == result.value());
                mPackageAliases.pop_back();
            }
        }
    }

    return event;
}

SourceXmlPullParser::Event SourceXmlPullParser::getEvent() const {
    return mEventQueue.front().event;
}

const std::string& SourceXmlPullParser::getLastError() const {
    return mLastError;
}

const std::u16string& SourceXmlPullParser::getComment() const {
    return mEventQueue.front().comment;
}

size_t SourceXmlPullParser::getLineNumber() const {
    return mEventQueue.front().lineNumber;
}

size_t SourceXmlPullParser::getDepth() const {
    return mEventQueue.front().depth;
}

const std::u16string& SourceXmlPullParser::getText() const {
    if (getEvent() != Event::kText) {
        return mEmpty;
    }
    return mEventQueue.front().data1;
}

const std::u16string& SourceXmlPullParser::getNamespacePrefix() const {
    const Event currentEvent = getEvent();
    if (currentEvent != Event::kStartNamespace && currentEvent != Event::kEndNamespace) {
        return mEmpty;
    }
    return mEventQueue.front().data1;
}

const std::u16string& SourceXmlPullParser::getNamespaceUri() const {
    const Event currentEvent = getEvent();
    if (currentEvent != Event::kStartNamespace && currentEvent != Event::kEndNamespace) {
        return mEmpty;
    }
    return mEventQueue.front().data2;
}

bool SourceXmlPullParser::applyPackageAlias(std::u16string* package,
                                            const std::u16string& defaultPackage) const {
    const auto endIter = mPackageAliases.rend();
    for (auto iter = mPackageAliases.rbegin(); iter != endIter; ++iter) {
        if (iter->first == *package) {
            if (iter->second.empty()) {
                *package = defaultPackage;
            } else {
                *package = iter->second;
            }
            return true;
        }
    }
    return false;
}

const std::u16string& SourceXmlPullParser::getElementNamespace() const {
    const Event currentEvent = getEvent();
    if (currentEvent != Event::kStartElement && currentEvent != Event::kEndElement) {
        return mEmpty;
    }
    return mEventQueue.front().data1;
}

const std::u16string& SourceXmlPullParser::getElementName() const {
    const Event currentEvent = getEvent();
    if (currentEvent != Event::kStartElement && currentEvent != Event::kEndElement) {
        return mEmpty;
    }
    return mEventQueue.front().data2;
}

XmlPullParser::const_iterator SourceXmlPullParser::beginAttributes() const {
    return mEventQueue.front().attributes.begin();
}

XmlPullParser::const_iterator SourceXmlPullParser::endAttributes() const {
    return mEventQueue.front().attributes.end();
}

size_t SourceXmlPullParser::getAttributeCount() const {
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

void XMLCALL SourceXmlPullParser::startNamespaceHandler(void* userData, const char* prefix,
        const char* uri) {
    SourceXmlPullParser* parser = reinterpret_cast<SourceXmlPullParser*>(userData);
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

void XMLCALL SourceXmlPullParser::startElementHandler(void* userData, const char* name,
        const char** attrs) {
    SourceXmlPullParser* parser = reinterpret_cast<SourceXmlPullParser*>(userData);

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

void XMLCALL SourceXmlPullParser::characterDataHandler(void* userData, const char* s, int len) {
    SourceXmlPullParser* parser = reinterpret_cast<SourceXmlPullParser*>(userData);

    parser->mEventQueue.push(EventData{
            Event::kText,
            XML_GetCurrentLineNumber(parser->mParser),
            parser->mDepth,
            util::utf8ToUtf16(StringPiece(s, len))
    });
}

void XMLCALL SourceXmlPullParser::endElementHandler(void* userData, const char* name) {
    SourceXmlPullParser* parser = reinterpret_cast<SourceXmlPullParser*>(userData);

    EventData data = {
            Event::kEndElement, XML_GetCurrentLineNumber(parser->mParser), --(parser->mDepth)
    };
    splitName(name, data.data1, data.data2);

    // Move the data into the queue (no copy).
    parser->mEventQueue.push(std::move(data));
}

void XMLCALL SourceXmlPullParser::endNamespaceHandler(void* userData, const char* prefix) {
    SourceXmlPullParser* parser = reinterpret_cast<SourceXmlPullParser*>(userData);

    parser->mEventQueue.push(EventData{
            Event::kEndNamespace,
            XML_GetCurrentLineNumber(parser->mParser),
            --(parser->mDepth),
            prefix != nullptr ? util::utf8ToUtf16(prefix) : std::u16string(),
            parser->mNamespaceUris.top()
    });
    parser->mNamespaceUris.pop();
}

void XMLCALL SourceXmlPullParser::commentDataHandler(void* userData, const char* comment) {
    SourceXmlPullParser* parser = reinterpret_cast<SourceXmlPullParser*>(userData);

    parser->mEventQueue.push(EventData{
            Event::kComment,
            XML_GetCurrentLineNumber(parser->mParser),
            parser->mDepth,
            util::utf8ToUtf16(comment)
    });
}

} // namespace aapt
