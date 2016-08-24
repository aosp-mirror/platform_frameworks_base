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

#ifndef AAPT_FLATTEN_XMLFLATTENER_H
#define AAPT_FLATTEN_XMLFLATTENER_H

#include "process/IResourceTableConsumer.h"
#include "util/BigBuffer.h"
#include "xml/XmlDom.h"

namespace aapt {

struct XmlFlattenerOptions {
    /**
     * Keep attribute raw string values along with typed values.
     */
    bool keepRawValues = false;

    /**
     * If set, the max SDK level of attribute to flatten. All others are ignored.
     */
    Maybe<size_t> maxSdkLevel;
};

class XmlFlattener : public IXmlResourceConsumer {
public:
    XmlFlattener(BigBuffer* buffer, XmlFlattenerOptions options) :
            mBuffer(buffer), mOptions(options) {
    }

    bool consume(IAaptContext* context, xml::XmlResource* resource) override;

private:
    BigBuffer* mBuffer;
    XmlFlattenerOptions mOptions;

    bool flatten(IAaptContext* context, xml::Node* node);
};

} // namespace aapt

#endif /* AAPT_FLATTEN_XMLFLATTENER_H */
