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

#include "androidfw/AttributeResolution.h"

#include <cstdint>

#include <log/log.h>

#include "androidfw/AssetManager2.h"
#include "androidfw/AttributeFinder.h"

constexpr bool kDebugStyles = false;

namespace android {

// Java asset cookies have 0 as an invalid cookie, but TypedArray expects < 0.
static uint32_t ApkAssetsCookieToJavaCookie(ApkAssetsCookie cookie) {
  return cookie != kInvalidCookie ? static_cast<uint32_t>(cookie + 1) : static_cast<uint32_t>(-1);
}

class XmlAttributeFinder
    : public BackTrackingAttributeFinder<XmlAttributeFinder, size_t> {
 public:
  explicit XmlAttributeFinder(const ResXMLParser* parser)
      : BackTrackingAttributeFinder(
            0, parser != nullptr ? parser->getAttributeCount() : 0),
        parser_(parser) {}

  inline uint32_t GetAttribute(size_t index) const {
    return parser_->getAttributeNameResID(index);
  }

 private:
  const ResXMLParser* parser_;
};

class BagAttributeFinder
    : public BackTrackingAttributeFinder<BagAttributeFinder, const ResolvedBag::Entry*> {
 public:
  BagAttributeFinder(const ResolvedBag* bag)
      : BackTrackingAttributeFinder(bag != nullptr ? bag->entries : nullptr,
                                    bag != nullptr ? bag->entries + bag->entry_count : nullptr) {
  }

  inline uint32_t GetAttribute(const ResolvedBag::Entry* entry) const {
    return entry->key;
  }
};

bool ResolveAttrs(Theme* theme, uint32_t def_style_attr, uint32_t def_style_res,
                  uint32_t* src_values, size_t src_values_length, uint32_t* attrs,
                  size_t attrs_length, uint32_t* out_values, uint32_t* out_indices) {
  if (kDebugStyles) {
    ALOGI("APPLY STYLE: theme=0x%p defStyleAttr=0x%x defStyleRes=0x%x", theme,
          def_style_attr, def_style_res);
  }

  AssetManager2* assetmanager = theme->GetAssetManager();
  ResTable_config config;
  Res_value value;

  int indices_idx = 0;

  // Load default style from attribute, if specified...
  uint32_t def_style_flags = 0u;
  if (def_style_attr != 0) {
    Res_value value;
    if (theme->GetAttribute(def_style_attr, &value, &def_style_flags) != kInvalidCookie) {
      if (value.dataType == Res_value::TYPE_REFERENCE) {
        def_style_res = value.data;
      }
    }
  }

  // Retrieve the default style bag, if requested.
  const ResolvedBag* default_style_bag = nullptr;
  if (def_style_res != 0) {
    default_style_bag = assetmanager->GetBag(def_style_res);
    if (default_style_bag != nullptr) {
      def_style_flags |= default_style_bag->type_spec_flags;
    }
  }

  BagAttributeFinder def_style_attr_finder(default_style_bag);

  // Now iterate through all of the attributes that the client has requested,
  // filling in each with whatever data we can find.
  for (size_t ii = 0; ii < attrs_length; ii++) {
    const uint32_t cur_ident = attrs[ii];

    if (kDebugStyles) {
      ALOGI("RETRIEVING ATTR 0x%08x...", cur_ident);
    }

    ApkAssetsCookie cookie = kInvalidCookie;
    uint32_t type_set_flags = 0;

    value.dataType = Res_value::TYPE_NULL;
    value.data = Res_value::DATA_NULL_UNDEFINED;
    config.density = 0;

    // Try to find a value for this attribute...  we prioritize values
    // coming from, first XML attributes, then XML style, then default
    // style, and finally the theme.

    // Retrieve the current input value if available.
    if (src_values_length > 0 && src_values[ii] != 0) {
      value.dataType = Res_value::TYPE_ATTRIBUTE;
      value.data = src_values[ii];
      if (kDebugStyles) {
        ALOGI("-> From values: type=0x%x, data=0x%08x", value.dataType, value.data);
      }
    } else {
      const ResolvedBag::Entry* const entry = def_style_attr_finder.Find(cur_ident);
      if (entry != def_style_attr_finder.end()) {
        cookie = entry->cookie;
        type_set_flags = def_style_flags;
        value = entry->value;
        if (kDebugStyles) {
          ALOGI("-> From def style: type=0x%x, data=0x%08x", value.dataType, value.data);
        }
      }
    }

    uint32_t resid = 0;
    if (value.dataType != Res_value::TYPE_NULL) {
      // Take care of resolving the found resource to its final value.
      ApkAssetsCookie new_cookie =
          theme->ResolveAttributeReference(cookie, &value, &config, &type_set_flags, &resid);
      if (new_cookie != kInvalidCookie) {
        cookie = new_cookie;
      }
      if (kDebugStyles) {
        ALOGI("-> Resolved attr: type=0x%x, data=0x%08x", value.dataType, value.data);
      }
    } else if (value.data != Res_value::DATA_NULL_EMPTY) {
      // If we still don't have a value for this attribute, try to find it in the theme!
      ApkAssetsCookie new_cookie = theme->GetAttribute(cur_ident, &value, &type_set_flags);
      if (new_cookie != kInvalidCookie) {
        if (kDebugStyles) {
          ALOGI("-> From theme: type=0x%x, data=0x%08x", value.dataType, value.data);
        }
        new_cookie =
            assetmanager->ResolveReference(new_cookie, &value, &config, &type_set_flags, &resid);
        if (new_cookie != kInvalidCookie) {
          cookie = new_cookie;
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
      cookie = kInvalidCookie;
    }

    if (kDebugStyles) {
      ALOGI("Attribute 0x%08x: type=0x%x, data=0x%08x", cur_ident, value.dataType, value.data);
    }

    // Write the final value back to Java.
    out_values[STYLE_TYPE] = value.dataType;
    out_values[STYLE_DATA] = value.data;
    out_values[STYLE_ASSET_COOKIE] = ApkAssetsCookieToJavaCookie(cookie);
    out_values[STYLE_RESOURCE_ID] = resid;
    out_values[STYLE_CHANGING_CONFIGURATIONS] = type_set_flags;
    out_values[STYLE_DENSITY] = config.density;

    if (out_indices != nullptr &&
        (value.dataType != Res_value::TYPE_NULL || value.data == Res_value::DATA_NULL_EMPTY)) {
      indices_idx++;
      out_indices[indices_idx] = ii;
    }

    out_values += STYLE_NUM_ENTRIES;
  }

  if (out_indices != nullptr) {
    out_indices[0] = indices_idx;
  }
  return true;
}

void ApplyStyle(Theme* theme, ResXMLParser* xml_parser, uint32_t def_style_attr,
                uint32_t def_style_resid, const uint32_t* attrs, size_t attrs_length,
                uint32_t* out_values, uint32_t* out_indices) {
  if (kDebugStyles) {
    ALOGI("APPLY STYLE: theme=0x%p defStyleAttr=0x%x defStyleRes=0x%x xml=0x%p", theme,
          def_style_attr, def_style_resid, xml_parser);
  }

  AssetManager2* assetmanager = theme->GetAssetManager();
  ResTable_config config;
  Res_value value;

  int indices_idx = 0;

  // Load default style from attribute, if specified...
  uint32_t def_style_flags = 0u;
  if (def_style_attr != 0) {
    Res_value value;
    if (theme->GetAttribute(def_style_attr, &value, &def_style_flags) != kInvalidCookie) {
      if (value.dataType == Res_value::TYPE_REFERENCE) {
        def_style_resid = value.data;
      }
    }
  }

  // Retrieve the style resource ID associated with the current XML tag's style attribute.
  uint32_t style_resid = 0u;
  uint32_t style_flags = 0u;
  if (xml_parser != nullptr) {
    ssize_t idx = xml_parser->indexOfStyle();
    if (idx >= 0 && xml_parser->getAttributeValue(idx, &value) >= 0) {
      if (value.dataType == value.TYPE_ATTRIBUTE) {
        // Resolve the attribute with out theme.
        if (theme->GetAttribute(value.data, &value, &style_flags) == kInvalidCookie) {
          value.dataType = Res_value::TYPE_NULL;
        }
      }

      if (value.dataType == value.TYPE_REFERENCE) {
        style_resid = value.data;
      }
    }
  }

  // Retrieve the default style bag, if requested.
  const ResolvedBag* default_style_bag = nullptr;
  if (def_style_resid != 0) {
    default_style_bag = assetmanager->GetBag(def_style_resid);
    if (default_style_bag != nullptr) {
      def_style_flags |= default_style_bag->type_spec_flags;
    }
  }

  BagAttributeFinder def_style_attr_finder(default_style_bag);

  // Retrieve the style class bag, if requested.
  const ResolvedBag* xml_style_bag = nullptr;
  if (style_resid != 0) {
    xml_style_bag = assetmanager->GetBag(style_resid);
    if (xml_style_bag != nullptr) {
      style_flags |= xml_style_bag->type_spec_flags;
    }
  }

  BagAttributeFinder xml_style_attr_finder(xml_style_bag);

  // Retrieve the XML attributes, if requested.
  XmlAttributeFinder xml_attr_finder(xml_parser);

  // Now iterate through all of the attributes that the client has requested,
  // filling in each with whatever data we can find.
  for (size_t ii = 0; ii < attrs_length; ii++) {
    const uint32_t cur_ident = attrs[ii];

    if (kDebugStyles) {
      ALOGI("RETRIEVING ATTR 0x%08x...", cur_ident);
    }

    ApkAssetsCookie cookie = kInvalidCookie;
    uint32_t type_set_flags = 0u;

    value.dataType = Res_value::TYPE_NULL;
    value.data = Res_value::DATA_NULL_UNDEFINED;
    config.density = 0;

    // Try to find a value for this attribute...  we prioritize values
    // coming from, first XML attributes, then XML style, then default
    // style, and finally the theme.

    // Walk through the xml attributes looking for the requested attribute.
    const size_t xml_attr_idx = xml_attr_finder.Find(cur_ident);
    if (xml_attr_idx != xml_attr_finder.end()) {
      // We found the attribute we were looking for.
      xml_parser->getAttributeValue(xml_attr_idx, &value);
      if (kDebugStyles) {
        ALOGI("-> From XML: type=0x%x, data=0x%08x", value.dataType, value.data);
      }
    }

    if (value.dataType == Res_value::TYPE_NULL && value.data != Res_value::DATA_NULL_EMPTY) {
      // Walk through the style class values looking for the requested attribute.
      const ResolvedBag::Entry* entry = xml_style_attr_finder.Find(cur_ident);
      if (entry != xml_style_attr_finder.end()) {
        // We found the attribute we were looking for.
        cookie = entry->cookie;
        type_set_flags = style_flags;
        value = entry->value;
        if (kDebugStyles) {
          ALOGI("-> From style: type=0x%x, data=0x%08x", value.dataType, value.data);
        }
      }
    }

    if (value.dataType == Res_value::TYPE_NULL && value.data != Res_value::DATA_NULL_EMPTY) {
      // Walk through the default style values looking for the requested attribute.
      const ResolvedBag::Entry* entry = def_style_attr_finder.Find(cur_ident);
      if (entry != def_style_attr_finder.end()) {
        // We found the attribute we were looking for.
        cookie = entry->cookie;
        type_set_flags = def_style_flags;
        value = entry->value;
        if (kDebugStyles) {
          ALOGI("-> From def style: type=0x%x, data=0x%08x", value.dataType, value.data);
        }
      }
    }

    uint32_t resid = 0u;
    if (value.dataType != Res_value::TYPE_NULL) {
      // Take care of resolving the found resource to its final value.
      ApkAssetsCookie new_cookie =
          theme->ResolveAttributeReference(cookie, &value, &config, &type_set_flags, &resid);
      if (new_cookie != kInvalidCookie) {
        cookie = new_cookie;
      }

      if (kDebugStyles) {
        ALOGI("-> Resolved attr: type=0x%x, data=0x%08x", value.dataType, value.data);
      }
    } else if (value.data != Res_value::DATA_NULL_EMPTY) {
      // If we still don't have a value for this attribute, try to find it in the theme!
      ApkAssetsCookie new_cookie = theme->GetAttribute(cur_ident, &value, &type_set_flags);
      if (new_cookie != kInvalidCookie) {
        if (kDebugStyles) {
          ALOGI("-> From theme: type=0x%x, data=0x%08x", value.dataType, value.data);
        }
        new_cookie =
            assetmanager->ResolveReference(new_cookie, &value, &config, &type_set_flags, &resid);
        if (new_cookie != kInvalidCookie) {
          cookie = new_cookie;
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
      cookie = kInvalidCookie;
    }

    if (kDebugStyles) {
      ALOGI("Attribute 0x%08x: type=0x%x, data=0x%08x", cur_ident, value.dataType, value.data);
    }

    // Write the final value back to Java.
    out_values[STYLE_TYPE] = value.dataType;
    out_values[STYLE_DATA] = value.data;
    out_values[STYLE_ASSET_COOKIE] = ApkAssetsCookieToJavaCookie(cookie);
    out_values[STYLE_RESOURCE_ID] = resid;
    out_values[STYLE_CHANGING_CONFIGURATIONS] = type_set_flags;
    out_values[STYLE_DENSITY] = config.density;

    if (value.dataType != Res_value::TYPE_NULL || value.data == Res_value::DATA_NULL_EMPTY) {
      indices_idx++;

      // out_indices must NOT be nullptr.
      out_indices[indices_idx] = ii;
    }

    out_values += STYLE_NUM_ENTRIES;
  }

  // out_indices must NOT be nullptr.
  out_indices[0] = indices_idx;
}

bool RetrieveAttributes(AssetManager2* assetmanager, ResXMLParser* xml_parser, uint32_t* attrs,
                        size_t attrs_length, uint32_t* out_values, uint32_t* out_indices) {
  ResTable_config config;
  Res_value value;

  int indices_idx = 0;

  // Retrieve the XML attributes, if requested.
  const size_t xml_attr_count = xml_parser->getAttributeCount();
  size_t ix = 0;
  uint32_t cur_xml_attr = xml_parser->getAttributeNameResID(ix);

  // Now iterate through all of the attributes that the client has requested,
  // filling in each with whatever data we can find.
  for (size_t ii = 0; ii < attrs_length; ii++) {
    const uint32_t cur_ident = attrs[ii];
    ApkAssetsCookie cookie = kInvalidCookie;
    uint32_t type_set_flags = 0u;

    value.dataType = Res_value::TYPE_NULL;
    value.data = Res_value::DATA_NULL_UNDEFINED;
    config.density = 0;

    // Try to find a value for this attribute...
    // Skip through XML attributes until the end or the next possible match.
    while (ix < xml_attr_count && cur_ident > cur_xml_attr) {
      ix++;
      cur_xml_attr = xml_parser->getAttributeNameResID(ix);
    }
    // Retrieve the current XML attribute if it matches, and step to next.
    if (ix < xml_attr_count && cur_ident == cur_xml_attr) {
      xml_parser->getAttributeValue(ix, &value);
      ix++;
      cur_xml_attr = xml_parser->getAttributeNameResID(ix);
    }

    uint32_t resid = 0u;
    if (value.dataType != Res_value::TYPE_NULL) {
      // Take care of resolving the found resource to its final value.
      ApkAssetsCookie new_cookie =
          assetmanager->ResolveReference(cookie, &value, &config, &type_set_flags, &resid);
      if (new_cookie != kInvalidCookie) {
        cookie = new_cookie;
      }
    }

    // Deal with the special @null value -- it turns back to TYPE_NULL.
    if (value.dataType == Res_value::TYPE_REFERENCE && value.data == 0) {
      value.dataType = Res_value::TYPE_NULL;
      value.data = Res_value::DATA_NULL_UNDEFINED;
      cookie = kInvalidCookie;
    }

    // Write the final value back to Java.
    out_values[STYLE_TYPE] = value.dataType;
    out_values[STYLE_DATA] = value.data;
    out_values[STYLE_ASSET_COOKIE] = ApkAssetsCookieToJavaCookie(cookie);
    out_values[STYLE_RESOURCE_ID] = resid;
    out_values[STYLE_CHANGING_CONFIGURATIONS] = type_set_flags;
    out_values[STYLE_DENSITY] = config.density;

    if (out_indices != nullptr &&
        (value.dataType != Res_value::TYPE_NULL || value.data == Res_value::DATA_NULL_EMPTY)) {
      indices_idx++;
      out_indices[indices_idx] = ii;
    }

    out_values += STYLE_NUM_ENTRIES;
  }

  if (out_indices != nullptr) {
    out_indices[0] = indices_idx;
  }
  return true;
}

}  // namespace android
