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

#ifndef AAPT_XML_FLATTENER_H
#define AAPT_XML_FLATTENER_H

#include "BigBuffer.h"
#include "Maybe.h"
#include "Resolver.h"
#include "Source.h"
#include "XmlDom.h"

#include <string>

namespace aapt {
namespace xml {

/**
 * Flattens an XML file into a binary representation parseable by
 * the Android resource system.
 */
bool flatten(Node* root, const std::u16string& defaultPackage, BigBuffer* outBuffer);

/**
 * Options for flattenAndLink.
 */
struct FlattenOptions {
    /**
     * Keep attribute raw string values along with typed values.
     */
    bool keepRawValues = false;

    /**
     * If set, any attribute introduced in a later SDK will not be encoded.
     */
    Maybe<size_t> maxSdkAttribute;
};

/**
 * Like flatten(Node*,BigBuffer*), but references to resources are checked
 * and string values are transformed to typed data where possible.
 *
 * `defaultPackage` is used when a reference has no package or the namespace URI
 * "http://schemas.android.com/apk/res-auto" is used.
 *
 * `resolver` is used to resolve references to resources.
 */
Maybe<size_t> flattenAndLink(const Source& source, Node* root,
                             const std::u16string& defaultPackage,
                             const std::shared_ptr<IResolver>& resolver,
                             const FlattenOptions& options, BigBuffer* outBuffer);

} // namespace xml
} // namespace aapt

#endif // AAPT_XML_FLATTENER_H
