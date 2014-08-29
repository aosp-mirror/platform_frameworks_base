/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef __AAPT_XML_H
#define __AAPT_XML_H

#include <androidfw/ResourceTypes.h>
#include <utils/String8.h>

/**
 * Utility methods for dealing with ResXMLTree.
 */
namespace AaptXml {

/**
 * Returns the index of the attribute, or < 0 if it was not found.
 */
ssize_t indexOfAttribute(const android::ResXMLTree& tree, uint32_t attrRes);

/**
 * Returns the string value for the specified attribute.
 * The string must be present in the ResXMLTree's string pool (inline in the XML).
 */
android::String8 getAttribute(const android::ResXMLTree& tree, const char* ns,
        const char* attr, android::String8* outError = NULL);

/**
 * Returns the string value for the specified attribute, or an empty string
 * if the attribute does not exist.
 * The string must be present in the ResXMLTree's string pool (inline in the XML).
 */
android::String8 getAttribute(const android::ResXMLTree& tree, uint32_t attrRes,
        android::String8* outError = NULL);

/**
 * Returns the integer value for the specified attribute, or the default value
 * if the attribute does not exist.
 * The integer must be declared inline in the XML.
 */
int32_t getIntegerAttribute(const android::ResXMLTree& tree, const char* ns,
        const char* attr, int32_t defValue = -1, android::String8* outError = NULL);

/**
 * Returns the integer value for the specified attribute, or the default value
 * if the attribute does not exist.
 * The integer must be declared inline in the XML.
 */
inline int32_t getIntegerAttribute(const android::ResXMLTree& tree, const char* ns,
        const char* attr, android::String8* outError) {
    return getIntegerAttribute(tree, ns, attr, -1, outError);
}

/**
 * Returns the integer value for the specified attribute, or the default value
 * if the attribute does not exist.
 * The integer must be declared inline in the XML.
 */
int32_t getIntegerAttribute(const android::ResXMLTree& tree, uint32_t attrRes,
        int32_t defValue = -1, android::String8* outError = NULL);

/**
 * Returns the integer value for the specified attribute, or the default value
 * if the attribute does not exist.
 * The integer must be declared inline in the XML.
 */
inline int32_t getIntegerAttribute(const android::ResXMLTree& tree, uint32_t attrRes,
        android::String8* outError) {
    return getIntegerAttribute(tree, attrRes, -1, outError);
}

/**
 * Returns the integer value for the specified attribute, or the default value
 * if the attribute does not exist.
 * The integer may be a resource in the supplied ResTable.
 */
int32_t getResolvedIntegerAttribute(const android::ResTable& resTable,
        const android::ResXMLTree& tree, uint32_t attrRes, int32_t defValue = -1,
        android::String8* outError = NULL);

/**
 * Returns the integer value for the specified attribute, or the default value
 * if the attribute does not exist.
 * The integer may be a resource in the supplied ResTable.
 */
inline int32_t getResolvedIntegerAttribute(const android::ResTable& resTable,
        const android::ResXMLTree& tree, uint32_t attrRes,
        android::String8* outError) {
    return getResolvedIntegerAttribute(resTable, tree, attrRes, -1, outError);
}

/**
 * Returns the string value for the specified attribute, or an empty string
 * if the attribute does not exist.
 * The string may be a resource in the supplied ResTable.
 */
android::String8 getResolvedAttribute(const android::ResTable& resTable,
        const android::ResXMLTree& tree, uint32_t attrRes,
        android::String8* outError = NULL);

/**
 * Returns the resource for the specified attribute in the outValue parameter.
 * The resource may be a resource in the supplied ResTable.
 */
void getResolvedResourceAttribute(const android::ResTable& resTable,
        const android::ResXMLTree& tree, uint32_t attrRes, android::Res_value* outValue,
        android::String8* outError = NULL);

} // namespace AaptXml

#endif // __AAPT_XML_H
