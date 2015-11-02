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
#include "XmlDom.h"

#include "java/AnnotationProcessor.h"
#include "java/ManifestClassGenerator.h"
#include "util/Maybe.h"

#include <algorithm>

namespace aapt {

constexpr const char16_t* kSchemaAndroid = u"http://schemas.android.com/apk/res/android";

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

static bool writeSymbol(IDiagnostics* diag, const Source& source, xml::Element* el,
                        std::ostream* out) {
    xml::Attribute* attr = el->findAttribute(kSchemaAndroid, u"name");
    if (!attr) {
        diag->error(DiagMessage(source) << "<" << el->name << "> must define 'android:name'");
        return false;
    }

    Maybe<StringPiece16> result = extractJavaIdentifier(diag, source.withLine(el->lineNumber),
                                                        attr->value);
    if (!result) {
        return false;
    }

    *out << "\n";

    if (!util::trimWhitespace(el->comment).empty()) {
        AnnotationProcessor processor("    ");
        processor.appendComment(el->comment);
        *out << processor.buildComment() << "\n";
        std::string annotations = processor.buildAnnotations();
        if (!annotations.empty()) {
            *out << annotations << "\n";
        }
    }
    *out << "    public static final String " << result.value() << "=\"" << attr->value << "\";\n";
    return true;
}

bool ManifestClassGenerator::generate(IDiagnostics* diag, const StringPiece16& package,
                                      XmlResource* res, std::ostream* out) {
    xml::Element* el = xml::findRootElement(res->root.get());
    if (!el) {
        return false;
    }

    if (el->name != u"manifest" && !el->namespaceUri.empty()) {
        diag->error(DiagMessage(res->file.source) << "no <manifest> root tag defined");
        return false;
    }

    *out << "package " << package << ";\n\n"
         << "public class Manifest {\n";

    bool error = false;
    std::vector<xml::Element*> children = el->getChildElements();


    // First write out permissions.
    *out << "  public static class permission {\n";
    for (xml::Element* childEl : children) {
        if (childEl->namespaceUri.empty() && childEl->name == u"permission") {
            error |= !writeSymbol(diag, res->file.source, childEl, out);
        }
    }
    *out << "  }\n";

    // Next write out permission groups.
    *out << "  public static class permission_group {\n";
    for (xml::Element* childEl : children) {
        if (childEl->namespaceUri.empty() && childEl->name == u"permission-group") {
            error |= !writeSymbol(diag, res->file.source, childEl, out);
        }
    }
    *out << "  }\n";

    *out << "}\n";
    return !error;
}

} // namespace aapt
