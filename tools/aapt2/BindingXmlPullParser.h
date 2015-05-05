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

#ifndef AAPT_BINDING_XML_PULL_PARSER_H
#define AAPT_BINDING_XML_PULL_PARSER_H

#include "XmlPullParser.h"

#include <iostream>
#include <memory>
#include <string>

namespace aapt {

class BindingXmlPullParser : public XmlPullParser {
public:
    BindingXmlPullParser(const std::shared_ptr<XmlPullParser>& parser);
    BindingXmlPullParser(const BindingXmlPullParser& rhs) = delete;

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

    bool writeToFile(std::ostream& out) const;

private:
    struct VarDecl {
        std::string name;
        std::string type;
    };

    struct Import {
        std::string name;
        std::string type;
    };

    struct Target {
        std::string className;
        std::string id;
        int32_t tagId;

        std::vector<XmlPullParser::Attribute> expressions;
    };

    bool readVariableDeclaration();
    bool readExpressions();

    std::shared_ptr<XmlPullParser> mParser;
    std::string mLastError;
    bool mOverride;
    std::vector<XmlPullParser::Attribute> mAttributes;
    std::vector<VarDecl> mVarDecls;
    std::vector<Target> mTargets;
    int32_t mNextTagId;
};

} // namespace aapt

#endif // AAPT_BINDING_XML_PULL_PARSER_H
