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

#include "xml/XmlUtil.h"

#include <algorithm>
#include <string>

#include "util/Util.h"
#include "xml/XmlDom.h"

using ::android::StringPiece;

namespace aapt {
namespace xml {

std::string BuildPackageNamespace(const StringPiece& package, bool private_reference) {
  std::string result = private_reference ? kSchemaPrivatePrefix : kSchemaPublicPrefix;
  result.append(package.data(), package.size());
  return result;
}

std::optional<ExtractedPackage> ExtractPackageFromNamespace(const std::string& namespace_uri) {
  if (util::StartsWith(namespace_uri, kSchemaPublicPrefix)) {
    StringPiece schema_prefix = kSchemaPublicPrefix;
    StringPiece package = namespace_uri;
    package = package.substr(schema_prefix.size(), package.size() - schema_prefix.size());
    if (package.empty()) {
      return {};
    }
    return ExtractedPackage{package.to_string(), false /* is_private */};

  } else if (util::StartsWith(namespace_uri, kSchemaPrivatePrefix)) {
    StringPiece schema_prefix = kSchemaPrivatePrefix;
    StringPiece package = namespace_uri;
    package = package.substr(schema_prefix.size(), package.size() - schema_prefix.size());
    if (package.empty()) {
      return {};
    }
    return ExtractedPackage{package.to_string(), true /* is_private */};

  } else if (namespace_uri == kSchemaAuto) {
    return ExtractedPackage{std::string(), true /* is_private */};
  }
  return {};
}

void ResolvePackage(const IPackageDeclStack* decl_stack, Reference* in_ref) {
  if (in_ref->name) {
    if (std::optional<ExtractedPackage> transformed_package =
            decl_stack->TransformPackageAlias(in_ref->name.value().package)) {
      ExtractedPackage& extracted_package = transformed_package.value();
      in_ref->name.value().package = std::move(extracted_package.package);

      // If the reference was already private (with a * prefix) and the
      // namespace is public, we keep the reference private.
      in_ref->private_reference |= extracted_package.private_namespace;
    }
  }
}

namespace {

class ToolsNamespaceRemover : public Visitor {
 public:
  using Visitor::Visit;

  void Visit(Element* el) override {
    auto new_end =
        std::remove_if(el->namespace_decls.begin(), el->namespace_decls.end(),
                       [](const NamespaceDecl& decl) -> bool { return decl.uri == kSchemaTools; });
    el->namespace_decls.erase(new_end, el->namespace_decls.end());

    auto new_attr_end = std::remove_if(
        el->attributes.begin(), el->attributes.end(),
        [](const Attribute& attr) -> bool { return attr.namespace_uri == kSchemaTools; });
    el->attributes.erase(new_attr_end, el->attributes.end());

    Visitor::Visit(el);
  }
};

}  // namespace

void StripAndroidStudioAttributes(Element* el) {
  ToolsNamespaceRemover remover;
  el->Accept(&remover);
}

}  // namespace xml
}  // namespace aapt
