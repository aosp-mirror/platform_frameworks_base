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

#include "ScopedXmlPullParser.h"

#include <string>

namespace aapt {

ScopedXmlPullParser::ScopedXmlPullParser(XmlPullParser* parser) :
        mParser(parser), mDepth(parser->getDepth()), mDone(false) {
}

ScopedXmlPullParser::~ScopedXmlPullParser() {
    while (isGoodEvent(next()));
}

XmlPullParser::Event ScopedXmlPullParser::next() {
    if (mDone) {
        return Event::kEndDocument;
    }

    const Event event = mParser->next();
    if (mParser->getDepth() <= mDepth) {
        mDone = true;
    }
    return event;
}

XmlPullParser::Event ScopedXmlPullParser::getEvent() const {
    return mParser->getEvent();
}

const std::string& ScopedXmlPullParser::getLastError() const {
    return mParser->getLastError();
}

const std::u16string& ScopedXmlPullParser::getComment() const {
    return mParser->getComment();
}

size_t ScopedXmlPullParser::getLineNumber() const {
    return mParser->getLineNumber();
}

size_t ScopedXmlPullParser::getDepth() const {
    const size_t depth = mParser->getDepth();
    if (depth < mDepth) {
        return 0;
    }
    return depth - mDepth;
}

const std::u16string& ScopedXmlPullParser::getText() const {
    return mParser->getText();
}

const std::u16string& ScopedXmlPullParser::getNamespacePrefix() const {
    return mParser->getNamespacePrefix();
}

const std::u16string& ScopedXmlPullParser::getNamespaceUri() const {
    return mParser->getNamespaceUri();
}

bool ScopedXmlPullParser::applyPackageAlias(std::u16string* package,
                                            const std::u16string& defaultPackage) const {
    return mParser->applyPackageAlias(package, defaultPackage);
}

const std::u16string& ScopedXmlPullParser::getElementNamespace() const {
    return mParser->getElementNamespace();
}

const std::u16string& ScopedXmlPullParser::getElementName() const {
    return mParser->getElementName();
}

size_t ScopedXmlPullParser::getAttributeCount() const {
    return mParser->getAttributeCount();
}

XmlPullParser::const_iterator ScopedXmlPullParser::beginAttributes() const {
    return mParser->beginAttributes();
}

XmlPullParser::const_iterator ScopedXmlPullParser::endAttributes() const {
    return mParser->endAttributes();
}

} // namespace aapt
