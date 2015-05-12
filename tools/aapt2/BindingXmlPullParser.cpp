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

#include "BindingXmlPullParser.h"
#include "Util.h"

#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace aapt {

constexpr const char16_t* kBindingNamespaceUri = u"http://schemas.android.com/apk/binding";
constexpr const char16_t* kAndroidNamespaceUri = u"http://schemas.android.com/apk/res/android";
constexpr const char16_t* kVariableTagName = u"variable";
constexpr const char* kBindingTagPrefix = "android:binding_";

BindingXmlPullParser::BindingXmlPullParser(const std::shared_ptr<XmlPullParser>& parser) :
        mParser(parser), mOverride(false), mNextTagId(0) {
}

bool BindingXmlPullParser::readVariableDeclaration() {
    VarDecl var;

    const auto endAttrIter = mParser->endAttributes();
    for (auto attrIter = mParser->beginAttributes(); attrIter != endAttrIter; ++attrIter) {
        if (!attrIter->namespaceUri.empty()) {
            continue;
        }

        if (attrIter->name == u"name") {
            var.name = util::utf16ToUtf8(attrIter->value);
        } else if (attrIter->name == u"type") {
            var.type = util::utf16ToUtf8(attrIter->value);
        }
    }

    XmlPullParser::skipCurrentElement(mParser.get());

    if (var.name.empty()) {
        mLastError = "variable declaration missing name";
        return false;
    }

    if (var.type.empty()) {
        mLastError = "variable declaration missing type";
        return false;
    }

    mVarDecls.push_back(std::move(var));
    return true;
}

bool BindingXmlPullParser::readExpressions() {
    mOverride = true;
    std::vector<XmlPullParser::Attribute> expressions;
    std::string idValue;

    const auto endAttrIter = mParser->endAttributes();
    for (auto attr = mParser->beginAttributes(); attr != endAttrIter; ++attr) {
        if (attr->namespaceUri == kAndroidNamespaceUri && attr->name == u"id") {
            idValue = util::utf16ToUtf8(attr->value);
        } else {
            StringPiece16 value = util::trimWhitespace(attr->value);
            if (util::stringStartsWith<char16_t>(value, u"@{") &&
                    util::stringEndsWith<char16_t>(value, u"}")) {
                // This is attribute's value is an expression of the form
                // @{expression}. We need to capture the expression inside.
                expressions.push_back(XmlPullParser::Attribute{
                        attr->namespaceUri,
                        attr->name,
                        value.substr(2, value.size() - 3).toString()
                });
            } else {
                // This is a normal attribute, use as is.
                mAttributes.emplace_back(*attr);
            }
        }
    }

    // Check if we have any expressions.
    if (!expressions.empty()) {
        // We have expressions, so let's assign the target a tag number
        // and add it to our targets list.
        int32_t targetId = mNextTagId++;
        mTargets.push_back(Target{
                util::utf16ToUtf8(mParser->getElementName()),
                idValue,
                targetId,
                std::move(expressions)
        });

        std::stringstream numGen;
        numGen << kBindingTagPrefix << targetId;
        mAttributes.push_back(XmlPullParser::Attribute{
                std::u16string(kAndroidNamespaceUri),
                std::u16string(u"tag"),
                util::utf8ToUtf16(numGen.str())
        });
    }
    return true;
}

