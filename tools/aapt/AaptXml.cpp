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

#include <androidfw/ResourceTypes.h>
#include <utils/String8.h>

#include "AaptXml.h"

using namespace android;

namespace AaptXml {

static String8 getStringAttributeAtIndex(const ResXMLTree& tree, ssize_t attrIndex,
        String8* outError) {
    Res_value value;
    if (tree.getAttributeValue(attrIndex, &value) < 0) {
        if (outError != NULL) {
            *outError = "could not find attribute at index";
        }
        return String8();
    }

    if (value.dataType != Res_value::TYPE_STRING) {
        if (outError != NULL) {
            *outError = "attribute is not a string value";
        }
        return String8();
    }

    size_t len;
    const uint16_t* str = tree.getAttributeStringValue(attrIndex, &len);
    return str ? String8(str, len) : String8();
}

static int32_t getIntegerAttributeAtIndex(const ResXMLTree& tree, ssize_t attrIndex,
    int32_t defValue, String8* outError) {
    Res_value value;
    if (tree.getAttributeValue(attrIndex, &value) < 0) {
        if (outError != NULL) {
            *outError = "could not find attribute at index";
        }
        return defValue;
    }

    if (value.dataType < Res_value::TYPE_FIRST_INT
            || value.dataType > Res_value::TYPE_LAST_INT) {
        if (outError != NULL) {
            *outError = "attribute is not an integer value";
        }
        return defValue;
    }
    return value.data;
}


ssize_t indexOfAttribute(const ResXMLTree& tree, uint32_t attrRes) {
    size_t attrCount = tree.getAttributeCount();
    for (size_t i = 0; i < attrCount; i++) {
        if (tree.getAttributeNameResID(i) == attrRes) {
            return (ssize_t)i;
        }
    }
    return -1;
}

String8 getAttribute(const ResXMLTree& tree, const char* ns,
        const char* attr, String8* outError) {
    ssize_t idx = tree.indexOfAttribute(ns, attr);
    if (idx < 0) {
        return String8();
    }
    return getStringAttributeAtIndex(tree, idx, outError);
}

String8 getAttribute(const ResXMLTree& tree, uint32_t attrRes, String8* outError) {
    ssize_t idx = indexOfAttribute(tree, attrRes);
    if (idx < 0) {
        return String8();
    }
    return getStringAttributeAtIndex(tree, idx, outError);
}

String8 getResolvedAttribute(const ResTable& resTable, const ResXMLTree& tree,
        uint32_t attrRes, String8* outError) {
    ssize_t idx = indexOfAttribute(tree, attrRes);
    if (idx < 0) {
        return String8();
    }
    Res_value value;
    if (tree.getAttributeValue(idx, &value) != NO_ERROR) {
        if (value.dataType == Res_value::TYPE_STRING) {
            size_t len;
            const uint16_t* str = tree.getAttributeStringValue(idx, &len);
            return str ? String8(str, len) : String8();
        }
        resTable.resolveReference(&value, 0);
        if (value.dataType != Res_value::TYPE_STRING) {
            if (outError != NULL) {
                *outError = "attribute is not a string value";
            }
            return String8();
        }
    }
    size_t len;
    const Res_value* value2 = &value;
    const char16_t* str = resTable.valueToString(value2, 0, NULL, &len);
    return str ? String8(str, len) : String8();
}

int32_t getIntegerAttribute(const ResXMLTree& tree, const char* ns,
        const char* attr, int32_t defValue, String8* outError) {
    ssize_t idx = tree.indexOfAttribute(ns, attr);
    if (idx < 0) {
        return defValue;
    }
    return getIntegerAttributeAtIndex(tree, idx, defValue, outError);
}

int32_t getIntegerAttribute(const ResXMLTree& tree, uint32_t attrRes, int32_t defValue,
        String8* outError) {
    ssize_t idx = indexOfAttribute(tree, attrRes);
    if (idx < 0) {
        return defValue;
    }
    return getIntegerAttributeAtIndex(tree, idx, defValue, outError);
}

int32_t getResolvedIntegerAttribute(const ResTable& resTable, const ResXMLTree& tree,
        uint32_t attrRes, int32_t defValue, String8* outError) {
    ssize_t idx = indexOfAttribute(tree, attrRes);
    if (idx < 0) {
        return defValue;
    }
    Res_value value;
    if (tree.getAttributeValue(idx, &value) != NO_ERROR) {
        if (value.dataType == Res_value::TYPE_REFERENCE) {
            resTable.resolveReference(&value, 0);
        }
        if (value.dataType < Res_value::TYPE_FIRST_INT
                || value.dataType > Res_value::TYPE_LAST_INT) {
            if (outError != NULL) {
                *outError = "attribute is not an integer value";
            }
            return defValue;
        }
    }
    return value.data;
}

void getResolvedResourceAttribute(const ResTable& resTable, const ResXMLTree& tree,
        uint32_t attrRes, Res_value* outValue, String8* outError) {
    ssize_t idx = indexOfAttribute(tree, attrRes);
    if (idx < 0) {
        if (outError != NULL) {
            *outError = "attribute could not be found";
        }
        return;
    }
    if (tree.getAttributeValue(idx, outValue) != NO_ERROR) {
        if (outValue->dataType == Res_value::TYPE_REFERENCE) {
            resTable.resolveReference(outValue, 0);
        }
        // The attribute was found and was resolved if need be.
        return;
    }
    if (outError != NULL) {
        *outError = "error getting resolved resource attribute";
    }
}

} // namespace AaptXml
