/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "androidfw/AttributeFinder.h"
#include "androidfw/AttributeResolution.h"
#include "androidfw/ResourceTypes.h"

#include <android/log.h>
#include <cstdint>

constexpr bool kDebugStyles = false;

namespace android {

enum {
    STYLE_NUM_ENTRIES = 6,
    STYLE_TYPE = 0,
    STYLE_DATA = 1,
    STYLE_ASSET_COOKIE = 2,
    STYLE_RESOURCE_ID = 3,
    STYLE_CHANGING_CONFIGURATIONS = 4,
    STYLE_DENSITY = 5
};

class XmlAttributeFinder : public BackTrackingAttributeFinder<XmlAttributeFinder, size_t> {
public:
    explicit XmlAttributeFinder(const ResXMLParser* parser) :
        BackTrackingAttributeFinder(0, parser != NULL ? parser->getAttributeCount() : 0),
        mParser(parser) {
    }

    inline uint32_t getAttribute(size_t index) const {
        return mParser->getAttributeNameResID(index);
    }

private:
    const ResXMLParser* mParser;
};

class BagAttributeFinder :
        public BackTrackingAttributeFinder<BagAttributeFinder, const ResTable::bag_entry*> {
public:
    BagAttributeFinder(const ResTable::bag_entry* start, const ResTable::bag_entry* end) :
        BackTrackingAttributeFinder(start, end) {}

    inline uint32_t getAttribute(const ResTable::bag_entry* entry) const {
        return entry->map.name.ident;
    }
};

bool resolveAttrs(ResTable::Theme* theme,
                  uint32_t defStyleAttr,
                  uint32_t defStyleRes,
                  uint32_t* srcValues, size_t srcValuesLength,
                  uint32_t* attrs, size_t attrsLength,
                  uint32_t* outValues,
                  uint32_t* outIndices) {
    if (kDebugStyles) {
        ALOGI("APPLY STYLE: theme=0x%p defStyleAttr=0x%x "
              "defStyleRes=0x%x", theme, defStyleAttr, defStyleRes);
    }

    const ResTable& res = theme->getResTable();
    ResTable_config config;
    Res_value value;

    int indicesIdx = 0;

    // Load default style from attribute, if specified...
    uint32_t defStyleBagTypeSetFlags = 0;
    if (defStyleAttr != 0) {
        Res_value value;
        if (theme->getAttribute(defStyleAttr, &value, &defStyleBagTypeSetFlags) >= 0) {
            if (value.dataType == Res_value::TYPE_REFERENCE) {
                defStyleRes = value.data;
            }
        }
    }

    // Now lock down the resource object and start pulling stuff from it.
    res.lock();

    // Retrieve the default style bag, if requested.
    const ResTable::bag_entry* defStyleStart = NULL;
    uint32_t defStyleTypeSetFlags = 0;
    ssize_t bagOff = defStyleRes != 0
            ? res.getBagLocked(defStyleRes, &defStyleStart, &defStyleTypeSetFlags) : -1;
    defStyleTypeSetFlags |= defStyleBagTypeSetFlags;
    const ResTable::bag_entry* const defStyleEnd = defStyleStart + (bagOff >= 0 ? bagOff : 0);
    BagAttributeFinder defStyleAttrFinder(defStyleStart, defStyleEnd);

    // Now iterate through all of the attributes that the client has requested,
    // filling in each with whatever data we can find.
    ssize_t block = 0;
    uint32_t typeSetFlags;
    for (size_t ii=0; ii<attrsLength; ii++) {
        const uint32_t curIdent = attrs[ii];

        if (kDebugStyles) {
            ALOGI("RETRIEVING ATTR 0x%08x...", curIdent);
        }

        // Try to find a value for this attribute...  we prioritize values
        // coming from, first XML attributes, then XML style, then default
        // style, and finally the theme.
        value.dataType = Res_value::TYPE_NULL;
        value.data = Res_value::DATA_NULL_UNDEFINED;
        typeSetFlags = 0;
        config.density = 0;

        // Retrieve the current input value if available.
        if (srcValuesLength > 0 && srcValues[ii] != 0) {
            block = -1;
            value.dataType = Res_value::TYPE_ATTRIBUTE;
            value.data = srcValues[ii];
            if (kDebugStyles) {
                ALOGI("-> From values: type=0x%x, data=0x%08x", value.dataType, value.data);
            }
        }

        if (value.dataType == Res_value::TYPE_NULL) {
            const ResTable::bag_entry* const defStyleEntry = defStyleAttrFinder.find(curIdent);
            if (defStyleEntry != defStyleEnd) {
                block = defStyleEntry->stringBlock;
                typeSetFlags = defStyleTypeSetFlags;
                value = defStyleEntry->map.value;
                if (kDebugStyles) {
                    ALOGI("-> From def style: type=0x%x, data=0x%08x", value.dataType, value.data);
                }
            }
        }

        uint32_t resid = 0;
        if (value.dataType != Res_value::TYPE_NULL) {
            // Take care of resolving the found resource to its final value.
            ssize_t newBlock = theme->resolveAttributeReference(&value, block,
                    &resid, &typeSetFlags, &config);
            if (newBlock >= 0) block = newBlock;
            if (kDebugStyles) {
                ALOGI("-> Resolved attr: type=0x%x, data=0x%08x", value.dataType, value.data);
            }
        } else {
            // If we still don't have a value for this attribute, try to find
            // it in the theme!
            ssize_t newBlock = theme->getAttribute(curIdent, &value, &typeSetFlags);
            if (newBlock >= 0) {
                if (kDebugStyles) {
                    ALOGI("-> From theme: type=0x%x, data=0x%08x", value.dataType, value.data);
                }
                newBlock = res.resolveReference(&value, block, &resid,
                        &typeSetFlags, &config);
                if (newBlock >= 0) block = newBlock;
                if (kDebugStyles) {
                    ALOGI("-> Resolved theme: type=0x%x, data=0x%08x", value.dataType, value.data);
                }
            }
        }

        // Deal with the special @null value -- it turns back to TYPE_NULL.
        if (value.dataType == Res_value::TYPE_REFERENCE && value.data == 0) {
            if (kDebugStyles) {
                ALOGI("-> Setting to @null!");
            }
            value.dataType = Res_value::TYPE_NULL;
            value.data = Res_value::DATA_NULL_UNDEFINED;
            block = -1;
        }

        if (kDebugStyles) {
            ALOGI("Attribute 0x%08x: type=0x%x, data=0x%08x", curIdent, value.dataType,
                  value.data);
        }

        // Write the final value back to Java.
        outValues[STYLE_TYPE] = value.dataType;
        outValues[STYLE_DATA] = value.data;
        outValues[STYLE_ASSET_COOKIE] = block != -1
                ? static_cast<uint32_t>(res.getTableCookie(block)) : static_cast<uint32_t>(-1);
        outValues[STYLE_RESOURCE_ID] = resid;
        outValues[STYLE_CHANGING_CONFIGURATIONS] = typeSetFlags;
        outValues[STYLE_DENSITY] = config.density;

        if (outIndices != NULL && value.dataType != Res_value::TYPE_NULL) {
            indicesIdx++;
            outIndices[indicesIdx] = ii;
        }

        outValues += STYLE_NUM_ENTRIES;
    }

    res.unlock();

    if (outIndices != NULL) {
        outIndices[0] = indicesIdx;
    }
    return true;
}

bool applyStyle(ResTable::Theme* theme, ResXMLParser* xmlParser,
                uint32_t defStyleAttr,
                uint32_t defStyleRes,
                uint32_t* attrs, size_t attrsLength,
                uint32_t* outValues,
                uint32_t* outIndices) {
    if (kDebugStyles) {
        ALOGI("APPLY STYLE: theme=0x%p defStyleAttr=0x%x defStyleRes=0x%x xml=0x%p",
              theme, defStyleAttr, defStyleRes, xmlParser);
    }

    const ResTable& res = theme->getResTable();
    ResTable_config config;
    Res_value value;

    int indicesIdx = 0;

    // Load default style from attribute, if specified...
    uint32_t defStyleBagTypeSetFlags = 0;
    if (defStyleAttr != 0) {
        Res_value value;
        if (theme->getAttribute(defStyleAttr, &value, &defStyleBagTypeSetFlags) >= 0) {
            if (value.dataType == Res_value::TYPE_REFERENCE) {
                defStyleRes = value.data;
            }
        }
    }

    // Retrieve the style class associated with the current XML tag.
    int style = 0;
    uint32_t styleBagTypeSetFlags = 0;
    if (xmlParser != NULL) {
        ssize_t idx = xmlParser->indexOfStyle();
        if (idx >= 0 && xmlParser->getAttributeValue(idx, &value) >= 0) {
            if (value.dataType == value.TYPE_ATTRIBUTE) {
                if (theme->getAttribute(value.data, &value, &styleBagTypeSetFlags) < 0) {
                    value.dataType = Res_value::TYPE_NULL;
                }
            }
            if (value.dataType == value.TYPE_REFERENCE) {
                style = value.data;
            }
        }
    }

    // Now lock down the resource object and start pulling stuff from it.
    res.lock();

    // Retrieve the default style bag, if requested.
    const ResTable::bag_entry* defStyleAttrStart = NULL;
    uint32_t defStyleTypeSetFlags = 0;
    ssize_t bagOff = defStyleRes != 0
            ? res.getBagLocked(defStyleRes, &defStyleAttrStart, &defStyleTypeSetFlags) : -1;
    defStyleTypeSetFlags |= defStyleBagTypeSetFlags;
    const ResTable::bag_entry* const defStyleAttrEnd = defStyleAttrStart + (bagOff >= 0 ? bagOff : 0);
    BagAttributeFinder defStyleAttrFinder(defStyleAttrStart, defStyleAttrEnd);

    // Retrieve the style class bag, if requested.
    const ResTable::bag_entry* styleAttrStart = NULL;
    uint32_t styleTypeSetFlags = 0;
    bagOff = style != 0 ? res.getBagLocked(style, &styleAttrStart, &styleTypeSetFlags) : -1;
    styleTypeSetFlags |= styleBagTypeSetFlags;
    const ResTable::bag_entry* const styleAttrEnd = styleAttrStart + (bagOff >= 0 ? bagOff : 0);
    BagAttributeFinder styleAttrFinder(styleAttrStart, styleAttrEnd);

    // Retrieve the XML attributes, if requested.
    static const ssize_t kXmlBlock = 0x10000000;
    XmlAttributeFinder xmlAttrFinder(xmlParser);
    const size_t xmlAttrEnd = xmlParser != NULL ? xmlParser->getAttributeCount() : 0;

    // Now iterate through all of the attributes that the client has requested,
    // filling in each with whatever data we can find.
    ssize_t block = 0;
    uint32_t typeSetFlags;
    for (size_t ii = 0; ii < attrsLength; ii++) {
        const uint32_t curIdent = attrs[ii];

        if (kDebugStyles) {
            ALOGI("RETRIEVING ATTR 0x%08x...", curIdent);
        }

        // Try to find a value for this attribute...  we prioritize values
        // coming from, first XML attributes, then XML style, then default
        // style, and finally the theme.
        value.dataType = Res_value::TYPE_NULL;
        value.data = Res_value::DATA_NULL_UNDEFINED;
        typeSetFlags = 0;
        config.density = 0;

        // Walk through the xml attributes looking for the requested attribute.
        const size_t xmlAttrIdx = xmlAttrFinder.find(curIdent);
        if (xmlAttrIdx != xmlAttrEnd) {
            // We found the attribute we were looking for.
            block = kXmlBlock;
            xmlParser->getAttributeValue(xmlAttrIdx, &value);
            if (kDebugStyles) {
                ALOGI("-> From XML: type=0x%x, data=0x%08x", value.dataType, value.data);
            }
        }

        if (value.dataType == Res_value::TYPE_NULL) {
            // Walk through the style class values looking for the requested attribute.
            const ResTable::bag_entry* const styleAttrEntry = styleAttrFinder.find(curIdent);
            if (styleAttrEntry != styleAttrEnd) {
                // We found the attribute we were looking for.
                block = styleAttrEntry->stringBlock;
                typeSetFlags = styleTypeSetFlags;
                value = styleAttrEntry->map.value;
                if (kDebugStyles) {
                    ALOGI("-> From style: type=0x%x, data=0x%08x", value.dataType, value.data);
                }
            }
        }

        if (value.dataType == Res_value::TYPE_NULL) {
            // Walk through the default style values looking for the requested attribute.
            const ResTable::bag_entry* const defStyleAttrEntry = defStyleAttrFinder.find(curIdent);
            if (defStyleAttrEntry != defStyleAttrEnd) {
                // We found the attribute we were looking for.
                block = defStyleAttrEntry->stringBlock;
                typeSetFlags = styleTypeSetFlags;
                value = defStyleAttrEntry->map.value;
                if (kDebugStyles) {
                    ALOGI("-> From def style: type=0x%x, data=0x%08x", value.dataType, value.data);
                }
            }
        }

        uint32_t resid = 0;
        if (value.dataType != Res_value::TYPE_NULL) {
            // Take care of resolving the found resource to its final value.
            ssize_t newBlock = theme->resolveAttributeReference(&value, block,
                    &resid, &typeSetFlags, &config);
            if (newBlock >= 0) {
                block = newBlock;
            }

            if (kDebugStyles) {
                ALOGI("-> Resolved attr: type=0x%x, data=0x%08x", value.dataType, value.data);
            }
        } else {
            // If we still don't have a value for this attribute, try to find
            // it in the theme!
            ssize_t newBlock = theme->getAttribute(curIdent, &value, &typeSetFlags);
            if (newBlock >= 0) {
                if (kDebugStyles) {
                    ALOGI("-> From theme: type=0x%x, data=0x%08x", value.dataType, value.data);
                }
                newBlock = res.resolveReference(&value, block, &resid,
                        &typeSetFlags, &config);

                if (newBlock >= 0) {
                    block = newBlock;
                }

                if (kDebugStyles) {
                    ALOGI("-> Resolved theme: type=0x%x, data=0x%08x", value.dataType, value.data);
                }
            }
        }

        // Deal with the special @null value -- it turns back to TYPE_NULL.
        if (value.dataType == Res_value::TYPE_REFERENCE && value.data == 0) {
            if (kDebugStyles) {
                ALOGI("-> Setting to @null!");
            }
            value.dataType = Res_value::TYPE_NULL;
            value.data = Res_value::DATA_NULL_UNDEFINED;
            block = kXmlBlock;
        }

        if (kDebugStyles) {
            ALOGI("Attribute 0x%08x: type=0x%x, data=0x%08x", curIdent, value.dataType, value.data);
        }

        // Write the final value back to Java.
        outValues[STYLE_TYPE] = value.dataType;
        outValues[STYLE_DATA] = value.data;
        outValues[STYLE_ASSET_COOKIE] = block != kXmlBlock ?
            static_cast<uint32_t>(res.getTableCookie(block)) : static_cast<uint32_t>(-1);
        outValues[STYLE_RESOURCE_ID] = resid;
        outValues[STYLE_CHANGING_CONFIGURATIONS] = typeSetFlags;
        outValues[STYLE_DENSITY] = config.density;

        if (outIndices != NULL && value.dataType != Res_value::TYPE_NULL) {
            indicesIdx++;
            outIndices[indicesIdx] = ii;
        }

        outValues += STYLE_NUM_ENTRIES;
    }

    res.unlock();

    if (outIndices != NULL) {
        outIndices[0] = indicesIdx;
    }
    return true;
}

bool retrieveAttributes(const ResTable* res, ResXMLParser* xmlParser,
                        uint32_t* attrs, size_t attrsLength,
                        uint32_t* outValues,
                        uint32_t* outIndices) {
    ResTable_config config;
    Res_value value;

    int indicesIdx = 0;

    // Now lock down the resource object and start pulling stuff from it.
    res->lock();

    // Retrieve the XML attributes, if requested.
    const size_t NX = xmlParser->getAttributeCount();
    size_t ix=0;
    uint32_t curXmlAttr = xmlParser->getAttributeNameResID(ix);

    static const ssize_t kXmlBlock = 0x10000000;

    // Now iterate through all of the attributes that the client has requested,
    // filling in each with whatever data we can find.
    ssize_t block = 0;
    uint32_t typeSetFlags;
    for (size_t ii=0; ii<attrsLength; ii++) {
        const uint32_t curIdent = attrs[ii];

        // Try to find a value for this attribute...
        value.dataType = Res_value::TYPE_NULL;
        value.data = Res_value::DATA_NULL_UNDEFINED;
        typeSetFlags = 0;
        config.density = 0;

        // Skip through XML attributes until the end or the next possible match.
        while (ix < NX && curIdent > curXmlAttr) {
            ix++;
            curXmlAttr = xmlParser->getAttributeNameResID(ix);
        }
        // Retrieve the current XML attribute if it matches, and step to next.
        if (ix < NX && curIdent == curXmlAttr) {
            block = kXmlBlock;
            xmlParser->getAttributeValue(ix, &value);
            ix++;
            curXmlAttr = xmlParser->getAttributeNameResID(ix);
        }

        //printf("Attribute 0x%08x: type=0x%x, data=0x%08x\n", curIdent, value.dataType, value.data);
        uint32_t resid = 0;
        if (value.dataType != Res_value::TYPE_NULL) {
            // Take care of resolving the found resource to its final value.
            //printf("Resolving attribute reference\n");
            ssize_t newBlock = res->resolveReference(&value, block, &resid,
                    &typeSetFlags, &config);
            if (newBlock >= 0) block = newBlock;
        }

        // Deal with the special @null value -- it turns back to TYPE_NULL.
        if (value.dataType == Res_value::TYPE_REFERENCE && value.data == 0) {
            value.dataType = Res_value::TYPE_NULL;
            value.data = Res_value::DATA_NULL_UNDEFINED;
        }

        //printf("Attribute 0x%08x: final type=0x%x, data=0x%08x\n", curIdent, value.dataType, value.data);

        // Write the final value back to Java.
        outValues[STYLE_TYPE] = value.dataType;
        outValues[STYLE_DATA] = value.data;
        outValues[STYLE_ASSET_COOKIE] = block != kXmlBlock
                ? static_cast<uint32_t>(res->getTableCookie(block)) : static_cast<uint32_t>(-1);
        outValues[STYLE_RESOURCE_ID] = resid;
        outValues[STYLE_CHANGING_CONFIGURATIONS] = typeSetFlags;
        outValues[STYLE_DENSITY] = config.density;

        if (outIndices != NULL && value.dataType != Res_value::TYPE_NULL) {
            indicesIdx++;
            outIndices[indicesIdx] = ii;
        }

        outValues += STYLE_NUM_ENTRIES;
    }

    res->unlock();

    if (outIndices != NULL) {
        outIndices[0] = indicesIdx;
    }
    return true;
}

} // namespace android
