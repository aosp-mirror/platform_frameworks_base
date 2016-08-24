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

#include "ResourceValues.h"
#include "util/Maybe.h"

#include <string>

namespace aapt {
namespace xml {

constexpr const char16_t* kSchemaAuto = u"http://schemas.android.com/apk/res-auto";
constexpr const char16_t* kSchemaPublicPrefix = u"http://schemas.android.com/apk/res/";
constexpr const char16_t* kSchemaPrivatePrefix = u"http://schemas.android.com/apk/prv/res/";
constexpr const char16_t* kSchemaAndroid = u"http://schemas.android.com/apk/res/android";

/**
 * Result of extracting a package name from a namespace URI declaration.
 */
struct ExtractedPackage {
    /**
     * The name of the package. This can be the empty string, which means that the package
     * should be assumed to be the package being compiled.
     */
    std::u16string package;

    /**
     * True if the package's private namespace was declared. This means that private resources
     * are made visible.
     */
    bool privateNamespace;
};

/**
 * Returns an ExtractedPackage struct if the namespace URI is of the form:
 * http://schemas.android.com/apk/res/<package> or
 * http://schemas.android.com/apk/prv/res/<package>
 *
 * Special case: if namespaceUri is http://schemas.android.com/apk/res-auto,
 * returns an empty package name.
 */
Maybe<ExtractedPackage> extractPackageFromNamespace(const std::u16string& namespaceUri);

/**
 * Interface representing a stack of XML namespace declarations. When looking up the package
 * for a namespace prefix, the stack is checked from top to bottom.
 */
struct IPackageDeclStack {
    virtual ~IPackageDeclStack() = default;

    /**
     * Returns an ExtractedPackage struct if the alias given corresponds with a package declaration.
     */
    virtual Maybe<ExtractedPackage> transformPackageAlias(
            const StringPiece16& alias, const StringPiece16& localPackage) const = 0;
};

/**
 * Helper function for transforming the original Reference inRef to a fully qualified reference
 * via the IPackageDeclStack. This will also mark the Reference as private if the namespace of
 * the package declaration was private.
 */
void transformReferenceFromNamespace(IPackageDeclStack* declStack,
                                     const StringPiece16& localPackage, Reference* inRef);

} // namespace xml
} // namespace aapt

#endif /* AAPT_XML_XMLUTIL_H */
