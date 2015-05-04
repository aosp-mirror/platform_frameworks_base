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

#include "XliffXmlPullParser.h"

#include <string>

namespace aapt {

XliffXmlPullParser::XliffXmlPullParser(const std::shared_ptr<XmlPullParser>& parser) :
        mParser(parser) {
}

XmlPullParser::Event XliffXmlPullParser::next() {
    while (XmlPullParser::isGoodEvent(mParser->next())) {
        Event event = mParser->getEvent();
        if (event != Event::kStartElement && event != Event::kEndElement) {
            break;
        }

        if (mParser->getElementNamespace() !=
                u"urn:oasis:names:tc:xliff:document:1.2") {
            break;
        }

        const std::u16string& name = mParser->getElementName();
        if (name != u"bpt"
                && name != u"ept"
                && name != u"it"
                && name != u"ph"
                && name != u"g"
                && name != u"bx"
                && name != u"ex"
                && name != u"x") {
            break;
        }

        // We hit a tag that was ignored, so get the next event.
    }
    return mParser->getEvent();
}

XmlPullParser::Event XliffXmlPullParser::getEvent() const {
    return mParser->getEvent();
}

const std::string& XliffXmlPullParser::getLastError() const {
    return mParser->getLastError();
}

const std::u16string& XliffXmlPullParser::getComment() const {
    return mParser->getComment();
}

size_t XliffXmlPullParser::getLineNumber() const {
    return mParser->getLineNumber();
}

size_t XliffXmlPullParser::getDepth() const {
    return mParser->getDepth();
}

const std::u16string& XliffXmlPullParser::getText() const {
    return mParser->getText();
}

const std::u16string& XliffXmlPullParser::getNamespacePrefix() const {
    return mParser->getNamespacePrefix();
}

const std::u16string& XliffXmlPullParser::getNamespaceUri() const {
    return mParser->getNamespaceUri();
}

bool XliffXmlPullParser::applyPackageAlias(std::u16string* package,
                                           const std::u16string& defaultPackage) const {
    return mParser->applyPackageAlias(package, defaultPackage);
}

const std::u16string& XliffXmlPullParser::getElementNamespace() const {
    return mParser->getElementNamespace();
}

const std::u16string& XliffXmlPullParser::getElementName() const {
    return mParser->getElementName();
}

size_t XliffXmlPullParser::getAttributeCount() const {
    return mParser->getAttributeCount();
}

XmlPullParser::const_iterator XliffXmlPullParser::beginAttributes() const {
    return mParser->beginAttributes();
}

XmlPullParser::const_iterator XliffXmlPullParser::endAttributes() const {
    return mParser->endAttributes();
}

} // namespace aapt
