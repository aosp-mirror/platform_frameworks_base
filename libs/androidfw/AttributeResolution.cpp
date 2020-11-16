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
#define DEBUG_LOG(...) do { if (kDebugStyles) { ALOGI(__VA_ARGS__); } } while(0)

namespace android {

namespace {

// Java asset cookies have 0 as an invalid cookie, but TypedArray expects < 0.
static uint32_t ApkAssetsCookieToJavaCookie(ApkAssetsCookie cookie) {
  return cookie != kInvalidCookie ? static_cast<uint32_t>(cookie + 1) : static_cast<uint32_t>(-1);
}

class XmlAttributeFinder
    : public BackTrackingAttributeFinder<XmlAttributeFinder, size_t> {
 public:
  explicit XmlAttributeFinder(const ResXMLParser* parser)
      : BackTrackingAttributeFinder(0, parser != nullptr ? parser->getAttributeCount() : 0),
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
  explicit BagAttributeFinder(const ResolvedBag* bag)
      : BackTrackingAttributeFinder(bag != nullptr ? bag->entries : nullptr,
                                    bag != nullptr ? bag->entries + bag->entry_count : nullptr) {
  }

  inline uint32_t GetAttribute(const ResolvedBag::Entry* entry) const {
    return entry->key;
  }
};

base::expected<const ResolvedBag*, NullOrIOError> GetStyleBag(Theme* theme,
                                                              uint32_t theme_attribute_resid,
                                                              uint32_t fallback_resid,
                                                              uint32_t* out_theme_flags) {
  // Load the style from the attribute if specified.
  if (theme_attribute_resid != 0U) {
    std::optional<AssetManager2::SelectedValue> value = theme->GetAttribute(theme_attribute_resid);
    if (value.has_value()) {
      *out_theme_flags |= value->flags;
      auto result = theme->GetAssetManager()->ResolveBag(*value);
      if (result.has_value() || IsIOError(result)) {
        return result;
      }
    }
  }

  // Fallback to loading the style from the resource id if specified.
  if (fallback_resid != 0U) {
    return theme->GetAssetManager()->GetBag(fallback_resid);
  }

  return base::unexpected(std::nullopt);
}

base::expected<const ResolvedBag*, NullOrIOError> GetXmlStyleBag(Theme* theme,
                                                                 ResXMLParser* xml_parser,
                                                                 uint32_t* out_theme_flags) {
  if (xml_parser == nullptr) {
    return base::unexpected(std::nullopt);
  }

  // Retrieve the style resource ID associated with the current XML tag's style attribute.
  Res_value value;
  const ssize_t idx = xml_parser->indexOfStyle();
  if (idx < 0 || xml_parser->getAttributeValue(idx, &value) < 0) {
    return base::unexpected(std::nullopt);
  }

  if (value.dataType == Res_value::TYPE_ATTRIBUTE) {
    // Resolve the attribute with out theme.
    if (std::optional<AssetManager2::SelectedValue> result = theme->GetAttribute(value.data)) {
      *out_theme_flags |= result->flags;
      return theme->GetAssetManager()->ResolveBag(*result);
    }
  }

  if (value.dataType == Res_value::TYPE_REFERENCE) {
    return theme->GetAssetManager()->GetBag(value.data);
  }

  return base::unexpected(std::nullopt);
}

} // namespace

base::expected<std::monostate, IOError> ResolveAttrs(Theme* theme, uint32_t def_style_attr,
                                                     uint32_t def_style_res, uint32_t* src_values,
                                                     size_t src_values_length, uint32_t* attrs,
                                                     size_t attrs_length, uint32_t* out_values,
                                                     uint32_t* out_indices) {
  DEBUG_LOG("APPLY STYLE: theme=0x%p defStyleAttr=0x%x defStyleRes=0x%x", theme, def_style_attr,
            def_style_res);

  int indices_idx = 0;
  const AssetManager2* assetmanager = theme->GetAssetManager();

  // Load default style from attribute or resource id, if specified...
  uint32_t def_style_theme_flags = 0U;
  const auto default_style_bag = GetStyleBag(theme, def_style_attr, def_style_res,
                                             &def_style_theme_flags);
  if (UNLIKELY(IsIOError(default_style_bag))) {
    return base::unexpected(GetIOError(default_style_bag.error()));
  }

  BagAttributeFinder def_style_attr_finder(default_style_bag.value_or(nullptr));

  // Now iterate through all of the attributes that the client has requested,
  // filling in each with whatever data we can find.
  for (size_t ii = 0; ii < attrs_length; ii++) {
    const uint32_t cur_ident = attrs[ii];
    DEBUG_LOG("RETRIEVING ATTR 0x%08x...", cur_ident);

    // Try to find a value for this attribute...  we prioritize values
    // coming from, first XML attributes, then XML style, then default
    // style, and finally the theme.

    // Retrieve the current input value if available.
    AssetManager2::SelectedValue value{};
    if (src_values_length > 0 && src_values[ii] != 0) {
      value.type = Res_value::TYPE_ATTRIBUTE;
      value.data = src_values[ii];
      DEBUG_LOG("-> From values: type=0x%x, data=0x%08x", value.type, value.data);
    } else {
      const ResolvedBag::Entry* const entry = def_style_attr_finder.Find(cur_ident);
      if (entry != def_style_attr_finder.end()) {
        value = AssetManager2::SelectedValue(*default_style_bag, *entry);
        value.flags |= def_style_theme_flags;
        DEBUG_LOG("-> From def style: type=0x%x, data=0x%08x", value.type, value.data);
      }
    }

    if (value.type != Res_value::TYPE_NULL) {
      // Take care of resolving the found resource to its final value.
      const auto result = theme->ResolveAttributeReference(value);
      if (UNLIKELY(IsIOError(result))) {
        return base::unexpected(GetIOError(result.error()));
      }
      DEBUG_LOG("-> Resolved attr: type=0x%x, data=0x%08x", value.type, value.data);
    } else if (value.data != Res_value::DATA_NULL_EMPTY) {
      // If we still don't have a value for this attribute, try to find it in the theme!
      if (auto attr_value = theme->GetAttribute(cur_ident)) {
        value = *attr_value;
        DEBUG_LOG("-> From theme: type=0x%x, data=0x%08x", value.type, value.data);

        const auto result = assetmanager->ResolveReference(value, true /* cache_value */);
        if (UNLIKELY(IsIOError(result))) {
          return base::unexpected(GetIOError(result.error()));
        }
        DEBUG_LOG("-> Resolved theme: type=0x%x, data=0x%08x", value.type, value.data);
      }
    }

    // Deal with the special @null value -- it turns back to TYPE_NULL.
    if (value.type == Res_value::TYPE_REFERENCE && value.data == 0) {
      DEBUG_LOG("-> Setting to @null!");
      value.type = Res_value::TYPE_NULL;
      value.data = Res_value::DATA_NULL_UNDEFINED;
      value.cookie = kInvalidCookie;
    }

    DEBUG_LOG("Attribute 0x%08x: type=0x%x, data=0x%08x", cur_ident, value.type, value.data);

    // Write the final value back to Java.
    out_values[STYLE_TYPE] = value.type;
    out_values[STYLE_DATA] = value.data;
    out_values[STYLE_ASSET_COOKIE] = ApkAssetsCookieToJavaCookie(value.cookie);
    out_values[STYLE_RESOURCE_ID] = value.resid;
    out_values[STYLE_CHANGING_CONFIGURATIONS] = value.flags;
    out_values[STYLE_DENSITY] = value.config.density;

    if (out_indices != nullptr &&
        (value.type != Res_value::TYPE_NULL || value.data == Res_value::DATA_NULL_EMPTY)) {
      out_indices[++indices_idx] = ii;
    }

    out_values += STYLE_NUM_ENTRIES;
  }

  if (out_indices != nullptr) {
    out_indices[0] = indices_idx;
  }
  return {};
}

base::expected<std::monostate, IOError> ApplyStyle(Theme* theme, ResXMLParser* xml_parser,
                                                   uint32_t def_style_attr,
                                                   uint32_t def_style_resid,
                                                   const uint32_t* attrs, size_t attrs_length,
                                                   uint32_t* out_values, uint32_t* out_indices) {
  DEBUG_LOG("APPLY STYLE: theme=0x%p defStyleAttr=0x%x defStyleRes=0x%x xml=0x%p", theme,
            def_style_attr, def_style_resid, xml_parser);

  int indices_idx = 0;
  const AssetManager2* assetmanager = theme->GetAssetManager();

  // Load default style from attribute, if specified...
  uint32_t def_style_theme_flags = 0U;
  const auto default_style_bag = GetStyleBag(theme, def_style_attr, def_style_resid,
                                             &def_style_theme_flags);
  if (IsIOError(default_style_bag)) {
    return base::unexpected(GetIOError(default_style_bag.error()));
  }

  // Retrieve the style resource ID associated with the current XML tag's style attribute.
  uint32_t xml_style_theme_flags = 0U;
  const auto xml_style_bag = GetXmlStyleBag(theme, xml_parser, &def_style_theme_flags);
  if (IsIOError(xml_style_bag)) {
    return base::unexpected(GetIOError(xml_style_bag.error()));
  }

  BagAttributeFinder def_style_attr_finder(default_style_bag.value_or(nullptr));
  BagAttributeFinder xml_style_attr_finder(xml_style_bag.value_or(nullptr));
  XmlAttributeFinder xml_attr_finder(xml_parser);

  // Now iterate through all of the attributes that the client has requested,
  // filling in each with whatever data we can find.
  for (size_t ii = 0; ii < attrs_length; ii++) {
    const uint32_t cur_ident = attrs[ii];
    DEBUG_LOG("RETRIEVING ATTR 0x%08x...", cur_ident);

    AssetManager2::SelectedValue value{};
    uint32_t value_source_resid = 0;

    // Try to find a value for this attribute...  we prioritize values
    // coming from, first XML attributes, then XML style, then default
    // style, and finally the theme.

    // Walk through the xml attributes looking for the requested attribute.
    const size_t xml_attr_idx = xml_attr_finder.Find(cur_ident);
    if (xml_attr_idx != xml_attr_finder.end()) {
      // We found the attribute we were looking for.
      Res_value attribute_value{};
      xml_parser->getAttributeValue(xml_attr_idx, &attribute_value);
      value.type = attribute_value.dataType;
      value.data = attribute_value.data;
      value_source_resid = xml_parser->getSourceResourceId();
      DEBUG_LOG("-> From XML: type=0x%x, data=0x%08x", value.type, value.data);
    }

    if (value.type == Res_value::TYPE_NULL && value.data != Res_value::DATA_NULL_EMPTY) {
      // Walk through the style class values looking for the requested attribute.
      const ResolvedBag::Entry* entry = xml_style_attr_finder.Find(cur_ident);
      if (entry != xml_style_attr_finder.end()) {
        value = AssetManager2::SelectedValue(*xml_style_bag, *entry);
        value.flags |= xml_style_theme_flags;
        value_source_resid = entry->style;
        DEBUG_LOG("-> From style: type=0x%x, data=0x%08x, style=0x%08x", value.type, value.data,
                  value_source_resid);
      }
    }

    if (value.type == Res_value::TYPE_NULL && value.data != Res_value::DATA_NULL_EMPTY) {
      // Walk through the default style values looking for the requested attribute.
      const ResolvedBag::Entry* entry = def_style_attr_finder.Find(cur_ident);
      if (entry != def_style_attr_finder.end()) {
        value = AssetManager2::SelectedValue(*default_style_bag, *entry);
        value.flags |= def_style_theme_flags;
        value_source_resid = entry->style;
        DEBUG_LOG("-> From def style: type=0x%x, data=0x%08x, style=0x%08x", value.type, value.data,
                  entry->style);
      }
    }

    if (value.type != Res_value::TYPE_NULL) {
      // Take care of resolving the found resource to its final value.
      auto result = theme->ResolveAttributeReference(value);
      if (UNLIKELY(IsIOError(result))) {
        return base::unexpected(GetIOError(result.error()));
      }
      DEBUG_LOG("-> Resolved attr: type=0x%x, data=0x%08x", value.type, value.data);
    } else if (value.data != Res_value::DATA_NULL_EMPTY) {
      // If we still don't have a value for this attribute, try to find it in the theme!
      if (auto attr_value = theme->GetAttribute(cur_ident)) {
        value = *attr_value;
        DEBUG_LOG("-> From theme: type=0x%x, data=0x%08x", value.type, value.data);

        auto result = assetmanager->ResolveReference(value, true /* cache_value */);
        if (UNLIKELY(IsIOError(result))) {
          return base::unexpected(GetIOError(result.error()));
        }
        DEBUG_LOG("-> Resolved theme: type=0x%x, data=0x%08x", value.type, value.data);
        // TODO: set value_source_resid for the style in the theme that was used.
      }
    }

    // Deal with the special @null value -- it turns back to TYPE_NULL.
    if (value.type == Res_value::TYPE_REFERENCE && value.data == 0U) {
      DEBUG_LOG("-> Setting to @null!");
      value.type = Res_value::TYPE_NULL;
      value.data = Res_value::DATA_NULL_UNDEFINED;
      value.cookie = kInvalidCookie;
    }

    DEBUG_LOG("Attribute 0x%08x: type=0x%x, data=0x%08x", cur_ident, value.type, value.data);

    // Write the final value back to Java.
    out_values[STYLE_TYPE] = value.type;
    out_values[STYLE_DATA] = value.data;
    out_values[STYLE_ASSET_COOKIE] = ApkAssetsCookieToJavaCookie(value.cookie);
    out_values[STYLE_RESOURCE_ID] = value.resid;
    out_values[STYLE_CHANGING_CONFIGURATIONS] = value.flags;
    out_values[STYLE_DENSITY] = value.config.density;
    out_values[STYLE_SOURCE_RESOURCE_ID] = value_source_resid;

    if (value.type != Res_value::TYPE_NULL || value.data == Res_value::DATA_NULL_EMPTY) {
      // out_indices must NOT be nullptr.
      out_indices[++indices_idx] = ii;
    }
    out_values += STYLE_NUM_ENTRIES;
  }

  // out_indices must NOT be nullptr.
  out_indices[0] = indices_idx;
  return {};
}

base::expected<std::monostate, IOError> RetrieveAttributes(AssetManager2* assetmanager,
                                                           ResXMLParser* xml_parser,
                                                           uint32_t* attrs,
                                                           size_t attrs_length,
                                                           uint32_t* out_values,
                                                           uint32_t* out_indices) {
  int indices_idx = 0;

  // Retrieve the XML attributes, if requested.
  size_t ix = 0;
  const size_t xml_attr_count = xml_parser->getAttributeCount();
  uint32_t cur_xml_attr = xml_parser->getAttributeNameResID(ix);

  // Now iterate through all of the attributes that the client has requested,
  // filling in each with whatever data we can find.
  for (size_t ii = 0; ii < attrs_length; ii++) {
    const uint32_t cur_ident = attrs[ii];
     AssetManager2::SelectedValue value{};

    // Try to find a value for this attribute...
    // Skip through XML attributes until the end or the next possible match.
    while (ix < xml_attr_count && cur_ident > cur_xml_attr) {
      cur_xml_attr = xml_parser->getAttributeNameResID(++ix);
    }

    // Retrieve the current XML attribute if it matches, and step to next.
    if (ix < xml_attr_count && cur_ident == cur_xml_attr) {
      Res_value attribute_value{};
      xml_parser->getAttributeValue(ix, &attribute_value);
      value.type = attribute_value.dataType;
      value.data = attribute_value.data;
      cur_xml_attr = xml_parser->getAttributeNameResID(++ix);
    }

    if (value.type != Res_value::TYPE_NULL) {
      // Take care of resolving the found resource to its final value.
      auto result = assetmanager->ResolveReference(value);
      if (UNLIKELY(IsIOError(result))) {
        return base::unexpected(GetIOError(result.error()));
      }
    }

    // Deal with the special @null value -- it turns back to TYPE_NULL.
    if (value.type == Res_value::TYPE_REFERENCE && value.data == 0U) {
      value.type = Res_value::TYPE_NULL;
      value.data = Res_value::DATA_NULL_UNDEFINED;
      value.cookie = kInvalidCookie;
    }

    // Write the final value back to Java.
    out_values[STYLE_TYPE] = value.type;
    out_values[STYLE_DATA] = value.data;
    out_values[STYLE_ASSET_COOKIE] = ApkAssetsCookieToJavaCookie(value.cookie);
    out_values[STYLE_RESOURCE_ID] = value.resid;
    out_values[STYLE_CHANGING_CONFIGURATIONS] = value.flags;
    out_values[STYLE_DENSITY] = value.config.density;

    if (out_indices != nullptr &&
        (value.type != Res_value::TYPE_NULL ||
         value.data == Res_value::DATA_NULL_EMPTY)) {
      out_indices[++indices_idx] = ii;
    }

    out_values += STYLE_NUM_ENTRIES;
  }

  if (out_indices != nullptr) {
    out_indices[0] = indices_idx;
  }
  return {};
}

}  // namespace android
