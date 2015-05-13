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
#include "XmlPullParser.h"

#include <string>

namespace aapt {

/**
 * Flattens an XML file into a binary representation parseable by
 * the Android resource system. References to resources are checked
 * and string values are transformed to typed data where possible.
 */
class XmlFlattener {
public:
    struct Options {
        /**
         * The package to use when a reference has no package specified
         * (or a namespace URI equals "http://schemas.android.com/apk/res-auto").
         */
        std::u16string defaultPackage;

        /**
         * If set, tells the XmlFlattener to strip out
         * attributes that have been introduced after
         * max SDK.
         */
        Maybe<size_t> maxSdkAttribute;

        /**
         * Setting this to true will keep the raw string value of
         * an attribute's value when it has been resolved.
         */
        bool keepRawValues = false;
    };

    /**
     * Creates a flattener with a Resolver to resolve references
     * and attributes.
     */
    XmlFlattener(const std::shared_ptr<ResourceTable>& table,
                 const std::shared_ptr<IResolver>& resolver);

    XmlFlattener(const XmlFlattener&) = delete; // Not copyable.

    /**
     * Flatten an XML file, reading from the XML parser and writing to the
     * BigBuffer. The source object is mainly for logging errors. If the
     * function succeeds, returns the smallest SDK version of an attribute that
     * was stripped out. If no attributes were stripped out, the return value
     * is 0.
     */
    Maybe<size_t> flatten(const Source& source, const std::shared_ptr<XmlPullParser>& parser,
                          BigBuffer* outBuffer, Options options);

private:
    std::shared_ptr<ResourceTable> mTable;
    std::shared_ptr<IResolver> mResolver;
};

} // namespace aapt

#endif // AAPT_XML_FLATTENER_H
