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

#include "androidfw/AttributeFinder.h"
#include "androidfw/ResourceTypes.h"

constexpr bool kDebugStyles = false;

namespace android {

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
    : public BackTrackingAttributeFinder<BagAttributeFinder, const ResTable::bag_entry*> {
 public:
  BagAttributeFinder(const ResTable::bag_entry* start,
                     const ResTable::bag_entry* end)
      : BackTrackingAttributeFinder(start, end) {}

  inline uint32_t GetAttribute(const ResTable::bag_entry* entry) const {
    return entry->map.name.ident;
  }
};

bool ResolveAttrs(ResTable::Theme* theme, uint32_t def_style_attr,
                  uint32_t def_style_res, uint32_t* src_values,
                  size_t src_values_length, uint32_t* attrs,
                  size_t attrs_length, uint32_t* out_values,
                  uint32_t* out_indices) {
  if (kDebugStyles) {
    ALOGI("APPLY STYLE: theme=0x%p defStyleAttr=0x%x defStyleRes=0x%x", theme,
          def_style_attr, def_style_res);
  }

  const ResTable& res = theme->getResTable();
  ResTable_config config;
  Res_value value;

  int indices_idx = 0;

  // Load default style from attribute, if specified...
  uint32_t def_style_bag_type_set_flags = 0;
  if (def_style_attr != 0) {
    Res_value value;
    if (theme->getAttribute(def_style_attr, &value, &def_style_bag_type_set_flags) >= 0) {
      if (value.dataType == Res_value::TYPE_REFERENCE) {
        def_style_res = value.data;
      }
    }
  }

  // Now lock down the resource object and start pulling stuff from it.
  res.lock();

  // Retrieve the default style bag, if requested.
  const ResTable::bag_entry* def_style_start = nullptr;
  uint32_t def_style_type_set_flags = 0;
  ssize_t bag_off = def_style_res != 0
                        ? res.getBagLocked(def_style_res, &def_style_start,
                                           &def_style_type_set_flags)
                        : -1;
  def_style_type_set_flags |= def_style_bag_type_set_flags;
  const ResTable::bag_entry* const def_style_end =
      def_style_start + (bag_off >= 0 ? bag_off : 0);
  BagAttributeFinder def_style_attr_finder(def_style_start, def_style_end);

  // Now iterate through all of the attributes that the client has requested,
  // filling in each with whatever data we can find.
  for (size_t ii = 0; ii < attrs_length; ii++) {
    const uint32_t cur_ident = attrs[ii];

    if (kDebugStyles) {
      ALOGI("RETRIEVING ATTR 0x%08x...", cur_ident);
    }

    ssize_t block = -1;
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
        ALOGI("-> From values: type=0x%x, data=0x%08x", value.dataType,
              value.data);
      }
    } else {
      const ResTable::bag_entry* const def_style_entry = def_style_attr_finder.Find(cur_ident);
      if (def_style_entry != def_style_end) {
        block = def_style_entry->stringBlock;
        type_set_flags = def_style_type_set_flags;
        value = def_style_entry->map.value;
        if (kDebugStyles) {
          ALOGI("-> From def style: type=0x%x, data=0x%08x", value.dataType, value.data);
        }
      }
    }

