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
#include "java/ClassDefinition.h"
#include "java/JavaClassGenerator.h"
#include "text/Unicode.h"
#include "xml/XmlDom.h"

using ::aapt::text::IsJavaIdentifier;

namespace aapt {

static std::optional<std::string> ExtractJavaIdentifier(IDiagnostics* diag, const Source& source,
                                                        const std::string& value) {
  std::string result = value;
  size_t pos = value.rfind('.');
  if (pos != std::string::npos) {
    result = result.substr(pos + 1);
  }

  // Normalize only the java identifier, leave the original value unchanged.
  if (result.find('-') != std::string::npos) {
    result = JavaClassGenerator::TransformToFieldName(result);
  }

  if (result.empty()) {
    diag->Error(DiagMessage(source) << "empty symbol");
    return {};
  }

  if (!IsJavaIdentifier(result)) {
    diag->Error(DiagMessage(source) << "invalid Java identifier '" << result << "'");
    return {};
  }
  return result;
}

static bool WriteSymbol(const Source& source, IDiagnostics* diag, xml::Element* el,
                        ClassDefinition* class_def) {
  xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "name");
  if (!attr) {
    diag->Error(DiagMessage(source) << "<" << el->name << "> must define 'android:name'");
    return false;
  }

  std::optional<std::string> result =
      ExtractJavaIdentifier(diag, source.WithLine(el->line_number), attr->value);
  if (!result) {
    return false;
  }

  std::unique_ptr<StringMember> string_member =
      util::make_unique<StringMember>(result.value(), attr->value);
  string_member->GetCommentBuilder()->AppendComment(el->comment);

  if (class_def->AddMember(std::move(string_member)) == ClassDefinition::Result::kOverridden) {
    diag->Warn(DiagMessage(source.WithLine(el->line_number))
               << "duplicate definitions of '" << result.value() << "', overriding previous");
  }
  return true;
}

std::unique_ptr<ClassDefinition> GenerateManifestClass(IDiagnostics* diag, xml::XmlResource* res) {
  xml::Element* el = xml::FindRootElement(res->root.get());
  if (!el) {
    diag->Error(DiagMessage(res->file.source) << "no root tag defined");
    return {};
  }

  if (el->name != "manifest" && !el->namespace_uri.empty()) {
    diag->Error(DiagMessage(res->file.source) << "no <manifest> root tag defined");
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
        error |= !WriteSymbol(res->file.source, diag, child_el, permission_class.get());
      } else if (child_el->name == "permission-group") {
        error |= !WriteSymbol(res->file.source, diag, child_el, permission_group_class.get());
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
