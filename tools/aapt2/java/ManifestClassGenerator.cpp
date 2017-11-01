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

#include "java/ManifestClassGenerator.h"

#include <algorithm>

#include "Source.h"
#include "java/AnnotationProcessor.h"
#include "java/ClassDefinition.h"
#include "util/Maybe.h"
#include "xml/XmlDom.h"

using android::StringPiece;

namespace aapt {

static Maybe<StringPiece> ExtractJavaIdentifier(IDiagnostics* diag,
                                                const Source& source,
                                                const StringPiece& value) {
  const StringPiece sep = ".";
  auto iter = std::find_end(value.begin(), value.end(), sep.begin(), sep.end());

  StringPiece result;
  if (iter != value.end()) {
    result.assign(iter + sep.size(), value.end() - (iter + sep.size()));
  } else {
    result = value;
  }

  if (result.empty()) {
    diag->Error(DiagMessage(source) << "empty symbol");
    return {};
  }

  iter = util::FindNonAlphaNumericAndNotInSet(result, "_");
  if (iter != result.end()) {
    diag->Error(DiagMessage(source) << "invalid character '"
                                    << StringPiece(iter, 1) << "' in '"
                                    << result << "'");
    return {};
  }

  if (*result.begin() >= '0' && *result.begin() <= '9') {
    diag->Error(DiagMessage(source) << "symbol can not start with a digit");
    return {};
  }

  return result;
}

static bool WriteSymbol(const Source& source, IDiagnostics* diag,
                        xml::Element* el, ClassDefinition* class_def) {
  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "name");
  if (!attr) {
    diag->Error(DiagMessage(source) << "<" << el->name
                                    << "> must define 'android:name'");
    return false;
  }

  Maybe<StringPiece> result = ExtractJavaIdentifier(
      diag, source.WithLine(el->line_number), attr->value);
  if (!result) {
    return false;
  }

  std::unique_ptr<StringMember> string_member =
      util::make_unique<StringMember>(result.value(), attr->value);
  string_member->GetCommentBuilder()->AppendComment(el->comment);

  class_def->AddMember(std::move(string_member));
  return true;
}

std::unique_ptr<ClassDefinition> GenerateManifestClass(IDiagnostics* diag,
                                                       xml::XmlResource* res) {
  xml::Element* el = xml::FindRootElement(res->root.get());
  if (!el) {
    diag->Error(DiagMessage(res->file.source) << "no root tag defined");
    return {};
  }

  if (el->name != "manifest" && !el->namespace_uri.empty()) {
    diag->Error(DiagMessage(res->file.source)
                << "no <manifest> root tag defined");
    return {};
  }

  std::unique_ptr<ClassDefinition> permission_class =
      util::make_unique<ClassDefinition>("permission", ClassQualifier::kStatic, false);
  std::unique_ptr<ClassDefinition> permission_group_class =
      util::make_unique<ClassDefinition>("permission_group", ClassQualifier::kStatic, false);

  bool error = false;
  std::vector<xml::Element*> children = el->GetChildElements();
  for (xml::Element* child_el : children) {
    if (child_el->namespace_uri.empty()) {
      if (child_el->name == "permission") {
        error |= !WriteSymbol(res->file.source, diag, child_el,
                              permission_class.get());
      } else if (child_el->name == "permission-group") {
        error |= !WriteSymbol(res->file.source, diag, child_el,
                              permission_group_class.get());
      }
    }
  }

  if (error) {
    return {};
  }

  std::unique_ptr<ClassDefinition> manifest_class =
      util::make_unique<ClassDefinition>("Manifest", ClassQualifier::kNone, false);
  manifest_class->AddMember(std::move(permission_class));
  manifest_class->AddMember(std::move(permission_group_class));
  return manifest_class;
}

}  // namespace aapt
