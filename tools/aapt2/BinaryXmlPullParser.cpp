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

#include "BinaryXmlPullParser.h"

#include <androidfw/ResourceTypes.h>
#include <memory>
#include <string>
#include <vector>

namespace aapt {

static XmlPullParser::Event codeToEvent(android::ResXMLParser::event_code_t code) {
    switch (code) {
        case android::ResXMLParser::START_DOCUMENT:
            return XmlPullParser::Event::kStartDocument;
        case android::ResXMLParser::END_DOCUMENT:
            return XmlPullParser::Event::kEndDocument;
        case android::ResXMLParser::START_NAMESPACE:
            return XmlPullParser::Event::kStartNamespace;
        case android::ResXMLParser::END_NAMESPACE:
            return XmlPullParser::Event::kEndNamespace;
        case android::ResXMLParser::START_TAG:
            return XmlPullParser::Event::kStartElement;
        case android::ResXMLParser::END_TAG:
            return XmlPullParser::Event::kEndElement;
        case android::ResXMLParser::TEXT:
            return XmlPullParser::Event::kText;
        default:
            break;
    }
    return XmlPullParser::Event::kBadDocument;
}

BinaryXmlPullParser::BinaryXmlPullParser(const std::shared_ptr<android::ResXMLTree>& parser)
    : mParser(parser), mEvent(Event::kStartDocument), mHasComment(false), sEmpty(), sEmpty8(),
      mDepth(0) {
}

XmlPullParser::Event BinaryXmlPullParser::next() {
    mStr1.clear();
    mStr2.clear();
    mAttributes.clear();

    android::ResXMLParser::event_code_t code;
    if (mHasComment) {
        mHasComment = false;
        code = mParser->getEventType();
    } else {
        code = mParser->next();
        if (code != android::ResXMLParser::BAD_DOCUMENT) {
            size_t len;
            const char16_t* comment = mParser->getComment(&len);
            if (comment) {
                mHasComment = true;
                mStr1.assign(comment, len);
                return XmlPullParser::Event::kComment;
            }
        }
    }

    size_t len;
    const char16_t* data;
    mEvent = codeToEvent(code);
    switch (mEvent) {
        case Event::kStartNamespace:
        case Event::kEndNamespace:
            data = mParser->getNamespacePrefix(&len);
            mStr1.assign(data, len);
            data = mParser->getNamespaceUri(&len);
            mStr2.assign(data, len);
            break;

        case Event::kStartElement:
            copyAttributes();
            // fallthrough

        case Event::kEndElement:
            data = mParser->getElementNamespace(&len);
            mStr1.assign(data, len);
            data = mParser->getElementName(&len);
            mStr2.assign(data, len);
            break;

        case Event::kText:
            data = mParser->getText(&len);
            mStr1.assign(data, len);
            break;

        default:
            break;
    }
    return mEvent;
}

XmlPullParser::Event BinaryXmlPullParser::getEvent() const {
    if (mHasComment) {
        return XmlPullParser::Event::kComment;
    }
    return mEvent;
}

const std::string& BinaryXmlPullParser::getLastError() const {
    return sEmpty8;
}

const std::u16string& BinaryXmlPullParser::getComment() const {
    if (mHasComment) {
        return mStr1;
    }
    return sEmpty;
}

size_t BinaryXmlPullParser::getLineNumber() const {
    return mParser->getLineNumber();
}

size_t BinaryXmlPullParser::getDepth() const {
    return mDepth;
}

const std::u16string& BinaryXmlPullParser::getText() const {
    if (!mHasComment && mEvent == XmlPullParser::Event::kText) {
        return mStr1;
    }
    return sEmpty;
}

const std::u16string& BinaryXmlPullParser::getNamespacePrefix() const {
    if (!mHasComment && (mEvent == XmlPullParser::Event::kStartNamespace ||
            mEvent == XmlPullParser::Event::kEndNamespace)) {
        return mStr1;
    }
    return sEmpty;
}

const std::u16string& BinaryXmlPullParser::getNamespaceUri() const {
    if (!mHasComment && (mEvent == XmlPullParser::Event::kStartNamespace ||
            mEvent == XmlPullParser::Event::kEndNamespace)) {
        return mStr2;
    }
    return sEmpty;
}

const std::u16string& BinaryXmlPullParser::getElementNamespace() const {
    if (!mHasComment && (mEvent == XmlPullParser::Event::kStartElement ||
            mEvent == XmlPullParser::Event::kEndElement)) {
        return mStr1;
    }
    return sEmpty;
}

const std::u16string& BinaryXmlPullParser::getElementName() const {
    if (!mHasComment && (mEvent == XmlPullParser::Event::kStartElement ||
            mEvent == XmlPullParser::Event::kEndElement)) {
        return mStr2;
    }
    return sEmpty;
}

size_t BinaryXmlPullParser::getAttributeCount() const {
    return mAttributes.size();
}

XmlPullParser::const_iterator BinaryXmlPullParser::beginAttributes() const {
    return mAttributes.begin();
}

XmlPullParser::const_iterator BinaryXmlPullParser::endAttributes() const {
    return mAttributes.end();
}

void BinaryXmlPullParser::copyAttributes() {
    const size_t attrCount = mParser->getAttributeCount();
    if (attrCount > 0) {
        mAttributes.reserve(attrCount);
        for (size_t i = 0; i < attrCount; i++) {
            XmlPullParser::Attribute attr;
            size_t len;
            const char16_t* str = mParser->getAttributeNamespace(i, &len);
            attr.namespaceUri.assign(str, len);
            str = mParser->getAttributeName(i, &len);
            attr.name.assign(str, len);
            str = mParser->getAttributeStringValue(i, &len);
            attr.value.assign(str, len);
            mAttributes.push_back(std::move(attr));
        }
    }
}

} // namespace aapt
