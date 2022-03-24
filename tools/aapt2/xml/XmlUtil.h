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

#ifndef AAPT_XML_XMLUTIL_H
#define AAPT_XML_XMLUTIL_H

#include <string>

#include "ResourceValues.h"

namespace aapt {
namespace xml {

constexpr const char* kSchemaAuto = "http://schemas.android.com/apk/res-auto";
constexpr const char* kSchemaPublicPrefix = "http://schemas.android.com/apk/res/";
constexpr const char* kSchemaPrivatePrefix = "http://schemas.android.com/apk/prv/res/";
constexpr const char* kSchemaAndroid = "http://schemas.android.com/apk/res/android";
constexpr const char* kSchemaTools = "http://schemas.android.com/tools";
constexpr const char* kSchemaAapt = "http://schemas.android.com/aapt";

// Result of extracting a package name from a namespace URI declaration.
struct ExtractedPackage {
  // The name of the package. This can be the empty string, which means that the package
  // should be assumed to be the same as the CallSite it was defined in.
  std::string package;

  // True if the package's private namespace was declared. This means that private resources
  // are made visible.
  bool private_namespace;

  friend inline bool operator==(const ExtractedPackage& a, const ExtractedPackage& b) {
    return a.package == b.package && a.private_namespace == b.private_namespace;
  }
};

// Returns an ExtractedPackage struct if the namespace URI is of the form:
//   http://schemas.android.com/apk/res/<package> or
//   http://schemas.android.com/apk/prv/res/<package>
//
// Special case: if namespaceUri is http://schemas.android.com/apk/res-auto, returns an empty
// package name.
std::optional<ExtractedPackage> ExtractPackageFromNamespace(const std::string& namespace_uri);

// Returns an XML Android namespace for the given package of the form:
//   http://schemas.android.com/apk/res/<package>
//
// If privateReference == true, the package will be of the form:
//   http://schemas.android.com/apk/prv/res/<package>
std::string BuildPackageNamespace(const android::StringPiece& package,
                                  bool private_reference = false);

// Interface representing a stack of XML namespace declarations. When looking up the package for a
// namespace prefix, the stack is checked from top to bottom.
struct IPackageDeclStack {
  virtual ~IPackageDeclStack() = default;

  // Returns an ExtractedPackage struct if the alias given corresponds with a package declaration.
  virtual std::optional<ExtractedPackage> TransformPackageAlias(
      const android::StringPiece& alias) const = 0;
};

// Helper function for transforming the original Reference inRef to a fully qualified reference
// via the IPackageDeclStack. This will also mark the Reference as private if the namespace of the
// package declaration was private.
void ResolvePackage(const IPackageDeclStack* decl_stack, Reference* in_ref);

class Element;

// Strips out any attributes in the http://schemas.android.com/tools namespace, which is owned by
// Android Studio and should not make it to the final APK.
void StripAndroidStudioAttributes(Element* el);

}  // namespace xml
}  // namespace aapt

#endif /* AAPT_XML_XMLUTIL_H */