    uint32_t resid = 0;
    if (value.dataType != Res_value::TYPE_NULL) {
      // Take care of resolving the found resource to its final value.
      ssize_t new_block =
          theme->resolveAttributeReference(&value, block, &resid, &type_set_flags, &config);
      if (new_block >= 0) block = new_block;
      if (kDebugStyles) {
        ALOGI("-> Resolved attr: type=0x%x, data=0x%08x", value.dataType, value.data);
      }
    } else if (value.data != Res_value::DATA_NULL_EMPTY) {
      // If we still don't have a value for this attribute, try to find
      // it in the theme!
      ssize_t new_block = theme->getAttribute(cur_ident, &value, &type_set_flags);
      if (new_block >= 0) {
        if (kDebugStyles) {
          ALOGI("-> From theme: type=0x%x, data=0x%08x", value.dataType, value.data);
        }
        new_block = res.resolveReference(&value, new_block, &resid, &type_set_flags, &config);
        if (new_block >= 0) block = new_block;
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
      ALOGI("Attribute 0x%08x: type=0x%x, data=0x%08x", cur_ident, value.dataType, value.data);
    }

    // Write the final value back to Java.
    out_values[STYLE_TYPE] = value.dataType;
    out_values[STYLE_DATA] = value.data;
    out_values[STYLE_ASSET_COOKIE] =
        block != -1 ? static_cast<uint32_t>(res.getTableCookie(block))
                    : static_cast<uint32_t>(-1);
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

  res.unlock();

  if (out_indices != nullptr) {
    out_indices[0] = indices_idx;
  }
  return true;
}

void ApplyStyle(ResTable::Theme* theme, ResXMLParser* xml_parser, uint32_t def_style_attr,
                uint32_t def_style_res, const uint32_t* attrs, size_t attrs_length,
                uint32_t* out_values, uint32_t* out_indices) {
  if (kDebugStyles) {
    ALOGI("APPLY STYLE: theme=0x%p defStyleAttr=0x%x defStyleRes=0x%x xml=0x%p",
          theme, def_style_attr, def_style_res, xml_parser);
  }

  const ResTable& res = theme->getResTable();
  ResTable_config config;
  Res_value value;

  int indices_idx = 0;

  // Load default style from attribute, if specified...
  uint32_t def_style_bag_type_set_flags = 0;
  if (def_style_attr != 0) {
    Res_value value;
    if (theme->getAttribute(def_style_attr, &value,
                            &def_style_bag_type_set_flags) >= 0) {
      if (value.dataType == Res_value::TYPE_REFERENCE) {
        def_style_res = value.data;
      }
    }
  }

  // Retrieve the style class associated with the current XML tag.
  int style = 0;
  uint32_t style_bag_type_set_flags = 0;
  if (xml_parser != nullptr) {
    ssize_t idx = xml_parser->indexOfStyle();
    if (idx >= 0 && xml_parser->getAttributeValue(idx, &value) >= 0) {
      if (value.dataType == value.TYPE_ATTRIBUTE) {
        if (theme->getAttribute(value.data, &value, &style_bag_type_set_flags) < 0) {
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
  const ResTable::bag_entry* def_style_attr_start = nullptr;
  uint32_t def_style_type_set_flags = 0;
  ssize_t bag_off = def_style_res != 0
                        ? res.getBagLocked(def_style_res, &def_style_attr_start,
                                           &def_style_type_set_flags)
                        : -1;
  def_style_type_set_flags |= def_style_bag_type_set_flags;
  const ResTable::bag_entry* const def_style_attr_end =
      def_style_attr_start + (bag_off >= 0 ? bag_off : 0);
  BagAttributeFinder def_style_attr_finder(def_style_attr_start,
                                           def_style_attr_end);

  // Retrieve the style class bag, if requested.
  const ResTable::bag_entry* style_attr_start = nullptr;
  uint32_t style_type_set_flags = 0;
  bag_off =
      style != 0
          ? res.getBagLocked(style, &style_attr_start, &style_type_set_flags)
          : -1;
  style_type_set_flags |= style_bag_type_set_flags;
  const ResTable::bag_entry* const style_attr_end =
      style_attr_start + (bag_off >= 0 ? bag_off : 0);
  BagAttributeFinder style_attr_finder(style_attr_start, style_attr_end);

  // Retrieve the XML attributes, if requested.
  static const ssize_t kXmlBlock = 0x10000000;
  XmlAttributeFinder xml_attr_finder(xml_parser);
  const size_t xml_attr_end =
      xml_parser != nullptr ? xml_parser->getAttributeCount() : 0;

  // Now iterate through all of the attributes that the client has requested,
  // filling in each with whatever data we can find.
  for (size_t ii = 0; ii < attrs_length; ii++) {
    const uint32_t cur_ident = attrs[ii];

    if (kDebugStyles) {
      ALOGI("RETRIEVING ATTR 0x%08x...", cur_ident);
    }

    ssize_t block = kXmlBlock;
    uint32_t type_set_flags = 0;

    value.dataType = Res_value::TYPE_NULL;
    value.data = Res_value::DATA_NULL_UNDEFINED;
    config.density = 0;

    // Try to find a value for this attribute...  we prioritize values
    // coming from, first XML attributes, then XML style, then default
    // style, and finally the theme.

    // Walk through the xml attributes looking for the requested attribute.
    const size_t xml_attr_idx = xml_attr_finder.Find(cur_ident);
    if (xml_attr_idx != xml_attr_end) {
      // We found the attribute we were looking for.
      xml_parser->getAttributeValue(xml_attr_idx, &value);
      if (kDebugStyles) {
        ALOGI("-> From XML: type=0x%x, data=0x%08x", value.dataType, value.data);
      }
    }

    if (value.dataType == Res_value::TYPE_NULL && value.data != Res_value::DATA_NULL_EMPTY) {
      // Walk through the style class values looking for the requested attribute.
      const ResTable::bag_entry* const style_attr_entry = style_attr_finder.Find(cur_ident);
      if (style_attr_entry != style_attr_end) {
        // We found the attribute we were looking for.
        block = style_attr_entry->stringBlock;
        type_set_flags = style_type_set_flags;
        value = style_attr_entry->map.value;
        if (kDebugStyles) {
          ALOGI("-> From style: type=0x%x, data=0x%08x", value.dataType, value.data);
        }
      }
    }

    if (value.dataType == Res_value::TYPE_NULL && value.data != Res_value::DATA_NULL_EMPTY) {
      // Walk through the default style values looking for the requested attribute.
      const ResTable::bag_entry* const def_style_attr_entry = def_style_attr_finder.Find(cur_ident);
      if (def_style_attr_entry != def_style_attr_end) {
        // We found the attribute we were looking for.
        block = def_style_attr_entry->stringBlock;
        type_set_flags = style_type_set_flags;
        value = def_style_attr_entry->map.value;
        if (kDebugStyles) {
          ALOGI("-> From def style: type=0x%x, data=0x%08x", value.dataType, value.data);
        }
      }
    }

    uint32_t resid = 0;
    if (value.dataType != Res_value::TYPE_NULL) {
      // Take care of resolving the found resource to its final value.
      ssize_t new_block =
          theme->resolveAttributeReference(&value, block, &resid, &type_set_flags, &config);
      if (new_block >= 0) {
        block = new_block;
      }

      if (kDebugStyles) {
        ALOGI("-> Resolved attr: type=0x%x, data=0x%08x", value.dataType, value.data);
      }
    } else if (value.data != Res_value::DATA_NULL_EMPTY) {
      // If we still don't have a value for this attribute, try to find it in the theme!
      ssize_t new_block = theme->getAttribute(cur_ident, &value, &type_set_flags);
      if (new_block >= 0) {
        if (kDebugStyles) {
          ALOGI("-> From theme: type=0x%x, data=0x%08x", value.dataType, value.data);
        }
        new_block = res.resolveReference(&value, new_block, &resid, &type_set_flags, &config);
        if (new_block >= 0) {
          block = new_block;
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
      ALOGI("Attribute 0x%08x: type=0x%x, data=0x%08x", cur_ident, value.dataType, value.data);
    }

    // Write the final value back to Java.
    out_values[STYLE_TYPE] = value.dataType;
    out_values[STYLE_DATA] = value.data;
    out_values[STYLE_ASSET_COOKIE] =
        block != kXmlBlock ? static_cast<uint32_t>(res.getTableCookie(block))
                           : static_cast<uint32_t>(-1);
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

  res.unlock();

  // out_indices must NOT be nullptr.
  out_indices[0] = indices_idx;
}

bool RetrieveAttributes(const ResTable* res, ResXMLParser* xml_parser,
                        uint32_t* attrs, size_t attrs_length,
                        uint32_t* out_values, uint32_t* out_indices) {
  ResTable_config config;
  Res_value value;

  int indices_idx = 0;

  // Now lock down the resource object and start pulling stuff from it.
  res->lock();

  // Retrieve the XML attributes, if requested.
  const size_t xml_attr_count = xml_parser->getAttributeCount();
  size_t ix = 0;
  uint32_t cur_xml_attr = xml_parser->getAttributeNameResID(ix);

  static const ssize_t kXmlBlock = 0x10000000;

  // Now iterate through all of the attributes that the client has requested,
  // filling in each with whatever data we can find.
  for (size_t ii = 0; ii < attrs_length; ii++) {
    const uint32_t cur_ident = attrs[ii];
    ssize_t block = kXmlBlock;
    uint32_t type_set_flags = 0;

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

    uint32_t resid = 0;
    if (value.dataType != Res_value::TYPE_NULL) {
      // Take care of resolving the found resource to its final value.
      // printf("Resolving attribute reference\n");
      ssize_t new_block = res->resolveReference(&value, block, &resid,
                                                &type_set_flags, &config);
      if (new_block >= 0) block = new_block;
    }

    // Deal with the special @null value -- it turns back to TYPE_NULL.
    if (value.dataType == Res_value::TYPE_REFERENCE && value.data == 0) {
      value.dataType = Res_value::TYPE_NULL;
      value.data = Res_value::DATA_NULL_UNDEFINED;
      block = kXmlBlock;
    }

    // Write the final value back to Java.
    out_values[STYLE_TYPE] = value.dataType;
    out_values[STYLE_DATA] = value.data;
    out_values[STYLE_ASSET_COOKIE] =
        block != kXmlBlock ? static_cast<uint32_t>(res->getTableCookie(block))
                           : static_cast<uint32_t>(-1);
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

  res->unlock();

  if (out_indices != nullptr) {
    out_indices[0] = indices_idx;
  }
  return true;
}

}  // namespace android