XmlPullParser::Event BindingXmlPullParser::next() {
    // Clear old state in preparation for the next event.
    mOverride = false;
    mAttributes.clear();

    while (true) {
        Event event = mParser->next();
        if (event == Event::kStartElement) {
            if (mParser->getElementNamespace().empty() &&
                    mParser->getElementName() == kVariableTagName) {
                // This is a variable tag. Record data from it, and
                // then discard the entire element.
                if (!readVariableDeclaration()) {
                    // mLastError is set, so getEvent will return kBadDocument.
                    return getEvent();
                }
                continue;
            } else {
                // Check for expressions of the form @{} in attribute text.
                const auto endAttrIter = mParser->endAttributes();
                for (auto attr = mParser->beginAttributes(); attr != endAttrIter; ++attr) {
                    StringPiece16 value = util::trimWhitespace(attr->value);
                    if (util::stringStartsWith<char16_t>(value, u"@{") &&
                            util::stringEndsWith<char16_t>(value, u"}")) {
                        if (!readExpressions()) {
                            return getEvent();
                        }
                        break;
                    }
                }
            }
        } else if (event == Event::kStartNamespace || event == Event::kEndNamespace) {
            if (mParser->getNamespaceUri() == kBindingNamespaceUri) {
                // Skip binding namespace tags.
                continue;
            }
        }
        return event;
    }
    return Event::kBadDocument;
}

bool BindingXmlPullParser::writeToFile(std::ostream& out) const {
    out << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
    out << "<Layout directory=\"\" layout=\"\" layoutId=\"\">\n";

    // Write the variables.
    out << "  <Variables>\n";
    for (const VarDecl& v : mVarDecls) {
        out << "    <entries name=\"" << v.name << "\" type=\"" << v.type << "\"/>\n";
    }
    out << "  </Variables>\n";

    // Write the imports.

    std::stringstream tagGen;

    // Write the targets.
    out << "  <Targets>\n";
    for (const Target& t : mTargets) {
        tagGen.str({});
        tagGen << kBindingTagPrefix << t.tagId;
        out << "    <Target boundClass=\"" << t.className << "\" id=\"" << t.id
            << "\" tag=\"" << tagGen.str() << "\">\n";
        out << "      <Expressions>\n";
        for (const XmlPullParser::Attribute& a : t.expressions) {
            out << "        <Expression attribute=\"" << a.namespaceUri << ":" << a.name
                << "\" text=\"" << a.value << "\"/>\n";
        }
        out << "      </Expressions>\n";
        out << "    </Target>\n";
    }
    out << "  </Targets>\n";

    out << "</Layout>\n";
    return bool(out);
}

XmlPullParser::const_iterator BindingXmlPullParser::beginAttributes() const {
    if (mOverride) {
        return mAttributes.begin();
    }
    return mParser->beginAttributes();
}

XmlPullParser::const_iterator BindingXmlPullParser::endAttributes() const {
    if (mOverride) {
        return mAttributes.end();
    }
    return mParser->endAttributes();
}

size_t BindingXmlPullParser::getAttributeCount() const {
    if (mOverride) {
        return mAttributes.size();
    }
    return mParser->getAttributeCount();
}

XmlPullParser::Event BindingXmlPullParser::getEvent() const {
    if (!mLastError.empty()) {
        return Event::kBadDocument;
    }
    return mParser->getEvent();
}

const std::string& BindingXmlPullParser::getLastError() const {
    if (!mLastError.empty()) {
        return mLastError;
    }
    return mParser->getLastError();
}

const std::u16string& BindingXmlPullParser::getComment() const {
    return mParser->getComment();
}

size_t BindingXmlPullParser::getLineNumber() const {
    return mParser->getLineNumber();
}

size_t BindingXmlPullParser::getDepth() const {
    return mParser->getDepth();
}

const std::u16string& BindingXmlPullParser::getText() const {
    return mParser->getText();
}

const std::u16string& BindingXmlPullParser::getNamespacePrefix() const {
    return mParser->getNamespacePrefix();
}

const std::u16string& BindingXmlPullParser::getNamespaceUri() const {
    return mParser->getNamespaceUri();
}

bool BindingXmlPullParser::applyPackageAlias(std::u16string* package,
                                             const std::u16string& defaultPackage) const {
    return mParser->applyPackageAlias(package, defaultPackage);
}

const std::u16string& BindingXmlPullParser::getElementNamespace() const {
    return mParser->getElementNamespace();
}

const std::u16string& BindingXmlPullParser::getElementName() const {
    return mParser->getElementName();
}

} // namespace aapt
