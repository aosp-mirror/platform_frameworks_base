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

#include "Source.h"
#include "java/AnnotationProcessor.h"
#include "java/ClassDefinition.h"
#include "java/ManifestClassGenerator.h"
#include "util/Maybe.h"
#include "xml/XmlDom.h"

#include <algorithm>

namespace aapt {

static Maybe<StringPiece16> extractJavaIdentifier(IDiagnostics* diag, const Source& source,
                                                  const StringPiece16& value) {
    const StringPiece16 sep = u".";
    auto iter = std::find_end(value.begin(), value.end(), sep.begin(), sep.end());

    StringPiece16 result;
    if (iter != value.end()) {
        result.assign(iter + sep.size(), value.end() - (iter + sep.size()));
    } else {
        result = value;
    }

    if (result.empty()) {
        diag->error(DiagMessage(source) << "empty symbol");
        return {};
    }

    iter = util::findNonAlphaNumericAndNotInSet(result, u"_");
    if (iter != result.end()) {
        diag->error(DiagMessage(source)
                    << "invalid character '" << StringPiece16(iter, 1)
                    << "' in '" << result << "'");
        return {};
    }

    if (*result.begin() >= u'0' && *result.begin() <= u'9') {
        diag->error(DiagMessage(source) << "symbol can not start with a digit");
        return {};
    }

    return result;
}

static bool writeSymbol(const Source& source, IDiagnostics* diag, xml::Element* el,
                        ClassDefinition* classDef) {
    xml::Attribute* attr = el->findAttribute(xml::kSchemaAndroid, u"name");
    if (!attr) {
        diag->error(DiagMessage(source) << "<" << el->name << "> must define 'android:name'");
        return false;
    }

    Maybe<StringPiece16> result = extractJavaIdentifier(diag, source.withLine(el->lineNumber),
                                                        attr->value);
    if (!result) {
        return false;
    }

    std::unique_ptr<StringMember> stringMember = util::make_unique<StringMember>(
            util::utf16ToUtf8(result.value()), util::utf16ToUtf8(attr->value));
    stringMember->getCommentBuilder()->appendComment(el->comment);

    classDef->addMember(std::move(stringMember));
    return true;
}

std::unique_ptr<ClassDefinition> generateManifestClass(IDiagnostics* diag, xml::XmlResource* res) {
    xml::Element* el = xml::findRootElement(res->root.get());
    if (!el) {
        diag->error(DiagMessage(res->file.source) << "no root tag defined");
        return {};
    }

    if (el->name != u"manifest" && !el->namespaceUri.empty()) {
        diag->error(DiagMessage(res->file.source) << "no <manifest> root tag defined");
        return {};
    }

    std::unique_ptr<ClassDefinition> permissionClass =
            util::make_unique<ClassDefinition>("permission", ClassQualifier::Static, false);
    std::unique_ptr<ClassDefinition> permissionGroupClass =
            util::make_unique<ClassDefinition>("permission_group", ClassQualifier::Static, false);

    bool error = false;

    std::vector<xml::Element*> children = el->getChildElements();
    for (xml::Element* childEl : children) {
        if (childEl->namespaceUri.empty()) {
            if (childEl->name == u"permission") {
                error |= !writeSymbol(res->file.source, diag, childEl, permissionClass.get());
            } else if (childEl->name == u"permission-group") {
                error |= !writeSymbol(res->file.source, diag, childEl, permissionGroupClass.get());
            }
        }
    }

    if (error) {
        return {};
    }

    std::unique_ptr<ClassDefinition> manifestClass =
            util::make_unique<ClassDefinition>("Manifest", ClassQualifier::None, false);
    manifestClass->addMember(std::move(permissionClass));
    manifestClass->addMember(std::move(permissionGroupClass));
    return manifestClass;
}

} // namespace aapt
