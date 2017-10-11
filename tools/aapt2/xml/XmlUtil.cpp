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

#include <string>

#include "util/Maybe.h"
#include "util/Util.h"

using android::StringPiece;

namespace aapt {
namespace xml {

std::string BuildPackageNamespace(const StringPiece& package,
                                  bool private_reference) {
  std::string result =
      private_reference ? kSchemaPrivatePrefix : kSchemaPublicPrefix;
  result.append(package.data(), package.size());
  return result;
}

Maybe<ExtractedPackage> ExtractPackageFromNamespace(
    const std::string& namespace_uri) {
  if (util::StartsWith(namespace_uri, kSchemaPublicPrefix)) {
    StringPiece schema_prefix = kSchemaPublicPrefix;
    StringPiece package = namespace_uri;
    package = package.substr(schema_prefix.size(),
                             package.size() - schema_prefix.size());
    if (package.empty()) {
      return {};
    }
    return ExtractedPackage{package.to_string(), false /* is_private */};

  } else if (util::StartsWith(namespace_uri, kSchemaPrivatePrefix)) {
    StringPiece schema_prefix = kSchemaPrivatePrefix;
    StringPiece package = namespace_uri;
    package = package.substr(schema_prefix.size(),
                             package.size() - schema_prefix.size());
    if (package.empty()) {
      return {};
    }
    return ExtractedPackage{package.to_string(), true /* is_private */};

  } else if (namespace_uri == kSchemaAuto) {
    return ExtractedPackage{std::string(), true /* is_private */};
  }
  return {};
}

void TransformReferenceFromNamespace(IPackageDeclStack* decl_stack,
                                     const StringPiece& local_package,
                                     Reference* in_ref) {
  if (in_ref->name) {
    if (Maybe<ExtractedPackage> transformed_package =
            decl_stack->TransformPackageAlias(in_ref->name.value().package,
                                              local_package)) {
      ExtractedPackage& extracted_package = transformed_package.value();
      in_ref->name.value().package = std::move(extracted_package.package);

      // If the reference was already private (with a * prefix) and the
      // namespace is public,
      // we keep the reference private.
      in_ref->private_reference |= extracted_package.private_namespace;
    }
  }
}

}  // namespace xml
}  // namespace aapt
